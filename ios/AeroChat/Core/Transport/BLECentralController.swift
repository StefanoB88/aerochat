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
    /// Node Info dei peer (write-without-response): serve ai risvegli di `nudge`.
    private var nodeInfoCharacteristics: [UUID: CBCharacteristic] = [:]
    private var trustedIdentifiers: Set<UUID> = []
    private var wantsScanning = true
    /// Link chiusi perche' duplicati (il peer e' gia' collegato a noi come central): niente
    /// auto-reconnect finche' quel link resta aperto, altrimenti i due iPhone si ricollegano all'infinito.
    private var suppressedReconnects: Set<UUID> = []
    /// Timeout di connessione + handshake (GATT, PSM, L2CAP) per dispositivo.
    private var handshakeTimeouts: [UUID: DispatchWorkItem] = [:]
    /// Riavvio periodico della scansione: con `AllowDuplicates = false` iOS segnala ogni dispositivo
    /// una sola volta per sessione di scansione, quindi dopo un tentativo fallito non lo rivedremmo piu'.
    private var scanRefreshTimer: DispatchSourceTimer?
    /// Lettura periodica dell'RSSI dei link aperti (radar di prossimita'): la scansione non la ripete.
    private var rssiTimer: DispatchSourceTimer?
    /// Tentativi falliti consecutivi per fidato: backoff esponenziale invece di un loop connect/fail.
    private var connectFailures: [UUID: Int] = [:]
    /// Ultimo riavvio della scansione: molti disconnect ravvicinati non devono martellare la radio.
    private var lastScanRestart = Date.distantPast

    static let connectTimeout: TimeInterval = 12
    static let handshakeTimeout: TimeInterval = 15
    static let scanRefreshInterval: TimeInterval = 20
    static let rssiPollInterval: TimeInterval = 4
    static let minimumScanRestartInterval: TimeInterval = 2
    static let maximumReconnectBackoff: TimeInterval = 60

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
            stopScanRefresh()
            stopRSSIPolling()
            handshakeTimeouts.values.forEach { $0.cancel() }
            handshakeTimeouts.removeAll()
            suppressedReconnects.removeAll()
            connectFailures.removeAll()
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

    /// Chiude il link verso il dispositivo (es. link duplicato o peer bloccato). Con `suppressReconnect`
    /// il dispositivo non viene ricollegato (ne' come fidato ne' da scansione) fino a `resumeReconnect`.
    func disconnect(deviceID: UUID, suppressReconnect: Bool = false) {
        queue.async { [self] in
            if suppressReconnect { suppressedReconnects.insert(deviceID) }
            guard let manager, let peripheral = peripherals[deviceID] else { return }
            manager.cancelPeripheralConnection(peripheral)
        }
    }

    /// Il link alternativo verso quel peer si e' chiuso: torniamo a collegarci noi.
    func resumeReconnect(deviceID: UUID) {
        queue.async { [self] in
            guard suppressedReconnects.remove(deviceID) != nil else { return }
            // In background (schermo bloccato) la scansione non ririporta un dispositivo gia' visto:
            // una connessione diretta pendente si completa appena il peer e' a portata.
            let known = peripherals[deviceID] ?? manager?.retrievePeripherals(withIdentifiers: [deviceID]).first
            if let peripheral = known, peripheral.state == .disconnected,
               activeOrPendingLinkCount < maxConcurrentLinks || trustedIdentifiers.contains(deviceID) {
                connect(peripheral)
            } else {
                restartScan()
            }
        }
    }

    /// Risveglia il peer dopo un invio L2CAP: riscrive il nostro `node_id` sul suo Node Info.
    /// Su un iPhone bloccato la write risveglia l'app sospesa (`bluetooth-peripheral`).
    func nudge(deviceID: UUID) {
        queue.async { [self] in
            guard openChannels.contains(deviceID), let peripheral = peripherals[deviceID],
                  peripheral.state == .connected,
                  let characteristic = nodeInfoCharacteristics[deviceID] else { return }
            peripheral.writeValue(localNodeID, for: characteristic, type: .withoutResponse)
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

    /// Nuova sessione di scansione: i dispositivi gia' visti (ma non collegati) vengono segnalati di nuovo.
    private func restartScan() {
        guard let manager, wantsScanning, manager.state == .poweredOn else { return }
        let now = Date()
        guard now.timeIntervalSince(lastScanRestart) >= Self.minimumScanRestartInterval else {
            // Raffica di disconnect: basta il prossimo giro del timer di refresh (o questo riavvio rinviato).
            queue.asyncAfter(deadline: .now() + Self.minimumScanRestartInterval) { [weak self] in
                guard let self, Date().timeIntervalSince(self.lastScanRestart) >= Self.minimumScanRestartInterval else { return }
                self.restartScan()
            }
            return
        }
        lastScanRestart = now
        if manager.isScanning { manager.stopScan() }
        startScanIfNeeded()
    }

    private func startScanRefresh() {
        guard scanRefreshTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + Self.scanRefreshInterval, repeating: Self.scanRefreshInterval, leeway: .seconds(2))
        timer.setEventHandler { [weak self] in self?.restartScan() }
        timer.resume()
        scanRefreshTimer = timer
    }

    private func stopScanRefresh() {
        scanRefreshTimer?.cancel()
        scanRefreshTimer = nil
    }

    private func startRSSIPolling() {
        guard rssiTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + Self.rssiPollInterval, repeating: Self.rssiPollInterval, leeway: .seconds(1))
        timer.setEventHandler { [weak self] in self?.pollRSSI() }
        timer.resume()
        rssiTimer = timer
    }

    private func stopRSSIPolling() {
        rssiTimer?.cancel()
        rssiTimer = nil
    }

    /// Chiede l'RSSI a ogni peripheral con canale aperto; la risposta arriva in `didReadRSSI`.
    private func pollRSSI() {
        for id in openChannels {
            guard let peripheral = peripherals[id], peripheral.state == .connected else { continue }
            peripheral.readRSSI()
        }
    }

    /// Se entro `interval` il canale L2CAP non e' aperto, il tentativo viene annullato e il
    /// dispositivo torna rilevabile (una connessione pendente non scade mai da sola).
    private func armHandshakeTimeout(for peripheral: CBPeripheral, after interval: TimeInterval) {
        let id = peripheral.identifier
        handshakeTimeouts[id]?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.handshakeTimeouts.removeValue(forKey: id)
            guard !self.openChannels.contains(id), let manager = self.manager,
                  let peripheral = self.peripherals[id] else { return }
            let wasPending = peripheral.state == .connecting
            manager.cancelPeripheralConnection(peripheral)
            if wasPending {
                // Una connessione mai riuscita non genera sempre `didDisconnect`: liberiamo lo slot noi.
                self.forget(peripheral)
                self.restartScan()
            }
        }
        handshakeTimeouts[id] = work
        queue.asyncAfter(deadline: .now() + interval, execute: work)
    }

    private func disarmHandshakeTimeout(for id: UUID) {
        handshakeTimeouts.removeValue(forKey: id)?.cancel()
    }

    private func connectTrustedPeripherals() {
        guard let manager, manager.state == .poweredOn, !trustedIdentifiers.isEmpty else { return }
        let known = manager.retrievePeripherals(withIdentifiers: Array(trustedIdentifiers))
        for peripheral in known where peripheral.state == .disconnected && !suppressedReconnects.contains(peripheral.identifier) {
            peripherals[peripheral.identifier] = peripheral
            peripheral.delegate = self
            // Nessuna opzione, nessun timeout: la richiesta resta pendente anche in background.
            manager.connect(peripheral, options: nil)
        }
    }

    /// Link attivi o in corso. Le connessioni perenni verso fidati fuori portata non occupano slot,
    /// altrimenti bastano pochi compagni lontani per non collegarsi piu' a nessuno.
    private var activeOrPendingLinkCount: Int {
        peripherals.values.filter {
            $0.state == .connected || ($0.state == .connecting && !trustedIdentifiers.contains($0.identifier))
        }.count
    }

    private func connect(_ peripheral: CBPeripheral) {
        guard let manager else { return }
        peripherals[peripheral.identifier] = peripheral
        peripheral.delegate = self
        manager.connect(peripheral, options: nil)
        if !trustedIdentifiers.contains(peripheral.identifier) {
            armHandshakeTimeout(for: peripheral, after: Self.connectTimeout + Self.handshakeTimeout)
        }
    }

    private func forget(_ peripheral: CBPeripheral) {
        disarmHandshakeTimeout(for: peripheral.identifier)
        psms.removeValue(forKey: peripheral.identifier)
        pendingOpen.remove(peripheral.identifier)
        openChannels.remove(peripheral.identifier)
        nodeInfoCharacteristics.removeValue(forKey: peripheral.identifier)
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
            startScanRefresh()
            startRSSIPolling()
            connectTrustedPeripherals()
            resumeRestoredPeripherals()
        default:
            stopScanRefresh()
            stopRSSIPolling()
            psms.removeAll()
            pendingOpen.removeAll()
            openChannels.removeAll()
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        // State Restoration: riprendiamo in carico i peripheral che iOS ha tenuto per noi.
        if let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] {
            // Solo presa in carico: le API GATT prima di `.poweredOn` vengono ignorate da CoreBluetooth.
            for peripheral in restored {
                peripherals[peripheral.identifier] = peripheral
                peripheral.delegate = self
            }
        }
    }

    /// Peripheral ripristinati gia' connessi ma senza canale L2CAP: rifacciamo l'handshake (con
    /// timeout), altrimenti occuperebbero per sempre uno degli slot di connessione.
    private func resumeRestoredPeripherals() {
        for peripheral in peripherals.values
        where peripheral.state == .connected && !openChannels.contains(peripheral.identifier) && !pendingOpen.contains(peripheral.identifier) {
            armHandshakeTimeout(for: peripheral, after: Self.handshakeTimeout)
            peripheral.discoverServices([MeshProtocol.serviceCBUUID])
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        handler(.discovered(deviceID: peripheral.identifier, rssi: RSSI.intValue))
        guard !suppressedReconnects.contains(peripheral.identifier) else { return }
        let existing = peripherals[peripheral.identifier]
        guard existing == nil || existing?.state == .disconnected else { return }
        guard activeOrPendingLinkCount < maxConcurrentLinks || trustedIdentifiers.contains(peripheral.identifier) else { return }
        connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        handler(.connected(deviceID: peripheral.identifier))
        armHandshakeTimeout(for: peripheral, after: Self.handshakeTimeout)
        peripheral.discoverServices([MeshProtocol.serviceCBUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        handler(.connectionFailed(deviceID: peripheral.identifier, reason: error?.localizedDescription))
        forget(peripheral)
        let id = peripheral.identifier
        if trustedIdentifiers.contains(id), !suppressedReconnects.contains(id) {
            // Errori ripetibili (chiavi rimosse, limite connessioni): ritenta con backoff 1, 2, 4… 60 s.
            let failures = (connectFailures[id] ?? 0) + 1
            connectFailures[id] = failures
            let delay = min(Self.maximumReconnectBackoff, pow(2, Double(failures - 1)))
            queue.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let self, let manager = self.manager, manager.state == .poweredOn,
                      self.trustedIdentifiers.contains(id), !self.suppressedReconnects.contains(id),
                      let target = self.peripherals[id] ?? manager.retrievePeripherals(withIdentifiers: [id]).first,
                      target.state == .disconnected else { return }
                self.peripherals[id] = target
                target.delegate = self
                manager.connect(target, options: nil)
            }
        }
        restartScan()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        handler(.disconnected(deviceID: peripheral.identifier, reason: error?.localizedDescription))
        forget(peripheral)
        if trustedIdentifiers.contains(peripheral.identifier), !suppressedReconnects.contains(peripheral.identifier) {
            // Auto-reconnect infinito: iOS risveglia l'app quando il compagno torna a portata.
            central.connect(peripheral, options: nil)
        }
        // Il dispositivo deve poter essere rilevato di nuovo dalla scansione.
        restartScan()
    }

    // MARK: - CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        // 127 = valore non disponibile secondo la specifica Bluetooth.
        guard error == nil, RSSI.intValue != 127 else { return }
        handler(.discovered(deviceID: peripheral.identifier, rssi: RSSI.intValue))
    }

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
                    nodeInfoCharacteristics[peripheral.identifier] = characteristic
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
        connectFailures.removeValue(forKey: peripheral.identifier)
        disarmHandshakeTimeout(for: peripheral.identifier)
        handler(.channelOpened(L2CAPChannelBox(channel: channel), deviceID: peripheral.identifier))
    }
}
