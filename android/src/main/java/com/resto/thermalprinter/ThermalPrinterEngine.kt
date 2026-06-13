package com.resto.thermalprinter

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
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
import com.resto.thermalprinter.store.PrinterStore
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
        )
    }

    // Dernière liste découverte (pour résoudre un printerId vers un profil ad hoc).
    @Volatile private var lastDiscovered: List<DiscoveredPrinter> = emptyList()

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
            p.isConnected = adapterFor(p.adapter)?.isConnected(p.id) == true
        }
        lastDiscovered = printers
        Logger.log("discovery", "complete", mapOf("count" to printers.size, "failed" to failed.joinToString()))
        return Pair(printers, failed)
    }

    // -------------------------------------------------------------------------
    // Connexion / reconnexion
    // -------------------------------------------------------------------------

    suspend fun connect(printerId: String, timeoutMs: Long, forceAdapter: AdapterId?): Boolean {
        val profile = resolveProfile(printerId, forceAdapter)
        val adapter = adapterFor(profile.adapter)
            ?: throw PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Aucun adapter pour ${profile.adapter.value}")
        if (!adapter.isAvailable()) {
            throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "Adapter ${profile.adapter.value} indisponible")
        }
        Logger.log("connect", "connecting", mapOf("id" to printerId, "adapter" to profile.adapter.value))
        withTimeout(timeoutMs + 1000) { adapter.connect(profile, timeoutMs) }
        Logger.log("connect", "connected", mapOf("id" to printerId))
        return adapter.isConnected(printerId)
    }

    suspend fun disconnect(printerId: String) {
        val profile = store.get(printerId) ?: lastDiscovered.firstOrNull { it.id == printerId }?.let(::toEphemeralProfile)
        val adapter = profile?.let { adapterFor(it.adapter) } ?: return
        adapter.disconnect(printerId)
        Logger.log("connect", "disconnected", mapOf("id" to printerId))
    }

    /** Assure une connexion avant impression (cœur de la reconnexion auto). */
    private suspend fun ensureConnected(profile: PrinterProfile, timeoutMs: Long) {
        val adapter = adapterFor(profile.adapter)
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

    data class PrintOutcome(
        val printerId: String,
        val adapter: AdapterId,
        val bytesSent: Int,
        val durationMs: Long,
        val status: PrinterStatus?,
    )

    suspend fun printImage(req: PrintRequest): PrintOutcome {
        val started = System.currentTimeMillis()

        // 1. Résoudre l'imprimante cible (sinon imprimante par défaut).
        val profile = resolveTargetProfile(req.printerId)
        val adapter = adapterFor(profile.adapter)
            ?: throw PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Adapter introuvable")

        // 2/3. Connexion ou reconnexion auto.
        if (!adapter.isConnected(profile.id)) {
            if (!req.autoReconnect) {
                throw PrinterException(ErrorCode.CONNECTION_FAILED, "Imprimante non connectée")
            }
            ensureConnected(profile, req.timeoutMs)
        }

        // 4. Charger l'image (fichier > url > base64).
        val bitmap = loadImage(req)
        Logger.log("print", "image loaded", mapOf("w" to bitmap.width, "h" to bitmap.height))

        // 5. Largeur cible + 6/7. resize + (mono/dither faits dans l'adapter ESC/POS).
        val render = resolveRenderOptions(profile, req.render)
        val resized = ImageProcessor.resizeToWidth(bitmap, render.widthDots)
        if (bitmap != resized) bitmap.recycle()

        // 8/9. Conversion adapter + envoi (avec timeout global).
        val bytes = try {
            withTimeout(req.timeoutMs) { adapter.printBitmap(profile, resized, render) }
        } finally {
            resized.recycle()
        }

        // 11. Statut post-impression (best effort).
        val status = runCatching { adapter.getStatus(profile) }.getOrNull()
        val duration = System.currentTimeMillis() - started
        Logger.log("print", "done", mapOf("id" to profile.id, "bytes" to bytes, "ms" to duration))
        return PrintOutcome(profile.id, profile.adapter, bytes, duration, status)
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
        val adapter = adapterFor(profile.adapter)
            ?: throw PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Adapter introuvable")
        return adapter.getStatus(profile)
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
