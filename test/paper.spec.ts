import { describe, it, expect } from 'vitest';
import { guessPaperInfo, paperDotsForWidth } from '../src/core/paper';

describe('paperDotsForWidth', () => {
  it('maps standard widths @203 dpi', () => {
    expect(paperDotsForWidth(58)).toBe(384);
    expect(paperDotsForWidth(80)).toBe(576);
    expect(paperDotsForWidth(112)).toBe(832);
    expect(paperDotsForWidth(99)).toBeNull();
  });
});

describe('guessPaperInfo', () => {
  it('deduces 80mm models', () => {
    expect(guessPaperInfo('Epson', 'TM-m30')).toEqual({ widthMm: 80, printableDots: 576, dpi: 203, source: 'model' });
    expect(guessPaperInfo('Epson', 'TM-T88VI')?.widthMm).toBe(80);
    expect(guessPaperInfo('Star', 'TSP143')?.widthMm).toBe(80);
    expect(guessPaperInfo('Star', 'mC-Print3')?.widthMm).toBe(80);
  });

  it('deduces 58mm models', () => {
    expect(guessPaperInfo('Epson', 'TM-P20')?.widthMm).toBe(58);
    expect(guessPaperInfo('Star', 'mC-Print2')).toEqual({ widthMm: 58, printableDots: 384, dpi: 203, source: 'model' });
    expect(guessPaperInfo('Star', 'SM-L200')?.widthMm).toBe(58);
  });

  it('deduces 112mm models', () => {
    expect(guessPaperInfo('Star', 'SM-T400i')?.widthMm).toBe(112);
  });

  it('returns null when unknown or empty', () => {
    expect(guessPaperInfo('Generic', 'POS-58 no-name')).toBeNull();
    expect(guessPaperInfo(undefined, undefined)).toBeNull();
    expect(guessPaperInfo('Brand', '')).toBeNull();
  });
});
