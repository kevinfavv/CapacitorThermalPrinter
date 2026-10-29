import CoreBluetooth
import Foundation

/// Transport BLE générique (CoreBluetooth) pour imprimantes ESC/POS BLE.
///
/// Beaucoup d'imprimantes "Bluetooth" bon marché (58 mm type MP210) sont en réalité
/// **Classic + BLE**. Le Bluetooth Classic SPP est inutilisable sur iOS (pas d'API),
/// mais le BLE l'est : on scanne les services d'impression connus, on trouve une
/// caractéristique inscriptible et on y écrit le flux ESC/POS par chunks.
///
/// ⚠️ À valider sur device réel : permissions Bluetooth requises
/// (NSBluetoothAlwaysUsageDescription), et l'imprimante doit exposer un service BLE.
final class BleManager: NSObject {
    static let shared = BleManager()

    /// Services BLE d'impression ESC/POS courants (filtre de scan).
    static let printerServices: [CBUUID] = [
        CBUUID(string: "18F0"), // ESC/POS BLE répandu (caractéristique d'écriture 2AF1)
        CBUUID(string: "FFE0"), // modules type HM-10 (FFE1)
        CBUUID(string: "FF00"), // FF02 en écriture
        CBUUID(string: "49535343-FE7D-4AE5-8FA9-9FAFD205E455"), // Microchip transparent UART
        CBUUID(string: "E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"),
        // Service WeChat/Tencent : certaines imprimantes n'annoncent QUE lui (TP85H-V
        // « TP85H-xxxx »), leur canal d'impression (FF00/FF02…) n'étant visible qu'après
        // connexion. Sert au scan uniquement, jamais de canal d'écriture (cf. controlServices).
        CBUUID(string: "FEE7"),
        CBUUID(string: "AE30"), // imprimantes BLE génériques (AE01 en écriture)
    ]

    /// Canaux d'écriture ESC/POS connus (service, caractéristique), par ordre de préférence.
    /// Une même imprimante en expose souvent plusieurs : la TP85H-V publie FF00, FF10, FEE7,
    /// FF80 et Microchip, tous avec des caractéristiques inscriptibles. Prendre « la première
    /// inscriptible découverte » dépendait de l'ordre des callbacks GATT (non déterministe) et
    /// pouvait viser un canal de contrôle (FEE7/FEC7, Microchip 6DAA) : rien ne s'imprimait.
    static let preferredWriteChannels: [(service: CBUUID, characteristic: CBUUID)] = [
        (CBUUID(string: "18F0"), CBUUID(string: "2AF1")),
        (CBUUID(string: "FF00"), CBUUID(string: "FF02")), // validé TP85H-V
        (CBUUID(string: "E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"), CBUUID(string: "BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F")),
        (CBUUID(string: "49535343-FE7D-4AE5-8FA9-9FAFD205E455"), CBUUID(string: "49535343-8841-43F4-A8D4-ECBE34729BB3")), // Microchip RX
        (CBUUID(string: "FFE0"), CBUUID(string: "FFE1")),
        (CBUUID(string: "AE30"), CBUUID(string: "AE01")),
    ]

    /// Canaux validés en écriture rapide : writeWithoutResponse (même pacing que les canaux
    /// sans `.write`) au lieu de l'écriture avec accusé. Sur la TP85H-V (FF02), l'accusé
    /// plafonnait à ~3 Ko/s (ticket image de 100 Ko ≈ 35 s) contre ~16 Ko/s ici. Les autres
    /// canaux gardent l'ancien comportement tant qu'ils ne sont pas validés.
    static let fastWriteCharacteristics: Set<CBUUID> = [CBUUID(string: "FF02")]

    /// Contrôle de flux par crédits (puces BLE Jieli, dont la TP85H-V) : caractéristique de
    /// notification associée au canal d'écriture. L'imprimante annonce `01 n` (+n paquets
    /// acceptés : 7 à la connexion, puis 1 par paquet absorbé) et `02 lo hi` (taille max d'un
    /// paquet). Écrire sans attendre ces crédits fait déborder son tampon dès que la tête
    /// ralentit (zones très noires) : bandes de quelques mm perdues, même avec du pacing.
    static let creditCharacteristics: [CBUUID: CBUUID] = [CBUUID(string: "FF02"): CBUUID(string: "FF03")]
    /// Attente max des crédits initiaux à la connexion ; au-delà, repli sur le pacing fixe.
    private static let initialCreditWaitMs = 1500
    /// Attente max d'un crédit pendant l'impression (papier épuisé, capot ouvert…).
    private static let creditTimeoutMs = 15000

