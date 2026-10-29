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

    /// Réécrit un raster `GS v 0` (sortie de `encodeEscPosRaster`) en remplaçant chaque bande
    /// de lignes blanches par un `GS v 0` blanc large d'UN octet (même hauteur). Un ticket est
    /// majoritairement blanc : sur un lien lent (BLE ≈ 11 Ko/s), c'est autant d'octets en
    /// moins et donc une impression d'autant plus rapide. Les bandes encrées restent des
    /// `GS v 0` de même largeur (alignement inchangé).
    ///
    /// Pas d'avance papier `ESC J n` : son unité varie selon l'imprimante et, sur la TP85H-V,
    /// les espaces blancs du ticket en ressortaient écrasés. Un raster blanc avance exactement
    /// d'autant de lignes de points que l'image d'origine.
    static func compactRaster(_ raster: [UInt8], minBlankRows: Int = 8) -> [UInt8] {
        guard raster.count >= 8, raster[0] == 0x1D, raster[1] == 0x76, raster[2] == 0x30 else { return raster }
        let bytesPerRow = Int(raster[4]) | Int(raster[5]) << 8
        let height = Int(raster[6]) | Int(raster[7]) << 8
        guard bytesPerRow > 0, raster.count >= 8 + bytesPerRow * height else { return raster }

        func isBlank(_ y: Int) -> Bool {
            let start = 8 + y * bytesPerRow
            return !raster[start..<start + bytesPerRow].contains { $0 != 0 }
        }
        var out: [UInt8] = []
        out.reserveCapacity(raster.count)
        func emitBand(_ from: Int, _ to: Int) { // lignes [from, to[
            var y = from
            while y < to {
                let rows = min(to - y, 0xFFFF)
                out += [0x1D, 0x76, 0x30, 0x00, raster[4], raster[5], UInt8(rows & 0xFF), UInt8(rows >> 8)]
                out += raster[(8 + y * bytesPerRow)..<(8 + (y + rows) * bytesPerRow)]
                y += rows
            }
        }
        func emitBlank(_ rows: Int) {
            var left = rows
            while left > 0 {
                let n = min(left, 0xFFFF)
                out += [0x1D, 0x76, 0x30, 0x00, 0x01, 0x00, UInt8(n & 0xFF), UInt8(n >> 8)]
                out += [UInt8](repeating: 0, count: n)
                left -= n
            }
        }
        var y = 0
        var bandStart = 0
        while y < height {
            guard isBlank(y) else { y += 1; continue }
            var end = y
            while end < height && isBlank(end) { end += 1 }
            if end - y >= minBlankRows {
                emitBand(bandStart, y)
                emitBlank(end - y)
                bandStart = end
            }
            y = end
        }
        emitBand(bandStart, height)
        return out
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
