import net from 'node:net';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

// @ts-expect-error - script JS pur sans types (.mjs)
import { startVirtualPrinter } from '../scripts/virtual-printer.mjs';
import { encodeEscPosItems } from '../src/core/escpos-text';
import { encodeEscPosRaster, thresholdToMono } from '../src/core/imaging';

/**
 * Test d'INTÉGRATION : l'encodeur ESC/POS de production -> socket TCP réel ->
 * imprimante virtuelle. Couvre toute la chaîne d'envoi (octets + transport TCP)
 * sans matériel. CI-friendly (localhost). Le Bluetooth n'est pas simulable (cf.
 * docs/TESTING_SDK.md) ; ce test valide le pipeline via le transport TCP/9100.
 */

let printer: Awaited<ReturnType<typeof startVirtualPrinter>>;

/** Ouvre une connexion, envoie les octets, ferme (= 1 job côté imprimante). */
function sendJob(port: number, bytes: Uint8Array): Promise<void> {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1', () => {
      socket.end(Buffer.from(bytes));
    });
    socket.on('close', () => resolve());
    socket.on('error', reject);
  });
}

beforeAll(async () => {
  printer = await startVirtualPrinter({ port: 0, quiet: true }); // port éphémère
});
afterAll(async () => {
  await printer.close();
});

describe('ESC/POS over TCP → virtual printer', () => {
  it('envoie un ticket texte et l’imprimante reçoit des octets identiques et bien formés', async () => {
    const { bytes } = encodeEscPosItems([
      { type: 'text', value: 'Delicity', style: { align: 'center', bold: true } },
      { type: 'divider', char: '-' },
      { type: 'text', value: 'Bonjour éàç' },
      { type: 'qrcode', value: 'https://delicity.com', size: 6 },
      { type: 'cut', mode: 'partial' },
    ]);

    await sendJob(printer.port, bytes);
    const job = await printer.nextJob();

    // 1) round-trip TCP : octets reçus == octets envoyés
    expect(Buffer.compare(job.bytes, Buffer.from(bytes))).toBe(0);
    // 2) flux bien formé : init ESC @ en tête + coupe détectée
    expect(job.hasInit).toBe(true);
    expect(job.hasCut).toBe(true);
    expect(job.textChars).toBeGreaterThan(0);
  });

  it('envoie une image raster et l’imprimante décode ses dimensions', async () => {
    const W = 16,
      H = 4;
    const gray = new Uint8Array(W * H).fill(0); // tout noir
    const mono = thresholdToMono(gray, W, H, 128);
    const raster = encodeEscPosRaster(mono);

    // Job complet : init + raster + coupe
    const job = new Uint8Array([0x1b, 0x40, ...raster, 0x1d, 0x56, 0x01]);
    await sendJob(printer.port, job);
    const received = await printer.nextJob();

    expect(received.hasInit).toBe(true);
    expect(received.rasters).toHaveLength(1);
    expect(received.rasters[0].widthDots).toBe(W);
    expect(received.rasters[0].height).toBe(H);
    expect(received.hasCut).toBe(true);
  });
});
