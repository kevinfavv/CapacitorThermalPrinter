package com.resto.thermalprinter.transport

/**
 * Canal d'octets bas niveau vers une imprimante (TCP, SPP, BLE).
 * Les adapters ESC/POS écrivent des commandes brutes via ce contrat.
 */
interface ByteTransport {
    val isOpen: Boolean
    fun open(timeoutMs: Long)
    fun write(bytes: ByteArray)

    /**
     * Lecture optionnelle (statut temps réel ESC/POS DLE EOT).
     * Retourne le nombre d'octets lus, ou -1 si non supporté/aucune donnée.
     */
    fun read(buffer: ByteArray, timeoutMs: Long): Int = -1
    fun close()
}
