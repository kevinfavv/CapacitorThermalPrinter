package com.resto.thermalprinter.adapters

import java.io.ByteArrayOutputStream

/** Constantes et helpers de commandes ESC/POS. */
object EscPosCommands {
    val INIT = byteArrayOf(0x1B, 0x40) // ESC @
    val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
    val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    val ALIGN_RIGHT = byteArrayOf(0x1B, 0x61, 0x02)
    val CUT_PARTIAL = byteArrayOf(0x1D, 0x56, 0x01) // GS V 1
    val DRAWER_PIN2 = byteArrayOf(0x1B, 0x70, 0x00, 0x19.toByte(), 0xFA.toByte())

    fun feed(lines: Int): ByteArray = byteArrayOf(0x1B, 0x64, lines.coerceIn(0, 255).toByte())

    fun alignOf(align: String): ByteArray = when (align) {
        "center" -> ALIGN_CENTER
        "right" -> ALIGN_RIGHT
        else -> ALIGN_LEFT
    }

    /** DLE EOT n : statut temps réel (1 imprimante, 2 offline, 3 erreur, 4 papier). */
    fun realtimeStatus(n: Int): ByteArray = byteArrayOf(0x10, 0x04, n.toByte())

    /** Assemble un job complet autour du raster déjà encodé. */
    fun buildJob(
        rasterData: ByteArray,
        align: String,
        feedLines: Int,
        cut: Boolean,
        openDrawer: Boolean,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(INIT)
        out.write(alignOf(align))
        out.write(rasterData)
        out.write(feed(feedLines))
        if (cut) out.write(CUT_PARTIAL)
        if (openDrawer) out.write(DRAWER_PIN2)
        return out.toByteArray()
    }
}
