package com.resto.thermalprinter.adapters

import android.content.Context
import android.graphics.Bitmap
import com.resto.thermalprinter.model.AdapterId
import com.resto.thermalprinter.model.DiscoveredPrinter
import com.resto.thermalprinter.model.ErrorCode
import com.resto.thermalprinter.model.PrinterException
import com.resto.thermalprinter.model.PrinterProfile
import com.resto.thermalprinter.model.PrinterStatus
import com.resto.thermalprinter.model.RenderOptions
import com.resto.thermalprinter.model.Transport
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/**
 * Adapter Epson basé sur le SDK ePOS2 (`com.epson.epos2`), piloté par RÉFLEXION.
 *
 * Le SDK ePOS2 n'est PAS redistribuable (licence Epson) : il n'est donc pas une
 * dépendance de compilation. L'app doit déposer `ePOS2.jar` (+ `.so`) — voir
 * docs/SDK_INTEGRATION.md (§ Epson). Tant que le binaire est absent,
 * `isAvailable()` renvoie false et l'adapter est ignoré (fallback ESC/POS).
 *
 * Chemin principal : impression IMAGE (réception rendue en bitmap). Le texte stylé
 * SDK (printItems) n'est pas mappé ici -> utiliser printImage pour les marques SDK.
 */
class EpsonAdapter(private val context: Context) : PrinterAdapter {

    override val id = AdapterId.EPSON

    private val cache = ConcurrentHashMap<String, Any>() // printerId -> com.epson.epos2.printer.Printer

    override fun isAvailable(): Boolean = classExists(PRINTER)

    override fun supportsTextItems(): Boolean = isAvailable()

