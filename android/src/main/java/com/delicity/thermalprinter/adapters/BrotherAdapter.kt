package com.delicity.thermalprinter.adapters

import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
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
 * Adapter Brother basé sur le Brother Print SDK v4 (`com.brother.sdk.lmprinter`),
 * piloté par RÉFLEXION.
 *
 * Brother est orienté étiquettes (QL / TD / RJ / PJ / PT) : les réglages d'impression
 * (`*PrintSettings`) dépendent du MODÈLE. L'adapter dérive un `PrinterModel` depuis
 * `profile.model` (ex "RJ-3150" -> PrinterModel.RJ_3150). Si le modèle est inconnu,
 * une erreur explicite invite à le préciser.
 *
 * Le SDK n'est pas redistribuable (licence Brother) : déposer
 * `BrotherPrintLibrary.aar` (portail Brother). Voir docs/SDK_INTEGRATION.md (§ Brother).
 *
 * ⚠️ API model-dépendante : à vérifier sur device avec le binaire réel.
 */
class BrotherAdapter(private val context: Context) : PrinterAdapter {

    override val id = AdapterId.BROTHER

    private val cache = ConcurrentHashMap<String, Any>() // printerId -> PrinterDriver

    private val btAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    override fun isAvailable(): Boolean = EpsonAdapter.classExists(DRIVER_GENERATOR)

