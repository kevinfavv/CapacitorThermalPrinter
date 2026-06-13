import { describe, it, expect } from 'vitest';
import { scoreCandidate, resolveBestAdapter, type AdapterCandidate } from '../src/adapters/priority';

function c(p: Partial<AdapterCandidate>): AdapterCandidate {
  return { adapter: 'escpos', transport: 'wifi', fromVendorSdk: false, ...p };
}

describe('scoreCandidate', () => {
  it('priorise les SDK fabricants', () => {
    expect(scoreCandidate(c({ adapter: 'epson', fromVendorSdk: true }))).toBe(900);
    expect(scoreCandidate(c({ adapter: 'star', fromVendorSdk: true }))).toBe(890);
    expect(scoreCandidate(c({ adapter: 'brother', fromVendorSdk: true }))).toBe(880);
  });

  it('force Zebra vers l’adapter zebra et bannit escpos', () => {
    expect(scoreCandidate(c({ adapter: 'zebra', brand: 'Zebra' }))).toBe(1000);
    // une Zebra vue en escpos doit être bannie
    expect(scoreCandidate(c({ adapter: 'escpos', brand: 'Zebra Technologies' }))).toBe(-1000);
  });

  it('classe escpos BT > escpos TCP > ble > rawTcp', () => {
    expect(scoreCandidate(c({ adapter: 'escpos', transport: 'bluetooth', escposVerified: true }))).toBe(700);
    expect(scoreCandidate(c({ adapter: 'escpos', transport: 'bluetooth' }))).toBe(620);
    expect(scoreCandidate(c({ adapter: 'escpos', transport: 'wifi' }))).toBe(600);
    expect(scoreCandidate(c({ adapter: 'rawTcp', transport: 'ble' }))).toBe(500);
    expect(scoreCandidate(c({ adapter: 'rawTcp', transport: 'wifi' }))).toBe(300);
  });
});

describe('resolveBestAdapter', () => {
  it('retourne le meilleur candidat', () => {
    const best = resolveBestAdapter([
      c({ adapter: 'rawTcp', transport: 'wifi' }),
      c({ adapter: 'epson', fromVendorSdk: true }),
      c({ adapter: 'escpos', transport: 'wifi' }),
    ]);
    expect(best.adapter).toBe('epson');
  });

  it('pour une Zebra, ne choisit jamais escpos', () => {
    const best = resolveBestAdapter([
      c({ adapter: 'escpos', transport: 'wifi', brand: 'Zebra' }),
      c({ adapter: 'zebra', transport: 'wifi', brand: 'Zebra' }),
    ]);
    expect(best.adapter).toBe('zebra');
  });

  it('lève si aucun candidat', () => {
    expect(() => resolveBestAdapter([])).toThrow();
  });
});
