import { describe, it, expect } from 'vitest';
import { PrinterError, toPrinterError } from '../src/core/errors';
import { PrintErrorCode } from '../src/core/enums';

describe('PrinterError', () => {
  it('expose code, message, detail', () => {
    const e = new PrinterError({ code: PrintErrorCode.PAPER_EMPTY, message: 'vide', detail: 'd' });
    expect(e).toBeInstanceOf(Error);
    expect(e.code).toBe(PrintErrorCode.PAPER_EMPTY);
    expect(e.message).toBe('vide');
    expect(e.detail).toBe('d');
  });

  it('calcule retryable par défaut selon le code', () => {
    expect(new PrinterError({ code: PrintErrorCode.TIMEOUT, message: '' }).retryable).toBe(true);
    expect(new PrinterError({ code: PrintErrorCode.CONNECTION_FAILED, message: '' }).retryable).toBe(true);
    expect(new PrinterError({ code: PrintErrorCode.PAPER_EMPTY, message: '' }).retryable).toBe(false);
  });

  it('respecte un retryable explicite', () => {
    expect(new PrinterError({ code: PrintErrorCode.PAPER_EMPTY, message: '', retryable: true }).retryable).toBe(true);
  });
});

describe('toPrinterError', () => {
  it('passe à travers une PrinterError existante', () => {
    const e = new PrinterError({ code: PrintErrorCode.TIMEOUT, message: 't' });
    expect(toPrinterError(e)).toBe(e);
  });

  it('mappe un code Capacitor connu', () => {
    const e = toPrinterError({ code: 'PAPER_EMPTY', message: 'plus de papier' });
    expect(e.code).toBe(PrintErrorCode.PAPER_EMPTY);
    expect(e.message).toBe('plus de papier');
  });

  it('retombe sur UNKNOWN pour un code inconnu/absent', () => {
    expect(toPrinterError({ message: 'x' }).code).toBe(PrintErrorCode.UNKNOWN);
    expect(toPrinterError({ code: 'NOPE', message: 'x' }).code).toBe(PrintErrorCode.UNKNOWN);
    expect(toPrinterError('boom').code).toBe(PrintErrorCode.UNKNOWN);
  });
});
