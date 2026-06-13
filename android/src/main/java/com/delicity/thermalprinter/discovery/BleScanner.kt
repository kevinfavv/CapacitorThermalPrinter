package com.delicity.thermalprinter.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.delicity.thermalprinter.model.AdapterId
import com.delicity.thermalprinter.model.DiscoveredPrinter
import com.delicity.thermalprinter.model.Transport
import com.delicity.thermalprinter.transport.BleGattClient
import java.util.Collections
import kotlinx.coroutines.delay

/**
 * Scanner BLE : repère les imprimantes thermiques exposant un service "UART série"
 * connu (allowlist [BleGattClient.ADVERTISED_SERVICES]). On filtre sur ces services
 * pour éviter le bruit (la plupart des appareils BLE ne sont pas des imprimantes).
 *
 * Les résultats sont produits avec adapter=ESCPOS / transport=BLE : la connexion +
 * l'écriture sont ensuite gérées par BleAdapter / BleGattClient.
 */
@SuppressLint("MissingPermission")
class BleScanner(
    private val context: Context,
    private val btAdapter: BluetoothAdapter,
) {

    suspend fun scan(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        val scanner = btAdapter.bluetoothLeScanner ?: return
        if (!btAdapter.isEnabled) return

        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val address = device.address ?: return
                if (!seen.add(address)) return
                val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull() ?: "BLE Printer"
                onFound(
                    DiscoveredPrinter(
                        id = "ble:$address",
                        name = name,
                        transport = Transport.BLE,
                        adapter = AdapterId.ESCPOS,
                        address = address,
                        discoveredBy = mutableSetOf(AdapterId.ESCPOS),
                    ),
                )
            }
        }

        val filters = BleGattClient.ADVERTISED_SERVICES.map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, callback)
            delay(timeoutMs)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }
}
