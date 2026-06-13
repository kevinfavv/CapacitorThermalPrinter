package com.resto.thermalprinter.adapters

import com.resto.thermalprinter.model.PrintItem
import com.resto.thermalprinter.model.TextStyle
import java.io.ByteArrayOutputStream
import android.util.Base64

/**
 * Encodeur ESC/POS texte (miroir Kotlin de src/core/escpos-text.ts).
 * Transforme une liste de PrintItem en flux d'octets ESC/POS.
 *
 * Les items `image` sont signalés via [imageIndexes] et NON encodés ici :
 * ils sont rendus par le pipeline image (ImageProcessor) et insérés par le moteur.
 */
object EscPosTextEncoder {

    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val LF = 0x0A

    private val CODE_PAGE_TO_ESC_T = mapOf(
        "CP437" to 0, "CP850" to 2, "CP858" to 19, "WPC1252" to 16, "CP852" to 18, "CP866" to 17,
    )

    private val BARCODE_M = mapOf(
        "UPC_A" to 65, "UPC_E" to 66, "EAN13" to 67, "EAN8" to 68,
        "CODE39" to 69, "ITF" to 70, "CODABAR" to 71, "CODE93" to 72, "CODE128" to 73,
    )

    data class Encoded(val bytes: ByteArray, val imageIndexes: List<Int>)

    fun encodeString(value: String): ByteArray {
        val out = ByteArrayOutputStream()
        value.codePoints().forEach { cp -> out.write(if (cp <= 0xFF) cp else 0x3F) }
        return out.toByteArray()
    }

    fun sizeByte(w: Int, h: Int): Int {
        val ww = (w.coerceIn(1, 8)) - 1
        val hh = (h.coerceIn(1, 8)) - 1
        return (ww shl 4) or hh
    }

    private fun openStyle(out: ByteArrayOutputStream, s: TextStyle, defaultCodePage: String) {
        val cp = s.codePageId ?: (CODE_PAGE_TO_ESC_T[s.codePage ?: defaultCodePage] ?: 16)
        out.write(byteArrayOf(ESC.toByte(), 0x74, (cp and 0xFF).toByte()))
        val align = when (s.align) { "center" -> 1; "right" -> 2; else -> 0 }
        out.write(byteArrayOf(ESC.toByte(), 0x61, align.toByte()))
        out.write(byteArrayOf(ESC.toByte(), 0x4D, if (s.font == "B") 1 else 0))
        out.write(byteArrayOf(ESC.toByte(), 0x45, if (s.bold) 1 else 0))
        out.write(byteArrayOf(ESC.toByte(), 0x47, if (s.doubleStrike) 1 else 0))
        val ul = when (s.underline) { "single" -> 1; "double" -> 2; else -> 0 }
        out.write(byteArrayOf(ESC.toByte(), 0x2D, ul.toByte()))
        out.write(byteArrayOf(GS.toByte(), 0x42, if (s.invert) 1 else 0))
        out.write(byteArrayOf(ESC.toByte(), 0x7B, if (s.upsideDown) 1 else 0))
        out.write(byteArrayOf(ESC.toByte(), 0x56, if (s.rotate90) 1 else 0))
        out.write(byteArrayOf(GS.toByte(), 0x21, sizeByte(s.widthMultiplier, s.heightMultiplier).toByte()))
        s.letterSpacing?.let { out.write(byteArrayOf(ESC.toByte(), 0x20, (it and 0xFF).toByte())) }
        if (s.lineSpacing != null) out.write(byteArrayOf(ESC.toByte(), 0x33, (s.lineSpacing and 0xFF).toByte()))
        else out.write(byteArrayOf(ESC.toByte(), 0x32))
    }

    private fun reset(out: ByteArrayOutputStream) = out.write(byteArrayOf(ESC.toByte(), 0x40))

