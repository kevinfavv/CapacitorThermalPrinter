package com.resto.thermalprinter.discovery

import com.resto.thermalprinter.model.AdapterId
import com.resto.thermalprinter.model.DiscoveredPrinter
import com.resto.thermalprinter.model.Transport

/**
 * Moteur de priorité d'adapter (miroir Kotlin de src/adapters/priority.ts).
 *
 * Règles :
 *   1. SDK officiel reconnaissant l'imprimante -> priorité max.
 *   2. Zebra -> ZebraAdapter UNIQUEMENT (escpos/rawTcp bannis).
 *   3. ESC/POS confirmé > BLE > rawTcp.
 */
object AdapterPriority {

    fun score(p: DiscoveredPrinter): Int {
        val brand = p.brand?.lowercase() ?: ""
        val isZebra = brand.contains("zebra") || p.adapter == AdapterId.ZEBRA
        if (isZebra) return if (p.adapter == AdapterId.ZEBRA) 1000 else -1000

        val fromVendorSdk = p.adapter in setOf(AdapterId.EPSON, AdapterId.STAR, AdapterId.BROTHER)
        if (fromVendorSdk) {
            return when (p.adapter) {
                AdapterId.EPSON -> 900
                AdapterId.STAR -> 890
                AdapterId.BROTHER -> 880
                else -> 850
            }
        }

        if (p.adapter == AdapterId.ESCPOS) {
            return if (p.transport == Transport.BLUETOOTH) 620 else 600
        }
        if (p.transport == Transport.BLE) return 500
        if (p.adapter == AdapterId.RAW_TCP) return 300
        return 100
    }
}
