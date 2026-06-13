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
 * Adapter Zebra basé sur le SDK Link-OS (com.zebra.sdk).
 *
 * ⚠️ IMPORTANT : Zebra N'EST PAS de l'ESC/POS. Le langage est ZPL (étiquettes) ou
 * CPCL (mobiles). On ne doit JAMAIS router une Zebra vers EscPosAdapter
 * (voir priority.ts : score escpos = -1000 pour une Zebra).
 *
 * Impression image : le SDK convertit un Bitmap en ZPL via ZebraImageAndroid +
 * printImage(...) ou en envoyant une commande ^GF. CPCL pour les mobiles type QLn.
 *
 * INTÉGRATION : ajouter ZSDK_ANDROID_API.aar puis activer le pseudo-code.
 */
class ZebraAdapter(private val context: Context) : PrinterAdapter {

    override val id = AdapterId.ZEBRA

    override fun isAvailable(): Boolean =
        EpsonAdapter.classExists("com.zebra.sdk.comm.Connection")

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        if (!isAvailable()) return
        /*
         * // Réseau
         * NetworkDiscoverer.findPrinters(object : DiscoveryHandler {
         *     override fun foundPrinter(printer: DiscoveredPrinter) { onFound(map(printer)) }
         *     override fun discoveryFinished() {}
         *     override fun discoveryError(message: String) {}
         * })
         * // Bluetooth
         * BluetoothDiscoverer.findPrinters(context, handler)
         */
    }

    override fun canHandle(profile: PrinterProfile): Boolean =
        isAvailable() && profile.adapter == AdapterId.ZEBRA

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) { ensureSdk() }
    override fun isConnected(printerId: String): Boolean = false
    override suspend fun disconnect(printerId: String) {}

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        ensureSdk()
        /*
         * val connection = when (profile.transport) {
         *     Transport.WIFI -> TcpConnection(host, port)
         *     Transport.BLUETOOTH -> BluetoothConnection(profile.address)
         *     else -> error("transport non supporté Zebra")
         * }
         * connection.open()
         * val printer = ZebraPrinterFactory.getInstance(connection)  // détecte ZPL/CPCL
         * val zebraImg = ZebraImageFactory.getImage(bitmap)
         * printer.printImage(zebraImg, 0, 0, bitmap.width, bitmap.height, false)
         * connection.close()
         */
        throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "Zebra Link-OS non intégré")
    }

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus {
        ensureSdk()
        /*
         * val status = printer.currentStatus
         * return PrinterStatus(
         *   id = profile.id, connection = "connected", online = status.isReadyToPrint,
         *   paper = if (status.isPaperOut) "empty" else "ok",
         *   coverOpen = status.isHeadOpen,
         * )
         */
        throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "Zebra Link-OS non intégré")
    }

    private fun ensureSdk() {
        if (!isAvailable()) throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "SDK Zebra absent")
    }
}
