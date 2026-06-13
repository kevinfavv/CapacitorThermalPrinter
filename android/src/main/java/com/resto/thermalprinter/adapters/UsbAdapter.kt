package com.resto.thermalprinter.adapters

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import com.resto.thermalprinter.image.ImageProcessor
import com.resto.thermalprinter.model.AdapterId
import com.resto.thermalprinter.model.DiscoveredPrinter
import com.resto.thermalprinter.model.ErrorCode
import com.resto.thermalprinter.model.PrinterException
import com.resto.thermalprinter.model.PrinterProfile
import com.resto.thermalprinter.model.PrinterStatus
import com.resto.thermalprinter.model.RenderOptions
import com.resto.thermalprinter.model.Transport

/**
 * Adapter USB (Android host) pour imprimantes ESC/POS branchées en USB.
 *
 * Détecte les périphériques de classe imprimante (USB class 7) et écrit le raster
 * ESC/POS sur l'endpoint bulk OUT. Nécessite l'autorisation USB runtime
 * (UsbManager.requestPermission) déclenchée par l'app/plugin.
 *
 * ⚠️ ANDROID UNIQUEMENT (iOS n'expose pas l'USB host pour ce cas).
 */
class UsbAdapter(private val context: Context) : PrinterAdapter {

    override val id = AdapterId.ESCPOS
    private val usbManager get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    override fun isAvailable(): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        if (!isAvailable()) return
        usbManager.deviceList.values.forEach { device ->
            val isPrinter = (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 7 }
            if (isPrinter) {
                onFound(
                    DiscoveredPrinter(
                        id = "usb:${device.vendorId}:${device.productId}",
                        name = device.productName ?: "USB Printer",
                        brand = device.manufacturerName,
                        transport = Transport.USB,
                        adapter = AdapterId.ESCPOS,
                        address = "${device.vendorId}:${device.productId}",
                        discoveredBy = mutableSetOf(AdapterId.ESCPOS),
                    ),
                )
            }
        }
    }

    override fun canHandle(profile: PrinterProfile): Boolean = profile.transport == Transport.USB

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) {
        ensureUsb()
        /*
         * 1. retrouver UsbDevice par vendorId:productId
         * 2. vérifier usbManager.hasPermission(device) sinon requestPermission (intent)
         * 3. openDevice -> UsbDeviceConnection ; claimInterface(printerInterface)
         * 4. repérer l'endpoint USB_DIR_OUT (bulk)
         */
    }

    override fun isConnected(printerId: String): Boolean = false
    override suspend fun disconnect(printerId: String) {}

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        ensureUsb()
        val mono = ImageProcessor.toMono(bitmap, options)
        val raster = ImageProcessor.encodeEscPosRaster(mono)
        @Suppress("UNUSED_VARIABLE")
        val job = EscPosCommands.buildJob(raster, options.align, options.feedLines, options.cut, options.openCashDrawer)
        /* connection.bulkTransfer(endpointOut, job, job.size, timeout) par chunks */
        throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "USB endpoint non finalisé")
    }

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus =
        PrinterStatus(profile.id, "disconnected", online = false, paper = "unknown")

    private fun ensureUsb() {
        if (!isAvailable()) throw PrinterException(ErrorCode.UNSUPPORTED_TRANSPORT, "USB host indisponible")
    }
}
