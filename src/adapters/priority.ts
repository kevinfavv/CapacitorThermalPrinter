import type { PrinterAdapterId, PrinterTransport } from '../core/enums';

/** SDK officiels fabricants (priment sur l'intégration générique native). */
const SDK_ADAPTERS: readonly PrinterAdapterId[] = ['epson', 'star', 'brother', 'zebra'];

/** Vrai si l'adapter correspond à un SDK officiel fabricant (et non au natif générique). */
export function isSdkAdapter(adapter: PrinterAdapterId): boolean {
  return SDK_ADAPTERS.includes(adapter);
}

/**
 * Indice de découverte brut avant résolution d'adapter.
 * Chaque source de découverte produit un ou plusieurs `AdapterCandidate`.
 */
export interface AdapterCandidate {
  adapter: PrinterAdapterId;
  transport: PrinterTransport;
  /** Marque détectée par la source (sert à arbitrer). */
  brand?: string;
  /** La source est un SDK officiel du fabricant (Epson/Star/Brother/Zebra). */
  fromVendorSdk: boolean;
  /** L'imprimante a répondu à un handshake ESC/POS (statut DLE EOT, etc.). */
  escposVerified?: boolean;
}

/**
 * Score de priorité d'adapter. Plus le score est élevé, plus l'adapter est préféré.
 *
 * Règles produit demandées :
 *  1. Un SDK officiel reconnaissant l'imprimante prime sur tout.
 *  2. Zebra => ZebraAdapter, JAMAIS escpos par défaut (langage ZPL/CPCL).
 *  3. Wi-Fi/TCP + ESC/POS confirmé => escpos ; sinon rawTcp en dernier recours.
 *  4. Bluetooth classique Android + ESC/POS => escpos.
 */
export function scoreCandidate(c: AdapterCandidate): number {
  // Zebra est un cas spécial : son langage n'est pas ESC/POS.
  const isZebra = c.brand?.toLowerCase().includes('zebra') || c.adapter === 'zebra';
  if (isZebra) {
    return c.adapter === 'zebra' ? 1000 : -1000; // bannir escpos/rawTcp pour Zebra
  }

  // SDK officiel = priorité maximale (statut, coupe, reconnexion gérés nativement).
  if (c.fromVendorSdk) {
    switch (c.adapter) {
      case 'epson':
        return 900;
      case 'star':
        return 890;
      case 'brother':
        return 880;
      default:
        return 850;
    }
  }

  // ESC/POS générique confirmé.
  if (c.adapter === 'escpos') {
    // BT classic vérifié et TCP vérifié sont très fiables.
    if (c.escposVerified) return 700;
    // ESC/POS non vérifié mais transport exploitable.
    return c.transport === 'bluetooth' ? 620 : 600;
  }

  // BLE générique : exploitable seulement si service GATT connu.
  if (c.transport === 'ble') return 500;

  // rawTcp : filet de sécurité réseau, on tente l'ESC/POS brut.
  if (c.adapter === 'rawTcp') return 300;

  return 100;
}

/**
 * Résout le meilleur adapter parmi plusieurs candidats pour une même imprimante.
 */
export function resolveBestAdapter(candidates: AdapterCandidate[]): AdapterCandidate {
  if (candidates.length === 0) {
    throw new Error('resolveBestAdapter: aucun candidat');
  }
  return [...candidates].sort((a, b) => scoreCandidate(b) - scoreCandidate(a))[0];
}
