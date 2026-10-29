import Foundation
import CoreBluetooth

/// Lato *central* del dual-role: scansiona il Service UUID, si connette ai nodi AeroMesh,
/// legge il PSM, scambia il `node_id` e apre il canale L2CAP. Mantiene connessioni perenni
/// (`connect(options: nil)` non scade mai) verso i compagni di viaggio fidati, cosi' iOS
/// risveglia l'app in background appena l'amico torna a portata, anche se Android non vede
/// il nostro advertising spostato nell'Overflow Area.
///
/// Tutto lo stato e' confinato alla `queue` CoreBluetooth; gli eventi escono tramite `handler`.
final class BLECentralController: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {

    enum Event: Sendable {
        case stateChanged(RadioState)
        case discovered(deviceID: UUID, rssi: Int)
        case connected(deviceID: UUID)
        case identified(deviceID: UUID, nodeID: Data)
        case channelOpened(L2CAPChannelBox, deviceID: UUID)
        case disconnected(deviceID: UUID, reason: String?)
        case connectionFailed(deviceID: UUID, reason: String?)
    }

    private let queue: DispatchQueue
    private let localNodeID: Data
    private let restoreIdentifier: String
    private let maxConcurrentLinks: Int
    private let handler: @Sendable (Event) -> Void

    private var manager: CBCentralManager?
    /// CoreBluetooth richiede un riferimento forte ai peripheral in uso.
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var psms: [UUID: CBL2CAPPSM] = [:]
    private var pendingOpen: Set<UUID> = []
    private var openChannels: Set<UUID> = []
    private var trustedIdentifiers: Set<UUID> = []
    private var wantsScanning = true

    init(localNodeID: Data,
         queue: DispatchQueue,
         restoreIdentifier: String,
         maxConcurrentLinks: Int = MeshProtocol.maxConcurrentLinks,
         handler: @escaping @Sendable (Event) -> Void) {
        self.localNodeID = localNodeID
        self.queue = queue
        self.restoreIdentifier = restoreIdentifier
        self.maxConcurrentLinks = maxConcurrentLinks
        self.handler = handler
        super.init()
    }

    // MARK: - API (thread-safe)

    func start() {
        queue.async { [self] in
            guard manager == nil else { return }
            manager = CBCentralManager(
                delegate: self,
                queue: queue,
                options: [
                    CBCentralManagerOptionRestoreIdentifierKey: restoreIdentifier,
                    CBCentralManagerOptionShowPowerAlertKey: false,
                ]
            )
        }
    }

    func stop() {
        queue.async { [self] in
            guard let manager else { return }
            if manager.isScanning { manager.stopScan() }
            for peripheral in peripherals.values {
                manager.cancelPeripheralConnection(peripheral)
            }
            peripherals.removeAll()
            psms.removeAll()
            pendingOpen.removeAll()
            openChannels.removeAll()
            self.manager = nil
        }
    }

    func setScanning(_ enabled: Bool) {
        queue.async { [self] in
            wantsScanning = enabled
            guard let manager, manager.state == .poweredOn else { return }
            if enabled {
                startScanIfNeeded()
            } else if manager.isScanning {
                manager.stopScan()
            }
        }
    }

    /// Imposta i peripheral fidati da tenere in connessione perenne.
    func setTrustedPeripherals(_ identifiers: Set<UUID>) {
        queue.async { [self] in
            trustedIdentifiers = identifiers
            connectTrustedPeripherals()
        }
    }

    /// Chiude il link verso il dispositivo (es. link duplicato o peer bloccato).
    func disconnect(deviceID: UUID) {
        queue.async { [self] in
            guard let manager, let peripheral = peripherals[deviceID] else { return }
            manager.cancelPeripheralConnection(peripheral)
        }
    }

    // MARK: - Scansione e connessione

