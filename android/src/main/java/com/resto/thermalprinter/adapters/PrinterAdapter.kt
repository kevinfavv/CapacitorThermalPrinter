package com.resto.thermalprinter.adapters

import android.graphics.Bitmap
import com.resto.thermalprinter.model.AdapterId
import com.resto.thermalprinter.model.DiscoveredPrinter
import com.resto.thermalprinter.model.PrinterProfile
import com.resto.thermalprinter.model.PrinterStatus
import com.resto.thermalprinter.model.RenderOptions

/**
 * Contrat commun à tous les adapters natifs Android.
 *
 * Un adapter encapsule TOUT ce qui est spécifique à une famille d'imprimantes :
 *   - sa découverte (via SDK ou scan générique),
 *   - sa connexion / reconnexion,
 *   - la conversion d'un Bitmap déjà binarisé en commandes natives,
 *   - l'envoi,
 *   - la lecture de statut.
 *
 * Le moteur (ThermalPrinterEngine) orchestre les adapters et applique la priorité.
 * Toutes les méthodes longues sont `suspend` (exécutées sur Dispatchers.IO).
 */
interface PrinterAdapter {

    val id: AdapterId

    /** True si le SDK requis est présent dans l'app (sinon l'adapter est ignoré). */
    fun isAvailable(): Boolean

    /**
     * Lance une découverte propre à cet adapter. Les résultats partiels peuvent
     * être émis via [onFound] au fil de l'eau. La méthode se termine quand le
     * scan est fini ou que [timeoutMs] est atteint.
     */
    suspend fun discover(
        timeoutMs: Long,
        onFound: (DiscoveredPrinter) -> Unit,
    )

    /** Indique si cet adapter sait gérer ce profil (transport + identité). */
    fun canHandle(profile: PrinterProfile): Boolean

    /** Ouvre une connexion. Idempotent : ne rien faire si déjà connecté. */
    suspend fun connect(profile: PrinterProfile, timeoutMs: Long)

    /** True si une connexion active existe pour cette imprimante. */
    fun isConnected(printerId: String): Boolean

    /** Ferme la connexion. */
    suspend fun disconnect(printerId: String)

    /**
     * Imprime un bitmap.
     *
     * IMPORTANT : `bitmap` est DÉJÀ redimensionné à la largeur cible et
     * binarisé/dithered par le moteur (ImageProcessor). Pour les adapters ESC/POS,
     * il suffit de l'encoder en raster GS v 0. Pour les SDK fabricants, on passe
     * le bitmap à l'API d'impression image du SDK.
     *
     * @return nombre d'octets envoyés (best effort).
     */
    suspend fun printBitmap(profile: PrinterProfile, bitmap: Bitmap, options: RenderOptions): Int

    /** Lit le statut. Renvoie supportsStatus=false via online/paper=unknown si non supporté. */
    suspend fun getStatus(profile: PrinterProfile): PrinterStatus
}
