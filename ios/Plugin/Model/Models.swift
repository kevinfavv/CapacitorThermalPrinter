import Foundation

/// Transports physiques. Aligné avec PrinterTransport (TypeScript).
enum Transport: String, Codable {
    case wifi, ethernet, bluetooth, ble, usb
    static func from(_ v: String?) -> Transport { Transport(rawValue: v ?? "") ?? .wifi }
}

/// Identifiants d'adapter. Aligné avec PrinterAdapterId (TypeScript).
enum AdapterId: String, Codable {
    case escpos, epson, star, brother, zebra, ble
    case rawTcp = "rawTcp"
    static func from(_ v: String?) -> AdapterId { AdapterId(rawValue: v ?? "") ?? .escpos }

    /// Vrai si l'adapter est un SDK officiel fabricant (et non le natif générique).
    var isSdk: Bool { self == .epson || self == .star || self == .brother || self == .zebra }
}

/// Codes d'erreur normalisés. Aligné avec PrintErrorCode (TypeScript).
enum ErrorCode: String {
    case PRINTER_NOT_FOUND, PRINTER_OFFLINE, CONNECTION_FAILED, PERMISSION_DENIED
    case BLUETOOTH_DISABLED, WIFI_NOT_CONNECTED, PAIRING_REQUIRED, UNSUPPORTED_TRANSPORT
    case UNSUPPORTED_PRINTER, IMAGE_INVALID, IMAGE_TOO_LARGE, PRINT_FAILED, PAPER_EMPTY
    case COVER_OPEN, SDK_NOT_AVAILABLE, TIMEOUT, UNKNOWN
}

/// Erreur interne portant un code normalisé.
struct PrinterError: Error {
    let code: ErrorCode
    let message: String
    let detail: String?
    let retryable: Bool
    init(_ code: ErrorCode, _ message: String, detail: String? = nil, retryable: Bool = false) {
        self.code = code; self.message = message; self.detail = detail; self.retryable = retryable
    }
}

struct Capabilities: Codable {
    var paperWidthMm: Int = 80
    var printableDots: Int = 576
    var dpi: Int = 203
    var supportsCut: Bool = true
    var supportsCashDrawer: Bool = false
    var supportsStatus: Bool = false
    var supportsRasterImage: Bool = true
    var supportsQrCode: Bool = false
    var supportsBarcode: Bool = false

    func toDict() -> [String: Any] {
        [
            "paperWidthMm": paperWidthMm, "printableDots": printableDots, "dpi": dpi,
            "supportsCut": supportsCut, "supportsCashDrawer": supportsCashDrawer,
            "supportsStatus": supportsStatus, "supportsRasterImage": supportsRasterImage,
            "supportsQrCode": supportsQrCode, "supportsBarcode": supportsBarcode,
        ]
    }

    static func fromDict(_ d: [String: Any]?) -> Capabilities {
        guard let d = d else { return Capabilities() }
        var c = Capabilities()
        c.paperWidthMm = d["paperWidthMm"] as? Int ?? 80
        c.printableDots = d["printableDots"] as? Int ?? 576
        c.dpi = d["dpi"] as? Int ?? 203
        c.supportsCut = d["supportsCut"] as? Bool ?? true
        c.supportsCashDrawer = d["supportsCashDrawer"] as? Bool ?? false
        c.supportsStatus = d["supportsStatus"] as? Bool ?? false
        c.supportsRasterImage = d["supportsRasterImage"] as? Bool ?? true
        c.supportsQrCode = d["supportsQrCode"] as? Bool ?? false
        c.supportsBarcode = d["supportsBarcode"] as? Bool ?? false
        return c
    }
}

struct DiscoveredPrinter {
    let id: String
    let name: String
    var brand: String?
    var model: String?
    let transport: Transport
    var adapter: AdapterId
    let address: String
    var capabilities: Capabilities?
    var discoveredBy: Set<String> = []
    var lastSeenAt: Double = Date().timeIntervalSince1970 * 1000
    var isDefault: Bool = false
    var isConnected: Bool = false

    func toDict() -> [String: Any] {
        var d: [String: Any] = [
            "id": id, "name": name, "transport": transport.rawValue, "adapter": adapter.rawValue,
            "address": address, "lastSeenAt": lastSeenAt, "isDefault": isDefault, "isConnected": isConnected,
            "discoveredBy": Array(discoveredBy), "isSdk": adapter.isSdk,
        ]
        if let brand = brand { d["brand"] = brand }
        if let model = model { d["model"] = model }
        if let caps = capabilities { d["capabilities"] = caps.toDict() }
        return d
    }
}

struct PrinterProfile {
    let id: String
    var adapter: AdapterId
    let transport: Transport
    let address: String
    var brand: String?
    var model: String?
    let name: String
    var capabilities: Capabilities
    var adapterMeta: [String: Any] = [:]
    var isDefault: Bool = false
    var createdAt: Double = Date().timeIntervalSince1970 * 1000
    var updatedAt: Double = Date().timeIntervalSince1970 * 1000

    func toDict() -> [String: Any] {
        var d: [String: Any] = [
            "id": id, "adapter": adapter.rawValue, "transport": transport.rawValue, "address": address,
            "name": name, "capabilities": capabilities.toDict(), "adapterMeta": adapterMeta,
            "isDefault": isDefault, "createdAt": createdAt, "updatedAt": updatedAt,
        ]
        if let brand = brand { d["brand"] = brand }
        if let model = model { d["model"] = model }
        return d
    }

    static func fromDict(_ d: [String: Any]) -> PrinterProfile {
        PrinterProfile(
            id: d["id"] as? String ?? "",
            adapter: AdapterId.from(d["adapter"] as? String),
            transport: Transport.from(d["transport"] as? String),
            address: d["address"] as? String ?? "",
            brand: d["brand"] as? String,
            model: d["model"] as? String,
            name: d["name"] as? String ?? "",
            capabilities: Capabilities.fromDict(d["capabilities"] as? [String: Any]),
            adapterMeta: d["adapterMeta"] as? [String: Any] ?? [:],
            isDefault: d["isDefault"] as? Bool ?? false,
            createdAt: d["createdAt"] as? Double ?? Date().timeIntervalSince1970 * 1000,
            updatedAt: d["updatedAt"] as? Double ?? Date().timeIntervalSince1970 * 1000
        )
    }
}

struct PrinterStatus {
    let id: String
    var connection: String
    var online: Bool
    var paper: String
    var coverOpen: Bool?
    var errorCode: ErrorCode?
    var rawStatus: String?
    var checkedAt: Double = Date().timeIntervalSince1970 * 1000

    func toDict() -> [String: Any] {
        var d: [String: Any] = [
            "id": id, "connection": connection, "online": online, "paper": paper, "checkedAt": checkedAt,
        ]
        if let coverOpen = coverOpen { d["coverOpen"] = coverOpen }
        if let errorCode = errorCode { d["errorCode"] = errorCode.rawValue }
        if let rawStatus = rawStatus { d["rawStatus"] = rawStatus }
        return d
    }
}

struct RenderOptions {
    var widthDots: Int
    /// Largeur papier en mm (ex. 58/80/112). Si fourni et `widthDots` non défini,
    /// la largeur en points est déduite (58→384, 80→576, 112→832).
    var paperWidthMm: Int?
    var resize: Bool = true
    var grayscale: Bool = true
    var threshold: Int = 128
    var dithering: String = "floyd_steinberg"
    var align: String = "center"
    var invert: Bool = false
    var cut: Bool = true
    var feedLines: Int = 3
    var openCashDrawer: Bool = false
    var copies: Int = 1
}
