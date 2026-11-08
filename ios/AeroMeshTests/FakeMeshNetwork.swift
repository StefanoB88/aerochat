import Foundation
@testable import AeroChat

/// Trasporto finto in memoria: piu' `FakeTransport` collegati tra loro simulano link L2CAP
/// bidirezionali gia' identificati (come dopo lo scambio del node_id via GATT).
actor FakeTransport: TransportProvider {

    struct Sent: Sendable {
        let link: LinkID
        let frame: Data
    }

    let localNodeID: Data
    private var remotes: [LinkID: (transport: FakeTransport, remoteLink: LinkID)] = [:]
    private var peers: [LinkID: PeerLink] = [:]
    private var subscribers: [UUID: AsyncStream<TransportEvent>.Continuation] = [:]
    private(set) var sent: [Sent] = []
    private(set) var trustedIdentifiers: Set<UUID> = []
    var state: RadioState = .poweredOn
    /// Frame in arrivo trattenuti (consegna fuori ordine simulata con `releaseHeldFrames`).
    private var holdsIncoming = false
    private var heldFrames: [(frame: Data, link: LinkID)] = []

    init(localNodeID: Data) {
        self.localNodeID = localNodeID
    }

    func start() {}
    func stop() {}

    func events() -> AsyncStream<TransportEvent> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .unbounded) { continuation in
            subscribers[id] = continuation
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.remove(id) }
            }
        }
    }

    func radioState() -> RadioState { state }

    func activeLinks() -> [PeerLink] { Array(peers.values) }

    func send(_ frame: Data, to link: LinkID) async throws {
        guard let remote = remotes[link] else { throw TransportError.linkNotFound }
        sent.append(Sent(link: link, frame: frame))
        await remote.transport.inject(frame, from: remote.remoteLink)
    }

    func send(_ frame: Data, toNode nodeID: Data) async throws -> Bool {
        guard let link = peers.values.first(where: { $0.nodeID == nodeID }) else { return false }
        try await send(frame, to: link.id)
        return true
    }

    func broadcast(_ frame: Data, excluding: Set<LinkID>) async -> Int {
        var reached: Set<Data> = []
        var count = 0
        for (id, peer) in peers where !excluding.contains(id) {
            guard let nodeID = peer.nodeID, !reached.contains(nodeID) else { continue }
            reached.insert(nodeID)
            try? await send(frame, to: id)
            count += 1
        }
        return count
    }

    func setTrustedPeripheralIdentifiers(_ identifiers: Set<UUID>) {
        trustedIdentifiers = identifiers
    }

    // MARK: - Simulazione

    func inject(_ frame: Data, from link: LinkID) {
        if holdsIncoming {
            heldFrames.append((frame, link))
            return
        }
        emit(.frameReceived(frame, from: link, nodeID: peers[link]?.nodeID))
    }

    /// Da ora i frame in arrivo restano in attesa (link "lento").
    func holdIncoming() {
        holdsIncoming = true
    }

    func heldFrameCount() -> Int { heldFrames.count }

    /// Consegna i frame trattenuti, in ordine inverso se `reversed` (arrivo fuori ordine).
    func releaseHeldFrames(reversed: Bool) {
        holdsIncoming = false
        let frames = reversed ? Array(heldFrames.reversed()) : heldFrames
        heldFrames.removeAll()
        for held in frames {
            emit(.frameReceived(held.frame, from: held.link, nodeID: peers[held.link]?.nodeID))
        }
    }

    func attach(_ remote: FakeTransport, localLink: LinkID, remoteLink: LinkID, rssi: Int = -60) {
        remotes[localLink] = (remote, remoteLink)
        let peer = PeerLink(id: localLink, nodeID: remote.localNodeID, rssi: rssi, connectedAt: Date())
        peers[localLink] = peer
        emit(.linkOpened(peer))
    }

    func detach(localLink: LinkID) {
        remotes.removeValue(forKey: localLink)
        if let peer = peers.removeValue(forKey: localLink) {
            emit(.linkClosed(localLink, nodeID: peer.nodeID))
        }
    }

    func framesSent() -> Int { sent.count }

    private func emit(_ event: TransportEvent) {
        for continuation in subscribers.values { continuation.yield(event) }
    }

    private func remove(_ id: UUID) { subscribers.removeValue(forKey: id) }
}

