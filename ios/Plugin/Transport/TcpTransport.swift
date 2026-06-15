import Foundation
import Network

/// Transport TCP (RAW / port 9100) basé sur Network.framework (NWConnection).
///
/// ⚠️ iOS : la première connexion à une IP locale déclenche la pop-up système
/// "Réseau local". Il FAUT déclarer NSLocalNetworkUsageDescription dans Info.plist.
final class TcpTransport {

    private let endpoint: NWEndpoint
    private let label: String
    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "thermalprinter.tcp")

    private(set) var isOpen = false

    init(host: String, port: UInt16 = 9100) {
        self.endpoint = .hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port) ?? 9100)
        self.label = "\(host):\(port)"
    }

    init(endpoint: NWEndpoint, label: String) {
        self.endpoint = endpoint
        self.label = label
    }

    /// Construit le transport depuis l'adresse d'un profil : soit `host[:port]`, soit une
    /// adresse Bonjour encodée par `BonjourScanner` (`bonjour:<name>\u{1}<type>\u{1}<domain>`)
    /// résolue via `NWEndpoint.service` — la résolution IP se fait à la connexion. Sans ça,
    /// une imprimante découverte par Bonjour (nom de service, pas une IP) ne peut PAS être
    /// connectée (NWEndpoint.Host("nom._service._tcp") ne résout pas -> timeout).
    static func make(address: String, defaultPort: UInt16 = 9100) -> TcpTransport {
        if address.hasPrefix("bonjour:") {
            let parts = address.dropFirst("bonjour:".count).components(separatedBy: "\u{1}")
            if parts.count == 3 {
                let ep = NWEndpoint.service(name: parts[0], type: parts[1], domain: parts[2], interface: nil)
                return TcpTransport(endpoint: ep, label: "\(parts[0]) (\(parts[1]))")
            }
        }
        let (host, port) = splitHostPort(address, defaultPort: defaultPort)
        return TcpTransport(host: host, port: port)
    }

    /// Sépare `host:port` (ne touche pas aux adresses sans `:` ni aux IPv6 multi-`:`).
    static func splitHostPort(_ addr: String, defaultPort: UInt16) -> (String, UInt16) {
        if let idx = addr.lastIndex(of: ":"), addr.firstIndex(of: ":") == idx {
            let host = String(addr[..<idx])
            let portStr = String(addr[addr.index(after: idx)...])
            return (host, UInt16(portStr) ?? defaultPort)
        }
        return (addr, defaultPort)
    }

    func open(timeoutMs: Int) async throws {
        if isOpen { return }
        let conn = NWConnection(to: endpoint, using: .tcp)
        self.connection = conn

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            var resumed = false
            func finish(_ result: Result<Void, Error>) {
                if resumed { return }
                resumed = true
                cont.resume(with: result)
            }
            conn.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    self?.isOpen = true
                    finish(.success(()))
                case .failed(let err):
                    finish(.failure(PrinterError(.CONNECTION_FAILED, "TCP \(self?.label ?? "")", detail: err.localizedDescription, retryable: true)))
                case .cancelled:
                    finish(.failure(PrinterError(.CONNECTION_FAILED, "TCP annulé")))
                default: break
                }
            }
            conn.start(queue: queue)
            queue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) {
                if !resumed {
                    conn.cancel()
                    finish(.failure(PrinterError(.TIMEOUT, "Timeout TCP \(self.label)", retryable: true)))
                }
            }
        }
    }

    func write(_ bytes: [UInt8]) async throws {
        guard let conn = connection, isOpen else {
            throw PrinterError(.CONNECTION_FAILED, "Socket TCP non ouvert")
        }
        let data = Data(bytes)
        // Envoi par chunks pour ménager les petits buffers imprimante.
        let chunkSize = 4096
        var offset = 0
        while offset < data.count {
            let end = min(offset + chunkSize, data.count)
            let chunk = data.subdata(in: offset..<end)
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                conn.send(content: chunk, completion: .contentProcessed { error in
                    if let error = error {
                        cont.resume(throwing: PrinterError(.PRINT_FAILED, "Écriture TCP échouée", detail: error.localizedDescription, retryable: true))
                    } else {
                        cont.resume()
                    }
                })
            }
            offset = end
        }
    }

    func close() {
        connection?.cancel()
        connection = nil
        isOpen = false
    }
}
