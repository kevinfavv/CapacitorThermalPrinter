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
  type PrintItem,
  type PrintJobStatusEvent,
  type StatusChangeEvent,
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

  /** Test d'impression puis connexion qui définit directement l'imprimante par défaut. */
  async testAndSetDefault(printerId: string, testTicketPng: string): Promise<void> {
    // setAsDefault: l'imprimante devient le défaut UNIQUEMENT si la connexion réussit.
    await ThermalPrinter.connectPrinter({ printerId, setAsDefault: true });
    await ThermalPrinter.printImage({ printerId, image: { filePath: testTicketPng } });
  }

  /** Impression image quotidienne (await = terminé physiquement, best-effort). */
  async printTicketImage(filePath: string): Promise<void> {
    const options: PrintImageOptions = {
      image: { filePath }, // imprimante par défaut + reconnexion auto implicites
      render: { dithering: 'floyd_steinberg', cut: true, feedLines: 3 },
      autoReconnect: true,
      timeoutMs: 15000,
    };
    await this.guard(() => ThermalPrinter.printImage(options));
  }

  /** Image déjà rendue serveur à la bonne largeur et en 1-bit : on désactive resize + grayscale. */
  async printPreRenderedImage(filePath: string): Promise<void> {
    await this.guard(() =>
      ThermalPrinter.printImage({
        image: { filePath },
        render: { resize: false, grayscale: false, cut: true },
      }),
    );
  }

  /** Impression de texte stylé (tableau d'items typés). */
  async printReceiptText(table: string, total: string): Promise<void> {
    const items: PrintItem[] = [
      { type: 'text', value: 'LE RESTO', style: { align: 'center', bold: true, widthMultiplier: 2, heightMultiplier: 2 } },
      { type: 'text', value: '12 rue des Lilas', style: { align: 'center' } },
      { type: 'divider', char: '-' },
      { type: 'text', value: `Table ${table}`, style: { bold: true } },
      { type: 'text', value: 'Burger............12.00' },
      { type: 'text', value: 'Frites............ 4.50' },
      { type: 'divider' },
      { type: 'text', value: `TOTAL ${total}`, style: { align: 'right', bold: true, widthMultiplier: 2 } },
      { type: 'feed', lines: 1 },
      { type: 'qrcode', value: 'https://resto.app/avis/123', size: 6, align: 'center' },
      { type: 'feed', lines: 1 },
      { type: 'barcode', value: '4006381333931', symbology: 'EAN13', hri: 'below' },
      { type: 'cut', mode: 'partial', feedBefore: 3 },
    ];
    await this.guard(() => ThermalPrinter.printText({ items, defaultCodePage: 'WPC1252' }));
  }

  /** Suivi temps réel des jobs + du statut imprimante côté client. */
  async listen(): Promise<() => void> {
    const jobSub = await ThermalPrinter.addListener('printJobStatus', (e: PrintJobStatusEvent) => {
      // états : pending -> printing -> completed | hold(paper_empty/cover_open) | failed
      console.log('Job', e.job.jobId, '→', e.job.state, e.job.holdReason ?? '');
    });
    const statusSub = await ThermalPrinter.addListener('statusChange', (e: StatusChangeEvent) => {
      console.log('Statut imprimante', e.status.online, e.status.paper);
    });
    return () => {
      jobSub.remove();
      statusSub.remove();
    };
  }

  async currentDefault() {
    return (await ThermalPrinter.getDefaultPrinter()).profile;
  }

  private async guard<T>(fn: () => Promise<T>): Promise<T> {
    try {
      return await fn();
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
}

