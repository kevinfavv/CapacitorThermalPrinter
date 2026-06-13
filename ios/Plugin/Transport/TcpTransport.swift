import Foundation
import Network

/// Transport TCP (RAW / port 9100) basé sur Network.framework (NWConnection).
///
/// ⚠️ iOS : la première connexion à une IP locale déclenche la pop-up système
/// "Réseau local". Il FAUT déclarer NSLocalNetworkUsageDescription dans Info.plist.
final class TcpTransport {

    private let host: String
    private let port: UInt16
    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "thermalprinter.tcp")

    private(set) var isOpen = false

    init(host: String, port: UInt16 = 9100) {
        self.host = host
        self.port = port
    }

    func open(timeoutMs: Int) async throws {
        if isOpen { return }
        let conn = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port)!,
            using: .tcp
        )
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
                    finish(.failure(PrinterError(.CONNECTION_FAILED, "TCP \(self?.host ?? "")", detail: err.localizedDescription, retryable: true)))
                case .cancelled:
                    finish(.failure(PrinterError(.CONNECTION_FAILED, "TCP annulé")))
                default: break
                }
            }
            conn.start(queue: queue)
            queue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) {
                if !resumed {
                    conn.cancel()
                    finish(.failure(PrinterError(.TIMEOUT, "Timeout TCP \(self.host):\(self.port)", retryable: true)))
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
