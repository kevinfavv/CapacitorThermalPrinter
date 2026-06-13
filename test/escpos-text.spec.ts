import { describe, it, expect } from 'vitest';
import {
  encodeString,
  sizeByte,
  openStyle,
  resetStyle,
  encodeQrCode,
  encodeBarcode,
  encodeEscPosItems,
} from '../src/core/escpos-text';
import type { PrintItem } from '../src/core/text';

const ESC = 0x1b;
const GS = 0x1d;

describe('encodeString', () => {
  it('encode l’ASCII', () => {
    expect(encodeString('AB')).toEqual([0x41, 0x42]);
  });
  it('encode les accents français en mono-octet (WPC1252/Latin-1)', () => {
    // é = U+00E9 = 0xE9, à = 0xE0, € = U+20AC hors plage -> '?'
    expect(encodeString('é')).toEqual([0xe9]);
    expect(encodeString('à')).toEqual([0xe0]);
    expect(encodeString('€')).toEqual([0x3f]);
  });
});

describe('sizeByte (GS ! n)', () => {
  it('combine largeur (bits hauts) et hauteur (bits bas)', () => {
    expect(sizeByte(1, 1)).toBe(0x00);
    expect(sizeByte(2, 1)).toBe(0x10);
    expect(sizeByte(1, 2)).toBe(0x01);
    expect(sizeByte(2, 2)).toBe(0x11);
    expect(sizeByte(8, 8)).toBe(0x77);
  });
  it('clamp 1..8', () => {
    expect(sizeByte(0, 0)).toBe(0x00);
    expect(sizeByte(99, 99)).toBe(0x77);
  });
});

describe('openStyle', () => {
  it('émet ESC t (codepage), alignement, gras, taille', () => {
    const bytes = openStyle({ align: 'center', bold: true, widthMultiplier: 2, heightMultiplier: 2 });
    const s = bytes.join(',');
    expect(s).toContain([ESC, 0x74, 16].join(',')); // WPC1252 par défaut -> 16
    expect(s).toContain([ESC, 0x61, 1].join(',')); // center
    expect(s).toContain([ESC, 0x45, 1].join(',')); // bold on
    expect(s).toContain([GS, 0x21, 0x11].join(',')); // taille x2/x2
  });

  it('respecte un codePage explicite et un override numérique', () => {
    expect(openStyle({ codePage: 'CP858' }).join(',')).toContain([ESC, 0x74, 19].join(','));
    expect(openStyle({ codePageId: 42 }).join(',')).toContain([ESC, 0x74, 42].join(','));
  });

  it('gère soulignement double et interligne', () => {
    const s = openStyle({ underline: 'double', lineSpacing: 40 }).join(',');
    expect(s).toContain([ESC, 0x2d, 2].join(',')); // underline double
    expect(s).toContain([ESC, 0x33, 40].join(',')); // line spacing
  });

  it('couvre police B, invert, upsideDown, rotate90, letterSpacing, underline simple', () => {
    const s = openStyle({
      font: 'B',
      invert: true,
      upsideDown: true,
      rotate90: true,
      letterSpacing: 3,
      underline: 'single',
    }).join(',');
    expect(s).toContain([ESC, 0x4d, 1].join(',')); // font B
    expect(s).toContain([GS, 0x42, 1].join(',')); // invert on
    expect(s).toContain([ESC, 0x7b, 1].join(',')); // upside-down on
    expect(s).toContain([ESC, 0x56, 1].join(',')); // rotate90 on
    expect(s).toContain([ESC, 0x20, 3].join(',')); // letter spacing
    expect(s).toContain([ESC, 0x2d, 1].join(',')); // underline single
  });

  it('émet l’interligne par défaut (ESC 2) quand lineSpacing absent', () => {
    expect(openStyle({}).join(',')).toContain([ESC, 0x32].join(','));
  });
});

describe('resetStyle', () => {
  it('émet ESC @', () => {
    expect(resetStyle()).toEqual([ESC, 0x40]);
  });
});

