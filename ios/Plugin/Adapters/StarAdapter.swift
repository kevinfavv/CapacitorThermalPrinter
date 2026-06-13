import Foundation
import UIKit

#if canImport(StarIO10)
import StarIO10
#endif

/// Adapter Star iOS basé sur StarXpand SDK (StarIO10).
///
/// ⭐ Star est le mieux supporté sur iOS (MFi) et le seul fabricant auto-installable :
/// via Swift Package Manager (`https://github.com/star-micronics/StarXpand-SDK-iOS`).
/// On utilise la compilation conditionnelle `#if canImport(StarIO10)` :
///   - si l'app a ajouté le package StarXpand -> code TYPÉ compilé et actif,
///   - sinon -> stub inerte, `isAvailable()` renvoie false, l'adapter est ignoré.
///
/// INSTALLATION : voir docs/SDK_INTEGRATION.md (§ Star iOS).
final class StarAdapter: PrinterAdapter {

    let id: AdapterId = .star

    /// Connexions ouvertes indexées par printerId (StarPrinter est une classe -> AnyObject).
    private var connections: [String: AnyObject] = [:]
    private let lock = NSLock()

    #if canImport(StarIO10)
    /// Délégué de découverte retenu le temps du scan.
    private var discoveryDelegate: StarDiscoveryDelegate?
    private var discoveryManager: StarDeviceDiscoveryManager?
    #endif

    func isAvailable() -> Bool {
        #if canImport(StarIO10)
        return true
        #else
        return false
        #endif
    }

    func canHandle(_ profile: PrinterProfile) -> Bool { isAvailable() && profile.adapter == .star }

    func supportsTextItems() -> Bool { isAvailable() }

