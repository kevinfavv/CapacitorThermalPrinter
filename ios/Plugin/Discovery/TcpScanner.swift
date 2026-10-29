import Foundation
import Network

/// Scanner réseau iOS (miroir de TcpScanner.kt) : sonde le /24 courant sur les ports
/// d'impression (9100 par défaut) pour détecter les imprimantes RAW.
///
/// Complète Bonjour : la plupart des imprimantes ESC/POS génériques (TP85H-V, Xprinter…)
/// ne publient AUCUN service mDNS. Sans ce scan, elles restaient introuvables sur iOS
/// alors qu'elles l'étaient sur Android.
///
/// Un port ouvert = candidat ESC/POS (adapter escpos). Les SDK (Epson…) tournent en
/// parallèle et absorbent ce résultat brut au dédoublonnage (même adresse IP).
///
/// ⚠️ Info.plist : NSLocalNetworkUsageDescription (déjà requis par Bonjour / le TCP).
final class TcpScanner {

    /// Connect court par hôte : le scan global reste borné par `timeoutMs`.
    private static let perHostTimeoutMs = 400
    /// Parallélisme borné pour ne pas saturer le Wi-Fi.
    private static let maxConcurrent = 64

    func scan(timeoutMs: Int, ports: [Int], networkCidr: String?, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        guard let local = Self.localIPv4() else { return }
        guard let prefix = networkCidr.flatMap(Self.cidrToPrefix) ?? Self.prefix(of: local) else { return }
        let portList = ports.isEmpty ? [9100] : ports
        let deadline = Date().addingTimeInterval(Double(timeoutMs) / 1000)
        let targets = (1...254).map { "\(prefix)\($0)" }.filter { $0 != local }
            .flatMap { host in portList.map { (host, $0) } }
        let queue = DispatchQueue(label: "thermalprinter.tcpscan")

        await withTaskGroup(of: Void.self) { group in
            var next = targets.makeIterator()
            func launch() -> Bool {
                guard Date() < deadline, let (host, port) = next.next() else { return false }
                group.addTask {
                    guard await Self.isOpen(host: host, port: port, timeoutMs: Self.perHostTimeoutMs, queue: queue) else { return }
                    onFound(DiscoveredPrinter(
                        id: "wifi:\(host)",
                        name: host,
                        brand: nil, model: nil,
                        transport: .wifi,
                        adapter: .escpos, // arbitré ensuite par la priorité
                        address: "\(host):\(port)",
                        capabilities: Capabilities(),
                        discoveredBy: ["escpos", "rawTcp"]
                    ))
                }
                return true
            }
            for _ in 0..<Self.maxConcurrent where launch() {}
            while await group.next() != nil { _ = launch() }
        }
    }

    /// Connect TCP court : vrai seulement si la connexion aboutit (`ready`).
    static func isOpen(host: String, port: Int, timeoutMs: Int, queue: DispatchQueue) async -> Bool {
        guard let p = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { return false }
        let params = NWParameters.tcp
        params.prohibitedInterfaceTypes = [.cellular]
        let conn = NWConnection(host: NWEndpoint.Host(host), port: p, using: params)
        return await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            var done = false // muté sur `queue` uniquement
            func finish(_ open: Bool) {
                if done { return }
                done = true
                conn.stateUpdateHandler = nil
                conn.cancel()
                cont.resume(returning: open)
            }
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(true)
                // `waiting` = refus (RST) ou hôte injoignable : inutile d'attendre le timeout.
                case .failed, .waiting, .cancelled: finish(false)
                default: break
                }
            }
            conn.start(queue: queue)
            queue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) { finish(false) }
        }
    }

    /// IPv4 de l'interface LAN active : Wi-Fi (en0) d'abord, sinon Ethernet (adaptateur USB).
    static func localIPv4() -> String? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }
        var found: [(name: String, ip: String)] = []
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  ifa.ifa_flags & UInt32(IFF_UP) != 0, ifa.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
            let name = String(cString: ifa.ifa_name)
            guard name.hasPrefix("en") else { continue } // exclut pdp_ip (cellulaire), utun (VPN)…
            var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &buf, socklen_t(buf.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: buf)
            if ip.hasPrefix("169.254.") { continue } // lien local : pas de réseau réel
            found.append((name, ip))
        }
        return (found.first { $0.name == "en0" } ?? found.first)?.ip
    }

    /// "192.168.1.42" -> "192.168.1."
    static func prefix(of ip: String) -> String? {
        let parts = ip.split(separator: ".")
        return parts.count == 4 ? parts.dropLast().joined(separator: ".") + "." : nil
    }

    /// Support simple /24 : "192.168.1.0/24" -> "192.168.1."
    static func cidrToPrefix(_ cidr: String) -> String? {
        prefix(of: String(cidr.split(separator: "/").first ?? ""))
    }
}
