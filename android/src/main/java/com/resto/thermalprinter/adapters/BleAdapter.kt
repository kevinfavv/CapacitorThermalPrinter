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
 * Adapter BLE (Bluetooth Low Energy) générique pour imprimantes ESC/POS exposant
 * un service GATT d'écriture série.
 *
 * ⚠️ LIMITES : il n'existe PAS de profil BLE standard pour l'impression. Chaque
 * fabricant définit son propre service/characteristic. On gère donc une liste de
 * services connus (configurable), et on écrit le raster ESC/POS par paquets <= MTU
 * via WRITE_NO_RESPONSE.
 *
 * Recommandation : n'activer BLE que pour des modèles validés (allowlist d'UUID).
 * Pour le BT classique ESC/POS générique, préférer SPP (EscPosAdapter) sur Android.
 *
 * Le scan BLE concret est fait par BleScanner (discovery), cet adapter gère
 * connexion GATT + écriture. Implémentation GATT à finaliser selon modèles ciblés.
 */
class BleAdapter(private val context: Context) : PrinterAdapter {

    override val id = AdapterId.ESCPOS // BLE transporte de l'ESC/POS dans la majorité des cas

    override fun isAvailable(): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)

    override suspend fun discover(timeoutMs: Long, onFound: (DiscoveredPrinter) -> Unit) {
        // Délégué à BleScanner (DiscoveryManager).
    }

    override fun canHandle(profile: PrinterProfile): Boolean =
        profile.transport == com.resto.thermalprinter.model.Transport.BLE

    override suspend fun connect(profile: PrinterProfile, timeoutMs: Long) {
        ensureBle()
        /*
         * 1. BluetoothAdapter.getRemoteDevice(profile.address)
         * 2. device.connectGatt(context, false, gattCallback, TRANSPORT_LE)
         * 3. discoverServices(), localiser la characteristic d'écriture (allowlist)
         * 4. négocier le MTU (requestMtu(512))
         * 5. stocker la référence GATT
         */
        throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "BLE GATT non finalisé (allowlist UUID requise)")
    }

    override fun isConnected(printerId: String): Boolean = false
    override suspend fun disconnect(printerId: String) {}

    override suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int {
        ensureBle()
        /*
         * val mono = ImageProcessor.toMono(bitmap, options)
         * val job = EscPosCommands.buildJob(ImageProcessor.encodeEscPosRaster(mono), ...)
         * // écrire job par paquets de (mtu-3) octets via WRITE_NO_RESPONSE,
         * // en attendant le callback onCharacteristicWrite entre paquets.
         */
        throw PrinterException(ErrorCode.SDK_NOT_AVAILABLE, "BLE GATT non finalisé")
    }

    override suspend fun getStatus(profile: PrinterProfile): PrinterStatus =
        PrinterStatus(profile.id, "disconnected", online = false, paper = "unknown")

    private fun ensureBle() {
        if (!isAvailable()) throw PrinterException(ErrorCode.UNSUPPORTED_TRANSPORT, "BLE indisponible sur cet appareil")
    }
}
