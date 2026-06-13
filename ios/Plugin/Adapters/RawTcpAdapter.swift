import Foundation
import UIKit

/// Adapter filet de sécurité réseau iOS : raster ESC/POS sur TCP brut, sans statut.
final class RawTcpAdapter: PrinterAdapter {

    let id: AdapterId = .rawTcp
    private var connections: [String: TcpTransport] = [:]
    private let lock = NSLock()

    func isAvailable() -> Bool { true }

    func supportsTextItems() -> Bool { true }

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {}

    func canHandle(_ profile: PrinterProfile) -> Bool {
        profile.adapter == .rawTcp && (profile.transport == .wifi || profile.transport == .ethernet)
    }

    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws {
        if isConnected(profile.id) { return }
        var host = profile.address
        var port: UInt16 = 9100
        if let idx = profile.address.lastIndex(of: ":") {
            host = String(profile.address[..<idx])
            port = UInt16(profile.address[profile.address.index(after: idx)...]) ?? 9100
        }
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
        guard let transport = t else { throw PrinterError(.CONNECTION_FAILED, "rawTcp non connecté") }
        let mono = try ImageProcessor.toMono(image, options: options)
        let raster = ImageProcessor.encodeEscPosRaster(mono)
        let job = EscPosCommands.buildJob(raster: raster, align: options.align, feedLines: options.feedLines, cut: options.cut, openDrawer: options.openCashDrawer)
        try await transport.write(job)
        return job.count
    }

    func printItems(_ profile: PrinterProfile, items: [PrintItem], defaultCodePage: String, cut: Bool, feedLines: Int) async throws -> Int {
        lock.lock(); let t = connections[profile.id]; lock.unlock()
        guard let transport = t else { throw PrinterError(.CONNECTION_FAILED, "rawTcp non connecté") }
        let columns = profile.capabilities.printableDots <= 420 ? 32 : 48
        let encoded = EscPosTextEncoder.encode(items, defaultCodePage: defaultCodePage, columns: columns)
        var job = encoded.bytes
        if feedLines > 0 { job += EscPosCommands.feed(feedLines) }
        if cut { job += EscPosCommands.CUT_PARTIAL }
        try await transport.write(job)
        return job.count
    }

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        let c = isConnected(profile.id)
        return PrinterStatus(id: profile.id, connection: c ? "connected" : "disconnected", online: c, paper: "unknown", rawStatus: "rawTcp: statut non supporté")
    }
}
