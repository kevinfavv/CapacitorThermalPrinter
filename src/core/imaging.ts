/**
 * Stratégie de largeur d'impression et utilitaires raster ESC/POS.
 *
 * Ce module est une référence TypeScript PURE (sans dépendance native) :
 *   - documente le mapping largeur papier (mm) -> points imprimables,
 *   - fournit un encodeur raster GS v 0 testable,
 *   - sert de spécification que les implémentations Android/iOS doivent suivre.
 *
 * Le rendu réel (decode PNG, resize, dithering) est fait en natif pour la perf,
 * mais l'algorithme de tramage et le format raster sont identiques.
 */

/**
 * Mapping largeur papier -> points imprimables @203 dpi (8 dots/mm).
 *
 * Valeurs DE RÉFÉRENCE (les plus courantes), à confirmer via le SDK/profil :
 *   - 58 mm  : 384 dots  (zone imprimable ~48mm)
 *   - 80 mm  : 576 dots  (zone imprimable ~72mm)
 *   - 80 mm  : 640 dots  sur certains modèles (zone imprimable ~80mm) -> override profil
 *   - 112 mm : 832 dots
 *
 * Recommandation : TOUJOURS préférer la valeur `printableDots` du profil/SDK
 * quand elle est connue. Ce mapping n'est qu'un défaut sûr.
 */
export const PAPER_WIDTH_PRESETS: Record<number, number> = {
  58: 384,
  80: 576,
  112: 832,
};

/** Déduit la largeur en points à partir de la largeur papier (fallback 80mm). */
export function dotsForPaperWidth(paperWidthMm: number): number {
  return PAPER_WIDTH_PRESETS[paperWidthMm] ?? PAPER_WIDTH_PRESETS[80];
}

/**
 * Représentation 1-bit d'une image : un tableau de pixels noir/blanc.
 * `data[i] = 1` => point ENCRE (noir). width et height en pixels.
 */
export interface MonoBitmap {
  width: number;
  height: number;
  /** 1 octet par pixel pour la lisibilité (0 = blanc, 1 = noir). */
  data: Uint8Array;
}

/**
 * Binarisation par seuil simple. À partir d'une image en niveaux de gris (0-255,
 * 0 = noir), produit un MonoBitmap. `threshold` par défaut 128.
 */
export function thresholdToMono(gray: Uint8Array, width: number, height: number, threshold = 128): MonoBitmap {
  const out = new Uint8Array(width * height);
  for (let i = 0; i < out.length; i++) {
    out[i] = gray[i] < threshold ? 1 : 0;
  }
  return { width, height, data: out };
}

/**
 * Tramage Floyd–Steinberg (diffusion d'erreur). Recommandé pour logos/photos.
 * Mute une copie du buffer gris. Retourne un MonoBitmap.
 */
export function floydSteinbergToMono(grayInput: Uint8Array, width: number, height: number): MonoBitmap {
  const gray = Float32Array.from(grayInput);
  const out = new Uint8Array(width * height);
  const at = (x: number, y: number) => y * width + x;

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const idx = at(x, y);
      const oldPixel = gray[idx];
      const newPixel = oldPixel < 128 ? 0 : 255;
      out[idx] = newPixel === 0 ? 1 : 0;
      const err = oldPixel - newPixel;

      if (x + 1 < width) gray[at(x + 1, y)] += (err * 7) / 16;
      if (x - 1 >= 0 && y + 1 < height) gray[at(x - 1, y + 1)] += (err * 3) / 16;
      if (y + 1 < height) gray[at(x, y + 1)] += (err * 5) / 16;
      if (x + 1 < width && y + 1 < height) gray[at(x + 1, y + 1)] += (err * 1) / 16;
    }
  }
  return { width, height, data: out };
}

/**
 * Tramage Atkinson (diffuse 6/8 de l'erreur). Rendu plus contrasté, agréable
 * sur tickets de caisse.
 */
export function atkinsonToMono(grayInput: Uint8Array, width: number, height: number): MonoBitmap {
  const gray = Float32Array.from(grayInput);
  const out = new Uint8Array(width * height);
  const at = (x: number, y: number) => y * width + x;
  const spread = (x: number, y: number, e: number) => {
    if (x >= 0 && x < width && y >= 0 && y < height) gray[at(x, y)] += e;
  };

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const idx = at(x, y);
      const oldPixel = gray[idx];
      const newPixel = oldPixel < 128 ? 0 : 255;
      out[idx] = newPixel === 0 ? 1 : 0;
      const err = (oldPixel - newPixel) / 8;
      spread(x + 1, y, err);
      spread(x + 2, y, err);
      spread(x - 1, y + 1, err);
      spread(x, y + 1, err);
      spread(x + 1, y + 1, err);
      spread(x, y + 2, err);
    }
  }
  return { width, height, data: out };
}

