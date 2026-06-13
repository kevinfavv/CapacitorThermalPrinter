import Foundation

/// Constantes et helpers de commandes ESC/POS (miroir iOS de EscPosCommands.kt).
enum EscPosCommands {
    static let INIT: [UInt8] = [0x1B, 0x40]
    static let ALIGN_LEFT: [UInt8] = [0x1B, 0x61, 0x00]
    static let ALIGN_CENTER: [UInt8] = [0x1B, 0x61, 0x01]
    static let ALIGN_RIGHT: [UInt8] = [0x1B, 0x61, 0x02]
    static let CUT_PARTIAL: [UInt8] = [0x1D, 0x56, 0x01]
    static let DRAWER_PIN2: [UInt8] = [0x1B, 0x70, 0x00, 0x19, 0xFA]

    static func feed(_ lines: Int) -> [UInt8] { [0x1B, 0x64, UInt8(clamping: lines)] }

    static func alignOf(_ align: String) -> [UInt8] {
        switch align {
        case "center": return ALIGN_CENTER
        case "right": return ALIGN_RIGHT
        default: return ALIGN_LEFT
        }
    }

    static func buildJob(raster: [UInt8], align: String, feedLines: Int, cut: Bool, openDrawer: Bool) -> [UInt8] {
        var out: [UInt8] = []
        out.append(contentsOf: INIT)
        out.append(contentsOf: alignOf(align))
        out.append(contentsOf: raster)
        out.append(contentsOf: feed(feedLines))
        if cut { out.append(contentsOf: CUT_PARTIAL) }
        if openDrawer { out.append(contentsOf: DRAWER_PIN2) }
        return out
    }
}
