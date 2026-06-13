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
        // La découverte Epson (Epos2Discovery) nécessite un delegate ObjC ; les
        // imprimantes Epson réseau restent trouvées par le scan TCP générique.
        // Découverte SDK dédiée à activer si besoin (voir docs).
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
                        color: Int(EPOS2_COLOR_1.rawValue), mode: Int(EPOS2_MODE_MONO.rawValue),
                        halftone: Self.halftone(options.dithering), brightness: 1.0,
                        compress: Int(EPOS2_COMPRESS_AUTO.rawValue))
            if options.cut && profile.capabilities.supportsCut { printer.addCut(Int(EPOS2_CUT_FEED.rawValue)) }
            if options.openCashDrawer && profile.capabilities.supportsCashDrawer {
                printer.addPulse(Int(EPOS2_DRAWER_2PIN.rawValue), time: Int(EPOS2_PULSE_100.rawValue))
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
        if profile.address.hasPrefix("TCP:") || profile.address.hasPrefix("BT:") || profile.address.hasPrefix("USB:") {
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

    private static func halftone(_ dithering: String) -> Int {
        switch dithering {
        case "none": return Int(EPOS2_HALFTONE_THRESHOLD.rawValue)
        case "atkinson", "floyd_steinberg": return Int(EPOS2_HALFTONE_ERROR_DIFFUSION.rawValue)
        default: return Int(EPOS2_HALFTONE_DITHER.rawValue)
        }
    }
    #endif
}