    private func startScanIfNeeded() {
        guard let manager, wantsScanning, manager.state == .poweredOn, !manager.isScanning else { return }
        manager.scanForPeripherals(
            withServices: [MeshProtocol.serviceCBUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    private func connectTrustedPeripherals() {
        guard let manager, manager.state == .poweredOn, !trustedIdentifiers.isEmpty else { return }
        let known = manager.retrievePeripherals(withIdentifiers: Array(trustedIdentifiers))
        for peripheral in known where peripheral.state == .disconnected {
            peripherals[peripheral.identifier] = peripheral
            peripheral.delegate = self
            // Nessuna opzione, nessun timeout: la richiesta resta pendente anche in background.
            manager.connect(peripheral, options: nil)
        }
    }

    private var activeOrPendingLinkCount: Int {
        peripherals.values.filter { $0.state == .connected || $0.state == .connecting }.count
    }

    private func connect(_ peripheral: CBPeripheral) {
        guard let manager else { return }
        peripherals[peripheral.identifier] = peripheral
        peripheral.delegate = self
        manager.connect(peripheral, options: nil)
    }

    private func forget(_ peripheral: CBPeripheral) {
        psms.removeValue(forKey: peripheral.identifier)
        pendingOpen.remove(peripheral.identifier)
        openChannels.remove(peripheral.identifier)
        if !trustedIdentifiers.contains(peripheral.identifier) {
            peripherals.removeValue(forKey: peripheral.identifier)
        }
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        handler(.stateChanged(RadioState(central.state)))
        switch central.state {
        case .poweredOn:
            startScanIfNeeded()
            connectTrustedPeripherals()
        default:
            psms.removeAll()
            pendingOpen.removeAll()
            openChannels.removeAll()
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        // State Restoration: riprendiamo in carico i peripheral che iOS ha tenuto per noi.
        if let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] {
            for peripheral in restored {
                peripherals[peripheral.identifier] = peripheral
                peripheral.delegate = self
                if peripheral.state == .connected {
                    peripheral.discoverServices([MeshProtocol.serviceCBUUID])
                }
            }
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        handler(.discovered(deviceID: peripheral.identifier, rssi: RSSI.intValue))
        let existing = peripherals[peripheral.identifier]
        guard existing == nil || existing?.state == .disconnected else { return }
        guard activeOrPendingLinkCount < maxConcurrentLinks || trustedIdentifiers.contains(peripheral.identifier) else { return }
        connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        handler(.connected(deviceID: peripheral.identifier))
        peripheral.discoverServices([MeshProtocol.serviceCBUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        handler(.connectionFailed(deviceID: peripheral.identifier, reason: error?.localizedDescription))
        forget(peripheral)
        if trustedIdentifiers.contains(peripheral.identifier) {
            central.connect(peripheral, options: nil)
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        handler(.disconnected(deviceID: peripheral.identifier, reason: error?.localizedDescription))
        forget(peripheral)
        if trustedIdentifiers.contains(peripheral.identifier) {
            // Auto-reconnect infinito: iOS risveglia l'app quando il compagno torna a portata.
            central.connect(peripheral, options: nil)
        }
    }

    // MARK: - CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services else {
            manager?.cancelPeripheralConnection(peripheral)
            return
        }
        for service in services where service.uuid == MeshProtocol.serviceCBUUID {
            peripheral.discoverCharacteristics(
                [MeshProtocol.psmCharacteristicCBUUID, MeshProtocol.nodeInfoCharacteristicCBUUID],
                for: service
            )
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil, let characteristics = service.characteristics else {
            manager?.cancelPeripheralConnection(peripheral)
            return
        }
        for characteristic in characteristics {
            switch characteristic.uuid {
            case MeshProtocol.psmCharacteristicCBUUID:
                peripheral.readValue(for: characteristic)
            case MeshProtocol.nodeInfoCharacteristicCBUUID:
                // Handshake fallback: presentiamo il nostro node_id, poi leggiamo il suo.
                if characteristic.properties.contains(.writeWithoutResponse) {
                    peripheral.writeValue(localNodeID, for: characteristic, type: .withoutResponse)
                }
                peripheral.readValue(for: characteristic)
                if characteristic.properties.contains(.notify) {
                    peripheral.setNotifyValue(true, for: characteristic)
                }
            default:
                break
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else { return }
        switch characteristic.uuid {
        case MeshProtocol.psmCharacteristicCBUUID:
            guard let psm = UInt16(littleEndianBytes: value), psm != 0 else { return }
            psms[peripheral.identifier] = CBL2CAPPSM(psm)
            openChannelIfReady(peripheral)
        case MeshProtocol.nodeInfoCharacteristicCBUUID:
            guard value.count == MeshProtocol.nodeIDLength else { return }
            handler(.identified(deviceID: peripheral.identifier, nodeID: value))
        default:
            break
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        // Il peer ha ripubblicato il servizio (es. nuovo PSM dopo un riavvio): ripartiamo dalla discovery.
        psms.removeValue(forKey: peripheral.identifier)
        peripheral.discoverServices([MeshProtocol.serviceCBUUID])
    }

    private func openChannelIfReady(_ peripheral: CBPeripheral) {
        let id = peripheral.identifier
        guard let psm = psms[id], !pendingOpen.contains(id), !openChannels.contains(id) else { return }
        pendingOpen.insert(id)
        peripheral.openL2CAPChannel(psm)
    }

    func peripheral(_ peripheral: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
        pendingOpen.remove(peripheral.identifier)
        guard let channel, error == nil else {
            // Riproviamo da zero: la disconnessione innesca una nuova discovery alla prossima scansione.
            manager?.cancelPeripheralConnection(peripheral)
            return
        }
        openChannels.insert(peripheral.identifier)
        handler(.channelOpened(L2CAPChannelBox(channel: channel), deviceID: peripheral.identifier))
    }
}
