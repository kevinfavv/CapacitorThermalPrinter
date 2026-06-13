import { registerPlugin } from '@capacitor/core';

import type { ThermalPrinterPlugin } from './definitions';

/**
 * Point d'entrée du plugin.
 *
 * `registerPlugin` renvoie un proxy : sur natif il route vers Kotlin/Swift,
 * sur web il charge dynamiquement l'implémentation `ThermalPrinterWeb`.
 */
const ThermalPrinter = registerPlugin<ThermalPrinterPlugin>('ThermalPrinter', {
  web: () => import('./web').then(m => new m.ThermalPrinterWeb()),
});

export * from './definitions';
export * from './core/enums';
export * from './core/models';
export * from './core/options';
export * from './core/text';
export * from './core/errors';
export { ThermalPrinter };
