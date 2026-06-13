import Foundation
import UIKit

/// Adapter Zebra iOS (Link-OS SDK / ZSDK).
///
/// ⚠️ Comme sur Android : Zebra = ZPL/CPCL, JAMAIS ESC/POS. Le SDK convertit
/// un UIImage en ZPL et l'imprime. Transports : TCP + Bluetooth MFi.
///
/// INTÉGRATION : ajouter `ZSDK_API.xcframework`, puis activer le pseudo-code.
final class ZebraAdapter: PrinterAdapter {

    let id: AdapterId = .zebra

    func isAvailable() -> Bool { NSClassFromString("TcpPrinterConnection") != nil || NSClassFromString("ZebraPrinterFactory") != nil }

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        guard isAvailable() else { return }
        /*
         NetworkDiscoverer.localBroadcast(with: &error) { discovered in onFound(map(discovered)) }
         // + EAAccessoryManager pour le Bluetooth MFi
        */
    }

    func canHandle(_ profile: PrinterProfile) -> Bool { isAvailable() && profile.adapter == .zebra }
    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws { try ensureSdk() }
    func isConnected(_ printerId: String) -> Bool { false }
    func disconnect(_ printerId: String) async {}

    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int {
        try ensureSdk()
        /*
         let conn = TcpPrinterConnection(address: host, andWithPort: port)
         conn?.open()
         let printer = try ZebraPrinterFactory.getInstance(conn)
         let tools = printer.getGraphicsUtil()
         try tools?.print(image.cgImage, atX: 0, atY: 0, withWidth: image.width, withHeight: image.height, andIsInsideFormat: false)
         conn?.close()
        */
        throw PrinterError(.SDK_NOT_AVAILABLE, "Zebra Link-OS iOS non intégré")
    }

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        try ensureSdk()
        throw PrinterError(.SDK_NOT_AVAILABLE, "Zebra Link-OS iOS non intégré")
    }

    private func ensureSdk() throws {
        if !isAvailable() { throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Zebra absent") }
    }
}
