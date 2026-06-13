import { WebPlugin } from '@capacitor/core';

import type {
  PermissionStatus,
  ThermalPrinterPlugin,
} from './definitions';
import { PrintErrorCode } from './core/enums';
import { PrinterError } from './core/errors';
import type {
  ConnectOptions,
  DiscoverOptions,
  PrintImageOptions,
  PrintTextOptions,
} from './core/options';
import type {
  DiscoveredPrinter,
  PrinterProfile,
  PrinterStatus,
  PrintResult,
} from './core/models';

/**
 * Implémentation Web (fallback navigateur / SSR).
 *
 * Le matériel d'impression thermique n'est pas adressable de façon fiable
 * depuis un navigateur standard. On expose donc une implémentation qui :
 *   - persiste les profils dans localStorage (utile pour le dev UI),
 *   - rejette explicitement les opérations matérielles avec SDK_NOT_AVAILABLE.
 *
 * Cela permet de développer toute l'UI Capacitor sans device.
 */
export class ThermalPrinterWeb extends WebPlugin implements ThermalPrinterPlugin {
  private readonly storageKey = 'delicity.thermalprinter.profiles';

  async discoverPrinters(_options?: DiscoverOptions): Promise<{ printers: DiscoveredPrinter[] }> {
    // Aucune découverte matérielle possible sur le web.
    return { printers: [] };
  }

  async connectPrinter(_options: ConnectOptions): Promise<{ connected: boolean }> {
    throw this.unsupported('connectPrinter');
  }

  async disconnectPrinter(_options: { printerId: string }): Promise<void> {
    return;
  }

  async setDefaultPrinter(options: { printerId: string }): Promise<{ profile: PrinterProfile }> {
    const profiles = this.readProfiles();
    const target = profiles.find(p => p.id === options.printerId);
    if (!target) throw new PrinterError({ code: PrintErrorCode.PRINTER_NOT_FOUND, message: 'Profil introuvable' });
    profiles.forEach(p => (p.isDefault = p.id === options.printerId));
    target.updatedAt = Date.now();
    this.writeProfiles(profiles);
    return { profile: target };
  }

  async getDefaultPrinter(): Promise<{ profile: PrinterProfile | null }> {
    const profiles = this.readProfiles();
    return { profile: profiles.find(p => p.isDefault) ?? null };
  }

  async getSavedPrinters(): Promise<{ profiles: PrinterProfile[] }> {
    return { profiles: this.readProfiles() };
  }

  async removePrinter(options: { printerId: string }): Promise<void> {
    this.writeProfiles(this.readProfiles().filter(p => p.id !== options.printerId));
  }

  async printImage(_options: PrintImageOptions): Promise<PrintResult> {
    throw this.unsupported('printImage');
  }

  async printText(_options: PrintTextOptions): Promise<PrintResult> {
    throw this.unsupported('printText');
  }

  async getPrinterStatus(_options: { printerId?: string }): Promise<PrinterStatus> {
    throw this.unsupported('getPrinterStatus');
  }

  async requestPermissions(): Promise<PermissionStatus> {
    return this.checkPermissions();
  }

  async checkPermissions(): Promise<PermissionStatus> {
    return {
      bluetooth: 'unavailable',
      bluetoothScan: 'unavailable',
      bluetoothConnect: 'unavailable',
      location: 'unavailable',
      localNetwork: 'unavailable',
    };
  }

  async startStatusMonitor(_options: { printerId: string; intervalMs?: number }): Promise<void> {
    return;
  }

  async stopStatusMonitor(_options: { printerId: string }): Promise<void> {
    return;
  }

  async getActiveSdks(): Promise<{ sdks: import('./definitions').SdkStatus[] }> {
    // Aucun adapter natif n'est actif sur le web.
    return {
      sdks: [
        { adapter: 'escpos', label: 'ESC/POS générique', available: false, requiresSdk: false, transports: ['wifi', 'ethernet', 'bluetooth', 'ble', 'usb'] },
        { adapter: 'star', label: 'Star StarXpand', available: false, requiresSdk: true, transports: ['wifi', 'bluetooth', 'ble', 'usb'] },
        { adapter: 'epson', label: 'Epson ePOS2', available: false, requiresSdk: true, transports: ['wifi', 'bluetooth', 'usb'] },
        { adapter: 'brother', label: 'Brother', available: false, requiresSdk: true, transports: ['wifi', 'bluetooth', 'ble'] },
        { adapter: 'zebra', label: 'Zebra Link-OS', available: false, requiresSdk: true, transports: ['wifi', 'bluetooth'] },
        { adapter: 'rawTcp', label: 'TCP brut', available: false, requiresSdk: false, transports: ['wifi', 'ethernet'] },
      ],
    };
  }

  async getDebugLog(): Promise<{ log: import('./definitions').DebugLogEntry[] }> {
    return { log: [] };
  }

  private unsupported(method: string): PrinterError {
    return new PrinterError({
      code: PrintErrorCode.SDK_NOT_AVAILABLE,
      message: `${method} n'est pas disponible sur le web. Utilisez un device Android/iOS.`,
    });
  }

  private readProfiles(): PrinterProfile[] {
    try {
      return JSON.parse(localStorage.getItem(this.storageKey) ?? '[]');
    } catch {
      return [];
    }
  }

  private writeProfiles(profiles: PrinterProfile[]): void {
    localStorage.setItem(this.storageKey, JSON.stringify(profiles));
  }
}
