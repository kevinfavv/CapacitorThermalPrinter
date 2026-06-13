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

## Architecture (in short)

- One JS API (`definitions.ts`) → Capacitor bridge → `ThermalPrinterEngine`
  (Kotlin/Swift) → an **adapter registry** + a **discovery manager**.
- The app sends an **image**; the engine normalizes it (resize → grayscale → 1-bit +
  dithering) and routes it to the right adapter (ESC/POS raster, SDK `addImage`, ZPL…).
- Manufacturer SDKs are **optional**: Star = real dependency (typed calls); Epson/
  Zebra/Brother = reflection on Android, `#if canImport` on iOS. See
  [`docs/SDK_INTEGRATION.md`](docs/SDK_INTEGRATION.md).

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
xcodebuild test -scheme DelicityThermalPrinter -enableCodeCoverage YES
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
| Brother / Zebra | Android reflection / iOS `canImport` (Brother iOS via pod) | ✅◷ |
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
