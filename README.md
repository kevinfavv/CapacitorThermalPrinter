# @delicity/capacitor-thermal-printer

> Capacitor **7** plugin for **thermal / receipt / label printing** — **multi-brand** (ESC/POS, Epson, Star, Brother, Zebra), **image-based**, with **aggregated discovery**, **deduplication**, **automatic reconnection** and a **single JavaScript API**.

For **any Capacitor app that needs to print** — point of sale, receipts, order tickets, shipping/label printing, kitchen slips, etc. The user taps "Add a printer", picks one from a list, runs a test print, and never has to touch the phone's Bluetooth/Wi-Fi settings again.

**Requires Capacitor 7** · Android (**`minSdk 26`**, `compileSdk 35`, JDK 21) · iOS 14+ / Xcode 16+.

> 🚧 **Work in progress.** This plugin is under active development and **not yet
> validated on real hardware** (see [Tested on real hardware](#tested-on-real-hardware)).
> APIs may still change. **Contributions are very welcome** — bug reports, hardware
> test feedback, and PRs. See [`CONTRIBUTING.md`](CONTRIBUTING.md).

---

## Contents

1. [Philosophy](#philosophy)
2. [Installation](#installation)
3. [Usage patterns](#usage-patterns)
4. [Manufacturer SDKs](#manufacturer-sdks)
5. [Permissions](#permissions)
6. [Public API](#public-api)
7. [Types](#types)
8. [Image printing flow](#image-printing-flow)
9. [Image processing](#image-processing)
10. [Aggregated discovery & adapter priority](#aggregated-discovery--adapter-priority)
11. [Default printer & reconnection](#default-printer--reconnection)
12. [Normalized errors](#normalized-errors)
13. [Android / iOS differences](#android--ios-differences)
14. [Image cache & logs/diagnostics](#image-cache--logsdiagnostics)
15. [Full example](#full-example)

---

## Philosophy

- **The app generates an image of what to print** (PNG/bitmap — receipt, ticket, label). It never sends structured text to the SDKs.
- The plugin **receives an image**, **normalizes** it (resize → grayscale → 1-bit + dithering), **converts** it to the adapter's format, and **sends** it.
- **One JS API.** Internally, an **adapter-based architecture** routes to the right implementation.
- **There is no universal protocol**: each family has its adapter. Adapter priority guarantees the best choice.

> 📁 Internal architecture, repo layout, tests and the contribution guide live in
> [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Installation

> Package: **`@delicity/capacitor-thermal-printer`** · Capacitor 7 · Android (**`minSdk 26`**, `compileSdk 35`, JDK 21) · iOS 14+ / Xcode 16+.

### Step by step

**1. Install the package**

```bash
npm install @delicity/capacitor-thermal-printer
```

**2. Sync the native projects**

```bash
npx cap sync
```

This adds the Android library to your Gradle project and runs `pod install` for iOS.

**3. iOS — add the required keys to your app's `Info.plist`**

At minimum, for Wi-Fi/network printers (see [Permissions](#permissions) for the full list):

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>Discover and print to printers on the local network.</string>
<key>NSBonjourServices</key>
<array>
  <string>_pdl-datastream._tcp</string>
  <string>_printer._tcp</string>
</array>
```

Then re-sync: `npx cap sync ios`.

**4. Android — set `minSdk 26`.** The bundled **Star** SDK (`stario10`) requires API 26, so
your app's `android/variables.gradle` must have `minSdkVersion = 26` (otherwise the manifest
merge fails). Otherwise nothing to configure: the plugin ships its own manifest permissions
(Bluetooth, network, USB feature) — just call `requestPermissions()` before scanning
(needed for Bluetooth on Android 12+).

**5. (Optional) Add a manufacturer SDK** — only if you use **Epson / Star / Brother /
Zebra** via their native SDK. Generic ESC/POS (Wi-Fi/Bluetooth/USB) and Star work
without extra setup; the others need a one-time binary drop. See
[Manufacturer SDKs](#manufacturer-sdks) and [`docs/SDK_INTEGRATION.md`](docs/SDK_INTEGRATION.md).

**6. Use it**

```ts
import { ThermalPrinter } from '@delicity/capacitor-thermal-printer';

await ThermalPrinter.requestPermissions();
const { printers } = await ThermalPrinter.discoverPrinters({ timeoutMs: 8000 });
// printImage auto-connects on demand (autoReconnect defaults to true) — no connectPrinter needed.
await ThermalPrinter.printImage({
  printerId: printers[0].id,
  image: { filePath: '/data/.../receipt.png' },
});
```

✅ **Works out of the box** for **Wi-Fi/Ethernet ESC/POS** printers — no SDK required.
For Bluetooth/USB/BLE and brand SDKs, see the sections below.

## Usage patterns

Pick the pattern that fits your UX. **You don't have to call `connectPrinter` to print** —
`printImage` / `printText` connect on demand (`autoReconnect: true` by default).

### A. One-shot — simplest

Discover, then print by `printerId`. The print call connects automatically.

```ts
const { printers } = await ThermalPrinter.discoverPrinters({ timeoutMs: 8000 });
await ThermalPrinter.printImage({ printerId: printers[0].id, image: { filePath } });
// → resolves the printer (from the latest discovery) + connects + prints
```

### B. Default printer — recommended for repeated use

**Once** (setup): let the user pick a printer, then connect & persist it as default
**only if the connection succeeds**. **Afterwards** (even after an app restart): print
without a `printerId` — the default profile is used and reconnected automatically.

```ts
// Setup (once), e.g. after a successful test print:
const { connected } = await ThermalPrinter.connectPrinter({ printerId, setAsDefault: true });

// Daily use — no printerId, no connect:
await ThermalPrinter.printImage({ image: { filePath } });        // uses default + auto-reconnect
await ThermalPrinter.printText({ items });                       // same
```

### C. Explicit connect — to test, read paper size, or show status

Use `connectPrinter` when you need a result **before** printing: a connection test,
the **paper size**, or a status badge.

```ts
const { connected, paper } = await ThermalPrinter.connectPrinter({ printerId });
if (!connected) return showError('Unreachable');
showLabel(paper?.widthMm ? `${paper.widthMm} mm` : 'Unknown width');
const status = await ThermalPrinter.getPrinterStatus({ printerId }); // paper/cover/online
await ThermalPrinter.printImage({ printerId, image: { filePath } });
```

### D. Manual connection management — `autoReconnect: false`

Opt out of on-demand connection (you manage `connectPrinter` / `disconnectPrinter`
yourself). Printing while disconnected then rejects with `CONNECTION_FAILED`.

```ts
await ThermalPrinter.connectPrinter({ printerId });
await ThermalPrinter.printImage({ printerId, image: { filePath }, autoReconnect: false });
await ThermalPrinter.disconnectPrinter({ printerId });
```

### Good to know

- **Resolution**: a `printerId` must be resolvable — either freshly discovered (this
  session) or a saved profile. Without `printerId`, the **default printer** is used.
- **Connection lifecycle**: connections are opened **just-in-time** before printing,
  not held open permanently. Reconnection uses exponential backoff (3 attempts).
- **`connectPrinter` is optional** — needed only to test, set the default, or read the
  paper size. See [Default printer & reconnection](#default-printer--reconnection).
- **Profiles**: `getSavedPrinters` / `getDefaultPrinter` / `setDefaultPrinter` /
  `removePrinter` manage persisted printers.

## Manufacturer SDKs

The plugin supports **Star, Epson, Brother, Zebra** via their native SDK, **optionally**:
it compiles and works **without any SDK** (generic ESC/POS over TCP/Bluetooth/USB/BLE),
and each brand **activates automatically** as soon as its binary is present.

> **Why isn't it 100% automatic on `npm install`?**
> Only SDKs published to a standard package repository download by themselves.
> The others are distributed only through the manufacturer's portal and their
> **license forbids redistribution** — so they cannot be put on Maven Central /
> CocoaPods, nor committed here. The consuming app downloads the binary itself
> (accepting the license); the plugin provides all the code to use it.

| Brand | Android | iOS | What to do in the app |
|---|---|---|---|
| **Star** | ✅ auto (Maven Central) | ✅ auto (SPM) | Add the `StarXpand-SDK-iOS` SPM package (iOS). Android: nothing. |
| **Brother** | ⛔ manual `.aar` | ✅ auto (CocoaPods) | `pod 'BRLMPrinterKit'` (iOS); drop `BrotherPrintLibrary.aar` (Android). |
| **Epson** | ⛔ manual `.jar`+`.so` | ⛔ manual xcframework | Drop `ePOS2.jar` (Android) / `libepos2.xcframework` (iOS). |
| **Zebra** | ⚠️ private Maven (token) or `.jar` | ⛔ manual xcframework | Zebra token or `ZSDK_ANDROID_API.jar`; `ZSDK_API.xcframework` (iOS). |

**➡️ Full installation guide: [`docs/SDK_INTEGRATION.md`](docs/SDK_INTEGRATION.md).**
It covers everything per brand — official download links, where to drop each binary,
the Star SPM package, CocoaPods/Podfile setup, the Zebra private Maven repo, iOS
module names, and the git-ignored test folder.

### Tested on real hardware

> ✅ **Verified on real hardware (iOS + Android):** Epson over Bluetooth & network, generic
> BLE, and generic Bluetooth Classic (Android) all print full tickets end-to-end — logo +
> styled text + **scannable QR** + cut. See the on-device notes in
> [`CONTRIBUTING.md`](CONTRIBUTING.md#verified-on-a-real-iphone-manual-june-2026).

| Target | On-device tested (iOS) | On-device tested (Android) |
|---|---|---|
| **Epson** — Bluetooth (MFi iOS / ePOS2 Android) | ✅ **Tested on iOS** | ✅ **Tested on Android** |
| **Epson** — Network (Wi-Fi / TCP) | ✅ **Tested on iOS** | ✅ **Tested on Android** |
| **Generic BLE** ESC/POS (e.g. MP210) | ✅ **Tested on iOS** | ✅ **Tested on Android** |
| **Generic Bluetooth Classic** (SPP) ESC/POS | ⛔ N/A (no iOS API) | ✅ **Tested on Android** |
| Network ESC/POS (Wi-Fi / Bonjour / TCP 9100) | ✅ **Tested on iOS** | ✅ **Tested on Android** |
| **Star** | ⏳ planned | ⏳ planned |
| **Brother** | ⏳ planned | ⏳ planned |
| **Zebra** | ⏳ planned | ⏳ planned |

> Bluetooth/BLE and MFi can't run on the iOS Simulator, so these are validated **manually on
> device**; the TCP path is covered automatically in CI. On **iOS, generic Bluetooth Classic
> (SPP) is not possible** (Apple exposes no API) — use **BLE**, **MFi (brand SDK)** or **Wi-Fi**.
> On **Android**, generic Bluetooth Classic (SPP) **is** supported and tested.

### Know which SDKs are active (runtime)

`getActiveSdks()` reports, at the current moment, which adapters/SDKs are available:

```ts
import { ThermalPrinter } from '@delicity/capacitor-thermal-printer';

const { sdks } = await ThermalPrinter.getActiveSdks();
// [
//   { adapter: 'escpos', label: 'Generic ESC/POS', available: true,  requiresSdk: false, transports: ['wifi','ethernet','bluetooth','usb'] },
//   { adapter: 'star',   label: 'Star StarXpand',  available: true,  requiresSdk: true,  transports: ['wifi','bluetooth','ble','usb'] },
//   { adapter: 'epson',  label: 'Epson ePOS2',     available: false, requiresSdk: true,  transports: ['wifi','bluetooth','usb'] },
//   ...
// ]
const active = sdks.filter(s => s.available).map(s => s.label);
```

Useful for a "Printer diagnostics" screen, or to only show the brands actually
available on the device.

## Permissions

### Android (plugin `AndroidManifest.xml`, already provided)

| Permission | Use | API |
|---|---|---|
| `BLUETOOTH_SCAN` (`neverForLocation`) | BT/BLE scan | 31+ |
| `BLUETOOTH_CONNECT` | SPP/GATT connection | 31+ |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | scan/connection | ≤30 |
| `ACCESS_FINE_LOCATION` | BLE scan | ≤30 |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | TCP 9100 + network detection | all |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS | all |
| `android.hardware.usb.host` (feature) | USB | optional |

Call `requestPermissions()` before the first scan.

### iOS (host app `Info.plist`)

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>Discover and print to printers on the local network.</string>
<key>NSBonjourServices</key>
<array>
  <string>_pdl-datastream._tcp</string>
  <string>_printer._tcp</string>
  <string>_ipp._tcp</string>
</array>
<!-- If BLE is enabled: -->
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Connect to compatible Bluetooth printers.</string>
<!-- If using an MFi SDK (Epson/Star/Zebra Bluetooth): declare the protocols -->
<key>UISupportedExternalAccessoryProtocols</key>
<array>
  <string>com.epson.escpos</string>
  <string>jp.star-m.starpro</string>
</array>
```

## Public API

```ts
import { ThermalPrinter } from '@delicity/capacitor-thermal-printer';

ThermalPrinter.discoverPrinters(options?)   // → { printers: DiscoveredPrinter[] }
ThermalPrinter.connectPrinter({ printerId, timeoutMs?, forceAdapter?, setAsDefault? })  // → { connected, paper: PaperInfo | null }
ThermalPrinter.disconnectPrinter({ printerId })                          // → void
ThermalPrinter.setDefaultPrinter({ printerId })                          // → { profile }
ThermalPrinter.getDefaultPrinter()                                       // → { profile | null }
ThermalPrinter.getSavedPrinters()                                        // → { profiles }
ThermalPrinter.removePrinter({ printerId })                              // → void
ThermalPrinter.printImage(options)                                       // → PrintResult (await = printed)
ThermalPrinter.printText({ items, ... })                                 // → PrintResult (await = printed)
ThermalPrinter.getPrinterStatus({ printerId? })                          // → PrinterStatus
ThermalPrinter.requestPermissions() / checkPermissions()                 // → PermissionStatus
ThermalPrinter.startStatusMonitor({ printerId, intervalMs? })            // background status polling
ThermalPrinter.stopStatusMonitor({ printerId })
ThermalPrinter.getActiveSdks()                                           // → { sdks: SdkStatus[] }
ThermalPrinter.getDebugLog()                                             // → { log: DebugLogEntry[] }

// Events
ThermalPrinter.addListener('printerFound', e => ...)        // incremental scan results
ThermalPrinter.addListener('discoveryComplete', e => ...)
ThermalPrinter.addListener('statusChange', e => ...)        // PrinterStatus (paper/cover/connection)
ThermalPrinter.addListener('printJobStatus', e => ...)      // JobState: pending/printing/hold/completed/failed
```

> **`connectPrinter({ setAsDefault: true })`** sets the default printer **only if
> the connection succeeds** (`connect` + `setDefaultPrinter` in one step, without
> persisting an unreachable printer).

> **Paper size on connect.** `connectPrinter` also returns `paper` — the paper size
> deduced from the printer's model (**best-effort**), or **`null`** when it can't be
> determined (typical for generic ESC/POS). The printer hardware does not report
> remaining/printed length; only the width is derivable. Example:
> ```ts
> const { connected, paper } = await ThermalPrinter.connectPrinter({ printerId });
> // paper?.widthMm → 80 | 58 | … | null
> if (paper?.widthMm) showLabel(`${paper.widthMm} mm`); else showLabel('Unknown width');
> ```

### Print completion / `await`

`printImage` and `printText` are **async and resolve when physical printing is done**
(best-effort) — so you can `await` them. Details:

- **Manufacturer SDKs**: the promise waits for the SDK's **completion callback** (max reliability).
- **ESC/POS TCP/SPP**: a **one-way** channel → the promise resolves once all bytes are
  **written and flushed**. A **status pre-check** runs before sending: paper empty /
  cover open → job set to `hold` + rejection `PAPER_EMPTY` / `COVER_OPEN`
  (`retryable: true`).

## Types

```ts
type PrinterTransport = 'wifi' | 'ethernet' | 'bluetooth' | 'ble' | 'usb';
type PrinterAdapterId = 'escpos' | 'epson' | 'star' | 'brother' | 'zebra' | 'rawTcp';

interface PrinterCapabilities {
  paperWidthMm: number;        // 58 | 80 | 112…
  printableDots: number;       // 384 (58mm) | 576 (80mm) | 832 (112mm)
  dpi: number;                 // 203 most of the time
  supportsCut: boolean;
  supportsCashDrawer: boolean;
  supportsStatus: boolean;
  supportsRasterImage: boolean;
  supportsQrCode?: boolean;
  supportsBarcode?: boolean;
}

interface DiscoveredPrinter {
  id: string;                  // stable id: "wifi:192.168.1.50", "bluetooth:AA:BB:.."
  name: string;
  brand?: string; model?: string;
  transport: PrinterTransport;
  adapter: PrinterAdapterId;   // resolved by priority
  address: string;             // "ip:port" | MAC | UUID
  capabilities?: Partial<PrinterCapabilities>;
  discoveredBy?: PrinterAdapterId[];
  lastSeenAt: number;
  isDefault: boolean;
  isConnected: boolean;
}

interface PrinterProfile {
  id: string;
  adapter: PrinterAdapterId;
  transport: PrinterTransport;
  address: string;
  brand?: string; model?: string;
  name: string;
  capabilities: PrinterCapabilities;
  defaultPrintOptions?: PrintRenderOptions;
  adapterMeta?: Record<string, string | number | boolean>;
  isDefault: boolean;
  createdAt: number; updatedAt: number;
}

// ---- States / statuses ----
type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'error';
type PaperStatus = 'ok' | 'near_end' | 'empty' | 'unknown';
type JobState = 'pending' | 'printing' | 'hold' | 'completed' | 'failed' | 'canceled';
type HoldReason = 'paper_empty' | 'paper_near_end' | 'cover_open' | 'buffer_full' | 'offline' | 'unknown';

interface PrinterStatus {
  id: string;
  connection: ConnectionState;
  online: boolean;
  paper: PaperStatus;
  coverOpen?: boolean;
  errorCode?: PrintErrorCode;
  rawStatus?: string;
  checkedAt: number;
}

interface PrintJobStatus {
  jobId: string;
  printerId: string;
  state: JobState;
  holdReason?: HoldReason;
  progress?: number;        // 0..1 (best-effort)
  errorCode?: PrintErrorCode;
  message?: string;
  updatedAt: number;
}

interface PrintResult {
  success: boolean;
  printerId: string;
  adapter: PrinterAdapterId;
  jobId: string;            // correlated with printJobStatus events
  state: JobState;          // 'completed' on success
  bytesSent?: number;
  durationMs?: number;
  status?: PrinterStatus;
}

// ---- Image print options ----
type DitheringAlgorithm = 'none' | 'floyd_steinberg' | 'atkinson';
type ImageAlign = 'left' | 'center' | 'right';

interface ImageSource { filePath?: string; url?: string; base64?: string; } // exactly one key

interface PrintRenderOptions {
  widthDots?: number;       // otherwise derived from the profile (384/576/832)
  resize?: boolean;         // default true; false = image already at the right width
  grayscale?: boolean;      // default true; false = image already 1-bit (simple threshold)
  threshold?: number;       // default 128 (when dithering 'none' or grayscale false)
  dithering?: DitheringAlgorithm; // default 'floyd_steinberg'
  align?: ImageAlign;       // default 'center'
  invert?: boolean;
  cut?: boolean;            // default true
  feedLines?: number;       // default 3
  openCashDrawer?: boolean;
  copies?: number;          // default 1
}

interface PrintImageOptions {
  printerId?: string;       // otherwise the default printer
  image: ImageSource;
  render?: PrintRenderOptions;
  timeoutMs?: number;       // default 15000
  autoReconnect?: boolean;  // default true
}

// ---- SDK status ----
interface SdkStatus {
  adapter: PrinterAdapterId;
  label: string;
  available: boolean;       // detected & usable right now
  requiresSdk: boolean;     // true for brand SDKs, false for built-in adapters
  transports: PrinterTransport[];
}

// ---- Paper size (returned by connectPrinter, best-effort) ----
interface PaperInfo {
  widthMm: number | null;        // 58 | 80 | 112 … (null if unknown)
  printableDots: number | null;  // 384 | 576 | 832 … @203 dpi (null if unknown)
  dpi: number | null;            // 203 when width is known
  source: 'model' | 'sdk' | 'profile';
}

// ---- Events ----
interface PrinterFoundEvent { printer: DiscoveredPrinter; }
interface DiscoveryCompleteEvent { printers: DiscoveredPrinter[]; failedSources?: string[]; }
interface StatusChangeEvent { status: PrinterStatus; }
interface PrintJobStatusEvent { job: PrintJobStatus; }
```

### `printText` types

```ts
type TextAlign = 'left' | 'center' | 'right';
type Underline = 'none' | 'single' | 'double';
type EscPosFont = 'A' | 'B';
type CodePage = 'CP437' | 'CP850' | 'CP858' | 'WPC1252' | 'CP852' | 'CP866'; // Latin/Western: WPC1252
type CjkEncoding = 'GB18030' | 'GBK' | 'Shift_JIS' | 'EUC-KR' | 'Big5';       // Chinese/Japanese/Korean
type TextEncoding = CodePage | CjkEncoding; // default 'WPC1252' (French/Latin). CJK -> printer FS & mode (native)
type BarcodeSymbology = 'UPC_A'|'UPC_E'|'EAN13'|'EAN8'|'CODE39'|'ITF'|'CODABAR'|'CODE93'|'CODE128';
type HriPosition = 'none' | 'above' | 'below' | 'both';
type QrErrorCorrection = 'L' | 'M' | 'Q' | 'H';

interface TextStyle {
  align?: TextAlign;
  bold?: boolean;
  underline?: Underline;
  font?: EscPosFont;
  widthMultiplier?: number;   // 1..8
  heightMultiplier?: number;  // 1..8
  doubleStrike?: boolean;
  invert?: boolean;           // white on black
  upsideDown?: boolean;
  rotate90?: boolean;
  letterSpacing?: number;     // dots
  lineSpacing?: number;       // dots (otherwise default)
  encoding?: TextEncoding;    // per-item encoding (Latin page or CJK charset)
  codePage?: CodePage;        // deprecated alias of `encoding` (Latin only)
  codePageId?: number;        // raw ESC t n override (Latin only)
  newline?: boolean;          // default true
}

type PrintItem =
  | { type: 'text'; value: string; style?: TextStyle }
  | { type: 'feed'; lines?: number }
  | { type: 'cut'; mode?: 'full' | 'partial'; feedBefore?: number }
  | { type: 'divider'; char?: string; columns?: number; style?: Pick<TextStyle,'align'|'bold'|'font'> }
  | { type: 'qrcode'; value: string; size?: number; errorCorrection?: QrErrorCorrection; align?: TextAlign }
  | { type: 'barcode'; value: string; symbology: BarcodeSymbology; height?: number; width?: number; hri?: HriPosition; align?: TextAlign }
  | { type: 'cashDrawer'; pin?: 2 | 5 }
  | { type: 'image'; image: ImageSource; render?: PrintRenderOptions }
  | { type: 'raw'; bytesBase64: string };

interface PrintTextOptions {
  printerId?: string;
  items: PrintItem[];
  encoding?: TextEncoding;    // text encoding: Latin page ('WPC1252' default) or CJK ('GB18030'…)
  defaultCodePage?: CodePage; // deprecated alias of `encoding` (Latin only)
  cut?: boolean;              // default false
  feedLines?: number;         // default 3
  timeoutMs?: number;
  autoReconnect?: boolean;
}
```

## Image printing flow

`printImage` performs exactly:

1. Resolve the target printer (otherwise the **default printer**).
2. Check whether it is connected.
3. If not, **automatic reconnection** (when `autoReconnect`, default `true`).
4. **Open the image** (`filePath` > `url` (cached) > `base64`).
5. **Resize** to the exact width (`widthDots` or the profile's capabilities).
6. **Grayscale** (BT.601 luminance), flatten onto a white background (transparent PNG).
7. **Dithering** (Floyd-Steinberg by default, Atkinson, or threshold).
8. **Convert to the adapter** (`GS v 0` raster for ESC/POS, `addImage` for SDKs, ZPL for Zebra).
9. **Send** (in transport-sized chunks).
10. **Feed + cut** (if supported) + optional cash drawer.
11. **Normalized result** + best-effort status read.

```ts
await ThermalPrinter.printImage({
  // printerId omitted → default printer
  image: { filePath: '/data/.../receipt.png' },   // recommended in production
  render: { dithering: 'floyd_steinberg', cut: true, feedLines: 3, align: 'center' },
  timeoutMs: 15000,
  autoReconnect: true,
});
```

### Concrete image-printing examples

```ts
// 1) Local file (RECOMMENDED in production) — most reliable/performant
await ThermalPrinter.printImage({ image: { filePath: '/data/user/0/app/files/receipt.png' } });

// 2) Remote URL — downloaded and cached by the plugin
await ThermalPrinter.printImage({
  image: { url: 'https://api.example.com/receipts/123/render.png' },
  render: { dithering: 'atkinson', cut: true },
});

// 3) base64 (handy for tests, less performant)
await ThermalPrinter.printImage({ image: { base64: 'iVBORw0KGgoAAAANS...' } });

// 4) Image ALREADY rendered server-side at the right width and as 1-bit black/white:
//    disable resize + grayscale → pixel-perfect, faster send.
await ThermalPrinter.printImage({
  image: { filePath: '/data/.../receipt_576px_1bit.png' },
  render: { resize: false, grayscale: false, cut: true },
});

// 5) Target a specific printer + 2 copies + cash drawer
await ThermalPrinter.printImage({
  printerId: 'wifi:192.168.1.50',
  image: { filePath: '/data/.../receipt.png' },
  render: { widthDots: 576, copies: 2, openCashDrawer: true },
});

// 6) await = printed (best-effort); typed error handling
try {
  const res = await ThermalPrinter.printImage({ image: { filePath } });
  console.log('Printed', res.jobId, res.bytesSent, 'bytes in', res.durationMs, 'ms');
} catch (e) {
  if ((e as PrinterError).code === PrintErrorCode.PAPER_EMPTY) alert('Out of paper');
}
```

> **`resize`/`grayscale` are optional**: if your server already produces a PNG at the
> exact width (`576px`/`384px`) and 1-bit black/white, pass
> `render: { resize: false, grayscale: false }`. The plugin then applies a simple
> threshold (no dithering) and does not alter the geometry.

## Text printing (`printText`)

`printText` accepts an **ordered array of typed items**. Ideal for purely textual
output, with no server-side pre-rendering.

```ts
await ThermalPrinter.printText({
  defaultCodePage: 'WPC1252', // Western/Latin-1 accents
  items: [
    { type: 'text', value: 'MY STORE', style: { align: 'center', bold: true, widthMultiplier: 2, heightMultiplier: 2 } },
    { type: 'text', value: '12 Main Street', style: { align: 'center' } },
    { type: 'divider', char: '-' },
    { type: 'text', value: 'Order #1042', style: { bold: true } },
    { type: 'text', value: 'Item A...........12.00' },
    { type: 'text', value: 'Item B........... 2.00' },
    { type: 'divider' },
    { type: 'text', value: 'TOTAL  14.00', style: { align: 'right', bold: true, widthMultiplier: 2 } },
    { type: 'feed', lines: 1 },
    { type: 'qrcode', value: 'https://example.com/order/1042', size: 6, align: 'center' },
    { type: 'barcode', value: '4006381333931', symbology: 'EAN13', hri: 'below' },
    { type: 'cut', mode: 'partial', feedBefore: 3 },
  ],
});
```

### Supported styles (ESC/POS) and SDK mapping

| Style / item | ESC/POS (escpos, rawTcp) | Epson ePOS2 | Star StarXpand | Brother | Zebra (ZPL) |
|---|:--:|:--:|:--:|:--:|:--:|
| `align` (left/center/right) | ✅ `ESC a` | ✅ | ✅ | ✅ | ✅ (field) |
| `bold` | ✅ `ESC E` | ✅ | ✅ | ✅ | ⚠️ via font |
| `underline` (single/double) | ✅ `ESC -` | ✅ | ✅ | ⚠️ | ❌ |
| `font` A/B | ✅ `ESC M` | ✅ | ✅ | ⚠️ | ⚠️ |
| `widthMultiplier`/`heightMultiplier` (1..8) | ✅ `GS !` | ✅ | ✅ | ✅ | ✅ (size) |
| `doubleStrike` | ✅ `ESC G` | ✅ | ⚠️ | ❌ | ❌ |
| `invert` (white/black) | ✅ `GS B` | ✅ | ✅ | ⚠️ | ✅ (reverse) |
| `upsideDown` | ✅ `ESC {` | ✅ | ⚠️ | ❌ | ✅ |
| `rotate90` | ✅ `ESC V` | ✅ | ⚠️ | ⚠️ | ✅ |
| `letterSpacing` | ✅ `ESC SP` | ✅ | ⚠️ | ❌ | ⚠️ |
| `lineSpacing` | ✅ `ESC 3` | ✅ | ✅ | ⚠️ | ✅ |
| `codePage` (accents) | ✅ `ESC t` | ✅ | ✅ | ✅ | ✅ |
| `qrcode` | ✅ `GS ( k` | ✅ native | ✅ native | ✅ native | ✅ `^BQ` |
| `barcode` (EAN/CODE128…) | ✅ `GS k` | ✅ native | ✅ native | ✅ native | ✅ `^BC`… |
| `divider` / `feed` / `cut` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `cashDrawer` | ✅ `ESC p` | ✅ | ✅ | ⚠️ | ⚠️ |
| `image` (inline) | ✅ raster | ✅ addImage | ✅ actionPrintImage | ✅ printImage | ✅ ^GF |
| `raw` (raw bytes) | ✅ | ⚠️ | ⚠️ | ❌ | ⚠️ (raw ZPL) |

> ✅ supported · ⚠️ partial/model-dependent equivalent · ❌ not available.
> Styles not supported by an SDK are **ignored gracefully** (never a hard failure).
> The reference ESC/POS encoder lives in `src/core/escpos-text.ts` (tested), mirrored
> in Kotlin (`EscPosTextEncoder.kt`) and Swift (`EscPosTextEncoder.swift`).

> **`printText` per brand.** It works on all brands: ESC/POS and **Star** (both
> platforms) and **Epson Android** map text to a native builder; **Epson iOS,
> Brother, Zebra** fall back automatically to **rendering the items to an image**
> (`TextRasterizer`) printed via the SDK's image path. See `docs/SDK_INTEGRATION.md`.

## Client-side events & status

```ts
// Job tracking: pending → printing → completed | hold | failed
const jobSub = await ThermalPrinter.addListener('printJobStatus', ({ job }) => {
  switch (job.state) {
    case 'printing': showSpinner(job.progress); break;
    case 'hold':     toast(job.holdReason === 'paper_empty' ? 'Add paper' : 'Cover open'); break;
    case 'completed': hideSpinner(); break;
    case 'failed':   alert(`Failed: ${job.errorCode}`); break;
  }
});

// Printer status (connection, paper, cover)
const statusSub = await ThermalPrinter.addListener('statusChange', ({ status }) => {
  updateBadge(status.online, status.paper); // 'ok' | 'near_end' | 'empty' | 'unknown'
});

// ... later
await jobSub.remove();
await statusSub.remove();
```

## Image processing

- **Reference widths @203 dpi**: `58mm → 384 px`, `80mm → 576 px`, `112mm → 832 px`. Some 80mm models print `640 px`: **always prefer the profile/SDK `printableDots`** when known.
- **Pipeline**: proportional resize to the target width → grayscale → binarization.
- **Dithering**:
  - `none` (threshold): crisp for text/lines.
  - `floyd_steinberg` (**default**): logos/photos.
  - `atkinson`: more contrast, pleasant on receipts.
- **ESC/POS raster**: `GS v 0` command (`0x1D 0x76 0x30 m xL xH yL yH data`), width padded to a multiple of 8, MSB = leftmost pixel. Testable reference implementation in `src/core/imaging.ts`, mirrored in Kotlin (`ImageProcessor.kt`) and Swift (`ImageProcessor.swift`).

## Aggregated discovery & adapter priority

Several sources run **in parallel**: Epson/Star/Brother/Zebra SDKs, TCP 9100 scan, Bluetooth Classic (Android), BLE (allowlisted services), USB (Android). Results are **merged** by stable `id` and **deduplicated**.

**Priority rules** (`priority.ts` / `AdapterPriority.kt` / `.swift`):

| Case | Selected adapter | Score |
|---|---|---|
| Printer recognized by an official SDK | `epson` / `star` / `brother` | 880–900 |
| **Zebra** | **`zebra` only** (ESC/POS banned) | 1000 / −1000 |
| ESC/POS confirmed over Bluetooth (Android) | `escpos` | 620 |
| ESC/POS confirmed over TCP | `escpos` | 600 |
| BLE with a usable service | (BLE) | 500 |
| Unidentified network device | `rawTcp` | 300 |

## Default printer & reconnection

- After a **successful test print**, the app calls `setDefaultPrinter({ printerId })`: the plugin **persists a `PrinterProfile`** (id, adapter, transport, address, brand, model, paper width, `printableDots`, dpi, cut options, reconnection metadata).
- On **startup** or **before printing**, the plugin reloads this profile.
- **Reconnection is not a permanent connection**: it is attempted **just before `printImage`** (step 3). This avoids keeping a socket/Bluetooth link open needlessly and improves reliability for occasional printing. It uses **exponential backoff** (up to 3 attempts) and detects recovery after a `hold` (paper reloaded / cover closed / back online).

## Normalized errors

Every rejected promise carries a **stable code** (`error.code`):

`PRINTER_NOT_FOUND`, `PRINTER_OFFLINE`, `CONNECTION_FAILED`, `PERMISSION_DENIED`, `BLUETOOTH_DISABLED`, `WIFI_NOT_CONNECTED`, `PAIRING_REQUIRED`, `UNSUPPORTED_TRANSPORT`, `UNSUPPORTED_PRINTER`, `IMAGE_INVALID`, `IMAGE_TOO_LARGE`, `PRINT_FAILED`, `PAPER_EMPTY`, `COVER_OPEN`, `SDK_NOT_AVAILABLE`, `TIMEOUT`, `UNKNOWN`.

```ts
import { PrinterError, PrintErrorCode } from '@delicity/capacitor-thermal-printer';
try { await ThermalPrinter.printImage({ image: { filePath } }); }
catch (e) {
  const err = e as PrinterError; // { code, message, detail, retryable }
  if (err.code === PrintErrorCode.PAPER_EMPTY) showPaperAlert();
}
```

## Android / iOS differences

### Android — broad hardware coverage
- Modern Bluetooth permissions (12+) handled.
- **Bluetooth Classic / SPP**: supported → covers the very common generic ESC/POS printers. ✅
- BLE supported (UUID allowlist recommended).
- Retrieval of **already-paired devices** (instant, no scan).
- TCP 9100 (Wi-Fi/Ethernet). ✅
- USB host (optional).

### iOS — Apple constraints
- ❌ **No generic Bluetooth Classic / SPP.** Apple exposes no API for it; a Classic "no-name"
  BT printer (visible but un-connectable in iOS Settings) **is not addressable** — unless it
  also exposes a BLE service (many cheap printers are Classic **+** BLE).
- ✅ **Generic BLE** (CoreBluetooth) — the plugin ships a generic BLE adapter that scans known
  ESC/POS BLE services, connects and prints. ✅📱 **verified on iPhone** (MP210: logo + text + QR).
- ✅ **MFi manufacturer SDKs** (Epson/Star/Brother/Zebra) for Bluetooth/MFi. ✅📱 **Epson verified
  on iPhone** over Bluetooth (MFi). Requires the brand's MFi protocol string in `Info.plist`.
- ✅ **Wi-Fi TCP** (port 9100) via `Network.framework` → triggers the **Local Network** prompt.
- ❌ No USB host for this use case.

> On iOS, for Bluetooth: **BLE (generic) or MFi (brand SDK)** — never generic Classic/SPP.
> Wi-Fi works for everyone.

## Image cache & logs/diagnostics

- **Cache**: `url` images are downloaded into `cache/thermal-images/` (key = URL hash, 32 MB quota, LRU eviction). The `filePath` mode remains the most reliable.
- **Logs**: in-memory ring buffer (500 lines) + Logcat/os_log. Retrievable via `getDebugLog()` for a "Diagnostics" screen attachable to support tickets. Never raw image data (only dimensions/byte counts).

> Implementation status, tests and development setup live in
> [`CONTRIBUTING.md`](CONTRIBUTING.md) · roadmap in [`ROADMAP.md`](ROADMAP.md).

## Full example

```ts
import { ThermalPrinter, PrinterError } from '@delicity/capacitor-thermal-printer';

// 1) Discovery (with incremental results)
const sub = await ThermalPrinter.addListener('printerFound', e => {
  console.log('Found:', e.printer.name, e.printer.adapter);
});
await ThermalPrinter.requestPermissions();
const { printers } = await ThermalPrinter.discoverPrinters({ timeoutMs: 8000 });
await sub.remove();

// 2) Connect, set as default IF it succeeds, then test print
const target = printers[0];
await ThermalPrinter.connectPrinter({ printerId: target.id, setAsDefault: true });
await ThermalPrinter.printImage({ printerId: target.id, image: { base64: testReceiptBase64 } });

// 3) Later: simple print (default printer + auto reconnection)
await ThermalPrinter.printImage({ image: { filePath: '/data/.../receipt.png' } });

// 4) Or styled text printing
await ThermalPrinter.printText({
  items: [
    { type: 'text', value: 'Thank you!', style: { align: 'center', bold: true } },
    { type: 'cut' },
  ],
});
```

---

## License

MIT © Delicity
