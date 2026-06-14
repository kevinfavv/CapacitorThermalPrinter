# Contributing

Thanks for contributing to **@delicity/capacitor-thermal-printer**. This document
covers the repo layout, local development, tests, and implementation status. For
**using** the plugin in an app, see the [README](README.md).

## Folder structure

```
capacitor-thermal-printer/
├── src/                                  # Public TypeScript API
│   ├── index.ts                          # registerPlugin + exports
│   ├── definitions.ts                    # native contract (plugin interface)
│   ├── web.ts                            # web fallback (dev UI)
│   ├── core/
│   │   ├── enums.ts                      # transports, adapters, error codes
│   │   ├── models.ts                     # DiscoveredPrinter, PrinterProfile, Status…
│   │   ├── options.ts                    # discover/print/connect options
│   │   ├── errors.ts                     # PrinterError + normalization
│   │   └── imaging.ts                    # ESC/POS raster spec + dithering (TS ref.)
│   └── adapters/
│       ├── priority.ts                   # adapter priority engine
│       └── dedup.ts                       # stable id + duplicate merging
├── android/src/main/java/com/delicity/thermalprinter/
│   ├── ThermalPrinterPlugin.kt           # Capacitor bridge
│   ├── ThermalPrinterEngine.kt           # orchestration
│   ├── Logger.kt
│   ├── adapters/  (PrinterAdapter, EscPos, Epson, Star, Brother, Zebra, RawTcp, Ble, Usb, SdkReflect, SdkContract)
│   ├── transport/ (ByteTransport, TcpTransport, BluetoothSppTransport, BleGattClient)
│   ├── discovery/ (DiscoveryManager, TcpScanner, BluetoothClassicScanner, BleScanner, AdapterPriority)
│   ├── image/     (ImageProcessor, ImageCache, TextRasterizer)
│   ├── store/     (PrinterStore)
│   └── model/     (Models.kt)
├── ios/Plugin/
│   ├── ThermalPrinterPlugin.swift + .m   # Capacitor bridge
│   ├── ThermalPrinterEngine.swift
│   ├── Logger.swift
│   ├── Adapters/  (PrinterAdapter, EscPos, Epson, Star, Brother, Zebra, RawTcp)
│   ├── Transport/ (TcpTransport — Network.framework)
│   ├── Discovery/ (DiscoveryManager, BonjourScanner, AdapterPriority)
│   ├── Image/     (ImageProcessor, ImageCache, TextRasterizer)
│   ├── Store/     (PrinterStore)
│   └── Model/     (Models.swift)
└── docs/
    ├── SDK_INTEGRATION.md                # how to wire each manufacturer SDK
    └── TESTING_SDK.md                    # SDK connection test strategy
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     App (Ionic/JS/TS)                          │
│   discoverPrinters / connect / setDefault / printImage ...     │
└───────────────────────────────┬───────────────────────────────┘
                                 │  Single API (definitions.ts)
                 ┌───────────────┴───────────────┐
                 │      Capacitor Bridge          │
        ┌────────┴─────────┐           ┌──────────┴─────────┐
        │  Android (Kotlin) │           │    iOS (Swift)     │
        │  ThermalPrinter…  │           │  ThermalPrinter…   │
        └────────┬─────────┘           └──────────┬─────────┘
                 │ ThermalPrinterEngine            │ ThermalPrinterEngine
   ┌─────────────┼──────────────┐      ┌───────────┼──────────────┐
   │  Discovery  │   Adapters    │      │ Discovery │   Adapters    │
   │  Manager    │  (registry)   │      │  Manager  │  (registry)   │
   └─────────────┴──────────────┘      └───────────┴──────────────┘
           │                                    │
   ┌───────┴────────────────────────────────────┴─────────┐
   │ EscPos · Epson · Star · Brother · Zebra · RawTcp · BLE │
   │ Transport: TCP9100 / SPP(Android) / NWConnection(iOS)  │
   │ Image: decode → resize → grayscale → dither → raster   │
   │ Store: profiles + default printer (persisted)          │
   └────────────────────────────────────────────────────────┘
```

In short:

- One JS API (`definitions.ts`) → Capacitor bridge → `ThermalPrinterEngine`
  (Kotlin/Swift) → an **adapter registry** + a **discovery manager**.
