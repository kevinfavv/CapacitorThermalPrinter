import Foundation
import UIKit

/// Adapter Epson iOS basé sur le SDK ePOS2 (framework `libepos2.xcframework`).
///
/// AVANTAGES iOS (cruciaux car pas de SPP générique) :
///   - imprimantes Epson Bluetooth MFi gérées NATIVEMENT par le SDK,
///   - découverte Epos2Discovery (TCP + Bluetooth MFi + USB-C selon modèle),
///   - impression image (EposPrinter.addImage), coupe, tiroir, statut riche.
///
/// INTÉGRATION : ajouter le xcframework Epson via le podspec
/// (s.vendored_frameworks / s.dependency), brancher le bridging header si ObjC,
/// puis activer le pseudo-code. isAvailable() détecte le SDK par NSClassFromString.
final class EpsonAdapter: PrinterAdapter {

    let id: AdapterId = .epson

    func isAvailable() -> Bool { NSClassFromString("Epos2Printer") != nil }

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        guard isAvailable() else { return }
        /*
         let filter = Epos2FilterOption()
         filter.deviceType = EPOS2_TYPE_PRINTER.rawValue
         Epos2Discovery.start(filter, delegate: self) // delegate -> onDiscovery(deviceInfo)
         // mapper chaque Epos2DeviceInfo -> DiscoveredPrinter(adapter: .epson, ...)
         // stopper après timeoutMs
        */
    }

    func canHandle(_ profile: PrinterProfile) -> Bool { isAvailable() && profile.adapter == .epson }

    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws {
        try ensureSdk()
        /*
         let printer = Epos2Printer(printerSeries: seriesFrom(profile.model), lang: EPOS2_MODEL_ANK.rawValue)
         printer?.connect(profile.address, timeout: Int(timeoutMs))   // ex "TCP:192.168.1.50" ou "BT:xxxx"
         cache[profile.id] = printer
        */
    }

    func isConnected(_ printerId: String) -> Bool { false }
    func disconnect(_ printerId: String) async {}

    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int {
        try ensureSdk()
        /*
         let printer = cache[profile.id]
         printer?.addImage(image, x: 0, y: 0, width: Int(image.size.width), height: Int(image.size.height),
                           color: EPOS2_COLOR_1.rawValue, mode: EPOS2_MODE_MONO.rawValue,
                           halftone: halftoneFrom(options.dithering), brightness: 1.0,
                           compress: EPOS2_COMPRESS_AUTO.rawValue)
         if options.cut { printer?.addCut(EPOS2_CUT_FEED.rawValue) }
         printer?.sendData(Int(EPOS2_PARAM_DEFAULT)) // async -> delegate onPtrReceive
        */
        throw PrinterError(.SDK_NOT_AVAILABLE, "Epson ePOS2 iOS non intégré")
    }

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        try ensureSdk()
        throw PrinterError(.SDK_NOT_AVAILABLE, "Epson ePOS2 iOS non intégré")
    }

    private func ensureSdk() throws {
        if !isAvailable() { throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Epson ePOS2 absent") }
    }
}
