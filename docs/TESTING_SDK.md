# Tester la connexion aux SDK (coverage)

Le code de connexion SDK parle à des binaires propriétaires : on ne peut pas
l'exécuter tel quel en CI. Voici comment obtenir un **vrai coverage** dessus,
par couche, **sans matériel ni binaire fabricant**.

## Les 3 couches de test

| Couche | Quoi | Comment | Matériel ? |
|---|---|---|---|
| 1. Orchestration (moteur) | routing transport, reconnexion+backoff, repli `printText`→image, monitor | tests du moteur avec un faux `PrinterAdapter` | ❌ |
| 2. Plumbing SDK (réflexion) | connect/print/status Epson/Zebra/Brother | **faux SDK sur le classpath de test** + Robolectric | ❌ |
| 3. Intégration réelle | vrais appels SDK | SDK réel + imprimante | ✅ device |

Les couches 1 et 2 sont automatisables et donnent du coverage. La couche 3 reste
une validation manuelle/CI-device.

## Couche 2 — la technique clé : faux SDK sur le classpath de test

Les adapters Epson/Zebra/Brother résolvent le SDK **par nom**
(`Class.forName("com.epson.epos2.printer.Printer")`). Il suffit donc de placer une
**classe homonyme factice** sous `android/src/test/...` : la réflexion l'exécute en
test, et comme elle n'est que sur le classpath de test, `isAvailable()` reste
`false` en production.

Exemple fourni (Epson) :
- Faux SDK : `android/src/test/java/com/epson/epos2/printer/FakeEposPrinter.kt`,
  `.../discovery/FakeEposDiscovery.kt` (mêmes packages/classes/signatures que le vrai).
- Test : `android/src/test/java/com/delicity/thermalprinter/adapters/EpsonAdapterTest.kt`
  (Robolectric) couvre `connect` / `printBitmap` / `printItems` / `getStatus` / `discover`
  / `disconnect` en vérifiant les appels reçus par le faux SDK.
- Moteur réflexif : `SdkReflectTest.kt` (JVM pur, sans Robolectric).

### Répliquer pour Zebra / Brother

1. Créer les faux packages sous `src/test/java/` :
   - Zebra : `com.zebra.sdk.comm.Connection`/`TcpConnection`, `com.zebra.sdk.printer.ZebraPrinterFactory`,
     `com.zebra.sdk.graphics.ZebraImageFactory`/`ZebraImageI`, `…discovery.NetworkDiscoverer`/`DiscoveryHandler`.
   - Brother : `com.brother.sdk.lmprinter.PrinterDriverGenerator`/`Channel`/`PrinterDriver`,
     `…setting.PrinterModel`/`PrintImageSettings`/`RJPrintSettings`…
