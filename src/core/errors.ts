import { PrintErrorCode } from './enums';
import type { NormalizedErrorShape } from './options';

/**
 * Erreur typée exposée côté JS. Les rejets de promesse du plugin sont
 * normalisés vers cette classe via `toPrinterError`.
 */
export class PrinterError extends Error implements NormalizedErrorShape {
  public readonly code: PrintErrorCode;
  public readonly detail?: string;
  public readonly retryable: boolean;

  constructor(shape: NormalizedErrorShape) {
    super(shape.message);
    this.name = 'PrinterError';
    this.code = shape.code;
    this.detail = shape.detail;
    this.retryable = shape.retryable ?? defaultRetryable(shape.code);
    Object.setPrototypeOf(this, PrinterError.prototype);
  }
}

/**
 * Convertit une erreur Capacitor (qui porte souvent `code` et `message`) en PrinterError.
 */
export function toPrinterError(err: unknown): PrinterError {
  if (err instanceof PrinterError) return err;

  const anyErr = err as { code?: string; message?: string; detail?: string };
  const code = mapToErrorCode(anyErr?.code);
  return new PrinterError({
    code,
    message: anyErr?.message ?? 'Erreur imprimante inconnue',
    detail: anyErr?.detail,
  });
}

function mapToErrorCode(raw?: string): PrintErrorCode {
  if (!raw) return PrintErrorCode.UNKNOWN;
  const upper = raw.toUpperCase();
  if (upper in PrintErrorCode) {
    return PrintErrorCode[upper as keyof typeof PrintErrorCode];
  }
  return PrintErrorCode.UNKNOWN;
}

/** Les erreurs réseau/connexion/timeout justifient un retry automatique. */
function defaultRetryable(code: PrintErrorCode): boolean {
  switch (code) {
    case PrintErrorCode.CONNECTION_FAILED:
    case PrintErrorCode.PRINTER_OFFLINE:
    case PrintErrorCode.TIMEOUT:
    case PrintErrorCode.WIFI_NOT_CONNECTED:
      return true;
    default:
      return false;
  }
}
