package com.resto.thermalprinter.store

import android.content.Context
import android.content.SharedPreferences
import com.resto.thermalprinter.model.PrinterProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistance des profils d'imprimantes (SharedPreferences + JSON).
 *
 * Conçu pour la reconnexion automatique : tout le nécessaire pour rejoindre une
 * imprimante sans re-découverte est stocké dans le PrinterProfile.
 *
 * NB : les profils ne contiennent pas de secret. Si un jour des credentials
 * réseau sont stockés, migrer vers EncryptedSharedPreferences.
 */
class PrinterStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("resto.thermalprinter", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<PrinterProfile> {
        val raw = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { PrinterProfile.fromJson(arr.getJSONObject(it)) }
    }

    fun get(id: String): PrinterProfile? = all().firstOrNull { it.id == id }

    fun getDefault(): PrinterProfile? = all().firstOrNull { it.isDefault }

    @Synchronized
    fun upsert(profile: PrinterProfile) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        profile.updatedAt = System.currentTimeMillis()
        if (idx >= 0) list[idx] = profile else list.add(profile)
        persist(list)
    }

    @Synchronized
    fun setDefault(id: String): PrinterProfile? {
        val list = all().toMutableList()
        var target: PrinterProfile? = null
        list.forEach {
            it.isDefault = it.id == id
            if (it.isDefault) target = it
        }
        persist(list)
        return target
    }

    @Synchronized
    fun remove(id: String) {
        persist(all().filterNot { it.id == id })
    }

    private fun persist(list: List<PrinterProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    companion object {
        private const val KEY_PROFILES = "profiles_v1"

        fun emptyJson(): JSONObject = JSONObject()
    }
}
