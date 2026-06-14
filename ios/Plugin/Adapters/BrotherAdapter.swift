import Foundation
import UIKit

#if canImport(BRLMPrinterKit)
import BRLMPrinterKit
#endif

/// Adapter Brother iOS (Brother Print SDK v4 / BRLMPrinterKit).
///
/// Brother iOS est le SEUL des SDK "gated" auto-installable sur iOS : pod officiel
/// `BRLMPrinterKit` (CocoaPods trunk). On l'ajoute en dépendance OPTIONNELLE via le
/// Podfile de l'app puis on l'utilise sous `#if canImport(BRLMPrinterKit)`.
/// Si le pod n'est pas ajouté -> stub inerte, adapter ignoré.
///
/// Gère réseau + Bluetooth MFi + BLE selon modèles (QL/TD/RJ/PJ). Impression image
/// native via BRLMPrinterDriver.printImage(_:settings:).
final class BrotherAdapter: PrinterAdapter {

    let id: AdapterId = .brother

    private var drivers: [String: AnyObject] = [:]
    private let lock = NSLock()

    func isAvailable() -> Bool {
        #if canImport(BRLMPrinterKit)
        return true
        #else
        return false
        #endif
    }

    func canHandle(_ profile: PrinterProfile) -> Bool { isAvailable() && profile.adapter == .brother }

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        #if canImport(BRLMPrinterKit)
        let option = BRLMNetworkSearchOption()
        option.searchDuration = TimeInterval(max(1, timeoutMs / 1000))
        _ = BRLMPrinterSearcher.startNetworkSearch(option) { channel in
            let info = channel.channelInfo
            onFound(DiscoveredPrinter(
                id: "brother:\(info)",
                name: (channel.extraInfo?[BRLMChannelExtraInfoKeyModelName] as? String) ?? "Brother",
                brand: "Brother",
                transport: .wifi,
                adapter: .brother,
                address: info,
                discoveredBy: ["brother"]
            ))
        }
        #endif
    }

    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws {
        #if canImport(BRLMPrinterKit)
        if isConnected(profile.id) { return }
        let channel = try Self.channel(for: profile)
        let result = BRLMPrinterDriverGenerator.open(channel)
        guard result.error.code == .noError, let driver = result.driver else {
            throw PrinterError(.CONNECTION_FAILED, "Connexion Brother échouée: \(profile.address)", detail: "\(result.error.code.rawValue)", retryable: true)
        }
        lock.lock(); drivers[profile.id] = driver; lock.unlock()
        #else
        throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Brother (BRLMPrinterKit) absent")
        #endif
    }

    func isConnected(_ printerId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return drivers[printerId] != nil
    }

    func disconnect(_ printerId: String) async {
        #if canImport(BRLMPrinterKit)
        lock.lock(); let d = drivers.removeValue(forKey: printerId) as? BRLMPrinterDriver; lock.unlock()
        d?.closeChannel()
        #endif
    }

    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int {
        #if canImport(BRLMPrinterKit)
        lock.lock(); let d = drivers[profile.id] as? BRLMPrinterDriver; lock.unlock()
        guard let driver = d else { throw PrinterError(.CONNECTION_FAILED, "Brother non connecté: \(profile.id)") }
        guard let cg = image.cgImage else { throw PrinterError(.IMAGE_INVALID, "CGImage indisponible") }
        let settings = try Self.printSettings(for: profile)
        for _ in 0..<max(1, options.copies) {
            let err = driver.printImage(with: cg, settings: settings)
            if err.code != BRLMPrintErrorCode.noError {
                throw PrinterError(.PRINT_FAILED, "Impression Brother échouée", detail: "\(err.code.rawValue)", retryable: true)
            }
        }
        return Int(image.size.width * image.size.height) / 8
        #else
        throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Brother (BRLMPrinterKit) absent")
        #endif
    }

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        let connected = isConnected(profile.id)
        return PrinterStatus(id: profile.id, connection: connected ? "connected" : "disconnected",
                             online: connected, paper: "unknown",
                             rawStatus: "Brother: statut détaillé non lu (best effort)")
    }

    // MARK: Helpers typés

    #if canImport(BRLMPrinterKit)
    private static func channel(for profile: PrinterProfile) throws -> BRLMChannel {
        switch profile.transport {
        case .wifi, .ethernet:
            return BRLMChannel(wifiIPAddress: profile.address.split(separator: ":").first.map(String.init) ?? profile.address)
        case .bluetooth:
            return BRLMChannel(bluetoothSerialNumber: profile.address)
        case .ble:
            return BRLMChannel(bleLocalName: profile.address)
        case .usb:
            throw PrinterError(.UNSUPPORTED_TRANSPORT, "USB non supporté pour Brother iOS")
        }
    }

    /// Réglages d'impression selon le modèle. Brother exige des `*PrintSettings`
    /// spécifiques à la famille (QL/RJ/PJ…) construits avec un `BRLMPrinterModel`
    /// précis. On mappe les modèles courants ; modèle non listé -> erreur explicite
    /// (l'ajouter ici si besoin). Best-effort, à valider sur device.
    private static func printSettings(for profile: PrinterProfile) throws -> BRLMPrintSettingsProtocol {
        guard let model = profile.model else {
            throw PrinterError(.UNSUPPORTED_PRINTER, "Modèle Brother requis (ex 'RJ-2150')")
        }
        let m = model.uppercased().replacingOccurrences(of: "[^A-Z0-9]", with: "", options: .regularExpression)

        let ql: [String: BRLMPrinterModel] = [
            "QL710W": .QL_710W, "QL720NW": .QL_720NW, "QL810W": .QL_810W,
            "QL820NWB": .QL_820NWB, "QL1110NWB": .QL_1110NWB, "QL1115NWB": .QL_1115NWB,
        ]
        if let pm = ql[m], let s = BRLMQLPrintSettings(defaultPrintSettingsWith: pm) { return s }

        let rj: [String: BRLMPrinterModel] = [
            "RJ2050": .RJ_2050, "RJ2140": .RJ_2140, "RJ2150": .RJ_2150, "RJ3050": .RJ_3050,
        ]
        if let pm = rj[m], let s = BRLMRJPrintSettings(defaultPrintSettingsWith: pm) { return s }

        let pj: [String: BRLMPrinterModel] = [
            "PJ673": .PJ_673, "PJ763MFI": .pj_763MFi, "PJ773": .PJ_773, "PJ822": .PJ_822, "PJ823": .PJ_823,
        ]
        if let pm = pj[m], let s = BRLMPJPrintSettings(defaultPrintSettingsWith: pm) { return s }

        throw PrinterError(.UNSUPPORTED_PRINTER, "Modèle Brother non mappé: '\(model)' — l'ajouter dans BrotherAdapter.printSettings")
    }
    #endif
}
