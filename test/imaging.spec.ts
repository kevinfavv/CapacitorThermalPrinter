import { describe, it, expect } from 'vitest';
import {
  PAPER_WIDTH_PRESETS,
  dotsForPaperWidth,
  thresholdToMono,
  floydSteinbergToMono,
  atkinsonToMono,
  encodeEscPosRaster,
  buildEscPosImageJob,
  EscPos,
  type MonoBitmap,
} from '../src/core/imaging';

describe('largeurs papier', () => {
  it('mappe les presets de référence', () => {
    expect(PAPER_WIDTH_PRESETS[58]).toBe(384);
    expect(PAPER_WIDTH_PRESETS[80]).toBe(576);
    expect(PAPER_WIDTH_PRESETS[112]).toBe(832);
  });

  it('retombe sur 80mm pour une largeur inconnue', () => {
    expect(dotsForPaperWidth(58)).toBe(384);
    expect(dotsForPaperWidth(999)).toBe(576);
  });
});

describe('binarisation', () => {
  it('thresholdToMono applique le seuil (1 = noir)', () => {
    const gray = new Uint8Array([0, 100, 130, 255]);
    const mono = thresholdToMono(gray, 4, 1, 128);
    expect(Array.from(mono.data)).toEqual([1, 1, 0, 0]);
  });

  it('Floyd-Steinberg conserve les dimensions et reste binaire', () => {
    const gray = new Uint8Array(64).fill(128);
    const mono = floydSteinbergToMono(gray, 8, 8);
    expect(mono.data.length).toBe(64);
    expect(mono.data.every(v => v === 0 || v === 1)).toBe(true);
  });

  it('Atkinson conserve les dimensions et reste binaire', () => {
    const gray = new Uint8Array(64).fill(64);
    const mono = atkinsonToMono(gray, 8, 8);
    expect(mono.data.length).toBe(64);
    expect(mono.data.every(v => v === 0 || v === 1)).toBe(true);
  });

  it('une image toute noire ressort toute encrée', () => {
    const mono = floydSteinbergToMono(new Uint8Array(16).fill(0), 4, 4);
    expect(mono.data.every(v => v === 1)).toBe(true);
  });

  it('une image toute blanche ressort vide', () => {
    const mono = atkinsonToMono(new Uint8Array(16).fill(255), 4, 4);
    expect(mono.data.every(v => v === 0)).toBe(true);
  });
});

describe('raster ESC/POS GS v 0', () => {
  it('encode le header et les octets attendus (8x2)', () => {
    const W = 8;
    const H = 2;
    const gray = new Uint8Array(W * H);
    for (let i = 0; i < W; i++) gray[i] = 0; // ligne 0 noire
    for (let i = W; i < W * H; i++) gray[i] = 255; // ligne 1 blanche
    const raster = encodeEscPosRaster(thresholdToMono(gray, W, H, 128));

    expect([raster[0], raster[1], raster[2]]).toEqual([0x1d, 0x76, 0x30]);
    expect(raster[3]).toBe(0x00); // mode normal
    expect([raster[4], raster[5]]).toEqual([1, 0]); // 1 octet/ligne
    expect([raster[6], raster[7]]).toEqual([2, 0]); // 2 lignes
    expect(raster[8]).toBe(0xff); // ligne noire
    expect(raster[9]).toBe(0x00); // ligne blanche
  });

  it('pad la largeur au multiple de 8 (10px -> 2 octets/ligne, MSB-first)', () => {
    const mono: MonoBitmap = { width: 10, height: 1, data: new Uint8Array(10).fill(1) };
    const raster = encodeEscPosRaster(mono);
    expect(raster[4]).toBe(2);
    expect(raster[8]).toBe(0xff);
    expect(raster[9]).toBe(0xc0); // 2 bits restants à gauche
  });

  it('supporte les grandes dimensions (xH/yH)', () => {
    const mono: MonoBitmap = { width: 16, height: 300, data: new Uint8Array(16 * 300) };
    const raster = encodeEscPosRaster(mono);
    expect([raster[4], raster[5]]).toEqual([2, 0]); // 16px -> 2 octets
    expect([raster[6], raster[7]]).toEqual([300 & 0xff, 300 >> 8]); // 44, 1
  });
});

describe('buildEscPosImageJob', () => {
  const mono: MonoBitmap = { width: 8, height: 1, data: new Uint8Array(8).fill(1) };

  it('contient init + alignement + raster + feed + coupe', () => {
    const job = buildEscPosImageJob(mono, { align: 'center', cut: true, feedLines: 3 });
    // ESC @ en tête
    expect([job[0], job[1]]).toEqual([0x1b, 0x40]);
    // contient la commande raster
    expect(Array.from(job).join(',')).toContain('29,118,48');
    // contient une coupe partielle GS V 1
    expect(Array.from(job).join(',')).toContain('29,86,1');
  });

  it('ouvre le tiroir quand demandé et n’ajoute pas de coupe sinon', () => {
    const job = buildEscPosImageJob(mono, { cut: false, openCashDrawer: true });
    const s = Array.from(job).join(',');
    expect(s).not.toContain('29,86,1');
    expect(s).toContain('27,112,0'); // ESC p 0 (pin2)
  });

  it('feed encode le bon nombre de lignes', () => {
    const job = buildEscPosImageJob(mono, { feedLines: 5, cut: false });
    expect(Array.from(EscPos.feed(5))).toEqual([0x1b, 0x64, 5]);
    expect(Array.from(job).join(',')).toContain('27,100,5');
  });
});
