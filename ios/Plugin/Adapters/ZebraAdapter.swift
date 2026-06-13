import Foundation
import UIKit

// Le SDK Link-OS iOS est livré en xcframework manuel (licence Zebra, non
// redistribuable, pas de pod/SPM officiel). Nom de module possible `ZSDK_API`.
// Import absent -> stub inerte. Voir docs/SDK_INTEGRATION.md (§ Zebra iOS).
#if canImport(ZSDK_API)
import ZSDK_API
#endif

/// Adapter Zebra iOS (Link-OS SDK / ZSDK).
///
/// ⚠️ Comme sur Android : Zebra = ZPL/CPCL, JAMAIS ESC/POS. Le SDK convertit un
/// CGImage en ZPL et l'imprime via GraphicsUtil. Transports : TCP + Bluetooth MFi.
/// API ObjC à VÉRIFIER sur device avec le xcframework réel.
final class ZebraAdapter: PrinterAdapter {

    let id: AdapterId = .zebra
    private var connections: [String: AnyObject] = [:]
    private let lock = NSLock()

    func isAvailable() -> Bool {
        #if canImport(ZSDK_API)
        return true
        #else
        return NSClassFromString("TcpPrinterConnection") != nil || NSClassFromString("ZebraPrinterFactory") != nil
        #endif
    }

    func canHandle(_ profile: PrinterProfile) -> Bool { isAvailable() && profile.adapter == .zebra }

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        // NetworkDiscoverer.localBroadcast(...) disponible mais bloquant/ObjC ;
        // les Zebra réseau restent trouvées par le scan TCP générique.
    }

    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws {
        #if canImport(ZSDK_API)
        if isConnected(profile.id) { return }
        let (host, port) = Self.hostPort(profile.address)
        guard let conn = TcpPrinterConnection(address: host, andWithPort: port) else {
            throw PrinterError(.CONNECTION_FAILED, "Init connexion Zebra échouée")
        }
        conn.setMaxTimeoutForRead(Int32(timeoutMs))
        if !conn.open() {
            throw PrinterError(.CONNECTION_FAILED, "Connexion Zebra échouée: \(profile.address)", retryable: true)
        }
        lock.lock(); connections[profile.id] = conn; lock.unlock()
        #else
        throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Zebra Link-OS absent")
        #endif
    }

    func isConnected(_ printerId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return connections[printerId] != nil
    }

    func disconnect(_ printerId: String) async {
        #if canImport(ZSDK_API)
        lock.lock(); let c = connections.removeValue(forKey: printerId) as? ZebraPrinterConnection; lock.unlock()
        c?.close()
        #endif
    }

    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int {
        #if canImport(ZSDK_API)
        lock.lock(); let c = connections[profile.id] as? ZebraPrinterConnection; lock.unlock()
        guard let conn = c else { throw PrinterError(.CONNECTION_FAILED, "Zebra non connecté: \(profile.id)") }
        guard let cg = image.cgImage else { throw PrinterError(.IMAGE_INVALID, "CGImage indisponible") }
        do {
            let printer = try ZebraPrinterFactory.getInstance(conn)
            let tools = printer.getGraphicsUtil()
            for _ in 0..<max(1, options.copies) {
                try tools?.print(cg, atX: 0, atY: 0, withWidth: cg.width, withHeight: cg.height, andIsInsideFormat: false)
            }
        } catch {
            throw PrinterError(.PRINT_FAILED, "Impression Zebra échouée", detail: "\(error)", retryable: true)
        }
        return cg.width * cg.height / 8
        #else
        throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Zebra Link-OS absent")
        #endif
    }

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        #if canImport(ZSDK_API)
        lock.lock(); let c = connections[profile.id] as? ZebraPrinterConnection; lock.unlock()
        guard let conn = c else {
            return PrinterStatus(id: profile.id, connection: "disconnected", online: false, paper: "unknown")
        }
        do {
            let printer = try ZebraPrinterFactory.getInstance(conn)
            let st = try printer.getCurrentStatus()
            let paperOut = st.isPaperOut
            let headOpen = st.isHeadOpen
            return PrinterStatus(
                id: profile.id, connection: "connected", online: st.isReadyToPrint,
                paper: paperOut ? "empty" : "ok", coverOpen: headOpen,
                errorCode: paperOut ? .PAPER_EMPTY : (headOpen ? .COVER_OPEN : nil)
            )
        } catch {
            return PrinterStatus(id: profile.id, connection: "error", online: false, paper: "unknown", rawStatus: "\(error)")
        }
        #else
        return PrinterStatus(id: profile.id, connection: isConnected(profile.id) ? "connected" : "disconnected",
                             online: isConnected(profile.id), paper: "unknown")
        #endif
    }

    // MARK: Helpers

    private static func hostPort(_ address: String) -> (String, Int) {
        let parts = address.split(separator: ":")
        let host = parts.first.map(String.init) ?? address
        let port = parts.count > 1 ? (Int(parts[1]) ?? 9100) : 9100
        return (host, port)
    }
}
