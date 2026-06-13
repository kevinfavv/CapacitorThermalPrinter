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
ThermalPrinter.connectPrinter({ printerId, timeoutMs?, forceAdapter? })  // → { connected }
ThermalPrinter.disconnectPrinter({ printerId })                          // → void
ThermalPrinter.setDefaultPrinter({ printerId })                          // → { profile }
ThermalPrinter.getDefaultPrinter()                                       // → { profile | null }
ThermalPrinter.getSavedPrinters()                                        // → { profiles }
ThermalPrinter.removePrinter({ printerId })                              // → void
ThermalPrinter.printImage(options)                                       // → PrintResult
ThermalPrinter.getPrinterStatus({ printerId? })                          // → PrinterStatus
ThermalPrinter.requestPermissions() / checkPermissions()                 // → PermissionStatus
ThermalPrinter.startStatusMonitor({ printerId, intervalMs? })            // Phase 6
ThermalPrinter.stopStatusMonitor({ printerId })                          // Phase 6

// Events
ThermalPrinter.addListener('printerFound', e => ...)        // résultats incrémentaux
ThermalPrinter.addListener('discoveryComplete', e => ...)
ThermalPrinter.addListener('statusChange', e => ...)        // Phase 6
```

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

interface PrinterProfile { /* tout le nécessaire à la reconnexion, voir models.ts */ }
interface PrinterStatus { connection; online; paper; coverOpen?; errorCode?; … }
interface PrintResult { success; printerId; adapter; bytesSent?; durationMs?; status? }
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

## Conversion image → 1-bit & largeurs

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

## Plan d'implémentation par phases

| Phase | Contenu | État du squelette |
|---|---|---|
| **1** | Scaffold plugin, types TS, registry d'adapters, store imprimante par défaut, **ESC/POS via Wi-Fi TCP 9100** | ✅ fonctionnel |
| **2** | **Bluetooth classique Android** (SPP) pour ESC/POS | ✅ transport + scanner fournis |
| **3** | **SDK Epson + Star** | 🔌 adapters stubs + pseudo-code |
| **4** | **iOS** : Wi-Fi TCP + SDK Star/Epson | ✅ TCP / 🔌 SDK stubs |
| **5** | **Brother + Zebra** | 🔌 adapters stubs + pseudo-code |
| **6** | Statut avancé, monitoring, reconnexion intelligente, logs/diagnostics | ✅ logs / 🔌 monitor stubs |

## Exemple complet

```ts
import { ThermalPrinter, PrinterError } from '@resto/capacitor-thermal-printer';

// 1) Découverte (avec résultats incrémentaux)
const sub = await ThermalPrinter.addListener('printerFound', e => {
  console.log('Trouvée :', e.printer.name, e.printer.adapter);
});
await ThermalPrinter.requestPermissions();
const { printers } = await ThermalPrinter.discoverPrinters({ timeoutMs: 8000 });
sub.remove();

// 2) Connexion + test
const target = printers[0];
await ThermalPrinter.connectPrinter({ printerId: target.id });
await ThermalPrinter.printImage({ printerId: target.id, image: { base64: testTicketBase64 } });

// 3) Enregistrer comme défaut (après test réussi)
await ThermalPrinter.setDefaultPrinter({ printerId: target.id });

// 4) Plus tard : impression simple (reconnexion auto)
await ThermalPrinter.printImage({ image: { filePath: '/data/.../ticket.png' } });
```

---

## Licence

MIT © Resto
