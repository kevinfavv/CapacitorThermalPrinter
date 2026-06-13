import Foundation
import UIKit

/// Cœur applicatif iOS (miroir de ThermalPrinterEngine.kt). Indépendant de Capacitor.
final class ThermalPrinterEngine {

    private let store = PrinterStore()
    private let imageCache = ImageCache()

    /// Registry d'adapters (l'ordre n'importe pas, priorité gérée ailleurs).
    private lazy var adapters: [PrinterAdapter] = [
        EpsonAdapter(), StarAdapter(), BrotherAdapter(), ZebraAdapter(),
        EscPosAdapter(), RawTcpAdapter(),
    ]

    private var lastDiscovered: [DiscoveredPrinter] = []
    private let lock = NSLock()

    // MARK: Découverte

    func discover(_ options: DiscoveryManager.Options, emitPartial: @escaping (DiscoveredPrinter) -> Void) async -> (printers: [DiscoveredPrinter], failed: [String]) {
        Logger.shared.log("discovery", "start")
        let manager = DiscoveryManager(adapters: adapters)
        var (printers, failed) = await manager.discover(options, emitPartial: emitPartial)
        let defaultId = store.getDefault()?.id
        for i in printers.indices {
            printers[i].isDefault = printers[i].id == defaultId
            printers[i].isConnected = adapterFor(printers[i].adapter)?.isConnected(printers[i].id) ?? false
        }
        lock.lock(); lastDiscovered = printers; lock.unlock()
        Logger.shared.log("discovery", "complete", ["count": printers.count])
        return (printers, failed)
    }

    // MARK: Connexion

    func connect(_ printerId: String, timeoutMs: Int, forceAdapter: AdapterId?) async throws -> Bool {
        let profile = try resolveProfile(printerId, forceAdapter: forceAdapter)
        guard let adapter = adapterFor(profile.adapter) else {
            throw PrinterError(.UNSUPPORTED_PRINTER, "Aucun adapter pour \(profile.adapter.rawValue)")
        }
        guard adapter.isAvailable() else {
            throw PrinterError(.SDK_NOT_AVAILABLE, "Adapter \(profile.adapter.rawValue) indisponible")
        }
        try await adapter.connect(profile, timeoutMs: timeoutMs)
        return adapter.isConnected(printerId)
    }

    func disconnect(_ printerId: String) async {
        guard let profile = store.get(printerId) ?? ephemeral(for: printerId),
              let adapter = adapterFor(profile.adapter) else { return }
        await adapter.disconnect(printerId)
    }

    private func ensureConnected(_ profile: PrinterProfile, timeoutMs: Int) async throws {
        guard let adapter = adapterFor(profile.adapter) else {
            throw PrinterError(.UNSUPPORTED_PRINTER, "Adapter introuvable")
        }
        if adapter.isConnected(profile.id) { return }
        Logger.shared.log("connect", "auto-reconnect", ["id": profile.id])
        try await adapter.connect(profile, timeoutMs: timeoutMs)
    }

    // MARK: Impression

    struct PrintRequest {
        let printerId: String?
        let filePath: String?
        let url: String?
        let base64: String?
        let render: RenderOptions?
        let timeoutMs: Int
        let autoReconnect: Bool
    }

    struct PrintOutcome {
        let printerId: String
        let adapter: AdapterId
        let bytesSent: Int
        let durationMs: Int
        let status: PrinterStatus?
    }

    func printImage(_ req: PrintRequest) async throws -> PrintOutcome {
        let started = Date()
        let profile = try resolveTargetProfile(req.printerId)
        guard let adapter = adapterFor(profile.adapter) else {
            throw PrinterError(.UNSUPPORTED_PRINTER, "Adapter introuvable")
        }

        if !adapter.isConnected(profile.id) {
            guard req.autoReconnect else { throw PrinterError(.CONNECTION_FAILED, "Imprimante non connectée") }
            try await ensureConnected(profile, timeoutMs: req.timeoutMs)
        }

        let image = try await loadImage(req)
        let render = resolveRenderOptions(profile, req.render)
        let resized = try ImageProcessor.resizeToWidth(image, targetWidth: render.widthDots)

        let bytes = try await adapter.printImage(profile, image: resized, options: render)
        let status = try? await adapter.getStatus(profile)
        let duration = Int(Date().timeIntervalSince(started) * 1000)
        Logger.shared.log("print", "done", ["id": profile.id, "bytes": bytes, "ms": duration])
        return PrintOutcome(printerId: profile.id, adapter: profile.adapter, bytesSent: bytes, durationMs: duration, status: status)
    }

