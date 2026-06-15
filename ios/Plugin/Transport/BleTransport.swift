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
    ]

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

    func disconnect(_ address: String) {
        queue.async {
            if let c = self.conns[address] {
                self.central.cancelPeripheralConnection(c.peripheral)
                self.conns[address] = nil
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
        let withResponse = char.properties.contains(.write)
        let type: CBCharacteristicWriteType = withResponse ? .withResponse : .withoutResponse
        let maxLen = peripheral.maximumWriteValueLength(for: type)
        let chunkSize = max(20, min(maxLen, 180))
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
        knownPeripherals[key] = peripheral
        onFound?(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices(Self.printerServices) // d'abord les services connus
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
        }
    }
}

// MARK: - CBPeripheralDelegate

extension BleManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let services = peripheral.services ?? []
        if services.isEmpty {
            // Aucun service connu : on tente une découverte large.
            peripheral.discoverServices(nil)
            return
        }
        for service in services {
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let key = peripheral.identifier.uuidString
        guard let conn = conns[key] else { return }
        if conn.writeChar == nil {
            // Première caractéristique inscriptible trouvée (write ou writeWithoutResponse).
            if let ch = (service.characteristics ?? []).first(where: {
                $0.properties.contains(.write) || $0.properties.contains(.writeWithoutResponse)
            }) {
                conn.writeChar = ch
                conn.isOpen = true
                if let cont = conn.openCont { conn.openCont = nil; cont.resume() }
            }
        }
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
