package com.delicity.thermalprinter.adapters

import android.graphics.Bitmap
import com.delicity.thermalprinter.image.ImageProcessor
import com.delicity.thermalprinter.model.AdapterId
import com.delicity.thermalprinter.model.DiscoveredPrinter
import com.delicity.thermalprinter.model.ErrorCode
import com.delicity.thermalprinter.model.PrinterException
import com.delicity.thermalprinter.model.PrinterProfile
import com.delicity.thermalprinter.model.PrinterStatus
import com.delicity.thermalprinter.model.RenderOptions
import com.delicity.thermalprinter.model.Transport
import com.delicity.thermalprinter.transport.TcpTransport
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter "filet de sécurité" réseau : envoie un raster ESC/POS sur un socket TCP
 * brut sans rien présumer du dialecte de statut. Utilisé quand une imprimante
 * réseau n'est identifiée par aucun SDK et n'a pas confirmé l'ESC/POS.
 *
 * Priorité la plus basse (voir priority.ts). Ne lit jamais de statut.
 */
class RawTcpAdapter : PrinterAdapter {

    override val id = AdapterId.RAW_TCP
    private val connections = ConcurrentHashMap<String, TcpTransport>()

    override fun isAvailable(): Boolean = true

    override fun supportsTextItems(): Boolean = true

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        // Délégué au TcpScanner.
    }

    override fun canHandle(profile: PrinterProfile): Boolean =
        profile.adapter == AdapterId.RAW_TCP &&
            profile.transport in setOf(Transport.WIFI, Transport.ETHERNET)

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) {
        if (isConnected(profile.id)) return
        val idx = profile.address.lastIndexOf(':')
        val host = if (idx > 0) profile.address.substring(0, idx) else profile.address
        val port = if (idx > 0) profile.address.substring(idx + 1).toIntOrNull() ?: 9100 else 9100
        val t = TcpTransport(host, port)
        t.open(timeoutMs)
        connections[profile.id] = t
    }

    override fun isConnected(printerId: String): Boolean = connections[printerId]?.isOpen == true

    override suspend fun disconnect(printerId: String) {
        connections.remove(printerId)?.close()
    }

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        val t = connections[profile.id]
            ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "rawTcp non connecté")
        val mono = ImageProcessor.toMono(bitmap, options)
        val raster = ImageProcessor.encodeEscPosRaster(mono)
        val job = EscPosCommands.buildJob(raster, options.align, options.feedLines, options.cut, options.openCashDrawer)
        t.write(job)
        return job.size
    }

    override suspend fun printItems(
        profile: PrinterProfile,
        items: List<com.delicity.thermalprinter.model.PrintItem>,
        defaultCodePage: String,
        cut: Boolean,
        feedLines: Int,
    ): Int {
        val t = connections[profile.id]
            ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "rawTcp non connecté")
        val columns = if (profile.capabilities.printableDots <= 420) 32 else 48
        val encoded = EscPosTextEncoder.encode(items, defaultCodePage, columns)
        val out = java.io.ByteArrayOutputStream()
        out.write(encoded.bytes)
        if (feedLines > 0) out.write(EscPosCommands.feed(feedLines))
        if (cut) out.write(EscPosCommands.CUT_PARTIAL)
        val job = out.toByteArray()
        t.write(job)
        return job.size
    }

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus {
        val connected = isConnected(profile.id)
        return PrinterStatus(
            id = profile.id,
            connection = if (connected) "connected" else "disconnected",
            online = connected,
            paper = "unknown",
            rawStatus = "rawTcp: statut non supporté",
        )
    }
}
