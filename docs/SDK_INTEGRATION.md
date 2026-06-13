# Intégration des SDK fabricants

Les adapters Epson / Star / Brother / Zebra sont livrés en **stubs prêts à brancher**.
Chaque adapter expose `isAvailable()` qui détecte le SDK par réflexion
(`Class.forName` sur Android, `NSClassFromString` sur iOS). **Sans SDK lié,
l'adapter est silencieusement ignoré** et le plugin retombe sur ESC/POS (TCP).

> ⚠️ Les SDK propriétaires ne doivent **jamais** être committés dans le dépôt
> (licences fabricant). Ils sont fournis localement (`android/libs/`, `ios/Frameworks/`)
> et listés dans `.gitignore`.

---

## Android

### 1. Déposer les artefacts

```
android/libs/
├── ePOS2.aar                 # Epson
├── StarIO10.aar              # Star (ou dépendance Maven)
├── BrotherPrintLibrary.aar   # Brother
└── ZSDK_ANDROID_API.aar      # Zebra Link-OS
```

### 2. Activer les dépendances dans `android/build.gradle`

```gradle
dependencies {
    implementation(name: 'ePOS2', ext: 'aar')
    implementation(name: 'StarIO10', ext: 'aar')   // ou implementation 'com.starmicronics:stario10:x.y.z'
    implementation(name: 'BrotherPrintLibrary', ext: 'aar')
    implementation(name: 'ZSDK_ANDROID_API', ext: 'aar')
}
```

### 3. Activer le pseudo-code

Dé-commenter les blocs marqués `IMPLÉMENTATION` dans :
`adapters/EpsonAdapter.kt`, `StarAdapter.kt`, `BrotherAdapter.kt`, `ZebraAdapter.kt`.

---

## iOS

### 1. Ajouter les frameworks

- **Epson ePOS2** : `libepos2.xcframework` → `ios/Frameworks/` + bridging header si API ObjC.
- **Star StarXpand** : `pod 'StarIO10'` (CocoaPods) ou `StarIO10.xcframework`.
- **Brother** : `pod 'BRLMPrinterKit'` ou xcframework.
- **Zebra** : `ZSDK_API.xcframework`.

### 2. Référencer dans `RestoThermalPrinter.podspec`

```ruby
s.dependency 'StarIO10'
s.vendored_frameworks = 'ios/Frameworks/libepos2.xcframework', 'ios/Frameworks/ZSDK_API.xcframework'
```

### 3. Info.plist (Bluetooth MFi)

```xml
<key>UISupportedExternalAccessoryProtocols</key>
<array>
  <string>com.epson.escpos</string>     <!-- Epson -->
  <string>jp.star-m.starpro</string>    <!-- Star -->
  <string>com.zebra.rawport</string>    <!-- Zebra -->
</array>
```

### 4. Activer le pseudo-code

Dé-commenter les blocs dans `ios/Plugin/Adapters/*Adapter.swift`.

---

## Mapping des concepts d'impression image par SDK

| Adapter | Découverte | Impression image | Coupe | Statut |
|---|---|---|---|---|
| **Epson ePOS2** | `Discovery.start` | `Printer.addImage(bitmap, …, MODE_MONO)` | `addCut(CUT_FEED)` | `PrinterStatusInfo` (papier/capot) |
| **Star StarXpand** | `StarDeviceDiscoveryManager` | `PrinterBuilder.actionPrintImage(ImageParameter)` | `actionCut(.partial)` | `printer.getStatus()` |
| **Brother** | `BRLMPrinterSearcher` | `driver.printImage(cgImage, settings)` | settings (auto-cut) | `driver.getPrinterStatus()` |
| **Zebra Link-OS** | `NetworkDiscoverer` / `BluetoothDiscoverer` | `GraphicsUtil.print(cgImage…)` → **ZPL/CPCL** | commande media | `printer.getCurrentStatus()` |

> **Important Zebra** : ne jamais router une Zebra vers ESC/POS. Le moteur de
> priorité attribue un score négatif à `escpos`/`rawTcp` pour une marque Zebra.

---

## Test de présence d'un SDK

```kotlin
// Android
EpsonAdapter(context).isAvailable()   // true si com.epson.epos2.printer.Printer est sur le classpath
```
```swift
// iOS
EpsonAdapter().isAvailable()          // true si NSClassFromString("Epos2Printer") != nil
```
