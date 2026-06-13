/**
 * Transports physiques possibles vers une imprimante.
 *
 * - `wifi`      : imprimante sur réseau Wi-Fi (TCP, généralement port 9100 / RAW).
 * - `ethernet`  : imprimante filaire réseau (même pile TCP que le Wi-Fi).
 * - `bluetooth` : Bluetooth Classic / SPP (Android uniquement pour le générique ESC/POS).
 * - `ble`       : Bluetooth Low Energy (iOS + Android, uniquement si l'imprimante
 *                 expose un service GATT exploitable).
 * - `usb`       : USB host (Android uniquement).
 */
export type PrinterTransport = 'wifi' | 'ethernet' | 'bluetooth' | 'ble' | 'usb';

/**
 * Adapter logiciel utilisé pour parler à l'imprimante.
 * Le choix de l'adapter est fait par le moteur de priorité (voir AdapterPriority).
 */
export type PrinterAdapterId =
  | 'escpos' // ESC/POS générique (raster GS v 0) — SPP, TCP, BLE
  | 'epson' // SDK Epson ePOS2 / ePOS-Print
  | 'star' // SDK StarXpand / StarIO10
  | 'brother' // SDK Brother Print SDK
  | 'zebra' // SDK Zebra Link-OS (ZPL / CPCL)
  | 'rawTcp'; // Flux TCP brut, fallback réseau quand l'imprimante n'est pas identifiée

/**
 * Codes d'erreur normalisés renvoyés par le plugin sur toutes les plateformes.
 * Ils sont stables et destinés à être mappés dans l'UI / le support.
 */
export enum PrintErrorCode {
  PRINTER_NOT_FOUND = 'PRINTER_NOT_FOUND',
  PRINTER_OFFLINE = 'PRINTER_OFFLINE',
  CONNECTION_FAILED = 'CONNECTION_FAILED',
  PERMISSION_DENIED = 'PERMISSION_DENIED',
  BLUETOOTH_DISABLED = 'BLUETOOTH_DISABLED',
  WIFI_NOT_CONNECTED = 'WIFI_NOT_CONNECTED',
  PAIRING_REQUIRED = 'PAIRING_REQUIRED',
  UNSUPPORTED_TRANSPORT = 'UNSUPPORTED_TRANSPORT',
  UNSUPPORTED_PRINTER = 'UNSUPPORTED_PRINTER',
  IMAGE_INVALID = 'IMAGE_INVALID',
  IMAGE_TOO_LARGE = 'IMAGE_TOO_LARGE',
  PRINT_FAILED = 'PRINT_FAILED',
  PAPER_EMPTY = 'PAPER_EMPTY',
  COVER_OPEN = 'COVER_OPEN',
  SDK_NOT_AVAILABLE = 'SDK_NOT_AVAILABLE',
  TIMEOUT = 'TIMEOUT',
  UNKNOWN = 'UNKNOWN',
}

/**
 * État de connexion logique d'une imprimante connue du plugin.
 */
export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'error';

/**
 * Niveau de papier rapporté par le statut imprimante (si supporté).
 */
export type PaperStatus = 'ok' | 'near_end' | 'empty' | 'unknown';
