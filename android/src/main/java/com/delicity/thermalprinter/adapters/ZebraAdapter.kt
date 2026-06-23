package com.delicity.thermalprinter.adapters

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.delay
import com.delicity.thermalprinter.model.AdapterId
import com.delicity.thermalprinter.model.DiscoveredPrinter
import com.delicity.thermalprinter.model.ErrorCode
import com.delicity.thermalprinter.model.PrinterException
import com.delicity.thermalprinter.model.PrinterProfile
import com.delicity.thermalprinter.model.PrinterStatus
import com.delicity.thermalprinter.model.RenderOptions
import com.delicity.thermalprinter.model.Transport
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter Zebra basé sur le SDK Link-OS (`com.zebra.sdk`), piloté par RÉFLEXION.
 *
 * ⚠️ Zebra N'EST PAS de l'ESC/POS : le langage est ZPL/CPCL. Le SDK convertit le
 * Bitmap en ZPL et l'imprime via GraphicsUtil.printImage(...). On ne route JAMAIS
 * une Zebra vers EscPosAdapter (priority.ts attribue un score négatif).
 *
 * Le SDK Link-OS n'est pas redistribuable (licence Zebra) : déposer
 * `ZSDK_ANDROID_API.jar` (portail Zebra) — ou activer le dépôt Maven privé Zebra
 * (credentials). Voir docs/SDK_INTEGRATION.md (§ Zebra).
 */
class ZebraAdapter(private val context: Context) : PrinterAdapter {

    override val id = AdapterId.ZEBRA

    private val cache = ConcurrentHashMap<String, Any>() // printerId -> com.zebra.sdk.comm.Connection

    override fun isAvailable(): Boolean = EpsonAdapter.classExists(CONNECTION)