2. Reproduire **exactement** les noms de classe/méthode/champ utilisés dans
   `ZebraAdapter.kt` / `BrotherAdapter.kt` (sinon le test révèle un décalage — c'est
   le but : le test devient le contrat de l'API réflexive).
3. Écrire `ZebraAdapterTest` / `BrotherAdapterTest` sur le modèle d'`EpsonAdapterTest`.

> 💡 Le faux SDK sert aussi de **filet anti-régression** : si une signature change
> côté adapter, le test échoue immédiatement (alors que la réflexion échouerait
> silencieusement à l'exécution).

## Couche 1 — tester le moteur (recommandé, sans SDK)

La logique la plus précieuse (reconnexion + **backoff**, repli `printText`→image,
sélection d'adapter, monitor de statut) vit dans `ThermalPrinterEngine`. Pour la
tester avec un **faux `PrinterAdapter`** (qui simule des échecs de connexion, un
papier vide puis ok, etc.), injecter la liste d'adapters via un constructeur de test.

> ⚠️ Aujourd'hui `ThermalPrinterEngine` construit sa propre liste d'adapters. Pour
> rendre la couche 1 testable, ajouter un constructeur interne
> `internal constructor(context, adapters: List<PrinterAdapter>)`. (À faire si tu
> veux couvrir backoff/monitor en unitaire — demande-le et je l'ajoute.)

## Star (dépendance Maven réelle)

Star n'est pas piloté par réflexion : la connexion réelle exige un device. On peut
néanmoins couvrir la **construction des commandes** (mapping `PrintItem` → StarXpand)
si on l'extrait dans une fonction pure testable. La connexion = couche 3 (device).

## iOS

`#if canImport` empêche d'injecter un faux module proprement. Stratégie iOS :
- **Couche 1** : tester `ThermalPrinterEngine` (backoff, repli image, monitor) avec
  un faux type conforme au protocole `PrinterAdapter` (mock Swift).
- **Couche 2/3** : les adapters SDK iOS se testent en **intégration** avec le vrai
  framework lié (sur device/simulateur selon le transport).

## Avec les vrais SDK téléchargés (dossier non commité)

Déposer les binaires réels débloque 3 validations supplémentaires — **sans imprimante** pour les deux premières :

### 1. Vérifier que la réflexion matche la vraie API (contract test)
Le risque n°1 des adapters réflexifs : un nom de classe/méthode/champ erroné échoue
*silencieusement* à l'exécution. `SdkContract` (`adapters/SdkContract.kt`) décrit la
surface exacte requise par marque et la vérifie par réflexion :

```kotlin
val missing = SdkContract.verifyAll()   // { } si tout est conforme
// ex: {"epson": ["com.epson.epos2.printer.Printer#addImage(...)"]} si l'API a bougé
```
- En CI du plugin (faux SDK), `SdkContractTest` vérifie que le contrat est cohérent.
- Dans ton app, **après dépôt du vrai binaire**, appelle `SdkContract.verifyAll()`
  (ou ajoute un test) : tout symbole manquant pointe précisément ce qu'il faut
  corriger dans l'adapter. **Aucune imprimante requise.**

> ⚠️ **Piège collision** : ne mets PAS le vrai binaire sur le **même classpath que
> les faux** (`src/test`) — `com.epson.epos2.printer.Printer` serait défini 2×.
> Donc : faux SDK = tests du plugin ; vrai binaire = **example app** (ou un module
> dédié) où les faux ne sont pas présents.

### 2. Compiler le code iOS `#if canImport`
Tant que le framework n'est pas lié, la branche typée n'est pas compilée. En liant
le vrai SDK (SPM Star, pod Brother, xcframework Epson/Zebra) dans l'app, Xcode
compile la branche `#if canImport(...)` → tu valides les signatures et le **nom de
module** (ajuste-le dans l'adapter si besoin).

### 3. Impression réelle (couche 3)
Binaire dans l'**example app** + imprimante → `discover` / `connect` / `printImage`
/ `printText` / `getStatus` de bout en bout.

### Où déposer (ignoré par git)
`android/libs/` (`.aar`/`.jar`/`.so`), `ios/Frameworks/` (`.xcframework`),
`sdk-binaries/`. Voir `docs/SDK_INTEGRATION.md` + liens de téléchargement.

## Lancer les tests + le coverage

Le plugin dépend de `:capacitor-android` : les tests Gradle se lancent **depuis une
app Capacitor** qui inclut le plugin (ou un job CI qui `npm install` Capacitor puis
build). Depuis le module du plugin :

```bash
./gradlew testDebugUnitTest          # exécute les tests JVM (Robolectric inclus)
./gradlew testDebugUnitTest jacocoTestReport
# Rapport : android/build/reports/jacoco/jacocoTestReport/html/index.html
```

Robolectric s'exécute sur la JVM : **aucun émulateur** n'est nécessaire pour les
couches 1 et 2.

## Imprimante ESC/POS virtuelle (TCP) — pipeline complet sans matériel

> Le **Bluetooth n'est simulable sur aucun simulateur** (le simulateur iOS n'a pas
> de pile BT/BLE, l'émulateur Android non plus). On couvre donc tout le pipeline
> d'impression via le **transport TCP/9100**, qui, lui, fonctionne en local ET en CI.

`scripts/virtual-printer.mjs` émule une imprimante réseau : elle accepte une connexion
TCP, capture le flux ESC/POS, le **décode** (init `ESC @`, coupe `GS V`, images raster
`GS v 0`, texte) et l'enregistre.

### En CI (automatique)

`test/escpos-tcp.integration.spec.ts` démarre l'imprimante virtuelle sur un port
éphémère, génère un ticket avec l'**encodeur ESC/POS de production**
(`encodeEscPosItems` / `encodeEscPosRaster`), l'envoie sur une **vraie socket TCP**, puis
vérifie que les octets reçus sont identiques et bien formés (init, coupe, dimensions
raster). Lancé par `npm test` (job `web` de la CI). Aucune dépendance, aucun matériel.

### En local (inspection visuelle)

```bash
npm run printer:virtual                 # écoute sur :9100 (ou `node scripts/virtual-printer.mjs 9100`)
```

Puis pointe ton app/device sur `<ip-de-ton-mac>:9100` (imprimante réseau Wi-Fi). Chaque
ticket est dump dans `esc-pos-out/` :
- `job-N.bin` : octets bruts reçus (rejouables/décortiquables) ;
- `job-N-imgK.pbm` : chaque image raster, en **PBM 1-bit visualisable** (Aperçu/GIMP/web)
  — tu vois exactement ce qui aurait été imprimé.

Le terminal affiche un résumé par job (`octets · init · coupe · texte≈ · images=[L×H]`).
