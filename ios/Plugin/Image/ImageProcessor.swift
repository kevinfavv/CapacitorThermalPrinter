import Foundation
import UIKit
import CoreGraphics

/// Pipeline image -> raster thermique (miroir iOS de ImageProcessor.kt).
///
/// Étapes : decode -> resize largeur -> aplatir sur blanc -> niveaux de gris ->
/// binarisation (threshold / Floyd-Steinberg / Atkinson) -> raster ESC/POS GS v 0.
enum ImageProcessor {

    private static let MAX_HEIGHT = 20_000

    // MARK: - Décodage

    static func decodeFile(_ path: String) throws -> UIImage {
        let clean = path.replacingOccurrences(of: "file://", with: "")
        guard FileManager.default.fileExists(atPath: clean),
              let img = UIImage(contentsOfFile: clean) else {
            throw PrinterError(.IMAGE_INVALID, "Fichier image introuvable: \(clean)")
        }
        return img
    }

    static func decodeBase64(_ b64: String) throws -> UIImage {
        let payload = b64.contains("base64,") ? String(b64.split(separator: ",").last ?? "") : b64
        guard let data = Data(base64Encoded: payload, options: .ignoreUnknownCharacters),
              let img = UIImage(data: data) else {
            throw PrinterError(.IMAGE_INVALID, "Base64 image invalide")
        }
        return img
    }

    // MARK: - Resize sur fond blanc

    static func resizeToWidth(_ image: UIImage, targetWidth: Int) throws -> UIImage {
        let w = max(8, targetWidth)
        let ratio = CGFloat(w) / image.size.width
        let h = Int((image.size.height * ratio).rounded())
        guard h <= MAX_HEIGHT else {
            throw PrinterError(.IMAGE_TOO_LARGE, "Image trop haute: \(h)px (max \(MAX_HEIGHT))")
        }
        let size = CGSize(width: w, height: h)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    // MARK: - Niveaux de gris

    /// Extrait un buffer 8-bit gris (0=noir..255=blanc) en redessinant en contexte gris.
    private static func grayscaleBuffer(_ image: UIImage) throws -> (px: [UInt8], w: Int, h: Int) {
        guard let cg = image.cgImage else { throw PrinterError(.IMAGE_INVALID, "CGImage indisponible") }
        let w = cg.width
        let h = cg.height
        var buffer = [UInt8](repeating: 0, count: w * h)
        let colorSpace = CGColorSpaceCreateDeviceGray()
        guard let ctx = CGContext(
            data: &buffer, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
            space: colorSpace, bitmapInfo: CGImageAlphaInfo.none.rawValue
        ) else { throw PrinterError(.IMAGE_INVALID, "Contexte gris impossible") }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        return (buffer, w, h)
    }

    // MARK: - Binarisation

    /// Produit un MonoBitmap (1 = encre/noir).
    static func toMono(_ image: UIImage, options: RenderOptions) throws -> MonoBitmap {
        var (gray, w, h) = try grayscaleBuffer(image)
        if options.invert {
            for i in 0..<gray.count { gray[i] = 255 - gray[i] }
        }
        let data: [UInt8]
        switch options.dithering {
        case "none": data = threshold(gray, options.threshold)
        case "atkinson": data = atkinson(gray, w, h)
        default: data = floydSteinberg(gray, w, h)
        }
        return MonoBitmap(width: w, height: h, data: data)
    }

    private static func threshold(_ gray: [UInt8], _ t: Int) -> [UInt8] {
        let tt = UInt8(clamping: t)
        return gray.map { $0 < tt ? UInt8(1) : UInt8(0) }
    }

    private static func floydSteinberg(_ grayInput: [UInt8], _ w: Int, _ h: Int) -> [UInt8] {
        var gray = grayInput.map { Float($0) }
        var out = [UInt8](repeating: 0, count: grayInput.count)
        func at(_ x: Int, _ y: Int) -> Int { y * w + x }
        for y in 0..<h {
            for x in 0..<w {
                let idx = at(x, y)
                let old = gray[idx]
                let new: Float = old < 128 ? 0 : 255
                out[idx] = new == 0 ? 1 : 0
                let err = old - new
                if x + 1 < w { gray[at(x + 1, y)] += err * 7 / 16 }
                if x - 1 >= 0 && y + 1 < h { gray[at(x - 1, y + 1)] += err * 3 / 16 }
                if y + 1 < h { gray[at(x, y + 1)] += err * 5 / 16 }
                if x + 1 < w && y + 1 < h { gray[at(x + 1, y + 1)] += err / 16 }
            }
        }
        return out
    }

    private static func atkinson(_ grayInput: [UInt8], _ w: Int, _ h: Int) -> [UInt8] {
        var gray = grayInput.map { Float($0) }
        var out = [UInt8](repeating: 0, count: grayInput.count)
        func at(_ x: Int, _ y: Int) -> Int { y * w + x }
        func spread(_ x: Int, _ y: Int, _ e: Float) {
            if x >= 0 && x < w && y >= 0 && y < h { gray[at(x, y)] += e }
        }
        for y in 0..<h {
            for x in 0..<w {
                let idx = at(x, y)
                let old = gray[idx]
                let new: Float = old < 128 ? 0 : 255
                out[idx] = new == 0 ? 1 : 0
                let err = (old - new) / 8
                spread(x + 1, y, err); spread(x + 2, y, err)
                spread(x - 1, y + 1, err); spread(x, y + 1, err); spread(x + 1, y + 1, err)
                spread(x, y + 2, err)
            }
        }
        return out
    }

    // MARK: - Raster ESC/POS GS v 0

    static func encodeEscPosRaster(_ mono: MonoBitmap) -> [UInt8] {
        let w = mono.width
        let h = mono.height
        let bytesPerRow = (w + 7) / 8
        let xL = UInt8(bytesPerRow & 0xff)
        let xH = UInt8((bytesPerRow >> 8) & 0xff)
        let yL = UInt8(h & 0xff)
        let yH = UInt8((h >> 8) & 0xff)
        var out: [UInt8] = [0x1D, 0x76, 0x30, 0x00, xL, xH, yL, yH]
        var body = [UInt8](repeating: 0, count: bytesPerRow * h)
        for y in 0..<h {
            let rowOff = y * bytesPerRow
            let srcOff = y * w
            for x in 0..<w where mono.data[srcOff + x] == 1 {
                let byteIndex = rowOff + (x >> 3)
                let bit = 7 - (x & 7)
                body[byteIndex] |= UInt8(1 << bit)
            }
        }
        out.append(contentsOf: body)
        return out
    }
}

/// Image 1-bit : data[i]=1 => point noir/encre.
struct MonoBitmap {
    let width: Int
    let height: Int
    let data: [UInt8]
}
