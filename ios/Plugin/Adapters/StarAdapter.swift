import Foundation
import UIKit

/// Adapter Star iOS basé sur StarXpand SDK (StarIO10).
///
/// Star est historiquement le mieux supporté sur iOS (MFi). StarXpand gère
/// LAN / Bluetooth (MFi) / BLE, l'impression image (actionPrintImage) et le statut.
///
/// INTÉGRATION : `pod 'StarIO10'` ou xcframework, puis activer le pseudo-code.
final class StarAdapter: PrinterAdapter {

    let id: AdapterId = .star

    func isAvailable() -> Bool { NSClassFromString("StarPrinter") != nil || NSClassFromString("StarIO10.StarPrinter") != nil }

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        guard isAvailable() else { return }
        /*
         let manager = try StarDeviceDiscoveryManagerFactory.create(interfaceTypes: [.lan, .bluetooth, .bluetoothLE])
         manager.discoveryTime = timeoutMs
         manager.delegate = self  // manager(_:didFind:) -> onFound(map(printer))
         try manager.startDiscovery()
        */
    }

    func canHandle(_ profile: PrinterProfile) -> Bool { isAvailable() && profile.adapter == .star }

    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws {
        try ensureSdk()
        /* let printer = StarPrinter(connectionSettingsFrom(profile)); try await printer.open() */
    }

    func isConnected(_ printerId: String) -> Bool { false }
    func disconnect(_ printerId: String) async {}

    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int {
        try ensureSdk()
        /*
         let builder = StarXpandCommand.StarXpandCommandBuilder()
         _ = builder.addDocument(StarXpandCommand.DocumentBuilder().addPrinter(
             StarXpandCommand.PrinterBuilder()
                 .actionPrintImage(StarXpandCommand.Printer.ImageParameter(image: image, width: profile.capabilities.printableDots))
                 .actionFeed(options.feedLines)
                 .actionCut(.partial)))
         let commands = builder.getCommands()
         try await printer.print(command: commands)
        */
        throw PrinterError(.SDK_NOT_AVAILABLE, "Star SDK iOS non intégré")
    }

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        try ensureSdk()
        throw PrinterError(.SDK_NOT_AVAILABLE, "Star SDK iOS non intégré")
    }

    private func ensureSdk() throws {
        if !isAvailable() { throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Star absent") }
    }
}
