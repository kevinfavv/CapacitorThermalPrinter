package com.resto.thermalprinter.adapters

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.resto.thermalprinter.image.ImageProcessor
import com.resto.thermalprinter.model.AdapterId
import com.resto.thermalprinter.model.DiscoveredPrinter
import com.resto.thermalprinter.model.ErrorCode
import com.resto.thermalprinter.model.PrinterException
import com.resto.thermalprinter.model.PrinterProfile
import com.resto.thermalprinter.model.PrinterStatus
import com.resto.thermalprinter.model.RenderOptions
import com.resto.thermalprinter.model.Transport
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Adapter USB (Android host) pour imprimantes ESC/POS branchées en USB.
 *
 * Détecte les périphériques de classe imprimante (USB class 7), demande la
 * permission runtime, réclame l'interface et écrit le raster ESC/POS sur
 * l'endpoint bulk OUT via bulkTransfer (par paquets).
 *
 * ⚠️ ANDROID UNIQUEMENT (iOS n'expose pas l'USB host pour ce cas).
 */
class UsbAdapter(private val context: Context) : PrinterAdapter {

    override val id = AdapterId.ESCPOS
    private val usbManager get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Connexions ouvertes indexées par printerId ("usb:vendorId:productId"). */
    private val connections = ConcurrentHashMap<String, UsbLink>()

    private data class UsbLink(
        val connection: UsbDeviceConnection,
        val iface: UsbInterface,
        val endpointOut: UsbEndpoint,
        val endpointIn: UsbEndpoint?,
    )

    override fun isAvailable(): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)

    override fun supportsTextItems(): Boolean = true

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        if (!isAvailable()) return
        usbManager.deviceList.values.forEach { device ->
            val isPrinter = (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER }
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
        if (isConnected(profile.id)) return

        val device = findDevice(profile.address)
            ?: throw PrinterException(ErrorCode.PRINTER_NOT_FOUND, "Périphérique USB introuvable: ${profile.address}")

        if (!usbManager.hasPermission(device)) {
            val granted = requestPermission(device, timeoutMs)
            if (!granted) throw PrinterException(ErrorCode.PERMISSION_DENIED, "Permission USB refusée", retryable = true)
        }

        val iface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
            ?: throw PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Aucune interface imprimante USB")

        var endpointOut: UsbEndpoint? = null
        var endpointIn: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_OUT) endpointOut = ep
                if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep
            }
        }
        if (endpointOut == null) throw PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Endpoint USB bulk OUT introuvable")

        val connection = usbManager.openDevice(device)
            ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "Ouverture USB échouée", retryable = true)
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            throw PrinterException(ErrorCode.CONNECTION_FAILED, "claimInterface USB échoué", retryable = true)
        }
        connections[profile.id] = UsbLink(connection, iface, endpointOut, endpointIn)
    }

    override fun isConnected(printerId: String): Boolean = connections.containsKey(printerId)

    override suspend fun disconnect(printerId: String) {
        connections.remove(printerId)?.let { link ->
            runCatching { link.connection.releaseInterface(link.iface) }
            runCatching { link.connection.close() }
        }
    }

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        val link = requireLink(profile)
        val mono = ImageProcessor.toMono(bitmap, options)
        val raster = ImageProcessor.encodeEscPosRaster(mono)
        val job = EscPosCommands.buildJob(
            rasterData = raster,
            align = options.align,
            feedLines = options.feedLines,
            cut = options.cut && profile.capabilities.supportsCut,
            openDrawer = options.openCashDrawer && profile.capabilities.supportsCashDrawer,
        )
        var sent = 0
        repeat(options.copies.coerceAtLeast(1)) { sent += writeBulk(link, job) }
        return sent
    }

    override suspend fun printItems(
        profile: PrinterProfile,
        items: List<com.resto.thermalprinter.model.PrintItem>,
        defaultCodePage: String,
        cut: Boolean,
        feedLines: Int,
    ): Int {
        val link = requireLink(profile)
        val columns = if (profile.capabilities.printableDots <= 420) 32 else 48
        val encoded = EscPosTextEncoder.encode(items, defaultCodePage, columns)
        val out = java.io.ByteArrayOutputStream()
        out.write(encoded.bytes)
        if (feedLines > 0) out.write(EscPosCommands.feed(feedLines))
        if (cut && profile.capabilities.supportsCut) out.write(EscPosCommands.CUT_PARTIAL)
        return writeBulk(link, out.toByteArray())
    }

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus {
        val link = connections[profile.id]
            ?: return PrinterStatus(profile.id, "disconnected", online = false, paper = "unknown")
        // DLE EOT 4 -> statut papier si un endpoint IN est exposé (rare en USB).
        val epIn = link.endpointIn
            ?: return PrinterStatus(profile.id, "connected", online = true, paper = "unknown", rawStatus = "no-in-endpoint")
        return try {
            writeBulk(link, EscPosCommands.realtimeStatus(4))
            val buf = ByteArray(8)
            val n = link.connection.bulkTransfer(epIn, buf, buf.size, 1500)
            if (n <= 0) {
                PrinterStatus(profile.id, "connected", online = true, paper = "unknown", rawStatus = "no-response")
            } else {
                val b = buf[0].toInt()
                val paperEmpty = (b and 0x60) != 0
                PrinterStatus(
                    profile.id, "connected", online = true,
                    paper = if (paperEmpty) "empty" else "ok",
                    errorCode = if (paperEmpty) ErrorCode.PAPER_EMPTY else null,
                    rawStatus = "0x%02X".format(b),
                )
            }
        } catch (e: Exception) {
            PrinterStatus(profile.id, "error", online = false, paper = "unknown", rawStatus = e.message)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun requireLink(profile: PrinterProfile): UsbLink =
        connections[profile.id] ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "USB non connecté: ${profile.id}")

    /** Écrit un buffer sur l'endpoint bulk OUT par paquets (taille = maxPacketSize). */
    private fun writeBulk(link: UsbLink, data: ByteArray): Int {
        val ep = link.endpointOut
        val chunk = if (ep.maxPacketSize > 0) ep.maxPacketSize else 16384
        var offset = 0
        while (offset < data.size) {
            val len = minOf(chunk, data.size - offset)
            val slice = if (offset == 0 && len == data.size) data else data.copyOfRange(offset, offset + len)
            val n = link.connection.bulkTransfer(ep, slice, len, 5000)
            if (n < 0) throw PrinterException(ErrorCode.PRINT_FAILED, "Écriture USB échouée (bulkTransfer=$n)", retryable = true)
            offset += len
        }
        return data.size
    }

    private fun findDevice(address: String): UsbDevice? {
        val parts = address.split(":")
        if (parts.size < 2) return null
        val vid = parts[0].toIntOrNull() ?: return null
        val pid = parts[1].toIntOrNull() ?: return null
        return usbManager.deviceList.values.firstOrNull { it.vendorId == vid && it.productId == pid }
    }

    /** Demande la permission USB runtime et suspend jusqu'à la réponse (ou timeout). */
    private suspend fun requestPermission(device: UsbDevice, timeoutMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val action = "$ACTION_USB_PERMISSION.${device.deviceId}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != action) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            usbManager.requestPermission(device, pi)
        }

    private fun ensureUsb() {
        if (!isAvailable()) throw PrinterException(ErrorCode.UNSUPPORTED_TRANSPORT, "USB host indisponible")
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.resto.thermalprinter.USB_PERMISSION"
    }
}
