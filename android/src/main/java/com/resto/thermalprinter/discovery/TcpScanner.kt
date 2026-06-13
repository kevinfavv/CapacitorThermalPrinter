package com.resto.thermalprinter.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import com.resto.thermalprinter.model.AdapterId
import com.resto.thermalprinter.model.Capabilities
import com.resto.thermalprinter.model.DiscoveredPrinter
import com.resto.thermalprinter.model.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Scanner réseau : sonde le /24 courant sur les ports d'impression (9100 par défaut)
 * pour détecter des imprimantes RAW.
 *
 * Stratégie :
 *   - déduire le préfixe réseau via ConnectivityManager (ou networkCidr fourni),
 *   - tester 1..254 en parallèle (semaphore borné) avec un connect TCP court,
 *   - un port ouvert = candidat ESC/POS (adapter ESCPOS), faute d'identification SDK.
 *
 * Limites : ne distingue pas la marque. Les SDK (Epson/Star/...) tournent en
 * parallèle et, via le dédoublonnage, prennent la priorité sur ce résultat brut.
 */
class TcpScanner(private val context: Context) {

    suspend fun scan(
        timeoutMs: Long,
        ports: List<Int>,
        networkCidr: String?,
        onFound: (DiscoveredPrinter) -> Unit,
    ) = coroutineScope {
        val prefix = networkCidr?.let { cidrToPrefix(it) } ?: detectPrefix() ?: return@coroutineScope
        val portList = if (ports.isEmpty()) listOf(9100) else ports
        val perHostTimeout = 300 // ms : connect court, le scan global est borné par timeoutMs
        val gate = Semaphore(64) // limiter le parallélisme pour ne pas saturer le Wi-Fi

        val deadline = System.currentTimeMillis() + timeoutMs
        for (i in 1..254) {
            val host = "$prefix$i"
            for (port in portList) {
                if (System.currentTimeMillis() > deadline) return@coroutineScope
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        if (isOpen(host, port, perHostTimeout)) {
                            onFound(
                                DiscoveredPrinter(
                                    id = "wifi:$host",
                                    name = host,
                                    transport = Transport.WIFI,
                                    adapter = AdapterId.ESCPOS, // sera arbitré par la priorité
                                    address = "$host:$port",
                                    capabilities = Capabilities(supportsRasterImage = true),
                                    discoveredBy = mutableSetOf(AdapterId.ESCPOS, AdapterId.RAW_TCP),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun isOpen(host: String, port: Int, timeout: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeout)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Renvoie le préfixe "192.168.1." du réseau courant, ou null. */
    private fun detectPrefix(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return null
        val lp: LinkProperties = cm.getLinkProperties(active) ?: return null
        val addr = lp.linkAddresses.firstOrNull { it.address.address.size == 4 } ?: return null
        val ip = addr.address.hostAddress ?: return null
        return ip.substringBeforeLast('.') + "."
    }

    private fun cidrToPrefix(cidr: String): String? {
        // Support simple /24 : "192.168.1.0/24" -> "192.168.1."
        val ip = cidr.substringBefore('/')
        return if (ip.count { it == '.' } == 3) ip.substringBeforeLast('.') + "." else null
    }
}
