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

        if (device.bondState != BluetoothDevice.BOND_BONDED) pair(ad, device)

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

    /**
     * Appairage à la volée : `createBond()` affiche la pop-up système (PIN / confirmation),
     * puis on attend l'issue. Une imprimante choisie dans la liste mais jamais appairée
     * se connecte ainsi du premier coup, sans passer par les Paramètres Android.
     */
    private fun pair(ad: BluetoothAdapter, device: BluetoothDevice) {
        try {
            ad.cancelDiscovery() // une découverte en cours fait échouer l'appairage
            if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
                throw PrinterException(ErrorCode.PAIRING_REQUIRED, "Appairage impossible: $macAddress", retryable = true)
            }
        } catch (e: SecurityException) {
            throw PrinterException(ErrorCode.PERMISSION_DENIED, "Permission BLUETOOTH_CONNECT manquante", e.message)
        }
        if (!awaitBonded({ device.bondState }, PAIRING_TIMEOUT_MS)) {
            throw PrinterException(ErrorCode.PAIRING_REQUIRED, "Appairage refusé ou expiré: $macAddress", retryable = true)
        }
    }

    override fun write(bytes: ByteArray) {
        val o = out ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "Socket SPP non ouvert")
        try {
            // Les imprimantes SPP bon marché n'ont PAS de contrôle de flux matériel et un
            // petit buffer d'entrée. Envoyer un gros raster (image/logo) d'un trait le fait
            // déborder -> image tronquée ou absente, alors que le texte (petit) passe.
            // On découpe en petits paquets ET on cadence dès qu'un job dépasse un paquet,
            // pour laisser l'imprimante drainer. Le BLE n'a pas ce souci (ACK par paquet).
            val chunk = 512
            val pace = bytes.size > chunk // gros job (image) -> on cadence
            var offset = 0
            while (offset < bytes.size) {
                val len = minOf(chunk, bytes.size - offset)
                o.write(bytes, offset, len)
                o.flush()
                offset += len
                if (pace) Thread.sleep(15)
            }
        } catch (e: Exception) {
            throw PrinterException(ErrorCode.PRINT_FAILED, "Écriture SPP échouée", e.message, retryable = true)
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Long): Int {
        val i = input ?: return -1
        return readWithTimeout(i, buffer, timeoutMs)
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

        /**
         * `BluetoothSocket` n'a pas de `soTimeout` : un `read()` nu bloque indéfiniment sur
         * une imprimante qui ne répond pas au statut ESC/POS (ex. Star TSP100), et gèle toute
         * la file d'impression derrière lui. On attend donc des octets disponibles.
         */
        fun readWithTimeout(input: InputStream, buffer: ByteArray, timeoutMs: Long): Int = try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (input.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            if (input.available() == 0) -1 else input.read(buffer)
        } catch (e: Exception) {
            -1
        }

        /** Laisse le temps de lire et saisir le PIN dans la pop-up d'appairage. */
        const val PAIRING_TIMEOUT_MS = 30_000L

        /**
         * Attend `BOND_BONDED`. Échec si l'état retombe à `BOND_NONE` après être passé par
         * `BOND_BONDING` (pop-up annulée, mauvais PIN) ou au bout de [timeoutMs].
         * Sondage plutôt que BroadcastReceiver : pas besoin de Context dans le transport.
         */
        fun awaitBonded(
            bondState: () -> Int,
            timeoutMs: Long,
            pollMs: Long = 200,
            sleep: (Long) -> Unit = Thread::sleep,
        ): Boolean {
            var bonding = false
            var waited = 0L
            while (waited <= timeoutMs) {
                when (bondState()) {
                    BluetoothDevice.BOND_BONDED -> return true
                    BluetoothDevice.BOND_BONDING -> bonding = true
                    BluetoothDevice.BOND_NONE -> if (bonding) return false
                }
                sleep(pollMs)
                waited += pollMs
            }
            return false
        }
    }
}
