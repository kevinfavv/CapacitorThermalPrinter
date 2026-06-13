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
                name: channel.extraInfo?[BRLMChannelExtraInfoKey.modelName] as? String ?? "Brother",
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
            if err.code != .noError {
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

    /// Réglages d'impression selon la famille du modèle (QL/PJ/RJ/TD/PT).
    private static func printSettings(for profile: PrinterProfile) throws -> BRLMPrintSettingsProtocol {
        guard let model = profile.model else {
            throw PrinterError(.UNSUPPORTED_PRINTER, "Modèle Brother requis (ex 'RJ-3150')")
        }
        let m = model.uppercased()
        // Roll/receipt: RJ / TD ; labels: QL ; mobiles: PJ
        if m.hasPrefix("RJ") || m.hasPrefix("TD") || m.hasPrefix("PJ") || m.hasPrefix("QL") || m.hasPrefix("PT") {
            // BRLMPrintSettings générique convient pour l'impression image roll.
            if let s = BRLMPrintSettings(printerModel: brlmModel(from: m)) { return s }
        }
        throw PrinterError(.UNSUPPORTED_PRINTER, "Famille Brother inconnue pour '\(model)'")
    }

    private static func brlmModel(from m: String) -> BRLMPrinterModel {
        // Best effort : laisser .other si le mapping précis n'est pas connu.
        return .other
    }
    #endif
}
