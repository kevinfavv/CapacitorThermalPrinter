package com.delicity.thermalprinter.adapters

import android.bluetooth.BluetoothManager
import android.content.Context
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
import com.delicity.thermalprinter.transport.BleGattClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter BLE (Bluetooth Low Energy) générique pour imprimantes ESC/POS exposant
 * un service GATT d'écriture "série".
 *
 * La connexion GATT + l'écriture par paquets sont déléguées à [BleGattClient]
 * (négociation MTU, allowlist d'UUID, fallback characteristic inscriptible). Le
 * scan BLE concret est fait par BleScanner (DiscoveryManager).
 *
 * Recommandation : valider chaque modèle (allowlist d'UUID dans BleGattClient).
 * Pour le BT classique ESC/POS générique sur Android, préférer SPP (EscPosAdapter).
 */
class BleAdapter(private val context: Context) : PrinterAdapter {

    override val id = AdapterId.ESCPOS // BLE transporte de l'ESC/POS dans la majorité des cas

    private val connections = ConcurrentHashMap<String, BleGattClient>()

    private val btAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    override fun isAvailable(): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)

    override fun supportsTextItems(): Boolean = true

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        // Délégué à BleScanner (DiscoveryManager).
    }

    override fun canHandle(profile: PrinterProfile): Boolean = profile.transport == Transport.BLE

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) {
        ensureBle()
        if (isConnected(profile.id)) return
        val adapter = btAdapter ?: throw PrinterException(ErrorCode.BLUETOOTH_DISABLED, "Bluetooth indisponible")
        val device = try {
            adapter.getRemoteDevice(profile.address)
        } catch (e: IllegalArgumentException) {
            throw PrinterException(ErrorCode.PRINTER_NOT_FOUND, "Adresse BLE invalide: ${profile.address}")
        }
        val client = BleGattClient(context, device)
        client.open(timeoutMs)
        connections[profile.id] = client
    }

    override fun isConnected(printerId: String): Boolean = connections[printerId]?.isOpen == true

    override suspend fun disconnect(printerId: String) {
        connections.remove(printerId)?.close()
    }

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        val client = requireClient(profile)
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
            client.write(job)
            sent += job.size
        }
        return sent
    }

    override suspend fun printItems(
        profile: PrinterProfile,
        items: List<com.delicity.thermalprinter.model.PrintItem>,
        defaultCodePage: String,
        cut: Boolean,
        feedLines: Int,
    ): Int {
        val client = requireClient(profile)
        val columns = if (profile.capabilities.printableDots <= 420) 32 else 48
        val encoded = EscPosTextEncoder.encode(items, defaultCodePage, columns)
        val out = java.io.ByteArrayOutputStream()
        out.write(encoded.bytes)
        if (feedLines > 0) out.write(EscPosCommands.feed(feedLines))
        if (cut && profile.capabilities.supportsCut) out.write(EscPosCommands.CUT_PARTIAL)
        val job = out.toByteArray()
        client.write(job)
        return job.size
    }

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus {
        val open = isConnected(profile.id)
        return PrinterStatus(
            id = profile.id,
            connection = if (open) "connected" else "disconnected",
            online = open,
            paper = "unknown",
            rawStatus = "BLE: statut temps réel non lu (notify spécifique au modèle)",
        )
    }

    private fun requireClient(profile: PrinterProfile): BleGattClient =
        connections[profile.id] ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "BLE non connecté: ${profile.id}")

    private fun ensureBle() {
        if (!isAvailable()) throw PrinterException(ErrorCode.UNSUPPORTED_TRANSPORT, "BLE indisponible sur cet appareil")
    }
}
