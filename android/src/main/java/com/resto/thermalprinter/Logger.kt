package com.resto.thermalprinter

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Logger circulaire en mémoire + Logcat, pour le diagnostic support client.
 *
 * - garde les N dernières lignes (ring buffer) consultables via getDebugLog(),
 * - chaque ligne est horodatée et catégorisée (discovery/connect/print/status),
 * - jamais de données image brutes (juste tailles/dimensions).
 *
 * L'app peut exposer ces logs dans un écran "Diagnostic imprimante" et les
 * joindre à un ticket de support.
 */
object Logger {
    private const val TAG = "ThermalPrinter"
    private const val MAX_LINES = 500
    private val ring = ArrayDeque<JSONObject>(MAX_LINES)
    @Volatile var verbose: Boolean = false

    @Synchronized
    fun log(category: String, message: String, data: Map<String, Any?>? = null) {
        val entry = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("category", category)
            .put("message", message)
        data?.forEach { (k, v) -> entry.put(k, v ?: JSONObject.NULL) }
        if (ring.size >= MAX_LINES) ring.pollFirst()
        ring.addLast(entry)
        if (verbose) Log.d(TAG, "[$category] $message ${data ?: ""}")
    }

    fun error(category: String, message: String, t: Throwable? = null) {
        log(category, message, mapOf("error" to (t?.message ?: "")))
        Log.e(TAG, "[$category] $message", t)
    }

    @Synchronized
    fun snapshot(): JSONArray {
        val arr = JSONArray()
        ring.forEach { arr.put(it) }
        return arr
    }

    @Synchronized
    fun clear() = ring.clear()
}
