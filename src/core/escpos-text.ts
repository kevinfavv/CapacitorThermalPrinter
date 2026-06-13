import {
  CODE_PAGE_TO_ESC_T,
  type BarcodeItem,
  type BarcodeSymbology,
  type CodePage,
  type PrintItem,
  type QrCodeItem,
  type TextStyle,
} from './text';

/**
 * ============================================================================
 *  ENCODEUR ESC/POS TEXTE — référence pure (testée), mirrorée en Kotlin/Swift.
 * ============================================================================
 *
 * Transforme un tableau de `PrintItem` en flux d'octets ESC/POS. Utilisé :
 *   - par les tests unitaires (vérification octet par octet),
 *   - comme spécification pour les implémentations natives,
 *   - directement par les adapters ESC/POS/rawTcp (via le miroir natif).
 *
 * Les items `image` ne sont PAS encodés ici (ils passent par le pipeline image
 * natif). L'encodeur émet un marqueur d'erreur si on lui en donne un.
 */

const ESC = 0x1b;
const GS = 0x1d;
const LF = 0x0a;

export interface EscPosTextOptions {
  /** Page de code par défaut (français : 'WPC1252'). */
  defaultCodePage?: CodePage;
  /** Nb de colonnes en police A (déduit largeur). Défaut 48 (80mm) / 32 (58mm). */
  columns?: number;
}

/** Concatène des segments d'octets. */
function concat(parts: number[][]): Uint8Array {
  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const p of parts) {
    out.set(p, off);
    off += p.length;
  }
  return out;
}

/**
 * Encode une chaîne vers une page de code mono-octet.
 * Pour WPC1252/Latin-1, le code-point Unicode == octet pour 0x00..0xFF (couvre FR).
 * Les caractères hors plage sont remplacés par '?'.
 */
export function encodeString(value: string): number[] {
  const bytes: number[] = [];
  for (const ch of value) {
    const cp = ch.codePointAt(0) ?? 0x3f;
    bytes.push(cp <= 0xff ? cp : 0x3f);
  }
  return bytes;
}

/** GS ! n : taille (largeur sur bits 4-6, hauteur sur bits 0-2). */
export function sizeByte(widthMultiplier = 1, heightMultiplier = 1): number {
  const w = Math.min(8, Math.max(1, widthMultiplier)) - 1;
  const h = Math.min(8, Math.max(1, heightMultiplier)) - 1;
  return (w << 4) | h;
}

/** Construit les commandes d'ouverture de style pour un item texte. */
export function openStyle(style: TextStyle = {}, opts: EscPosTextOptions = {}): number[] {
  const cmds: number[][] = [];

  // Page de code (accents)
  const cp = style.codePageId ?? CODE_PAGE_TO_ESC_T[style.codePage ?? opts.defaultCodePage ?? 'WPC1252'];
  cmds.push([ESC, 0x74, cp & 0xff]); // ESC t n

  // Alignement (ESC a n)
  const align = style.align === 'center' ? 1 : style.align === 'right' ? 2 : 0;
  cmds.push([ESC, 0x61, align]);

  // Police (ESC M n)
  cmds.push([ESC, 0x4d, style.font === 'B' ? 1 : 0]);

  // Gras (ESC E n)
  cmds.push([ESC, 0x45, style.bold ? 1 : 0]);

  // Double frappe (ESC G n)
  cmds.push([ESC, 0x47, style.doubleStrike ? 1 : 0]);

  // Soulignement (ESC - n) : 0/1/2
  const ul = style.underline === 'single' ? 1 : style.underline === 'double' ? 2 : 0;
  cmds.push([ESC, 0x2d, ul]);

  // Inversion vidéo (GS B n)
  cmds.push([GS, 0x42, style.invert ? 1 : 0]);

  // Upside-down (ESC { n)
  cmds.push([ESC, 0x7b, style.upsideDown ? 1 : 0]);

  // Rotation 90° (ESC V n)
  cmds.push([ESC, 0x56, style.rotate90 ? 1 : 0]);

  // Taille (GS ! n)
  cmds.push([GS, 0x21, sizeByte(style.widthMultiplier, style.heightMultiplier)]);

  // Espacement caractères (ESC SP n)
  if (style.letterSpacing != null) cmds.push([ESC, 0x20, style.letterSpacing & 0xff]);

  // Interligne (ESC 3 n) ou défaut (ESC 2)
  if (style.lineSpacing != null) cmds.push([ESC, 0x33, style.lineSpacing & 0xff]);
  else cmds.push([ESC, 0x32]);

  return cmds.flat();
}

/** Réinitialise les styles transitoires après un item (ESC @ = reset complet). */
export function resetStyle(): number[] {
  return [ESC, 0x40];
}

// ---------------------------------------------------------------------------
// QR code (GS ( k)
// ---------------------------------------------------------------------------

export function encodeQrCode(item: QrCodeItem): number[] {
  const out: number[][] = [];
  const align = item.align === 'left' ? 0 : item.align === 'right' ? 2 : 1;
  out.push([ESC, 0x61, align]);

  // Modèle 2 : GS ( k 04 00 31 41 50 00
  out.push([GS, 0x28, 0x6b, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00]);
  // Taille module : GS ( k 03 00 31 43 n
  const size = Math.min(16, Math.max(1, item.size ?? 6));
  out.push([GS, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x43, size]);
  // Correction erreur : GS ( k 03 00 31 45 n (48=L,49=M,50=Q,51=H)
  const ecMap: Record<string, number> = { L: 48, M: 49, Q: 50, H: 51 };
  out.push([GS, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x45, ecMap[item.errorCorrection ?? 'M']]);
  // Stockage data : GS ( k pL pH 31 50 30 d...
  const data = encodeString(item.value);
  const len = data.length + 3;
  out.push([GS, 0x28, 0x6b, len & 0xff, (len >> 8) & 0xff, 0x31, 0x50, 0x30, ...data]);
  // Impression : GS ( k 03 00 31 51 30
  out.push([GS, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x51, 0x30]);
  return out.flat();
}

