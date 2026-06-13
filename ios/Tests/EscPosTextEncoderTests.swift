import XCTest
@testable import DelicityThermalPrinter

/// Tests unitaires de l'encodeur ESC/POS texte iOS (miroir des tests TS/Kotlin).
/// À exécuter via le scheme de test du package (xcodebuild test).
final class EscPosTextEncoderTests: XCTestCase {

    private func bytes(_ items: [PrintItem]) -> [Int] {
        EscPosTextEncoder.encode(items).bytes.map { Int($0) }
    }

    func testSizeByte() {
        XCTAssertEqual(EscPosTextEncoder.sizeByte(1, 1), 0x00)
        XCTAssertEqual(EscPosTextEncoder.sizeByte(2, 1), 0x10)
        XCTAssertEqual(EscPosTextEncoder.sizeByte(1, 2), 0x01)
        XCTAssertEqual(EscPosTextEncoder.sizeByte(8, 8), 0x77)
        XCTAssertEqual(EscPosTextEncoder.sizeByte(99, 99), 0x77)
    }

    func testEncodeStringAccents() {
        XCTAssertEqual(EscPosTextEncoder.encodeString("é"), [0xE9])
        XCTAssertEqual(EscPosTextEncoder.encodeString("€"), [0x3F])
    }

    func testTextJobStartsWithResetAndEndsWithLF() {
        let b = bytes([.text(value: "Hi", style: TextStyle())])
        XCTAssertEqual(b[0], 0x1B)
        XCTAssertEqual(b[1], 0x40)
        XCTAssertTrue(b.contains(0x0A))
    }

    func testBoldCenterSize() {
        var style = TextStyle()
        style.align = "center"; style.bold = true; style.widthMultiplier = 2; style.heightMultiplier = 2
        let s = bytes([.text(value: "X", style: style)]).map(String.init).joined(separator: ",")
        XCTAssertTrue(s.contains("27,116,16")) // ESC t 16
        XCTAssertTrue(s.contains("27,97,1")) // center
        XCTAssertTrue(s.contains("27,69,1")) // bold
        XCTAssertTrue(s.contains("29,33,17")) // size x2/x2
    }

    func testQrCode() {
        let s = bytes([.qrcode(value: "HELLO", size: 6, ec: "H", align: "center")]).map(String.init).joined(separator: ",")
        XCTAssertTrue(s.contains("29,40,107,3,0,49,67,6"))
        XCTAssertTrue(s.contains("29,40,107,3,0,49,69,51"))
        XCTAssertTrue(s.contains("29,40,107,3,0,49,81,48"))
    }

    func testCode128Prefix() {
        let s = bytes([.barcode(value: "12345", symbology: "CODE128", height: 80, width: 3, hri: "below", align: "center")]).map(String.init).joined(separator: ",")
        XCTAssertTrue(s.contains("29,107,73"))
        XCTAssertTrue(s.contains("123,66")) // {B
    }

    func testRaster8x2() {
        var data = [UInt8](repeating: 0, count: 16)
        for i in 0..<8 { data[i] = 1 }
        let raster = ImageProcessor.encodeEscPosRaster(MonoBitmap(width: 8, height: 2, data: data))
        XCTAssertEqual(Array(raster[0...3]), [0x1D, 0x76, 0x30, 0x00])
        XCTAssertEqual(raster[8], 0xFF)
        XCTAssertEqual(raster[9], 0x00)
    }

    func testImageItemsFlagged() {
        let encoded = EscPosTextEncoder.encode([
            .text(value: "a", style: TextStyle()),
            .image(filePath: nil, url: nil, base64: nil, render: nil),
        ])
        XCTAssertEqual(encoded.imageIndexes, [1])
    }
}
