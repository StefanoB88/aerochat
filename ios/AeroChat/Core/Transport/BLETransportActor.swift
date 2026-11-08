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
    }

    nonisolated let localNodeID: Data
    nonisolated let configuration: Configuration

    private let queue = DispatchQueue(label: "AeroMesh.BLE", qos: .userInitiated)
    private let streamThread = RunLoopThread()
    private var central: BLECentralController?
    private var peripheral: BLEPeripheralController?
    private var isStarted = false

    private var centralState: RadioState = .unknown
    private var peripheralState: RadioState = .unknown
    private var isAdvertising = false
    private var publishedPSM: UInt16 = 0

    private var links: [LinkID: LinkState] = [:]
    /// `node_id` appresi via GATT, indicizzati per UUID del dispositivo remoto.
    private var nodeIDsByDevice: [UUID: Data] = [:]
    private var subscribers: [UUID: AsyncStream<TransportEvent>.Continuation] = [:]

    init(localNodeID: Data, configuration: Configuration = Configuration()) {
        precondition(localNodeID.count == MeshProtocol.nodeIDLength, "node_id deve essere di 8 byte")
        self.localNodeID = localNodeID
        self.configuration = configuration
    }

    // MARK: - Ciclo di vita

    func start() {
        guard !isStarted else { return }
        isStarted = true
        streamThread.startAndWaitUntilReady()

        let centralController = BLECentralController(
            localNodeID: localNodeID,
            queue: queue,
            restoreIdentifier: configuration.centralRestoreIdentifier,
            maxConcurrentLinks: configuration.maxConcurrentLinks
        ) { [weak self] event in
            guard let self else { return }
            Task { await self.handle(central: event) }
        }
        let peripheralController = BLEPeripheralController(
            localNodeID: localNodeID,
            queue: queue,
            restoreIdentifier: configuration.peripheralRestoreIdentifier
        ) { [weak self] event in
            guard let self else { return }
            Task { await self.handle(peripheral: event) }
        }
        central = centralController
        peripheral = peripheralController
        centralController.start()
        peripheralController.start()
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
        streamThread.shutdown()
    }

    /// Compagni fidati da tenere in connessione perenne (UUID CoreBluetooth dei loro peripheral).
    func setTrustedPeripheralIdentifiers(_ identifiers: Set<UUID>) {
        central?.setTrustedPeripherals(identifiers)
    }

    func setScanning(_ enabled: Bool) {
        central?.setScanning(enabled)
    }

    func setAdvertising(_ enabled: Bool) {
        peripheral?.setAdvertising(enabled)
    }

    // MARK: - TransportProvider

    func events() -> AsyncStream<TransportEvent> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(256)) { continuation in
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
    }

    func send(_ frame: Data, toNode nodeID: Data) throws -> Bool {
        guard let target = preferredLink(for: nodeID) else { return false }
        try target.connection.send(frame)
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
        let id = LinkID(deviceID: deviceID, role: role)
        if var state = links[id], state.peer.nodeID != nodeID {
            state.peer.nodeID = nodeID
            links[id] = state
            emit(.linkIdentified(id, nodeID: nodeID))
        }
        resolveDuplicateLinks(for: nodeID)
    }

    private func adopt(channel: L2CAPChannelBox, id: LinkID) {
        if let previous = links[id] {
            previous.connection.close()
        }
        let connection = L2CAPConnection(
            id: id,
            inputStream: channel.channel.inputStream,
            outputStream: channel.channel.outputStream,
            thread: streamThread,
            onFrame: { [weak self] frame in
                guard let self else { return }
                Task { await self.received(frame, from: id) }
            },
            onClose: { [weak self] _ in
                guard let self else { return }
                Task { await self.closeLink(id) }
            }
        )
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
        emit(.linkClosed(id, nodeID: state.peer.nodeID))
    }

    /// Quando entrambi i nodi si connettono a vicenda (dual-role) restano due link verso lo stesso
    /// `node_id`. Il nodo con `node_id` maggiore chiude il link che ha iniziato lui, l'altro lo tiene.
    private func resolveDuplicateLinks(for nodeID: Data) {
        let duplicates = links.values.filter { $0.peer.nodeID == nodeID }
        guard duplicates.count > 1,
              !shouldInitiateConnection(to: nodeID),
              let ours = duplicates.first(where: { $0.peer.id.role == .central }) else { return }
        central?.disconnect(deviceID: ours.peer.id.deviceID)
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

/// Astrazione minima di un link che sa spedire frame: `L2CAPConnection` in produzione, finti nei test.
protocol FrameLink: Sendable {
    var id: LinkID { get }
    func send(_ payload: Data) throws
    func close()
}

extension L2CAPConnection: FrameLink {}
