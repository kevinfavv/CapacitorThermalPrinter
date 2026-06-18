package com.delicity.thermalprinter

import android.graphics.Bitmap
import com.delicity.thermalprinter.image.ImageProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * resizeToWidth ne doit JAMAIS agrandir : une image déjà dimensionnée (ex. reçu 58mm/384px)
 * ne doit pas être gonflée à 576px (défaut 80mm) puis coupée par une imprimante 58mm.
 */
@RunWith(RobolectricTestRunner::class)
class ImageResizeTest {

    @Test
    fun `ne gonfle pas une image plus etroite que la cible`() {
        val src = Bitmap.createBitmap(384, 100, Bitmap.Config.ARGB_8888)
        val out = ImageProcessor.resizeToWidth(src, 576)
        assertEquals(384, out.width) // bornée à la largeur source, pas 576
        assertEquals(100, out.height) // hauteur inchangée (ratio 1)
    }

    @Test
    fun `reduit une image plus large que la cible`() {
        val src = Bitmap.createBitmap(800, 200, Bitmap.Config.ARGB_8888)
        val out = ImageProcessor.resizeToWidth(src, 384)
        assertEquals(384, out.width) // réduite à la cible
        assertEquals(96, out.height) // 200 * 384/800
    }
}
