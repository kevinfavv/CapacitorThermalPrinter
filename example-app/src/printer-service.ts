/**
 * Exemple de service applicatif (Ionic/Angular/React/Vue agnostique).
 *
 * Montre le parcours produit complet attendu en restauration :
 *   - ajout d'imprimante (scan -> liste -> test -> défaut),
 *   - impression quotidienne avec reconnexion automatique transparente.
 */
import {
  ThermalPrinter,
  PrinterError,
  PrintErrorCode,
  type DiscoveredPrinter,
  type PrintImageOptions,
} from '@resto/capacitor-thermal-printer';

export class PrinterService {
  /** Étape "Ajouter une imprimante" : scanne et retourne la liste live. */
  async scan(onFound?: (p: DiscoveredPrinter) => void): Promise<DiscoveredPrinter[]> {
    await ThermalPrinter.requestPermissions();
    const sub = await ThermalPrinter.addListener('printerFound', e => onFound?.(e.printer));
    try {
      const { printers } = await ThermalPrinter.discoverPrinters({ timeoutMs: 8000 });
      return printers;
    } finally {
      await sub.remove();
    }
  }

  /** Test d'impression puis enregistrement comme imprimante par défaut. */
  async testAndSetDefault(printerId: string, testTicketPng: string): Promise<void> {
    await ThermalPrinter.connectPrinter({ printerId });
    await ThermalPrinter.printImage({ printerId, image: { filePath: testTicketPng } });
    await ThermalPrinter.setDefaultPrinter({ printerId });
  }

  /** Impression quotidienne : aucune gestion technique côté serveur de salle. */
  async printTicket(filePath: string): Promise<void> {
    const options: PrintImageOptions = {
      image: { filePath }, // imprimante par défaut + reconnexion auto implicites
      render: { dithering: 'floyd_steinberg', cut: true, feedLines: 3 },
      autoReconnect: true,
      timeoutMs: 15000,
    };
    try {
      await ThermalPrinter.printImage(options);
    } catch (e) {
      const err = e as PrinterError;
      switch (err.code) {
        case PrintErrorCode.PAPER_EMPTY:
          throw new Error('Plus de papier 🧻');
        case PrintErrorCode.COVER_OPEN:
          throw new Error('Capot ouvert');
        case PrintErrorCode.CONNECTION_FAILED:
        case PrintErrorCode.PRINTER_OFFLINE:
          throw new Error('Imprimante injoignable, vérifiez le Wi-Fi/Bluetooth');
        default:
          throw err;
      }
    }
  }

  async currentDefault() {
    return (await ThermalPrinter.getDefaultPrinter()).profile;
  }
}
