import Foundation
import Network

/// Découverte réseau iOS.
///
/// Recommandé : Bonjour/mDNS via NWBrowser pour `_pdl-datastream._tcp` (port 9100)
/// et `_printer._tcp` / `_ipp._tcp`. C'est la méthode propre sur iOS (pas de scan
/// d'IP brute, qui est mal vu et lent). Les imprimantes réseau modernes publient
/// ces services.
///
/// ⚠️ Info.plist : nécessite NSLocalNetworkUsageDescription + NSBonjourServices
/// listant les services recherchés.
final class BonjourScanner {

    private var browsers: [NWBrowser] = []

    func scan(timeoutMs: Int, onFound: @escaping (DiscoveredPrinter) -> Void) async {
        let services = ["_pdl-datastream._tcp", "_printer._tcp", "_ipp._tcp"]
        let queue = DispatchQueue(label: "thermalprinter.bonjour")

        for service in services {
            let params = NWParameters()
            params.includePeerToPeer = false
            let browser = NWBrowser(for: .bonjour(type: service, domain: nil), using: params)
            browser.browseResultsChangedHandler = { results, _ in
                for result in results {
                    if case let .service(name, type, domain, _) = result.endpoint {
                        // On encode name/type/domain : `TcpTransport.make` reconstruit un
                        // `NWEndpoint.service` et résout l'IP à la connexion. (Encoder juste
                        // "name._type" donnait une fausse adresse hôte -> connexion timeout.)
                        let address = "bonjour:" + [name, type, domain].joined(separator: "\u{1}")
                        let printer = DiscoveredPrinter(
                            id: "wifi:\(name)",
                            name: name,
                            brand: nil, model: nil,
                            transport: .wifi,
                            adapter: .escpos,           // arbitré ensuite par la priorité
                            address: address,
                            capabilities: Capabilities(),
                            discoveredBy: ["escpos", "rawTcp"]
                        )
                        onFound(printer)
                    }
                }
            }
            browser.start(queue: queue)
            browsers.append(browser)
        }

        try? await Task.sleep(nanoseconds: UInt64(timeoutMs) * 1_000_000)
        browsers.forEach { $0.cancel() }
        browsers.removeAll()
    }
}