    // MARK: Profils

    func savedProfiles() -> [PrinterProfile] { store.all() }
    func defaultProfile() -> PrinterProfile? { store.getDefault() }
    func removeProfile(_ id: String) { store.remove(id) }

    func setDefault(_ printerId: String) throws -> PrinterProfile {
        if store.get(printerId) != nil {
            guard let updated = store.setDefault(printerId) else {
                throw PrinterError(.PRINTER_NOT_FOUND, "Profil introuvable")
            }
            return updated
        }
        guard let d = ephemeralDiscovered(printerId) else {
            throw PrinterError(.PRINTER_NOT_FOUND, "Imprimante inconnue: \(printerId)")
        }
        var profile = toEphemeralProfile(d)
        profile.isDefault = true
        store.upsert(profile)
        return store.setDefault(printerId) ?? profile
    }

    func getStatus(_ printerId: String?) async throws -> PrinterStatus {
        let profile = try resolveTargetProfile(printerId)
        guard let adapter = adapterFor(profile.adapter) else {
            throw PrinterError(.UNSUPPORTED_PRINTER, "Adapter introuvable")
        }
        return try await adapter.getStatus(profile)
    }

    func debugLog() -> [[String: Any]] { Logger.shared.snapshot() }

    // MARK: Helpers

    private func adapterFor(_ id: AdapterId) -> PrinterAdapter? { adapters.first { $0.id == id } }

    private func ephemeralDiscovered(_ id: String) -> DiscoveredPrinter? {
        lock.lock(); defer { lock.unlock() }
        return lastDiscovered.first { $0.id == id }
    }

    private func ephemeral(for id: String) -> PrinterProfile? {
        ephemeralDiscovered(id).map(toEphemeralProfile)
    }

    private func resolveTargetProfile(_ printerId: String?) throws -> PrinterProfile {
        guard let id = printerId else {
            guard let def = store.getDefault() else {
                throw PrinterError(.PRINTER_NOT_FOUND, "Aucune imprimante par défaut")
            }
            return def
        }
        return try resolveProfile(id, forceAdapter: nil)
    }

    private func resolveProfile(_ printerId: String, forceAdapter: AdapterId?) throws -> PrinterProfile {
        if var p = store.get(printerId) {
            if let f = forceAdapter { p.adapter = f }
            return p
        }
        guard let d = ephemeralDiscovered(printerId) else {
            throw PrinterError(.PRINTER_NOT_FOUND, "Imprimante inconnue: \(printerId)")
        }
        var base = toEphemeralProfile(d)
        if let f = forceAdapter { base.adapter = f }
        return base
    }

    private func toEphemeralProfile(_ d: DiscoveredPrinter) -> PrinterProfile {
        PrinterProfile(
            id: d.id, adapter: d.adapter, transport: d.transport, address: d.address,
            brand: d.brand, model: d.model, name: d.name,
            capabilities: d.capabilities ?? Capabilities()
        )
    }

    private func loadImage(_ req: PrintRequest) async throws -> UIImage {
        if let path = req.filePath, !path.isEmpty { return try ImageProcessor.decodeFile(path) }
        if let url = req.url, !url.isEmpty {
            let local = try await imageCache.fetch(url)
            return try ImageProcessor.decodeFile(local)
        }
        if let b64 = req.base64, !b64.isEmpty { return try ImageProcessor.decodeBase64(b64) }
        throw PrinterError(.IMAGE_INVALID, "Aucune source image fournie")
    }

    private func resolveRenderOptions(_ profile: PrinterProfile, _ req: RenderOptions?) -> RenderOptions {
        var width = req?.widthDots ?? 0
        if width <= 0 { width = profile.capabilities.printableDots }
        if width <= 0 {
            width = profile.capabilities.paperWidthMm == 58 ? 384 : (profile.capabilities.paperWidthMm == 112 ? 832 : 576)
        }
        var opts = req ?? RenderOptions(widthDots: width)
        opts.widthDots = width
        return opts
    }
}
