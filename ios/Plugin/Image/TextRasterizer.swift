import CoreImage
import Foundation
import UIKit

/// Rend une liste d'items texte (`printText`) en **UIImage**, pour les adapters dont
/// le SDK n'a pas de builder texte natif (Epson iOS, Brother, Zebra). Le moteur appelle
/// ensuite `printImage` → le SDK imprime l'image.
///
/// Police monospace (alignement colonne fiable). Supporte texte (align, gras, souligné,
/// multiplicateur de taille), séparateur, saut de ligne. Les **QR codes** et **CODE128**
/// sont rendus en **vraie image** (CoreImage) ; les autres symbologies code-barres
/// retombent sur un rendu texte. image/raw/cut/tiroir ignorés.
enum TextRasterizer {

    private struct Line {
        let text: String
        let sizeMul: Int
        let bold: Bool
        let underline: Bool
        let align: String
    }

    private enum Block {
        case line(Line)
        case spacer
        case image(UIImage, align: String)
    }

    static func render(_ items: [PrintItem], widthDots: Int) -> UIImage {
        let width = CGFloat(max(128, widthDots))
        let columns = widthDots <= 420 ? 32 : 48

        // Taille de base : caler N caractères monospace sur la largeur cible.
        var fontSize: CGFloat = 24
        let probe = UIFont.monospacedSystemFont(ofSize: fontSize, weight: .regular)
        let charW = ("M" as NSString).size(withAttributes: [.font: probe]).width
        fontSize = fontSize * (width / CGFloat(columns)) / max(1, charW)
        let baseFont = UIFont.monospacedSystemFont(ofSize: fontSize, weight: .regular)
        let baseLineH = baseFont.lineHeight

        var blocks: [Block] = []
        for item in items {
            switch item {
            case let .text(value, style):
                for raw in value.components(separatedBy: "\n") {
                    blocks.append(.line(Line(text: raw, sizeMul: min(max(style.heightMultiplier, 1), 6),
                                             bold: style.bold, underline: style.underline != "none", align: style.align ?? "left")))
                }
            case let .divider(char, cols, align, bold):
                let n = cols ?? columns
                let c = char.isEmpty ? "-" : String(char.prefix(1))
                blocks.append(.line(Line(text: String(repeating: c, count: min(max(n, 1), 96)), sizeMul: 1, bold: bold, underline: false, align: align ?? "left")))
            case let .feed(n):
                for _ in 0..<min(max(n, 1), 20) { blocks.append(.spacer) }
            case let .qrcode(value, size, ec, align):
                // Module `size` 1..16 -> côté en points (borné à la largeur imprimable).
                let side = min(width - 16, CGFloat(min(max(size, 2), 16)) * 26)
                if let img = qrImage(value, correction: ec, side: side) {
                    blocks.append(.image(img, align: align))
                } else {
                    blocks.append(.line(Line(text: "[QR] \(value)", sizeMul: 1, bold: false, underline: false, align: align)))
                }
            case let .barcode(value, symbology, height, _, _, align):
                if symbology.uppercased() == "CODE128",
                   let img = code128(value, targetWidth: width * 0.85, height: CGFloat(max(40, height))) {
                    blocks.append(.image(img, align: align))
                } else {
                    blocks.append(.line(Line(text: "[\(symbology)] \(value)", sizeMul: 1, bold: false, underline: false, align: align)))
                }
            case .cut, .cashDrawer, .image, .raw:
                break
            }
        }
        if blocks.isEmpty { blocks.append(.spacer) }

        func blockHeight(_ b: Block) -> CGFloat {
            switch b {
            case let .line(l): return CGFloat(l.sizeMul) * baseLineH
            case .spacer: return baseLineH
            case let .image(img, _): return img.size.height + 8
            }
        }
        let totalH = blocks.reduce(CGFloat(0)) { $0 + blockHeight($1) } + 8
        let height = max(baseLineH, totalH)

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)
        return renderer.image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
            var y: CGFloat = 0
            for b in blocks {
                switch b {
                case .spacer:
                    y += baseLineH
                case let .line(l):
                    let font = UIFont.monospacedSystemFont(ofSize: fontSize * CGFloat(l.sizeMul), weight: l.bold ? .bold : .regular)
                    var attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.black]
                    if l.underline { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
                    let ns = l.text as NSString
                    let tw = ns.size(withAttributes: attrs).width
                    let x: CGFloat = l.align == "center" ? max(0, (width - tw) / 2) : (l.align == "right" ? max(0, width - tw) : 0)
                    ns.draw(at: CGPoint(x: x, y: y), withAttributes: attrs)
                    y += font.lineHeight
                case let .image(img, align):
                    let iw = min(img.size.width, width)
                    let x: CGFloat = align == "center" ? max(0, (width - iw) / 2) : (align == "right" ? max(0, width - iw) : 0)
                    img.draw(in: CGRect(x: x, y: y + 4, width: iw, height: img.size.height))
                    y += img.size.height + 8
                }
            }
        }
    }

    // MARK: - Codes (CoreImage)

    private static let ciContext = CIContext(options: nil)

    /// QR code natif (CIQRCodeGenerator), mis à l'échelle net (nearest-neighbor) à `side` px.
    private static func qrImage(_ value: String, correction: String, side: CGFloat) -> UIImage? {
        guard let data = value.data(using: .utf8),
              let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        let level = ["L", "M", "Q", "H"].contains(correction.uppercased()) ? correction.uppercased() : "M"
        filter.setValue(level, forKey: "inputCorrectionLevel")
        guard let out = filter.outputImage, out.extent.width > 0 else { return nil }
        let scale = max(1, side / out.extent.width)
        // samplingNearest garde les modules nets une fois agrandis.
        let scaled = out.samplingNearest().transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cg = ciContext.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// Code-barres CODE128 natif (CICode128BarcodeGenerator).
    private static func code128(_ value: String, targetWidth: CGFloat, height: CGFloat) -> UIImage? {
        guard let data = value.data(using: .ascii),
              let filter = CIFilter(name: "CICode128BarcodeGenerator") else { return nil }
        filter.setValue(data, forKey: "inputMessage")
        guard let out = filter.outputImage, out.extent.width > 0 else { return nil }
        let sx = max(1, targetWidth / out.extent.width)
        let sy = max(1, height / out.extent.height)
        let scaled = out.samplingNearest().transformed(by: CGAffineTransform(scaleX: sx, y: sy))
        guard let cg = ciContext.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
