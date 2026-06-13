package com.delicity.thermalprinter.discovery

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.delicity.thermalprinter.adapters.PrinterAdapter
import com.delicity.thermalprinter.model.DiscoveredPrinter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/**
 * Orchestre la découverte AGRÉGÉE multi-sources en parallèle puis fusionne.
 *
 * Sources lancées (selon options + disponibilité plateforme) :
 *   - SDK Epson / Star / Brother / Zebra (via PrinterAdapter.discover)
 *   - TcpScanner (réseau 9100)
 *   - BluetoothClassicScanner (Android)
 *   - BleScanner (optionnel)
 *   - UsbAdapter.discover (optionnel)
 *
 * Chaque source pousse ses résultats dans un buffer thread-safe ; `emitPartial`
 * est invoqué au fil de l'eau (event printerFound). À la fin : fusion +
 * dédoublonnage + arbitrage d'adapter via AdapterPriority.
 */
class DiscoveryManager(
    private val context: Context,
    private val btAdapter: BluetoothAdapter?,
    private val adapters: List<PrinterAdapter>,
) {

    data class Options(
        val sources: Set<String>?, // null = toutes
        val timeoutMs: Long = 8000,
        val includePaired: Boolean = true,
        val networkCidr: String? = null,
        val tcpPorts: List<Int> = listOf(9100),
    )

    suspend fun discover(
        options: Options,
        emitPartial: (DiscoveredPrinter) -> Unit,
    ): Pair<List<DiscoveredPrinter>, List<String>> = coroutineScope {
        val buffer = Collections.synchronizedList(mutableListOf<DiscoveredPrinter>())
        val failed = Collections.synchronizedList(mutableListOf<String>())

        // Collecte thread-safe + émission partielle (callback synchrone).
        val collect: (DiscoveredPrinter) -> Unit = { p ->
            buffer.add(p)
            runCatching { emitPartial(p) }
        }

        fun enabled(src: String) = options.sources == null || options.sources.contains(src)

        val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

        // --- Sources SDK fabricants (via adapters) ---
        for (adapter in adapters) {
            val discoverySource = when (adapter.id.value) {
                "epson" -> "epson"
                "star" -> "star"
                "brother" -> "brother"
                "zebra" -> "zebra"
                else -> null
            } ?: continue
            if (enabled(discoverySource) && adapter.isAvailable()) {
                jobs += async {
                    runCatching {
                        withTimeoutOrNull(options.timeoutMs + 1000) {
                            adapter.discover(options.timeoutMs, collect)
                        }
                    }.onFailure { failed.add(discoverySource) }
                    Unit
                }
            }
        }

        // --- Source TCP réseau ---
        if (enabled("tcp")) {
            jobs += async {
                runCatching {
                    TcpScanner(context).scan(options.timeoutMs, options.tcpPorts, options.networkCidr, collect)
                }.onFailure { failed.add("tcp") }
                Unit
            }
        }

        // --- Source Bluetooth classique (Android) ---
        if (enabled("bluetooth") && btAdapter != null) {
            jobs += async {
                runCatching {
                    BluetoothClassicScanner(context, btAdapter)
                        .scan(options.timeoutMs, options.includePaired, collect)
                }.onFailure { failed.add("bluetooth") }
                Unit
            }
        }

        // --- Source BLE (allowlist de services "UART série") ---
        if (enabled("ble") && btAdapter != null) {
            jobs += async {
                runCatching {
                    BleScanner(context, btAdapter).scan(options.timeoutMs, collect)
                }.onFailure { failed.add("ble") }
                Unit
            }
        }

        jobs.awaitAll()

        Pair(merge(buffer.toList()), failed.distinct())
    }

    /** Fusion + dédoublonnage par id stable, en conservant le meilleur adapter. */
    private fun merge(incoming: List<DiscoveredPrinter>): List<DiscoveredPrinter> {
        val byId = LinkedHashMap<String, DiscoveredPrinter>()
        for (p in incoming) {
            val existing = byId[p.id]
            if (existing == null) {
                byId[p.id] = p
                continue
            }
            val mergedSources = (existing.discoveredBy + p.discoveredBy).toMutableSet()
            val winner = if (AdapterPriority.score(p) > AdapterPriority.score(existing)) p else existing
            winner.discoveredBy.clear()
            winner.discoveredBy.addAll(mergedSources)
            winner.lastSeenAt = maxOf(existing.lastSeenAt, p.lastSeenAt)
            winner.isConnected = existing.isConnected || p.isConnected
            byId[p.id] = winner
        }
        return byId.values.sortedWith(
            compareByDescending<DiscoveredPrinter> { AdapterPriority.score(it) }.thenBy { it.name },
        )
    }
}