// ---------------------------------------------------------------------------
// Code-barres 1D (GS k, fonction B)
// ---------------------------------------------------------------------------

const BARCODE_M: Record<BarcodeSymbology, number> = {
  UPC_A: 65,
  UPC_E: 66,
  EAN13: 67,
  EAN8: 68,
  CODE39: 69,
  ITF: 70,
  CODABAR: 71,
  CODE93: 72,
  CODE128: 73,
};

export function encodeBarcode(item: BarcodeItem): number[] {
  const out: number[][] = [];
  const align = item.align === 'left' ? 0 : item.align === 'right' ? 2 : 1;
  out.push([ESC, 0x61, align]);

  // HRI position (GS H n) : 0 none,1 above,2 below,3 both
  const hri = item.hri === 'above' ? 1 : item.hri === 'both' ? 3 : item.hri === 'none' ? 0 : 2;
  out.push([GS, 0x48, hri]);
  // Hauteur (GS h n)
  out.push([GS, 0x68, Math.min(255, Math.max(1, item.height ?? 80))]);
  // Largeur module (GS w n)
  out.push([GS, 0x77, Math.min(6, Math.max(2, item.width ?? 3))]);

  const m = BARCODE_M[item.symbology];
  let data = encodeString(item.value);
  // CODE128 attend un préfixe de jeu de codes ({B par défaut) si absent.
  if (item.symbology === 'CODE128' && !(data[0] === 0x7b)) {
    data = [0x7b, 0x42, ...data]; // "{B"
  }
  // Fonction B : GS k m n d1..dn
  out.push([GS, 0x6b, m, data.length, ...data]);
  return out.flat();
}

// ---------------------------------------------------------------------------
// Encodeur principal
// ---------------------------------------------------------------------------

/** Sépare le flux en (octets, métadonnées images à rendre séparément). */
export interface EncodedJob {
  bytes: Uint8Array;
  /** Index des items `image` rencontrés (rendus par le pipeline natif). */
  imageItemIndexes: number[];
}

/**
 * Encode un tableau d'items. Les items `image` produisent un marqueur
 * (interruption) géré par le natif : l'encodeur retourne donc des "segments".
 * Pour la référence/tests, on encode tout SAUF image (signalé dans imageItemIndexes).
 */
export function encodeEscPosItems(items: PrintItem[], opts: EscPosTextOptions = {}): EncodedJob {
  const parts: number[][] = [resetStyle()]; // ESC @ initial
  const imageItemIndexes: number[] = [];
  const columns = opts.columns ?? 48;

  items.forEach((item, index) => {
    switch (item.type) {
      case 'text': {
        const style = item.style ?? {};
        parts.push(openStyle(style, opts));
        parts.push(encodeString(item.value));
        if (style.newline !== false) parts.push([LF]);
        parts.push(resetStyle());
        break;
      }
      case 'feed':
        parts.push([ESC, 0x64, Math.min(255, Math.max(0, item.lines ?? 1))]);
        break;
      case 'divider': {
        const ch = (item.char ?? '-').charCodeAt(0);
        const n = item.columns ?? columns;
        const align = item.style?.align === 'center' ? 1 : item.style?.align === 'right' ? 2 : 0;
        parts.push([ESC, 0x61, align]);
        if (item.style?.bold) parts.push([ESC, 0x45, 1]);
        parts.push(new Array(n).fill(ch));
        parts.push([LF]);
        parts.push(resetStyle());
        break;
      }
      case 'qrcode':
        parts.push(encodeQrCode(item));
        break;
      case 'barcode':
        parts.push(encodeBarcode(item));
        break;
      case 'cashDrawer':
        parts.push(item.pin === 5 ? [ESC, 0x70, 0x01, 0x19, 0xfa] : [ESC, 0x70, 0x00, 0x19, 0xfa]);
        break;
      case 'cut': {
        if (item.feedBefore) parts.push([ESC, 0x64, item.feedBefore & 0xff]);
        parts.push(item.mode === 'full' ? [GS, 0x56, 0x00] : [GS, 0x56, 0x01]);
        break;
      }
      case 'raw': {
        const bin = atobToBytes(item.bytesBase64);
        parts.push(Array.from(bin));
        break;
      }
      case 'image':
        imageItemIndexes.push(index);
        break;
    }
  });

  return { bytes: concat(parts), imageItemIndexes };
}

/** Décodage base64 -> octets, compatible Node et navigateur (sans dépendance). */
function atobToBytes(b64: string): Uint8Array {
  const clean = b64.includes('base64,') ? b64.split('base64,')[1] : b64;
  const decode =
    typeof atob === 'function'
      ? atob
      : (s: string): string => {
          const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
          let str = s.replace(/=+$/, '');
          let output = '';
          for (let bc = 0, bs = 0, buffer, i = 0; (buffer = str.charAt(i++)); ) {
            buffer = chars.indexOf(buffer);
            if (~buffer) {
              bs = bc % 4 ? bs * 64 + buffer : buffer;
              if (bc++ % 4) output += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6)));
            }
          }
          return output;
        };
  const bin = decode(clean);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
