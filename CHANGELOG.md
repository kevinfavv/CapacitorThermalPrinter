# Changelog

Toutes les modifications notables de ce projet sont documentées ici.
Le format suit [Keep a Changelog](https://keepachangelog.com/) et
[SemVer](https://semver.org/lang/fr/).

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