    // -------------------------------------------------------------------------
    // Découverte (Discovery.start + DiscoveryListener via proxy)
    // -------------------------------------------------------------------------

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        if (!isAvailable()) return
        val filter = SdkReflect.newInstance(FILTER_OPTION, emptyArray(), emptyArray())
        runCatching {
            SdkReflect.call(filter, "setDeviceType", arrayOf(Int::class.javaPrimitiveType!!),
                arrayOf(SdkReflect.staticInt(DISCOVERY, "TYPE_PRINTER", 0)))
        }
        val listener = SdkReflect.proxy(DISCOVERY_LISTENER, mapOf(
            "onDiscovery" to { args ->
                val info = args.getOrNull(0)
                if (info != null) {
                    val target = SdkReflect.call(info, "getTarget") as? String ?: ""
                    val name = SdkReflect.call(info, "getDeviceName") as? String ?: "Epson"
                    val transport = when {
                        target.startsWith("BT:") -> Transport.BLUETOOTH
                        target.startsWith("USB:") -> Transport.USB
                        else -> Transport.WIFI
                    }
                    onFound(
                        DiscoveredPrinter(
                            id = "epson:$target",
                            name = name,
                            brand = "Epson",
                            model = name,
                            transport = transport,
                            adapter = AdapterId.EPSON,
                            address = target,
                            discoveredBy = mutableSetOf(AdapterId.EPSON),
                        ),
                    )
                }
                null
            },
        ))
        try {
            SdkReflect.callStatic(
                DISCOVERY, "start",
                arrayOf(Context::class.java, SdkReflect.classOrNull(FILTER_OPTION)!!, SdkReflect.classOrNull(DISCOVERY_LISTENER)!!),
                arrayOf(context, filter, listener),
            )
            delay(timeoutMs)
        } catch (e: Throwable) {
            throw PrinterException(ErrorCode.UNKNOWN, "Découverte Epson échouée", e.message)
        } finally {
            runCatching { SdkReflect.callStatic(DISCOVERY, "stop") }
        }
    }

    override fun canHandle(profile: PrinterProfile): Boolean =
        isAvailable() && profile.adapter == AdapterId.EPSON

    // -------------------------------------------------------------------------
    // Connexion
    // -------------------------------------------------------------------------

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) {
        ensureSdk()
        if (isConnected(profile.id)) return
        val series = seriesConstFor(profile.model)
        val lang = SdkReflect.staticInt(PRINTER, "MODEL_ANK", 0)
        val printer = SdkReflect.newInstance(
            PRINTER,
            arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Context::class.java),
            arrayOf(series, lang, context),
        )
        try {
            SdkReflect.call(
                printer, "connect",
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!),
                arrayOf(targetFor(profile), SdkReflect.staticInt(PRINTER, "PARAM_DEFAULT", -2)),
            )
        } catch (e: Throwable) {
            throw PrinterException(ErrorCode.CONNECTION_FAILED, "Connexion Epson échouée: ${profile.address}", e.message, retryable = true)
        }
        cache[profile.id] = printer
    }

    override fun isConnected(printerId: String): Boolean = cache.containsKey(printerId)

    override suspend fun disconnect(printerId: String) {
        cache.remove(printerId)?.let { printer ->
            runCatching { SdkReflect.call(printer, "disconnect") }
            runCatching { SdkReflect.call(printer, "clearCommandBuffer") }
        }
    }

    // -------------------------------------------------------------------------
    // Impression image
    // -------------------------------------------------------------------------

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        val printer = cache[profile.id]
            ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "Epson non connecté: ${profile.id}")
        val intT = Int::class.javaPrimitiveType!!
        val dblT = Double::class.javaPrimitiveType!!
        try {
            repeat(options.copies.coerceAtLeast(1)) {
                SdkReflect.call(printer, "beginTransaction")
                SdkReflect.call(
                    printer, "addImage",
                    arrayOf(Bitmap::class.java, intT, intT, intT, intT, intT, intT, intT, dblT, intT),
                    arrayOf(
                        bitmap, 0, 0, bitmap.width, bitmap.height,
                        SdkReflect.staticInt(PRINTER, "COLOR_1", 1),
                        SdkReflect.staticInt(PRINTER, "MODE_MONO", 0),
                        halftoneFor(options.dithering),
                        1.0,
                        SdkReflect.staticInt(PRINTER, "COMPRESS_AUTO", 0),
                    ),
                )
                if (options.cut && profile.capabilities.supportsCut) {
                    SdkReflect.call(printer, "addCut", arrayOf(intT), arrayOf(SdkReflect.staticInt(PRINTER, "CUT_FEED", 1)))
                }
                if (options.openCashDrawer && profile.capabilities.supportsCashDrawer) {
                    SdkReflect.call(
                        printer, "addPulse", arrayOf(intT, intT),
                        arrayOf(SdkReflect.staticInt(PRINTER, "DRAWER_2PIN", 0), SdkReflect.staticInt(PRINTER, "PULSE_100", 0)),
                    )
                }
                SdkReflect.call(printer, "sendData", arrayOf(intT), arrayOf(SdkReflect.staticInt(PRINTER, "PARAM_DEFAULT", -2)))
                SdkReflect.call(printer, "endTransaction")
                runCatching { SdkReflect.call(printer, "clearCommandBuffer") }
            }
        } catch (e: Throwable) {
            runCatching { SdkReflect.call(printer, "clearCommandBuffer") }
            throw PrinterException(ErrorCode.PRINT_FAILED, "Impression Epson échouée", e.message, retryable = true)
        }
        return bitmap.width * bitmap.height / 8
    }

    // -------------------------------------------------------------------------
    // Impression texte stylé (builder ePOS2 natif)
    // -------------------------------------------------------------------------

    override suspend fun printItems(
        profile: PrinterProfile,
        items: List<com.resto.thermalprinter.model.PrintItem>,
        defaultCodePage: String,
        cut: Boolean,
        feedLines: Int,
    ): Int {
        val printer = cache[profile.id]
            ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "Epson non connecté: ${profile.id}")
        try {
            SdkReflect.call(printer, "beginTransaction")
            for (item in items) mapTextItem(printer, item, profile)
            if (feedLines > 0) callInt(printer, "addFeedLine", feedLines)
            if (cut && profile.capabilities.supportsCut) callInt(printer, "addCut", SdkReflect.staticInt(PRINTER, "CUT_FEED", 1))
            callInt(printer, "sendData", SdkReflect.staticInt(PRINTER, "PARAM_DEFAULT", -2))
            SdkReflect.call(printer, "endTransaction")
            runCatching { SdkReflect.call(printer, "clearCommandBuffer") }
        } catch (e: Throwable) {
            runCatching { SdkReflect.call(printer, "clearCommandBuffer") }
            throw PrinterException(ErrorCode.PRINT_FAILED, "Impression texte Epson échouée", e.message, retryable = true)
        }
        return items.size
    }

    private fun mapTextItem(printer: Any, item: com.resto.thermalprinter.model.PrintItem, profile: PrinterProfile) {
        val P = com.resto.thermalprinter.model.PrintItem
        val tru = SdkReflect.staticInt(PRINTER, "TRUE", 1)
        val fls = SdkReflect.staticInt(PRINTER, "FALSE", 0)
        when (item) {
            is com.resto.thermalprinter.model.PrintItem.Text -> {
                val s = item.style
                callInt(printer, "addTextAlign", alignConst(s.align))
                SdkReflect.call(
                    printer, "addTextStyle",
                    arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
                    arrayOf(if (s.invert) tru else fls, if (s.underline != "none") tru else fls, if (s.bold) tru else fls, SdkReflect.staticInt(PRINTER, "COLOR_1", 1)),
                )
                SdkReflect.call(
                    printer, "addTextSize",
                    arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
                    arrayOf(s.widthMultiplier.coerceIn(1, 8), s.heightMultiplier.coerceIn(1, 8)),
                )
                callStr(printer, "addText", if (s.newline) item.value + "\n" else item.value)
            }
            is com.resto.thermalprinter.model.PrintItem.Feed -> callInt(printer, "addFeedLine", item.lines.coerceIn(1, 255))
            is com.resto.thermalprinter.model.PrintItem.Cut ->
                callInt(printer, "addCut", SdkReflect.staticInt(PRINTER, if (item.mode == "full") "CUT_NO_FEED" else "CUT_FEED", 1))
            is com.resto.thermalprinter.model.PrintItem.Divider -> {
                val cols = item.columns ?: if (profile.capabilities.printableDots <= 420) 32 else 48
                callInt(printer, "addTextAlign", alignConst(item.align))
                callStr(printer, "addText", item.char.take(1).ifEmpty { "-" }.repeat(cols.coerceIn(1, 96)) + "\n")
            }
            is com.resto.thermalprinter.model.PrintItem.QrCode -> {
                callInt(printer, "addTextAlign", alignConst(item.align))
                val i = Int::class.javaPrimitiveType!!
                SdkReflect.call(
                    printer, "addSymbol",
                    arrayOf(String::class.java, i, i, i, i, i),
                    arrayOf(item.value, SdkReflect.staticInt(PRINTER, "SYMBOL_QRCODE_MODEL_2", 0), qrLevelConst(item.ec), item.size.coerceIn(1, 16), item.size.coerceIn(1, 16), 0),
                )
            }
            is com.resto.thermalprinter.model.PrintItem.Barcode -> {
                callInt(printer, "addTextAlign", alignConst(item.align))
                val i = Int::class.javaPrimitiveType!!
                SdkReflect.call(
                    printer, "addBarcode",
                    arrayOf(String::class.java, i, i, i, i, i),
                    arrayOf(
                        item.value, barcodeConst(item.symbology),
                        SdkReflect.staticInt(PRINTER, if (item.hri == "none") "HRI_NONE" else "HRI_BELOW", 0),
                        SdkReflect.staticInt(PRINTER, "FONT_A", 0),
                        item.width.coerceIn(2, 6), item.height.coerceIn(1, 255),
                    ),
                )
            }
            is com.resto.thermalprinter.model.PrintItem.CashDrawer ->
                SdkReflect.call(
                    printer, "addPulse",
                    arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
                    arrayOf(SdkReflect.staticInt(PRINTER, "DRAWER_2PIN", 0), SdkReflect.staticInt(PRINTER, "PULSE_100", 0)),
                )
            is com.resto.thermalprinter.model.PrintItem.Image, is com.resto.thermalprinter.model.PrintItem.Raw -> Unit
        }
        @Suppress("UNUSED_EXPRESSION") P
    }

    private fun callInt(target: Any, method: String, value: Int) =
        SdkReflect.call(target, method, arrayOf(Int::class.javaPrimitiveType!!), arrayOf(value))

    private fun callStr(target: Any, method: String, value: String) =
        SdkReflect.call(target, method, arrayOf(String::class.java), arrayOf(value))

    private fun alignConst(align: String?): Int = when (align) {
        "center" -> SdkReflect.staticInt(PRINTER, "ALIGN_CENTER", 1)
        "right" -> SdkReflect.staticInt(PRINTER, "ALIGN_RIGHT", 2)
        else -> SdkReflect.staticInt(PRINTER, "ALIGN_LEFT", 0)
    }

    private fun qrLevelConst(ec: String): Int = when (ec.uppercase()) {
        "L" -> SdkReflect.staticInt(PRINTER, "LEVEL_L", 0)
        "Q" -> SdkReflect.staticInt(PRINTER, "LEVEL_Q", 2)
        "H" -> SdkReflect.staticInt(PRINTER, "LEVEL_H", 3)
        else -> SdkReflect.staticInt(PRINTER, "LEVEL_M", 1)
    }

    private fun barcodeConst(symbology: String): Int = when (symbology.uppercase()) {
        "CODE39" -> SdkReflect.staticInt(PRINTER, "BARCODE_CODE39", 0)
        "CODE93" -> SdkReflect.staticInt(PRINTER, "BARCODE_CODE93", 0)
        "EAN13" -> SdkReflect.staticInt(PRINTER, "BARCODE_EAN13", 0)
        "EAN8" -> SdkReflect.staticInt(PRINTER, "BARCODE_EAN8", 0)
        "ITF" -> SdkReflect.staticInt(PRINTER, "BARCODE_ITF", 0)
        "UPCA" -> SdkReflect.staticInt(PRINTER, "BARCODE_UPC_A", 0)
        "UPCE" -> SdkReflect.staticInt(PRINTER, "BARCODE_UPC_E", 0)
        "CODABAR" -> SdkReflect.staticInt(PRINTER, "BARCODE_CODABAR", 0)
        else -> SdkReflect.staticInt(PRINTER, "BARCODE_CODE128", 0)
    }

    // -------------------------------------------------------------------------
    // Statut
    // -------------------------------------------------------------------------

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus {
        val printer = cache[profile.id]
            ?: return PrinterStatus(profile.id, "disconnected", online = false, paper = "unknown")
        return try {
            val info = SdkReflect.call(printer, "getStatus")
                ?: return PrinterStatus(profile.id, "connected", online = true, paper = "unknown")
            val trueVal = SdkReflect.staticInt(PRINTER, "TRUE", 1)
            val paperEmptyVal = SdkReflect.staticInt(PRINTER, "PAPER_EMPTY", 2)
            val paperNearVal = SdkReflect.staticInt(PRINTER, "PAPER_NEAR_END", 1)
            val connection = SdkReflect.intField(info, "connection", -1)
            val online = SdkReflect.intField(info, "online", -1)
            val paper = SdkReflect.intField(info, "paper", -1)
            val coverOpen = SdkReflect.intField(info, "coverOpen", -1)
            PrinterStatus(
                id = profile.id,
                connection = if (connection == trueVal) "connected" else "disconnected",
                online = online == trueVal,
                paper = when (paper) {
                    paperEmptyVal -> "empty"
                    paperNearVal -> "near_end"
                    else -> "ok"
                },
                coverOpen = coverOpen == trueVal,
                errorCode = if (paper == paperEmptyVal) ErrorCode.PAPER_EMPTY else if (coverOpen == trueVal) ErrorCode.COVER_OPEN else null,
            )
        } catch (e: Throwable) {
            PrinterStatus(profile.id, "error", online = false, paper = "unknown", rawStatus = e.message)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Cible ePOS2 selon le transport ("TCP:ip" / "BT:mac" / "USB:..."). */
    private fun targetFor(profile: PrinterProfile): String {
        if (profile.address.contains(":") &&
            (profile.address.startsWith("TCP:") || profile.address.startsWith("BT:") || profile.address.startsWith("USB:"))
        ) {
            return profile.address
        }
        return when (profile.transport) {
            Transport.WIFI, Transport.ETHERNET -> "TCP:${profile.address.substringBefore(":")}"
            Transport.BLUETOOTH -> "BT:${profile.address}"
            Transport.USB -> "USB:${profile.address}"
            Transport.BLE -> "BT:${profile.address}"
        }
    }

    /** Constante de série modèle (ex "TM_M30") lue par réflexion, fallback TM_m30/TM_T88. */
    private fun seriesConstFor(model: String?): Int {
        val candidates = buildList {
            model?.uppercase()?.replace(" ", "")?.replace("-", "")?.let { m ->
                Regex("TM[_]?([A-Z0-9]+)").find(m)?.let { add("TM_${it.groupValues[1]}") }
            }
            add("TM_M30")
            add("TM_T88VI")
            add("TM_T20")
        }
        for (name in candidates) {
            val v = SdkReflect.staticInt(PRINTER, name, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) return v
        }
        return 0
    }

    private fun halftoneFor(dithering: String): Int = when (dithering) {
        "none" -> SdkReflect.staticInt(PRINTER, "HALFTONE_THRESHOLD", 1)
        "atkinson", "floyd_steinberg" -> SdkReflect.staticInt(PRINTER, "HALFTONE_ERROR_DIFFUSION", 2)
        else -> SdkReflect.staticInt(PRINTER, "HALFTONE_DITHER", 0)
    }

    private fun ensureSdk() {
        if (!isAvailable()) throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "SDK Epson ePOS2 absent")
    }

    companion object {
        private const val PRINTER = "com.epson.epos2.printer.Printer"
        private const val FILTER_OPTION = "com.epson.epos2.discovery.FilterOption"
        private const val DISCOVERY = "com.epson.epos2.discovery.Discovery"
        private const val DISCOVERY_LISTENER = "com.epson.epos2.discovery.DiscoveryListener"

        fun classExists(name: String): Boolean = try {
            Class.forName(name); true
        } catch (e: Throwable) {
            false
        }
    }
}