    /// Services standard ou de contrôle qui ne reçoivent jamais le flux d'impression.
    static let controlServices: Set<CBUUID> = [
        CBUUID(string: "1800"), CBUUID(string: "1801"), CBUUID(string: "180A"), CBUUID(string: "180F"),
        CBUUID(string: "FEE7"),
    ]

    /// Caractéristiques de contrôle du service Microchip (paramètres de connexion, point de
    /// contrôle) : inscriptibles mais hors flux UART.
    static let controlCharacteristics: Set<CBUUID> = [
        CBUUID(string: "49535343-6DAA-4D02-ABF6-19569ACA69FE"),
        CBUUID(string: "49535343-ACA3-481C-91EC-D85E28A60318"),
    ]

    /// Un périphérique trouvé au scan est-il une imprimante ? Les services d'impression
    /// suffisent ; FEE7 seul (aussi annoncé par des bracelets/objets WeChat) exige un nom.
    static func isLikelyPrinter(advertised: [CBUUID], name: String?) -> Bool {
        if advertised.contains(where: { $0 != CBUUID(string: "FEE7") && printerServices.contains($0) }) { return true }
        if advertised.isEmpty { return true } // scan filtré : service vu hors annonce (cache iOS)
        return !(name ?? "").trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// Choisit le canal d'écriture parmi toutes les caractéristiques découvertes : d'abord un
    /// canal connu (ordre de préférence), sinon la première inscriptible d'un service
    /// d'impression connu, sinon de n'importe quel service non contrôle (ancien comportement).
    static func selectWriteCharacteristic(_ services: [CBService]) -> CBCharacteristic? {
        func writable(_ c: CBCharacteristic) -> Bool {
            c.properties.contains(.write) || c.properties.contains(.writeWithoutResponse)
        }
        for (svc, chr) in preferredWriteChannels {
            if let c = services.first(where: { $0.uuid == svc })?.characteristics?.first(where: { $0.uuid == chr }), writable(c) {
                return c
            }
        }
        let candidates = services.filter { !controlServices.contains($0.uuid) }
        let ordered = candidates.filter { printerServices.contains($0.uuid) } + candidates.filter { !printerServices.contains($0.uuid) }
        for s in ordered {
            if let c = (s.characteristics ?? []).first(where: { writable($0) && !controlCharacteristics.contains($0.uuid) }) {
                return c
            }
        }
        return nil
    }

    private var central: CBCentralManager!
    private let queue = DispatchQueue(label: "thermalprinter.ble")

    private var isPoweredOn = false

    /// Continuation à résolution unique (garde contre double-resume timeout/état).
    private final class Waiter {
        let cont: CheckedContinuation<Void, Error>
        private var done = false
        init(_ c: CheckedContinuation<Void, Error>) { cont = c }
        func claim() -> Bool { if done { return false }; done = true; return true }
    }
    private var poweredWaiters: [Waiter] = []

    private var scanning = false
    private var onFound: ((CBPeripheral) -> Void)?

    private final class Conn {
        let peripheral: CBPeripheral
        var writeChar: CBCharacteristic?
        var openCont: CheckedContinuation<Void, Error>?
        var writeAck: CheckedContinuation<Void, Error>?
        var readyWaiters: [CheckedContinuation<Void, Error>] = []
        /// Services dont les caractéristiques sont encore en cours de découverte.
        var pendingServices = 0
        /// Crédits d'envoi restants ; nil = imprimante sans contrôle de flux par crédits.
        var credits: Int?
        var creditWaiters: [Waiter] = []
        /// Taille max d'un paquet annoncée par l'imprimante (protocole crédits).
        var creditPacketSize: Int?
        var isOpen = false
        init(_ p: CBPeripheral) { self.peripheral = p }
    }
    private var conns: [String: Conn] = [:] // peripheral.identifier.uuidString -> Conn
    private var knownPeripherals: [String: CBPeripheral] = [:]

    override private init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: queue)
    }

    // MARK: API (appelée par BleAdapter)

    func ensurePoweredOn(timeoutMs: Int = 4000) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            queue.async {
                if self.isPoweredOn { cont.resume(); return }
                switch self.central.state {
                case .unsupported, .unauthorized:
                    cont.resume(throwing: PrinterError(.BLUETOOTH_DISABLED, "BLE indisponible (état \(self.central.state.rawValue))"))
                case .poweredOff:
                    cont.resume(throwing: PrinterError(.BLUETOOTH_DISABLED, "Bluetooth désactivé"))
                default:
                    let box = Waiter(cont)
                    self.poweredWaiters.append(box)
                    self.queue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) {
                        guard !self.isPoweredOn, box.claim() else { return }
                        self.poweredWaiters.removeAll { $0 === box }
                        cont.resume(throwing: PrinterError(.TIMEOUT, "Bluetooth non prêt (timeout)"))
                    }
                }
            }
        }
    }

    func scan(timeoutMs: Int, onFound: @escaping (CBPeripheral) -> Void) async {
        do { try await ensurePoweredOn() } catch { return }
        queue.async {
            self.onFound = onFound
            self.scanning = true
            // Scanne uniquement les imprimantes annonçant un service connu (évite de
            // remonter tous les périphériques BLE alentour).
            self.central.scanForPeripherals(withServices: Self.printerServices, options: nil)
        }
        try? await Task.sleep(nanoseconds: UInt64(max(1000, timeoutMs)) * 1_000_000)
        queue.async {
            if self.scanning { self.central.stopScan(); self.scanning = false }
            self.onFound = nil
        }
    }

    func connect(address: String, timeoutMs: Int) async throws {
        try await ensurePoweredOn()
        let peripheral = try resolvePeripheral(address)
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            queue.async {
                if let c = self.conns[address], c.isOpen, c.writeChar != nil { cont.resume(); return }
                let conn = Conn(peripheral)
                conn.openCont = cont
                self.conns[address] = conn
                peripheral.delegate = self
                self.central.connect(peripheral, options: nil)
                self.queue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) {
                    if let c = self.conns[address], !c.isOpen, let k = c.openCont {
                        c.openCont = nil
                        self.central.cancelPeripheralConnection(peripheral)
                        k.resume(throwing: PrinterError(.TIMEOUT, "Connexion BLE expirée", retryable: true))
                    }
                }
            }
        }
    }

    func isConnected(_ address: String) -> Bool {
        queue.sync { conns[address]?.isOpen == true && conns[address]?.writeChar != nil }
    }

    /// Canal validé pour le raster compacté (EscPosCommands.compactRaster), cf. fastWriteCharacteristics.
    func supportsCompactRaster(_ address: String) -> Bool {
        queue.sync { conns[address]?.writeChar.map { Self.fastWriteCharacteristics.contains($0.uuid) } ?? false }
    }

    func disconnect(_ address: String) {
        queue.async {
            if let c = self.conns[address] {
                self.central.cancelPeripheralConnection(c.peripheral)
                self.conns[address] = nil
                self.failPending(c, PrinterError(.CONNECTION_FAILED, "BLE fermé"))
            }
        }
    }

    func write(_ bytes: [UInt8], address: String) async throws {
        let (peripheral, char): (CBPeripheral, CBCharacteristic) = try queue.sync {
            guard let c = conns[address], c.isOpen, let ch = c.writeChar else {
                throw PrinterError(.CONNECTION_FAILED, "BLE non connecté: \(address)")
            }
            return (c.peripheral, ch)
        }
        let fast = Self.fastWriteCharacteristics.contains(char.uuid) && char.properties.contains(.writeWithoutResponse)
        let withResponse = char.properties.contains(.write) && !fast
        let type: CBCharacteristicWriteType = withResponse ? .withResponse : .withoutResponse
        let maxLen = peripheral.maximumWriteValueLength(for: type)
        let (credited, packetSize) = queue.sync { (conns[address]?.credits != nil, conns[address]?.creditPacketSize) }
        let chunkSize = max(20, min(maxLen, credited ? (packetSize ?? 180) : 180))
        let data = Data(bytes)
        var offset = 0
        while offset < data.count {
            let end = min(offset + chunkSize, data.count)
            let slice = data.subdata(in: offset..<end)
            if withResponse {
                try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                    queue.async {
                        guard let c = self.conns[address] else { cont.resume(throwing: PrinterError(.CONNECTION_FAILED, "BLE fermé")); return }
                        c.writeAck = cont
                        peripheral.writeValue(slice, for: char, type: .withResponse)
                    }
                }
            } else if credited {
                // Un paquet par crédit : l'imprimante règle elle-même le débit.
                try await takeCredit(address: address)
                try await waitReady(address: address, peripheral: peripheral)
                queue.async { peripheral.writeValue(slice, for: char, type: .withoutResponse) }
            } else {
                try await waitReady(address: address, peripheral: peripheral)
                queue.async { peripheral.writeValue(slice, for: char, type: .withoutResponse) }
                try? await Task.sleep(nanoseconds: 6_000_000) // ~6ms de pacing
            }
            offset = end
        }
    }

    // MARK: Helpers

    private func resolvePeripheral(_ address: String) throws -> CBPeripheral {
        if let p = knownPeripherals[address] { return p }
        if let uuid = UUID(uuidString: address),
           let p = central.retrievePeripherals(withIdentifiers: [uuid]).first {
            knownPeripherals[address] = p
            return p
        }
        throw PrinterError(.PRINTER_NOT_FOUND, "Imprimante BLE inconnue: \(address) (relancer une découverte)")
    }

    private func takeCredit(address: String) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            queue.async {
                guard let c = self.conns[address], c.isOpen else {
                    cont.resume(throwing: PrinterError(.CONNECTION_FAILED, "BLE fermé")); return
                }
                if let n = c.credits, n > 0, c.creditWaiters.isEmpty { c.credits = n - 1; cont.resume(); return }
                let box = Waiter(cont)
                c.creditWaiters.append(box)
                self.queue.asyncAfter(deadline: .now() + .milliseconds(Self.creditTimeoutMs)) {
                    guard box.claim() else { return }
                    c.creditWaiters.removeAll { $0 === box }
                    cont.resume(throwing: PrinterError(.TIMEOUT, "L'imprimante BLE n'accepte plus de données (papier ? capot ?)", retryable: true))
                }
            }
        }
    }

    /// Distribue les crédits disponibles aux écritures en attente (sur `queue`).
    private func grantCredits(_ c: Conn) {
        while let n = c.credits, n > 0, !c.creditWaiters.isEmpty {
            let box = c.creditWaiters.removeFirst()
            if box.claim() { c.credits = n - 1; box.cont.resume() }
        }
    }

    /// Débloque toutes les attentes d'une connexion perdue/fermée (sur `queue`). Sans ça, une
    /// coupure pendant une impression laissait le job suspendu indéfiniment.
    private func failPending(_ c: Conn, _ error: PrinterError) {
        if let k = c.openCont { c.openCont = nil; k.resume(throwing: error) }
        if let k = c.writeAck { c.writeAck = nil; k.resume(throwing: error) }
        let ready = c.readyWaiters; c.readyWaiters.removeAll()
        ready.forEach { $0.resume(throwing: error) }
        let credit = c.creditWaiters; c.creditWaiters.removeAll()
        credit.forEach { if $0.claim() { $0.cont.resume(throwing: error) } }
    }

    private func waitReady(address: String, peripheral: CBPeripheral) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            queue.async {
                if peripheral.canSendWriteWithoutResponse { cont.resume(); return }
                self.conns[address]?.readyWaiters.append(cont)
            }
        }
    }
}

