package com.epson.epos2.printer

import android.content.Context
import android.graphics.Bitmap

/**
 * FAUX SDK Epson ePOS2 — présent UNIQUEMENT sur le classpath de test.
 *
 * Reproduit le package/les classes/les signatures réellement appelés par
 * [com.delicity.thermalprinter.adapters.EpsonAdapter] via réflexion. Comme l'adapter
 * résout les classes par `Class.forName("com.epson.epos2.printer.Printer")`, ce faux
 * est exécuté à sa place en test → on couvre tout le plumbing réflexif (connexion,
 * impression image/texte, statut) SANS le binaire propriétaire ni imprimante.
 *
 * En production, ce faux n'existe pas (src/test seulement) → `isAvailable()` = false.
 */
class Printer(val series: Int, val lang: Int, val context: Context) {

    /** Journal des appels, pour les assertions de test. */
    val calls = mutableListOf<String>()
    var statusInfo = PrinterStatusInfo()
    var connected = false

    init { instances.add(this) }

    fun connect(target: String, timeout: Int): Int { calls.add("connect:$target:$timeout"); connected = true; return 0 }
    fun disconnect(): Int { calls.add("disconnect"); connected = false; return 0 }
    fun clearCommandBuffer(): Int { calls.add("clear"); return 0 }
    fun beginTransaction(): Int { calls.add("begin"); return 0 }
    fun endTransaction(): Int { calls.add("end"); return 0 }

    fun addImage(
        image: Bitmap, x: Int, y: Int, width: Int, height: Int,
        color: Int, mode: Int, halftone: Int, brightness: Double, compress: Int,
    ): Int { calls.add("addImage:${width}x$height:halftone=$halftone"); return 0 }

    fun addCut(type: Int): Int { calls.add("addCut:$type"); return 0 }
    fun addPulse(drawer: Int, time: Int): Int { calls.add("addPulse:$drawer"); return 0 }
    fun addText(data: String): Int { calls.add("addText:$data"); return 0 }
    fun addTextAlign(align: Int): Int { calls.add("align:$align"); return 0 }
    fun addTextStyle(reverse: Int, ul: Int, em: Int, color: Int): Int { calls.add("style:$reverse,$ul,$em"); return 0 }
    fun addTextSize(w: Int, h: Int): Int { calls.add("size:${w}x$h"); return 0 }
    fun addFeedLine(line: Int): Int { calls.add("feed:$line"); return 0 }
    fun addSymbol(data: String, type: Int, level: Int, width: Int, height: Int, size: Int): Int { calls.add("symbol:$data"); return 0 }
    fun addBarcode(data: String, type: Int, hri: Int, font: Int, width: Int, height: Int): Int { calls.add("barcode:$data"); return 0 }
    fun sendData(timeout: Int): Int { calls.add("sendData"); return 0 }
    fun getStatus(): PrinterStatusInfo = statusInfo

    companion object {
        /** Toutes les instances créées (la dernière = celle du test courant). */
        @JvmField val instances = mutableListOf<Printer>()
        fun reset() = instances.clear()

        @JvmField val TRUE = 1
        @JvmField val FALSE = 0
        @JvmField val MODEL_ANK = 0
        @JvmField val PARAM_DEFAULT = -2
        @JvmField val COLOR_1 = 1
        @JvmField val MODE_MONO = 0
        @JvmField val COMPRESS_AUTO = 0
        @JvmField val HALFTONE_DITHER = 0
        @JvmField val HALFTONE_ERROR_DIFFUSION = 2
        @JvmField val HALFTONE_THRESHOLD = 1
        @JvmField val CUT_FEED = 1
        @JvmField val CUT_NO_FEED = 2
        @JvmField val DRAWER_2PIN = 0
        @JvmField val PULSE_100 = 0
        @JvmField val PAPER_OK = 0
        @JvmField val PAPER_NEAR_END = 1
        @JvmField val PAPER_EMPTY = 2
        @JvmField val ALIGN_LEFT = 0
        @JvmField val ALIGN_CENTER = 1
        @JvmField val ALIGN_RIGHT = 2
        @JvmField val SYMBOL_QRCODE_MODEL_2 = 0
        @JvmField val LEVEL_L = 0
        @JvmField val LEVEL_M = 1
        @JvmField val LEVEL_Q = 2
        @JvmField val LEVEL_H = 3
        @JvmField val BARCODE_CODE128 = 10
        @JvmField val BARCODE_CODE39 = 4
        @JvmField val HRI_NONE = 0
        @JvmField val HRI_BELOW = 2
        @JvmField val FONT_A = 0
        @JvmField val TM_M30 = 18
    }
}

/** Faux PrinterStatusInfo : champs `int` publics lus par réflexion. */
class PrinterStatusInfo {
    @JvmField var connection = 1
    @JvmField var online = 1
    @JvmField var paper = 0
    @JvmField var coverOpen = 0
}
