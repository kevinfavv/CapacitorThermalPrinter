# Changelog

Toutes les modifications notables de ce projet sont documentées ici.
Le format suit [Keep a Changelog](https://keepachangelog.com/) et
[SemVer](https://semver.org/lang/fr/).

## [Non publié]

### Ajouté
- **`printText([...])`** : impression de texte stylé via un tableau d'items typés
  (texte, divider, QR code, code-barres, feed, cut, tiroir, image, raw). Styles
  ESC/POS complets (gras, souligné, taille, police, inversion, rotation, interligne,
  page de code pour les accents) + tableau de correspondance SDK documenté.
- **Événement `printJobStatus`** : suivi temps réel des jobs
  (`pending`/`printing`/`hold`/`completed`/`failed`, avec `holdReason`).
- **Options image `resize` et `grayscale`** : désactivables si l'image est déjà
  rendue à la bonne largeur / en 1-bit côté serveur.
- **`connectPrinter({ setAsDefault })`** : définit l'imprimante par défaut
  uniquement si la connexion réussit.
- **`printImage`/`printText` résolvent quand l'impression est terminée** (best-effort
  selon transport/SDK) + `PrintResult` enrichi de `jobId` et `state`.
- Encodeur ESC/POS texte de référence (TS) + miroirs Kotlin/Swift.
- Suite de tests **Vitest** (65 tests, coverage > 90 %) + tests JUnit (Android) et
  XCTest (iOS) des encodeurs ; seuils de couverture appliqués en CI.
- `ROADMAP.md`.

## [7.0.3] - 2026-06-14

### Ajouté
- **Zebra Link-OS (iOS)** : pont Objective-C runtime (`ZebraBridge`) qui pilote le SDK via
  `NSClassFromString`. Le plugin compile sans le SDK et active Zebra dès que
  `ZSDK_API.xcframework` est présent ; le podspec ajoute automatiquement `-ObjC`,
  `ExternalAccessory` et `CoreBluetooth` (requis par la lib statique Zebra).
- **Star (iOS)** : `FRAMEWORK_SEARCH_PATHS` dans le podspec pour que l'adapter voie le
  package SPM `StarIO10` ajouté à la target App — activation automatique, sans hook Podfile.

### Modifié
- **Brother (iOS)** : meilleure prise en charge des modèles d'imprimante.
- **Epson (iOS)** : cohérence des types de données dans l'adapter.
- **Documentation SDK** (`docs/SDK_INTEGRATION.md`) entièrement revue : guide d'installation
  par marque, en anglais et orienté développeur intégrateur (GIFs Star/Epson/Zebra, liens de
  téléchargement, signature des frameworks). Les détails techniques internes ont été déplacés
  dans `CONTRIBUTING.md`.

## [0.1.0] - Non publié

### Ajouté
- Scaffold complet du plugin Capacitor 7 (TypeScript + Android Kotlin + iOS Swift).
- API publique unique : `discoverPrinters`, `connectPrinter`, `disconnectPrinter`,
  `setDefaultPrinter`, `getDefaultPrinter`, `getSavedPrinters`, `removePrinter`,
  `printImage`, `getPrinterStatus`, `requestPermissions`/`checkPermissions`,
  `startStatusMonitor`/`stopStatusMonitor`, `getDebugLog`.
- Types normalisés : `DiscoveredPrinter`, `PrinterProfile`, `PrinterCapabilities`,
  `PrinterStatus`, `PrintResult`, transports, adapters, codes d'erreur.
- Architecture par adapters : `EscPos`, `Epson`, `Star`, `Brother`, `Zebra`,
  `RawTcp`, `Ble`, `Usb`.
- **Phase 1** : ESC/POS via Wi-Fi TCP 9100 (raster `GS v 0`) — Android + iOS.
- **Phase 2** : Bluetooth Classic SPP (Android) — transport + scanner.
- Pipeline image : decode → resize → niveaux de gris → dithering
  (Floyd-Steinberg / Atkinson / seuil) → raster, mirroré TS/Kotlin/Swift.
- Découverte agrégée multi-sources avec dédoublonnage par id stable et
  arbitrage d'adapter par priorité.
- Persistance des profils + imprimante par défaut ; reconnexion automatique
  juste avant impression.
- Cache d'images (téléchargement URL) et journal de diagnostic (ring-buffer).
- Stubs prêts à brancher pour les SDK fabricants (détection par réflexion).

### À venir
- Phases 3-5 : intégration effective des SDK Epson/Star/Brother/Zebra.
- Phase 6 : monitoring de statut en arrière-plan, reconnexion intelligente.
- Finalisation GATT BLE (allowlist d'UUID) et endpoint USB Android.
