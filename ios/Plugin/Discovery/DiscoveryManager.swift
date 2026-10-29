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
            // Réseau : Bonjour (imprimantes qui publient un service) + scan 9100 du /24
            // (ESC/POS génériques sans mDNS). BLE : via BleAdapter (source "ble").
            if enabled("tcp") {
                group.addTask { await BonjourScanner().scan(timeoutMs: options.timeoutMs, onFound: collect) }
                group.addTask {
                    await TcpScanner().scan(timeoutMs: options.timeoutMs, ports: options.tcpPorts,
                                            networkCidr: options.networkCidr, onFound: collect)
                }
            }
        }

        return (merge(buffer.snapshot()), failed)
    }

    private func merge(_ incoming: [DiscoveredPrinter]) -> [DiscoveredPrinter] {
        var byId: [String: DiscoveredPrinter] = [:]
        for p in incoming {
            if let existing = byId[p.id] {
                // À score d'adapter égal, départager par le service Bonjour : une entrée
                // `_pdl-datastream` (RAW 9100) doit gagner sur `_printer`/`_ipp` (LPD/IPP),
                // sinon l'ESC/POS brut part sur le mauvais port → ticket en charabia.
                let sp = AdapterPriority.score(p)
                let se = AdapterPriority.score(existing)
                var winner = sp != se
                    ? (sp > se ? p : existing)
                    : (BonjourScanner.addressRank(p.address) < BonjourScanner.addressRank(existing.address) ? p : existing)
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
    /// que du natif VERS du SDK pour ne pas masquer par erreur deux imprimantes
    /// distinctes de même modèle. Seule exception SDK↔SDK, par adresse uniquement : si
    /// un SDK de marque a identifié l'adresse d'une entrée Zebra, celle-ci est un faux
    /// positif (le découvreur Zebra remonte toutes les imprimantes) et disparaît.
    private func collapseSdkDuplicates(_ list: [DiscoveredPrinter]) -> [DiscoveredPrinter] {
        let brandSdk = list.filter { $0.adapter.isSdk && $0.adapter != .zebra }
        // Un SDK de marque passe avant Zebra : sinon le natif, rapproché d'abord du faux
        // positif Zebra, échappe à la fusion (exception Zebra ci-dessous).
        let sdkIndices = list.indices
            .filter { i in
                let p = list[i]
                return p.adapter.isSdk && !(p.adapter == .zebra && brandSdk.contains { Self.sameAddress($0.address, p.address) })
            }
            .sorted { (list[$0].adapter == .zebra ? 1 : 0) < (list[$1].adapter == .zebra ? 1 : 0) }
        if sdkIndices.isEmpty { return list }

        var merged = list
        var result: [DiscoveredPrinter] = []
        for p in list {
            if p.adapter.isSdk { continue } // les entrées SDK sont émises depuis `merged`
            if let mi = sdkIndices.first(where: {
                Self.sameAddress(merged[$0].address, p.address) || Self.sameName(merged[$0].name, p.name)
            }) {
                // Exception Zebra : on NE fusionne PAS le doublon natif (BLE/Classic). Une Zebra
                // peut être en `line_print` ou refuser le ZPL : on garde l'entrée native générique
                // comme chemin d'impression ESC/POS « normal »/de secours, EN PLUS de l'entrée SDK.
                if merged[mi].adapter == .zebra {
                    result.append(p)
                    continue
                }
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

    /// Entrée SDK de marque (Epson, Star, Brother) désignant la même imprimante physique que
    /// l'imprimante native (`address`, `name`) : même adresse, sinon même nom si un seul candidat
    /// le porte (deux imprimantes du même modèle ne doivent pas être confondues). Zebra exclu :
    /// son entrée native est conservée à dessein (cf. collapseSdkDuplicates).
    static func sdkTwin(address: String, name: String, in candidates: [DiscoveredPrinter]) -> DiscoveredPrinter? {
        let brand = candidates.filter { $0.adapter.isSdk && $0.adapter != .zebra }
        if let byAddress = brand.first(where: { sameAddress($0.address, address) }) { return byAddress }
        let byName = brand.filter { sameName($0.name, name) }
        return byName.count == 1 ? byName[0] : nil
    }

    /// Adresse comparable cross-transport : minuscule, préfixe de cible ePOS2 retiré
    /// (`BT:<mac>`, `TCP:<ip>`…), port retiré pour les IPv4.
    private static func bareAddress(_ a: String) -> String {
        let s = a.trimmingCharacters(in: .whitespaces).lowercased()
            .replacingOccurrences(of: "^(bt|ble|tcps?|usb):", with: "", options: .regularExpression)
        if s.contains(".") {
            return s.replacingOccurrences(of: ":\\d+$", with: "", options: .regularExpression)
        }
        // MAC : le SDK Star la donne sans séparateur (`0011622E0816`), le natif avec (`00:11:62:…`).
        guard s.range(of: "^[0-9a-f]{2}([:-]?[0-9a-f]{2}){5}$", options: .regularExpression) != nil else { return s }
        return s.replacingOccurrences(of: "[:-]", with: "", options: .regularExpression)
    }

    private static func sameAddress(_ a: String, _ b: String) -> Bool {
        let na = bareAddress(a)
        return !na.isEmpty && na == bareAddress(b)
    }

    private static func sameName(_ a: String, _ b: String) -> Bool {
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
