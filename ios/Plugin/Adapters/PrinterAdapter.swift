import Foundation
import UIKit

/// Contrat commun à tous les adapters iOS (miroir de l'interface Android).
///
/// Toutes les méthodes longues sont `async` et lèvent `PrinterError` en cas d'échec.
protocol PrinterAdapter {
    var id: AdapterId { get }

    /// True si le SDK requis est lié à l'app (sinon adapter ignoré).
    func isAvailable() -> Bool

    /// Découverte propre à l'adapter, résultats poussés via `onFound`.
    func discover(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async

    func canHandle(_ profile: PrinterProfile) -> Bool

    func connect(_ profile: PrinterProfile, timeoutMs: Int) async throws
    func isConnected(_ printerId: String) -> Bool
    func disconnect(_ printerId: String) async

    /// Imprime un UIImage DÉJÀ redimensionné à la largeur cible.
    /// Retourne le nombre d'octets envoyés (best effort).
    func printImage(_ profile: PrinterProfile, image: UIImage, options: RenderOptions) async throws -> Int

    /// Imprime une liste d'items texte stylés (+ QR/code-barres/feed/cut...).
    /// Pour ESC/POS : encodage via EscPosTextEncoder. SDK : builder du SDK.
    func printItems(_ profile: PrinterProfile, items: [PrintItem], defaultCodePage: String, cut: Bool, feedLines: Int) async throws -> Int

    func getStatus(_ profile: PrinterProfile) async throws -> PrinterStatus
}

/// Implémentation par défaut : printText non supporté (les SDK la surchargent).
extension PrinterAdapter {
    func printItems(_ profile: PrinterProfile, items: [PrintItem], defaultCodePage: String, cut: Bool, feedLines: Int) async throws -> Int {
        throw PrinterError(.SDK_NOT_AVAILABLE, "printText non implémenté pour cet adapter (\(id.rawValue)) — voir docs/SDK_INTEGRATION.md")
    }
}
