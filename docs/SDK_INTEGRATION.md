# Intégration des SDK fabricants

Ce plugin gère 4 marques via leur SDK natif : **Star, Epson, Brother, Zebra**.
Tous fonctionnent en **option** : le plugin **compile et s'installe sans aucun SDK
fabricant**, et chaque adapter s'active **automatiquement** dès que le binaire
correspondant est présent. S'il est absent, l'adapter est ignoré et le plugin
retombe sur l'ESC/POS générique (TCP/Bluetooth/USB/BLE).

## TL;DR — qui se télécharge tout seul, qui non ?

| Marque | Android | iOS | Activation côté plugin |
|---|---|---|---|
| **Star** | ✅ **auto** (Maven Central) | ✅ **auto** (Swift Package Manager) | Android: appels typés · iOS: `#if canImport(StarIO10)` |
| **Brother** | ⛔ binaire manuel (`.aar`) | ✅ **auto** (CocoaPods) | Android: réflexion · iOS: `#if canImport(BRLMPrinterKit)` |
| **Epson** | ⛔ binaire manuel (`.jar` + `.so`) | ⛔ xcframework manuel | Android: réflexion · iOS: `#if canImport(libepos2)` |
| **Zebra** | ⚠️ Maven **privé** (token) ou `.jar` manuel | ⛔ xcframework manuel | Android: réflexion · iOS: `#if canImport(ZSDK_API)` |

> ### Pourquoi tout n'est pas auto-téléchargé ?
> Seuls les SDK publiés sur un **dépôt de paquets standard** (Maven Central pour
> Android, CocoaPods/SPM pour iOS) se téléchargent automatiquement. Epson, Zebra
> et Brother-Android ne sont distribués **que via le portail du fabricant**, et
> leur **licence interdit la redistribution** (on ne peut donc pas les mettre sur
> Maven Central / CocoaPods, ni les committer dans ce dépôt). C'est une contrainte
> **juridique** des fabricants, pas une limite technique du plugin.
>
> ✅ **Conforme** : l'app qui consomme le plugin télécharge le binaire elle-même
> (elle accepte la licence) et l'embarque dans son app. Le plugin fournit tout le
> code pour s'en servir.

---

## Liens de téléchargement officiels

