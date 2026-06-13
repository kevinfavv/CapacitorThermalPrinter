package com.delicity.thermalprinter.image

import android.content.Context
import com.delicity.thermalprinter.Logger
import com.delicity.thermalprinter.model.ErrorCode
import com.delicity.thermalprinter.model.PrinterException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Cache local des images à imprimer.
 *
 * Objectif : éviter de re-télécharger une même URL et fournir un chemin fichier
 * stable au pipeline (le mode fichier local est le plus fiable/performant).
 *
 * Emplacement : context.cacheDir/thermal-images/
 * Clé de cache : SHA-1 de l'URL.
 * Politique : LRU best-effort par date de modification, plafonné à MAX_BYTES.
 */
class ImageCache(context: Context) {

    private val dir: File = File(context.cacheDir, "thermal-images").apply { mkdirs() }

    /** Télécharge l'URL (si absente du cache) et renvoie le fichier local. */
    fun fetch(url: String, timeoutMs: Int = 10000): File {
        val key = sha1(url)
        val cached = File(dir, "$key.img")
        if (cached.exists() && cached.length() > 0) {
            cached.setLastModified(System.currentTimeMillis())
            Logger.log("image", "cache hit", mapOf("url" to url, "bytes" to cached.length()))
            return cached
        }
        return download(url, cached, timeoutMs)
    }

    private fun download(url: String, dest: File, timeoutMs: Int): File {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw PrinterException(ErrorCode.IMAGE_INVALID, "HTTP $code en téléchargeant $url")
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { out -> input.copyTo(out, 8192) }
            }
            Logger.log("image", "downloaded", mapOf("url" to url, "bytes" to dest.length()))
            enforceQuota()
            return dest
        } catch (e: PrinterException) {
            throw e
        } catch (e: Exception) {
            throw PrinterException(ErrorCode.IMAGE_INVALID, "Téléchargement image échoué", e.message, retryable = true)
        } finally {
            conn?.disconnect()
        }
    }

    /** Supprime les fichiers les plus anciens si le cache dépasse le quota. */
    private fun enforceQuota() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var i = 0
        while (total > MAX_BYTES && i < files.size) {
            total -= files[i].length()
            files[i].delete()
            i++
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_BYTES = 32L * 1024 * 1024 // 32 Mo
    }
}
