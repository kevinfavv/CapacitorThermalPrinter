import Foundation
import UIKit

/// Adapter Brother iOS (Brother Print SDK / BRLMPrinterKit).
///
/// Gère réseau + Bluetooth MFi + BLE selon modèles (QL/TD/RJ/PJ). Impression image
/// native via BRLMPrinterDriver.printImage(_:settings:).
///
/// INTÉGRATION : `pod 'BRLMPrinterKit'` (ou xcframework), puis activer le pseudo-code.
final class BrotherAdapter: PrinterAdapter {

    let id: AdapterId = .brother

    func isAvailable() -> Bool { NSClassFromString("BRLMPrinterDriverGenerator") != nil }

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        guard isAvailable() else { return }
        /*
         let option = BRLMNetworkSearchOption()
         option.searchDuration = UInt(timeoutMs / 1000)
         BRLMPrinterSearcher.startNetworkSearch(option) { channel in onFound(map(channel)) }
         // + startBLESearch / startBluetoothMFiSearch
        */
    }

    func canHandle(_ profile: PrinterProfile) -> Bool { isAvailable() && profile.adapter == .brother }
    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws { try ensureSdk() }
    func isConnected(_ printerId: String) -> Bool { false }
    func disconnect(_ printerId: String) async {}

    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int {
        try ensureSdk()
        /*
         let channel = BRLMChannel(wifiIPAddress: host) // ou bleSerialNumber:/ bluetoothSerialNumber:
         let result = BRLMPrinterDriverGenerator.open(channel)
         let driver = result.driver
         let settings = BRLMQLPrintSettings(defaultPrintSettingsWith: modelFrom(profile))
         driver.printImage(with: image.cgImage!, settings: settings!)
         driver.closeChannel()
        */
        throw PrinterError(.SDK_NOT_AVAILABLE, "Brother SDK iOS non intégré")
    }

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        try ensureSdk()
        throw PrinterError(.SDK_NOT_AVAILABLE, "Brother SDK iOS non intégré")
    }

    private func ensureSdk() throws {
        if !isAvailable() { throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Brother absent") }
    }
}
