import Foundation
import UIKit

/// Adapter Zebra iOS (Link-OS SDK / ZSDK).
///
/// Le SDK Zebra iOS est livré en **librairie statique + headers ObjC, sans module Swift**,
/// donc `#if canImport(ZSDK_API)` ne peut pas l'activer (contrairement à Star/Epson/Brother).
/// On passe par `ZebraBridge` (pont Objective-C) qui pilote le SDK **au runtime**
/// (`NSClassFromString`) : aucune dépendance de symbole au link, le support s'active tout
/// seul dès que le SDK est présent dans la target App — exactement comme les autres.
///
/// ⚠️ Comme sur Android : Zebra = ZPL/CPCL, JAMAIS ESC/POS. Le SDK convertit un CGImage en
/// ZPL et l'imprime via GraphicsUtil. Transports : TCP (9100) + Bluetooth MFi.
final class ZebraAdapter: PrinterAdapter {

    let id: AdapterId = .zebra
    private var connections: [String: AnyObject] = [:]
    private let lock = NSLock()

    func isAvailable() -> Bool {
        // Honnête : disponible uniquement si les classes Zebra sont réellement liées au
        // binaire (SDK ajouté à l'app). Détecté au runtime par le pont ObjC.
        return ZebraBridge.isAvailable()
    }

    func canHandle(_ profile: PrinterProfile) -> Bool { isAvailable() && profile.adapter == .zebra }

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        // NetworkDiscoverer.localBroadcast(...) du SDK est disponible mais bloquant/ObjC ;
        // les Zebra réseau restent trouvées par le scan TCP générique.
    }

    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws {
        guard isAvailable() else { throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Zebra Link-OS absent") }
        if isConnected(profile.id) { return }
        let conn: AnyObject
        do {
            switch profile.transport {
            case .bluetooth, .ble:
                conn = try ZebraBridge.connectBt(profile.address) as AnyObject
            default:
                let (host, port) = Self.hostPort(profile.address)
                conn = try ZebraBridge.connectTcp(host, port: port, timeoutMs: timeoutMs) as AnyObject
            }
        } catch {
            throw PrinterError(.CONNECTION_FAILED, "Connexion Zebra échouée: \(profile.address)", detail: "\(error)", retryable: true)
        }
        lock.lock(); connections[profile.id] = conn; lock.unlock()
    }

    func isConnected(_ printerId: String) -> Bool {
        lock.lock(); let c = connections[printerId]; lock.unlock()
        guard let conn = c else { return false }
        return ZebraBridge.isOpen(conn)
    }

    func disconnect(_ printerId: String) async {
        lock.lock(); let c = connections.removeValue(forKey: printerId); lock.unlock()
        if let conn = c { ZebraBridge.disconnect(conn) }
    }

    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int {
        lock.lock(); let c = connections[profile.id]; lock.unlock()
        guard let conn = c else { throw PrinterError(.CONNECTION_FAILED, "Zebra non connecté: \(profile.id)") }
        guard let cg = image.cgImage else { throw PrinterError(.IMAGE_INVALID, "CGImage indisponible") }
        do {
            try ZebraBridge.printImage(conn, cgImage: cg, copies: max(1, options.copies))
        } catch {
            throw PrinterError(.PRINT_FAILED, "Impression Zebra échouée", detail: "\(error)", retryable: true)
        }
        return cg.width * cg.height / 8
    }

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        lock.lock(); let c = connections[profile.id]; lock.unlock()
        guard let conn = c else {
            return PrinterStatus(id: profile.id, connection: "disconnected", online: false, paper: "unknown")
        }
        do {
            let st = try ZebraBridge.status(conn)
            let paperOut = st["paperOut"]?.boolValue ?? false
            let headOpen = st["headOpen"]?.boolValue ?? false
            let ready = st["ready"]?.boolValue ?? false
            return PrinterStatus(
                id: profile.id, connection: "connected", online: ready,
                paper: paperOut ? "empty" : "ok", coverOpen: headOpen,
                errorCode: paperOut ? .PAPER_EMPTY : (headOpen ? .COVER_OPEN : nil)
            )
        } catch {
            return PrinterStatus(id: profile.id, connection: "error", online: false, paper: "unknown", rawStatus: "\(error)")
        }
    }

    // MARK: Helpers

    private static func hostPort(_ address: String) -> (String, Int) {
        let parts = address.split(separator: ":")
        let host = parts.first.map(String.init) ?? address
        let port = parts.count > 1 ? (Int(parts[1]) ?? 9100) : 9100
        return (host, port)
    }
}
