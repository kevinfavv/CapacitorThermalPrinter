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

    /// Émetteur d'états de job (branché par le plugin sur notifyListeners).
    var onJobUpdate: ((JobUpdate) -> Void)?

    /// Émetteur de changement de statut (branché par le plugin sur 'statusChange').
    var onStatusChange: ((PrinterStatus) -> Void)?

    /// Registre des moniteurs de statut actifs (Phase 6).
    private var monitors: [String: Task<Void, Never>] = [:]
    private let monitorLock = NSLock()

    private func emitJob(_ jobId: String, _ printerId: String, _ state: String,
                         holdReason: String? = nil, progress: Double? = nil,
                         errorCode: ErrorCode? = nil, message: String? = nil) {
        onJobUpdate?(JobUpdate(jobId: jobId, printerId: printerId, state: state,
                               holdReason: holdReason, progress: progress, errorCode: errorCode, message: message))
    }

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

    func connect(_ printerId: String, timeoutMs: Int, forceAdapter: AdapterId?, setAsDefault: Bool = false) async throws -> Bool {
        let profile = try resolveProfile(printerId, forceAdapter: forceAdapter)
        guard let adapter = adapterFor(profile.adapter) else {
            throw PrinterError(.UNSUPPORTED_PRINTER, "Aucun adapter pour \(profile.adapter.rawValue)")
        }
        guard adapter.isAvailable() else {
            throw PrinterError(.SDK_NOT_AVAILABLE, "Adapter \(profile.adapter.rawValue) indisponible")
        }
        try await adapter.connect(profile, timeoutMs: timeoutMs)
        let connected = adapter.isConnected(printerId)
        // setAsDefault UNIQUEMENT si la connexion a réussi.
        if connected && setAsDefault {
            store.upsert(profile)
            store.setDefault(printerId)
            Logger.shared.log("connect", "set-default-after-connect", ["id": printerId])
        }
        return connected
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

    struct PrintTextRequest {
        let printerId: String?
        let items: [PrintItem]
        let defaultCodePage: String
        let cut: Bool
        let feedLines: Int
        let timeoutMs: Int
        let autoReconnect: Bool
    }

    struct PrintOutcome {
        let printerId: String
        let adapter: AdapterId
        let jobId: String
        let state: String
        let bytesSent: Int
        let durationMs: Int
        let status: PrinterStatus?
    }

    func printImage(_ req: PrintRequest) async throws -> PrintOutcome {
        let started = Date()
        let jobId = UUID().uuidString
        let profile = try resolveTargetProfile(req.printerId)
        emitJob(jobId, profile.id, "pending")
        guard let adapter = adapterFor(profile.adapter) else {
            let e = PrinterError(.UNSUPPORTED_PRINTER, "Adapter introuvable")
            emitJob(jobId, profile.id, "failed", errorCode: e.code, message: e.message); throw e
        }
        do {
            if !adapter.isConnected(profile.id) {
                guard req.autoReconnect else { throw PrinterError(.CONNECTION_FAILED, "Imprimante non connectée") }
                try await ensureConnected(profile, timeoutMs: req.timeoutMs)
            }
            try await preflightHold(adapter, profile, jobId)

            let image = try await loadImage(req)
            let render = resolveRenderOptions(profile, req.render)
            let resized = render.resize ? try ImageProcessor.resizeToWidth(image, targetWidth: render.widthDots) : image

            emitJob(jobId, profile.id, "printing", progress: 0.1)
            let bytes = try await adapter.printImage(profile, image: resized, options: render)
            let status = try? await adapter.getStatus(profile)
            let duration = Int(Date().timeIntervalSince(started) * 1000)
            emitJob(jobId, profile.id, "completed", progress: 1.0)
            return PrintOutcome(printerId: profile.id, adapter: profile.adapter, jobId: jobId, state: "completed", bytesSent: bytes, durationMs: duration, status: status)
        } catch let e as PrinterError {
            emitJob(jobId, profile.id, "failed", errorCode: e.code, message: e.message); throw e
        }
    }

    func printText(_ req: PrintTextRequest) async throws -> PrintOutcome {
        let started = Date()
        let jobId = UUID().uuidString
        let profile = try resolveTargetProfile(req.printerId)
        emitJob(jobId, profile.id, "pending")
        guard let adapter = adapterFor(profile.adapter) else {
            let e = PrinterError(.UNSUPPORTED_PRINTER, "Adapter introuvable")
            emitJob(jobId, profile.id, "failed", errorCode: e.code, message: e.message); throw e
        }
        do {
            if !adapter.isConnected(profile.id) {
                guard req.autoReconnect else { throw PrinterError(.CONNECTION_FAILED, "Imprimante non connectée") }
                try await ensureConnected(profile, timeoutMs: req.timeoutMs)
            }
            try await preflightHold(adapter, profile, jobId)

            emitJob(jobId, profile.id, "printing", progress: 0.1)
            let bytes = try await adapter.printItems(profile, items: req.items, defaultCodePage: req.defaultCodePage, cut: req.cut, feedLines: req.feedLines)
            let status = try? await adapter.getStatus(profile)
            let duration = Int(Date().timeIntervalSince(started) * 1000)
            emitJob(jobId, profile.id, "completed", progress: 1.0)
            return PrintOutcome(printerId: profile.id, adapter: profile.adapter, jobId: jobId, state: "completed", bytesSent: bytes, durationMs: duration, status: status)
        } catch let e as PrinterError {
            emitJob(jobId, profile.id, "failed", errorCode: e.code, message: e.message); throw e
        }
    }

    /// Lit le statut avant impression ; émet HOLD + lève si papier/capot bloquant.
    private func preflightHold(_ adapter: PrinterAdapter, _ profile: PrinterProfile, _ jobId: String) async throws {
        guard profile.capabilities.supportsStatus else { return }
        guard let st = try? await adapter.getStatus(profile) else { return }
        if st.paper == "empty" {
            emitJob(jobId, profile.id, "hold", holdReason: "paper_empty")
            throw PrinterError(.PAPER_EMPTY, "Plus de papier", retryable: true)
        }
        if st.coverOpen == true {
            emitJob(jobId, profile.id, "hold", holdReason: "cover_open")
            throw PrinterError(.COVER_OPEN, "Capot ouvert", retryable: true)
        }
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

    // MARK: Monitoring de statut (Phase 6)

    /// Démarre un polling périodique du statut et émet `statusChange` uniquement
    /// quand l'état pertinent change (connexion/online/papier/capot). Idempotent.
    func startStatusMonitor(_ printerId: String, intervalMs: Int) {
        stopStatusMonitor(printerId)
        let interval = min(max(intervalMs, 1000), 300_000)
        let task = Task { [weak self] in
            var lastKey: String?
            while !Task.isCancelled {
                guard let self = self else { return }
                let status: PrinterStatus
                do {
                    status = try await self.getStatus(printerId)
                } catch let e as PrinterError {
                    status = PrinterStatus(id: printerId, connection: "error", online: false, paper: "unknown", errorCode: e.code, rawStatus: e.message)
                } catch {
                    status = PrinterStatus(id: printerId, connection: "error", online: false, paper: "unknown", rawStatus: "\(error)")
                }
                let key = "\(status.connection)|\(status.online)|\(status.paper)|\(String(describing: status.coverOpen))|\(String(describing: status.errorCode))"
                if key != lastKey {
                    lastKey = key
                    self.onStatusChange?(status)
                    Logger.shared.log("status", "change", ["id": printerId, "paper": status.paper])
                }
                try? await Task.sleep(nanoseconds: UInt64(interval) * 1_000_000)
            }
        }
        monitorLock.lock(); monitors[printerId] = task; monitorLock.unlock()
        Logger.shared.log("status", "monitor-start", ["id": printerId, "intervalMs": interval])
    }

    /// Arrête le moniteur d'une imprimante (no-op si absent).
    func stopStatusMonitor(_ printerId: String) {
        monitorLock.lock(); let t = monitors.removeValue(forKey: printerId); monitorLock.unlock()
        t?.cancel()
    }

    /// Arrête tous les moniteurs.
    func stopAllMonitors() {
        monitorLock.lock(); let all = monitors; monitors.removeAll(); monitorLock.unlock()
        all.values.forEach { $0.cancel() }
    }

    /// État courant de chaque adapter/SDK (cf. getActiveSdks).
    func activeSdks() -> [[String: Any]] {
        let star = adapters.first { $0 is StarAdapter }?.isAvailable() ?? false
        let epson = adapters.first { $0 is EpsonAdapter }?.isAvailable() ?? false
        let brother = adapters.first { $0 is BrotherAdapter }?.isAvailable() ?? false
        let zebra = adapters.first { $0 is ZebraAdapter }?.isAvailable() ?? false
        return [
            ["adapter": "escpos", "label": "ESC/POS générique", "available": true, "requiresSdk": false, "transports": ["wifi", "ethernet"]],
            ["adapter": "star", "label": "Star StarXpand", "available": star, "requiresSdk": true, "transports": ["wifi", "bluetooth", "ble"]],
            ["adapter": "epson", "label": "Epson ePOS2", "available": epson, "requiresSdk": true, "transports": ["wifi", "bluetooth"]],
            ["adapter": "brother", "label": "Brother", "available": brother, "requiresSdk": true, "transports": ["wifi", "bluetooth", "ble"]],
            ["adapter": "zebra", "label": "Zebra Link-OS", "available": zebra, "requiresSdk": true, "transports": ["wifi", "bluetooth"]],
            ["adapter": "rawTcp", "label": "TCP brut", "available": true, "requiresSdk": false, "transports": ["wifi", "ethernet"]],
        ]
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
