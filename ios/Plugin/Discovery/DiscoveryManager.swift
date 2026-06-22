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
                case .ble: src = "ble"
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
        return collapseSdkDuplicates(Array(byId.values)).sorted {
            let sa = AdapterPriority.score($0), sb = AdapterPriority.score($1)
            return sa != sb ? sa > sb : $0.name < $1.name
        }
    }

    /// 2ᵉ passe : une même imprimante physique peut être remontée à la fois par
    /// son SDK fabricant ET par une source native générique sous un `id` différent
    /// (transport/adresse distincts) — typiquement une Epson visible aussi en BLE.
    /// On garde alors l'entrée SDK (priorité produit) et on y fusionne la source
    /// native, au lieu d'afficher deux lignes.
    ///
    /// Rapprochement demandé : même nom OU même adresse normalisée. On ne fusionne
    /// que du natif VERS du SDK (jamais SDK↔SDK ni natif↔natif) pour ne pas masquer
    /// par erreur deux imprimantes distinctes de même modèle.
    private func collapseSdkDuplicates(_ list: [DiscoveredPrinter]) -> [DiscoveredPrinter] {
        let sdkIndices = list.indices.filter { list[$0].adapter.isSdk }
        if sdkIndices.isEmpty { return list }

        var merged = list
        var result: [DiscoveredPrinter] = []
        for p in list {
            if p.adapter.isSdk { continue } // les entrées SDK sont émises depuis `merged`
            if let mi = sdkIndices.first(where: {
                sameAddress(merged[$0].address, p.address) || sameName(merged[$0].name, p.name)
            }) {
                merged[mi].discoveredBy.formUnion(p.discoveredBy)
                merged[mi].discoveredBy.insert(p.adapter.rawValue)
                merged[mi].isConnected = merged[mi].isConnected || p.isConnected
                merged[mi].isDefault = merged[mi].isDefault || p.isDefault
                continue // doublon natif supprimé
            }
            result.append(p)
        }
        // Entrées SDK (potentiellement enrichies) + entrées natives non rapprochées.
        return sdkIndices.map { merged[$0] } + result
    }

    /// Adresse comparable cross-transport : minuscule, port retiré pour les IPv4.
    private func bareAddress(_ a: String) -> String {
        let s = a.trimmingCharacters(in: .whitespaces).lowercased()
        guard s.contains(".") else { return s }
        return s.replacingOccurrences(of: ":\\d+$", with: "", options: .regularExpression)
    }

    private func sameAddress(_ a: String, _ b: String) -> Bool {
        let na = bareAddress(a)
        return !na.isEmpty && na == bareAddress(b)
    }

    private func sameName(_ a: String, _ b: String) -> Bool {
        let na = a.trimmingCharacters(in: .whitespaces).lowercased()
        return !na.isEmpty && na == b.trimmingCharacters(in: .whitespaces).lowercased()
    }
}

/// Petit buffer thread-safe pour collecter les résultats concurrents.
final class SyncBuffer {
    private var items: [DiscoveredPrinter] = []
    private let lock = NSLock()
    func add(_ p: DiscoveredPrinter) { lock.lock(); items.append(p); lock.unlock() }
    func snapshot() -> [DiscoveredPrinter] { lock.lock(); defer { lock.unlock() }; return items }
}
