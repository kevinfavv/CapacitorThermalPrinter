import XCTest
@testable import DelicityCapacitorThermalPrinter

/// Tests du raster compacté BLE (EscPosCommands.compactRaster).
final class BleCompactRasterTests: XCTestCase {

    /// Raster `GS v 0` de `rows` lignes (largeur `bpr` octets) : `ink[y]` = ligne encrée.
    private func raster(bpr: Int, ink: [Bool]) -> [UInt8] {
        var r: [UInt8] = [0x1D, 0x76, 0x30, 0x00, UInt8(bpr), 0, UInt8(ink.count & 0xFF), UInt8(ink.count >> 8)]
        for on in ink { r += [UInt8](repeating: on ? 0xFF : 0x00, count: bpr) }
        return r
    }

    /// Hauteur totale imprimée (somme des hauteurs des `GS v 0`) : doit égaler l'original.
    private func printedRows(_ bytes: [UInt8]) -> Int {
        var i = 0, rows = 0
        while i + 8 <= bytes.count, bytes[i] == 0x1D, bytes[i + 1] == 0x76 {
            let w = Int(bytes[i + 4]) | Int(bytes[i + 5]) << 8
            let h = Int(bytes[i + 6]) | Int(bytes[i + 7]) << 8
            rows += h
            i += 8 + w * h
        }
        XCTAssertEqual(i, bytes.count, "flux mal formé")
        return rows
    }

    func testBlankRunsBecomeOneByteBandsWithSameHeight() {
        let ink = [Bool](repeating: true, count: 10) + [Bool](repeating: false, count: 100) + [Bool](repeating: true, count: 10)
        let src = raster(bpr: 72, ink: ink)
        let out = EscPosCommands.compactRaster(src)
        XCTAssertEqual(printedRows(out), ink.count)
        XCTAssertLessThan(out.count, src.count / 2)
        // Bande blanche du milieu : largeur 1 octet, 100 lignes.
        XCTAssertEqual(Array(out[(8 + 720)..<(8 + 720 + 8)]), [0x1D, 0x76, 0x30, 0x00, 0x01, 0x00, 100, 0])
    }

    func testShortBlankRunsStayInBand() {
        let ink = [true, false, false, true]
        let src = raster(bpr: 4, ink: ink)
        XCTAssertEqual(EscPosCommands.compactRaster(src), src)
    }

    func testNonRasterInputUntouched() {
        let text: [UInt8] = [0x1B, 0x40, 0x41, 0x0A]
        XCTAssertEqual(EscPosCommands.compactRaster(text), text)
    }
}
