package com.delicity.thermalprinter.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.delicity.thermalprinter.model.PrintItem

/**
 * Rend une liste d'items texte (`printText`) en **bitmap monochrome**, pour les
 * adapters dont le SDK ne propose pas de builder texte natif (Brother, Zebra).
 * Le moteur appelle ensuite `printBitmap` → le SDK imprime l'image.
 *
 * Police monospace (alignement colonne fiable). Supporte : texte (align, gras,
 * souligné, multiplicateurs de taille), séparateur, saut de ligne. Les items
 * QR/code-barres sont rendus en texte de repli ; image/raw/cut/tiroir sont ignorés
 * (utiliser `printImage` pour un QR/code-barres précis sur ces marques).
 */
object TextRasterizer {

    private data class Line(
        val text: String,
        val sizeMul: Int,
        val widthMul: Int,
        val bold: Boolean,
        val underline: Boolean,
        val align: String,
    )

    fun render(items: List<PrintItem>, widthDots: Int): Bitmap {
        val width = widthDots.coerceAtLeast(128)
        val columns = if (width <= 420) 32 else 48

        // Taille de base : caler la largeur de N caractères monospace sur la largeur cible.
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 24f
        }
        val charW = base.measureText("M").coerceAtLeast(1f)
        val baseTextSize = (24f * (width.toFloat() / columns) / charW)
        base.textSize = baseTextSize
        val baseLineH = base.fontMetrics.let { it.descent - it.ascent + it.leading }

        // Pass 1 : construire la liste de lignes + hauteur totale.
        val lines = mutableListOf<Line?>() // null = ligne vide (feed)
        for (item in items) {
            when (item) {
                is PrintItem.Text -> {
                    val s = item.style
                    item.value.split("\n").forEachIndexed { idx, raw ->
                        lines.add(Line(raw, s.heightMultiplier.coerceIn(1, 6), s.widthMultiplier.coerceIn(1, 6), s.bold, s.underline != "none", s.align ?: "left"))
                        // newline=false sur le dernier fragment : on garde quand même une ligne (simplifié).
                        if (!s.newline && idx == 0) Unit
                    }
                }
                is PrintItem.Divider -> {
                    val cols = item.columns ?: columns
                    lines.add(Line(item.char.take(1).ifEmpty { "-" }.repeat(cols.coerceIn(1, 96)), 1, 1, item.bold, false, item.align ?: "left"))
                }
                is PrintItem.Feed -> repeat(item.lines.coerceIn(1, 20)) { lines.add(null) }
                is PrintItem.QrCode -> lines.add(Line("[QR] ${item.value}", 1, 1, false, false, item.align))
                is PrintItem.Barcode -> lines.add(Line("[${item.symbology}] ${item.value}", 1, 1, false, false, item.align))
                is PrintItem.Cut, is PrintItem.CashDrawer, is PrintItem.Image, is PrintItem.Raw -> Unit
            }
        }
        if (lines.isEmpty()) lines.add(null)

        val totalHeight = lines.sumOf { line ->
            ((line?.sizeMul ?: 1) * baseLineH).toDouble()
        }.toInt().coerceAtLeast(baseLineH.toInt())

        // Pass 2 : dessiner.
        val bmp = Bitmap.createBitmap(width, totalHeight + 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.MONOSPACE }

        var y = 0f
        for (line in lines) {
            if (line == null) { y += baseLineH; continue }
            paint.textSize = baseTextSize * line.sizeMul
            paint.textScaleX = line.widthMul.toFloat()
            paint.isFakeBoldText = line.bold
            paint.isUnderlineText = line.underline
            val lineH = baseLineH * line.sizeMul
            val textW = paint.measureText(line.text)
            val x = when (line.align) {
                "center" -> (width - textW) / 2f
                "right" -> width - textW
                else -> 0f
            }.coerceAtLeast(0f)
            // baseline = y - ascent
            canvas.drawText(line.text, x, y - paint.fontMetrics.ascent, paint)
            y += lineH
        }
        return bmp
    }
}
