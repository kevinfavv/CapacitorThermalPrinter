package com.delicity.thermalprinter.model

import org.json.JSONObject

/**
 * Taille de papier déduite à la connexion (best-effort). Miroir Kotlin de
 * `src/core/paper.ts`. Tous les champs sont nullables : `null` = inconnu.
 */
data class PaperInfo(
    val widthMm: Int?,
    val printableDots: Int?,
    val dpi: Int?,
    val source: String, // 'model' | 'sdk' | 'profile'
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("widthMm", widthMm ?: JSONObject.NULL)
        put("printableDots", printableDots ?: JSONObject.NULL)
        put("dpi", dpi ?: JSONObject.NULL)
        put("source", source)
    }
}

/** Déduction de la taille papier depuis la marque/modèle (best-effort). */
object PaperSizeGuess {

    fun dotsForWidth(widthMm: Int): Int? = when (widthMm) {
        58 -> 384
        80 -> 576
        112 -> 832
        else -> null
    }

    /** Renvoie la taille papier déduite, ou null si le modèle est inconnu. */
    fun fromBrandModel(brand: String?, model: String?): PaperInfo? {
        if (model.isNullOrBlank()) return null
        val m = "${brand ?: ""}$model".uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (m.isEmpty()) return null
        val widthMm = when {
            Regex("TMM10|TMP20|TMP60|MCPRINT2|MCP2|SML200|SMS2").containsMatchIn(m) -> 58
            Regex("SMT400|TUP5").containsMatchIn(m) -> 112
            Regex("TMM30|TMM50|TMT20|TMT70|TMT8|TMT100|TMP80|TML90|MCPRINT3|MCP3|TSP1|TSP6|TSP7|TSP8|SMT300").containsMatchIn(m) -> 80
            else -> return null
        }
        return PaperInfo(widthMm, dotsForWidth(widthMm), 203, "model")
    }
}
