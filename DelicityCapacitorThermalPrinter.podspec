require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name = 'DelicityCapacitorThermalPrinter'
  s.version = package['version']
  s.summary = package['description']
  s.license = package['license']
  s.homepage = package['repository']['url']
  s.author = package['author']
  s.source = { :git => package['repository']['url'], :tag => s.version.to_s }
  s.source_files = 'ios/Plugin/**/*.{swift,h,m,c,cc,mm,cpp}'
  s.ios.deployment_target = '14.0'
  s.dependency 'Capacitor'
  s.swift_version = '5.1'

  # ---- SDK fabricants iOS ----
  # Le plugin compile SANS aucun SDK fabricant (compilation conditionnelle
  # `#if canImport(...)` dans les adapters). On NE déclare donc PAS ici de
  # dépendance fabricant : c'est l'APP consommatrice qui ajoute le(s) SDK qu'elle
  # utilise (licences fabricant -> non redistribuables dans ce pod). Voir
  # docs/SDK_INTEGRATION.md :
  #   • Star    : Swift Package Manager `StarXpand-SDK-iOS`  -> #if canImport(StarIO10)
  #   • Brother : `pod 'BRLMPrinterKit'` dans le Podfile app  -> #if canImport(BRLMPrinterKit)
  #   • Epson   : xcframework manuel (libepos2)               -> #if canImport(libepos2)
  #   • Zebra   : xcframework manuel (ZSDK_API)               -> #if canImport(ZSDK_API)
end
