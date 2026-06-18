import Foundation
import CryptoKit

/// Cache local des images à imprimer (miroir de ImageCache.kt).
/// Emplacement : caches/thermal-images/, clé = SHA-256(url), quota 32 Mo.
final class ImageCache {

    private let dir: URL
    private let maxBytes: Int64 = 32 * 1024 * 1024

    init() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        dir = caches.appendingPathComponent("thermal-images", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    /// Télécharge l'URL (si absente) et renvoie le chemin local.
    /// `forceFetch` ignore le cache : re-télécharge et écrase l'entrée existante.
    func fetch(_ urlString: String, timeoutMs: Int = 10000, forceFetch: Bool = false) async throws -> String {
        guard let url = URL(string: urlString) else {
            throw PrinterError(.IMAGE_INVALID, "URL invalide: \(urlString)")
        }
        let dest = dir.appendingPathComponent(sha256(urlString) + ".img")
        if !forceFetch, FileManager.default.fileExists(atPath: dest.path) {
            Logger.shared.log("image", "cache hit", ["url": urlString])
            return dest.path
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = Double(timeoutMs) / 1000.0
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                throw PrinterError(.IMAGE_INVALID, "HTTP \(http.statusCode) pour \(urlString)")
            }
            try data.write(to: dest)
            Logger.shared.log("image", "downloaded", ["url": urlString, "bytes": data.count])
            enforceQuota()
            return dest.path
        } catch let e as PrinterError {
            throw e
        } catch {
            throw PrinterError(.IMAGE_INVALID, "Téléchargement image échoué", detail: error.localizedDescription, retryable: true)
        }
    }

    private func enforceQuota() {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys) else { return }
        let sorted = files.sorted {
            let d0 = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            let d1 = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return d0 < d1
        }
        var total = sorted.reduce(Int64(0)) { acc, u in
            acc + Int64((try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        var i = 0
        while total > maxBytes && i < sorted.count {
            let size = Int64((try? sorted[i].resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            try? FileManager.default.removeItem(at: sorted[i])
            total -= size
            i += 1
        }
    }

    func clear() {
        try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .forEach { try? FileManager.default.removeItem(at: $0) }
    }

    private func sha256(_ s: String) -> String {
        SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
