import Foundation
import os.log

/// Logger circulaire en mémoire + os_log, pour le diagnostic support (miroir Logger.kt).
final class Logger {
    static let shared = Logger()
    private let maxLines = 500
    private var ring: [[String: Any]] = []
    private let queue = DispatchQueue(label: "thermalprinter.logger")
    var verbose = false
    private let osLog = OSLog(subsystem: "com.resto.thermalprinter", category: "plugin")

    func log(_ category: String, _ message: String, _ data: [String: Any]? = nil) {
        queue.sync {
            var entry: [String: Any] = [
                "ts": Date().timeIntervalSince1970 * 1000,
                "category": category, "message": message,
            ]
            data?.forEach { entry[$0.key] = $0.value }
            if ring.count >= maxLines { ring.removeFirst() }
            ring.append(entry)
            if verbose { os_log("[%{public}@] %{public}@", log: osLog, type: .debug, category, message) }
        }
    }

    func error(_ category: String, _ message: String) {
        log(category, message, ["level": "error"])
        os_log("[%{public}@] %{public}@", log: osLog, type: .error, category, message)
    }

    func snapshot() -> [[String: Any]] { queue.sync { ring } }
    func clear() { queue.sync { ring.removeAll() } }
}
