# Changelog

Toutes les modifications notables de ce projet sont documentées ici.
Le format suit [Keep a Changelog](https://keepachangelog.com/) et
[SemVer](https://semver.org/lang/fr/).

## [Unreleased]

### Corrigé
- **Android USB : la découverte USB n'était jamais appelée depuis `DiscoveryManager`.**
  Le commentaire d'en-tête liste bien `UsbAdapter.discover (optionnel)` comme source,
  et `UsbAdapter.discover(timeoutMs, onFound)` est implémenté (énumération de
  `UsbManager.deviceList`, filtre classe imprimante USB 7). Mais la boucle
  `for (adapter in adapters)` de `DiscoveryManager.discover()` ne mappait que les
  SDK fabricants (`epson|star|brother|zebra`) via `when (adapter.id.value)` et
  `continue`ait pour tout le reste — l'`UsbAdapter` (dont `id = ESCPOS`) tombait dans
  ce trou. Résultat : côté client, aucune imprimante USB ne remontait dans
  `discoverPrinters({ sources: ['usb'] })` — impossible d'obtenir l'`id` d'une
  imprimante USB pour la connecter, sauf via un profil déjà persisté. Ajout d'un
  bloc `--- Source USB Host (Android) ---` symétrique aux blocs TCP/Bluetooth/BLE,
  filtrant les adapters par type `UsbAdapter` et appelant leur `discover()` sous
  `runCatching`. Validé sur AX-5 + NCR7748 + Bixolon (Powered USB, classe 7).

## [8.3.0]

### Corrigé
- **Epson réseau : doublons dans la liste + « Connexion Epson échouée » sur les firmwares
  TLS (target `TCPS:<MAC>`).** La découverte ePOS2 remontait le `target` brut comme
  adresse de l'imprimante, avec deux conséquences :
  1. sur les firmwares avec TLS activé (TM-m30II-NT, TM-m30III…), le target est
     `TCPS:<MAC>` : le fallback de connexion ne reconnaissait pas ce préfixe et coupait
     au premier `:` → cible `TCP:TCPS` invalide → échec systématique de la connexion via
     le SDK Epson ;
  2. une adresse MAC n'est pas rapprochable des IP du scan réseau natif → chaque
     imprimante apparaissait **deux fois** (« Epson · Réseau » sans IP + « Natif
     Réseau » avec IP), 4 lignes pour 2 imprimantes.
  La découverte utilise désormais `DeviceInfo.getIpAddress()` comme adresse : affichage
  d'une IP lisible, fusion automatique avec l'entrée du scan natif (une seule ligne par
  imprimante), et cible de connexion `TCP:<ip>` fiable. Un target `TCPS:` persisté par
  une ancienne version est réécrit en `TCP:` à la connexion (le canal TLS d'Epson exige
  un certificat provisionné sur l'imprimante, hors périmètre). L'id des imprimantes aux
  targets `TCP:<ip>` classiques est inchangé (pas de ré-appairage du parc existant).
  (Android + iOS.)
- **iOS : ticket imprimé en charabia quand le service Bonjour LPD/IPP était retenu.** Une
  imprimante réseau publie souvent trois services mDNS sous le même nom :
  `_pdl-datastream._tcp` (RAW 9100), `_printer._tcp` (LPD 515) et `_ipp._tcp` (IPP 631).
  La fusion des découvertes gardait la **première** entrée arrivée — ordre mDNS non
  déterministe : quand LPD ou IPP gagnait, le flux ESC/POS brut partait sur le mauvais
  port et l'imprimante imprimait le payload raster en texte (symboles aléatoires).
  Corrections : classement déterministe des services (`_pdl-datastream` prioritaire) à la
  découverte ET à la fusion, et réécriture à la connexion des adresses Bonjour
  `_printer`/`_ipp` (profils persistés par une ancienne version) vers
  `_pdl-datastream._tcp` — si l'imprimante ne publie pas le service RAW, la connexion
  échoue par un timeout explicite au lieu d'imprimer n'importe quoi.

## [8.2.2]

### Corrigé
- **Zebra : détection du langage réel (ZPL/CPCL) au lieu de forcer le ZPL.** Une Zebra en
  CPCL (mobiles iMZ, ZQ1xx…) ignore un flux ZPL : elle **bipe mais n'imprime rien**, alors
  que l'écriture Bluetooth réussit et que le job se termine `completed`. Depuis 8.1.0 le
  plugin forçait systématiquement `device.languages=hybrid_xml_zpl` + `getInstance(ZPL)`,
  ce qui cassait ces modèles. À la connexion (premier octet sur connexion propre, même
  contrainte que le fix `line_print` de 8.1.0), le plugin lit désormais `device.languages`
  via SGD et instancie le `ZebraPrinter` dans le langage natif de l'imprimante :
  `zpl`/`hybrid` → ZPL (aucune écriture de config) ; `cpcl` → CPCL (on ne touche plus au
  langage) ; `line_print` → bascule `hybrid_xml_zpl` (comportement 8.1.0) avec repli CPCL
  si refusée ; pas de réponse → comportement historique. Le langage détecté est tracé dans
  `getDebugLog()` (catégorie `zebra`). (Android.)

## [8.2.1]

### Corrigé
- **Impressions concurrentes : « Décodage impossible » et tickets illisibles.** Le plugin
  lançait chaque appel Capacitor dans une coroutine/Task indépendante, sans sérialisation :
  deux jobs simultanés vers la même imprimante (double impression `printReceiptDouble`,
  retap pendant un envoi lent, timeout JS relâché alors que le natif émettait encore)
  entrelaçaient leurs octets sur le même socket — l'en-tête raster `GS v 0` désynchronisé
  faisait imprimer du charabia. En parallèle, `ImageCache` écrivait le téléchargement
  **directement dans le fichier de cache final** (clé = hash de l'URL) : un second fetch de
  la même URL tronquait/réécrivait le fichier pendant que le premier job le lisait →
  `BitmapFactory` renvoyait `null` (« Décodage impossible: \<path\> ») ou décodait un
  fragment. Corrections :
  - **Un job d'impression à la fois par imprimante** (mutex par `printerId`, Android + iOS) ;
    deux imprimantes différentes restent parallèles.
  - **Cache d'images atomique** : téléchargement vers un fichier temporaire puis rename
    atomique (Android) / écriture `.atomic` (iOS) + verrou par URL (Android) — une entrée
    visible est toujours une image complète.
  - **Validation du contenu avant mise en cache** : bornes décodables + longueur annoncée
    (`Content-Length`) vérifiées ; une page d'erreur HTTP 200 (portail captif, erreur
    serveur) ou un flux tronqué silencieusement n'entre plus jamais au cache. Les entrées
    corrompues héritées d'anciennes versions sont ignorées et re-téléchargées.
  - **Resynchronisation après échec d'envoi** : un timeout ou une erreur d'écriture en
    plein job coupe désormais la connexion (le raster incomplet laissé dans le buffer de
    l'imprimante aurait fait interpréter le job suivant comme des pixels). Le timeout
    d'envoi est remonté en `PrinterException(TIMEOUT, retryable)` au lieu d'une
    cancellation générique. (Android.)

## [8.2.0]

### Corrigé
- **TCP RAW 9100 : reconnexion automatique à l'écriture.** Les imprimantes Epson TM
  (ex. TM-T20IV) n'acceptent qu'**une seule** connexion RAW 9100 et ferment les
  connexions inactives. En Java, ni `Socket.isConnected()` ni `isClosed()` ne détectent
  une fermeture **côté pair** : `TcpTransport.isOpen` renvoyait `true` sur un socket
  fantôme, court-circuitant la reconnexion amont (`ensureConnected`) et faisant échouer
  l'impression avec « Écriture TCP échouée » (le symptôme typique : « Imprimante
  connectée ! » puis rien à l'impression). `write()` ré-ouvre désormais un socket neuf
  et réémet le job une fois avant d'abandonner. (Android.)

## [8.1.0]

### Ajouté
- **Exception d'agrégation Zebra — fallback ESC/POS exposé.** Quand une imprimante Zebra
  est aussi découverte par une source native générique (Bluetooth classique / BLE), son
  entrée native n'est plus fusionnée dans l'entrée SDK : les deux lignes sont conservées et
  sélectionnables. L'utilisateur garde le chemin Zebra (ZPL, prioritaire) **et** un chemin
  d'impression ESC/POS « normal » de secours — utile si l'imprimante refuse le ZPL ou est
  configurée en `line_print`. (Android, iOS, Web.)

### Corrigé
- **Zebra : auto-correction du mode `line_print`.** Certaines Zebra imprimaient littéralement
  la sonde de détection de langage du SDK (`! U1 getvar "appl.name"`) au lieu de l'exécuter,
  ne produisant aucun ticket. À la connexion, le plugin force désormais
  `device.languages = hybrid_xml_zpl` (premier octet envoyé, persistant et non destructif) et
  récupère l'imprimante via `getInstance(PrinterLanguage.ZPL, …)` — la sonde n'est donc plus
  émise ni imprimée. (Android.)

## [7.1.4]

### Ajouté
- **`PrintTextOptions.paperWidthMm` — largeur papier par appel pour `printText`.**
  Détermine le nombre de colonnes (dividers, mise en page) en surchargeant la largeur
  du profil connecté. Corrige les dividers trop larges quand le profil n'est pas à la
  bonne largeur (ex. 58mm imprimé via un profil resté en 80mm). Équivalent de
  `render.paperWidthMm` pour `printImage`.

## [7.1.3]

### Ajouté
- **`isBluetoothEnabled()` — état réel de l'adaptateur Bluetooth (allumé/éteint).**
  Distinct des permissions (`checkPermissions`). Android : `BluetoothAdapter`. iOS :
  CoreBluetooth (`poweredOn`, sans pop-up système). Web : `false`. Permet d'inviter
  l'utilisateur à activer le Bluetooth avant d'imprimer.

## [7.1.2]

### Ajouté
- **`ImageSource.forceFetch` — re-télécharger une image distante sans dépendre du cache.**
  Quand `printImage({ image: { url, forceFetch: true } })`, le plugin ignore le cache local,
  re-télécharge toujours l'`url`, puis **remplace** l'entrée de cache (les appels suivants
  sans `forceFetch` la réutilisent). Sans effet hors source `url`. Défaut `false` (comportement
  inchangé). Implémenté Android + iOS.

## [7.1.1]

### Corrigé
- **Image agrandie puis coupée (escpos/BLE sur imprimante 58 mm).** `resizeToWidth`
  **agrandissait** une image plus étroite que la largeur cible (ex. reçu déjà rendu à
  384 px/58 mm gonflé à 576 px car le BLE assume 80 mm par défaut, largeur non détectable) :
  l'imprimante 58 mm (384 dots) coupait alors la partie droite. Le resize **ne fait plus
  d'agrandissement** — il imprime au pixel natif (1 px = 1 dot) et ne réduit que si l'image
  dépasse la cible. Corrigé Android + iOS.

## [7.1.0]

### Ajouté
- **Encodage texte configurable par le dev — y compris CJK (chinois/japonais/coréen).**
  Nouveau type `TextEncoding` = page de code latine (`WPC1252` défaut FR, `CP437`…) **ou**
  charset multi-octets (`GB18030`, `GBK`, `Shift_JIS`, `EUC-KR`, `Big5`). Configurable par
  job (`PrintTextOptions.encoding`) ou par item (`TextStyle.encoding`). `defaultCodePage` /
  `codePage` restent acceptés comme **alias** (latin). Les langues idéographiques ne sont
  donc plus bloquées par l'abstraction latine.
  - Latin : `FS .` (annule le mode CJK) + `ESC t` (page de code) + remap d'accents.
  - CJK : `FS &` (mode idéogrammes) + encodage natif dans le charset (Android `Charset`,
    iOS `CFStringEncoding`). L'encodeur web de référence reste latin (CJK = natif only).

### Modifié
- **`FS .` n'est plus émis inconditionnellement** (v7.0.8) mais **selon l'encodage** : latin
  → `FS .`, CJK → `FS &`. Émis par `openStyle` avant chaque texte (au lieu du reset).

## [7.0.9]

### Corrigé
- **Android — `render.paperWidthMm` ignoré (image non imprimée sur imprimante 58 mm).**
  L'option `paperWidthMm` du `printImage` était documentée mais jamais appliquée côté Android
  (ni parsée, ni utilisée) : le plugin gardait 576 pts (80 mm) par défaut. Une imprimante
  Bluetooth générique 58 mm (384 pts) recevait alors un raster 576 pts trop large qu'elle
  **rejette silencieusement** (l'appel réussit mais rien ne s'imprime). `paperWidthMm` est
  désormais parsé et **prioritaire** sur la largeur par défaut du profil
  (`widthDots` > `paperWidthMm` > profil), avec conversion 58→384 / 80→576 / 112→832.

## [7.0.8]

### Corrigé
- **Android — accents transformés en idéogrammes sur certaines imprimantes Bluetooth.**
  Les imprimantes "génériques" chinoises démarrent souvent en **mode caractères chinois
  (double-octet)** : elles avalent les octets ≥0x80 par paires (ex. `éàçùê` → `獣琦`) et
  ignorent la page de code (`ESC t`). L'encodeur émet désormais **`FS .`** (annule le mode
  Kanji/chinois) après chaque `ESC @`, ce qui force le mono-octet et rend `ESC t` + le
  remap d'accents (v7.0.7) effectifs. Appliqué côté Kotlin et TS (parité).

## [7.0.7]

### Corrigé
- **Android — accents cassés en ESC/POS générique (BLE + Bluetooth).** L'encodeur
  Android envoyait toujours les octets Latin-1 sans tenir compte de la page de code :
  sur une imprimante en CP437 (très répandu), `é`/`à`/`ç`… sortaient en grec/cyrillique
  (é→Θ, à→α…). `EscPosTextEncoder` est désormais **conscient de la page de code**
  (miroir d'iOS) : `encodeString(value, codePage)` remappe les accents FR vers les bons
  octets DOS pour CP437/CP850/CP858. Même correction portée à l'encodeur TS de référence
  (parité TS/Kotlin/Swift). Sélectionner la page via `defaultCodePage` (ex. `'CP437'`).
- **Android — image/logo non imprimée en Bluetooth-classic (SPP).** Les imprimantes SPP
  bon marché n'ont pas de contrôle de flux : un gros raster envoyé d'un trait débordait
  leur buffer (image perdue) alors que le texte passait. `BluetoothSppTransport` cadence
  désormais les gros jobs (paquets de 512 o + micro-pause de 15 ms). Le BLE n'était pas
  affecté (ACK par paquet).

## [7.0.6]

### Corrigé
- **Android — crash fatal pendant la découverte Zebra Bluetooth.** `ZebraAdapter`
  lisait l'adresse via `getAddress()` en réflexion, mais `DiscoveredPrinter` l'expose
  en **champ public `address`** (pas de getter) ; `SdkReflect.call` levait alors
  `NoSuchMethodException`, et comme le callback s'exécute dans le `BroadcastReceiver`
  de découverte du SDK Zebra, l'exception faisait planter l'app
  (`UndeclaredThrowableException`). Ajout de `SdkReflect.callOrNull` (renvoie `null`
  au lieu de lever) pour que le fallback getter→champ fonctionne, et durcissement de
  `SdkReflect.proxy` : une exception dans un callback de SDK ne peut plus crasher
  l'app hôte.

### Documentation
- `docs/SDK_INTEGRATION.md` : procédures d'installation Android détaillées et
  vérifiées (Brother `.aar` double-dézippage, Epson `ePOS2.jar` + `.so` dans
  `jniLibs/`, Zebra Link-OS — tous les jars `lib/` + bloc `packaging` anti-collision
  `META-INF/DEPENDENCIES`), section ProGuard/R8 vulgarisée.

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
