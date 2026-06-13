// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest';
import { ThermalPrinterWeb } from '../src/web';
import { PrintErrorCode } from '../src/core/enums';
import type { PrinterProfile } from '../src/core/models';

const KEY = 'delicity.thermalprinter.profiles';

function seed(profiles: PrinterProfile[]): void {
  localStorage.setItem(KEY, JSON.stringify(profiles));
}

function makeProfile(id: string, isDefault = false): PrinterProfile {
  return {
    id,
    adapter: 'escpos',
    transport: 'wifi',
    address: '192.168.1.50:9100',
    name: 'Test',
    capabilities: {
      paperWidthMm: 80,
      printableDots: 576,
      dpi: 203,
      supportsCut: true,
      supportsCashDrawer: false,
      supportsStatus: false,
      supportsRasterImage: true,
    },
    isDefault,
    createdAt: 1,
    updatedAt: 1,
  };
}

describe('ThermalPrinterWeb', () => {
  let web: ThermalPrinterWeb;

  beforeEach(() => {
    localStorage.clear();
    web = new ThermalPrinterWeb();
  });

  it('discoverPrinters renvoie une liste vide sur le web', async () => {
    expect(await web.discoverPrinters()).toEqual({ printers: [] });
  });

  it('rejette les opérations matérielles avec SDK_NOT_AVAILABLE', async () => {
    await expect(web.connectPrinter({ printerId: 'x' })).rejects.toMatchObject({
      code: PrintErrorCode.SDK_NOT_AVAILABLE,
    });
    await expect(web.printImage({ image: { filePath: '/x.png' } })).rejects.toMatchObject({
      code: PrintErrorCode.SDK_NOT_AVAILABLE,
    });
    await expect(web.printText({ items: [] })).rejects.toMatchObject({
      code: PrintErrorCode.SDK_NOT_AVAILABLE,
    });
  });

  it('gère les profils persistés (get/saved/setDefault/remove)', async () => {
    seed([makeProfile('a'), makeProfile('b')]);

    expect((await web.getSavedPrinters()).profiles).toHaveLength(2);
    expect((await web.getDefaultPrinter()).profile).toBeNull();

    const { profile } = await web.setDefaultPrinter({ printerId: 'b' });
    expect(profile.id).toBe('b');
    expect(profile.isDefault).toBe(true);
    expect((await web.getDefaultPrinter()).profile?.id).toBe('b');

    await web.removePrinter({ printerId: 'a' });
    expect((await web.getSavedPrinters()).profiles).toHaveLength(1);
  });

  it('setDefaultPrinter lève PRINTER_NOT_FOUND pour un id inconnu', async () => {
    await expect(web.setDefaultPrinter({ printerId: 'zzz' })).rejects.toMatchObject({
      code: PrintErrorCode.PRINTER_NOT_FOUND,
    });
  });

  it('checkPermissions renvoie unavailable sur le web', async () => {
    const perms = await web.checkPermissions();
    expect(perms.bluetooth).toBe('unavailable');
  });

  it('getDebugLog renvoie un journal vide', async () => {
    expect((await web.getDebugLog()).log).toEqual([]);
  });

  it('getActiveSdks liste les adapters (tous indisponibles sur le web)', async () => {
    const { sdks } = await web.getActiveSdks();
    expect(sdks.map(s => s.adapter)).toEqual(['escpos', 'star', 'epson', 'brother', 'zebra', 'rawTcp']);
    expect(sdks.every(s => s.available === false)).toBe(true);
    expect(sdks.find(s => s.adapter === 'star')?.requiresSdk).toBe(true);
    expect(sdks.find(s => s.adapter === 'escpos')?.requiresSdk).toBe(false);
  });

  it('disconnect/monitor sont des no-op résolus', async () => {
    await expect(web.disconnectPrinter({ printerId: 'x' })).resolves.toBeUndefined();
    await expect(web.startStatusMonitor({ printerId: 'x' })).resolves.toBeUndefined();
    await expect(web.stopStatusMonitor({ printerId: 'x' })).resolves.toBeUndefined();
  });
});