    // -------------------------------------------------------------------------
    // Découverte (NetworkDiscoverer / BluetoothDiscoverer + DiscoveryHandler proxy)
    // -------------------------------------------------------------------------

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        if (!isAvailable()) return
        val handler = SdkReflect.proxy(DISCOVERY_HANDLER, mapOf(
            "foundPrinter" to { args ->
                val dp = args.getOrNull(0)
                if (dp != null) {
                    // DiscoveredPrinter expose l'adresse en CHAMP public `address` (pas de
                    // getter getAddress()). callOrNull tente quand même un getter (autres
                    // versions de SDK), sinon on lit le champ — sans lever.
                    val address = SdkReflect.callOrNull(dp, "getAddress") as? String
                        ?: SdkReflect.field(dp, "address") as? String ?: ""
                    if (address.isNotEmpty()) {
                        onFound(
                            DiscoveredPrinter(
                                id = "zebra:$address",
                                name = "Zebra $address",
                                brand = "Zebra",
                                transport = if (address.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) Transport.BLUETOOTH else Transport.WIFI,
                                adapter = AdapterId.ZEBRA,
                                address = address,
                                discoveredBy = mutableSetOf(AdapterId.ZEBRA),
                            ),
                        )
                    }
                }
                null
            },
            "discoveryFinished" to { null },
            "discoveryError" to { null },
        ))
        // NetworkDiscoverer.findPrinters(DiscoveryHandler) — bloquant jusqu'à fin.
        runCatching {
            SdkReflect.callStatic(
                NETWORK_DISCOVERER, "findPrinters",
                arrayOf(SdkReflect.classOrNull(DISCOVERY_HANDLER)!!),
                arrayOf(handler),
            )
        }
        // BluetoothDiscoverer.findPrinters(Context, DiscoveryHandler)
        runCatching {
            SdkReflect.callStatic(
                BLUETOOTH_DISCOVERER, "findPrinters",
                arrayOf(Context::class.java, SdkReflect.classOrNull(DISCOVERY_HANDLER)!!),
                arrayOf(context, handler),
            )
        }
    }

    override fun canHandle(profile: PrinterProfile): Boolean =
        isAvailable() && profile.adapter == AdapterId.ZEBRA

    // -------------------------------------------------------------------------
    // Connexion
    // -------------------------------------------------------------------------

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) {
        ensureSdk()
        if (isConnected(profile.id)) return
        val connection = buildConnection(profile)
        try {
            SdkReflect.call(connection, "open")
        } catch (e: Throwable) {
            throw PrinterException(ErrorCode.CONNECTION_FAILED, "Connexion Zebra échouée: ${profile.address}", e.message, retryable = true)
        }
        // Auto-correction : certaines Zebra sont configurées en `line_print` (elles IMPRIMENT
        // littéralement les commandes reçues, ex. la sonde de détection de langage du SDK
        // `! U1 getvar "appl.name"`, au lieu de les interpréter → rien d'imprimable ne sort).
        // On force `device.languages` en ZPL dès l'ouverture, comme TOUT PREMIER octet envoyé
        // sur une connexion propre (la sonde du SDK arrive elle en milieu de flux). Best-effort,
        // persistant et non destructif (`hybrid_xml_zpl` conserve ZPL + XML).
        forceZplLanguage(connection)
        cache[profile.id] = connection
    }

    /** Sort l'imprimante du mode line_print en forçant un langage ZPL-compatible. */
    private suspend fun forceZplLanguage(connection: Any) {
        val ok = runCatching {
            val cmd = "! U1 setvar \"device.languages\" \"hybrid_xml_zpl\"\r\n".toByteArray(Charsets.US_ASCII)
            SdkReflect.call(connection, "write", arrayOf(ByteArray::class.java), arrayOf<Any?>(cmd))
        }.isSuccess
        // Le changement de langage n'est pas instantané : laisser l'imprimante l'appliquer
        // avant le premier `getInstance`/impression.
        if (ok) delay(LANGUAGE_SWITCH_DELAY_MS)
    }

    /**
     * Récupère le `ZebraPrinter` en FORÇANT le langage ZPL : évite l'overload auto-détection
     * `getInstance(Connection)` qui envoie la sonde `! U1 getvar "appl.name"` (imprimée
     * littéralement si l'imprimante n'interprète pas le SGD). Fallback auto-détection si
     * l'enum `PrinterLanguage` est introuvable (vieille version de SDK).
     */
    private fun zebraPrinter(connection: Any): Any {
        val zpl = SdkReflect.enumValue(PRINTER_LANGUAGE, "ZPL")
        val langClass = SdkReflect.classOrNull(PRINTER_LANGUAGE)
        val connClass = SdkReflect.classOrNull(CONNECTION)!!
        if (zpl != null && langClass != null) {
            SdkReflect.callStatic(
                PRINTER_FACTORY, "getInstance",
                arrayOf(langClass, connClass), arrayOf(zpl, connection),
            )?.let { return it }
        }
        return SdkReflect.callStatic(
            PRINTER_FACTORY, "getInstance",
            arrayOf(connClass), arrayOf(connection),
        ) ?: error("ZebraPrinterFactory.getInstance null")
    }

    override fun isConnected(printerId: String): Boolean {
        val c = cache[printerId] ?: return false
        return (SdkReflect.call(c, "isConnected") as? Boolean) ?: true
    }

    override suspend fun disconnect(printerId: String) {
        cache.remove(printerId)?.let { runCatching { SdkReflect.call(it, "close") } }
    }

    // -------------------------------------------------------------------------
    // Impression image (-> ZPL via GraphicsUtil)
    // -------------------------------------------------------------------------

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        val connection = cache[profile.id]
            ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "Zebra non connecté: ${profile.id}")
        try {
            val printer = zebraPrinter(connection)
            val graphics = SdkReflect.call(printer, "getGraphicsUtil") ?: error("getGraphicsUtil null")
            val zebraImage = SdkReflect.callStatic(
                IMAGE_FACTORY, "getImage",
                arrayOf(Bitmap::class.java), arrayOf(bitmap),
            ) ?: error("ZebraImageFactory.getImage null")
            val intT = Int::class.javaPrimitiveType!!
            val boolT = Boolean::class.javaPrimitiveType!!
            repeat(options.copies.coerceAtLeast(1)) {
                SdkReflect.call(
                    graphics, "printImage",
                    arrayOf(SdkReflect.classOrNull(IMAGE_I)!!, intT, intT, intT, intT, boolT),
                    arrayOf(zebraImage, 0, 0, bitmap.width, bitmap.height, false),
                )
            }
        } catch (e: Throwable) {
            throw PrinterException(ErrorCode.PRINT_FAILED, "Impression Zebra échouée", e.message, retryable = true)
        }
        return bitmap.width * bitmap.height / 8
    }

    // -------------------------------------------------------------------------
    // Statut
    // -------------------------------------------------------------------------

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus {
        val connection = cache[profile.id]
            ?: return PrinterStatus(profile.id, "disconnected", online = false, paper = "unknown")
        return try {
            val printer = zebraPrinter(connection)
            val status = SdkReflect.call(printer, "getCurrentStatus") ?: error("getCurrentStatus null")
            val ready = (SdkReflect.field(status, "isReadyToPrint") as? Boolean) ?: false
            val paperOut = (SdkReflect.field(status, "isPaperOut") as? Boolean) ?: false
            val headOpen = (SdkReflect.field(status, "isHeadOpen") as? Boolean) ?: false
            PrinterStatus(
                id = profile.id,
                connection = "connected",
                online = ready,
                paper = if (paperOut) "empty" else "ok",
                coverOpen = headOpen,
                errorCode = if (paperOut) ErrorCode.PAPER_EMPTY else if (headOpen) ErrorCode.COVER_OPEN else null,
            )
        } catch (e: Throwable) {
            PrinterStatus(profile.id, "error", online = false, paper = "unknown", rawStatus = e.message)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildConnection(profile: PrinterProfile): Any = when (profile.transport) {
        Transport.WIFI, Transport.ETHERNET -> {
            val host = profile.address.substringBefore(":")
            val port = profile.address.substringAfter(":", "9100").toIntOrNull() ?: 9100
            SdkReflect.newInstance(
                TCP_CONNECTION,
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!),
                arrayOf(host, port),
            )
        }
        Transport.BLUETOOTH -> SdkReflect.newInstance(
            BT_CONNECTION, arrayOf(String::class.java), arrayOf(profile.address),
        )
        else -> throw PrinterException(ErrorCode.UNSUPPORTED_TRANSPORT, "Transport Zebra non supporté: ${profile.transport.value}")
    }

    private fun ensureSdk() {
        if (!isAvailable()) throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "SDK Zebra Link-OS absent")
    }

    companion object {
        private const val CONNECTION = "com.zebra.sdk.comm.Connection"
        private const val TCP_CONNECTION = "com.zebra.sdk.comm.TcpConnection"
        private const val BT_CONNECTION = "com.zebra.sdk.comm.BluetoothConnection"
        private const val PRINTER_FACTORY = "com.zebra.sdk.printer.ZebraPrinterFactory"
        private const val PRINTER_LANGUAGE = "com.zebra.sdk.printer.PrinterLanguage"
        private const val LANGUAGE_SWITCH_DELAY_MS = 400L
        private const val IMAGE_FACTORY = "com.zebra.sdk.graphics.ZebraImageFactory"
        private const val IMAGE_I = "com.zebra.sdk.graphics.ZebraImageI"
        private const val NETWORK_DISCOVERER = "com.zebra.sdk.printer.discovery.NetworkDiscoverer"
        private const val BLUETOOTH_DISCOVERER = "com.zebra.sdk.printer.discovery.BluetoothDiscoverer"
        private const val DISCOVERY_HANDLER = "com.zebra.sdk.printer.discovery.DiscoveryHandler"
    }
}
