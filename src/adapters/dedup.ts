import type { PrinterTransport } from '../core/enums';
import type { DiscoveredPrinter } from '../core/models';

import { isSdkAdapter } from './priority';

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
      byId.set(printer.id, { ...printer, isSdk: isSdkAdapter(printer.adapter), discoveredBy: dedupeSources(printer) });
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
      isSdk: isSdkAdapter(winner.adapter),
      // capacités: union (on garde le plus d'infos possible)
      capabilities: { ...existing.capabilities, ...printer.capabilities, ...winner.capabilities },
      lastSeenAt: Math.max(existing.lastSeenAt, printer.lastSeenAt),
      discoveredBy: mergedSources,
      isDefault: existing.isDefault || printer.isDefault,
      isConnected: existing.isConnected || printer.isConnected,
    });
  }

  return collapseSdkDuplicates(Array.from(byId.values()));
}

/**
 * 2ᵉ passe de fusion : une même imprimante physique peut être remontée à la
 * fois par son SDK fabricant ET par une source native générique sous un `id`
 * différent (transport/adresse distincts) — typiquement une Epson visible aussi
 * en Bluetooth classique. Dans ce cas on ne veut PAS deux entrées : on garde
 * l'entrée SDK (priorité produit) et on y fusionne la source native.
 *
 * Critère de rapprochement demandé : même nom OU même adresse normalisée.
 * On ne fusionne que du natif VERS du SDK (jamais SDK↔SDK ni natif↔natif) afin
 * de ne pas masquer par erreur deux imprimantes distinctes de même modèle.
 */
function collapseSdkDuplicates(list: DiscoveredPrinter[]): DiscoveredPrinter[] {
  const sdkEntries = list.filter((p) => p.isSdk);
  if (sdkEntries.length === 0) return list;

  const result: DiscoveredPrinter[] = [];
  for (const p of list) {
    if (p.isSdk) {
      result.push(p);
      continue;
    }
    const match = sdkEntries.find((s) => sameAddress(s.address, p.address) || sameName(s.name, p.name));
    if (match) {
      // Exception Zebra : on NE fusionne PAS le doublon natif (BLE/Classic). Une Zebra
      // peut être en `line_print` ou refuser le ZPL : on garde l'entrée native générique
      // comme chemin d'impression ESC/POS « normal »/de secours, EN PLUS de l'entrée SDK.
      if (match.adapter === 'zebra') {
        result.push(p);
        continue;
      }
      // Fusionner la source native dans l'entrée SDK conservée.
      match.discoveredBy = Array.from(new Set([...(match.discoveredBy ?? []), ...(p.discoveredBy ?? []), p.adapter]));
      match.isConnected = match.isConnected || p.isConnected;
      match.isDefault = match.isDefault || p.isDefault;
      continue; // on supprime le doublon natif
    }
    result.push(p);
  }
  return result;
}

/** Adresse comparable cross-transport : minuscule, port retiré pour les IPv4. */
function bareAddress(a: string): string {
  const s = a.trim().toLowerCase();
  return s.includes('.') ? s.replace(/:\d+$/, '') : s;
}

function sameAddress(a: string, b: string): boolean {
  const na = bareAddress(a);
  return na.length > 0 && na === bareAddress(b);
}

function sameName(a: string, b: string): boolean {
  const na = a.trim().toLowerCase();
  return na.length > 0 && na === b.trim().toLowerCase();
}

function dedupeSources(p: DiscoveredPrinter): typeof p.discoveredBy {
  const base = p.discoveredBy ?? [];
  return Array.from(new Set([...base, p.adapter]));
}