    private fun qrCode(out: ByteArrayOutputStream, item: PrintItem.QrCode) {
        val align = when (item.align) { "left" -> 0; "right" -> 2; else -> 1 }
        out.write(byteArrayOf(ESC.toByte(), 0x61, align.toByte()))
        out.write(byteArrayOf(GS.toByte(), 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00))
        val size = item.size.coerceIn(1, 16)
        out.write(byteArrayOf(GS.toByte(), 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, size.toByte()))
        val ec = when (item.ec) { "L" -> 48; "Q" -> 50; "H" -> 51; else -> 49 }
        out.write(byteArrayOf(GS.toByte(), 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, ec.toByte()))
        val data = encodeString(item.value)
        val len = data.size + 3
        out.write(byteArrayOf(GS.toByte(), 0x28, 0x6B, (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(), 0x31, 0x50, 0x30))
        out.write(data)
        out.write(byteArrayOf(GS.toByte(), 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30))
    }

    private fun barcode(out: ByteArrayOutputStream, item: PrintItem.Barcode) {
        val align = when (item.align) { "left" -> 0; "right" -> 2; else -> 1 }
        out.write(byteArrayOf(ESC.toByte(), 0x61, align.toByte()))
        val hri = when (item.hri) { "above" -> 1; "both" -> 3; "none" -> 0; else -> 2 }
        out.write(byteArrayOf(GS.toByte(), 0x48, hri.toByte()))
        out.write(byteArrayOf(GS.toByte(), 0x68, item.height.coerceIn(1, 255).toByte()))
        out.write(byteArrayOf(GS.toByte(), 0x77, item.width.coerceIn(2, 6).toByte()))
        val m = BARCODE_M[item.symbology] ?: 73
        var data = encodeString(item.value)
        if (item.symbology == "CODE128" && !(data.isNotEmpty() && data[0].toInt() == 0x7B)) {
            data = byteArrayOf(0x7B, 0x42) + data
        }
        out.write(byteArrayOf(GS.toByte(), 0x6B, m.toByte(), data.size.toByte()))
        out.write(data)
    }

    fun encode(items: List<PrintItem>, defaultCodePage: String = "WPC1252", columns: Int = 48): Encoded {
        val out = ByteArrayOutputStream()
        val imageIndexes = mutableListOf<Int>()
        reset(out)
        items.forEachIndexed { index, item ->
            when (item) {
                is PrintItem.Text -> {
                    openStyle(out, item.style, defaultCodePage)
                    out.write(encodeString(item.value))
                    if (item.style.newline) out.write(LF)
                    reset(out)
                }
                is PrintItem.Feed -> out.write(byteArrayOf(ESC.toByte(), 0x64, item.lines.coerceIn(0, 255).toByte()))
                is PrintItem.Divider -> {
                    val ch = (item.char.firstOrNull() ?: '-').code
                    val n = item.columns ?: columns
                    val align = when (item.align) { "center" -> 1; "right" -> 2; else -> 0 }
                    out.write(byteArrayOf(ESC.toByte(), 0x61, align.toByte()))
                    if (item.bold) out.write(byteArrayOf(ESC.toByte(), 0x45, 1))
                    repeat(n) { out.write(ch) }
                    out.write(LF)
                    reset(out)
                }
                is PrintItem.QrCode -> qrCode(out, item)
                is PrintItem.Barcode -> barcode(out, item)
                is PrintItem.CashDrawer -> out.write(
                    if (item.pin == 5) byteArrayOf(ESC.toByte(), 0x70, 0x01, 0x19, 0xFA.toByte())
                    else byteArrayOf(ESC.toByte(), 0x70, 0x00, 0x19, 0xFA.toByte()),
                )
                is PrintItem.Cut -> {
                    if (item.feedBefore > 0) out.write(byteArrayOf(ESC.toByte(), 0x64, (item.feedBefore and 0xFF).toByte()))
                    out.write(if (item.mode == "full") byteArrayOf(GS.toByte(), 0x56, 0x00) else byteArrayOf(GS.toByte(), 0x56, 0x01))
                }
                is PrintItem.Raw -> {
                    val clean = if (item.bytesBase64.contains("base64,")) item.bytesBase64.substringAfter("base64,") else item.bytesBase64
                    out.write(Base64.decode(clean, Base64.DEFAULT))
                }
                is PrintItem.Image -> imageIndexes.add(index)
            }
        }
        return Encoded(out.toByteArray(), imageIndexes)
    }
}
