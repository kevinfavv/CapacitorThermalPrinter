import Foundation

/// Moteur de priorité d'adapter (miroir iOS de priority.ts / AdapterPriority.kt).
enum AdapterPriority {
    static func score(_ p: DiscoveredPrinter) -> Int {
        let brand = (p.brand ?? "").lowercased()
        let isZebra = brand.contains("zebra") || p.adapter == .zebra
        if isZebra { return p.adapter == .zebra ? 1000 : -1000 }

        if p.adapter == .epson { return 900 }
        if p.adapter == .star { return 890 }
        if p.adapter == .brother { return 880 }

        if p.adapter == .escpos {
            return p.transport == .bluetooth ? 620 : 600
        }
        if p.transport == .ble { return 500 }
        if p.adapter == .rawTcp { return 300 }
        return 100
    }
}
