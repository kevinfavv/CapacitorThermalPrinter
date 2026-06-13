# @resto/capacitor-thermal-printer

> Plugin Capacitor **7** d'impression thermique **multi-marques** (ESC/POS, Epson, Star, Brother, Zebra) **par image**, avec **découverte agrégée**, **dédoublonnage**, **reconnexion automatique** et **API JavaScript unique**.

Conçu pour la **restauration** : le commerçant clique sur « Ajouter une imprimante », choisit dans une liste, fait un test d'impression, puis n'a plus jamais à toucher aux réglages Bluetooth/Wi-Fi du téléphone.

---

## Sommaire

1. [Philosophie](#philosophie)
2. [Architecture](#architecture)
3. [Installation](#installation)
4. [Permissions](#permissions)
5. [API publique](#api-publique)
6. [Types](#types)
7. [Flux d'impression d'image](#flux-dimpression-dimage)
8. [Conversion image → 1-bit & largeurs](#conversion-image--1-bit--largeurs)
9. [Découverte agrégée & priorité d'adapter](#découverte-agrégée--priorité-dadapter)
10. [Imprimante par défaut & reconnexion](#imprimante-par-défaut--reconnexion)
11. [Erreurs normalisées](#erreurs-normalisées)
12. [Différences Android / iOS](#différences-android--ios)
13. [Cache image & logs/diagnostic](#cache-image--logsdiagnostic)
14. [Intégration des SDK fabricants](#intégration-des-sdk-fabricants)
15. [Plan d'implémentation par phases](#plan-dimplémentation-par-phases)
16. [Exemple complet](#exemple-complet)

---

## Philosophie

- **L'app génère une image du ticket** (PNG/bitmap). Elle n'envoie jamais de texte structuré aux SDK.
- Le plugin **reçoit une image**, la **normalise** (resize → niveaux de gris → 1-bit + dithering), la **convertit au format de l'adapter** et l'**envoie**.
- **Une seule API JS.** En interne, une **architecture par adapters** route vers la bonne implémentation.
- **Il n'existe pas de protocole universel** : chaque famille a son adapter. La priorité d'adapter garantit le meilleur choix.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     App (Ionic/JS/TS)                          │
│   discoverPrinters / connect / setDefault / printImage ...     │
└───────────────────────────────┬───────────────────────────────┘
                                 │  API unique (definitions.ts)
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
   │ Store: profils + imprimante par défaut (persistés)     │
   └────────────────────────────────────────────────────────┘
```

### Structure des dossiers

```
capacitor-thermal-printer/
├── src/                                  # API TypeScript publique
│   ├── index.ts                          # registerPlugin + exports
│   ├── definitions.ts                    # contrat natif (interface plugin)
│   ├── web.ts                            # fallback web (dev UI)
│   ├── core/
│   │   ├── enums.ts                      # transports, adapters, codes erreur
│   │   ├── models.ts                     # DiscoveredPrinter, PrinterProfile, Status…
│   │   ├── options.ts                    # options discover/print/connect
│   │   ├── errors.ts                     # PrinterError + normalisation
│   │   └── imaging.ts                    # spec raster ESC/POS + dithering (réf. TS)
│   └── adapters/
│       ├── priority.ts                   # moteur de priorité d'adapter
│       └── dedup.ts                       # id stable + fusion des doublons
├── android/src/main/java/com/resto/thermalprinter/
│   ├── ThermalPrinterPlugin.kt           # bridge Capacitor
│   ├── ThermalPrinterEngine.kt           # orchestration
│   ├── Logger.kt
│   ├── adapters/  (PrinterAdapter, EscPos, Epson, Star, Brother, Zebra, RawTcp, Ble, Usb)
│   ├── transport/ (ByteTransport, TcpTransport, BluetoothSppTransport)
│   ├── discovery/ (DiscoveryManager, TcpScanner, BluetoothClassicScanner, AdapterPriority)
│   ├── image/     (ImageProcessor, ImageCache)
│   ├── store/     (PrinterStore)
│   └── model/     (Models.kt)
├── ios/Plugin/
│   ├── ThermalPrinterPlugin.swift + .m   # bridge Capacitor
│   ├── ThermalPrinterEngine.swift
│   ├── Logger.swift
│   ├── Adapters/  (PrinterAdapter, EscPos, Epson, Star, Brother, Zebra, RawTcp)
│   ├── Transport/ (TcpTransport — Network.framework)
│   ├── Discovery/ (DiscoveryManager, BonjourScanner, AdapterPriority)
│   ├── Image/     (ImageProcessor, ImageCache)
│   ├── Store/     (PrinterStore)
│   └── Model/     (Models.swift)
└── docs/
    └── SDK_INTEGRATION.md
```

## Installation

```bash
npm install @resto/capacitor-thermal-printer
npx cap sync
```

**Prérequis Capacitor 7** : Android `compileSdk 35` / JDK 21 ; iOS 14+ / Xcode 16+.

## Permissions

### Android (`AndroidManifest.xml` du plugin, déjà fourni)

| Permission | Usage | API |
|---|---|---|
| `BLUETOOTH_SCAN` (`neverForLocation`) | scan BT/BLE | 31+ |
| `BLUETOOTH_CONNECT` | connexion SPP/GATT | 31+ |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | scan/connexion | ≤30 |
| `ACCESS_FINE_LOCATION` | scan BLE | ≤30 |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | TCP 9100 + détection réseau | toutes |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS | toutes |
| `android.hardware.usb.host` (feature) | USB | optionnel |

Appeler `requestPermissions()` avant le premier scan.

### iOS (`Info.plist` de l'app hôte)

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>Découverte et impression sur les imprimantes du réseau local.</string>
<key>NSBonjourServices</key>
<array>
  <string>_pdl-datastream._tcp</string>
  <string>_printer._tcp</string>
  <string>_ipp._tcp</string>
</array>
<!-- Si BLE activé : -->
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Connexion aux imprimantes Bluetooth compatibles.</string>
<!-- Si SDK MFi (Epson/Star/Zebra Bluetooth) : déclarer les protocoles -->
<key>UISupportedExternalAccessoryProtocols</key>
<array>
  <string>com.epson.escpos</string>
  <string>jp.star-m.starpro</string>
</array>
```

## API publique

```ts
import { ThermalPrinter } from '@resto/capacitor-thermal-printer';

ThermalPrinter.discoverPrinters(options?)   // → { printers: DiscoveredPrinter[] }
ThermalPrinter.connectPrinter({ printerId, timeoutMs?, forceAdapter?, setAsDefault? })  // → { connected }
ThermalPrinter.disconnectPrinter({ printerId })                          // → void
ThermalPrinter.setDefaultPrinter({ printerId })                          // → { profile }
ThermalPrinter.getDefaultPrinter()                                       // → { profile | null }
ThermalPrinter.getSavedPrinters()                                        // → { profiles }
ThermalPrinter.removePrinter({ printerId })                              // → void
ThermalPrinter.printImage(options)                                       // → PrintResult (await = imprimé)
ThermalPrinter.printText({ items, ... })                                 // → PrintResult (await = imprimé)
ThermalPrinter.getPrinterStatus({ printerId? })                          // → PrinterStatus
ThermalPrinter.requestPermissions() / checkPermissions()                 // → PermissionStatus
ThermalPrinter.startStatusMonitor({ printerId, intervalMs? })            // Phase 6
ThermalPrinter.stopStatusMonitor({ printerId })                          // Phase 6
ThermalPrinter.getDebugLog()                                             // → { log: DebugLogEntry[] }

// Events
ThermalPrinter.addListener('printerFound', e => ...)        // résultats de scan incrémentaux
ThermalPrinter.addListener('discoveryComplete', e => ...)
ThermalPrinter.addListener('statusChange', e => ...)        // PrinterStatus (papier/capot/connexion)
ThermalPrinter.addListener('printJobStatus', e => ...)      // JobState: pending/printing/hold/completed/failed
```

> **`connectPrinter({ setAsDefault: true })`** définit l'imprimante par défaut
> **uniquement si la connexion réussit** (`connect` + `setDefaultPrinter` en une étape,
> sans persister une imprimante injoignable).

### Fin d'impression / `await`

`printImage` et `printText` sont **asynchrones et se résolvent quand l'impression
physique est terminée** (best-effort) — on peut donc `await`. Précisions :

- **SDK fabricants** : la promesse attend le **callback de fin** du SDK (fiabilité max).
- **ESC/POS TCP/SPP** : canal **unidirectionnel** → la promesse se résout quand tous
  les octets sont **écrits et flushés**. Un **pré-contrôle de statut** est fait avant
  l'envoi : papier vide / capot ouvert → job en `hold` + rejet `PAPER_EMPTY` /
  `COVER_OPEN` (`retryable: true`).

## Types

```ts
type PrinterTransport = 'wifi' | 'ethernet' | 'bluetooth' | 'ble' | 'usb';
type PrinterAdapterId = 'escpos' | 'epson' | 'star' | 'brother' | 'zebra' | 'rawTcp';

interface PrinterCapabilities {
  paperWidthMm: number;        // 58 | 80 | 112…
  printableDots: number;       // 384 (58mm) | 576 (80mm) | 832 (112mm)
  dpi: number;                 // 203 le plus souvent
  supportsCut: boolean;
  supportsCashDrawer: boolean;
  supportsStatus: boolean;
  supportsRasterImage: boolean;
  supportsQrCode?: boolean;
  supportsBarcode?: boolean;
}

interface DiscoveredPrinter {
  id: string;                  // id stable: "wifi:192.168.1.50", "bluetooth:AA:BB:.."
  name: string;
  brand?: string; model?: string;
  transport: PrinterTransport;
  adapter: PrinterAdapterId;   // résolu par la priorité
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

// ---- États / statuts ----
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
  jobId: string;            // corrélé aux events printJobStatus
  state: JobState;          // 'completed' si succès
  bytesSent?: number;
  durationMs?: number;
  status?: PrinterStatus;
}

// ---- Options d'impression image ----
type DitheringAlgorithm = 'none' | 'floyd_steinberg' | 'atkinson';
type ImageAlign = 'left' | 'center' | 'right';

interface ImageSource { filePath?: string; url?: string; base64?: string; } // 1 seule clé

interface PrintRenderOptions {
  widthDots?: number;       // sinon déduit du profil (384/576/832)
  resize?: boolean;         // défaut true ; false = image déjà à la bonne largeur
  grayscale?: boolean;      // défaut true ; false = image déjà 1-bit (seuil simple)
  threshold?: number;       // défaut 128 (si dithering 'none' ou grayscale false)
  dithering?: DitheringAlgorithm; // défaut 'floyd_steinberg'
  align?: ImageAlign;       // défaut 'center'
  invert?: boolean;
  cut?: boolean;            // défaut true
  feedLines?: number;       // défaut 3
  openCashDrawer?: boolean;
  copies?: number;          // défaut 1
}

interface PrintImageOptions {
  printerId?: string;       // sinon imprimante par défaut
  image: ImageSource;
  render?: PrintRenderOptions;
  timeoutMs?: number;       // défaut 15000
  autoReconnect?: boolean;  // défaut true
}

// ---- Events ----
interface PrinterFoundEvent { printer: DiscoveredPrinter; }
interface DiscoveryCompleteEvent { printers: DiscoveredPrinter[]; failedSources?: string[]; }
interface StatusChangeEvent { status: PrinterStatus; }
interface PrintJobStatusEvent { job: PrintJobStatus; }
```

### Types `printText`

```ts
type TextAlign = 'left' | 'center' | 'right';
type Underline = 'none' | 'single' | 'double';
type EscPosFont = 'A' | 'B';
type CodePage = 'CP437' | 'CP850' | 'CP858' | 'WPC1252' | 'CP852' | 'CP866'; // FR: WPC1252
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
  invert?: boolean;           // blanc sur noir
  upsideDown?: boolean;
  rotate90?: boolean;
  letterSpacing?: number;     // dots
  lineSpacing?: number;       // dots (sinon défaut)
  codePage?: CodePage;
  codePageId?: number;        // override brut ESC t n
  newline?: boolean;          // défaut true
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
  defaultCodePage?: CodePage; // FR: 'WPC1252'
  cut?: boolean;              // défaut false
  feedLines?: number;         // défaut 3
  timeoutMs?: number;
  autoReconnect?: boolean;
}
```

## Flux d'impression d'image

`printImage` exécute exactement :

1. Résout l'imprimante cible (sinon **imprimante par défaut**).
2. Vérifie si elle est connectée.
3. Sinon, **reconnexion automatique** (si `autoReconnect`, défaut `true`).
4. **Ouvre l'image** (`filePath` > `url` (cache) > `base64`).
5. **Redimensionne** à la largeur exacte (`widthDots` ou capacités du profil).
6. **Niveaux de gris** (luminance BT.601), aplatissement sur fond blanc (PNG transparent).
7. **Dithering** (Floyd-Steinberg par défaut, Atkinson ou seuil).
8. **Conversion vers l'adapter** (raster `GS v 0` pour ESC/POS, `addImage` pour SDK, ZPL pour Zebra).
9. **Envoi** (par chunks adaptés au transport).
10. **Feed + coupe** (si supporté) + tiroir-caisse optionnel.
11. **Résultat normalisé** + lecture de statut best-effort.

```ts
await ThermalPrinter.printImage({
  // printerId omis → imprimante par défaut
  image: { filePath: '/data/.../ticket.png' },   // recommandé en prod
  render: { dithering: 'floyd_steinberg', cut: true, feedLines: 3, align: 'center' },
  timeoutMs: 15000,
  autoReconnect: true,
});
```

### Exemples concrets d'impression d'image

```ts
// 1) Fichier local (RECOMMANDÉ en production) — le plus fiable/performant
await ThermalPrinter.printImage({ image: { filePath: '/data/user/0/app/files/ticket.png' } });

// 2) URL distante — téléchargée et mise en cache par le plugin
await ThermalPrinter.printImage({
  image: { url: 'https://api.resto.app/tickets/123/render.png' },
  render: { dithering: 'atkinson', cut: true },
});

// 3) base64 (pratique pour les tests, moins performant)
await ThermalPrinter.printImage({ image: { base64: 'iVBORw0KGgoAAAANS...' } });

// 4) Image DÉJÀ rendue serveur à la bonne largeur et en 1-bit noir/blanc :
//    on désactive resize + grayscale → envoi pixel-perfect, plus rapide.
await ThermalPrinter.printImage({
  image: { filePath: '/data/.../ticket_576px_1bit.png' },
  render: { resize: false, grayscale: false, cut: true },
});

// 5) Cibler une imprimante précise + 2 copies + tiroir-caisse
await ThermalPrinter.printImage({
  printerId: 'wifi:192.168.1.50',
  image: { filePath: '/data/.../ticket.png' },
  render: { widthDots: 576, copies: 2, openCashDrawer: true },
});

// 6) await = imprimé (best-effort) ; gestion d'erreur typée
try {
  const res = await ThermalPrinter.printImage({ image: { filePath } });
  console.log('Imprimé', res.jobId, res.bytesSent, 'octets en', res.durationMs, 'ms');
} catch (e) {
  if ((e as PrinterError).code === PrintErrorCode.PAPER_EMPTY) alert('Plus de papier');
}
```

> **`resize`/`grayscale` optionnels** : si votre serveur génère déjà un PNG à la
> largeur exacte (`576px`/`384px`) et en noir/blanc 1-bit, passez
> `render: { resize: false, grayscale: false }`. Le plugin applique alors un simple
> seuil (pas de dithering) et n'altère pas la géométrie.

## Impression de texte (`printText`)

`printText` accepte un **tableau ordonné d'items typés**. Idéal pour les tickets
purement textuels, sans pré-rendu serveur.

```ts
await ThermalPrinter.printText({
  defaultCodePage: 'WPC1252', // accents FR
  items: [
    { type: 'text', value: 'LE RESTO', style: { align: 'center', bold: true, widthMultiplier: 2, heightMultiplier: 2 } },
    { type: 'text', value: '12 rue des Lilas — Paris', style: { align: 'center' } },
    { type: 'divider', char: '-' },
    { type: 'text', value: 'Table 7', style: { bold: true } },
    { type: 'text', value: 'Burger............12.00 €' },
    { type: 'text', value: 'Café.............. 2.00 €' },
    { type: 'divider' },
    { type: 'text', value: 'TOTAL  14.00 €', style: { align: 'right', bold: true, widthMultiplier: 2 } },
    { type: 'feed', lines: 1 },
    { type: 'qrcode', value: 'https://resto.app/avis/123', size: 6, align: 'center' },
    { type: 'barcode', value: '4006381333931', symbology: 'EAN13', hri: 'below' },
    { type: 'cut', mode: 'partial', feedBefore: 3 },
  ],
});
```

### Styles supportés (ESC/POS) et correspondance SDK

| Style / item | ESC/POS (escpos, rawTcp) | Epson ePOS2 | Star StarXpand | Brother | Zebra (ZPL) |
|---|:--:|:--:|:--:|:--:|:--:|
| `align` (left/center/right) | ✅ `ESC a` | ✅ | ✅ | ✅ | ✅ (champ) |
| `bold` | ✅ `ESC E` | ✅ | ✅ | ✅ | ⚠️ via police |
| `underline` (single/double) | ✅ `ESC -` | ✅ | ✅ | ⚠️ | ❌ |
| `font` A/B | ✅ `ESC M` | ✅ | ✅ | ⚠️ | ⚠️ |
| `widthMultiplier`/`heightMultiplier` (1..8) | ✅ `GS !` | ✅ | ✅ | ✅ | ✅ (taille) |
| `doubleStrike` | ✅ `ESC G` | ✅ | ⚠️ | ❌ | ❌ |
| `invert` (blanc/noir) | ✅ `GS B` | ✅ | ✅ | ⚠️ | ✅ (reverse) |
| `upsideDown` | ✅ `ESC {` | ✅ | ⚠️ | ❌ | ✅ |
| `rotate90` | ✅ `ESC V` | ✅ | ⚠️ | ⚠️ | ✅ |
| `letterSpacing` | ✅ `ESC SP` | ✅ | ⚠️ | ❌ | ⚠️ |
| `lineSpacing` | ✅ `ESC 3` | ✅ | ✅ | ⚠️ | ✅ |
| `codePage` (accents) | ✅ `ESC t` | ✅ | ✅ | ✅ | ✅ |
| `qrcode` | ✅ `GS ( k` | ✅ natif | ✅ natif | ✅ natif | ✅ `^BQ` |
| `barcode` (EAN/CODE128…) | ✅ `GS k` | ✅ natif | ✅ natif | ✅ natif | ✅ `^BC`… |
| `divider` / `feed` / `cut` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `cashDrawer` | ✅ `ESC p` | ✅ | ✅ | ⚠️ | ⚠️ |
| `image` (intercalée) | ✅ raster | ✅ addImage | ✅ actionPrintImage | ✅ printImage | ✅ ^GF |
| `raw` (octets bruts) | ✅ | ⚠️ | ⚠️ | ❌ | ⚠️ (ZPL brut) |

> ✅ supporté · ⚠️ partiel/équivalent selon modèle · ❌ non disponible.
> Les styles non supportés par un SDK sont **ignorés proprement** (jamais d'échec dur).
> L'encodeur ESC/POS de référence est dans `src/core/escpos-text.ts` (testé), mirroré
> en Kotlin (`EscPosTextEncoder.kt`) et Swift (`EscPosTextEncoder.swift`).

## Événements & statut côté client

```ts
// Suivi des jobs : pending → printing → completed | hold | failed
const jobSub = await ThermalPrinter.addListener('printJobStatus', ({ job }) => {
  switch (job.state) {
    case 'printing': showSpinner(job.progress); break;
    case 'hold':     toast(job.holdReason === 'paper_empty' ? 'Ajoutez du papier' : 'Capot ouvert'); break;
    case 'completed': hideSpinner(); break;
    case 'failed':   alert(`Échec: ${job.errorCode}`); break;
  }
});

// Statut imprimante (connexion, papier, capot)
const statusSub = await ThermalPrinter.addListener('statusChange', ({ status }) => {
  updateBadge(status.online, status.paper); // 'ok' | 'near_end' | 'empty' | 'unknown'
});

// ... plus tard
await jobSub.remove();
await statusSub.remove();
```



- **Largeurs de référence @203 dpi** : `58mm → 384 px`, `80mm → 576 px`, `112mm → 832 px`. Certains 80mm impriment `640 px` : on **privilégie toujours `printableDots` du profil/SDK** quand connu.
- **Pipeline** : redimensionnement proportionnel à la largeur cible → niveaux de gris → binarisation.
- **Dithering** :
  - `none` (seuil) : net pour le texte/lignes.
  - `floyd_steinberg` (**défaut**) : logos/photos.
  - `atkinson` : plus contrasté, agréable sur ticket.
- **Raster ESC/POS** : commande `GS v 0` (`0x1D 0x76 0x30 m xL xH yL yH data`), largeur paddée au multiple de 8, MSB = pixel le plus à gauche. Implémentation de référence testable dans `src/core/imaging.ts`, mirrorée en Kotlin (`ImageProcessor.kt`) et Swift (`ImageProcessor.swift`).

## Découverte agrégée & priorité d'adapter

Plusieurs sources tournent **en parallèle** : SDK Epson/Star/Brother/Zebra, scan TCP 9100, Bluetooth classique (Android), BLE (optionnel), USB (Android). Les résultats sont **fusionnés** par `id` stable et **dédoublonnés**.

**Règles de priorité** (`priority.ts` / `AdapterPriority.kt` / `.swift`) :

| Cas | Adapter retenu | Score |
|---|---|---|
| Imprimante reconnue par un SDK officiel | `epson` / `star` / `brother` | 880–900 |
| **Zebra** | **`zebra` uniquement** (ESC/POS banni) | 1000 / −1000 |
| ESC/POS confirmé en Bluetooth (Android) | `escpos` | 620 |
| ESC/POS confirmé en TCP | `escpos` | 600 |
| BLE avec service exploitable | (BLE) | 500 |
| Réseau non identifié | `rawTcp` | 300 |

## Imprimante par défaut & reconnexion

- Après un **test d'impression réussi**, l'app appelle `setDefaultPrinter({ printerId })` : le plugin **persiste un `PrinterProfile`** (id, adapter, transport, adresse, marque, modèle, largeur papier, `printableDots`, dpi, options de coupe, métadonnées de reconnexion).
- Au **démarrage** ou **avant impression**, le plugin relit ce profil.
- **La reconnexion n'est pas une connexion permanente** : elle est tentée **juste avant `printImage`** (étape 3). Cela évite de garder un socket/Bluetooth ouvert inutilement et améliore la fiabilité en restauration.

## Erreurs normalisées

Toutes les promesses rejetées portent un **code stable** (`error.code`) :

`PRINTER_NOT_FOUND`, `PRINTER_OFFLINE`, `CONNECTION_FAILED`, `PERMISSION_DENIED`, `BLUETOOTH_DISABLED`, `WIFI_NOT_CONNECTED`, `PAIRING_REQUIRED`, `UNSUPPORTED_TRANSPORT`, `UNSUPPORTED_PRINTER`, `IMAGE_INVALID`, `IMAGE_TOO_LARGE`, `PRINT_FAILED`, `PAPER_EMPTY`, `COVER_OPEN`, `SDK_NOT_AVAILABLE`, `TIMEOUT`, `UNKNOWN`.

```ts
import { PrinterError, PrintErrorCode } from '@resto/capacitor-thermal-printer';
try { await ThermalPrinter.printImage({ image: { filePath } }); }
catch (e) {
  const err = e as PrinterError; // { code, message, detail, retryable }
  if (err.code === PrintErrorCode.PAPER_EMPTY) showPaperAlert();
}
```

## Différences Android / iOS

### Android — large couverture matérielle
- Permissions Bluetooth modernes (12+) gérées.
- **Bluetooth Classic / SPP** : pris en charge → ESC/POS génériques très répandus. ✅
- BLE pris en charge (allowlist d'UUID conseillée).
- Récupération des **appareils déjà appairés** (instantané, sans scan).
- TCP 9100 (Wi-Fi/Ethernet). ✅
- USB host (optionnel).

### iOS — contraintes Apple
- ❌ **Pas de Bluetooth Classic / SPP générique.** Une imprimante BT « chinoise » générique **n'est pas adressable** sauf si elle expose un service BLE exploitable.
- ✅ **SDK fabricants MFi** (Epson/Star/Brother/Zebra) : c'est **la** voie pour le Bluetooth sur iOS.
- ✅ **Wi-Fi TCP** (port 9100) via `Network.framework` → déclencher la pop-up **Réseau local**.
- ⚠️ **BLE** uniquement si l'imprimante expose un service GATT série utilisable.
- ❌ Pas d'USB host pour ce cas.

> **Ne promettez jamais** une compatibilité Bluetooth universelle sur iOS. En pratique : **Wi-Fi pour tout le monde, Bluetooth via SDK fabricant**.

## Cache image & logs/diagnostic

- **Cache** : les images `url` sont téléchargées dans `cache/thermal-images/` (clé = hash de l'URL, quota 32 Mo, purge LRU). Le mode `filePath` reste le plus fiable.
- **Logs** : ring-buffer en mémoire (500 lignes) + Logcat/os_log. Récupérables via `getDebugLog()` pour un écran « Diagnostic » joignable au support. Jamais de données image brutes (seulement dimensions/octets).

## Intégration des SDK fabricants

Les adapters Epson/Star/Brother/Zebra sont **prêts à brancher** : `isAvailable()` détecte le SDK (réflexion `Class.forName` / `NSClassFromString`). Tant qu'un SDK est absent, son adapter est ignoré et le plugin **retombe sur ESC/POS** si l'imprimante répond en TCP. Voir [`docs/SDK_INTEGRATION.md`](docs/SDK_INTEGRATION.md).

## Tests & qualité

- **TypeScript (Vitest)** : la logique métier pure (imaging, encodeur ESC/POS texte,
  priorité d'adapter, dédoublonnage, erreurs, fallback web) est couverte par **65 tests**
  avec un **coverage > 90 %** (seuils CI : 85 % lignes/fonctions, 80 % branches).

  ```bash
  npm test            # exécute la suite Vitest
  npm run test:coverage
  ```

- **Android (JUnit)** : `android/src/test/...` valide les encodeurs ESC/POS texte et raster
  (mêmes assertions octet-à-octet que les tests TS) → `./gradlew test`.
- **iOS (XCTest)** : `ios/Tests/...` valide l'encodeur (mêmes vecteurs) → `xcodebuild test`.
- **Tests d'intégration SDK** : à exécuter sur matériel réel quand les SDK sont liés
  (voir ROADMAP). Les trois implémentations d'encodeur (TS/Kotlin/Swift) partagent les
  **mêmes vecteurs de test**, garantissant un flux d'octets identique multiplateforme.

## Plan d'implémentation par phases

| Phase | Contenu | État du squelette |
|---|---|---|
| **1** | Scaffold plugin, types TS, registry d'adapters, store imprimante par défaut, **ESC/POS via Wi-Fi TCP 9100** | ✅ fonctionnel |
| **2** | **Bluetooth classique Android** (SPP) pour ESC/POS | ✅ transport + scanner fournis |
| **3** | **SDK Epson + Star** | 🔌 adapters stubs + pseudo-code |
| **4** | **iOS** : Wi-Fi TCP + SDK Star/Epson | ✅ TCP / 🔌 SDK stubs |
| **5** | **Brother + Zebra** | 🔌 adapters stubs + pseudo-code |
| **6** | Statut avancé, monitoring, reconnexion intelligente, logs/diagnostics | ✅ logs + events / 🔌 monitor stubs |
| **+** | **`printText` stylé** (ESC/POS texte/QR/code-barres) + **events de job** | ✅ ESC/POS / 🔌 SDK builders |

Voir [`ROADMAP.md`](ROADMAP.md) pour le détail de ce qu'il reste à faire.

## Exemple complet

```ts
import { ThermalPrinter, PrinterError } from '@resto/capacitor-thermal-printer';

// 1) Découverte (avec résultats incrémentaux)
const sub = await ThermalPrinter.addListener('printerFound', e => {
  console.log('Trouvée :', e.printer.name, e.printer.adapter);
});
await ThermalPrinter.requestPermissions();
const { printers } = await ThermalPrinter.discoverPrinters({ timeoutMs: 8000 });
await sub.remove();

// 2) Connexion qui définit le défaut SI elle réussit, puis test
const target = printers[0];
await ThermalPrinter.connectPrinter({ printerId: target.id, setAsDefault: true });
await ThermalPrinter.printImage({ printerId: target.id, image: { base64: testTicketBase64 } });

// 3) Plus tard : impression simple (imprimante par défaut + reconnexion auto)
await ThermalPrinter.printImage({ image: { filePath: '/data/.../ticket.png' } });

// 4) Ou impression texte stylée
await ThermalPrinter.printText({
  items: [
    { type: 'text', value: 'Merci !', style: { align: 'center', bold: true } },
    { type: 'cut' },
  ],
});
```

---

## Licence

MIT © Resto
