import type { PluginListenerHandle } from '@capacitor/core';

import type {
  ConnectOptions,
  DiscoverOptions,
  PrintImageOptions,
} from './core/options';
import type {
  DiscoveredPrinter,
  PrinterProfile,
  PrinterStatus,
  PrintResult,
} from './core/models';

/** Payload de l'event émis pendant un scan quand une imprimante est trouvée. */
export interface PrinterFoundEvent {
  printer: DiscoveredPrinter;
}

/** Payload de l'event de fin de découverte. */
export interface DiscoveryCompleteEvent {
  printers: DiscoveredPrinter[];
  /** Sources ayant échoué (ex: SDK manquant), pour diagnostic non bloquant. */
  failedSources?: string[];
}

/** Payload de changement de statut (monitoring, Phase 6). */
export interface StatusChangeEvent {
  status: PrinterStatus;
}

export type ThermalPrinterEvent =
  | 'printerFound'
  | 'discoveryComplete'
  | 'statusChange';

/** Entrée du journal de diagnostic (ring-buffer natif). */
export interface DebugLogEntry {
  ts: number;
  category: string;
  message: string;
  [key: string]: unknown;
}

/**
 * API native exposée par le plugin. C'est le contrat strict implémenté
 * par Android (Kotlin), iOS (Swift) et le fallback Web.
 *
 * NB: l'objet exporté par `index.ts` enrichit ce contrat avec des helpers
 * ergonomiques (overloads, conversions d'erreur), mais la surface native
 * reste celle-ci.
 */
export interface ThermalPrinterPlugin {
  /**
   * Lance une découverte agrégée multi-sources et renvoie la liste
   * dédoublonnée et normalisée à la fin du scan.
   * Émet aussi `printerFound` au fil de l'eau si `emitPartialResults`.
   */
  discoverPrinters(options?: DiscoverOptions): Promise<{ printers: DiscoveredPrinter[] }>;

  /** Ouvre explicitement une connexion vers une imprimante connue/découverte. */
  connectPrinter(options: ConnectOptions): Promise<{ connected: boolean }>;

  /** Ferme la connexion active (sans supprimer le profil). */
  disconnectPrinter(options: { printerId: string }): Promise<void>;

  /**
   * Enregistre une imprimante comme imprimante par défaut et persiste son profil.
   * À appeler après un test d'impression réussi.
   */
  setDefaultPrinter(options: { printerId: string }): Promise<{ profile: PrinterProfile }>;

  /** Retourne le profil par défaut, ou null si aucun. */
  getDefaultPrinter(): Promise<{ profile: PrinterProfile | null }>;

  /** Liste tous les profils enregistrés (persistés). */
  getSavedPrinters(): Promise<{ profiles: PrinterProfile[] }>;

  /** Supprime un profil enregistré (oubli total). */
  removePrinter(options: { printerId: string }): Promise<void>;

  /**
   * Imprime une image. Gère reconnexion auto, redimensionnement, binarisation,
   * dithering, conversion adapter et envoi. Voir flux détaillé dans le README.
   */
  printImage(options: PrintImageOptions): Promise<PrintResult>;

  /** Lit le statut temps réel (si supporté par l'adapter/transport). */
  getPrinterStatus(options: { printerId?: string }): Promise<PrinterStatus>;

  /** Demande/vérifie les permissions natives nécessaires (Bluetooth, etc.). */
  requestPermissions(): Promise<PermissionStatus>;
  checkPermissions(): Promise<PermissionStatus>;

  /** Active/désactive le monitoring de statut en arrière-plan pour une imprimante. */
  startStatusMonitor(options: { printerId: string; intervalMs?: number }): Promise<void>;
  stopStatusMonitor(options: { printerId: string }): Promise<void>;

  /** Récupère le journal de diagnostic en mémoire (support client). */
  getDebugLog(): Promise<{ log: DebugLogEntry[] }>;

  addListener(
    eventName: 'printerFound',
    listener: (event: PrinterFoundEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'discoveryComplete',
    listener: (event: DiscoveryCompleteEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'statusChange',
    listener: (event: StatusChangeEvent) => void,
  ): Promise<PluginListenerHandle>;

  removeAllListeners(): Promise<void>;
}

/** État des permissions natives liées au transport. */
export interface PermissionStatus {
  bluetooth: PermissionState;
  bluetoothScan: PermissionState;
  bluetoothConnect: PermissionState;
  location: PermissionState; // requis pour le scan BLE sur anciens Android
  localNetwork: PermissionState; // iOS Local Network usage
}

export type PermissionState = 'granted' | 'denied' | 'prompt' | 'prompt-with-rationale' | 'unavailable';
