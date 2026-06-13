import Foundation

/// Encodeur ESC/POS texte iOS (miroir de escpos-text.ts / EscPosTextEncoder.kt).
enum EscPosTextEncoder {

    private static let ESC: UInt8 = 0x1B
    private static let GS: UInt8 = 0x1D
    private static let LF: UInt8 = 0x0A

    private static let codePageToEscT: [String: UInt8] = [
        "CP437": 0, "CP850": 2, "CP858": 19, "WPC1252": 16, "CP852": 18, "CP866": 17,
    ]
    private static let barcodeM: [String: UInt8] = [
        "UPC_A": 65, "UPC_E": 66, "EAN13": 67, "EAN8": 68,
        "CODE39": 69, "ITF": 70, "CODABAR": 71, "CODE93": 72, "CODE128": 73,
    ]

    struct Encoded { let bytes: [UInt8]; let imageIndexes: [Int] }

    static func encodeString(_ value: String) -> [UInt8] {
        value.unicodeScalars.map { $0.value <= 0xFF ? UInt8($0.value) : 0x3F }
    }

    static func sizeByte(_ w: Int, _ h: Int) -> UInt8 {
        let ww = UInt8(min(8, max(1, w)) - 1)
        let hh = UInt8(min(8, max(1, h)) - 1)
        return (ww << 4) | hh
    }

    private static func openStyle(_ out: inout [UInt8], _ s: TextStyle, _ defaultCodePage: String) {
        let cp = s.codePageId.map { UInt8(truncatingIfNeeded: $0) } ?? (codePageToEscT[s.codePage ?? defaultCodePage] ?? 16)
        out += [ESC, 0x74, cp]
        let align: UInt8 = s.align == "center" ? 1 : (s.align == "right" ? 2 : 0)
        out += [ESC, 0x61, align]
        out += [ESC, 0x4D, s.font == "B" ? 1 : 0]
        out += [ESC, 0x45, s.bold ? 1 : 0]
        out += [ESC, 0x47, s.doubleStrike ? 1 : 0]
        let ul: UInt8 = s.underline == "single" ? 1 : (s.underline == "double" ? 2 : 0)
        out += [ESC, 0x2D, ul]
        out += [GS, 0x42, s.invert ? 1 : 0]
        out += [ESC, 0x7B, s.upsideDown ? 1 : 0]
        out += [ESC, 0x56, s.rotate90 ? 1 : 0]
        out += [GS, 0x21, sizeByte(s.widthMultiplier, s.heightMultiplier)]
        if let ls = s.letterSpacing { out += [ESC, 0x20, UInt8(truncatingIfNeeded: ls)] }
        if let lsp = s.lineSpacing { out += [ESC, 0x33, UInt8(truncatingIfNeeded: lsp)] } else { out += [ESC, 0x32] }
    }

    private static func qrCode(_ out: inout [UInt8], value: String, size: Int, ec: String, align: String) {
        let a: UInt8 = align == "left" ? 0 : (align == "right" ? 2 : 1)
        out += [ESC, 0x61, a]
        out += [GS, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00]
        out += [GS, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, UInt8(min(16, max(1, size)))]
        let ecv: UInt8 = ec == "L" ? 48 : (ec == "Q" ? 50 : (ec == "H" ? 51 : 49))
        out += [GS, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, ecv]
        let data = encodeString(value)
        let len = data.count + 3
        out += [GS, 0x28, 0x6B, UInt8(len & 0xFF), UInt8((len >> 8) & 0xFF), 0x31, 0x50, 0x30]
        out += data
        out += [GS, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30]
    }

    private static func barcode(_ out: inout [UInt8], value: String, symbology: String, height: Int, width: Int, hri: String, align: String) {
        let a: UInt8 = align == "left" ? 0 : (align == "right" ? 2 : 1)
        out += [ESC, 0x61, a]
        let h: UInt8 = hri == "above" ? 1 : (hri == "both" ? 3 : (hri == "none" ? 0 : 2))
        out += [GS, 0x48, h]
        out += [GS, 0x68, UInt8(min(255, max(1, height)))]
        out += [GS, 0x77, UInt8(min(6, max(2, width)))]
        let m = barcodeM[symbology] ?? 73
        var data = encodeString(value)
        if symbology == "CODE128" && !(data.first == 0x7B) { data = [0x7B, 0x42] + data }
        out += [GS, 0x6B, m, UInt8(data.count)]
        out += data
    }

    static func encode(_ items: [PrintItem], defaultCodePage: String = "WPC1252", columns: Int = 48) -> Encoded {
        var out: [UInt8] = [ESC, 0x40] // reset
        var imageIndexes: [Int] = []
        for (index, item) in items.enumerated() {
            switch item {
            case let .text(value, style):
                openStyle(&out, style, defaultCodePage)
                out += encodeString(value)
                if style.newline { out.append(LF) }
                out += [ESC, 0x40]
            case let .feed(lines):
                out += [ESC, 0x64, UInt8(min(255, max(0, lines)))]
            case let .divider(char, cols, align, bold):
                let ch = char.unicodeScalars.first.map { UInt8(truncatingIfNeeded: $0.value) } ?? 0x2D
                let n = cols ?? columns
                let a: UInt8 = align == "center" ? 1 : (align == "right" ? 2 : 0)
                out += [ESC, 0x61, a]
                if bold { out += [ESC, 0x45, 1] }
                out += [UInt8](repeating: ch, count: n)
                out.append(LF)
                out += [ESC, 0x40]
            case let .qrcode(value, size, ec, align):
                qrCode(&out, value: value, size: size, ec: ec, align: align)
            case let .barcode(value, symbology, height, width, hri, align):
                barcode(&out, value: value, symbology: symbology, height: height, width: width, hri: hri, align: align)
            case let .cashDrawer(pin):
                out += pin == 5 ? [ESC, 0x70, 0x01, 0x19, 0xFA] : [ESC, 0x70, 0x00, 0x19, 0xFA]
            case let .cut(mode, feedBefore):
                if feedBefore > 0 { out += [ESC, 0x64, UInt8(truncatingIfNeeded: feedBefore)] }
                out += mode == "full" ? [GS, 0x56, 0x00] : [GS, 0x56, 0x01]
            case let .raw(bytesBase64):
                let clean = bytesBase64.contains("base64,") ? String(bytesBase64.split(separator: ",").last ?? "") : bytesBase64
                if let data = Data(base64Encoded: clean, options: .ignoreUnknownCharacters) { out += [UInt8](data) }
            case .image:
                imageIndexes.append(index)
            }
        }
        return Encoded(bytes: out, imageIndexes: imageIndexes)
    }
}
