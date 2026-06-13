import Foundation
import UIKit

/// Rend une liste d'items texte (`printText`) en **UIImage**, pour les adapters dont
/// le SDK n'a pas de builder texte natif (Brother, Zebra). Le moteur appelle ensuite
/// `printImage` → le SDK imprime l'image.
///
/// Police monospace (alignement colonne fiable). Supporte texte (align, gras,
/// souligné, multiplicateur de taille), séparateur, saut de ligne. Les items
/// QR/code-barres sont rendus en texte de repli ; image/raw/cut/tiroir ignorés.
enum TextRasterizer {

    private struct Line {
        let text: String
        let sizeMul: Int
        let bold: Bool
        let underline: Bool
        let align: String
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

        var lines: [Line?] = []
        for item in items {
            switch item {
            case let .text(value, style):
                for raw in value.components(separatedBy: "\n") {
                    lines.append(Line(text: raw, sizeMul: min(max(style.heightMultiplier, 1), 6),
                                      bold: style.bold, underline: style.underline != "none", align: style.align ?? "left"))
                }
            case let .divider(char, cols, align, bold):
                let n = cols ?? columns
                let c = char.isEmpty ? "-" : String(char.prefix(1))
                lines.append(Line(text: String(repeating: c, count: min(max(n, 1), 96)), sizeMul: 1, bold: bold, underline: false, align: align ?? "left"))
            case let .feed(n):
                for _ in 0..<min(max(n, 1), 20) { lines.append(nil) }
            case let .qrcode(value, _, _, align):
                lines.append(Line(text: "[QR] \(value)", sizeMul: 1, bold: false, underline: false, align: align))
            case let .barcode(value, symbology, _, _, _, align):
                lines.append(Line(text: "[\(symbology)] \(value)", sizeMul: 1, bold: false, underline: false, align: align))
            case .cut, .cashDrawer, .image, .raw:
                break
            }
        }
        if lines.isEmpty { lines.append(nil) }

        let totalH = lines.reduce(CGFloat(0)) { $0 + CGFloat($1?.sizeMul ?? 1) * baseLineH } + 8
        let height = max(baseLineH, totalH)

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)
        return renderer.image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
            var y: CGFloat = 0
            for line in lines {
                guard let l = line else { y += baseLineH; continue }
                let font = UIFont.monospacedSystemFont(ofSize: fontSize * CGFloat(l.sizeMul), weight: l.bold ? .bold : .regular)
                var attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.black]
                if l.underline { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
                let ns = l.text as NSString
                let tw = ns.size(withAttributes: attrs).width
                let x: CGFloat = l.align == "center" ? max(0, (width - tw) / 2) : (l.align == "right" ? max(0, width - tw) : 0)
                ns.draw(at: CGPoint(x: x, y: y), withAttributes: attrs)
                y += font.lineHeight
            }
        }
    }
}
