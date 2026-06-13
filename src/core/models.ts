import type {
  ConnectionState,
  PaperStatus,
  PrinterAdapterId,
  PrinterTransport,
  PrintErrorCode,
} from './enums';
import type { PrintRenderOptions } from './options';

/**
 * Capacités matérielles/logicielles d'une imprimante.
 * Renseignées au mieux à la découverte, complétées après un premier print réussi.
 */
export interface PrinterCapabilities {
  /** Largeur papier physique en millimètres (58, 80, 112...). */
  paperWidthMm: number;
  /** Nombre de points imprimables sur la largeur (ex: 384 pour 58mm, 576 pour 80mm @203dpi). */
  printableDots: number;
  /** Résolution en points par pouce (généralement 203). */
  dpi: number;
  /** L'imprimante possède un massicot. */
  supportsCut: boolean;
  /** L'imprimante peut piloter un tiroir-caisse. */
  supportsCashDrawer: boolean;
  /** L'imprimante remonte un statut temps réel (papier, capot, erreurs). */
  supportsStatus: boolean;
  /** L'imprimante accepte l'impression d'images raster. */
  supportsRasterImage: boolean;
  /** L'imprimante peut générer des QR codes nativement (sinon: imprimés via image). */
  supportsQrCode?: boolean;
  /** L'imprimante peut générer des codes-barres nativement. */
  supportsBarcode?: boolean;
}

/**
 * Imprimante telle que remontée par la découverte agrégée, après dédoublonnage.
 */
export interface DiscoveredPrinter {
  /** Identifiant interne stable construit par le plugin (voir buildStableId). */
  id: string;
  /** Nom lisible (nom Bluetooth, hostname, ou modèle SDK). */
  name: string;
  /** Marque détectée si connue ("Epson", "Star", "Generic", ...). */
  brand?: string;
  /** Modèle détecté si connu ("TM-m30", "mC-Print3", ...). */
  model?: string;
  /** Transport physique utilisé pour joindre l'imprimante. */
  transport: PrinterTransport;
  /** Adapter retenu par le moteur de priorité. */
  adapter: PrinterAdapterId;
  /**
   * Adresse de joignabilité, dépendante du transport :
   * - wifi/ethernet : "192.168.1.50:9100"
   * - bluetooth     : "AA:BB:CC:DD:EE:FF" (MAC)
   * - ble           : UUID périphérique (iOS) ou MAC (Android)
   * - usb           : "vendorId:productId" ou device name
   */
  address: string;
  /** Capacités connues (best effort à la découverte). */
  capabilities?: Partial<PrinterCapabilities>;
  /** Source(s) de découverte ayant remonté cette imprimante (pour debug / fusion). */
  discoveredBy?: PrinterAdapterId[];
  /** Timestamp (ms epoch) de dernière détection. */
  lastSeenAt: number;
  /** Vrai si c'est l'imprimante par défaut enregistrée. */
  isDefault: boolean;
  /** Vrai si le plugin a une connexion active ouverte. */
  isConnected: boolean;
}

/**
 * Profil persistant d'une imprimante enregistrée (sauvegardé après test réussi).
 * Contient tout le nécessaire pour la reconnexion automatique sans re-découverte.
 */
export interface PrinterProfile {
  id: string;
  adapter: PrinterAdapterId;
  transport: PrinterTransport;
  address: string;
  brand?: string;
  model?: string;
  name: string;
  capabilities: PrinterCapabilities;
  /** Options par défaut d'impression mémorisées pour cette imprimante. */
  defaultPrintOptions?: PrintRenderOptions;
  /** Données opaques propres à l'adapter pour reconnexion (ex: identifiant SDK). */
  adapterMeta?: Record<string, string | number | boolean>;
  isDefault: boolean;
  createdAt: number;
  updatedAt: number;
}

/**
 * Statut temps réel d'une imprimante.
 */
export interface PrinterStatus {
  id: string;
  connection: ConnectionState;
  online: boolean;
  paper: PaperStatus;
  coverOpen?: boolean;
  /** Erreur courante si l'imprimante est en faute. */
  errorCode?: PrintErrorCode;
  /** Message brut du SDK/transport (debug). */
  rawStatus?: string;
  checkedAt: number;
}

/**
 * Résultat normalisé d'une impression.
 */
export interface PrintResult {
  success: boolean;
  printerId: string;
  adapter: PrinterAdapterId;
  /** Nombre d'octets effectivement envoyés au transport. */
  bytesSent?: number;
  /** Durée totale (ms) de l'opération print (reconnexion incluse). */
  durationMs?: number;
  /** Statut imprimante lu après impression, si disponible. */
  status?: PrinterStatus;
}
