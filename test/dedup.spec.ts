import { describe, it, expect } from 'vitest';
import { buildStableId, mergeDiscoveries } from '../src/adapters/dedup';
import type { DiscoveredPrinter } from '../src/core/models';

describe('buildStableId', () => {
  it('normalise les adresses réseau (sans port, minuscule)', () => {
    expect(buildStableId('wifi', '192.168.1.50:9100')).toBe('wifi:192.168.1.50');
    expect(buildStableId('ethernet', '10.0.0.5')).toBe('ethernet:10.0.0.5');
  });
  it('met les MAC/UUID en majuscules', () => {
    expect(buildStableId('bluetooth', 'aa:bb:cc:dd:ee:ff')).toBe('bluetooth:AA:BB:CC:DD:EE:FF');
    expect(buildStableId('ble', 'abc-123')).toBe('ble:ABC-123');
  });
  it('gère l’USB', () => {
    expect(buildStableId('usb', '1234:5678')).toBe('usb:1234:5678');
  });
});

function printer(p: Partial<DiscoveredPrinter>): DiscoveredPrinter {
  return {
    id: 'wifi:192.168.1.50',
    name: 'P',
    transport: 'wifi',
    adapter: 'escpos',
    address: '192.168.1.50:9100',
    lastSeenAt: 1000,
    isDefault: false,
    isConnected: false,
    ...p,
  };
}

describe('mergeDiscoveries', () => {
  const rank = (p: DiscoveredPrinter): number => (p.adapter === 'epson' ? 900 : 600);

  it('déduplique par id et garde le meilleur adapter', () => {
    const merged = mergeDiscoveries(
      [
        printer({ adapter: 'escpos', discoveredBy: ['escpos'] }),
        printer({ adapter: 'epson', brand: 'Epson', discoveredBy: ['epson'] }),
      ],
      rank,
    );
    expect(merged).toHaveLength(1);
    expect(merged[0].adapter).toBe('epson');
  });

  it('fusionne les sources de découverte et le lastSeen', () => {
    const merged = mergeDiscoveries(
      [
        printer({ adapter: 'escpos', discoveredBy: ['escpos'], lastSeenAt: 1000 }),
        printer({ adapter: 'rawTcp', discoveredBy: ['rawTcp'], lastSeenAt: 2000 }),
      ],
      rank,
    );
    expect(merged[0].lastSeenAt).toBe(2000);
    expect(new Set(merged[0].discoveredBy)).toEqual(new Set(['escpos', 'rawTcp']));
  });

  it('conserve isDefault/isConnected si l’un des doublons les porte', () => {
    const merged = mergeDiscoveries(
      [
        printer({ adapter: 'escpos', isDefault: true }),
        printer({ adapter: 'rawTcp', isConnected: true }),
      ],
      rank,
    );
    expect(merged[0].isDefault).toBe(true);
    expect(merged[0].isConnected).toBe(true);
  });

  it('garde les imprimantes distinctes séparées', () => {
    const merged = mergeDiscoveries(
      [printer({ id: 'wifi:a', address: 'a' }), printer({ id: 'wifi:b', address: 'b' })],
      rank,
    );
    expect(merged).toHaveLength(2);
  });
});