    // -------------------------------------------------------------------------
    // Découverte (BRLMPrinterSearcher — réseau)
    // -------------------------------------------------------------------------

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        if (!isAvailable()) return
        // L'API de recherche Brother varie selon la version ; on tente la recherche
        // réseau si disponible, sinon on s'appuie sur le scan TCP générique.
        runCatching {
            val option = SdkReflect.newInstance(NET_SEARCH_OPTION, emptyArray(), emptyArray())
            runCatching {
                SdkReflect.call(option, "setSearchDuration", arrayOf(Int::class.javaPrimitiveType!!), arrayOf((timeoutMs / 1000).toInt()))
            }
            val callback = SdkReflect.proxy(SEARCH_RESULT_CALLBACK, mapOf(
                "onChannelInfo" to { args -> handleChannel(args.getOrNull(0), onFound); null },
                "onSearchResult" to { null },
            ))
            SdkReflect.callStatic(
                SEARCHER, "startNetworkSearch",
                arrayOf(Context::class.java, SdkReflect.classOrNull(NET_SEARCH_OPTION)!!, SdkReflect.classOrNull(SEARCH_RESULT_CALLBACK)!!),
                arrayOf(context, option, callback),
            )
        }
    }

    private fun handleChannel(channel: Any?, onFound: (DiscoveredPrinter) -> Unit) {
        if (channel == null) return
        val address = (SdkReflect.call(channel, "getChannelInfo") as? String)
            ?: (SdkReflect.field(channel, "channelInfo") as? String) ?: return
        val name = (SdkReflect.call(channel, "getExtraInfo") as? String) ?: "Brother"
        onFound(
            DiscoveredPrinter(
                id = "brother:$address",
                name = name,
                brand = "Brother",
                transport = Transport.WIFI,
                adapter = AdapterId.BROTHER,
                address = address,
                discoveredBy = mutableSetOf(AdapterId.BROTHER),
            ),
        )
    }

    override fun canHandle(profile: PrinterProfile): Boolean =
        isAvailable() && profile.adapter == AdapterId.BROTHER

    // -------------------------------------------------------------------------
    // Connexion (openChannel -> PrinterDriver)
    // -------------------------------------------------------------------------

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) {
        ensureSdk()
        if (isConnected(profile.id)) return
        val channel = buildChannel(profile)
        val result = SdkReflect.callStatic(
            DRIVER_GENERATOR, "openChannel",
            arrayOf(SdkReflect.classOrNull(CHANNEL)!!), arrayOf(channel),
        ) ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "openChannel Brother null")

        val driver = SdkReflect.call(result, "getDriver")
        if (driver == null) {
            val err = runCatching { SdkReflect.call(result, "getError")?.let { SdkReflect.call(it, "getCode") } }.getOrNull()
            throw PrinterException(ErrorCode.CONNECTION_FAILED, "Connexion Brother échouée (${err ?: "openChannel"})", retryable = true)
        }
        cache[profile.id] = driver
    }

    override fun isConnected(printerId: String): Boolean = cache.containsKey(printerId)

    override suspend fun disconnect(printerId: String) {
        cache.remove(printerId)?.let { runCatching { SdkReflect.call(it, "closeChannel") } }
    }

    // -------------------------------------------------------------------------
    // Impression image
    // -------------------------------------------------------------------------

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        val driver = cache[profile.id]
            ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "Brother non connecté: ${profile.id}")
        val settings = buildPrintSettings(profile)
        try {
            repeat(options.copies.coerceAtLeast(1)) {
                SdkReflect.call(
                    driver, "printImage",
                    arrayOf(Bitmap::class.java, SdkReflect.classOrNull(PRINT_IMAGE_SETTINGS)!!),
                    arrayOf(bitmap, settings),
                )
            }
        } catch (e: Throwable) {
            throw PrinterException(ErrorCode.PRINT_FAILED, "Impression Brother échouée", e.message, retryable = true)
        }
        return bitmap.width * bitmap.height / 8
    }

    // -------------------------------------------------------------------------
    // Statut
    // -------------------------------------------------------------------------

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus {
        val driver = cache[profile.id]
            ?: return PrinterStatus(profile.id, "disconnected", online = false, paper = "unknown")
        return try {
            val result = SdkReflect.call(driver, "getPrinterStatus")
            val online = result != null
            PrinterStatus(profile.id, if (online) "connected" else "disconnected", online = online, paper = "unknown", rawStatus = result?.toString())
        } catch (e: Throwable) {
            PrinterStatus(profile.id, "connected", online = true, paper = "unknown", rawStatus = e.message)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildChannel(profile: PrinterProfile): Any = when (profile.transport) {
        Transport.WIFI, Transport.ETHERNET -> SdkReflect.callStatic(
            CHANNEL, "newWifiChannel", arrayOf(String::class.java), arrayOf(profile.address.substringBefore(":")),
        ) ?: error("newWifiChannel null")
        Transport.BLUETOOTH -> SdkReflect.callStatic(
            CHANNEL, "newBluetoothChannel",
            arrayOf(String::class.java, android.bluetooth.BluetoothAdapter::class.java),
            arrayOf(profile.address, btAdapter),
        ) ?: error("newBluetoothChannel null")
        Transport.BLE -> SdkReflect.callStatic(
            CHANNEL, "newBluetoothLowEnergyChannel",
            arrayOf(String::class.java, Context::class.java, android.bluetooth.BluetoothAdapter::class.java),
            arrayOf(profile.address, context, btAdapter),
        ) ?: error("newBluetoothLowEnergyChannel null")
        else -> throw PrinterException(ErrorCode.UNSUPPORTED_TRANSPORT, "Transport Brother non supporté: ${profile.transport.value}")
    }

    /**
     * Construit les *PrintSettings selon la famille du modèle (QL/PJ/RJ/TD/PT).
     * `PrinterModel` est dérivé du nom (ex "RJ-3150" -> RJ_3150).
     */
    private fun buildPrintSettings(profile: PrinterProfile): Any {
        val model = profile.model
            ?: throw PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Modèle Brother requis (ex 'RJ-3150') pour les réglages d'impression")
        val enumName = model.uppercase().replace("-", "_").replace(" ", "_")
        val family = Regex("^(QL|PJ|RJ|TD|PT)").find(enumName)?.value
            ?: throw PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Famille Brother inconnue pour '$model'")
        val printerModel = SdkReflect.enumValue(PRINTER_MODEL, enumName)
            ?: throw PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "PrinterModel Brother inconnu: $enumName")
        val settingsClass = "$SETTING_PKG.${family}PrintSettings"
        return SdkReflect.newInstance(
            settingsClass, arrayOf(SdkReflect.classOrNull(PRINTER_MODEL)!!), arrayOf(printerModel),
        )
    }

    private fun ensureSdk() {
        if (!isAvailable()) throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "SDK Brother absent")
    }

    companion object {
        private const val DRIVER_GENERATOR = "com.brother.sdk.lmprinter.PrinterDriverGenerator"
        private const val CHANNEL = "com.brother.sdk.lmprinter.Channel"
        private const val SEARCHER = "com.brother.sdk.lmprinter.PrinterSearcher"
        private const val NET_SEARCH_OPTION = "com.brother.sdk.lmprinter.NetworkSearchOption"
        private const val SEARCH_RESULT_CALLBACK = "com.brother.sdk.lmprinter.PrinterSearcher\$SearchResultCallback"
        private const val SETTING_PKG = "com.brother.sdk.lmprinter.setting"
        private const val PRINT_IMAGE_SETTINGS = "com.brother.sdk.lmprinter.setting.PrintImageSettings"
        private const val PRINTER_MODEL = "com.brother.sdk.lmprinter.setting.PrinterModel"
    }
}