describe('encodeQrCode', () => {
  it('émet la séquence GS ( k complète', () => {
    const bytes = encodeQrCode({ type: 'qrcode', value: 'HELLO', size: 6, errorCorrection: 'H' });
    const s = bytes.join(',');
    expect(s).toContain([GS, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x43, 6].join(',')); // taille
    expect(s).toContain([GS, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x45, 51].join(',')); // EC = H (51)
    expect(s).toContain([GS, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x51, 0x30].join(',')); // print
  });

  it('aligne à gauche/droite et clamp la taille + EC par défaut M', () => {
    expect(encodeQrCode({ type: 'qrcode', value: 'a', align: 'left' }).slice(0, 3)).toEqual([ESC, 0x61, 0]);
    expect(encodeQrCode({ type: 'qrcode', value: 'a', align: 'right' }).slice(0, 3)).toEqual([ESC, 0x61, 2]);
    const def = encodeQrCode({ type: 'qrcode', value: 'a', size: 99 }).join(',');
    expect(def).toContain([GS, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x43, 16].join(',')); // clamp 16
    expect(def).toContain([GS, 0x28, 0x6b, 0x03, 0x00, 0x31, 0x45, 49].join(',')); // M par défaut
  });
});

describe('encodeBarcode', () => {
  it('préfixe CODE128 avec {B et émet GS k 73', () => {
    const bytes = encodeBarcode({ type: 'barcode', value: '12345', symbology: 'CODE128' });
    const s = bytes.join(',');
    expect(s).toContain([GS, 0x6b, 73].join(',')); // m = CODE128
    expect(s).toContain([0x7b, 0x42].join(',')); // {B
  });
  it('EAN13 utilise m=67 et HRI below par défaut', () => {
    const bytes = encodeBarcode({ type: 'barcode', value: '4006381333931', symbology: 'EAN13' });
    const s = bytes.join(',');
    expect(s).toContain([GS, 0x6b, 67].join(','));
    expect(s).toContain([GS, 0x48, 2].join(',')); // HRI below
  });

  it('gère HRI none/above/both et alignements + clamp largeur', () => {
    expect(encodeBarcode({ type: 'barcode', value: '1', symbology: 'CODE39', hri: 'none' }).join(',')).toContain(
      [GS, 0x48, 0].join(','),
    );
    expect(encodeBarcode({ type: 'barcode', value: '1', symbology: 'CODE39', hri: 'above' }).join(',')).toContain(
      [GS, 0x48, 1].join(','),
    );
    expect(encodeBarcode({ type: 'barcode', value: '1', symbology: 'CODE39', hri: 'both' }).join(',')).toContain(
      [GS, 0x48, 3].join(','),
    );
    expect(encodeBarcode({ type: 'barcode', value: '1', symbology: 'CODE39', align: 'left' }).slice(0, 3)).toEqual([
      ESC, 0x61, 0,
    ]);
    expect(encodeBarcode({ type: 'barcode', value: '1', symbology: 'CODE39', width: 99 }).join(',')).toContain(
      [GS, 0x77, 6].join(','),
    );
  });

  it('ne re-préfixe pas CODE128 si déjà {…', () => {
    const bytes = encodeBarcode({ type: 'barcode', value: '{A12', symbology: 'CODE128' });
    const data = bytes.slice(-(4 + 1)); // [m? ...] approximatif : on vérifie l'absence de double {B
    expect(bytes.filter((b, i) => b === 0x7b && bytes[i + 1] === 0x42).length).toBe(0);
    expect(data.length).toBeGreaterThan(0);
  });
});

