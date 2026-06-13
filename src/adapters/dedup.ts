import type { PrinterTransport } from '../core/enums';
import type { DiscoveredPrinter } from '../core/models';

/**
 * Construit un identifiant interne stable pour une imprimante.
 *
 * On veut le MÊME id quelle que soit la source de découverte afin de fusionner
 * les doublons (ex: une TM-m30 vue à la fois par le SDK Epson et par le scan TCP).
 *
 * Clé de stabilité par transport :
 *  - réseau (wifi/ethernet) : adresse IP normalisée (sans port) — l'imprimante a
 *    une seule IP même si plusieurs ports répondent.
 *  - bluetooth/ble          : adresse MAC (Android) ou UUID périphérique (iOS).
 *  - usb                    : vendorId:productId.
 *
 * Le préfixe transport évite les collisions improbables entre familles.
 */
export function buildStableId(transport: PrinterTransport, rawAddress: string): string {
  const norm = normalizeAddress(transport, rawAddress);
  return `${transport}:${norm}`;
}

function normalizeAddress(transport: PrinterTransport, address: string): string {
  switch (transport) {
    case 'wifi':
    case 'ethernet':
      // retire un éventuel :port
      return address.replace(/:\d+$/, '').trim().toLowerCase();
    case 'bluetooth':
    case 'ble':
      return address.trim().toUpperCase();
    case 'usb':
      return address.trim().toLowerCase();
    default:
      return address.trim().toLowerCase();
  }
}

/**
 * Fusionne une liste de découvertes brutes en une liste dédoublonnée.
 * En cas de doublon (même id), on conserve l'entrée au meilleur adapter
 * (déjà résolu par `resolveBestAdapter`) et on fusionne `discoveredBy`.
 *
 * @param incoming découvertes brutes (chaque source peut produire des doublons)
 * @param adapterRank fonction de classement (score) déjà appliquée au champ adapter
 */
export function mergeDiscoveries(
  incoming: DiscoveredPrinter[],
  adapterRank: (p: DiscoveredPrinter) => number,
): DiscoveredPrinter[] {
  const byId = new Map<string, DiscoveredPrinter>();

  for (const printer of incoming) {
    const existing = byId.get(printer.id);
    if (!existing) {
      byId.set(printer.id, { ...printer, discoveredBy: dedupeSources(printer) });
      continue;
    }

    // Fusionner les sources de découverte.
    const mergedSources = Array.from(
      new Set([...(existing.discoveredBy ?? []), ...(printer.discoveredBy ?? []), printer.adapter, existing.adapter]),
    );

    // Garder l'entrée au meilleur adapter.
    const winner = adapterRank(printer) > adapterRank(existing) ? printer : existing;
    byId.set(printer.id, {
      ...winner,
      // capacités: union (on garde le plus d'infos possible)
      capabilities: { ...existing.capabilities, ...printer.capabilities, ...winner.capabilities },
      lastSeenAt: Math.max(existing.lastSeenAt, printer.lastSeenAt),
      discoveredBy: mergedSources,
      isDefault: existing.isDefault || printer.isDefault,
      isConnected: existing.isConnected || printer.isConnected,
    });
  }

  return Array.from(byId.values());
}

function dedupeSources(p: DiscoveredPrinter): typeof p.discoveredBy {
  const base = p.discoveredBy ?? [];
  return Array.from(new Set([...base, p.adapter]));
}