/**
 * Encode un MonoBitmap au format raster ESC/POS `GS v 0`.
 *
 * Format de la commande (mode normal m=0) :
 *   GS 'v' '0' m xL xH yL yH d1...dk
 *   - GS = 0x1D, 'v' = 0x76, '0' = 0x30
 *   - m  = 0 (normal) | 1 (double largeur) | 2 (double hauteur) | 3 (quad)
 *   - xL,xH = nombre d'OCTETS par ligne (width/8) en little-endian
 *   - yL,yH = nombre de LIGNES (height) en little-endian
 *   - data  = octets bitmap, bit de poids fort = pixel le plus à gauche
 *
 * IMPORTANT : la largeur DOIT être paddée à un multiple de 8 pixels.
 */
export function encodeEscPosRaster(mono: MonoBitmap, mode: 0 | 1 | 2 | 3 = 0): Uint8Array {
  const { width, height, data } = mono;
  const bytesPerRow = Math.ceil(width / 8);
  const xL = bytesPerRow & 0xff;
  const xH = (bytesPerRow >> 8) & 0xff;
  const yL = height & 0xff;
  const yH = (height >> 8) & 0xff;

  const header = new Uint8Array([0x1d, 0x76, 0x30, mode, xL, xH, yL, yH]);
  const body = new Uint8Array(bytesPerRow * height);

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (data[y * width + x] === 1) {
        const byteIndex = y * bytesPerRow + (x >> 3);
        const bit = 7 - (x & 7);
        body[byteIndex] |= 1 << bit;
      }
    }
  }

  const out = new Uint8Array(header.length + body.length);
  out.set(header, 0);
  out.set(body, header.length);
  return out;
}

/** Commandes ESC/POS de base (init, feed, cut, drawer). */
export const EscPos = {
  INIT: new Uint8Array([0x1b, 0x40]), // ESC @
  feed(lines: number): Uint8Array {
    // ESC d n
    return new Uint8Array([0x1b, 0x64, Math.max(0, Math.min(255, lines))]);
  },
  alignLeft: new Uint8Array([0x1b, 0x61, 0x00]),
  alignCenter: new Uint8Array([0x1b, 0x61, 0x01]),
  alignRight: new Uint8Array([0x1b, 0x61, 0x02]),
  cutFull: new Uint8Array([0x1d, 0x56, 0x00]), // GS V 0
  cutPartial: new Uint8Array([0x1d, 0x56, 0x01]), // GS V 1
  // GS V 66 n : feed + partial cut
  cutFeed(n = 3): Uint8Array {
    return new Uint8Array([0x1d, 0x56, 0x42, n]);
  },
  openDrawerPin2: new Uint8Array([0x1b, 0x70, 0x00, 0x19, 0xfa]), // ESC p 0
  openDrawerPin5: new Uint8Array([0x1b, 0x70, 0x01, 0x19, 0xfa]), // ESC p 1
  /** Statut temps réel : DLE EOT n (1=printer,2=offline,3=error,4=paper). */
  realtimeStatus(n: 1 | 2 | 3 | 4): Uint8Array {
    return new Uint8Array([0x10, 0x04, n]);
  },
};

/**
 * Assemble un job ESC/POS complet (init + align + raster + feed + cut + drawer).
 * `chunkRowsForLargeImage` : sur de très grandes images, certaines imprimantes
 * BT/série préfèrent recevoir le raster en bandes pour éviter les overflows de buffer.
 */
export function buildEscPosImageJob(
  mono: MonoBitmap,
  opts: {
    align?: 'left' | 'center' | 'right';
    cut?: boolean;
    feedLines?: number;
    openCashDrawer?: boolean;
  } = {},
): Uint8Array {
  const { align = 'center', cut = true, feedLines = 3, openCashDrawer = false } = opts;
  const parts: Uint8Array[] = [EscPos.INIT];

  parts.push(align === 'center' ? EscPos.alignCenter : align === 'right' ? EscPos.alignRight : EscPos.alignLeft);
  parts.push(encodeEscPosRaster(mono));
  parts.push(EscPos.feed(feedLines));
  if (cut) parts.push(EscPos.cutPartial);
  if (openCashDrawer) parts.push(EscPos.openDrawerPin2);

  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const p of parts) {
    out.set(p, off);
    off += p.length;
  }
  return out;
}
