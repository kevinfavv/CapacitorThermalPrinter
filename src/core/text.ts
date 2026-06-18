import type { ImageSource, PrintRenderOptions } from './options';

/**
 * ============================================================================
 *  MODÈLE DE TEXTE STYLÉ — `printText([...])`
 * ============================================================================
 *
 * L'app envoie un TABLEAU d'items typés. Chaque item est rendu séquentiellement.
 * Le sous-ensemble de styles couvre TOUT ce que l'ESC/POS standard permet ; pour
 * les adapters SDK (Epson/Star/Brother), un mapping documenté est appliqué et les
 * styles non supportés sont ignorés proprement (jamais d'échec dur).
 *
 * Voir le tableau de correspondance complet dans le README (section "printText").
 */

/** Alignement horizontal. */
export type TextAlign = 'left' | 'center' | 'right';

/** Soulignement. */
export type Underline = 'none' | 'single' | 'double';

/** Police interne ESC/POS (A ≈ 12x24, B ≈ 9x17). */
export type EscPosFont = 'A' | 'B';

/**
 * Page de code MONO-OCTET pour les caractères latins/accentués. Le défaut `WPC1252`
 * (Windows-1252) couvre le français. La valeur ESC t exacte est résolue par
 * `CODE_PAGE_TO_ESC_T` (surclassable via `codePageId`).
 */
export type CodePage = 'CP437' | 'CP850' | 'CP858' | 'WPC1252' | 'CP852' | 'CP866';

/**
 * Encodages MULTI-OCTETS (CJK) : pour imprimer du chinois / japonais / coréen. Le plugin
 * sélectionne alors le mode idéogrammes de l'imprimante (FS &) et encode le texte dans le
 * charset natif. Géré côté natif (Android/iOS) ; non rendu par l'encodeur web de référence.
 */
export type CjkEncoding = 'GB18030' | 'GBK' | 'Shift_JIS' | 'EUC-KR' | 'Big5';

/**
 * Encodage du texte : page de code latine (défaut `WPC1252`, accents FR) OU charset CJK
 * (`GB18030`…) pour ne pas bloquer les langues idéographiques. Configurable par le dev au
 * niveau connexion (`ConnectOptions.encoding`), par appel (`PrintTextOptions.encoding`) ou
 * par item (`TextStyle.encoding`) ; le plus précis l'emporte. Défaut : `WPC1252`.
 */
export type TextEncoding = CodePage | CjkEncoding;

/** Symbologies code-barres ESC/POS (GS k). */
export type BarcodeSymbology =
  | 'UPC_A'
  | 'UPC_E'
  | 'EAN13'
  | 'EAN8'
  | 'CODE39'
  | 'ITF'
  | 'CODABAR'
  | 'CODE93'
  | 'CODE128';

/** Position du texte lisible (HRI) d'un code-barres. */
export type HriPosition = 'none' | 'above' | 'below' | 'both';

/** Niveau de correction d'erreur QR. */
export type QrErrorCorrection = 'L' | 'M' | 'Q' | 'H';

/**
 * Style applicable à un item `text`. Tous les champs sont optionnels ;
 * chaque imprimante repart de l'état par défaut (ESC @) entre les jobs.
 */
export interface TextStyle {
  align?: TextAlign;
  /** Gras / emphase (ESC E). */
  bold?: boolean;
  /** Soulignement (ESC - n). */
  underline?: Underline;
  /** Police interne (ESC M). */
  font?: EscPosFont;
  /** Multiplicateur de largeur 1..8 (GS ! partie haute). */
  widthMultiplier?: number;
  /** Multiplicateur de hauteur 1..8 (GS ! partie basse). */
  heightMultiplier?: number;
  /** Double frappe (rendu plus dense) (ESC G). */
  doubleStrike?: boolean;
  /** Vidéo inverse : blanc sur noir (GS B). */
  invert?: boolean;
  /** Texte à l'envers (ESC { ). */
  upsideDown?: boolean;
  /** Rotation 90° dans le sens horaire (ESC V). */
  rotate90?: boolean;
  /** Espacement inter-caractères en points (ESC SP n). */
  letterSpacing?: number;
  /** Interligne en points (ESC 3 n). `undefined` = interligne par défaut (ESC 2). */
  lineSpacing?: number;
  /**
   * Encodage du texte pour CET item : page de code latine (`CP437`, `WPC1252`…) ou charset
   * CJK (`GB18030`…). Prioritaire sur l'encodage du job/de la connexion. Défaut hérité.
   */
  encoding?: TextEncoding;
  /** @deprecated Alias historique de `encoding` (mono-octet uniquement). */
  codePage?: CodePage;
  /** Override numérique brut de la commande ESC t (priorité sur encoding ; latin uniquement). */
  codePageId?: number;
  /** Ajoute un saut de ligne (LF) après le texte. Défaut `true`. */
  newline?: boolean;
}

/** Item: texte stylé. */
export interface TextItem {
  type: 'text';
  value: string;
  style?: TextStyle;
}

/** Item: avance papier de N lignes. */
export interface FeedItem {
  type: 'feed';
  lines?: number; // défaut 1
}

/** Item: coupe papier. */
export interface CutItem {
  type: 'cut';
  mode?: 'full' | 'partial'; // défaut 'partial'
  /** Lignes d'avance avant la coupe. Défaut 0. */
  feedBefore?: number;
}

/** Item: ligne de séparation pleine largeur (répétition d'un caractère). */
export interface DividerItem {
  type: 'divider';
  /** Caractère répété. Défaut '-'. */
  char?: string;
  /** Nombre de colonnes ; si omis, déduit de la largeur papier/police. */
  columns?: number;
  style?: Pick<TextStyle, 'align' | 'bold' | 'font'>;
}

/** Item: QR code natif (GS ( k). */
export interface QrCodeItem {
  type: 'qrcode';
  value: string;
  /** Taille du module 1..16. Défaut 6. */
  size?: number;
  errorCorrection?: QrErrorCorrection; // défaut 'M'
  align?: TextAlign; // défaut 'center'
}

/** Item: code-barres 1D natif (GS k). */
export interface BarcodeItem {
  type: 'barcode';
  value: string;
  symbology: BarcodeSymbology;
  /** Hauteur en points. Défaut 80. */
  height?: number;
  /** Largeur du module 2..6. Défaut 3. */
  width?: number;
  hri?: HriPosition; // défaut 'below'
  align?: TextAlign; // défaut 'center'
}

/** Item: ouverture tiroir-caisse. */
export interface CashDrawerItem {
  type: 'cashDrawer';
  pin?: 2 | 5; // défaut 2
}

/** Item: image intercalée dans le flux texte (réutilise le pipeline image). */
export interface ImageItem {
  type: 'image';
  image: ImageSource;
  render?: PrintRenderOptions;
}

/** Item: octets bruts (échappatoire avancée) — base64. */
export interface RawItem {
  type: 'raw';
  bytesBase64: string;
}

/** Union discriminée de tous les items imprimables. */
export type PrintItem =
  | TextItem
  | FeedItem
  | CutItem
  | DividerItem
  | QrCodeItem
  | BarcodeItem
  | CashDrawerItem
  | ImageItem
  | RawItem;

/**
 * Correspondance page de code -> argument de la commande ESC t (n).
 * Ces valeurs sont les plus répandues ; certaines imprimantes diffèrent
 * (override possible via `TextStyle.codePageId`).
 */
export const CODE_PAGE_TO_ESC_T: Record<CodePage, number> = {
  CP437: 0,
  CP850: 2,
  CP858: 19,
  WPC1252: 16,
  CP852: 18,
  CP866: 17,
};
