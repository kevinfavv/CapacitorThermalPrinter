import Foundation
import UIKit

/// Adapter ESC/POS générique iOS.
///
/// ⚠️ LIMITE iOS : pas de Bluetooth Classic / SPP générique (voir README).
/// Cet adapter ne gère donc QUE le TCP (Wi-Fi / Ethernet). Pour le Bluetooth
/// d'une imprimante ESC/POS sur iOS, deux seules options réelles :
///   - le fabricant fournit un SDK MFi (Epson/Star/...) -> utiliser leur adapter,
///   - l'imprimante expose un service BLE exploitable -> BleAdapter (allowlist).
final class EscPosAdapter: PrinterAdapter {

    let id: AdapterId = .escpos
    private var connections: [String: TcpTransport] = [:]
    private let lock = NSLock()

    func isAvailable() -> Bool { true }

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        // Délégué au TcpScanner / Bonjour (DiscoveryManager).
    }

    func canHandle(_ profile: PrinterProfile) -> Bool {
        profile.adapter == .escpos && (profile.transport == .wifi || profile.transport == .ethernet)
    }

    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws {
        if isConnected(profile.id) { return }
        let (host, port) = splitHostPort(profile.address, defaultPort: 9100)
        let t = TcpTransport(host: host, port: port)
        try await t.open(timeoutMs: timeoutMs)
        lock.lock(); connections[profile.id] = t; lock.unlock()
    }

    func isConnected(_ printerId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return connections[printerId]?.isOpen == true
    }

    func disconnect(_ printerId: String) async {
        lock.lock(); let t = connections.removeValue(forKey: printerId); lock.unlock()
        t?.close()
    }

    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int {
        lock.lock(); let t = connections[profile.id]; lock.unlock()
        guard let transport = t else {
            throw PrinterError(.CONNECTION_FAILED, "ESC/POS non connecté: \(profile.id)")
        }
        let mono = try ImageProcessor.toMono(image, options: options)
        let raster = ImageProcessor.encodeEscPosRaster(mono)
        let job = EscPosCommands.buildJob(
            raster: raster, align: options.align, feedLines: options.feedLines,
            cut: options.cut && profile.capabilities.supportsCut,
            openDrawer: options.openCashDrawer && profile.capabilities.supportsCashDrawer
        )
        var sent = 0
        for _ in 0..<max(1, options.copies) {
            try await transport.write(job)
            sent += job.count
        }
        return sent
    }

    func printItems(_ profile: PrinterProfile, items: [PrintItem], defaultCodePage: String, cut: Bool, feedLines: Int) async throws -> Int {
        lock.lock(); let t = connections[profile.id]; lock.unlock()
        guard let transport = t else { throw PrinterError(.CONNECTION_FAILED, "ESC/POS non connecté: \(profile.id)") }
        let columns = profile.capabilities.printableDots <= 420 ? 32 : 48
        let encoded = EscPosTextEncoder.encode(items, defaultCodePage: defaultCodePage, columns: columns)
        var job = encoded.bytes
        if feedLines > 0 { job += EscPosCommands.feed(feedLines) }
        if cut && profile.capabilities.supportsCut { job += EscPosCommands.CUT_PARTIAL }
        try await transport.write(job)
        return job.count
    }

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        let connected = isConnected(profile.id)
        return PrinterStatus(
            id: profile.id,
            connection: connected ? "connected" : "disconnected",
            online: connected, paper: "unknown",
            rawStatus: "ESC/POS TCP: statut temps réel non lu (unidirectionnel)"
        )
    }

    private func splitHostPort(_ addr: String, defaultPort: UInt16) -> (String, UInt16) {
        if let idx = addr.lastIndex(of: ":"), addr.firstIndex(of: ":") == idx {
            let host = String(addr[..<idx])
            let portStr = String(addr[addr.index(after: idx)...])
            return (host, UInt16(portStr) ?? defaultPort)
        }
        return (addr, defaultPort)
    }
}
