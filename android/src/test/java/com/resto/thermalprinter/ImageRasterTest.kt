package com.resto.thermalprinter

import com.resto.thermalprinter.image.ImageProcessor
import com.resto.thermalprinter.image.MonoBitmap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests unitaires JVM de l'encodeur raster ESC/POS (GS v 0).
 * encodeEscPosRaster est pur (MonoBitmap -> ByteArray), donc testable sans Android.
 */
class ImageRasterTest {

    private fun u(b: Byte) = b.toInt() and 0xFF

    @Test
    fun `header GS v 0 et octets attendus 8x2`() {
        val data = ByteArray(16)
        for (i in 0 until 8) data[i] = 1 // ligne 0 noire
        val raster = ImageProcessor.encodeEscPosRaster(MonoBitmap(8, 2, data))
        assertEquals(0x1D, u(raster[0]))
        assertEquals(0x76, u(raster[1]))
        assertEquals(0x30, u(raster[2]))
        assertEquals(0x00, u(raster[3])) // mode normal
        assertEquals(1, u(raster[4])); assertEquals(0, u(raster[5])) // 1 octet/ligne
        assertEquals(2, u(raster[6])); assertEquals(0, u(raster[7])) // 2 lignes
        assertEquals(0xFF, u(raster[8])) // ligne noire
        assertEquals(0x00, u(raster[9])) // ligne blanche
    }

    @Test
    fun `padding largeur au multiple de 8 - 10px MSB-first`() {
        val raster = ImageProcessor.encodeEscPosRaster(MonoBitmap(10, 1, ByteArray(10) { 1 }))
        assertEquals(2, u(raster[4])) // 2 octets/ligne
        assertEquals(0xFF, u(raster[8]))
        assertEquals(0xC0, u(raster[9])) // 2 bits restants à gauche
    }

    @Test
    fun `grandes dimensions encodees sur 2 octets`() {
        val raster = ImageProcessor.encodeEscPosRaster(MonoBitmap(16, 300, ByteArray(16 * 300)))
        assertEquals(2, u(raster[4])); assertEquals(0, u(raster[5]))
        assertEquals(300 and 0xFF, u(raster[6])); assertEquals(300 shr 8, u(raster[7]))
    }
}
