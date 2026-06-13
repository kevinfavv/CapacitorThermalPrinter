import Foundation

/// Persistance des profils via UserDefaults (JSON). Miroir de PrinterStore.kt.
final class PrinterStore {

    private let key = "resto.thermalprinter.profiles_v1"
    private let defaults = UserDefaults.standard
    private let queue = DispatchQueue(label: "thermalprinter.store")

    func all() -> [PrinterProfile] {
        queue.sync {
            guard let data = defaults.data(forKey: key),
                  let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
            return arr.map { PrinterProfile.fromDict($0) }
        }
    }

    func get(_ id: String) -> PrinterProfile? { all().first { $0.id == id } }
    func getDefault() -> PrinterProfile? { all().first { $0.isDefault } }

    func upsert(_ profile: PrinterProfile) {
        var list = all()
        var p = profile
        p.updatedAt = Date().timeIntervalSince1970 * 1000
        if let idx = list.firstIndex(where: { $0.id == p.id }) { list[idx] = p } else { list.append(p) }
        persist(list)
    }

    @discardableResult
    func setDefault(_ id: String) -> PrinterProfile? {
        var list = all()
        var target: PrinterProfile?
        for i in list.indices {
            list[i].isDefault = list[i].id == id
            if list[i].isDefault { target = list[i] }
        }
        persist(list)
        return target
    }

    func remove(_ id: String) { persist(all().filter { $0.id != id }) }

    private func persist(_ list: [PrinterProfile]) {
        queue.sync {
            let arr = list.map { $0.toDict() }
            if let data = try? JSONSerialization.data(withJSONObject: arr) {
                defaults.set(data, forKey: key)
            }
        }
    }
}
