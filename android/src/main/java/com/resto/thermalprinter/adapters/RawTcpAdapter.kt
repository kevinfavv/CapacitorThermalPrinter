package com.resto.thermalprinter.adapters

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
import com.resto.thermalprinter.transport.TcpTransport
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
