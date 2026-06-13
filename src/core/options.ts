import type { PrinterAdapterId, PrinterTransport, PrintErrorCode } from './enums';

/**
 * Algorithme de tramage (dithering) appliqué lors de la conversion 1-bit.
 * - `none`             : seuillage simple (threshold) — idéal pour texte/ligne nettes.
 * - `floyd_steinberg`  : tramage par diffusion d'erreur — idéal pour logos/photos.
 * - `atkinson`         : variante plus contrastée, agréable sur tickets.
 */
export type DitheringAlgorithm = 'none' | 'floyd_steinberg' | 'atkinson';

/** Alignement horizontal de l'image si plus étroite que la zone imprimable. */
export type ImageAlign = 'left' | 'center' | 'right';

/**
 * Options de rendu image -> impression. Indépendantes du transport.
 */
export interface PrintRenderOptions {
  /**
   * Largeur cible en points (dots). Si omise, déduite du profil/capacités
   * (ex: 384 pour 58mm, 576 pour 80mm).
   */
  widthDots?: number;
  /**
   * Redimensionner l'image à la largeur cible. Défaut `true`.
   * Mettre `false` si l'image est DÉJÀ à la bonne largeur (rendu serveur) :
   * envoi tel quel, pixel-perfect, plus rapide.
   */
  resize?: boolean;
  /**
   * Conversion niveaux de gris + dithering. Défaut `true`.
   * Mettre `false` si l'image est DÉJÀ en 1-bit noir/blanc (pré-traitée serveur) :
   * un simple seuil est appliqué, sans dithering.
   */
  grayscale?: boolean;
  /** Seuil de binarisation 0-255 (défaut 128) quand dithering = 'none'. */
  threshold?: number;
  /** Algorithme de tramage. Défaut: 'floyd_steinberg'. */
  dithering?: DitheringAlgorithm;
  /** Alignement si l'image est plus étroite que la largeur imprimable. Défaut 'center'. */
  align?: ImageAlign;
  /** Inverser le noir et blanc (impression en négatif). */
  invert?: boolean;
  /** Couper le papier après impression (si supportsCut). Défaut true. */
  cut?: boolean;
  /** Nombre de lignes de feed avant la coupe. Défaut 3. */
  feedLines?: number;
  /** Ouvrir le tiroir-caisse après impression (si supportsCashDrawer). Défaut false. */
  openCashDrawer?: boolean;
  /** Nombre de copies. Défaut 1. */
  copies?: number;
}

/**
 * Source de l'image à imprimer. Une seule des trois clés doit être renseignée.
 * Ordre de préférence en production : `filePath` > `url` > `base64`.
 */
export interface ImageSource {
  /** Chemin local du fichier (file:// ou chemin absolu). MODE RECOMMANDÉ. */
  filePath?: string;
  /** URL distante. Le plugin télécharge (avec cache). */
  url?: string;
  /** Données base64 (avec ou sans préfixe data:). Pratique en test. */
  base64?: string;
}

/**
 * Options de l'appel printImage.
 */
export interface PrintImageOptions {
  /** Imprimante cible. Si omis, utilise l'imprimante par défaut. */
  printerId?: string;
  /** Source image. */
  image: ImageSource;
  /** Options de rendu (fusionnées avec les defaults du profil). */
  render?: PrintRenderOptions;
  /** Timeout global de l'opération en ms (reconnexion + envoi). Défaut 15000. */
  timeoutMs?: number;
  /**
   * Si true, tente une reconnexion automatique si l'imprimante n'est pas connectée.
   * Défaut true.
   */
  autoReconnect?: boolean;
}

/**
 * Options de découverte agrégée.
 */
export interface DiscoverOptions {
  /**
   * Sous-ensemble de sources à activer. Si omis, toutes les sources disponibles
   * sur la plateforme sont lancées en parallèle.
   */
  sources?: DiscoverySource[];
  /** Durée max de scan en ms (le scan BT/BLE est borné par ce délai). Défaut 8000. */
  timeoutMs?: number;
  /** Inclure les appareils Bluetooth déjà appairés (Android). Défaut true. */
  includePaired?: boolean;
  /**
   * Plage/segment réseau à scanner pour TCP 9100 (ex: "192.168.1.0/24").
   * Si omis, déduit du réseau courant.
   */
  networkCidr?: string;
  /** Ports TCP à sonder en plus de 9100 (ex: [9100, 6101, 515]). */
  tcpPorts?: number[];
  /** Émettre des résultats incrémentaux via l'event 'printerFound'. Défaut true. */
  emitPartialResults?: boolean;
}

/** Sources de découverte activables. */
export type DiscoverySource =
  | 'epson'
  | 'star'
  | 'brother'
  | 'zebra'
  | 'tcp'
  | 'bluetooth'
  | 'ble'
  | 'usb';

/**
 * Options de l'appel printText.
 */
export interface PrintTextOptions {
  /** Imprimante cible. Si omis, utilise l'imprimante par défaut. */
  printerId?: string;
  /** Liste ordonnée d'items à imprimer (texte stylé, QR, code-barres, feed, cut...). */
  items: import('./text').PrintItem[];
  /** Page de code par défaut pour les accents (français : 'WPC1252'). */
  defaultCodePage?: import('./text').CodePage;
  /** Couper le papier en fin de job (si non géré par un item `cut`). Défaut false. */
  cut?: boolean;
  /** Lignes d'avance en fin de job avant la coupe. Défaut 3. */
  feedLines?: number;
  /** Timeout global de l'opération en ms. Défaut 15000. */
  timeoutMs?: number;
  /** Reconnexion automatique si non connecté. Défaut true. */
  autoReconnect?: boolean;
}

/**
 * Options de connexion explicite.
 */
export interface ConnectOptions {
  printerId: string;
  /** Timeout de connexion ms. Défaut 10000. */
  timeoutMs?: number;
  /** Forcer un adapter (sinon: celui résolu à la découverte / dans le profil). */
  forceAdapter?: PrinterAdapterId;
  /**
   * Si `true`, enregistre cette imprimante comme imprimante par défaut
   * UNIQUEMENT si la connexion réussit (persistance du profil incluse).
   * Défaut `false`.
   */
  setAsDefault?: boolean;
}

/**
 * Erreur normalisée transportée dans les rejets de promesse.
 * Côté JS elle est exposée via PrinterError (classe).
 */
export interface NormalizedErrorShape {
  code: PrintErrorCode;
  message: string;
  /** Détail brut (message SDK / exception native). */
  detail?: string;
  /** Transport concerné si pertinent. */
  transport?: PrinterTransport;
  /** Adapter concerné si pertinent. */
  adapter?: PrinterAdapterId;
  /** Indique si une nouvelle tentative a du sens. */
  retryable?: boolean;
}
