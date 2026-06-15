/**
 * Imprimante ESC/POS VIRTUELLE sur TCP (port 9100).
 *
 * Émule une imprimante réseau : elle accepte une connexion TCP, capture le flux
 * d'octets ESC/POS, le décode (texte / coupe / images raster) et l'enregistre.
 * Permet de tester toute la chaîne d'impression SANS imprimante physique :
 *   - en LOCAL : `npm run printer:virtual` puis pointe ton app/device sur
 *     `<ip-de-ton-mac>:9100` (imprimante réseau) → chaque ticket est dump dans
 *     `esc-pos-out/` (.bin + .pbm visualisable) avec un résumé console.
 *   - en TEST/CI : importé par `test/escpos-tcp.integration.spec.ts` (port éphémère).
 *
 * Pur Node, aucune dépendance. (Le Bluetooth n'est pas simulable ; voir
 * docs/TESTING_SDK.md — ici on couvre tout le pipeline via le transport TCP.)
 *
 *   node scripts/virtual-printer.mjs [port]      # défaut 9100, ou $PORT
 */
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';

const ESC = 0x1b,
  GS = 0x1d;

/** Extrait les blocs raster GS v 0 et un résumé lisible du flux ESC/POS. */
function decodeJob(buf) {
  const rasters = [];
  let textChars = 0;
  let hasCut = false;
  const hasInit = buf.length >= 2 && buf[0] === ESC && buf[1] === 0x40; // ESC @
  for (let i = 0; i < buf.length; i++) {
    const b = buf[i];
    if (b === GS && buf[i + 1] === 0x76 && buf[i + 2] === 0x30) {
      // GS v 0 m xL xH yL yH [data]
      const xL = buf[i + 4],
        xH = buf[i + 5],
        yL = buf[i + 6],
        yH = buf[i + 7];
      const bytesPerRow = xL + (xH << 8);
      const height = yL + (yH << 8);
      const dataStart = i + 8;
      const dataLen = bytesPerRow * height;
      const data = buf.subarray(dataStart, dataStart + dataLen);
      rasters.push({ widthDots: bytesPerRow * 8, height, bytesPerRow, data });
      i = dataStart + dataLen - 1;
      continue;
    }
    if (b === GS && buf[i + 1] === 0x56) {
      hasCut = true;
      i += 1;
      continue;
    } // GS V (cut)
    if (b >= 0x20 && b < 0x7f) textChars++;
  }
  return { hasInit, hasCut, rasters, textChars, size: buf.length };
}

/** Écrit un raster en PBM (P4) — image 1-bit visualisable (Aperçu, GIMP, web…). */
function writePbm(file, raster) {
  const header = Buffer.from(`P4\n${raster.widthDots} ${raster.height}\n`, 'ascii');
  // ESC/POS raster et PBM P4 : 1 = noir, MSB-first, lignes alignées sur l'octet → copie directe.
  fs.writeFileSync(file, Buffer.concat([header, Buffer.from(raster.data)]));
}

/**
 * Démarre l'imprimante virtuelle.
 * @returns {Promise<{port:number, jobs:object[], nextJob:(t?:number)=>Promise<object>, close:()=>Promise<void>}>}
 */
export function startVirtualPrinter({ port = 9100, outDir = null, quiet = false } = {}) {
  return new Promise((resolve, reject) => {
    const jobs = [];
    const waiters = [];
    let consumed = 0;
    let jobN = 0;

    if (outDir) fs.mkdirSync(outDir, { recursive: true });

    function emit(job) {
      jobs.push(job);
      const w = waiters.shift();
      if (w) {
        consumed++;
        w.resolve(job);
      }
    }

    function finalize(chunks) {
      if (!chunks.length) return;
      const bytes = Buffer.concat(chunks);
      const decoded = decodeJob(bytes);
      const n = ++jobN;
      if (outDir) {
        fs.mkdirSync(outDir, { recursive: true }); // robuste si le dossier a été supprimé
        fs.writeFileSync(path.join(outDir, `job-${n}.bin`), bytes);
        decoded.rasters.forEach((r, k) => writePbm(path.join(outDir, `job-${n}-img${k}.pbm`), r));
      }
      if (!quiet) {
        const imgs = decoded.rasters.map((r) => `${r.widthDots}×${r.height}`).join(', ') || 'aucune';
        console.log(
          `🧾 Job #${n} reçu : ${decoded.size} octets · init=${decoded.hasInit} · ` +
            `coupe=${decoded.hasCut} · texte≈${decoded.textChars} car · images=[${imgs}]` +
            (outDir ? ` → ${path.join(outDir, `job-${n}.*`)}` : ''),
        );
      }
      emit({ bytes, ...decoded });
    }

    const server = net.createServer((socket) => {
      // Une vraie imprimante réseau garde souvent la connexion ouverte (connect puis
      // écritures). On flush donc un "job" soit à la fermeture, soit après un court
      // silence (idle), pour capturer même sur connexion persistante.
      let chunks = [];
      let idle = null;
      const flush = () => {
        if (idle) {
          clearTimeout(idle);
          idle = null;
        }
        const c = chunks;
        chunks = [];
        finalize(c);
      };
      socket.on('data', (d) => {
        chunks.push(d);
        if (idle) clearTimeout(idle);
        idle = setTimeout(flush, 1200); // burst terminé -> on matérialise le ticket
      });
      socket.on('error', () => {});
      socket.on('close', flush);
    });

    server.on('error', reject);
    server.listen(port, () => {
      const actualPort = server.address().port;
      if (!quiet) console.log(`🖨️  Imprimante virtuelle ESC/POS en écoute sur le port ${actualPort} (TCP)`);
      resolve({
        port: actualPort,
        jobs,
        nextJob(timeoutMs = 3000) {
          if (consumed < jobs.length) return Promise.resolve(jobs[consumed++]);
          return new Promise((res, rej) => {
            const t = setTimeout(() => {
              const idx = waiters.indexOf(waiter);
              if (idx >= 0) waiters.splice(idx, 1);
              rej(new Error('timeout: aucun job reçu'));
            }, timeoutMs);
            const waiter = {
              resolve: (j) => {
                clearTimeout(t);
                res(j);
              },
            };
            waiters.push(waiter);
          });
        },
        close() {
          return new Promise((res) => server.close(() => res()));
        },
      });
    });
  });
}

// --- Exécution en CLI ---------------------------------------------------------
const invokedDirectly = process.argv[1] && process.argv[1].endsWith('virtual-printer.mjs');
if (invokedDirectly) {
  const port = Number(process.argv[2] || process.env.PORT || 9100);
  startVirtualPrinter({ port, outDir: 'esc-pos-out' })
    .then(() => {
      console.log('Pointe ton imprimante/app sur ce port. Ctrl+C pour arrêter.');
    })
    .catch((e) => {
      console.error('Impossible de démarrer:', e.message);
      process.exit(1);
    });
}
