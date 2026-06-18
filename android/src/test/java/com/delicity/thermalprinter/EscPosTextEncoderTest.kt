package com.delicity.thermalprinter

import com.delicity.thermalprinter.adapters.EscPosTextEncoder
import com.delicity.thermalprinter.model.PrintItem
import com.delicity.thermalprinter.model.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitaires JVM de l'encodeur ESC/POS texte (miroir de test/escpos-text.spec.ts).
 * Ne couvre pas l'item `raw` (qui dépend d'android.util.Base64, non mocké en test unitaire).
 */
class EscPosTextEncoderTest {

    private fun bytes(items: List<PrintItem>) = EscPosTextEncoder.encode(items).bytes.map { it.toInt() and 0xFF }

    @Test
    fun `sizeByte combine largeur et hauteur`() {
        assertEquals(0x00, EscPosTextEncoder.sizeByte(1, 1))
        assertEquals(0x10, EscPosTextEncoder.sizeByte(2, 1))
        assertEquals(0x01, EscPosTextEncoder.sizeByte(1, 2))
        assertEquals(0x77, EscPosTextEncoder.sizeByte(8, 8))
        assertEquals(0x77, EscPosTextEncoder.sizeByte(99, 99)) // clamp
    }

    @Test
    fun `encodeString WPC1252 sort l'octet Latin-1 direct`() {
        assertEquals(listOf(0xE9), EscPosTextEncoder.encodeString("é").map { it.toInt() and 0xFF })
        assertEquals(listOf(0x3F), EscPosTextEncoder.encodeString("€").map { it.toInt() and 0xFF }) // hors plage -> ?
    }

    @Test
    fun `encodeString CP437 remappe les accents FR vers les octets DOS`() {
        // En CP437 é/à/ç ne valent PAS leur octet Latin-1 (sinon é->Θ sur l'imprimante).
        assertEquals(
            listOf(0x82, 0x85, 0x87, 0x97, 0x88),
            EscPosTextEncoder.encodeString("éàçùê", "CP437").map { it.toInt() and 0xFF },
        )
        // € indisponible en CP437 -> ?
        assertEquals(listOf(0x3F), EscPosTextEncoder.encodeString("€", "CP437").map { it.toInt() and 0xFF })
    }

    @Test
    fun `encodeString CP858 ajoute l'euro`() {
        assertEquals(listOf(0xD5), EscPosTextEncoder.encodeString("€", "CP858").map { it.toInt() and 0xFF })
        assertEquals(listOf(0x82), EscPosTextEncoder.encodeString("é", "CP858").map { it.toInt() and 0xFF })
    }

    @Test
    fun `encode applique la page de code aux accents du texte`() {
        // defaultCodePage=CP437 -> é encodé 0x82 (pas 0xE9) ET ESC t 0 émis.
        val b = EscPosTextEncoder.encode(listOf(PrintItem.Text("é", TextStyle())), "CP437")
            .bytes.map { it.toInt() and 0xFF }
        assertTrue(b.joinToString(",").contains("27,116,0")) // ESC t 0 (CP437)
        assertTrue(b.contains(0x82)) // é remappé
        assertTrue(!b.contains(0xE9)) // surtout PAS l'octet Latin-1
    }

    @Test
    fun `un job texte commence par ESC arobase et finit le texte par LF`() {
        val b = bytes(listOf(PrintItem.Text("Hi", TextStyle())))
        assertEquals(0x1B, b[0])
        assertEquals(0x40, b[1])
        assertTrue(b.contains(0x0A)) // LF
    }

    @Test
    fun `texte gras centre taille x2 emet les bonnes commandes`() {
        val b = bytes(listOf(PrintItem.Text("X", TextStyle(align = "center", bold = true, widthMultiplier = 2, heightMultiplier = 2))))
        val s = b.joinToString(",")
        assertTrue(s.contains("27,116,16")) // ESC t 16 (WPC1252)
        assertTrue(s.contains("27,97,1")) // align center
        assertTrue(s.contains("27,69,1")) // bold on
        assertTrue(s.contains("29,33,17")) // GS ! taille x2/x2
    }

    @Test
    fun `qrcode emet la sequence GS k complete`() {
        val b = bytes(listOf(PrintItem.QrCode("HELLO", 6, "H", "center")))
        val s = b.joinToString(",")
        assertTrue(s.contains("29,40,107,3,0,49,67,6")) // taille module 6
        assertTrue(s.contains("29,40,107,3,0,49,69,51")) // EC = H (51)
        assertTrue(s.contains("29,40,107,3,0,49,81,48")) // print
    }

    @Test
    fun `barcode CODE128 prefixe avec B et utilise m=73`() {
        val b = bytes(listOf(PrintItem.Barcode("12345", "CODE128", 80, 3, "below", "center")))
        val s = b.joinToString(",")
        assertTrue(s.contains("29,107,73")) // GS k 73
        assertTrue(s.contains("123,66")) // {B
    }

    @Test
    fun `divider produit n caracteres et cut partiel par defaut`() {
        val divider = bytes(listOf(PrintItem.Divider("=", 5, null, false)))
        assertTrue(divider.joinToString(",").contains("61,61,61,61,61")) // 5x '='
        val cut = bytes(listOf(PrintItem.Cut("partial", 0)))
        assertTrue(cut.joinToString(",").contains("29,86,1"))
    }

    @Test
    fun `feed et cashDrawer pin5`() {
        val b = bytes(listOf(PrintItem.Feed(2), PrintItem.CashDrawer(5)))
        val s = b.joinToString(",")
        assertTrue(s.contains("27,100,2")) // feed 2
        assertTrue(s.contains("27,112,1")) // drawer pin5
    }

    @Test
    fun `les items image sont signales sans etre encodes`() {
        val encoded = EscPosTextEncoder.encode(
            listOf(PrintItem.Text("a", TextStyle()), PrintItem.Image(null, null, null, null)),
        )
        assertEquals(listOf(1), encoded.imageIndexes)
    }
}
