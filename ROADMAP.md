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
- **`statusChange`** : émis lors des opérations ; le monitoring périodique
  (`startStatusMonitor`) reste un stub (Phase 6).

## À brancher (SDK fabricants) 🔌

> Pseudo-code déjà présent dans chaque adapter ; activer après dépôt des SDK
> (voir `docs/SDK_INTEGRATION.md`).

- [ ] **Epson ePOS2** (Android + iOS) : discovery, connect, `addImage`, builder texte, statut, cut, drawer.
- [ ] **Star StarXpand** (Android + iOS) : discovery, `actionPrintImage` + builder texte, statut.
- [ ] **Brother** (Android + iOS) : recherche réseau/BLE, `printImage`, settings modèles QL/TD/RJ.
- [ ] **Zebra Link-OS** (Android + iOS) : discovery, conversion image → ZPL/CPCL, statut. (Jamais ESC/POS.)
- [ ] Mapping `printText` → builder de chaque SDK (les styles non supportés sont déjà ignorés proprement).

## À finaliser (transports) 🔌

- [ ] **BLE GATT** (Android + iOS) : négociation MTU, écriture par paquets, **allowlist d'UUID** par modèle validé.
- [ ] **USB host Android** : permission runtime + transfert bulk sur l'endpoint OUT.

## À faire ⛔

- [ ] **Phase 6 — monitoring** : polling périodique de statut + émission `statusChange`,
      reconnexion intelligente avec backoff, détection de reprise après `hold`.
- [ ] **Projet d'exemple Capacitor exécutable** (Ionic) : écran scan → liste → test → défaut → impression.
- [ ] **CI native** : activer les jobs Gradle (`./gradlew test`) et Xcode (`xcodebuild test`).
- [ ] **Tests d'intégration matériels** par famille d'imprimante (matrice device).
- [ ] **i18n des messages d'erreur** côté app (codes déjà normalisés).
- [ ] **Sécurité** : si credentials réseau un jour stockés → `EncryptedSharedPreferences` / Keychain.
- [ ] **Annulation de job** (`cancelJob(jobId)`) + état `canceled` exposé.
- [ ] **QR/code-barres de secours en image** pour les modèles sans support natif.
- [ ] Remplacer le scope `@resto` / `your-org` par les valeurs réelles avant publication.

## Limites connues (par design)

- **iOS** : pas de Bluetooth Classic/SPP générique (Apple). Bluetooth = SDK MFi ; sinon Wi-Fi / BLE exploitable.
- **ESC/POS** : statut temps réel limité (canal unidirectionnel selon transport).
- **Zebra** : ZPL/CPCL uniquement (jamais routée en ESC/POS).
