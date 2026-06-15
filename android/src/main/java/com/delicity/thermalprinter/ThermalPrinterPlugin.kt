package com.delicity.thermalprinter

import android.Manifest
import android.os.Build
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.delicity.thermalprinter.discovery.DiscoveryManager
import com.delicity.thermalprinter.model.PrinterException
import com.delicity.thermalprinter.model.RenderOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pont Capacitor (bridge JS <-> natif). Mappe l'API publique ThermalPrinterPlugin
 * (definitions.ts) vers le ThermalPrinterEngine.
 *
 * Toutes les opérations longues s'exécutent sur Dispatchers.IO ; les erreurs
 * PrinterException sont converties en rejets Capacitor avec le code normalisé.
 */
@CapacitorPlugin(
    name = "ThermalPrinter",
    permissions = [
        Permission(alias = "bluetoothScan", strings = [Manifest.permission.BLUETOOTH_SCAN]),
        Permission(alias = "bluetoothConnect", strings = [Manifest.permission.BLUETOOTH_CONNECT]),
        Permission(alias = "location", strings = [Manifest.permission.ACCESS_FINE_LOCATION]),
    ],
)
class ThermalPrinterPlugin : Plugin() {

    private lateinit var engine: ThermalPrinterEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun load() {
        engine = ThermalPrinterEngine(context)
        // Relaye les états de job vers le JS (event printJobStatus).
        engine.onJobUpdate = { u ->
            val job = JSObject()
                .put("jobId", u.jobId)
                .put("printerId", u.printerId)
                .put("state", u.state)
                .put("updatedAt", System.currentTimeMillis())
            u.holdReason?.let { job.put("holdReason", it) }
            u.progress?.let { job.put("progress", it) }
            u.errorCode?.let { job.put("errorCode", it.name) }
            u.message?.let { job.put("message", it) }
            notifyListeners("printJobStatus", JSObject().put("job", job))
        }
        // Relaye les changements de statut vers le JS (event statusChange).
        engine.onStatusChange = { status ->
            notifyListeners("statusChange", JSObject().put("status", status.toJson()))
        }
        Logger.log("plugin", "loaded")
    }

    // ---------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------

