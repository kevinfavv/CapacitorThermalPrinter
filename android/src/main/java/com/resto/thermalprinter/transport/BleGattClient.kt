package com.resto.thermalprinter.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.resto.thermalprinter.model.ErrorCode
import com.resto.thermalprinter.model.PrinterException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Client GATT BLE suspend pour imprimantes ESC/POS exposant une "UART série" BLE.
 *
 * Flux : connectGatt -> (MTU) -> discoverServices -> localiser la characteristic
 * d'écriture (allowlist d'UUID connus, sinon 1re characteristic WRITE/WRITE_NO_RESPONSE)
 * -> écriture du raster ESC/POS par paquets <= (MTU-3).
 *
 * ⚠️ Il n'existe pas de profil BLE standard d'impression : on s'appuie sur une
 * allowlist de services/characteristics validés (cf. [WRITE_TARGETS]). Pour un
 * modèle non listé, on tente la 1re characteristic inscriptible trouvée.
 */
@SuppressLint("MissingPermission")
class BleGattClient(
    private val context: Context,
    private val device: BluetoothDevice,
) {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var mtu: Int = 23
    @Volatile var isOpen: Boolean = false
        private set

    // Continuations one-shot par opération (le GATT sérialise les callbacks).
    private val connectCont = AtomicReference<Continuation<Unit>?>()
    private val mtuCont = AtomicReference<Continuation<Unit>?>()
    private val servicesCont = AtomicReference<Continuation<Unit>?>()
    private val writeCont = AtomicReference<Continuation<Unit>?>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectCont.getAndSet(null)?.resume(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isOpen = false
                val e = PrinterException(ErrorCode.CONNECTION_FAILED, "BLE déconnecté (status=$status)", retryable = true)
                connectCont.getAndSet(null)?.resumeWithException(e)
                writeCont.getAndSet(null)?.resumeWithException(e)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else mtu
            mtuCont.getAndSet(null)?.resume(Unit)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesCont.getAndSet(null)?.let { cont ->
                if (status == BluetoothGatt.GATT_SUCCESS) cont.resume(Unit)
                else cont.resumeWithException(PrinterException(ErrorCode.CONNECTION_FAILED, "discoverServices BLE échoué"))
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeCont.getAndSet(null)?.let { cont ->
                if (status == BluetoothGatt.GATT_SUCCESS) cont.resume(Unit)
                else cont.resumeWithException(PrinterException(ErrorCode.PRINT_FAILED, "Écriture BLE échouée (status=$status)", retryable = true))
            }
        }
    }

    suspend fun open(timeoutMs: Long) {
        withTimeout(timeoutMs.coerceAtLeast(3000)) {
            suspendCancellableCoroutine<Unit> { cont ->
                connectCont.set(cont)
                cont.invokeOnCancellation { runCatching { gatt?.close() } }
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, callback)
                }
            }
            // MTU max (512) pour réduire le nombre de paquets ; best effort.
            runCatching {
                suspendCancellableCoroutine<Unit> { cont ->
                    mtuCont.set(cont)
                    if (gatt?.requestMtu(512) != true) mtuCont.getAndSet(null)?.resume(Unit)
                }
            }
            suspendCancellableCoroutine<Unit> { cont ->
                servicesCont.set(cont)
                if (gatt?.discoverServices() != true) {
                    servicesCont.getAndSet(null)?.resumeWithException(
                        PrinterException(ErrorCode.CONNECTION_FAILED, "discoverServices BLE non démarré"),
                    )
                }
            }
            writeChar = locateWriteCharacteristic()
                ?: throw PrinterException(ErrorCode.UNSUPPORTED_PRINTER, "Aucune characteristic BLE inscriptible (allowlist UUID requise)")
            isOpen = true
        }
    }

    /** Écrit [data] par paquets <= (MTU-3), en attendant l'ACK entre chaque paquet. */
    suspend fun write(data: ByteArray) {
        val g = gatt ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "GATT non ouvert")
        val ch = writeChar ?: throw PrinterException(ErrorCode.CONNECTION_FAILED, "Characteristic d'écriture absente")
        val packet = (mtu - 3).coerceIn(20, 512)
        val noResponse = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
        val writeType = if (noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        var offset = 0
        while (offset < data.size) {
            val len = minOf(packet, data.size - offset)
            val slice = data.copyOfRange(offset, offset + len)
            suspendCancellableCoroutine<Unit> { cont ->
                writeCont.set(cont)
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, slice, writeType) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        ch.writeType = writeType
                        ch.value = slice
                        g.writeCharacteristic(ch)
                    }
                }
                if (!ok) writeCont.getAndSet(null)?.resumeWithException(
                    PrinterException(ErrorCode.PRINT_FAILED, "writeCharacteristic BLE refusé", retryable = true),
                )
            }
            offset += len
        }
    }

    fun close() {
        isOpen = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
    }

    private fun locateWriteCharacteristic(): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        // 1) Allowlist : services/characteristics connus.
        for ((svc, chr) in WRITE_TARGETS) {
            g.getService(svc)?.getCharacteristic(chr)?.let { return it }
        }
        // 2) Fallback : 1re characteristic inscriptible trouvée.
        for (service in g.services) {
            for (c in service.characteristics) {
                val p = c.properties
                if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                    (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ) {
                    return c
                }
            }
        }
        return null
    }

    companion object {
        private fun u(s: String) = UUID.fromString(s)

        /**
         * Allowlist (service -> characteristic d'écriture) des "UART série" BLE
         * couramment exposées par les imprimantes thermiques. À enrichir au fil des
         * modèles validés.
         */
        val WRITE_TARGETS: List<Pair<UUID, UUID>> = listOf(
            // Nordic UART Service (NUS)
            u("6e400001-b5a3-f393-e0a9-e50e24dcca9e") to u("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
            // Microchip / ISSC Transparent UART
            u("49535343-fe7d-4ae5-8fa9-9fafd205e455") to u("49535343-8841-43f4-a8d4-ecbe34729bb3"),
            // Générique "FFE0/FFE1" (modules HM-10 et clones)
            u("0000ffe0-0000-1000-8000-00805f9b34fb") to u("0000ffe1-0000-1000-8000-00805f9b34fb"),
            // Générique "FF00/FF02"
            u("0000ff00-0000-1000-8000-00805f9b34fb") to u("0000ff02-0000-1000-8000-00805f9b34fb"),
            // Imprimantes type "18F0/2AF1"
            u("000018f0-0000-1000-8000-00805f9b34fb") to u("00002af1-0000-1000-8000-00805f9b34fb"),
        )

        /** Services BLE à mettre en avant lors du scan (filtre de découverte). */
        val ADVERTISED_SERVICES: List<UUID> = WRITE_TARGETS.map { it.first }
    }
}
