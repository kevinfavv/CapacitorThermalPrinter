import Foundation
import UIKit

// Le module ePOS2 iOS est livré en xcframework manuel (licence Epson, non
// redistribuable). Le nom de module peut varier selon la version du SDK
// (`libepos2`). Si l'import échoue -> stub inerte (aucune casse de build).
// Voir docs/SDK_INTEGRATION.md (§ Epson iOS).
#if canImport(libepos2)
import libepos2
#endif

/// Adapter Epson iOS basé sur le SDK ePOS2.
///
/// ⚠️ API ObjC pilotée en Swift : à VÉRIFIER sur device avec le xcframework réel
/// (la version du SDK peut ajuster les signatures / le nom de module).
/// Chemin principal : impression IMAGE (réception rendue en bitmap).
final class EpsonAdapter: PrinterAdapter {

    let id: AdapterId = .epson
    private var printers: [String: AnyObject] = [:]
    private let lock = NSLock()

    func isAvailable() -> Bool {
        #if canImport(libepos2)
        return true
        #else
        // Détection runtime de secours si le framework est lié sans module Swift.
        return NSClassFromString("Epos2Printer") != nil
        #endif
    }

    func canHandle(_ profile: PrinterProfile) -> Bool { isAvailable() && profile.adapter == .epson }

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        #if canImport(libepos2)
        // Découverte SDK ePOS2 : TCP + Bluetooth (MFi) + BLE + USB. Indispensable pour les
        // imprimantes Epson en Bluetooth (le scan réseau générique ne les voit pas).
        // ⚠️ L'app DOIT déclarer la protocol string MFi Epson `com.epson.escpos` dans
        // `UISupportedExternalAccessoryProtocols` (Info.plist), sinon iOS ne remonte pas
        // l'imprimante appairée — voir docs/SDK_INTEGRATION.md (§ Epson iOS).
        let filter = Epos2FilterOption()
        filter.deviceType = EPOS2_TYPE_ALL.rawValue
        filter.portType = EPOS2_PORTTYPE_ALL.rawValue
        let delegate = EpsonDiscoveryDelegate(onFound: onFound)
        var result = Epos2Discovery.start(filter, delegate: delegate)
        if result != EPOS2_SUCCESS.rawValue {
            _ = Epos2Discovery.stop() // un scan précédent traîne peut-être : on réessaie
            result = Epos2Discovery.start(filter, delegate: delegate)
        }
        guard result == EPOS2_SUCCESS.rawValue else { return }
        // Scan asynchrone (callbacks delegate) : on le laisse courir puis on l'arrête.
        try? await Task.sleep(nanoseconds: UInt64(max(1000, timeoutMs)) * 1_000_000)
        _ = Epos2Discovery.stop()
        withExtendedLifetime(delegate) {} // garder le delegate vivant pendant tout le scan
        #endif
    }

    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws {
        #if canImport(libepos2)
        if isConnected(profile.id) { return }
        guard let printer = Epos2Printer(printerSeries: Self.series(for: profile.model), lang: EPOS2_MODEL_ANK.rawValue) else {
            throw PrinterError(.SDK_NOT_AVAILABLE, "Init Epos2Printer échouée")
        }
        let result = printer.connect(Self.target(for: profile), timeout: Int(timeoutMs))
        guard result == EPOS2_SUCCESS.rawValue else {
            throw PrinterError(.CONNECTION_FAILED, "Connexion Epson échouée: \(profile.address)", detail: "\(result)", retryable: true)
        }
        lock.lock(); printers[profile.id] = printer; lock.unlock()
        #else
        throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Epson ePOS2 absent")
        #endif
    }

    func isConnected(_ printerId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return printers[printerId] != nil
    }

    func disconnect(_ printerId: String) async {
        #if canImport(libepos2)
        lock.lock(); let p = printers.removeValue(forKey: printerId) as? Epos2Printer; lock.unlock()
        p?.disconnect()
        p?.clearCommandBuffer()
        #endif
    }

    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int {
        #if canImport(libepos2)
        lock.lock(); let p = printers[profile.id] as? Epos2Printer; lock.unlock()
        guard let printer = p else { throw PrinterError(.CONNECTION_FAILED, "Epson non connecté: \(profile.id)") }
        for _ in 0..<max(1, options.copies) {
            printer.beginTransaction()
            printer.add(image, x: 0, y: 0,
                        width: Int(image.size.width), height: Int(image.size.height),
                        color: EPOS2_COLOR_1.rawValue, mode: EPOS2_MODE_MONO.rawValue,
                        halftone: Self.halftone(options.dithering), brightness: 1.0,
                        compress: EPOS2_COMPRESS_AUTO.rawValue)
            if options.cut && profile.capabilities.supportsCut { printer.addCut(EPOS2_CUT_FEED.rawValue) }
            if options.openCashDrawer && profile.capabilities.supportsCashDrawer {
                printer.addPulse(EPOS2_DRAWER_2PIN.rawValue, time: EPOS2_PULSE_100.rawValue)
            }
            let result = printer.sendData(Int(EPOS2_PARAM_DEFAULT))
            printer.endTransaction()
            printer.clearCommandBuffer()
            if result != EPOS2_SUCCESS.rawValue {
                throw PrinterError(.PRINT_FAILED, "Impression Epson échouée", detail: "\(result)", retryable: true)
            }
        }
        return Int(image.size.width * image.size.height) / 8
        #else
        throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Epson ePOS2 absent")
        #endif
    }

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        let connected = isConnected(profile.id)
        return PrinterStatus(id: profile.id, connection: connected ? "connected" : "disconnected",
                             online: connected, paper: "unknown",
                             rawStatus: "Epson: statut détaillé via getStatus() à activer si besoin")
    }

    // MARK: Helpers typés

    #if canImport(libepos2)
    private static func target(for profile: PrinterProfile) -> String {
        if profile.address.hasPrefix("TCP:") || profile.address.hasPrefix("BT:")
            || profile.address.hasPrefix("BLE:") || profile.address.hasPrefix("USB:") {
            return profile.address
        }
        switch profile.transport {
        case .wifi, .ethernet: return "TCP:\(profile.address.split(separator: ":").first.map(String.init) ?? profile.address)"
        case .bluetooth, .ble: return "BT:\(profile.address)"
        case .usb: return "USB:\(profile.address)"
        }
    }

    private static func series(for model: String?) -> Int32 {
        // Best effort : série générique. Affiner via EPOS2_TM_* si le modèle est connu.
        return EPOS2_TM_M30.rawValue
    }

    private static func halftone(_ dithering: String) -> Int32 {
        switch dithering {
        case "none": return EPOS2_HALFTONE_THRESHOLD.rawValue
        case "atkinson", "floyd_steinberg": return EPOS2_HALFTONE_ERROR_DIFFUSION.rawValue
        default: return EPOS2_HALFTONE_DITHER.rawValue
        }
    }
    #endif
}