| Marque | Page de téléchargement / SDK | Notes |
|---|---|---|
| **Star** | Android : [StarXpand-SDK-Android](https://github.com/star-micronics/StarXpand-SDK-Android) (Maven Central `com.starmicronics:stario10`) · iOS : [StarXpand-SDK-iOS](https://github.com/star-micronics/StarXpand-SDK-iOS) (SPM) | [Doc/manuel StarXpand](https://www.star-m.jp/products/s_print/sdk/starxpand/manual/en/index.html) |
| **Epson** | [Epson Developers – POS products](https://epson.com/developers-products) · [ePOS SDK (référence)](https://download4.epson.biz/sec_pubs/pos/reference_en/technology/epson_epos_sdk.html) | iOS Bluetooth MFi : voir [MFi / ePOS SDK support](https://global.epson.com/products_and_drivers/tm/en/mfi.html) (compte + acceptation licence) |
| **Brother** | [Mobile SDK – téléchargement](https://support.brother.com/g/s/es/dev/en/mobilesdk/download/index.html) · US : [Brother Developer Program](https://developerprogram.brother-usa.com/sdk-download) | iOS : pod [BRLMPrinterKit](https://cocoapods.org/pods/BRLMPrinterKit) · [Manuel SDK](https://support.brother.com/g/s/es/htmldoc/mobilesdk/) |
| **Zebra** | [Link-OS Multiplatform SDK (portail dev)](https://developer.zebra.com/products/printers/link-os-multiplatform-sdk) · [Téléchargements & support](https://www.zebra.com/us/en/support-downloads/software/printer-software/link-os-multiplatform-sdk.html) | [TechDocs Link-OS](https://techdocs.zebra.com/link-os/) (création d'un compte Zebra requise) |

> Les téléchargements Epson / Brother / Zebra nécessitent un **compte développeur**
> gratuit et l'**acceptation de la licence** du fabricant. Star ne nécessite rien
> (Maven Central / SPM publics).

---

## Où déposer les binaires (et ne JAMAIS les committer)

Les binaires propriétaires sont **ignorés par git** (voir `.gitignore`). Pour
**tester en local**, tu peux les déposer dans :

```
android/libs/            # .aar / .jar / .so   (Android)
ios/Frameworks/          # .xcframework         (iOS)
sdk-binaries/            # dossier libre de test, ignoré par git
```

En production, l'app consommatrice les place dans **son propre projet** (voir
sections par marque ci-dessous).

---

## ⭐ Star (recommandé — 100 % automatique)

### Android
Déjà câblé dans `android/build.gradle` :
```gradle
implementation "com.starmicronics:stario10:1.12.1"
```
Rien à faire : Gradle télécharge le SDK depuis Maven Central. (Mettre à jour la
version au besoin.) L'adapter `StarAdapter.kt` utilise des appels **typés**.

### iOS
Le SDK Star iOS est distribué via **Swift Package Manager** (pas de pod officiel).
Dans l'app Xcode consommatrice :
1. **File ▸ Add Package Dependencies…**
2. URL : `https://github.com/star-micronics/StarXpand-SDK-iOS`
3. Choisir la dernière version, ajouter le produit **`StarIO10`** à la target de l'app.

L'adapter `StarAdapter.swift` s'active via `#if canImport(StarIO10)`.
> ⚠️ Si le module n'est pas visible depuis la target du plugin (Pod), lier
> aussi `StarIO10` au pod `DelicityThermalPrinter` dans le Podfile post-install.

---

## 🟦 Brother

### Android (binaire manuel)
1. Télécharger **Brother Print SDK v4** (`BrotherPrintLibrary.aar`) sur le
   [portail Mobile SDK Brother](https://support.brother.com/g/s/es/dev/en/mobilesdk/download/index.html)
   (ou [Brother Developer Program US](https://developerprogram.brother-usa.com/sdk-download)) — acceptation de licence.
2. Le déposer dans l'app : `android/app/libs/BrotherPrintLibrary.aar`
3. Dans le `build.gradle` de l'app :
   ```gradle
   repositories { flatDir { dirs 'libs' } }
   dependencies { implementation(name: 'BrotherPrintLibrary', ext: 'aar') }
   ```
4. `BrotherAdapter.kt` (réflexion) s'active automatiquement.

### iOS (automatique via CocoaPods)
Dans le `Podfile` de l'app :
```ruby
pod 'BRLMPrinterKit', '~> 4.12'
```
`BrotherAdapter.swift` s'active via `#if canImport(BRLMPrinterKit)`.

---

## 🟧 Epson (binaire manuel sur les 2 plateformes)

### Android
1. Télécharger **ePOS SDK for Android** sur [Epson Developers](https://epson.com/developers-products)
   ([réf. ePOS SDK](https://download4.epson.biz/sec_pubs/pos/reference_en/technology/epson_epos_sdk.html)) — acceptation de licence.
2. Déposer `ePOS2.jar` dans `android/app/libs/` (+ les `.so` dans `src/main/jniLibs/` si fournis).
3. Dans le `build.gradle` de l'app :
   ```gradle
   dependencies { implementation files('libs/ePOS2.jar') }
   ```
   ProGuard/R8 (release) :
   ```
   -keep class com.epson.** { *; }
   -dontwarn com.epson.**
   ```
4. `EpsonAdapter.kt` (réflexion) s'active automatiquement.

### iOS
1. Télécharger **ePOS SDK for iOS** sur [Epson Developers](https://epson.com/developers-products)
   (Bluetooth MFi : voir [support MFi / ePOS SDK](https://global.epson.com/products_and_drivers/tm/en/mfi.html)).
2. Glisser `libepos2.xcframework` (+ `libeposeasyselect.xcframework` si fourni) dans
   le projet Xcode (target de l'app, *Embed & Sign*).
3. `EpsonAdapter.swift` s'active via `#if canImport(libepos2)`.
   > ⚠️ Si ta version du SDK expose un **nom de module différent**, ajuste-le dans
   > `EpsonAdapter.swift` (les 2 lignes `canImport(libepos2)` et `import libepos2`).

---

## 🟨 Zebra (Link-OS — ZPL/CPCL, jamais ESC/POS)

### Android — option A : dépôt Maven privé Zebra (token requis)
Dans `android/build.gradle` (bloc déjà présent, commenté) :
```gradle
maven {
    url "https://artifactory-us.zebra.com/artifactory/dmo-mvn-rel/"
    credentials {
        username = project.findProperty('zebraCoreId') ?: ''
        password = project.findProperty('zebraToken') ?: ''
    }
}
// dependencies { implementation 'com.zebra:zsdk-api:2.14.5198' }
```
Renseigner `zebraCoreId` / `zebraToken` dans `~/.gradle/gradle.properties`
(obtenus avec un compte Zebra Core ID).

### Android — option B : binaire manuel
Télécharger le [Link-OS Multiplatform SDK](https://developer.zebra.com/products/printers/link-os-multiplatform-sdk),
déposer `ZSDK_ANDROID_API.jar` (+ `ZSDK_ANDROID_BTLE.jar`) dans `android/app/libs/`
puis `implementation files('libs/ZSDK_ANDROID_API.jar')`.

### iOS (xcframework manuel)
1. Télécharger le **Link-OS Multiplatform SDK** (iOS) sur le
   [portail Zebra](https://developer.zebra.com/products/printers/link-os-multiplatform-sdk)
   ([téléchargements & support](https://www.zebra.com/us/en/support-downloads/software/printer-software/link-os-multiplatform-sdk.html)).
2. Glisser `ZSDK_API.xcframework` dans le projet Xcode (*Embed & Sign*).
3. `ZebraAdapter.swift` s'active via `#if canImport(ZSDK_API)` (ajuster le nom de
   module si nécessaire).

`ZebraAdapter.kt` (réflexion) s'active automatiquement quand le `.jar` est présent.

---

## Comment fonctionne l'activation (résumé technique)

- **Android** : chaque adapter SDK teste la présence du binaire par **réflexion**
  (`Class.forName(...)` dans `isAvailable()`), puis pilote le SDK par réflexion
  (`SdkReflect.kt`). Aucune dépendance de compilation -> le plugin compile sans binaire.
  *Exception* : **Star** est une vraie dépendance Maven (appels typés).
- **iOS** : chaque adapter SDK utilise la **compilation conditionnelle**
  `#if canImport(Module)`. Si le module n'est pas lié, le corps typé est remplacé
  par un stub inerte -> le plugin compile sans le SDK, sans risque de casse.

> ⚠️ **Le code réflexif (Android) et le code iOS gated ne sont pas vérifiés par le
> compilateur de ce dépôt** (binaires non présents). Ils sont écrits d'après les API
> documentées des SDK et **doivent être testés sur device réel** avec le binaire. En
> cas d'évolution d'API fabricant, ajuster les noms de classe/méthode/module.

---

## Vérifier qu'un SDK est bien détecté

```kotlin
// Android
EpsonAdapter(context).isAvailable()   // true si com.epson.epos2.printer.Printer est sur le classpath
StarAdapter(context).isAvailable()    // true si com.starmicronics.stario10.StarPrinter est présent
```
```swift
// iOS
EpsonAdapter().isAvailable()          // true si #if canImport(libepos2) (ou Epos2Printer runtime)
StarAdapter().isAvailable()           // true si #if canImport(StarIO10)
```

À la découverte, les sources de SDK absentes sont remontées dans
`discoveryComplete.failedSources` (diagnostic non bloquant).

---

## Mapping des concepts d'impression image par SDK

| Adapter | Découverte | Impression image | Coupe | Statut |
|---|---|---|---|---|
| **Star StarXpand** | `StarDeviceDiscoveryManager` | `PrinterBuilder.actionPrintImage(ImageParameter)` | `actionCut(.partial)` | `getStatus()` |
| **Epson ePOS2** | `Discovery.start` | `Printer.addImage(bitmap, …, MODE_MONO)` | `addCut(CUT_FEED)` | `PrinterStatusInfo` |
| **Brother** | `BRLMPrinterSearcher` | `driver.printImage(image, settings)` | settings (auto-cut) | `getPrinterStatus()` |
| **Zebra Link-OS** | `NetworkDiscoverer`/`BluetoothDiscoverer` | `GraphicsUtil.printImage(…)` → **ZPL** | commande media | `getCurrentStatus()` |

### `printText` par marque

`printText([...])` fonctionne sur **toutes** les marques :

| Cible | Implémentation `printText` |
|---|---|
| ESC/POS (TCP/SPP/USB/BLE), **rawTcp** | encodeur ESC/POS natif (octets) |
| **Star** (Android + iOS) | builder StarXpand natif (`actionPrintText`, QR, code-barres, cut) |
| **Epson Android** | builder ePOS2 natif (`addText`/`addTextStyle`/`addSymbol`/`addBarcode`) |
| **Epson iOS, Brother, Zebra** | **repli automatique en image** : les items sont rendus par `TextRasterizer` puis envoyés via `printImage` du SDK |

Le routage est automatique via `supportsTextItems()` : si un adapter ne sait pas
mapper le texte nativement, le moteur rend les items en bitmap (police monospace,
alignement/gras/souligné/taille, séparateurs) et imprime en image. Les items
QR/code-barres du repli image sont rendus en **texte** : pour un QR/code-barres
précis sur Brother/Zebra, utiliser `printImage` avec un visuel pré-rendu.