/// Collega due trasporti finti con un link bidirezionale.
enum FakeMeshNetwork {
    @discardableResult
    static func connect(_ a: FakeTransport, _ b: FakeTransport) async -> (LinkID, LinkID) {
        let linkA = LinkID(deviceID: UUID(), role: .central)
        let linkB = LinkID(deviceID: UUID(), role: .peripheral)
        await a.attach(b, localLink: linkA, remoteLink: linkB)
        await b.attach(a, localLink: linkB, remoteLink: linkA)
        return (linkA, linkB)
    }

    static func disconnect(_ a: FakeTransport, _ linkA: LinkID, _ b: FakeTransport, _ linkB: LinkID) async {
        await a.detach(localLink: linkA)
        await b.detach(localLink: linkB)
    }
}

/// Nodo di test completo: identita', DB temporaneo, trasporto finto e router avviato.
struct TestNode: Sendable {
    let nickname: String
    let identity: IdentityKeys
    let database: AppDatabase
    let transport: FakeTransport
    let router: MeshRouterActor
    let notifier: RecordingNotifier

    static func make(_ nickname: String, relayEnabled: Bool = true, relayBackoff: ClosedRange<Double> = 0.0...0.05) async throws -> TestNode {
        let identity = IdentityKeys.generate()
        let database = try AppDatabase.makeTemporary()
        try await database.identities.save(IdentityRecord(identity: identity, nickname: nickname, avatarColorHex: "00E5FF"))
        let transport = FakeTransport(localNodeID: identity.nodeID)
        let notifier = RecordingNotifier()
        var configuration = MeshRouterActor.Configuration()
        configuration.relayEnabled = relayEnabled
        configuration.relayBackoff = relayBackoff
        configuration.maintenanceInterval = 3_600
        let router = MeshRouterActor(identity: identity, database: database, transport: transport,
                                     nickname: nickname, notifier: notifier, configuration: configuration)
        await router.start()
        return TestNode(nickname: nickname, identity: identity, database: database, transport: transport, router: router, notifier: notifier)
    }

    var pairingPayload: PairingPayload { PairingPayload(identity: identity, nickname: nickname) }

    var nodeID: Data { identity.nodeID }

    /// Pairing reciproco via QR (entrambi si scansionano).
    static func pair(_ a: TestNode, _ b: TestNode) async throws {
        try await a.router.pair(with: b.pairingPayload)
        try await b.router.pair(with: a.pairingPayload)
    }

    func directConversation(with other: TestNode) async throws -> ConversationRecord {
        guard let peer = try await database.peers.fetch(nodeID: other.nodeID) else { throw RouterError.unknownPeer(other.nodeID) }
        return try await database.conversations.ensureDirectConversation(with: peer)
    }
}

final class RecordingNotifier: MeshNotifier, @unchecked Sendable {
    private let lock = NSLock()
    private var _notified: [MessageRecord] = []
    var notified: [MessageRecord] { lock.withLock { _notified } }
    func notify(message: MessageRecord, conversation: ConversationRecord) async {
        lock.withLock { _notified.append(message) }
    }
}

/// Attende (max `timeout`) che la condizione asincrona diventi vera (un errore conta come `false`).
func eventually(timeout: TimeInterval = 5, interval: Duration = .milliseconds(25),
                _ condition: @Sendable () async throws -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if (try? await condition()) == true { return true }
        try? await Task.sleep(for: interval)
    }
    return (try? await condition()) == true
}
