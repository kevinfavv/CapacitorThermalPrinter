import Foundation
import UIKit

/// Adapter BLE générique (CoreBluetooth) pour imprimantes ESC/POS BLE.
///
/// Cible les imprimantes "Bluetooth" qui exposent un service BLE (très courant sur les
/// 58 mm bon marché type MP210, souvent Classic + BLE). Le Classic SPP étant inutilisable
/// sur iOS, le BLE est la seule voie générique. Encodage ESC/POS identique à l'adapter
/// réseau (texte natif + image raster), transport = `BleManager`.
final class BleAdapter: PrinterAdapter {

    let id: AdapterId = .ble
    private var addrByPrinter: [String: String] = [:]
    private let lock = NSLock()

    func isAvailable() -> Bool {
        // CoreBluetooth est toujours lié ; l'état réel (BT off / refus) est géré à la
        // connexion. On expose donc l'adapter (transport intégré, pas un SDK fabricant).
        return true
    }

    func canHandle(_ profile: PrinterProfile) -> Bool {
        profile.adapter == .ble && profile.transport == .ble
    }

    func supportsTextItems() -> Bool { true }

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        await BleManager.shared.scan(timeoutMs: timeoutMs) { peripheral in
            let addr = peripheral.identifier.uuidString
            onFound(DiscoveredPrinter(
                id: "ble:\(addr)",
                name: peripheral.name ?? "Imprimante BLE",
                brand: nil, model: nil,
                transport: .ble,
                adapter: .ble,
                address: addr,
                capabilities: Capabilities(),
                discoveredBy: ["ble"]
            ))
        }
    }

    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws {
        try await BleManager.shared.connect(address: profile.address, timeoutMs: timeoutMs)
        lock.lock(); addrByPrinter[profile.id] = profile.address; lock.unlock()
    }

    func isConnected(_ printerId: String) -> Bool {
        BleManager.shared.isConnected(address(for: printerId))
    }

    func disconnect(_ printerId: String) async {
        let addr = address(for: printerId)
        BleManager.shared.disconnect(addr)
        lock.lock(); addrByPrinter[printerId] = nil; lock.unlock()
    }

    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int {
        let mono = try ImageProcessor.toMono(image, options: options)
        let raster = ImageProcessor.encodeEscPosRaster(mono)
        let job = EscPosCommands.buildJob(
            raster: raster, align: options.align, feedLines: options.feedLines,
            cut: options.cut && profile.capabilities.supportsCut,
            openDrawer: options.openCashDrawer && profile.capabilities.supportsCashDrawer
        )
        var sent = 0
        for _ in 0..<max(1, options.copies) {
            try await BleManager.shared.write(job, address: profile.address)
            sent += job.count
        }
        return sent
    }

    func printItems(_ profile: PrinterProfile, items: [PrintItem], defaultCodePage: String, cut: Bool, feedLines: Int) async throws -> Int {
        let columns = profile.capabilities.printableDots <= 420 ? 32 : 48
        let encoded = EscPosTextEncoder.encode(items, defaultCodePage: defaultCodePage, columns: columns)
        var job = encoded.bytes
        if feedLines > 0 { job += EscPosCommands.feed(feedLines) }
        if cut && profile.capabilities.supportsCut { job += EscPosCommands.CUT_PARTIAL }
        try await BleManager.shared.write(job, address: profile.address)
        return job.count
    }

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        let connected = BleManager.shared.isConnected(profile.address)
        return PrinterStatus(
            id: profile.id,
            connection: connected ? "connected" : "disconnected",
            online: connected, paper: "unknown",
            rawStatus: "BLE ESC/POS : statut temps réel non lu (unidirectionnel)"
        )
    }

    /// profile.id discovery = "ble:<addr>" et profile.address = "<addr>". On retrouve
    /// l'adresse via la map de connexion, sinon en retirant le préfixe "ble:".
    private func address(for printerId: String) -> String {
        lock.lock(); let mapped = addrByPrinter[printerId]; lock.unlock()
        if let mapped = mapped { return mapped }
        return printerId.hasPrefix("ble:") ? String(printerId.dropFirst(4)) : printerId
    }
}