    // MARK: Découverte

    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        #if canImport(StarIO10)
        guard let manager = try? StarDeviceDiscoveryManagerFactory.create(
            interfaceTypes: [.lan, .bluetooth, .bluetoothLE, .usb]
        ) else { return }
        manager.discoveryTime = min(max(timeoutMs, 1000), 30000)

        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            let delegate = StarDiscoveryDelegate(
                onFound: { printer in
                    let settings = printer.connectionSettings
                    let transport = Self.transport(for: settings.interfaceType)
                    let model = printer.information?.model.map { String(describing: $0) }
                    onFound(DiscoveredPrinter(
                        id: "star:\(settings.interfaceType):\(settings.identifier)",
                        name: model ?? "Star Printer",
                        brand: "Star",
                        model: model,
                        transport: transport,
                        adapter: .star,
                        address: settings.identifier,
                        discoveredBy: ["star"]
                    ))
                },
                onFinish: { [weak self] in
                    self?.lock.lock(); let resumed = self?.discoveryDelegate != nil; self?.discoveryDelegate = nil; self?.lock.unlock()
                    if resumed { cont.resume() }
                }
            )
            self.lock.lock(); self.discoveryDelegate = delegate; self.discoveryManager = manager; self.lock.unlock()
            manager.delegate = delegate
            do { try manager.startDiscovery() } catch {
                self.lock.lock(); self.discoveryDelegate = nil; self.lock.unlock()
                cont.resume()
            }
        }
        manager.stopDiscovery()
        #endif
    }

    // MARK: Connexion

    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws {
        #if canImport(StarIO10)
        if isConnected(profile.id) { return }
        let printer = StarPrinter(Self.connectionSettings(for: profile))
        do {
            try await printer.open()
        } catch {
            throw PrinterError(.CONNECTION_FAILED, "Connexion Star échouée: \(profile.address)", detail: "\(error)", retryable: true)
        }
        lock.lock(); connections[profile.id] = printer; lock.unlock()
        #else
        throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Star (StarIO10) absent")
        #endif
    }

    func isConnected(_ printerId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return connections[printerId] != nil
    }

    func disconnect(_ printerId: String) async {
        #if canImport(StarIO10)
        lock.lock(); let p = connections.removeValue(forKey: printerId) as? StarPrinter; lock.unlock()
        if let p = p { try? await p.close() }
        #endif
    }

    // MARK: Impression

    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int {
        #if canImport(StarIO10)
        let printer = try requireConnected(profile)
        let printerBuilder = StarXpandCommand.PrinterBuilder()
            .styleAlignment(Self.alignment(for: options.align))
            .actionPrintImage(StarXpandCommand.Printer.ImageParameter(image: image, width: max(8, profile.capabilities.printableDots)))
        if options.feedLines > 0 { _ = printerBuilder.actionFeedLine(options.feedLines) }
        if options.cut && profile.capabilities.supportsCut { _ = printerBuilder.actionCut(.partial) }

        let document = StarXpandCommand.DocumentBuilder().addPrinter(printerBuilder)
        if options.openCashDrawer && profile.capabilities.supportsCashDrawer {
            _ = document.addDrawer(StarXpandCommand.DrawerBuilder().actionOpen(StarXpandCommand.Drawer.OpenParameter()))
        }
        let commands = StarXpandCommand.StarXpandCommandBuilder().addDocument(document).getCommands()

        var sent = 0
        for _ in 0..<max(1, options.copies) {
            try await send(printer, commands)
            sent += commands.count
        }
        return sent
        #else
        throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Star (StarIO10) absent")
        #endif
    }

    func printItems(_ profile: PrinterProfile, items: [PrintItem], defaultCodePage: String, cut: Bool, feedLines: Int) async throws -> Int {
        #if canImport(StarIO10)
        let printer = try requireConnected(profile)
        let pb = StarXpandCommand.PrinterBuilder()
        for item in items { Self.map(item, into: pb, profile: profile) }
        if feedLines > 0 { _ = pb.actionFeedLine(feedLines) }
        if cut && profile.capabilities.supportsCut { _ = pb.actionCut(.partial) }
        let commands = StarXpandCommand.StarXpandCommandBuilder()
            .addDocument(StarXpandCommand.DocumentBuilder().addPrinter(pb))
            .getCommands()
        try await send(printer, commands)
        return commands.count
        #else
        throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Star (StarIO10) absent")
        #endif
    }

    // MARK: Statut

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus {
        #if canImport(StarIO10)
        lock.lock(); let p = connections[profile.id] as? StarPrinter; lock.unlock()
        guard let printer = p else {
            return PrinterStatus(id: profile.id, connection: "disconnected", online: false, paper: "unknown")
        }
        do {
            let st = try await printer.getStatus()
            let paperEmpty = st.paperEmpty
            let coverOpen = st.coverOpen
            return PrinterStatus(
                id: profile.id, connection: "connected", online: !paperEmpty && !coverOpen,
                paper: paperEmpty ? "empty" : (st.paperNearEmpty ? "near_end" : "ok"),
                coverOpen: coverOpen,
                errorCode: paperEmpty ? .PAPER_EMPTY : (coverOpen ? .COVER_OPEN : nil),
                rawStatus: "\(st)"
            )
        } catch {
            return PrinterStatus(id: profile.id, connection: "error", online: false, paper: "unknown", rawStatus: "\(error)")
        }
        #else
        throw PrinterError(.SDK_NOT_AVAILABLE, "SDK Star (StarIO10) absent")
        #endif
    }

    // MARK: Helpers (typés, sous canImport)

    #if canImport(StarIO10)
    private func requireConnected(_ profile: PrinterProfile) throws -> StarPrinter {
        lock.lock(); let p = connections[profile.id] as? StarPrinter; lock.unlock()
        guard let printer = p else { throw PrinterError(.CONNECTION_FAILED, "Star non connecté: \(profile.id)") }
        return printer
    }

    private func send(_ printer: StarPrinter, _ commands: String) async throws {
        do { try await printer.print(command: commands) }
        catch { throw PrinterError(.PRINT_FAILED, "Impression Star échouée", detail: "\(error)", retryable: true) }
    }

    private static func connectionSettings(for profile: PrinterProfile) -> StarConnectionSettings {
        let iface: InterfaceType
        switch profile.transport {
        case .wifi, .ethernet: iface = .lan
        case .bluetooth: iface = .bluetooth
        case .ble: iface = .bluetoothLE
        case .usb: iface = .usb
        }
        let identifier = iface == .lan ? String(profile.address.split(separator: ":").first ?? "") : profile.address
        return StarConnectionSettings(interfaceType: iface, identifier: identifier)
    }

    private static func transport(for iface: InterfaceType) -> Transport {
        switch iface {
        case .lan: return .wifi
        case .bluetooth: return .bluetooth
        case .bluetoothLE: return .ble
        case .usb: return .usb
        @unknown default: return .wifi
        }
    }

    private static func alignment(for align: String) -> StarXpandCommand.Printer.Alignment {
        switch align {
        case "center": return .center
        case "right": return .right
        default: return .left
        }
    }

    /// Mappe un PrintItem vers le PrinterBuilder StarXpand (best effort par type).
    private static func map(_ item: PrintItem, into pb: StarXpandCommand.PrinterBuilder, profile: PrinterProfile) {
        switch item {
        case let .text(value, style):
            if let a = style.align { _ = pb.styleAlignment(alignment(for: a)) }
            _ = pb.styleBold(style.bold)
            _ = pb.styleInvert(style.invert)
            _ = pb.styleUnderLine(style.underline != "none")
            _ = pb.styleMagnification(StarXpandCommand.MagnificationParameter(
                width: min(max(style.widthMultiplier, 1), 6), height: min(max(style.heightMultiplier, 1), 6)))
            _ = pb.actionPrintText(style.newline ? value + "\n" : value)
            _ = pb.styleBold(false).styleInvert(false).styleUnderLine(false)
                .styleMagnification(StarXpandCommand.MagnificationParameter(width: 1, height: 1))
        case let .feed(lines):
            _ = pb.actionFeedLine(max(1, lines))
        case let .cut(mode, _):
            _ = pb.actionCut(mode == "full" ? .full : .partial)
        case let .divider(char, columns, align, bold):
            let cols = columns ?? (profile.capabilities.printableDots <= 420 ? 32 : 48)
            if let a = align { _ = pb.styleAlignment(alignment(for: a)) }
            _ = pb.styleBold(bold)
            _ = pb.actionPrintText(String(repeating: char, count: min(max(cols, 1), 96)) + "\n")
            _ = pb.styleBold(false)
        case let .qrcode(value, size, ec, align):
            _ = pb.styleAlignment(alignment(for: align))
            _ = pb.actionPrintQRCode(StarXpandCommand.Printer.QRCodeParameter(content: value)
                .setLevel(qrLevel(for: ec))
                .setCellSize(min(max(size, 1), 16)))
        case let .barcode(value, symbology, height, _, hri, align):
            _ = pb.styleAlignment(alignment(for: align))
            _ = pb.actionPrintBarcode(StarXpandCommand.Printer.BarcodeParameter(content: value, symbology: barcodeSymbology(for: symbology))
                .setHeight(Double(min(max(height, 1), 255)))
                .setPrintHri(hri != "none"))
        case .cashDrawer, .image, .raw:
            // tiroir -> openCashDrawer ; image -> printImage ; raw -> non supporté par StarXpand.
            break
        }
    }

    private static func qrLevel(for ec: String) -> StarXpandCommand.Printer.QRCodeLevel {
        switch ec.uppercased() {
        case "L": return .l
        case "Q": return .q
        case "H": return .h
        default: return .m
        }
    }

    private static func barcodeSymbology(for s: String) -> StarXpandCommand.Printer.BarcodeSymbology {
        switch s.uppercased() {
        case "CODE39": return .code39
        case "CODE93": return .code93
        case "EAN13", "JAN13": return .jan13
        case "EAN8", "JAN8": return .jan8
        case "ITF": return .itf
        case "UPCA": return .upcA
        case "UPCE": return .upcE
        case "NW7", "CODABAR": return .nw7
        default: return .code128
        }
    }
    #endif
}

#if canImport(StarIO10)
/// Délégué de découverte StarXpand (retenu par l'adapter le temps du scan).
private final class StarDiscoveryDelegate: NSObject, StarDeviceDiscoveryManagerDelegate {
    private let onFound: (StarPrinter) -> Void
    private let onFinish: () -> Void
    init(onFound: @escaping (StarPrinter) -> Void, onFinish: @escaping () -> Void) {
        self.onFound = onFound; self.onFinish = onFinish
    }
    func manager(_ manager: StarDeviceDiscoveryManager, didFind printer: StarPrinter) { onFound(printer) }
    func managerDidFinishDiscovery(_ manager: StarDeviceDiscoveryManager) { onFinish() }
}
#endif
