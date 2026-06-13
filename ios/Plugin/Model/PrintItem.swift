import Foundation

/// Modèle d'items texte iOS (miroir de src/core/text.ts et de PrintItem.kt).

struct TextStyle {
    var align: String?
    var bold = false
    var underline = "none"
    var font = "A"
    var widthMultiplier = 1
    var heightMultiplier = 1
    var doubleStrike = false
    var invert = false
    var upsideDown = false
    var rotate90 = false
    var letterSpacing: Int?
    var lineSpacing: Int?
    var codePage: String?
    var codePageId: Int?
    var newline = true

    static func fromDict(_ d: [String: Any]?) -> TextStyle {
        guard let d = d else { return TextStyle() }
        var s = TextStyle()
        s.align = d["align"] as? String
        s.bold = d["bold"] as? Bool ?? false
        s.underline = d["underline"] as? String ?? "none"
        s.font = d["font"] as? String ?? "A"
        s.widthMultiplier = d["widthMultiplier"] as? Int ?? 1
        s.heightMultiplier = d["heightMultiplier"] as? Int ?? 1
        s.doubleStrike = d["doubleStrike"] as? Bool ?? false
        s.invert = d["invert"] as? Bool ?? false
        s.upsideDown = d["upsideDown"] as? Bool ?? false
        s.rotate90 = d["rotate90"] as? Bool ?? false
        s.letterSpacing = d["letterSpacing"] as? Int
        s.lineSpacing = d["lineSpacing"] as? Int
        s.codePage = d["codePage"] as? String
        s.codePageId = d["codePageId"] as? Int
        s.newline = d["newline"] as? Bool ?? true
        return s
    }
}

enum PrintItem {
    case text(value: String, style: TextStyle)
    case feed(lines: Int)
    case cut(mode: String, feedBefore: Int)
    case divider(char: String, columns: Int?, align: String?, bold: Bool)
    case qrcode(value: String, size: Int, ec: String, align: String)
    case barcode(value: String, symbology: String, height: Int, width: Int, hri: String, align: String)
    case cashDrawer(pin: Int)
    case image(filePath: String?, url: String?, base64: String?, render: [String: Any]?)
    case raw(bytesBase64: String)

    static func parseList(_ arr: [[String: Any]]) -> [PrintItem] {
        arr.compactMap { parse($0) }
    }

    static func parse(_ d: [String: Any]) -> PrintItem? {
        switch d["type"] as? String {
        case "text":
            return .text(value: d["value"] as? String ?? "", style: TextStyle.fromDict(d["style"] as? [String: Any]))
        case "feed":
            return .feed(lines: d["lines"] as? Int ?? 1)
        case "cut":
            return .cut(mode: d["mode"] as? String ?? "partial", feedBefore: d["feedBefore"] as? Int ?? 0)
        case "divider":
            let style = d["style"] as? [String: Any]
            return .divider(char: d["char"] as? String ?? "-", columns: d["columns"] as? Int,
                            align: style?["align"] as? String, bold: style?["bold"] as? Bool ?? false)
        case "qrcode":
            return .qrcode(value: d["value"] as? String ?? "", size: d["size"] as? Int ?? 6,
                           ec: d["errorCorrection"] as? String ?? "M", align: d["align"] as? String ?? "center")
        case "barcode":
            return .barcode(value: d["value"] as? String ?? "", symbology: d["symbology"] as? String ?? "CODE128",
                            height: d["height"] as? Int ?? 80, width: d["width"] as? Int ?? 3,
                            hri: d["hri"] as? String ?? "below", align: d["align"] as? String ?? "center")
        case "cashDrawer":
            return .cashDrawer(pin: d["pin"] as? Int ?? 2)
        case "image":
            let img = d["image"] as? [String: Any]
            return .image(filePath: img?["filePath"] as? String, url: img?["url"] as? String,
                          base64: img?["base64"] as? String, render: d["render"] as? [String: Any])
        case "raw":
            return .raw(bytesBase64: d["bytesBase64"] as? String ?? "")
        default:
            return nil
        }
    }
}

/// Mise à jour d'état d'un job d'impression (émis via printJobStatus).
struct JobUpdate {
    let jobId: String
    let printerId: String
    let state: String
    var holdReason: String?
    var progress: Double?
    var errorCode: ErrorCode?
    var message: String?

    func toDict() -> [String: Any] {
        var d: [String: Any] = ["jobId": jobId, "printerId": printerId, "state": state,
                                "updatedAt": Date().timeIntervalSince1970 * 1000]
        if let holdReason = holdReason { d["holdReason"] = holdReason }
        if let progress = progress { d["progress"] = progress }
        if let errorCode = errorCode { d["errorCode"] = errorCode.rawValue }
        if let message = message { d["message"] = message }
        return d
    }
}
