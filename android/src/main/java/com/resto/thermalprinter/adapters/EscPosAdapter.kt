package com.resto.thermalprinter.adapters

import android.bluetooth.BluetoothAdapter
import android.graphics.Bitmap
import com.resto.thermalprinter.image.ImageProcessor
import com.resto.thermalprinter.model.AdapterId
import com.resto.thermalprinter.model.DiscoveredPrinter
import com.resto.thermalprinter.model.ErrorCode
import com.resto.thermalprinter.model.PrinterException
import com.resto.thermalprinter.model.PrinterProfile
import com.resto.thermalprinter.model.PrinterStatus
import com.resto.thermalprinter.model.RenderOptions
import com.resto.thermalprinter.model.Transport
import com.resto.thermalprinter.transport.BluetoothSppTransport
import com.resto.thermalprinter.transport.ByteTransport
import com.resto.thermalprinter.transport.TcpTransport
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter ESC/POS générique.
 *
 * Couvre la grande majorité des imprimantes thermiques bas/moyen de gamme via :
 *   - TCP 9100 (Wi-Fi / Ethernet)
 *   - Bluetooth Classic SPP (Android)
 *   - (BLE délégué à BleAdapter)
 *
 * La découverte propre à ESC/POS n'existe pas en tant que telle : ce sont les
 * sources génériques (TcpScanner, BluetoothScanner) qui produisent des
 * DiscoveredPrinter avec adapter=ESCPOS. Cet adapter se concentre sur la
 * connexion + l'impression raster.
 */
class EscPosAdapter(
    private val btAdapter: BluetoothAdapter?,
) : PrinterAdapter {

    override val id = AdapterId.ESCPOS

    /** Connexions vivantes indexées par printerId. */
    private val connections = ConcurrentHashMap<String, ByteTransport>()

    override fun isAvailable(): Boolean = true // pas de SDK requis

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        // Délégué aux scanners génériques (voir DiscoveryManager). No-op ici.
    }

    override fun canHandle(profile: PrinterProfile): Boolean =
        profile.adapter == AdapterId.ESCPOS &&
            profile.transport in setOf(Transport.WIFI, Transport.ETHERNET, Transport.BLUETOOTH)

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) {
        if (isConnected(profile.id)) return
        val transport = buildTransport(profile)
        transport.open(timeoutMs)
        connections[profile.id] = transport
    }

    override fun isConnected(printerId: String): Boolean = connections[printerId]?.isOpen == true

    override suspend fun disconnect(printerId: String) {
        connections.remove(printerId)?.close()
    }

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        val transport = connections[profile.id]
            ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "ESC/POS non connecté: ${profile.id}")

        // 1-bit + dithering -> raster GS v 0
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
        repeat(options.copies.coerceAtLeast(1)) {
            transport.write(job)
            sent += job.size
        }
        return sent
    }

    override suspend fun printItems(
        profile: PrinterProfile,
        items: List<com.resto.thermalprinter.model.PrintItem>,
        defaultCodePage: String,
        cut: Boolean,
        feedLines: Int,
    ): Int {
        val transport = connections[profile.id]
            ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "ESC/POS non connecté: ${profile.id}")
        // Colonnes selon largeur (police A ~ 12 dots/char): 384->32, 576->48.
        val columns = if (profile.capabilities.printableDots <= 420) 32 else 48
        val encoded = EscPosTextEncoder.encode(items, defaultCodePage, columns)
        val out = java.io.ByteArrayOutputStream()
        out.write(encoded.bytes)
        if (feedLines > 0) out.write(EscPosCommands.feed(feedLines))
        if (cut && profile.capabilities.supportsCut) out.write(EscPosCommands.CUT_PARTIAL)
        val job = out.toByteArray()
        transport.write(job)
        return job.size
    }

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus {
        val transport = connections[profile.id]
        if (transport == null || !transport.isOpen) {
            return PrinterStatus(
                id = profile.id, connection = "disconnected", online = false, paper = "unknown",
            )
        }
        // DLE EOT 4 -> statut papier (si l'imprimante le supporte sur ce transport).
        return try {
            transport.write(EscPosCommands.realtimeStatus(4))
            val buf = ByteArray(8)
            val n = transport.read(buf, 1500)
            if (n <= 0) {
                PrinterStatus(profile.id, "connected", online = true, paper = "unknown", rawStatus = "no-response")
            } else {
                val b = buf[0].toInt()
                // bit 5/6 (0x60) indiquent fin papier sur la plupart des modèles ESC/POS
                val paperEmpty = (b and 0x60) != 0
                PrinterStatus(
                    id = profile.id, connection = "connected", online = true,
                    paper = if (paperEmpty) "empty" else "ok",
                    errorCode = if (paperEmpty) ErrorCode.PAPER_EMPTY else null,
                    rawStatus = "0x%02X".format(b),
                )
            }
        } catch (e: Exception) {
            PrinterStatus(profile.id, "error", online = false, paper = "unknown", rawStatus = e.message)
        }
    }

    private fun buildTransport(profile: PrinterProfile): ByteTransport = when (profile.transport) {
        Transport.WIFI, Transport.ETHERNET -> {
            val (host, port) = splitHostPort(profile.address, 9100)
            TcpTransport(host, port)
        }
        Transport.BLUETOOTH -> BluetoothSppTransport(btAdapter, profile.address)
        else -> throw PrinterException(
            ErrorCode.UNSUPPORTED_TRANSPORT,
            "ESC/POS ne gère pas le transport ${profile.transport.value}",
        )
    }

    private fun splitHostPort(addr: String, defaultPort: Int): Pair<String, Int> {
        val idx = addr.lastIndexOf(':')
        return if (idx > 0 && addr.indexOf(':') == idx) {
            addr.substring(0, idx) to (addr.substring(idx + 1).toIntOrNull() ?: defaultPort)
        } else {
            addr to defaultPort
        }
    }
}
