/**
 * Test de validation de l'encodeur raster ESC/POS (GS v 0).
 * Vérifie le header, le padding largeur au multiple de 8 et le mapping des bits.
 *
 *   node scripts/verify-raster.mjs
 */
import {
  encodeEscPosRaster,
  thresholdToMono,
  floydSteinbergToMono,
  dotsForPaperWidth,
} from '../dist/esm/core/imaging.js';

let failures = 0;
function assert(cond, msg) {
  if (!cond) { console.error('❌', msg); failures++; }
  else console.log('✅', msg);
}

// 1) Largeurs de référence
assert(dotsForPaperWidth(58) === 384, '58mm -> 384 dots');
assert(dotsForPaperWidth(80) === 576, '80mm -> 576 dots');
assert(dotsForPaperWidth(112) === 832, '112mm -> 832 dots');
assert(dotsForPaperWidth(999) === 576, 'largeur inconnue -> fallback 576');

// 2) Image 8x2 : 1ère ligne toute noire, 2e toute blanche
const W = 8, H = 2;
const gray = new Uint8Array(W * H);
for (let i = 0; i < W; i++) gray[i] = 0;        // ligne 0 noire
for (let i = W; i < W * H; i++) gray[i] = 255;  // ligne 1 blanche
const mono = thresholdToMono(gray, W, H, 128);
const raster = encodeEscPosRaster(mono);

// header attendu : 1D 76 30 00 xL xH yL yH ; bytesPerRow=1, height=2
assert(raster[0] === 0x1d && raster[1] === 0x76 && raster[2] === 0x30, 'header GS v 0');
assert(raster[3] === 0x00, 'mode normal m=0');
assert(raster[4] === 1 && raster[5] === 0, 'xL=1 xH=0 (1 octet/ligne)');
assert(raster[6] === 2 && raster[7] === 0, 'yL=2 yH=0 (2 lignes)');
assert(raster[8] === 0xff, 'ligne 0 = 0xFF (tout noir)');
assert(raster[9] === 0x00, 'ligne 1 = 0x00 (tout blanc)');

// 3) Padding largeur 10 -> 2 octets/ligne
const mono10 = thresholdToMono(new Uint8Array(10).fill(0), 10, 1, 128);
const raster10 = encodeEscPosRaster(mono10);
assert(raster10[4] === 2, 'largeur 10px -> 2 octets/ligne (padding multiple de 8)');
// 10 bits noirs -> 0xFF puis 0xC0 (les 2 premiers bits du 2e octet)
assert(raster10[8] === 0xff && raster10[9] === 0xc0, 'bits MSB-first + padding 0');

// 4) Floyd-Steinberg ne crashe pas et produit la bonne taille
const fs = floydSteinbergToMono(new Uint8Array(64).fill(128), 8, 8);
assert(fs.data.length === 64, 'Floyd-Steinberg dimensions OK');

console.log(failures === 0 ? '\nTOUS LES TESTS PASSENT 🎉' : `\n${failures} test(s) en échec`);
process.exit(failures === 0 ? 0 : 1);
