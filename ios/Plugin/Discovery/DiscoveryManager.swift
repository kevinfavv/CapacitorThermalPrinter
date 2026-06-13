import Foundation

/// Orchestre la découverte agrégée iOS (miroir de DiscoveryManager.kt).
///
/// Sources iOS :
///   - SDK Epson / Star / Brother / Zebra (via adapters, si liés)
///   - Bonjour/mDNS réseau (BonjourScanner)
///   - BLE (optionnel, allowlist)
///
/// PAS de Bluetooth Classic générique sur iOS (voir README "Limites iOS").
final class DiscoveryManager {

    struct Options {
        let sources: Set<String>?
        let timeoutMs: Int
        let networkCidr: String?
        let tcpPorts: [Int]
    }

    private let adapters: [PrinterAdapter]
    init(adapters: [PrinterAdapter]) { self.adapters = adapters }

    func discover(_ options: Options, emitPartial: @escaping (DiscoveredPrinter) -> Void) async -> (printers: [DiscoveredPrinter], failed: [String]) {
        let buffer = SyncBuffer()
        var failed: [String] = []

        func enabled(_ src: String) -> Bool { options.sources == nil || options.sources!.contains(src) }

        let collect: (DiscoveredPrinter) -> Void = { p in
            buffer.add(p)
            emitPartial(p)
        }

        await withTaskGroup(of: Void.self) { group in
            // SDK fabricants
            for adapter in adapters {
                let src: String?
                switch adapter.id {
                case .epson: src = "epson"
                case .star: src = "star"
                case .brother: src = "brother"
                case .zebra: src = "zebra"
                default: src = nil
                }
                if let src = src, enabled(src), adapter.isAvailable() {
                    group.addTask { await adapter.discover(timeoutMs: options.timeoutMs, onFound: collect) }
                }
            }
            // Bonjour réseau
            if enabled("tcp") {
                group.addTask { await BonjourScanner().scan(timeoutMs: options.timeoutMs, onFound: collect) }
            }
            // BLE (optionnel)
            // if enabled("ble") { group.addTask { await BleScanner().scan(...) } }
        }

        return (merge(buffer.snapshot()), failed)
    }

    private func merge(_ incoming: [DiscoveredPrinter]) -> [DiscoveredPrinter] {
        var byId: [String: DiscoveredPrinter] = [:]
        for p in incoming {
            if let existing = byId[p.id] {
                var winner = AdapterPriority.score(p) > AdapterPriority.score(existing) ? p : existing
                winner.discoveredBy = existing.discoveredBy.union(p.discoveredBy)
                winner.lastSeenAt = max(existing.lastSeenAt, p.lastSeenAt)
                winner.isConnected = existing.isConnected || p.isConnected
                byId[p.id] = winner
            } else {
                byId[p.id] = p
            }
        }
        return byId.values.sorted {
            let sa = AdapterPriority.score($0), sb = AdapterPriority.score($1)
            return sa != sb ? sa > sb : $0.name < $1.name
        }
    }
}

/// Petit buffer thread-safe pour collecter les résultats concurrents.
final class SyncBuffer {
    private var items: [DiscoveredPrinter] = []
    private let lock = NSLock()
    func add(_ p: DiscoveredPrinter) { lock.lock(); items.append(p); lock.unlock() }
    func snapshot() -> [DiscoveredPrinter] { lock.lock(); defer { lock.unlock() }; return items }
}