// MARK: - CBCentralManagerDelegate

extension BleManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            isPoweredOn = true
            let waiters = poweredWaiters; poweredWaiters.removeAll()
            waiters.forEach { if $0.claim() { $0.cont.resume() } }
        } else {
            isPoweredOn = false
            if central.state == .poweredOff || central.state == .unauthorized || central.state == .unsupported {
                let waiters = poweredWaiters; poweredWaiters.removeAll()
                waiters.forEach { if $0.claim() { $0.cont.resume(throwing: PrinterError(.BLUETOOTH_DISABLED, "Bluetooth indisponible (\(central.state.rawValue))")) } }
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let key = peripheral.identifier.uuidString
        let advertised = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        let name = peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String
        guard Self.isLikelyPrinter(advertised: advertised, name: name) else { return }
        knownPeripherals[key] = peripheral
        onFound?(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        // Tous les services : le canal d'impression n'est pas forcément annoncé au scan
        // (TP85H-V : FEE7 annoncé, impression sur FF00). Le choix se fait une fois toutes
        // les caractéristiques connues (selectWriteCharacteristic).
        peripheral.discoverServices(nil)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let key = peripheral.identifier.uuidString
        if let c = conns[key], let cont = c.openCont {
            c.openCont = nil
            cont.resume(throwing: PrinterError(.CONNECTION_FAILED, "Connexion BLE échouée", detail: error?.localizedDescription, retryable: true))
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let key = peripheral.identifier.uuidString
        if let c = conns[key] {
            c.isOpen = false
            c.writeChar = nil
            failPending(c, PrinterError(.CONNECTION_FAILED, "BLE déconnecté", detail: error?.localizedDescription, retryable: true))
        }
    }
}

// MARK: - CBPeripheralDelegate

extension BleManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let key = peripheral.identifier.uuidString
        let services = peripheral.services ?? []
        guard let conn = conns[key] else { return }
        if services.isEmpty {
            failOpen(conn, peripheral, "Aucun service BLE exposé", detail: error?.localizedDescription)
            return
        }
        conn.pendingServices = services.count
        for service in services {
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let key = peripheral.identifier.uuidString
        guard let conn = conns[key], conn.writeChar == nil else { return }
        conn.pendingServices -= 1
        guard conn.pendingServices <= 0 else { return }
        guard let ch = Self.selectWriteCharacteristic(peripheral.services ?? []) else {
            failOpen(conn, peripheral, "Aucun canal d'impression BLE trouvé")
            return
        }
        Logger.shared.log("ble", "write-channel", ["service": ch.service?.uuid.uuidString ?? "?", "char": ch.uuid.uuidString])
        conn.writeChar = ch
        if let creditUuid = Self.creditCharacteristics[ch.uuid],
           let notify = ch.service?.characteristics?.first(where: { $0.uuid == creditUuid && $0.properties.contains(.notify) }) {
            // Ouverture différée jusqu'aux crédits initiaux (`01 07`), sinon repli pacing.
            peripheral.setNotifyValue(true, for: notify)
            queue.asyncAfter(deadline: .now() + .milliseconds(Self.initialCreditWaitMs)) { [weak conn] in
                guard let conn = conn, conn.writeChar != nil, !conn.isOpen else { return }
                Logger.shared.log("ble", "no-credits-fallback-pacing")
                self.finishOpen(conn)
            }
        } else {
            finishOpen(conn)
        }
    }

    private func finishOpen(_ conn: Conn) {
        conn.isOpen = true
        if let cont = conn.openCont { conn.openCont = nil; cont.resume() }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let key = peripheral.identifier.uuidString
        guard let conn = conns[key], let write = conn.writeChar,
              Self.creditCharacteristics[write.uuid] == characteristic.uuid,
              let value = characteristic.value else { return }
        let b = [UInt8](value)
        var i = 0
        while i < b.count {
            if b[i] == 0x01, i + 1 < b.count {
                conn.credits = (conn.credits ?? 0) + Int(b[i + 1]); i += 2
            } else if b[i] == 0x02, i + 2 < b.count {
                conn.creditPacketSize = Int(b[i + 1]) | Int(b[i + 2]) << 8; i += 3
            } else {
                break // trame inconnue : ignorée
            }
        }
        if !conn.isOpen, conn.credits != nil {
            Logger.shared.log("ble", "credit-flow-control", ["credits": conn.credits ?? 0])
            finishOpen(conn)
        }
        grantCredits(conn)
    }

    private func failOpen(_ conn: Conn, _ peripheral: CBPeripheral, _ message: String, detail: String? = nil) {
        guard let cont = conn.openCont else { return }
        conn.openCont = nil
        central.cancelPeripheralConnection(peripheral)
        cont.resume(throwing: PrinterError(.UNSUPPORTED_PRINTER, message, detail: detail))
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        let key = peripheral.identifier.uuidString
        guard let conn = conns[key], let ack = conn.writeAck else { return }
        conn.writeAck = nil
        if let error = error {
            ack.resume(throwing: PrinterError(.PRINT_FAILED, "Écriture BLE échouée", detail: error.localizedDescription, retryable: true))
        } else {
            ack.resume()
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        let key = peripheral.identifier.uuidString
        guard let conn = conns[key] else { return }
        let waiters = conn.readyWaiters; conn.readyWaiters.removeAll()
        waiters.forEach { $0.resume() }
    }
}
