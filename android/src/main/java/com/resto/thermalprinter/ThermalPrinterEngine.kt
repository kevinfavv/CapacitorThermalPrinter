package com.resto.thermalprinter

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import com.resto.thermalprinter.adapters.BleAdapter
import com.resto.thermalprinter.adapters.BrotherAdapter
import com.resto.thermalprinter.adapters.EpsonAdapter
import com.resto.thermalprinter.adapters.EscPosAdapter
import com.resto.thermalprinter.adapters.PrinterAdapter
import com.resto.thermalprinter.adapters.RawTcpAdapter
import com.resto.thermalprinter.adapters.StarAdapter
import com.resto.thermalprinter.adapters.UsbAdapter
import com.resto.thermalprinter.adapters.ZebraAdapter
import com.resto.thermalprinter.discovery.DiscoveryManager
import com.resto.thermalprinter.image.ImageCache
import com.resto.thermalprinter.image.ImageProcessor
import com.resto.thermalprinter.model.AdapterId
import com.resto.thermalprinter.model.Capabilities
import com.resto.thermalprinter.model.DiscoveredPrinter
import com.resto.thermalprinter.model.ErrorCode
import com.resto.thermalprinter.model.PrinterException
import com.resto.thermalprinter.model.PrinterProfile
import com.resto.thermalprinter.model.PrinterStatus
import com.resto.thermalprinter.model.RenderOptions
import com.resto.thermalprinter.model.Transport
import com.resto.thermalprinter.store.PrinterStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Cœur applicatif côté Android. Indépendant de Capacitor (testable).
 *
 * Responsabilités :
 *   - tenir la registry d'adapters,
 *   - exposer la découverte agrégée,
 *   - gérer connexion / reconnexion / déconnexion,
 *   - exécuter le flux printImage (load -> resize -> mono -> dither -> adapter -> send),
 *   - persister les profils (PrinterStore) et l'imprimante par défaut.
 */
class ThermalPrinterEngine(private val context: Context) {

    private val btAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val store = PrinterStore(context)
    private val imageCache = ImageCache(context)

    /** Registry d'adapters. L'ordre n'a pas d'importance (priorité gérée ailleurs). */
    private val adapters: List<PrinterAdapter> by lazy {
        listOf(
            EpsonAdapter(context),
            StarAdapter(context),
            BrotherAdapter(context),
            ZebraAdapter(context),
            EscPosAdapter(btAdapter),
            RawTcpAdapter(),
            UsbAdapter(context),
            BleAdapter(context),
        )
    }

    // Dernière liste découverte (pour résoudre un printerId vers un profil ad hoc).
    @Volatile private var lastDiscovered: List<DiscoveredPrinter> = emptyList()

    /** Émetteur d'états de job (branché par le plugin sur notifyListeners). */
    var onJobUpdate: ((JobUpdate) -> Unit)? = null

    /** Émetteur de changement de statut (branché par le plugin sur 'statusChange'). */
    var onStatusChange: ((PrinterStatus) -> Unit)? = null

    /** Scope + registre des moniteurs de statut actifs (Phase 6). */
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitors = ConcurrentHashMap<String, Job>()

    /** Mise à jour d'état d'un job d'impression. */
    data class JobUpdate(
        val jobId: String,
        val printerId: String,
        val state: String, // pending|printing|hold|completed|failed|canceled
        val holdReason: String? = null,
        val progress: Double? = null,
        val errorCode: ErrorCode? = null,
        val message: String? = null,
    )

    private fun emitJob(
        jobId: String,
        printerId: String,
        state: String,
        holdReason: String? = null,
        progress: Double? = null,
        errorCode: ErrorCode? = null,
        message: String? = null,
    ) {
        onJobUpdate?.invoke(JobUpdate(jobId, printerId, state, holdReason, progress, errorCode, message))
    }

    // -------------------------------------------------------------------------
    // Découverte
    // -------------------------------------------------------------------------

    suspend fun discover(
        options: DiscoveryManager.Options,
        emitPartial: (DiscoveredPrinter) -> Unit,
    ): Pair<List<DiscoveredPrinter>, List<String>> {
        Logger.log("discovery", "start", mapOf("sources" to (options.sources?.joinToString() ?: "all")))
        val manager = DiscoveryManager(context, btAdapter, adapters)
        val (printers, failed) = manager.discover(options, emitPartial)
        // Marquer défaut / connecté connus.
        val defaultId = store.getDefault()?.id
        printers.forEach { p ->
            p.isDefault = p.id == defaultId
            val adapter = if (p.adapter == AdapterId.ESCPOS) escFamilyFor(p.transport) else adapterFor(p.adapter)
            p.isConnected = adapter?.isConnected(p.id) == true
        }
        lastDiscovered = printers
        Logger.log("discovery", "complete", mapOf("count" to printers.size, "failed" to failed.joinToString()))
        return Pair(printers, failed)
    }

