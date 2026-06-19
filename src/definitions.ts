import type { PluginListenerHandle } from '@capacitor/core';

import type {
  ConnectOptions,
  DiscoverOptions,
  PrintImageOptions,
  PrintTextOptions,
} from './core/options';
import type { PrinterAdapterId, PrinterTransport } from './core/enums';
import type { PaperInfo } from './core/paper';
import type {
  DiscoveredPrinter,
  PrinterProfile,
  PrinterStatus,
  PrintJobStatus,
  PrintResult,
} from './core/models';

/**
 * État d'un adapter/SDK à l'instant présent (résultat de `getActiveSdks`).
 */
export interface SdkStatus {
  /** Identifiant d'adapter (marque ou intégré). */
  adapter: PrinterAdapterId;
  /** Libellé lisible (ex. "Star StarXpand"). */
  label: string;
  /**
   * `true` si le SDK/binaire est détecté et utilisable MAINTENANT.
   * Pour les adapters intégrés (escpos, rawTcp) : toujours `true`.
   * Pour les marques (star, epson, brother, zebra) : `true` si le SDK est lié.
   */
  available: boolean;
  /**
   * `true` si l'adapter dépend d'un SDK fabricant (à fournir), `false` s'il est
   * intégré au plugin (ESC/POS générique, TCP brut, BLE, USB).
   */
  requiresSdk: boolean;
  /** Transports gérés par cet adapter quand il est disponible. */
  transports: PrinterTransport[];
}

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

/** Payload de changement de statut imprimante (connexion, papier, capot). */
export interface StatusChangeEvent {
  status: PrinterStatus;
}

/** Payload de changement d'état d'un job d'impression. */
export interface PrintJobStatusEvent {
  job: PrintJobStatus;
}

export type ThermalPrinterEvent =
  | 'printerFound'
  | 'discoveryComplete'
  | 'statusChange'
  | 'printJobStatus';

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

  /**
   * Ouvre explicitement une connexion vers une imprimante connue/découverte.
   * Renvoie aussi la **taille de papier** déduite du modèle (best-effort), ou
   * `paper: null` si elle n'a pas pu être déterminée (à afficher à l'utilisateur).
   */
  connectPrinter(options: ConnectOptions): Promise<{ connected: boolean; paper: PaperInfo | null }>;

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
   *
   * La promesse se résout quand l'impression physique est terminée (best-effort
   * selon transport/SDK — voir README "Fin d'impression / await").
   */
  printImage(options: PrintImageOptions): Promise<PrintResult>;

  /**
   * Imprime un tableau d'items texte stylés (+ QR/code-barres/feed/cut/image).
   * Voir les types `PrintItem` / `TextStyle` et le tableau de styles dans le README.
   *
   * La promesse se résout quand l'impression physique est terminée (best-effort).
   */
  printText(options: PrintTextOptions): Promise<PrintResult>;

  /** Lit le statut temps réel (si supporté par l'adapter/transport). */
  getPrinterStatus(options: { printerId?: string }): Promise<PrinterStatus>;

  /** Demande/vérifie les permissions natives nécessaires (Bluetooth, etc.). */
  requestPermissions(): Promise<PermissionStatus>;
  checkPermissions(): Promise<PermissionStatus>;

  /**
   * Indique si l'adaptateur Bluetooth de l'appareil est actuellement **activé**.
   * - Android : état réel de `BluetoothAdapter` (allumé/éteint).
   * - iOS     : état CoreBluetooth (`poweredOn`).
   * - Web     : toujours `false`.
   *
   * Distinct des permissions (`checkPermissions`) : une permission accordée
   * n'implique pas que l'adaptateur soit allumé.
   */
  isBluetoothEnabled(): Promise<{ enabled: boolean }>;

  /** Active/désactive le monitoring de statut en arrière-plan pour une imprimante. */
  startStatusMonitor(options: { printerId: string; intervalMs?: number }): Promise<void>;
  stopStatusMonitor(options: { printerId: string }): Promise<void>;

  /**
   * Retourne l'état, à l'instant présent, de chaque adapter/SDK : quels SDK
   * fabricants sont actifs (binaire détecté) et quels adapters intégrés sont
   * disponibles. Utile pour un écran de diagnostic ou pour adapter l'UI.
   */
  getActiveSdks(): Promise<{ sdks: SdkStatus[] }>;

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
  addListener(
    eventName: 'printJobStatus',
    listener: (event: PrintJobStatusEvent) => void,
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