    @PluginMethod
    override fun checkPermissions(call: PluginCall) {
        call.resolve(buildPermissionStatus())
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        // Sur API < 31, les permissions BT runtime n'existent pas -> rien à demander.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            call.resolve(buildPermissionStatus())
            return
        }
        requestPermissionForAliases(arrayOf("bluetoothScan", "bluetoothConnect"), call, "permsCallback")
    }

    @PermissionCallback
    private fun permsCallback(call: PluginCall) {
        call.resolve(buildPermissionStatus())
    }

    private fun buildPermissionStatus(): JSObject {
        fun state(alias: String): String =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && alias.startsWith("bluetooth")) "granted"
            else getPermissionState(alias)?.toString() ?: "prompt"
        return JSObject().apply {
            put("bluetooth", state("bluetoothConnect"))
            put("bluetoothScan", state("bluetoothScan"))
            put("bluetoothConnect", state("bluetoothConnect"))
            put("location", state("location"))
            put("localNetwork", "granted") // pas de permission runtime réseau sur Android
        }
    }

    // ---------------------------------------------------------------------
    // Découverte
    // ---------------------------------------------------------------------

    @PluginMethod
    fun discoverPrinters(call: PluginCall) = exec(call) {
        val sources = call.getArray("sources", null)?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }
        val options = DiscoveryManager.Options(
            sources = sources,
            timeoutMs = (call.getInt("timeoutMs")?.toLong()) ?: 8000L,
            includePaired = call.getBoolean("includePaired", true) ?: true,
            networkCidr = call.getString("networkCidr"),
            tcpPorts = call.getArray("tcpPorts", null)?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optInt(it) }
            } ?: listOf(9100),
        )
        val emitPartial = call.getBoolean("emitPartialResults", true) ?: true

        val (printers, failed) = engine.discover(options) { p ->
            if (emitPartial) notifyListeners("printerFound", JSObject().put("printer", p.toJson()))
        }

        val arr = JSArray()
        printers.forEach { arr.put(it.toJson()) }
        val failedArr = JSArray()
        failed.forEach { failedArr.put(it) }
        val result = JSObject().put("printers", arr)

        // Event de fin de découverte.
        notifyListeners(
            "discoveryComplete",
            JSObject().put("printers", arr).put("failedSources", failedArr),
        )
        result
    }

    // ---------------------------------------------------------------------
    // Connexion
    // ---------------------------------------------------------------------

    @PluginMethod
    fun connectPrinter(call: PluginCall) = exec(call) {
        val printerId = call.getString("printerId")
            ?: throw PrinterException(com.delicity.thermalprinter.model.ErrorCode.PRINTER_NOT_FOUND, "printerId requis")
        val timeout = (call.getInt("timeoutMs")?.toLong()) ?: 10000L
        val force = call.getString("forceAdapter")?.let { com.delicity.thermalprinter.model.AdapterId.from(it) }
        val setAsDefault = call.getBoolean("setAsDefault", false) ?: false
        val result = engine.connect(printerId, timeout, force, setAsDefault)
        JSObject()
            .put("connected", result.connected)
            .put("paper", result.paper?.toJson() ?: JSObject.NULL)
    }

    @PluginMethod
    fun disconnectPrinter(call: PluginCall) = exec(call) {
        engine.disconnect(call.getString("printerId") ?: "")
        JSObject()
    }

    // ---------------------------------------------------------------------
    // Profils / défaut
    // ---------------------------------------------------------------------

    @PluginMethod
    fun setDefaultPrinter(call: PluginCall) = exec(call) {
        val id = call.getString("printerId")
            ?: throw PrinterException(com.delicity.thermalprinter.model.ErrorCode.PRINTER_NOT_FOUND, "printerId requis")
        val profile = engine.setDefault(id)
        JSObject().put("profile", profile.toJson())
    }

    @PluginMethod
    fun getDefaultPrinter(call: PluginCall) = exec(call) {
        val profile = engine.defaultProfile()
        JSObject().put("profile", profile?.toJson() ?: JSObject.NULL)
    }

    @PluginMethod
    fun getSavedPrinters(call: PluginCall) = exec(call) {
        val arr = JSArray()
        engine.savedProfiles().forEach { arr.put(it.toJson()) }
        JSObject().put("profiles", arr)
    }

    @PluginMethod
    fun removePrinter(call: PluginCall) = exec(call) {
        engine.removeProfile(call.getString("printerId") ?: "")
        JSObject()
    }

    // ---------------------------------------------------------------------
    // Impression / statut
    // ---------------------------------------------------------------------

    @PluginMethod
    fun printImage(call: PluginCall) = exec(call) {
        val image = call.getObject("image")
            ?: throw PrinterException(com.delicity.thermalprinter.model.ErrorCode.IMAGE_INVALID, "image requise")
        val render = call.getObject("render")?.let { r ->
            RenderOptions(
                widthDots = r.optInt("widthDots", 0),
                resize = r.optBoolean("resize", true),
                grayscale = r.optBoolean("grayscale", true),
                threshold = r.optInt("threshold", 128),
                dithering = r.optString("dithering", "floyd_steinberg"),
                align = r.optString("align", "center"),
                invert = r.optBoolean("invert", false),
                cut = r.optBoolean("cut", true),
                feedLines = r.optInt("feedLines", 3),
                openCashDrawer = r.optBoolean("openCashDrawer", false),
                copies = r.optInt("copies", 1),
            )
        }
        val req = ThermalPrinterEngine.PrintRequest(
            printerId = call.getString("printerId"),
            filePath = image.optString("filePath", null),
            url = image.optString("url", null),
            base64 = image.optString("base64", null),
            render = render,
            timeoutMs = (call.getInt("timeoutMs")?.toLong()) ?: 15000L,
            autoReconnect = call.getBoolean("autoReconnect", true) ?: true,
        )
        val out = engine.printImage(req)
        printResultJson(out)
    }

    @PluginMethod
    fun printText(call: PluginCall) = exec(call) {
        val itemsArr = call.getArray("items")
            ?: throw PrinterException(com.delicity.thermalprinter.model.ErrorCode.IMAGE_INVALID, "items requis")
        val items = com.delicity.thermalprinter.model.PrintItem.parseList(itemsArr)
        val req = ThermalPrinterEngine.PrintTextRequest(
            printerId = call.getString("printerId"),
            items = items,
            defaultCodePage = call.getString("defaultCodePage") ?: "WPC1252",
            cut = call.getBoolean("cut", false) ?: false,
            feedLines = call.getInt("feedLines") ?: 3,
            timeoutMs = (call.getInt("timeoutMs")?.toLong()) ?: 15000L,
            autoReconnect = call.getBoolean("autoReconnect", true) ?: true,
        )
        val out = engine.printText(req)
        printResultJson(out)
    }

    private fun printResultJson(out: ThermalPrinterEngine.PrintOutcome): JSObject = JSObject().apply {
        put("success", out.state == "completed")
        put("printerId", out.printerId)
        put("adapter", out.adapter.value)
        put("jobId", out.jobId)
        put("state", out.state)
        put("bytesSent", out.bytesSent)
        put("durationMs", out.durationMs)
        out.status?.let { put("status", it.toJson()) }
    }

    @PluginMethod
    fun getPrinterStatus(call: PluginCall) = exec(call) {
        JSObject.fromJSONObject(engine.getStatus(call.getString("printerId")).toJson())
    }

    // ---------------------------------------------------------------------
    // Monitoring (Phase 6) — stubs branchés à compléter
    // ---------------------------------------------------------------------

    @PluginMethod
    fun startStatusMonitor(call: PluginCall) = exec(call) {
        val printerId = call.getString("printerId")
            ?: throw PrinterException(com.delicity.thermalprinter.model.ErrorCode.PRINTER_NOT_FOUND, "printerId requis")
        val interval = (call.getInt("intervalMs")?.toLong()) ?: 5000L
        engine.startStatusMonitor(printerId, interval)
        JSObject()
    }

    @PluginMethod
    fun stopStatusMonitor(call: PluginCall) = exec(call) {
        val printerId = call.getString("printerId")
            ?: throw PrinterException(com.delicity.thermalprinter.model.ErrorCode.PRINTER_NOT_FOUND, "printerId requis")
        engine.stopStatusMonitor(printerId)
        JSObject()
    }

    @PluginMethod
    fun getActiveSdks(call: PluginCall) = exec(call) {
        val arr = JSArray()
        engine.activeSdks().forEach { s ->
            val transports = JSArray()
            s.transports.forEach { transports.put(it) }
            arr.put(
                JSObject()
                    .put("adapter", s.adapter)
                    .put("label", s.label)
                    .put("available", s.available)
                    .put("requiresSdk", s.requiresSdk)
                    .put("transports", transports),
            )
        }
        JSObject().put("sdks", arr)
    }

    @PluginMethod
    fun getDebugLog(call: PluginCall) {
        call.resolve(JSObject().put("log", engine.debugLog()))
    }

    // ---------------------------------------------------------------------
    // Plomberie : exécution async + normalisation d'erreurs
    // ---------------------------------------------------------------------

    private fun exec(call: PluginCall, block: suspend () -> JSObject) {
        scope.launch {
            try {
                val result = block()
                withContext(Dispatchers.Main) { call.resolve(result) }
            } catch (e: PrinterException) {
                Logger.error("plugin", "${e.code}: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    val data = JSObject().put("code", e.code.name).put("detail", e.detail).put("retryable", e.retryable)
                    call.reject(e.message, e.code.name, e, data)
                }
            } catch (e: Exception) {
                Logger.error("plugin", "UNKNOWN: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    call.reject(e.message ?: "Erreur inconnue", "UNKNOWN", e)
                }
            }
        }
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        engine.stopAllMonitors()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
