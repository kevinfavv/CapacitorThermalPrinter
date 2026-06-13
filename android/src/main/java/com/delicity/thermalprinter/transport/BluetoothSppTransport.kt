package com.delicity.thermalprinter.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.delicity.thermalprinter.model.ErrorCode
import com.delicity.thermalprinter.model.PrinterException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Transport Bluetooth Classic / SPP (Serial Port Profile).
 *
 * ⚠️ ANDROID UNIQUEMENT. iOS n'expose pas le SPP générique (voir limites README).
 *
 * Le SPP est le canal des imprimantes ESC/POS Bluetooth "génériques" très répandues
 * en restauration (modèles chinois à bas coût). UUID SPP standard :
 *   00001101-0000-1000-8000-00805F9B34FB
 *
 * Requiert BLUETOOTH_CONNECT (API 31+) accordée AVANT l'appel.
 */
@SuppressLint("MissingPermission")
class BluetoothSppTransport(
    private val adapter: BluetoothAdapter?,
    private val macAddress: String,
) : ByteTransport {

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var input: InputStream? = null

    override val isOpen: Boolean
        get() = socket?.isConnected == true

    override fun open(timeoutMs: Long) {
        if (isOpen) return
        val ad = adapter ?: throw PrinterException(ErrorCode.BLUETOOTH_DISABLED, "Bluetooth indisponible")
        if (!ad.isEnabled) throw PrinterException(ErrorCode.BLUETOOTH_DISABLED, "Bluetooth désactivé")

        val device: BluetoothDevice = try {
            ad.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            throw PrinterException(ErrorCode.PRINTER_NOT_FOUND, "MAC invalide: $macAddress", e.message)
        }

        // Si non appairé, l'app doit déclencher l'appairage en amont.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            throw PrinterException(ErrorCode.PAIRING_REQUIRED, "Appareil non appairé: $macAddress", retryable = false)
        }

        try {
            ad.cancelDiscovery() // la découverte ralentit/échoue la connexion
            val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
            sock.connect() // bloquant ; pas de vrai timeout natif -> orchestré par coroutine
            socket = sock
            out = sock.outputStream
            input = sock.inputStream
        } catch (e: SecurityException) {
            throw PrinterException(ErrorCode.PERMISSION_DENIED, "Permission BLUETOOTH_CONNECT manquante", e.message)
        } catch (e: Exception) {
            // Fallback "insecure" pour certaines imprimantes capricieuses.
            try {
                val sock = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                sock.connect()
                socket = sock
                out = sock.outputStream
                input = sock.inputStream
            } catch (e2: Exception) {
                throw PrinterException(ErrorCode.CONNECTION_FAILED, "Connexion SPP échouée $macAddress", e2.message, retryable = true)
            }
        }
    }

    override fun write(bytes: ByteArray) {
        val o = out ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "Socket SPP non ouvert")
        try {
            var offset = 0
            val chunk = 2048 // les buffers SPP sont petits
            while (offset < bytes.size) {
                val len = minOf(chunk, bytes.size - offset)
                o.write(bytes, offset, len)
                o.flush()
                offset += len
                // micro-pause anti-overflow sur imprimantes lentes
                if (bytes.size > 16_384) Thread.sleep(8)
            }
        } catch (e: Exception) {
            throw PrinterException(ErrorCode.PRINT_FAILED, "Écriture SPP échouée", e.message, retryable = true)
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Long): Int {
        val i = input ?: return -1
        return try { i.read(buffer) } catch (e: Exception) { -1 }
    }

    override fun close() {
        try { out?.flush() } catch (_: Exception) {}
        try { input?.close() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null; input = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