- The app sends an **image**; the engine normalizes it (resize → grayscale → 1-bit +
  dithering) and routes it to the right adapter (ESC/POS raster, SDK `addImage`, ZPL…).
- Manufacturer SDKs are **optional**: Star = real dependency (typed calls); Epson/Brother
  = reflection on Android, `#if canImport` on iOS; **Zebra = reflection on Android, and an
  Objective-C runtime bridge on iOS** (its SDK is a static lib with no Swift module, so
  `canImport` can't see it). See [`docs/SDK_INTEGRATION.md`](docs/SDK_INTEGRATION.md).

## SDK activation & detection (internals)

> Implementation details — for contributors, not for app developers integrating the
> plugin. End-user setup lives in [`docs/SDK_INTEGRATION.md`](docs/SDK_INTEGRATION.md).

### How activation works

- **Android**: each SDK adapter tests for the binary via **reflection**
  (`Class.forName(...)` in `isAvailable()`), then drives the SDK by reflection
  (`SdkReflect.kt`). No compile-time dependency → the plugin compiles without the binary.
  *Exception*: **Star** is a real Maven dependency (typed calls).
- **iOS**: each SDK adapter uses **conditional compilation** `#if canImport(Module)`. If the
  module isn't linked, the typed body is replaced by an inert stub → the plugin compiles
  without the SDK, with no risk of breakage.
  *Exception*: **Zebra** ships a static lib + ObjC headers with **no Swift module**, so
  `canImport` can never see it. Its adapter goes through an **Objective-C runtime bridge**
  (`ios/Plugin/Adapters/ZebraBridge.{h,m}`) that resolves the SDK classes with
  `NSClassFromString` and calls them via protocol-typed `objc_msgSend` — **no compile-time
  symbol references**, so the plugin still links in apps without Zebra. The podspec injects
  `-ObjC` (so the linker keeps the static lib's classes) and links `ExternalAccessory` +
  `CoreBluetooth`. `ZebraBridge.isAvailable()` (i.e. `NSClassFromString != nil`) gates it.
- **iOS — the SDK only has to be added to the `App` target.** The adapters are compiled
  inside the `DelicityCapacitorThermalPrinter` pod, but the **podspec already sets
  `FRAMEWORK_SEARCH_PATHS`** so the pod sees whatever the app adds (Star SPM, Brother pod,
  Epson/Zebra xcframeworks). `#if canImport(...)` becomes true and the adapter activates
  automatically — no Podfile `post_install` or manual pod linking needed.

> ⚠️ **The reflective code (Android) and the gated iOS code are not checked by the repo's
> compiler** (the binaries aren't present). They are written against the SDKs' documented
> APIs and **must be tested on a real device** with the binary. If a manufacturer API
> changes, adjust the class/method/module names.

### Checking that an SDK is detected

```kotlin
// Android
EpsonAdapter(context).isAvailable()   // true if com.epson.epos2.printer.Printer is on the classpath
StarAdapter(context).isAvailable()    // true if com.starmicronics.stario10.StarPrinter is present
```
```swift
// iOS
EpsonAdapter().isAvailable()          // true if #if canImport(libepos2) (or Epos2Printer at runtime)
StarAdapter().isAvailable()           // true if #if canImport(StarIO10)
```

During discovery, missing SDK sources are reported in `discoveryComplete.failedSources`
(non-blocking diagnostic).

### How image printing concepts map per SDK

| Adapter | Discovery | Image printing | Cut | Status |
|---|---|---|---|---|
| **Star StarXpand** | `StarDeviceDiscoveryManager` | `PrinterBuilder.actionPrintImage(ImageParameter)` | `actionCut(.partial)` | `getStatus()` |
| **Epson ePOS2** | `Discovery.start` | `Printer.addImage(bitmap, …, MODE_MONO)` | `addCut(CUT_FEED)` | `PrinterStatusInfo` |
| **Brother** | `BRLMPrinterSearcher` | `driver.printImage(image, settings)` | settings (auto-cut) | `getPrinterStatus()` |
| **Zebra Link-OS** | `NetworkDiscoverer`/`BluetoothDiscoverer` | `GraphicsUtil.printImage(…)` → **ZPL** | media command | `getCurrentStatus()` |

#### `printText` per brand

`printText([...])` works on **all** brands:

| Target | `printText` implementation |
|---|---|
| ESC/POS (TCP/SPP/USB/BLE), **rawTcp** | native ESC/POS encoder (bytes) |
| **Star** (Android + iOS) | native StarXpand builder (`actionPrintText`, QR, barcode, cut) |
| **Epson Android** | native ePOS2 builder (`addText`/`addTextStyle`/`addSymbol`/`addBarcode`) |
| **Epson iOS, Brother, Zebra** | **automatic image fallback**: items are rendered by `TextRasterizer` then sent via the SDK's `printImage` |

Routing is automatic via `supportsTextItems()`: if an adapter can't map text natively, the
engine renders the items to a bitmap (monospace font, alignment/bold/underline/size,
separators) and prints them as an image. In the image fallback, QR/barcode items are rendered
as **text** — for a precise QR/barcode on Brother/Zebra, use `printImage` with a pre-rendered
visual.

## Local development

```bash
npm install
npm run build            # tsc + rollup
npm test                 # Vitest
npm run test:coverage
npm run lint             # eslint + prettier --check
npm run fmt              # eslint --fix + prettier --write
```

Native builds run from a host Capacitor app that includes the plugin (the Android
library depends on `:capacitor-android`):

```bash
# Android (from the host app)
./gradlew test
./gradlew testDebugUnitTest jacocoTestReport
#   → android/build/reports/jacoco/jacocoTestReport/html/index.html
# iOS
xcodebuild test -scheme DelicityCapacitorThermalPrinter -enableCodeCoverage YES
```

## Tests & quality

- **TypeScript (Vitest)** — pure business logic (imaging, ESC/POS text encoder,
  adapter priority, deduplication, errors, web fallback): **66 tests**, **~94% coverage**
  (CI thresholds: 85% lines/functions, 80% branches).
- **Android (JUnit)** — `android/src/test/...` validates the ESC/POS text and raster
  encoders (same byte-for-byte assertions as the TS tests).
- **SDK connection coverage** — reflection adapters (Epson/Zebra/Brother) covered via a
  **fake SDK on the test classpath** (Robolectric, no binary/printer) + JaCoCo. Example:
  `EpsonAdapterTest`, `SdkReflectTest`, `SdkContractTest`. See
  [`docs/TESTING_SDK.md`](docs/TESTING_SDK.md).
- **iOS (XCTest)** — `ios/Tests/...` validates the encoder (same vectors).
- **SDK integration tests** (layer 3) — on real hardware once the SDKs are linked.

> The three encoder implementations (TS/Kotlin/Swift) share the **same test vectors**,
> guaranteeing identical byte output across platforms.

## Implementation status

Legend: ✅ done & verifiable (TS/transports/plugin logic) · ✅◷ implemented, **needs
on-device validation** (native SDK code not compiled in this repo) · 🟡 partial.

| Area | Content | Status |
|---|---|---|
| Core | Plugin scaffold, TS types, adapter registry, default-printer store, **ESC/POS over Wi-Fi TCP 9100** | ✅ |
| Bluetooth | **Android Bluetooth Classic** (SPP) for ESC/POS | ✅ |
| Star SDK | auto-download (Maven/SPM), typed calls | ✅ |
| Epson SDK | Android reflection / iOS `canImport` | ✅◷ |
| Brother | Android reflection / iOS `canImport` (iOS via pod) | ✅◷ |
| Zebra | Android reflection / iOS **ObjC runtime bridge** (`ZebraBridge`, static lib + `-ObjC`) | ✅◷ |
| iOS | Wi-Fi TCP + Star/Epson SDK; **BLE via MFi SDK** (no generic GATT exposed) | ✅ |
| Monitoring | `start/stopStatusMonitor` + **backoff reconnection** + **hold recovery** | ✅ |
| Android transports | **BLE GATT** (MTU, UUID allowlist) + **USB host** (bulk OUT) | ✅◷ |
| Styled `printText` | native ESC/POS + Star native + Epson-Android native; Brother/Zebra/Epson-iOS → image fallback (`TextRasterizer`) | ✅ |
| Diagnostics | `getActiveSdks()` + job events + debug log | ✅ |

See [`ROADMAP.md`](ROADMAP.md) for the remaining work.

## Pull requests

- Keep the three encoder implementations (TS/Kotlin/Swift) byte-compatible (shared vectors).
- Never commit manufacturer SDK binaries (license-gated, git-ignored).
- Run `npm test` + `npm run lint` before opening a PR.
