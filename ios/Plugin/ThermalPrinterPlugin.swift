import Foundation
import Capacitor

/// Pont Capacitor iOS (bridge JS <-> Swift). Conforme Capacitor 7 (CAPBridgedPlugin).
///
/// Mappe l'API publique (definitions.ts) vers ThermalPrinterEngine. Les opérations
/// async tournent dans des Task ; les PrinterError sont converties en rejets
/// avec code normalisé.
@objc(ThermalPrinterPlugin)
public class ThermalPrinterPlugin: CAPPlugin, CAPBridgedPlugin {

    public let identifier = "ThermalPrinterPlugin"
    public let jsName = "ThermalPrinter"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "discoverPrinters", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "connectPrinter", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disconnectPrinter", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setDefaultPrinter", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDefaultPrinter", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSavedPrinters", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removePrinter", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "printImage", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "printText", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPrinterStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startStatusMonitor", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopStatusMonitor", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDebugLog", returnType: CAPPluginReturnPromise),
    ]

    private let engine = ThermalPrinterEngine()

    override public func load() {
        // Relaye les états de job vers le JS (event printJobStatus).
        engine.onJobUpdate = { [weak self] update in
            self?.notifyListeners("printJobStatus", data: ["job": update.toDict()])
        }
    }

    // MARK: Permissions
    //
    // iOS n'a pas de permission runtime pour le Bluetooth Classic (inexistant) ni
    // pour le réseau local au sens "demande explicite" : la pop-up Local Network
    // apparaît à la première connexion. Le BLE déclenche une autorisation gérée par
    // CoreBluetooth si on instancie un CBCentralManager (à activer avec BleAdapter).

    @objc func checkPermissions(_ call: CAPPluginCall) {
        call.resolve(permissionStatus())
    }

    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        // Rien à demander explicitement ici (voir note ci-dessus).
        call.resolve(permissionStatus())
    }

    private func permissionStatus() -> [String: Any] {
        [
            "bluetooth": "unavailable",       // BT Classic générique impossible sur iOS
            "bluetoothScan": "unavailable",
            "bluetoothConnect": "unavailable",
            "location": "granted",
            "localNetwork": "prompt",          // pop-up système à la 1re connexion locale
        ]
    }

    // MARK: Découverte

    @objc func discoverPrinters(_ call: CAPPluginCall) {
        let sources: Set<String>? = (call.getArray("sources") as? [String]).map { Set($0) }
        let options = DiscoveryManager.Options(
            sources: sources,
            timeoutMs: call.getInt("timeoutMs") ?? 8000,
            networkCidr: call.getString("networkCidr"),
            tcpPorts: (call.getArray("tcpPorts") as? [Int]) ?? [9100]
        )
        let emitPartial = call.getBool("emitPartialResults") ?? true

        Task {
            let result = await engine.discover(options) { [weak self] p in
                if emitPartial { self?.notifyListeners("printerFound", data: ["printer": p.toDict()]) }
            }
            let printersDict = result.printers.map { $0.toDict() }
            self.notifyListeners("discoveryComplete", data: ["printers": printersDict, "failedSources": result.failed])
            call.resolve(["printers": printersDict])
        }
    }

    // MARK: Connexion

    @objc func connectPrinter(_ call: CAPPluginCall) {
        guard let printerId = call.getString("printerId") else {
            return reject(call, .PRINTER_NOT_FOUND, "printerId requis")
        }
        let timeout = call.getInt("timeoutMs") ?? 10000
        let force = call.getString("forceAdapter").map { AdapterId.from($0) }
        let setAsDefault = call.getBool("setAsDefault") ?? false
        Task { await self.guarded(call) {
            let connected = try await self.engine.connect(printerId, timeoutMs: timeout, forceAdapter: force, setAsDefault: setAsDefault)
            call.resolve(["connected": connected])
        } }
    }

    @objc func disconnectPrinter(_ call: CAPPluginCall) {
        let id = call.getString("printerId") ?? ""
        Task { await self.engine.disconnect(id); call.resolve() }
    }

    // MARK: Profils

    @objc func setDefaultPrinter(_ call: CAPPluginCall) {
        guard let id = call.getString("printerId") else { return reject(call, .PRINTER_NOT_FOUND, "printerId requis") }
        do {
            let profile = try engine.setDefault(id)
            call.resolve(["profile": profile.toDict()])
        } catch { rejectError(call, error) }
    }

    @objc func getDefaultPrinter(_ call: CAPPluginCall) {
        if let p = engine.defaultProfile() { call.resolve(["profile": p.toDict()]) }
        else { call.resolve(["profile": NSNull()]) }
    }

    @objc func getSavedPrinters(_ call: CAPPluginCall) {
        call.resolve(["profiles": engine.savedProfiles().map { $0.toDict() }])
    }

    @objc func removePrinter(_ call: CAPPluginCall) {
        engine.removeProfile(call.getString("printerId") ?? "")
        call.resolve()
    }

    // MARK: Impression / statut

    @objc func printImage(_ call: CAPPluginCall) {
        guard let image = call.getObject("image") else { return reject(call, .IMAGE_INVALID, "image requise") }
        var render: RenderOptions?
        if let r = call.getObject("render") {
            render = RenderOptions(
                widthDots: r["widthDots"] as? Int ?? 0,
                resize: r["resize"] as? Bool ?? true,
                grayscale: r["grayscale"] as? Bool ?? true,
                threshold: r["threshold"] as? Int ?? 128,
                dithering: r["dithering"] as? String ?? "floyd_steinberg",
                align: r["align"] as? String ?? "center",
                invert: r["invert"] as? Bool ?? false,
                cut: r["cut"] as? Bool ?? true,
                feedLines: r["feedLines"] as? Int ?? 3,
                openCashDrawer: r["openCashDrawer"] as? Bool ?? false,
                copies: r["copies"] as? Int ?? 1
            )
        }
        let req = ThermalPrinterEngine.PrintRequest(
            printerId: call.getString("printerId"),
            filePath: image["filePath"] as? String,
            url: image["url"] as? String,
            base64: image["base64"] as? String,
            render: render,
            timeoutMs: call.getInt("timeoutMs") ?? 15000,
            autoReconnect: call.getBool("autoReconnect") ?? true
        )
        Task { await self.guarded(call) {
            let out = try await self.engine.printImage(req)
            call.resolve(self.printResultDict(out))
        } }
    }

    @objc func printText(_ call: CAPPluginCall) {
        guard let rawItems = call.getArray("items") as? [[String: Any]] else {
            return reject(call, .IMAGE_INVALID, "items requis")
        }
        let req = ThermalPrinterEngine.PrintTextRequest(
            printerId: call.getString("printerId"),
            items: PrintItem.parseList(rawItems),
            defaultCodePage: call.getString("defaultCodePage") ?? "WPC1252",
            cut: call.getBool("cut") ?? false,
            feedLines: call.getInt("feedLines") ?? 3,
            timeoutMs: call.getInt("timeoutMs") ?? 15000,
            autoReconnect: call.getBool("autoReconnect") ?? true
        )
        Task { await self.guarded(call) {
            let out = try await self.engine.printText(req)
            call.resolve(self.printResultDict(out))
        } }
    }

    private func printResultDict(_ out: ThermalPrinterEngine.PrintOutcome) -> [String: Any] {
        var result: [String: Any] = [
            "success": out.state == "completed",
            "printerId": out.printerId,
            "adapter": out.adapter.rawValue,
            "jobId": out.jobId,
            "state": out.state,
            "bytesSent": out.bytesSent,
            "durationMs": out.durationMs,
        ]
        if let status = out.status { result["status"] = status.toDict() }
        return result
    }

    @objc func getPrinterStatus(_ call: CAPPluginCall) {
        Task { await self.guarded(call) {
            let status = try await self.engine.getStatus(call.getString("printerId"))
            call.resolve(status.toDict())
        } }
    }

    // MARK: Monitoring (Phase 6 — stubs)

    @objc func startStatusMonitor(_ call: CAPPluginCall) { call.resolve() }
    @objc func stopStatusMonitor(_ call: CAPPluginCall) { call.resolve() }

    @objc func getDebugLog(_ call: CAPPluginCall) {
        call.resolve(["log": engine.debugLog()])
    }

    // MARK: Plomberie erreurs

    private func guarded(_ call: CAPPluginCall, _ block: () async throws -> Void) async {
        do { try await block() } catch { rejectError(call, error) }
    }

    private func reject(_ call: CAPPluginCall, _ code: ErrorCode, _ message: String) {
        call.reject(message, code.rawValue, nil, ["code": code.rawValue, "retryable": false])
    }

    private func rejectError(_ call: CAPPluginCall, _ error: Error) {
        if let pe = error as? PrinterError {
            Logger.shared.error("plugin", "\(pe.code.rawValue): \(pe.message)")
            call.reject(pe.message, pe.code.rawValue, nil, ["code": pe.code.rawValue, "detail": pe.detail ?? "", "retryable": pe.retryable])
        } else {
            Logger.shared.error("plugin", "UNKNOWN: \(error.localizedDescription)")
            call.reject(error.localizedDescription, ErrorCode.UNKNOWN.rawValue, error)
        }
    }
}