#if canImport(libepos2)
/// Delegate de découverte ePOS2 : relaie chaque appareil trouvé vers `onFound`.
/// L'`address` est le `target` exact attendu par `Epos2Printer.connect` (ex.
/// `BT:xx:xx:xx`, `TCP:192.168.x.x`, `BLE:xxxx`), donc une imprimante découverte
/// peut être connectée telle quelle.
private final class EpsonDiscoveryDelegate: NSObject, Epos2DiscoveryDelegate {
    private let onFound: (DiscoveredPrinter) -> Void
    init(onFound: @escaping (DiscoveredPrinter) -> Void) { self.onFound = onFound }

    func onDiscovery(_ deviceInfo: Epos2DeviceInfo!) {
        guard let info = deviceInfo else { return }
        let target = info.target ?? ""
        if target.isEmpty { return }
        let transport: Transport
        if target.hasPrefix("BT:") { transport = .bluetooth }
        else if target.hasPrefix("BLE:") { transport = .ble }
        else if target.hasPrefix("USB:") { transport = .usb }
        else { transport = .wifi }
        let name = info.deviceName ?? ""
        onFound(DiscoveredPrinter(
            id: "epson:\(target)",
            name: name.isEmpty ? "Epson" : name,
            brand: "Epson",
            transport: transport,
            adapter: .epson,
            address: target,
            discoveredBy: ["epson"]))
    }
}
#endif
