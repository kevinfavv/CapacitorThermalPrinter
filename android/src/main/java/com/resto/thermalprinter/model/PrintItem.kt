package com.resto.thermalprinter.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Modèle d'items texte (miroir Kotlin de src/core/text.ts).
 * Parsé depuis le tableau JSON envoyé par printText([...]).
 */
data class TextStyle(
    val align: String? = null,
    val bold: Boolean = false,
    val underline: String = "none",
    val font: String = "A",
    val widthMultiplier: Int = 1,
    val heightMultiplier: Int = 1,
    val doubleStrike: Boolean = false,
    val invert: Boolean = false,
    val upsideDown: Boolean = false,
    val rotate90: Boolean = false,
    val letterSpacing: Int? = null,
    val lineSpacing: Int? = null,
    val codePage: String? = null,
    val codePageId: Int? = null,
    val newline: Boolean = true,
) {
    companion object {
        fun fromJson(o: JSONObject?): TextStyle {
            if (o == null) return TextStyle()
            return TextStyle(
                align = o.optString("align").ifEmpty { null },
                bold = o.optBoolean("bold", false),
                underline = o.optString("underline", "none"),
                font = o.optString("font", "A"),
                widthMultiplier = o.optInt("widthMultiplier", 1),
                heightMultiplier = o.optInt("heightMultiplier", 1),
                doubleStrike = o.optBoolean("doubleStrike", false),
                invert = o.optBoolean("invert", false),
                upsideDown = o.optBoolean("upsideDown", false),
                rotate90 = o.optBoolean("rotate90", false),
                letterSpacing = if (o.has("letterSpacing")) o.optInt("letterSpacing") else null,
                lineSpacing = if (o.has("lineSpacing")) o.optInt("lineSpacing") else null,
                codePage = o.optString("codePage").ifEmpty { null },
                codePageId = if (o.has("codePageId")) o.optInt("codePageId") else null,
                newline = o.optBoolean("newline", true),
            )
        }
    }
}

sealed class PrintItem {
    data class Text(val value: String, val style: TextStyle) : PrintItem()
    data class Feed(val lines: Int) : PrintItem()
    data class Cut(val mode: String, val feedBefore: Int) : PrintItem()
    data class Divider(val char: String, val columns: Int?, val align: String?, val bold: Boolean) : PrintItem()
    data class QrCode(val value: String, val size: Int, val ec: String, val align: String) : PrintItem()
    data class Barcode(
        val value: String, val symbology: String, val height: Int, val width: Int, val hri: String, val align: String,
    ) : PrintItem()
    data class CashDrawer(val pin: Int) : PrintItem()
    data class Image(val filePath: String?, val url: String?, val base64: String?, val render: JSONObject?) : PrintItem()
    data class Raw(val bytesBase64: String) : PrintItem()

    companion object {
        fun parseList(arr: JSONArray): List<PrintItem> =
            (0 until arr.length()).mapNotNull { parse(arr.optJSONObject(it)) }

        private fun parse(o: JSONObject?): PrintItem? {
            if (o == null) return null
            return when (o.optString("type")) {
                "text" -> Text(o.optString("value"), TextStyle.fromJson(o.optJSONObject("style")))
                "feed" -> Feed(o.optInt("lines", 1))
                "cut" -> Cut(o.optString("mode", "partial"), o.optInt("feedBefore", 0))
                "divider" -> Divider(
                    o.optString("char", "-"),
                    if (o.has("columns")) o.optInt("columns") else null,
                    o.optJSONObject("style")?.optString("align")?.ifEmpty { null },
                    o.optJSONObject("style")?.optBoolean("bold", false) ?: false,
                )
                "qrcode" -> QrCode(o.optString("value"), o.optInt("size", 6), o.optString("errorCorrection", "M"), o.optString("align", "center"))
                "barcode" -> Barcode(
                    o.optString("value"), o.optString("symbology", "CODE128"),
                    o.optInt("height", 80), o.optInt("width", 3),
                    o.optString("hri", "below"), o.optString("align", "center"),
                )
                "cashDrawer" -> CashDrawer(o.optInt("pin", 2))
                "image" -> o.optJSONObject("image").let { img ->
                    Image(
                        img?.optString("filePath")?.ifEmpty { null },
                        img?.optString("url")?.ifEmpty { null },
                        img?.optString("base64")?.ifEmpty { null },
                        o.optJSONObject("render"),
                    )
                }
                "raw" -> Raw(o.optString("bytesBase64"))
                else -> null
            }
        }
    }
}