    // -------------------------------------------------------------------------
    // Connexion / reconnexion
    // -------------------------------------------------------------------------

    suspend fun connect(printerId: String, timeoutMs: Long, forceAdapter: AdapterId?, setAsDefault: Boolean = false): Boolean {
        val profile = resolveProfile(printerId, forceAdapter)
        val adapter = adapterFor(profile)
            ?: throw PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Aucun adapter pour ${profile.adapter.value}")
        if (!adapter.isAvailable()) {
            throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "Adapter ${profile.adapter.value} indisponible")
        }
        Logger.log("connect", "connecting", mapOf("id" to printerId, "adapter" to profile.adapter.value))
        withTimeout(timeoutMs + 1000) { adapter.connect(profile, timeoutMs) }
        val connected = adapter.isConnected(printerId)
        Logger.log("connect", "connected", mapOf("id" to printerId, "ok" to connected))
        // setAsDefault UNIQUEMENT si la connexion a réussi.
        if (connected && setAsDefault) {
            store.upsert(profile)
            store.setDefault(printerId)
            Logger.log("connect", "set-default-after-connect", mapOf("id" to printerId))
        }
        return connected
    }

    suspend fun disconnect(printerId: String) {
        val profile = store.get(printerId) ?: lastDiscovered.firstOrNull { it.id == printerId }?.let(::toEphemeralProfile)
        val adapter = profile?.let { adapterFor(it) } ?: return
        adapter.disconnect(printerId)
        Logger.log("connect", "disconnected", mapOf("id" to printerId))
    }

    /** Assure une connexion avant impression (cœur de la reconnexion auto). */
    private suspend fun ensureConnected(profile: PrinterProfile, timeoutMs: Long) {
        val adapter = adapterFor(profile)
            ?: throw PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Adapter introuvable")
        if (adapter.isConnected(profile.id)) return
        Logger.log("connect", "auto-reconnect", mapOf("id" to profile.id))
        withTimeout(timeoutMs) { adapter.connect(profile, timeoutMs) }
    }

    // -------------------------------------------------------------------------
    // Impression (flux complet)
    // -------------------------------------------------------------------------

    data class PrintRequest(
        val printerId: String?,
        val filePath: String?,
        val url: String?,
        val base64: String?,
        val render: RenderOptions?,
        val timeoutMs: Long = 15000,
        val autoReconnect: Boolean = true,
    )

    data class PrintTextRequest(
        val printerId: String?,
        val items: List<com.resto.thermalprinter.model.PrintItem>,
        val defaultCodePage: String = "WPC1252",
        val cut: Boolean = false,
        val feedLines: Int = 3,
        val timeoutMs: Long = 15000,
        val autoReconnect: Boolean = true,
    )

    data class PrintOutcome(
        val printerId: String,
        val adapter: AdapterId,
        val jobId: String,
        val state: String,
        val bytesSent: Int,
        val durationMs: Long,
        val status: PrinterStatus?,
    )

    suspend fun printImage(req: PrintRequest): PrintOutcome {
        val started = System.currentTimeMillis()
        val jobId = java.util.UUID.randomUUID().toString()

        val profile = resolveTargetProfile(req.printerId)
        emitJob(jobId, profile.id, "pending")
        val adapter = adapterFor(profile)
            ?: throw failJob(jobId, profile.id, PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Adapter introuvable"))

        try {
            // 2/3. Connexion ou reconnexion auto.
            if (!adapter.isConnected(profile.id)) {
                if (!req.autoReconnect) throw PrinterException(ErrorCode.CONNECTION_FAILED, "Imprimante non connectée")
                ensureConnected(profile, req.timeoutMs)
            }

            // Pré-contrôle statut -> HOLD si problème connu (papier/capot).
            preflightHold(adapter, profile, jobId)

            // 4. Charger l'image.
            val bitmap = loadImage(req)
            val render = resolveRenderOptions(profile, req.render)
            // 5/6. Resize (sauf si désactivé).
            val resized = if (render.resize) ImageProcessor.resizeToWidth(bitmap, render.widthDots) else bitmap
            if (render.resize && bitmap != resized) bitmap.recycle()

            emitJob(jobId, profile.id, "printing", progress = 0.1)
            val bytes = try {
                withTimeout(req.timeoutMs) { adapter.printBitmap(profile, resized, render) }
            } finally {
                resized.recycle()
            }

            val status = runCatching { adapter.getStatus(profile) }.getOrNull()
            val duration = System.currentTimeMillis() - started
            emitJob(jobId, profile.id, "completed", progress = 1.0)
            Logger.log("print", "done", mapOf("id" to profile.id, "bytes" to bytes, "ms" to duration))
            return PrintOutcome(profile.id, profile.adapter, jobId, "completed", bytes, duration, status)
        } catch (e: PrinterException) {
            throw failJob(jobId, profile.id, e)
        }
    }

    suspend fun printText(req: PrintTextRequest): PrintOutcome {
        val started = System.currentTimeMillis()
        val jobId = java.util.UUID.randomUUID().toString()

        val profile = resolveTargetProfile(req.printerId)
        emitJob(jobId, profile.id, "pending")
        val adapter = adapterFor(profile)
            ?: throw failJob(jobId, profile.id, PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Adapter introuvable"))

        try {
            if (!adapter.isConnected(profile.id)) {
                if (!req.autoReconnect) throw PrinterException(ErrorCode.CONNECTION_FAILED, "Imprimante non connectée")
                ensureConnected(profile, req.timeoutMs)
            }
            preflightHold(adapter, profile, jobId)

            emitJob(jobId, profile.id, "printing", progress = 0.1)
            val bytes = withTimeout(req.timeoutMs) {
                adapter.printItems(profile, req.items, req.defaultCodePage, req.cut, req.feedLines)
            }
            val status = runCatching { adapter.getStatus(profile) }.getOrNull()
            val duration = System.currentTimeMillis() - started
            emitJob(jobId, profile.id, "completed", progress = 1.0)
            Logger.log("print", "text done", mapOf("id" to profile.id, "items" to req.items.size, "bytes" to bytes))
            return PrintOutcome(profile.id, profile.adapter, jobId, "completed", bytes, duration, status)
        } catch (e: PrinterException) {
            throw failJob(jobId, profile.id, e)
        }
    }

    /** Lit le statut avant impression ; émet HOLD + lève si papier/capot bloquant. */
    private suspend fun preflightHold(adapter: PrinterAdapter, profile: PrinterProfile, jobId: String) {
        if (!profile.capabilities.supportsStatus) return
        val st = runCatching { adapter.getStatus(profile) }.getOrNull() ?: return
        if (st.paper == "empty") {
            emitJob(jobId, profile.id, "hold", holdReason = "paper_empty")
            throw PrinterException(ErrorCode.PAPER_EMPTY, "Plus de papier", retryable = true)
        }
        if (st.coverOpen == true) {
            emitJob(jobId, profile.id, "hold", holdReason = "cover_open")
            throw PrinterException(ErrorCode.COVER_OPEN, "Capot ouvert", retryable = true)
        }
    }

    private fun failJob(jobId: String, printerId: String, e: PrinterException): PrinterException {
        emitJob(jobId, printerId, "failed", errorCode = e.code, message = e.message)
        return e
    }

    // -------------------------------------------------------------------------
    // Profils / défaut
    // -------------------------------------------------------------------------

    fun savedProfiles(): List<PrinterProfile> = store.all()
    fun defaultProfile(): PrinterProfile? = store.getDefault()
    fun removeProfile(id: String) = store.remove(id)

    /** Enregistre/MAJ le profil depuis la dernière découverte et le marque par défaut. */
    fun setDefault(printerId: String): PrinterProfile {
        val existing = store.get(printerId)
        if (existing != null) {
            return store.setDefault(printerId)
                ?: throw PrinterException(ErrorCode.PRINTER_NOT_FOUND, "Profil introuvable")
        }
        val discovered = lastDiscovered.firstOrNull { it.id == printerId }
            ?: throw PrinterException(ErrorCode.PRINTER_NOT_FOUND, "Imprimante inconnue: $printerId")
        val profile = toEphemeralProfile(discovered).copy(isDefault = true)
        store.upsert(profile)
        return store.setDefault(printerId) ?: profile
    }

    suspend fun getStatus(printerId: String?): PrinterStatus {
        val profile = resolveTargetProfile(printerId)
        val adapter = adapterFor(profile)
            ?: throw PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Adapter introuvable")
        return adapter.getStatus(profile)
    }

    // -------------------------------------------------------------------------
    // Monitoring de statut (Phase 6)
    // -------------------------------------------------------------------------

    /**
     * Démarre un polling périodique du statut de [printerId] et émet `statusChange`
     * uniquement quand l'état pertinent change (connexion/online/papier/capot).
     * Idempotent : relance le moniteur si déjà actif.
     */
    fun startStatusMonitor(printerId: String, intervalMs: Long) {
        stopStatusMonitor(printerId)
        val interval = intervalMs.coerceIn(1000L, 300_000L)
        monitors[printerId] = monitorScope.launch {
            var lastKey: String? = null
            while (isActive) {
                val status = runCatching { getStatus(printerId) }.getOrElse { e ->
                    val code = (e as? PrinterException)?.code
                    PrinterStatus(printerId, "error", online = false, paper = "unknown", errorCode = code, rawStatus = e.message)
                }
                val key = "${status.connection}|${status.online}|${status.paper}|${status.coverOpen}|${status.errorCode}"
                if (key != lastKey) {
                    lastKey = key
                    onStatusChange?.invoke(status)
                    Logger.log("status", "change", mapOf("id" to printerId, "paper" to status.paper, "conn" to status.connection))
                }
                delay(interval)
            }
        }
        Logger.log("status", "monitor-start", mapOf("id" to printerId, "intervalMs" to interval))
    }

    /** Arrête le moniteur d'une imprimante (no-op si absent). */
    fun stopStatusMonitor(printerId: String) {
        monitors.remove(printerId)?.cancel()
    }

    /** Arrête tous les moniteurs (à l'arrêt du plugin). */
    fun stopAllMonitors() {
        monitors.values.forEach { it.cancel() }
        monitors.clear()
    }

    fun debugLog() = Logger.snapshot()

    // -------------------------------------------------------------------------
    // Helpers internes
    // -------------------------------------------------------------------------

    private fun adapterFor(id: AdapterId): PrinterAdapter? = when (id) {
        AdapterId.ESCPOS -> adapters.firstOrNull { it is EscPosAdapter }
        AdapterId.EPSON -> adapters.firstOrNull { it is EpsonAdapter }
        AdapterId.STAR -> adapters.firstOrNull { it is StarAdapter }
        AdapterId.BROTHER -> adapters.firstOrNull { it is BrotherAdapter }
        AdapterId.ZEBRA -> adapters.firstOrNull { it is ZebraAdapter }
        AdapterId.RAW_TCP -> adapters.firstOrNull { it is RawTcpAdapter }
    }

    /**
     * Résolution transport-aware : la famille ESC/POS (escpos) regroupe 3 adapters
     * distincts (TCP/SPP, USB, BLE) qui ne se différencient que par le transport.
     */
    private fun adapterFor(profile: PrinterProfile): PrinterAdapter? =
        if (profile.adapter == AdapterId.ESCPOS) escFamilyFor(profile.transport) else adapterFor(profile.adapter)

    private fun escFamilyFor(transport: Transport): PrinterAdapter? = when (transport) {
        Transport.USB -> adapters.firstOrNull { it is UsbAdapter }
        Transport.BLE -> adapters.firstOrNull { it is BleAdapter }
        else -> adapters.firstOrNull { it is EscPosAdapter }
    }

    private fun resolveTargetProfile(printerId: String?): PrinterProfile {
        if (printerId == null) {
            return store.getDefault()
                ?: throw PrinterException(ErrorCode.PRINTER_NOT_FOUND, "Aucune imprimante par défaut")
        }
        return resolveProfile(printerId, null)
    }

    /** Profil persistant si connu, sinon profil éphémère depuis la découverte. */
    private fun resolveProfile(printerId: String, forceAdapter: AdapterId?): PrinterProfile {
        store.get(printerId)?.let { p ->
            return if (forceAdapter != null) p.copy(adapter = forceAdapter) else p
        }
        val d = lastDiscovered.firstOrNull { it.id == printerId }
            ?: throw PrinterException(ErrorCode.PRINTER_NOT_FOUND, "Imprimante inconnue: $printerId")
        val base = toEphemeralProfile(d)
        return if (forceAdapter != null) base.copy(adapter = forceAdapter) else base
    }

    private fun toEphemeralProfile(d: DiscoveredPrinter): PrinterProfile = PrinterProfile(
        id = d.id,
        adapter = d.adapter,
        transport = d.transport,
        address = d.address,
        brand = d.brand,
        model = d.model,
        name = d.name,
        capabilities = d.capabilities?.let { mergeCaps(it) } ?: Capabilities(),
    )

    private fun mergeCaps(partial: Capabilities): Capabilities = partial

    private fun loadImage(req: PrintRequest): Bitmap = when {
        !req.filePath.isNullOrBlank() -> ImageProcessor.decodeFile(req.filePath)
        !req.url.isNullOrBlank() -> ImageProcessor.decodeFile(imageCache.fetch(req.url).absolutePath)
        !req.base64.isNullOrBlank() -> ImageProcessor.decodeBase64(req.base64)
        else -> throw PrinterException(ErrorCode.IMAGE_INVALID, "Aucune source image fournie")
    }

    private fun resolveRenderOptions(profile: PrinterProfile, req: RenderOptions?): RenderOptions {
        val width = req?.widthDots?.takeIf { it > 0 }
            ?: profile.capabilities.printableDots.takeIf { it > 0 }
            ?: when (profile.capabilities.paperWidthMm) { 58 -> 384; 112 -> 832; else -> 576 }
        return (req ?: RenderOptions(widthDots = width)).copy(widthDots = width)
    }
}
