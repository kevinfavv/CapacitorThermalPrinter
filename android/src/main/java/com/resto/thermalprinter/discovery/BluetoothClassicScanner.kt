package com.resto.thermalprinter.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.resto.thermalprinter.model.AdapterId
import com.resto.thermalprinter.model.Capabilities
import com.resto.thermalprinter.model.DiscoveredPrinter
import com.resto.thermalprinter.model.Transport
import kotlinx.coroutines.delay

/**
 * Scanner Bluetooth Classic (Android).
 *
 * Deux sources :
 *   1. Appareils DÉJÀ APPAIRÉS (instantané, sans scan) -> idéal en restauration
 *      car l'imprimante est souvent appairée une fois pour toutes.
 *   2. Découverte active (startDiscovery) pour les nouveaux appareils.
 *
 * Tous les résultats sont taggés adapter=ESCPOS (BT classic = SPP générique).
 * Les imprimantes Epson/Star BT seront, elles, mieux remontées par leurs SDK
 * respectifs et prendront la priorité via le dédoublonnage.
 *
 * Permissions requises (API 31+) : BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class BluetoothClassicScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
) {

    suspend fun scan(
        timeoutMs: Long,
        includePaired: Boolean,
        onFound: (DiscoveredPrinter) -> Unit,
    ) {
        val ad = adapter ?: return
        if (!ad.isEnabled) return

        // 1) Appareils appairés
        if (includePaired) {
            ad.bondedDevices?.forEach { device ->
                if (looksLikePrinter(device)) onFound(toPrinter(device, paired = true))
            }
        }

        // 2) Découverte active
        val discovered = mutableSetOf<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null && discovered.add(device.address) && looksLikePrinter(device)) {
                        onFound(toPrinter(device, paired = false))
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        try {
            ad.startDiscovery()
            delay(timeoutMs)
        } finally {
            try { ad.cancelDiscovery() } catch (_: Exception) {}
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    /** Heuristique : classe IMAGING/printer, ou nom contenant des mots-clés imprimante. */
    private fun looksLikePrinter(device: BluetoothDevice): Boolean {
        val cls = device.bluetoothClass?.majorDeviceClass
        if (cls == BluetoothClass.Device.Major.IMAGING) return true
        val name = (device.name ?: "").lowercase()
        return PRINTER_HINTS.any { name.contains(it) }
    }

    private fun toPrinter(device: BluetoothDevice, paired: Boolean): DiscoveredPrinter =
        DiscoveredPrinter(
            id = "bluetooth:${device.address}",
            name = device.name ?: device.address,
            brand = guessBrand(device.name),
            transport = Transport.BLUETOOTH,
            adapter = AdapterId.ESCPOS,
            address = device.address,
            capabilities = Capabilities(supportsRasterImage = true, supportsStatus = false),
            discoveredBy = mutableSetOf(AdapterId.ESCPOS),
        ).also { it.isConnected = false; if (paired) it.lastSeenAt = System.currentTimeMillis() }

    private fun guessBrand(name: String?): String? {
        val n = (name ?: "").lowercase()
        return when {
            n.contains("epson") -> "Epson"
            n.contains("star") -> "Star"
            n.contains("zebra") -> "Zebra"
            n.contains("brother") -> "Brother"
            else -> null
        }
    }

    companion object {
        private val PRINTER_HINTS = listOf(
            "print", "printer", "pos", "escpos", "esc/pos", "thermal", "receipt",
            "tm-", "mpt", "rpp", "mtp", "bluetooth printer",
        )
    }
}
