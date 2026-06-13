package com.resto.thermalprinter.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import com.resto.thermalprinter.model.ErrorCode
import com.resto.thermalprinter.model.PrinterException
import com.resto.thermalprinter.model.RenderOptions
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pipeline de traitement image -> raster thermique.
 *
 * Étapes :
 *   1. decode (fichier local / base64) en Bitmap ARGB
 *   2. resize à la largeur cible (widthDots), hauteur proportionnelle
 *   3. aplatir sur fond blanc (gérer la transparence PNG)
 *   4. niveaux de gris (luminance ITU-R BT.601)
 *   5. binarisation 1-bit (threshold / Floyd-Steinberg / Atkinson)
 *   6. encodage raster ESC/POS GS v 0 (pour les adapters ESC/POS)
 *
 * Garde-fous mémoire : MAX_HEIGHT borne la hauteur pour éviter les OOM /
 * débordements de buffer imprimante.
 */
object ImageProcessor {

    /** Hauteur max d'un ticket en points (~ plusieurs mètres @203dpi). */
    private const val MAX_HEIGHT = 20_000

    // ---------------------------------------------------------------------
    // 1. Décodage
    // ---------------------------------------------------------------------

    fun decodeFile(path: String): Bitmap {
        val clean = path.removePrefix("file://")
        val file = File(clean)
        if (!file.exists()) {
            throw PrinterException(ErrorCode.IMAGE_INVALID, "Fichier introuvable: $clean")
        }
        // Pré-lecture des bornes pour calculer un inSampleSize si l'image est énorme.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(clean, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 1 // le resize final fait le vrai downscale
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(clean, opts)
            ?: throw PrinterException(ErrorCode.IMAGE_INVALID, "Décodage impossible: $clean")
    }

    fun decodeBase64(b64: String): Bitmap {
        val payload = b64.substringAfter("base64,", b64) // tolère le préfixe data:
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw PrinterException(ErrorCode.IMAGE_INVALID, "Base64 invalide", e.message)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw PrinterException(ErrorCode.IMAGE_INVALID, "Décodage base64 impossible")
    }

    // ---------------------------------------------------------------------
    // 2 + 3. Resize sur fond blanc
    // ---------------------------------------------------------------------

    /** Redimensionne à [targetWidth] px, hauteur proportionnelle, fond blanc opaque. */
    fun resizeToWidth(src: Bitmap, targetWidth: Int): Bitmap {
        val w = targetWidth.coerceAtLeast(8)
        val ratio = w.toDouble() / src.width.toDouble()
        var h = Math.round(src.height * ratio).toInt().coerceAtLeast(1)
        if (h > MAX_HEIGHT) {
            throw PrinterException(
                ErrorCode.IMAGE_TOO_LARGE,
                "Image trop haute après resize: ${h}px (max $MAX_HEIGHT)",
            )
        }
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        // Aplatir sur blanc pour neutraliser l'alpha (PNG transparents).
        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaled, 0f, 0f, null)
        if (scaled != src) scaled.recycle()
        return flat
    }

    // ---------------------------------------------------------------------
    // 4 + 5. Niveaux de gris + binarisation
    // ---------------------------------------------------------------------

    /**
     * Convertit en niveaux de gris (0=noir..255=blanc) dans un IntArray pixel-par-pixel.
     */
    private fun toGrayscale(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            // luminance perceptuelle
            gray[i] = ((0.299 * r) + (0.587 * g) + (0.114 * b)).toInt().coerceIn(0, 255)
        }
        return gray
    }

    /**
     * Produit un MonoBitmap (1 = encre/noir) selon l'option de dithering.
     */
    fun toMono(bmp: Bitmap, options: RenderOptions): MonoBitmap {
        val w = bmp.width
        val h = bmp.height
        val gray = toGrayscale(bmp)
        if (options.invert) {
            for (i in gray.indices) gray[i] = 255 - gray[i]
        }
        val data = when (options.dithering) {
            "none" -> threshold(gray, options.threshold)
            "atkinson" -> atkinson(gray, w, h)
            else -> floydSteinberg(gray, w, h)
        }
        return MonoBitmap(w, h, data)
    }

    private fun threshold(gray: IntArray, t: Int): ByteArray {
        val out = ByteArray(gray.size)
        for (i in gray.indices) out[i] = if (gray[i] < t) 1 else 0
        return out
    }

    private fun floydSteinberg(grayInput: IntArray, w: Int, h: Int): ByteArray {
        val gray = FloatArray(grayInput.size) { grayInput[it].toFloat() }
        val out = ByteArray(grayInput.size)
        fun at(x: Int, y: Int) = y * w + x
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = at(x, y)
                val old = gray[idx]
                val new = if (old < 128f) 0f else 255f
                out[idx] = if (new == 0f) 1 else 0
                val err = old - new
                if (x + 1 < w) gray[at(x + 1, y)] += err * 7f / 16f
                if (x - 1 >= 0 && y + 1 < h) gray[at(x - 1, y + 1)] += err * 3f / 16f
                if (y + 1 < h) gray[at(x, y + 1)] += err * 5f / 16f
                if (x + 1 < w && y + 1 < h) gray[at(x + 1, y + 1)] += err * 1f / 16f
            }
        }
        return out
    }

    private fun atkinson(grayInput: IntArray, w: Int, h: Int): ByteArray {
        val gray = FloatArray(grayInput.size) { grayInput[it].toFloat() }
        val out = ByteArray(grayInput.size)
        fun at(x: Int, y: Int) = y * w + x
        fun spread(x: Int, y: Int, e: Float) {
            if (x in 0 until w && y in 0 until h) gray[at(x, y)] += e
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = at(x, y)
                val old = gray[idx]
                val new = if (old < 128f) 0f else 255f
                out[idx] = if (new == 0f) 1 else 0
                val err = (old - new) / 8f
                spread(x + 1, y, err); spread(x + 2, y, err)
                spread(x - 1, y + 1, err); spread(x, y + 1, err); spread(x + 1, y + 1, err)
                spread(x, y + 2, err)
            }
        }
        return out
    }

    // ---------------------------------------------------------------------
    // 6. Encodage raster ESC/POS GS v 0
    // ---------------------------------------------------------------------

    /**
     * Encode un MonoBitmap en commande raster ESC/POS `GS v 0` (mode normal).
     * Largeur paddée au multiple de 8. Voir spec dans src/core/imaging.ts.
     */
    fun encodeEscPosRaster(mono: MonoBitmap): ByteArray {
        val w = mono.width
        val h = mono.height
        val bytesPerRow = (w + 7) / 8
        val xL = bytesPerRow and 0xff
        val xH = (bytesPerRow shr 8) and 0xff
        val yL = h and 0xff
        val yH = (h shr 8) and 0xff
        val header = byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL.toByte(), xH.toByte(), yL.toByte(), yH.toByte())
        val body = ByteArray(bytesPerRow * h)
        for (y in 0 until h) {
            val rowOff = y * bytesPerRow
            val srcOff = y * w
            for (x in 0 until w) {
                if (mono.data[srcOff + x].toInt() == 1) {
                    val byteIndex = rowOff + (x shr 3)
                    val bit = 7 - (x and 7)
                    body[byteIndex] = (body[byteIndex].toInt() or (1 shl bit)).toByte()
                }
            }
        }
        val out = ByteArrayOutputStream(header.size + body.size)
        out.write(header)
        out.write(body)
        return out.toByteArray()
    }
}

/** Image 1-bit : data[i]=1 => point noir/encre. */
class MonoBitmap(val width: Int, val height: Int, val data: ByteArray)
