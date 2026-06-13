# ROADMAP

État d'avancement et travail restant. Légende : ✅ fait · 🟡 partiel · 🔌 stub prêt à brancher · ⛔ à faire.

## Fait ✅

- API JS unique + types normalisés (transports, adapters, capacités, erreurs).
- Architecture par adapters + moteur de priorité + dédoublonnage par id stable.
- Pipeline image : decode → resize (optionnel) → niveaux de gris (optionnel) →
  dithering (Floyd-Steinberg / Atkinson / seuil) → raster `GS v 0`.
- `printImage` : fichier local / URL (cache) / base64, options `resize`/`grayscale`.
- `printText([...])` : items typés (texte stylé, divider, QR, code-barres, feed, cut,
  tiroir, image, raw) — encodeur ESC/POS de référence + miroirs Kotlin/Swift.
- Événements `printerFound`, `discoveryComplete`, `statusChange`, `printJobStatus`
  (états `pending`/`printing`/`hold`/`completed`/`failed`).
- `connectPrinter({ setAsDefault })` : défaut défini seulement si connexion OK.
- Persistance des profils + reconnexion automatique juste avant impression.
- Transports : TCP 9100 (Android + iOS), Bluetooth SPP (Android), Bonjour (iOS).
- Cache image (quota + purge LRU) + journal de diagnostic (`getDebugLog`).
- Tests : 65 tests TS (coverage > 90 %), tests JUnit (Android) et XCTest (iOS) des encodeurs.

## En cours / partiel 🟡

- **Fin d'impression physique** : fiable via SDK ; best-effort (flush) en ESC/POS
  unidirectionnel. Améliorer en lisant le statut post-job quand le transport le permet.

## SDK fabricants — intégrés ✅ (à vérifier sur device avec binaire)

> Architecture : Star = dépendance auto (Maven/SPM, code typé) ; Epson/Zebra/Brother
> = activation auto si binaire présent (Android réflexion · iOS `#if canImport`).
> Détails et installation : `docs/SDK_INTEGRATION.md`. Le code natif n'est pas
> compilé dans ce dépôt (binaires non redistribuables) -> **tester sur device**.

- [x] **Star StarXpand** (Android + iOS) : discovery, connect, `actionPrintImage`, builder texte, cut, drawer, statut. **Auto-download.**
- [x] **Epson ePOS2** (Android réflexion + iOS canImport) : discovery, connect, `addImage`, cut, drawer, statut.
- [x] **Brother** (Android réflexion + iOS pod) : recherche réseau, `printImage`, settings par modèle.
- [x] **Zebra Link-OS** (Android réflexion + iOS canImport) : discovery, image → ZPL, statut. (Jamais ESC/POS.)
- [x] Mapping `printText` → builder : **Star** (natif, 2 plateformes) et **Epson Android**
      (builder ePOS2 natif). **Epson iOS / Brother / Zebra** : repli automatique en image
      (`TextRasterizer` → `printImage`) via le flag `supportsTextItems()`.

## Transports — finalisés ✅

- [x] **BLE GATT** (Android) : connexion GATT, négociation MTU, écriture par paquets, **allowlist d'UUID** (`BleGattClient`), scan BLE filtré.
- [x] **USB host Android** : permission runtime + claimInterface + transfert bulk sur l'endpoint OUT.
- [x] **BLE iOS** : décision actée — via SDK MFi (Star/Epson/Brother) ; **pas de GATT
      générique** exposé par le plugin (garde `UNSUPPORTED_TRANSPORT` explicite + doc).

## Monitoring — fait ✅

- [x] **Phase 6** : `startStatusMonitor`/`stopStatusMonitor` (Android + iOS), polling
      périodique + émission `statusChange` sur diff d'état.
- [x] Reconnexion intelligente avec **backoff exponentiel** (`ensureConnected`, 3 tentatives)
      + **détection de reprise après `hold`** dans le monitor (transition bloqué → ok).

## À faire ⛔

- [ ] **Projet d'exemple Capacitor exécutable** (Ionic) : écran scan → liste → test → défaut → impression.
- [ ] **CI native** : activer les jobs Gradle (`./gradlew test`) et Xcode (`xcodebuild test`).
- [ ] **Tests d'intégration matériels** par famille d'imprimante (matrice device).
- [ ] **i18n des messages d'erreur** côté app (codes déjà normalisés).
- [ ] **Sécurité** : si credentials réseau un jour stockés → `EncryptedSharedPreferences` / Keychain.
- [ ] **Annulation de job** (`cancelJob(jobId)`) + état `canceled` exposé.
- [ ] **QR/code-barres de secours en image** pour les modèles sans support natif.
- [ ] Renseigner l'URL `repository` (`your-org`) avant publication (scope npm = `@delicity`).

## Limites connues (par design)

- **iOS** : pas de Bluetooth Classic/SPP générique (Apple). Bluetooth = SDK MFi ; sinon Wi-Fi / BLE exploitable.
- **ESC/POS** : statut temps réel limité (canal unidirectionnel selon transport).
- **Zebra** : ZPL/CPCL uniquement (jamais routée en ESC/POS).
