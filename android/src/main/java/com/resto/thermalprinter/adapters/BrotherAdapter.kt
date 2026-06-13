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

/**
 * Adapter Brother basé sur le Brother Print SDK (com.brother.sdk / BRLMPrinterDriver).
 *
 * Spécificités Brother :
 *   - SDK orienté étiquettes (QL / TD / RJ / PJ) autant que tickets,
 *   - impression image native (PrinterDriverGenerator + printImage),
 *   - découverte BRLMPrinterSearcher (réseau + Bluetooth + BLE).
 *
 * INTÉGRATION : ajouter le SDK Brother (.aar) puis activer le pseudo-code.
 */
class BrotherAdapter(private val context: Context) : PrinterAdapter {

    override val id = AdapterId.BROTHER

    override fun isAvailable(): Boolean =
        EpsonAdapter.classExists("com.brother.sdk.lmprinter.PrinterDriverGenerator")

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        if (!isAvailable()) return
        /*
         * val option = BRLMNetworkSearchOption(...)
         * BRLMPrinterSearcher.startNetworkSearch(context, timeoutMs/1000.0) { channel ->
         *     onFound(DiscoveredPrinter(... adapter = AdapterId.BROTHER ...))
         * }
         * // + startBluetoothSearch / startBLESearch
         */
    }

    override fun canHandle(profile: PrinterProfile): Boolean =
        isAvailable() && profile.adapter == AdapterId.BROTHER

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) { ensureSdk() }
    override fun isConnected(printerId: String): Boolean = false
    override suspend fun disconnect(printerId: String) {}

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        ensureSdk()
        /*
         * val channel = Channel.newWifiChannel(host) // ou bluetooth/ble
         * val result = PrinterDriverGenerator.openChannel(channel)
         * val driver = result.driver
         * val settings = QLPrintSettings(modelFrom(profile))   // ou TD/RJ selon famille
         * driver.printImage(bitmap, settings)
         * driver.closeChannel()
         */
        throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "Brother SDK non intégré")
    }

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus {
        ensureSdk()
        throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "Brother SDK non intégré")
    }

    private fun ensureSdk() {
        if (!isAvailable()) throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "SDK Brother absent")
    }
}