describe('encodeEscPosItems (intégration)', () => {
  it('démarre par ESC @ et termine un texte par LF', () => {
    const items: PrintItem[] = [{ type: 'text', value: 'Hi' }];
    const { bytes } = encodeEscPosItems(items);
    expect([bytes[0], bytes[1]]).toEqual([ESC, 0x40]);
    expect(bytes).toContain(0x0a); // LF présent
  });

  it('respecte newline:false (pas de LF ajouté)', () => {
    const { bytes } = encodeEscPosItems([{ type: 'text', value: 'X', style: { newline: false } }]);
    // dernier octet = reset ESC @ (0x40), pas un LF
    expect(bytes[bytes.length - 1]).toBe(0x40);
  });

  it('génère un divider pleine largeur', () => {
    const { bytes } = encodeEscPosItems([{ type: 'divider', char: '=', columns: 5 }]);
    const s = Array.from(bytes).join(',');
    expect(s).toContain([0x3d, 0x3d, 0x3d, 0x3d, 0x3d].join(',')); // 5x '='
  });

  it('gère feed, cut full/partial et cashDrawer', () => {
    const { bytes } = encodeEscPosItems([
      { type: 'feed', lines: 2 },
      { type: 'cashDrawer', pin: 5 },
      { type: 'cut', mode: 'full', feedBefore: 1 },
    ]);
    const s = Array.from(bytes).join(',');
    expect(s).toContain([ESC, 0x64, 2].join(',')); // feed 2
    expect(s).toContain([ESC, 0x70, 0x01].join(',')); // drawer pin5
    expect(s).toContain([GS, 0x56, 0x00].join(',')); // full cut
  });

  it('signale les items image sans les encoder', () => {
    const { imageItemIndexes, bytes } = encodeEscPosItems([
      { type: 'text', value: 'a' },
      { type: 'image', image: { filePath: '/x.png' } },
    ]);
    expect(imageItemIndexes).toEqual([1]);
    expect(bytes.length).toBeGreaterThan(0);
  });

  it('insère des octets bruts (raw base64)', () => {
    // base64 de [0x1b, 0x40] = "G0A="
    const { bytes } = encodeEscPosItems([{ type: 'raw', bytesBase64: 'G0A=' }]);
    const s = Array.from(bytes).join(',');
    expect(s).toContain([0x1b, 0x40].join(','));
  });

  it('accepte le préfixe data:...;base64, pour raw', () => {
    const { bytes } = encodeEscPosItems([{ type: 'raw', bytesBase64: 'data:application/octet-stream;base64,G0A=' }]);
    expect(Array.from(bytes).join(',')).toContain([0x1b, 0x40].join(','));
  });

  it('divider: caractère et alignement par défaut + style bold/center', () => {
    const def = encodeEscPosItems([{ type: 'divider', columns: 3 }]).bytes;
    expect(Array.from(def).join(',')).toContain([0x2d, 0x2d, 0x2d].join(',')); // '-' par défaut
    const styled = encodeEscPosItems([
      { type: 'divider', char: '*', columns: 2, style: { align: 'center', bold: true } },
    ]).bytes;
    const s = Array.from(styled).join(',');
    expect(s).toContain([ESC, 0x61, 1].join(',')); // center
    expect(s).toContain([ESC, 0x45, 1].join(',')); // bold
    expect(s).toContain([0x2a, 0x2a].join(',')); // '**'
  });

  it('cut partiel par défaut', () => {
    const { bytes } = encodeEscPosItems([{ type: 'cut' }]);
    expect(Array.from(bytes).join(',')).toContain([GS, 0x56, 1].join(','));
  });

  it('encode une suite réaliste de ticket', () => {
    const items: PrintItem[] = [
      { type: 'text', value: 'RESTO', style: { align: 'center', bold: true, widthMultiplier: 2, heightMultiplier: 2 } },
      { type: 'divider' },
      { type: 'text', value: 'Café   2.00€'.replace('€', 'EUR') },
      { type: 'qrcode', value: 'https://resto.app/t/1' },
      { type: 'feed', lines: 2 },
      { type: 'cut' },
    ];
    const { bytes, imageItemIndexes } = encodeEscPosItems(items, { defaultCodePage: 'CP858', columns: 32 });
    expect(bytes.length).toBeGreaterThan(20);
    expect(imageItemIndexes).toEqual([]);
    expect([bytes[0], bytes[1]]).toEqual([ESC, 0x40]);
  });
});
