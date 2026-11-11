import Foundation
import CoreBluetooth

/// Trasporto BLE dual-role: orchestra `BLECentralController` + `BLEPeripheralController`,
/// adotta i canali L2CAP in `L2CAPConnection` e offre al router un'API uniforme
/// basata su link identificati dal `node_id` del vicino.
actor BLETransportActor: TransportProvider {

    struct Configuration: Sendable {
        var centralRestoreIdentifier = "aeromesh.central"
        var peripheralRestoreIdentifier = "aeromesh.peripheral"
        var maxConcurrentLinks = MeshProtocol.maxConcurrentLinks
    }

    private struct LinkState {
        let connection: any FrameLink
        var peer: PeerLink
        /// Mantiene vivo il `CBL2CAPChannel` finche' il link e' aperto.
        let channel: L2CAPChannelBox?
        /// Identita' della connessione: i callback di una connessione sostituita non toccano la nuova.
        var token: ObjectIdentifier { ObjectIdentifier(connection as AnyObject) }
    }

    /// Tutto cio' che arriva dai thread di CoreBluetooth e della runloop passa da un'unica coda FIFO:
    /// un `Task` per evento non garantisce l'ordine (frame riordinati, close prima degli ultimi frame).
    private enum Input: Sendable {
        case central(BLECentralController.Event)
        case peripheral(BLEPeripheralController.Event)
        case frame(Data, LinkID, ObjectIdentifier)
        case closed(LinkID, ObjectIdentifier)
    }

    static let maxRememberedDevices = 256

    nonisolated let localNodeID: Data
    nonisolated let configuration: Configuration

    private let queue = DispatchQueue(label: "AeroMesh.BLE", qos: .userInitiated)
    /// Nuovo a ogni `start()`: una runloop fermata non si puo' riavviare.
    private var streamThread: RunLoopThread?
    private let inputs: AsyncStream<Input>
    private let inputContinuation: AsyncStream<Input>.Continuation
    private var inputTask: Task<Void, Never>?
    private var central: BLECentralController?
    private var peripheral: BLEPeripheralController?
    private var isStarted = false
    /// Radar di prossimita' a schermo: solo allora il central legge l'RSSI dei link ogni pochi secondi.
    private var wantsProximityRanging = false

    private var centralState: RadioState = .unknown
    private var peripheralState: RadioState = .unknown
    private var isAdvertising = false
    private var publishedPSM: UInt16 = 0

    private var links: [LinkID: LinkState] = [:]
    /// `node_id` appresi via GATT, indicizzati per UUID del dispositivo remoto.
    private var nodeIDsByDevice: [UUID: Data] = [:]
    private var subscribers: [UUID: AsyncStream<TransportEvent>.Continuation] = [:]
    /// Ultimo risveglio GATT per link (vedi `nudgeAfterSend`).
    private var lastWakeNudge: [LinkID: Date] = [:]
    static let wakeNudgeInterval: TimeInterval = 3

    init(localNodeID: Data, configuration: Configuration = Configuration()) {
        precondition(localNodeID.count == MeshProtocol.nodeIDLength, "node_id deve essere di 8 byte")
        self.localNodeID = localNodeID
        self.configuration = configuration
        (inputs, inputContinuation) = AsyncStream.makeStream(of: Input.self, bufferingPolicy: .unbounded)
    }

    // MARK: - Ciclo di vita

    func start() {
        guard !isStarted else { return }
        isStarted = true
        let thread = RunLoopThread()
        thread.startAndWaitUntilReady()
        streamThread = thread
        startInputLoopIfNeeded()

        let continuation = inputContinuation
        let centralController = BLECentralController(
            localNodeID: localNodeID,
            queue: queue,
            restoreIdentifier: configuration.centralRestoreIdentifier,
            maxConcurrentLinks: configuration.maxConcurrentLinks
        ) { event in
            continuation.yield(.central(event))
        }
        let peripheralController = BLEPeripheralController(
            localNodeID: localNodeID,
            queue: queue,
            restoreIdentifier: configuration.peripheralRestoreIdentifier
        ) { event in
            continuation.yield(.peripheral(event))
        }
        central = centralController
        peripheral = peripheralController
        centralController.start()
        peripheralController.start()
        centralController.setRSSIPolling(wantsProximityRanging)
    }

    func stop() {
        guard isStarted else { return }
        isStarted = false
        for (id, state) in links {
            state.connection.close()
            emit(.linkClosed(id, nodeID: state.peer.nodeID))
        }
        links.removeAll()
        central?.stop()
        peripheral?.stop()
        central = nil
        peripheral = nil
        // Le `close()` sono gia' in coda sulla runloop: `shutdown` le esegue prima di fermarla.
        streamThread?.shutdown()
        streamThread = nil
    }

    /// Unico consumatore della coda di input (vive quanto l'attore, sopravvive a stop/start).
    private func startInputLoopIfNeeded() {
        guard inputTask == nil else { return }
        let stream = inputs
        inputTask = Task { [weak self] in
            for await input in stream {
                guard let self else { break }
                await self.process(input)
            }
        }
    }

    private func process(_ input: Input) {
        switch input {
        case .central(let event):
            handle(central: event)
        case .peripheral(let event):
            handle(peripheral: event)
        case .frame(let frame, let id, let token):
            guard links[id]?.token == token else { return }
            received(frame, from: id)
        case .closed(let id, let token):
            guard links[id]?.token == token else { return }
            closeLink(id)
        }
    }

    /// Compagni fidati da tenere in connessione perenne (UUID CoreBluetooth dei loro peripheral).
    func setTrustedPeripheralIdentifiers(_ identifiers: Set<UUID>) {
        central?.setTrustedPeripherals(identifiers)
    }

    func setScanning(_ enabled: Bool) {
        central?.setScanning(enabled)
    }

    func setProximityRanging(_ enabled: Bool) {
        wantsProximityRanging = enabled
        central?.setRSSIPolling(enabled)
    }

    func setAdvertising(_ enabled: Bool) {
        peripheral?.setAdvertising(enabled)
    }

    // MARK: - TransportProvider

    func events() -> AsyncStream<TransportEvent> {
        let id = UUID()
        // Senza limite: scartare eventi (linkOpened/linkClosed) desincronizzerebbe il router.
        return AsyncStream(bufferingPolicy: .unbounded) { continuation in
            subscribers[id] = continuation
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.removeSubscriber(id) }
            }
        }
    }

    func radioState() -> RadioState {
        // La radio e' una sola: se uno dei due manager non e' pronto, non lo e' nessuno.
        centralState.isReady && peripheralState.isReady ? .poweredOn : (centralState.isReady ? peripheralState : centralState)
    }

    func activeLinks() -> [PeerLink] {
        links.values.map(\.peer).sorted { $0.connectedAt < $1.connectedAt }
    }

    func isAdvertisingNow() -> Bool { isAdvertising }
    func currentPSM() -> UInt16 { publishedPSM }

    func send(_ frame: Data, to link: LinkID) throws {
        guard let state = links[link] else { throw TransportError.linkNotFound }
        try state.connection.send(frame)
        nudgeAfterSend(link)
    }

    func send(_ frame: Data, toNode nodeID: Data) throws -> Bool {
        guard let target = preferredLink(for: nodeID) else { return false }
        try target.connection.send(frame)
        nudgeAfterSend(target.peer.id)
        return true
    }

    func broadcast(_ frame: Data, excluding: Set<LinkID> = []) -> Int {
        var reachedNodes: Set<Data> = []
        var count = 0
        for (id, state) in links.sorted(by: { $0.value.peer.connectedAt < $1.value.peer.connectedAt }) {
            if excluding.contains(id) { continue }
            if let nodeID = state.peer.nodeID {
                guard !reachedNodes.contains(nodeID) else { continue }
                reachedNodes.insert(nodeID)
            }
            do {
                try state.connection.send(frame)
                count += 1
                nudgeAfterSend(id)
            } catch {
                continue
            }
        }
        return count
    }

    // MARK: - Eventi dai controller

    private func handle(central event: BLECentralController.Event) {
        switch event {
        case .stateChanged(let state):
            centralState = state
            emit(.radioStateChanged(radioState()))
        case .discovered(let deviceID, let rssi):
            if var state = links[LinkID(deviceID: deviceID, role: .central)] {
                state.peer.rssi = rssi
                links[state.peer.id] = state
            }
            emit(.peerDiscovered(deviceID: deviceID, rssi: rssi))
        case .connected:
            break
        case .identified(let deviceID, let nodeID):
            identify(deviceID: deviceID, nodeID: nodeID, role: .central)
        case .channelOpened(let box, let deviceID):
            adopt(channel: box, id: LinkID(deviceID: deviceID, role: .central))
        case .disconnected(let deviceID, _), .connectionFailed(let deviceID, _):
            closeLink(LinkID(deviceID: deviceID, role: .central))
        }
    }

    private func handle(peripheral event: BLEPeripheralController.Event) {
        switch event {
        case .stateChanged(let state):
            peripheralState = state
            emit(.radioStateChanged(radioState()))
        case .psmPublished(let psm):
            publishedPSM = psm
        case .advertisingChanged(let advertising):
            isAdvertising = advertising
            emit(.advertisingChanged(isAdvertising: advertising))
        case .centralWroteNodeID(let centralID, let nodeID):
            identify(deviceID: centralID, nodeID: nodeID, role: .peripheral)
        case .channelOpened(let box, let centralID):
            adopt(channel: box, id: LinkID(deviceID: centralID, role: .peripheral))
        case .channelFailed:
            break
        }
    }

    // MARK: - Gestione link

    /// Simula l'identificazione GATT di un dispositivo (test senza radio).
    func identifyForTesting(deviceID: UUID, nodeID: Data, role: LinkRole) {
        identify(deviceID: deviceID, nodeID: nodeID, role: role)
    }

    private func identify(deviceID: UUID, nodeID: Data, role: LinkRole) {
        nodeIDsByDevice[deviceID] = nodeID
        // Gli UUID CoreBluetooth ruotano (indirizzi privati Android): oltre la soglia teniamo solo
        // i dispositivi con un link aperto, cosi' la mappa non cresce senza fine nei voli lunghi.
        if nodeIDsByDevice.count > Self.maxRememberedDevices {
            let active = Set(links.keys.map(\.deviceID)).union([deviceID])
            nodeIDsByDevice = nodeIDsByDevice.filter { active.contains($0.key) }
        }
        let id = LinkID(deviceID: deviceID, role: role)
        if var state = links[id], state.peer.nodeID != nodeID {
            state.peer.nodeID = nodeID
            links[id] = state
            emit(.linkIdentified(id, nodeID: nodeID))
        }
        resolveDuplicateLinks(for: nodeID)
    }

    private func adopt(channel: L2CAPChannelBox, id: LinkID) {
        guard let streamThread else { return }
        if links[id] != nil {
            // Riconnessione sullo stesso LinkID: chiudiamo (ed emettiamo linkClosed) il vecchio link.
            closeLink(id)
        }
        let continuation = inputContinuation
        let box = ConnectionTokenBox()
        let connection = L2CAPConnection(
            id: id,
            inputStream: channel.channel.inputStream,
            outputStream: channel.channel.outputStream,
            thread: streamThread,
            onFrame: { frame in
                guard let token = box.token else { return }
                continuation.yield(.frame(frame, id, token))
            },
            onClose: { _ in
                guard let token = box.token else { return }
                continuation.yield(.closed(id, token))
            }
        )
        box.token = ObjectIdentifier(connection)
        register(link: connection, id: id, channel: channel)
        connection.open()
    }

    /// Registra un link gia' pronto (usato da `adopt` e dai test con link finti).
    func register(link connection: any FrameLink, id: LinkID, channel: L2CAPChannelBox? = nil) {
        let peer = PeerLink(id: id, nodeID: nodeIDsByDevice[id.deviceID], rssi: nil, connectedAt: Date())
        links[id] = LinkState(connection: connection, peer: peer, channel: channel)
        emit(.linkOpened(peer))
        if let nodeID = peer.nodeID {
            resolveDuplicateLinks(for: nodeID)
        }
    }

    /// Chiamato dal link quando riceve un frame completo (anche dai test).
    func received(_ frame: Data, from id: LinkID) {
        guard let state = links[id] else { return }
        emit(.frameReceived(frame, from: id, nodeID: state.peer.nodeID))
    }

    func closeLink(_ id: LinkID) {
        guard let state = links.removeValue(forKey: id) else { return }
        state.connection.close()
        lastWakeNudge.removeValue(forKey: id)
        emit(.linkClosed(id, nodeID: state.peer.nodeID))

        // Era il link "tenuto" di una coppia duplicata: il nostro lato central puo' ricollegarsi.
        if id.role == .peripheral, let nodeID = state.peer.nodeID,
           !links.values.contains(where: { $0.peer.nodeID == nodeID }) {
            // Anche il dispositivo del link appena chiuso: CoreBluetooth usa lo stesso UUID per il
            // CBCentral e il CBPeripheral di un telefono, e la soppressione anti-duplicato sta proprio li'.
            for (deviceID, known) in nodeIDsByDevice where known == nodeID {
                central?.resumeReconnect(deviceID: deviceID)
            }
        }
    }

    /// Risveglio dopo un invio: un iPhone bloccato ha l'app sospesa e i dati L2CAP da soli non
    /// garantiscono il risveglio; un evento GATT si' (write ricevuta in `bluetooth-peripheral`, notify
    /// ricevuta in `bluetooth-central`). Ripresentiamo il nostro `node_id` sul Node Info: per il peer e'
    /// un handshake idempotente (compatibile con Android e versioni precedenti). Max uno ogni 3 s per link.
    private func nudgeAfterSend(_ id: LinkID) {
        let now = Date()
        if let last = lastWakeNudge[id], now.timeIntervalSince(last) >= 0,
           now.timeIntervalSince(last) < Self.wakeNudgeInterval { return }
        lastWakeNudge[id] = now
        switch id.role {
        case .central: central?.nudge(deviceID: id.deviceID)
        case .peripheral: peripheral?.nudge(centralID: id.deviceID)
        }
    }

    /// Quando entrambi i nodi si connettono a vicenda (dual-role) restano due link verso lo stesso
    /// `node_id`. Il nodo con `node_id` maggiore chiude il link che ha iniziato lui, l'altro lo tiene.
    private func resolveDuplicateLinks(for nodeID: Data) {
        let duplicates = links.values.filter { $0.peer.nodeID == nodeID }
        guard duplicates.count > 1,
              !shouldInitiateConnection(to: nodeID),
              let ours = duplicates.first(where: { $0.peer.id.role == .central }) else { return }
        // Senza soppressione il fidato verrebbe ricollegato subito, creando un nuovo duplicato all'infinito.
        central?.disconnect(deviceID: ours.peer.id.deviceID, suppressReconnect: true)
        closeLink(ours.peer.id)
    }

    /// Politica deterministica anti-doppio-link: inizia chi ha il `node_id` lessicograficamente minore.
    nonisolated func shouldInitiateConnection(to peerNodeID: Data) -> Bool {
        localNodeID.lexicographicallyPrecedes(peerNodeID)
    }

    private func preferredLink(for nodeID: Data) -> LinkState? {
        let candidates = links.values.filter { $0.peer.nodeID == nodeID }
        return candidates.first { $0.peer.id.role == .central } ?? candidates.first
    }

    // MARK: - Eventi verso il router

    private func emit(_ event: TransportEvent) {
        for continuation in subscribers.values {
            continuation.yield(event)
        }
    }

    private func removeSubscriber(_ id: UUID) {
        subscribers.removeValue(forKey: id)
    }
}

/// Token della connessione, impostato subito dopo la creazione (i callback non partono prima di `open()`).
private final class ConnectionTokenBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: ObjectIdentifier?
    var token: ObjectIdentifier? {
        get { lock.withLock { value } }
        set { lock.withLock { value = newValue } }
    }
}

/// Astrazione minima di un link che sa spedire frame: `L2CAPConnection` in produzione, finti nei test.
protocol FrameLink: AnyObject, Sendable {
    var id: LinkID { get }
    func send(_ payload: Data) throws
    func close()
}

extension L2CAPConnection: FrameLink {}
