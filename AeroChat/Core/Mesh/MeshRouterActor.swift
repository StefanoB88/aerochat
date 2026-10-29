import Foundation
import CryptoKit

/// Motore mesh: managed flooding anti-loop, routing verso le nostre conversazioni, relay con filtro
/// media, Store-and-Forward, `DELIVERY_ACK` e Group Key Gossip.
actor MeshRouterActor {

    struct Configuration: Sendable {
        var seenCacheCapacity = 4_096
        var maintenanceInterval: TimeInterval = 600
        var relayEnabled = true
        /// Finestra entro cui un `last_seen` via ponte conta come "connesso tramite ponte".
        var bridgeFreshness: TimeInterval = 120
        /// Anti-replay: pacchetti piu' vecchi di cosi' vengono scartati (coda store-and-forward 6 h + tolleranza).
        var replayWindow: TimeInterval = MeshProtocol.relayQueueLifetime + 15 * 60
        /// Anti-replay: tolleranza per `created_at_ms` nel futuro (orologi non sincronizzati).
        var futureSkewTolerance: TimeInterval = 5 * 60
        /// Backoff casuale (secondi) prima di ritrasmettere: se nel frattempo un vicino ritrasmette
        /// lo stesso pacchetto, il nostro relay viene annullato (meno traffico in cabina piena).
        var relayBackoff: ClosedRange<Double> = 0.05...0.3
    }

    private enum Target {
        case me
        case group(ConversationRecord)
        case lounge(ConversationRecord)
        case other
    }

    let identity: IdentityKeys
    let database: AppDatabase
    let transport: any TransportProvider
    private let notifier: any MeshNotifier
    private let configuration: Configuration

    private var nickname: String
    private var seen: SeenPacketCache
    private var links: [LinkID: PeerLink] = [:]
    private var blocked: Set<Data> = []
    private var isRunning = false
    private var eventTask: Task<Void, Never>?
    private var maintenanceTask: Task<Void, Never>?
    private var subscribers: [UUID: AsyncStream<RouterEvent>.Continuation] = [:]
    private var inboundSubscribers: [UUID: AsyncStream<InboundPacket>.Continuation] = [:]
    /// Relay in attesa del backoff, per `msg_id`.
    private var pendingRelays: [Data: Task<Void, Never>] = [:]
    private(set) var stats = RouterStats()
    private(set) var radioState: RadioState = .unknown

    init(identity: IdentityKeys,
         database: AppDatabase,
         transport: any TransportProvider,
         nickname: String,
         notifier: any MeshNotifier = NoopNotifier(),
         configuration: Configuration = Configuration()) {
        self.identity = identity
        self.database = database
        self.transport = transport
        self.nickname = nickname
        self.notifier = notifier
        self.configuration = configuration
        self.seen = SeenPacketCache(capacity: configuration.seenCacheCapacity)
    }

    // MARK: - Ciclo di vita

    func start() async {
        guard !isRunning else { return }
        isRunning = true

        let now = Date()
        if let persisted = try? await database.seenPackets.activeMessageIDs(now: now) {
            seen.preload(persisted, expiresAtMs: now.addingTimeInterval(MeshProtocol.seenPacketLifetime).unixMilliseconds)
        }
        blocked = (try? await database.peers.blockedNodeIDs()) ?? []

        let stream = await transport.events()
        eventTask = Task { [weak self] in
            for await event in stream {
                guard let self else { break }
                await self.handle(event)
            }
        }
        await transport.start()
        await refreshTrustedPeers()
        radioState = await transport.radioState()

        let interval = configuration.maintenanceInterval
        maintenanceTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(interval))
                guard !Task.isCancelled, let self else { break }
                await self.runMaintenance()
            }
        }
    }

    func stop() async {
        guard isRunning else { return }
        isRunning = false
        eventTask?.cancel()
        maintenanceTask?.cancel()
        eventTask = nil
        maintenanceTask = nil
        pendingRelays.values.forEach { $0.cancel() }
        pendingRelays.removeAll()
        await transport.stop()
        links.removeAll()
    }

    func setNickname(_ nickname: String) {
        self.nickname = nickname
    }

    func events() -> AsyncStream<RouterEvent> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(128)) { continuation in
            subscribers[id] = continuation
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.removeSubscriber(id) }
            }
        }
    }

    /// Pacchetti media / giochi / Pro Pass gia' decifrati e verificati.
    func inboundPackets() -> AsyncStream<InboundPacket> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(256)) { continuation in
            inboundSubscribers[id] = continuation
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.removeInboundSubscriber(id) }
            }
        }
    }

    func neighbors() -> [Neighbor] {
        var byNode: [Data: Neighbor] = [:]
        for link in links.values {
            guard let nodeID = link.nodeID else { continue }
            if var existing = byNode[nodeID] {
                existing.linkCount += 1
                existing.rssi = max(existing.rssi ?? Int.min, link.rssi ?? Int.min) == Int.min ? nil : max(existing.rssi ?? Int.min, link.rssi ?? Int.min)
                byNode[nodeID] = existing
            } else {
                byNode[nodeID] = Neighbor(nodeID: nodeID, rssi: link.rssi, linkCount: 1)
            }
        }
        return byNode.values.sorted { $0.nodeID.lexicographicallyPrecedes($1.nodeID) }
    }

    /// Stato radio semantico verso un nodo (header chat).
    func reachability(of nodeID: Data, now: Date = Date()) async -> Reachability {
        if let neighbor = neighbors().first(where: { $0.nodeID == nodeID }) {
            return .direct(rssi: neighbor.rssi)
        }
        guard let peer = try? await database.peers.fetch(nodeID: nodeID),
              let seenAt = peer.lastSeenAtMs,
              let hops = peer.lastHopCount,
              hops > 1,
              Date(unixMilliseconds: seenAt).addingTimeInterval(configuration.bridgeFreshness) > now else {
            return .outOfRange
        }
        return .viaBridge(hops: hops)
    }

    /// Stato radio di una conversazione: diretta = quella del peer; gruppo = il migliore tra i membri.
    func reachability(of conversation: ConversationRecord) async -> Reachability {
        switch conversation.type {
        case .direct:
            guard let peerID = conversation.peerNodeID else { return .outOfRange }
            return await reachability(of: peerID)
        case .group:
            let members = (try? await database.conversations.groupMembers(of: conversation.id)) ?? []
            var best: Reachability = .outOfRange
            for member in members {
                let candidate = await reachability(of: member.nodeID)
                switch (candidate, best) {
                case (.direct, _): return candidate
                case (.viaBridge, .outOfRange): best = candidate
                case (.viaBridge(let a), .viaBridge(let b)) where a < b: best = candidate
                default: break
                }
            }
            return best
        case .cabinLounge:
            return neighbors().isEmpty ? .outOfRange : .direct(rssi: neighbors().compactMap(\.rssi).max())
        }
    }

    func refreshTrustedPeers() async {
        if let identifiers = try? await database.peers.trustedPeripheralIdentifiers() {
            await transport.setTrustedPeripheralIdentifiers(identifiers)
        }
    }

    func refreshBlockedPeers() async {
        blocked = (try? await database.peers.blockedNodeIDs()) ?? []
    }

    // MARK: - Pairing e gruppi

    /// Pairing da QR: salva il compagno come fidato, crea la chat 1-a-1 e, se il QR porta un gruppo, vi entra.
    @discardableResult
    func pair(with payload: PairingPayload) async throws -> ConversationRecord {
        let peer = try await database.peers.upsert(PeerRecord(pairing: payload, isTrusted: true))
        let direct = try await database.conversations.ensureDirectConversation(with: peer)
        await refreshTrustedPeers()
        if payload.isGroupInvite {
            return try await joinGroup(from: payload, inviter: peer)
        }
        return direct
    }

    /// Crea un gruppo di viaggio locale (noi siamo il primo membro).
    func createGroup(title: String) async throws -> ConversationRecord {
        try await database.conversations.createGroup(title: title)
    }

    /// Entra nel gruppo descritto dal QR e annuncia la propria adesione all'invitante (Group Key Gossip).
    func joinGroup(from payload: PairingPayload, inviter: PeerRecord) async throws -> ConversationRecord {
        guard let groupID = payload.groupID, let key = payload.groupSecretKey else { throw RouterError.invalidGossip("QR senza gruppo") }
        let group = try await database.conversations.createGroup(id: groupID, title: String(localized: "Gruppo di viaggio"), secretKey: key, members: [inviter])
        try await database.conversations.addGroupMember(inviter, to: group.id)
        emit(.groupMembersChanged(conversationID: group.id))
        try await sendGossip(for: group, to: inviter)
        return group
    }

    /// Aggiunge un compagno gia' noto al gruppo e propaga il gossip a tutti i membri.
    func invite(_ peer: PeerRecord, to group: ConversationRecord) async throws {
        try await database.conversations.addGroupMember(peer, to: group.id)
        emit(.groupMembersChanged(conversationID: group.id))
        try await propagateGossip(for: group, excluding: nil)
    }

    /// QR del gruppo da mostrare agli altri: identita' personale + chiave del gruppo.
    func groupInvitePayload(for group: ConversationRecord) -> PairingPayload? {
        guard let groupID = group.groupID, let key = group.groupSecretKey else { return nil }
        return PairingPayload(identity: identity, nickname: nickname, groupID: groupID, groupSecretKey: key)
    }

    // MARK: - Invio

    /// Accetta subito il messaggio in chat (stato `queued`) e lo immette nella mesh.
    @discardableResult
    func sendText(_ text: String, in conversation: ConversationRecord, replyTo: Data? = nil) async throws -> MessageRecord {
        guard isRunning else { throw RouterError.notRunning }
        var message = MessageRecord.outgoingText(text, conversationID: conversation.id, sender: identity,
                                                 senderNickname: nickname, replyTo: replyTo)
        try await database.messages.insert(message)

        let payload = InnerPayload.text(text, identity: identity, nickname: nickname, replyTo: replyTo)
        let type: PacketType = conversation.type == .cabinLounge ? .publicLounge : .chatText
        let envelope = try await makeEnvelope(type: type, payload: payload, conversation: conversation, msgID: message.msgID)
        let reached = await dispatch(envelope, storeIfUnreachable: true)
        message.deliveryStatus = reached ? .sentMesh : .queued
        try await database.messages.updateDeliveryStatus(message.deliveryStatus, msgID: message.msgID)
        return message
    }

    /// Invio generico (media, giochi, Pro Pass). Ritorna il `msg_id` e se almeno un vicino e' stato raggiunto.
    @discardableResult
    func sendPacket(type: PacketType,
                    payload: InnerPayload,
                    in conversation: ConversationRecord,
                    msgID: Data = CryptoEngine.randomMessageID(),
                    ttl: UInt32 = MeshProtocol.defaultTTL,
                    storeIfUnreachable: Bool = true) async throws -> (msgID: Data, reached: Bool) {
        guard isRunning else { throw RouterError.notRunning }
        let envelope = try await makeEnvelope(type: type, payload: payload, conversation: conversation, msgID: msgID, ttl: ttl)
        let reached = await dispatch(envelope, storeIfUnreachable: storeIfUnreachable)
        return (msgID, reached)
    }

    /// Invio diretto a un peer noto, senza passare da una conversazione (gossip, ack, pro pass).
    @discardableResult
    func sendDirectPacket(type: PacketType, payload: InnerPayload, to peer: PeerRecord,
                          ttl: UInt32 = MeshProtocol.defaultTTL, storeIfUnreachable: Bool = true) async throws -> Bool {
        let key = try identity.directChatKey(peerX25519PublicKeyRaw: peer.x25519Pub)
        let envelope = try EnvelopeBuilder(identity: identity).build(packetType: type, recipientID: peer.nodeID, payload: payload, key: key, ttl: ttl)
        return await dispatch(envelope, storeIfUnreachable: storeIfUnreachable)
    }

    private func makeEnvelope(type: PacketType, payload: InnerPayload, conversation: ConversationRecord,
                              msgID: Data, ttl: UInt32 = MeshProtocol.defaultTTL) async throws -> MeshEnvelope {
        let key = try await conversationKey(conversation)
        return try EnvelopeBuilder(identity: identity).build(packetType: type,
                                                             recipientID: conversation.recipientID,
                                                             payload: payload,
                                                             key: key,
                                                             ttl: ttl,
                                                             msgID: msgID)
    }

    private func conversationKey(_ conversation: ConversationRecord) async throws -> SymmetricKey {
        switch conversation.type {
        case .direct:
            guard let peerID = conversation.peerNodeID,
                  let peer = try await database.peers.fetch(nodeID: peerID) else { throw RouterError.invalidConversation }
            return try identity.directChatKey(peerX25519PublicKeyRaw: peer.x25519Pub)
        case .group:
            guard let secret = conversation.groupSecretKey else { throw RouterError.missingGroupKey }
            return try CryptoEngine.groupKey(fromSecret: secret)
        case .cabinLounge:
            return CryptoEngine.publicCabinKey
        }
    }

    /// Immette l'envelope nella mesh: link diretto se esiste, altrimenti flooding; se nessun vicino,
    /// Store-and-Forward per i pacchetti idonei. Ritorna `true` se almeno un vicino e' stato raggiunto.
    private func dispatch(_ envelope: MeshEnvelope, storeIfUnreachable: Bool, excluding: Set<LinkID> = []) async -> Bool {
        _ = seen.insertIfNew(envelope.msgID)
        guard let bytes = try? MeshWire.serialize(envelope) else { return false }

        var reached = 0
        let isMulticast = envelope.recipientID == MeshProtocol.loungeRecipientID || isGroupRecipient(envelope.recipientID)
        if !isMulticast, (try? await transport.send(bytes, toNode: envelope.recipientID)) == true {
            reached = 1
        } else {
            reached = await transport.broadcast(bytes, excluding: excluding)
        }

        if reached == 0, storeIfUnreachable, envelope.packetType.isStoreAndForwardEligible {
            let record = RelayQueueRecord(msgID: envelope.msgID, recipientID: envelope.recipientID, rawEnvelopeBytes: bytes)
            if (try? await database.relayQueue.enqueue(record)) != nil {
                stats.queued += 1
                await emitRelayQueueCount()
            }
        }
        return reached > 0
    }

    private var knownGroupRecipientIDs: Set<Data> = []

    private func isGroupRecipient(_ recipientID: Data) -> Bool {
        knownGroupRecipientIDs.contains(recipientID)
    }

    // MARK: - Eventi trasporto

    private func handle(_ event: TransportEvent) async {
        switch event {
        case .radioStateChanged(let state):
            radioState = state
            emit(.radioStateChanged(state))
        case .advertisingChanged:
            break
        case .peerDiscovered(let deviceID, let rssi):
            for (id, var link) in links where id.deviceID == deviceID {
                link.rssi = rssi
                links[id] = link
            }
        case .linkOpened(let link):
            links[link.id] = link
            if let nodeID = link.nodeID {
                await peerCameOnline(nodeID, via: link)
            }
            emit(.neighborsChanged(neighbors()))
        case .linkIdentified(let id, let nodeID):
            guard var link = links[id] else { return }
            link.nodeID = nodeID
            links[id] = link
            await peerCameOnline(nodeID, via: link)
            emit(.neighborsChanged(neighbors()))
        case .linkClosed(let id, _):
            links.removeValue(forKey: id)
            emit(.neighborsChanged(neighbors()))
        case .frameReceived(let data, let from, _):
            await receive(data, from: from)
        }
    }

    private func peerCameOnline(_ nodeID: Data, via link: PeerLink) async {
        try? await database.peers.markSeen(nodeID: nodeID, hopCount: 1, rssi: link.rssi)
        if link.id.role == .central {
            try? await database.peers.setBLEIdentifier(link.id.deviceID, nodeID: nodeID)
        }
        await flushRelayQueue(toward: nodeID)
    }

    /// Svuota la coda Store-and-Forward verso il nuovo vicino (diretto se destinatario, altrimenti flooding).
    func flushRelayQueue(toward nodeID: Data? = nil) async {
        guard let pending = try? await database.relayQueue.pending(), !pending.isEmpty else { return }
        var flushed: [Data] = []
        for record in pending {
            var sent = false
            if let nodeID, record.recipientID == nodeID {
                sent = (try? await transport.send(record.rawEnvelopeBytes, toNode: nodeID)) ?? false
            }
            if !sent {
                sent = await transport.broadcast(record.rawEnvelopeBytes, excluding: []) > 0
            }
            if sent {
                flushed.append(record.msgID)
                try? await database.messages.advanceDeliveryStatus(to: .sentMesh, msgID: record.msgID)
            }
        }
        if !flushed.isEmpty {
            try? await database.relayQueue.remove(msgIDs: flushed)
            await emitRelayQueueCount()
        }
    }

    // MARK: - Ricezione

    /// Entry point di ogni frame ricevuto dalla radio (pubblico per i test).
    func receive(_ data: Data, from link: LinkID) async {
        guard let envelope = try? MeshWire.parseEnvelope(data),
              (try? EnvelopeVerifier.validateShape(envelope)) != nil else {
            stats.malformed += 1
            return
        }
        // 1. Anti-replay: finestra temporale (ne' troppo vecchio, ne' nel futuro).
        let ageMs = Date.nowUnixMilliseconds - envelope.createdAtMs
        if ageMs > Int64(configuration.replayWindow * 1_000) || -ageMs > Int64(configuration.futureSkewTolerance * 1_000) {
            stats.replayRejected += 1
            return
        }
        // 2. Managed flooding anti-loop: scarto in memoria, prima di qualsiasi I/O.
        guard seen.insertIfNew(envelope.msgID) else {
            stats.duplicates += 1
            // Un vicino ha gia' ritrasmesso questo pacchetto: il nostro relay in attesa e' superfluo.
            cancelPendingRelay(for: envelope.msgID)
            return
        }
        stats.received += 1
        let hops = Int(envelope.hopCount) + 1

        // 3. Routing.
        let target = await resolveTarget(envelope.recipientID)
        switch target {
        case .me:
            await deliver(envelope, hops: hops, via: link, target: target)
        case .group, .lounge:
            await deliver(envelope, hops: hops, via: link, target: target)
            await relayIfAllowed(envelope, from: link, target: target)
        case .other:
            await relayIfAllowed(envelope, from: link, target: target)
        }
        _ = try? await database.seenPackets.markSeenIfNew(msgID: envelope.msgID)
    }

    private func resolveTarget(_ recipientID: Data) async -> Target {
        if recipientID == identity.nodeID { return .me }
        if recipientID == MeshProtocol.loungeRecipientID {
            if let lounge = try? await database.conversations.currentCabinLounge() { return .lounge(lounge) }
            return .other
        }
        if let conversation = try? await database.conversations.fetch(recipientID: recipientID), conversation.type == .group {
            knownGroupRecipientIDs.insert(recipientID)
            return .group(conversation)
        }
        return .other
    }

    /// Relay con TTL/hop e filtro media:
    /// - mai oltre 16 KB di payload;
    /// - `MEDIA_*` solo come primo e unico ponte (`hop_count == 0`) e solo se siamo membri del gruppo destinatario.
    private func relayIfAllowed(_ envelope: MeshEnvelope, from link: LinkID, target: Target) async {
        guard configuration.relayEnabled, envelope.ttl > 1 else { return }
        guard envelope.encryptedPayload.count <= MeshProtocol.maxRelayPayloadBytes else { return }
        if envelope.packetType.isMedia {
            guard envelope.hopCount == 0, case .group = target else { return }
        }
        var forwarded = envelope
        forwarded.ttl -= 1
        forwarded.hopCount += 1
        guard let bytes = try? MeshWire.serialize(forwarded) else { return }

        // Backoff casuale: in una cabina piena molti vicini ricevono lo stesso pacchetto nello stesso
        // istante; chi estrae il ritardo piu' corto ritrasmette, gli altri sentono il duplicato e rinunciano.
        let msgID = forwarded.msgID
        let recipientID = forwarded.recipientID
        let type = forwarded.packetType
        let delay = Double.random(in: configuration.relayBackoff)
        pendingRelays[msgID]?.cancel()
        pendingRelays[msgID] = Task { [weak self] in
            if delay > 0 {
                try? await Task.sleep(for: .seconds(delay))
            }
            guard !Task.isCancelled, let self else { return }
            await self.performRelay(bytes: bytes, msgID: msgID, recipientID: recipientID, type: type, excluding: link)
        }
    }

    private func performRelay(bytes: Data, msgID: Data, recipientID: Data, type: PacketType, excluding link: LinkID) async {
        guard pendingRelays.removeValue(forKey: msgID) != nil else { return }
        let reached = await transport.broadcast(bytes, excluding: [link])
        if reached > 0 {
            stats.relayed += 1
        } else if type.isStoreAndForwardEligible {
            let record = RelayQueueRecord(msgID: msgID, recipientID: recipientID, rawEnvelopeBytes: bytes)
            if (try? await database.relayQueue.enqueue(record)) != nil {
                stats.queued += 1
                await emitRelayQueueCount()
            }
        }
    }

    private func cancelPendingRelay(for msgID: Data) {
        guard let task = pendingRelays.removeValue(forKey: msgID) else { return }
        task.cancel()
        stats.relaySuppressed += 1
    }

    private func deliver(_ envelope: MeshEnvelope, hops: Int, via link: LinkID, target: Target) async {
        if blocked.contains(envelope.senderNodeID) { return }
        do {
            switch (envelope.packetType, target) {
            case (.chatText, .me):
                try await deliverDirectText(envelope, hops: hops, via: link)
            case (.chatText, .group(let group)):
                try await deliverGroupText(envelope, group: group, hops: hops, via: link)
            case (.publicLounge, .lounge(let lounge)):
                try await deliverLoungeText(envelope, lounge: lounge, hops: hops)
            case (.deliveryAck, .me):
                try await handleAck(envelope)
            case (.groupKeyGossip, .me):
                try await handleGossip(envelope, hops: hops)
            case (.mediaHeader, _), (.mediaChunk, _), (.proPassGrant, _), (.gameMove, _):
                try await forwardToSubsystems(envelope, hops: hops, target: target)
            default:
                return
            }
            stats.delivered += 1
        } catch {
            stats.undecryptable += 1
        }
    }

    // MARK: Decifratura

    private func openDirect(_ envelope: MeshEnvelope) async throws -> (InnerPayload, PeerRecord) {
        guard let peer = try await database.peers.fetch(nodeID: envelope.senderNodeID) else {
            throw RouterError.unknownPeer(envelope.senderNodeID)
        }
        let key = try identity.directChatKey(peerX25519PublicKeyRaw: peer.x25519Pub)
        let payload = try EnvelopeVerifier.open(envelope, key: key, expectedSenderEd25519PublicKeyRaw: peer.ed25519Pub)
        return (payload, peer)
    }

    /// Apre un pacchetto cifrato con chiave condivisa (gruppo/Lounge) e autentica il mittente tramite
    /// le chiavi dichiarate nel payload, che devono corrispondere al `sender_node_id` dell'envelope.
    private func openShared(_ envelope: MeshEnvelope, key: SymmetricKey) async throws -> (InnerPayload, PeerRecord) {
        let payload = try EnvelopeVerifier.open(envelope, key: key)
        guard payload.declaredSenderNodeID == envelope.senderNodeID else { throw RouterError.senderMismatch }
        if let known = try await database.peers.fetch(nodeID: envelope.senderNodeID) {
            guard known.ed25519Pub == payload.senderEd25519Pub else { throw RouterError.senderMismatch }
            return (payload, known)
        }
        let nickname = payload.senderNickname.isEmpty ? String(localized: "Passeggero") : payload.senderNickname
        let peer = PeerRecord(nodeID: envelope.senderNodeID, x25519Pub: payload.senderX25519Pub,
                              ed25519Pub: payload.senderEd25519Pub, nickname: nickname)
        return (payload, peer)
    }

    // MARK: Consegna

    private func deliverDirectText(_ envelope: MeshEnvelope, hops: Int, via link: LinkID) async throws {
        let (payload, peer) = try await openDirect(envelope)
        let conversation = try await database.conversations.ensureDirectConversation(with: peer)
        try await storeIncomingText(payload, envelope: envelope, conversation: conversation, sender: peer, hops: hops, via: link, notify: true)
        try await sendAck(for: envelope, to: peer)
    }

    private func deliverGroupText(_ envelope: MeshEnvelope, group: ConversationRecord, hops: Int, via link: LinkID) async throws {
        guard let secret = group.groupSecretKey else { throw RouterError.missingGroupKey }
        let (payload, sender) = try await openShared(envelope, key: try CryptoEngine.groupKey(fromSecret: secret))
        let peer = try await database.peers.upsert(sender)
        try await database.conversations.addGroupMember(peer, to: group.id)
        try await storeIncomingText(payload, envelope: envelope, conversation: group, sender: peer, hops: hops, via: link, notify: true)
        try await sendAck(for: envelope, to: peer)
    }

    private func deliverLoungeText(_ envelope: MeshEnvelope, lounge: ConversationRecord, hops: Int) async throws {
        let (payload, sender) = try await openShared(envelope, key: CryptoEngine.publicCabinKey)
        // Cabina Pubblica: solo testo, niente link.
        guard !Self.containsLink(payload.text), !payload.text.isEmpty else { throw RouterError.linkNotAllowedInLounge }
        let message = MessageRecord(msgID: envelope.msgID,
                                    conversationID: lounge.id,
                                    senderNodeID: sender.nodeID,
                                    senderNickname: sender.nickname,
                                    contentType: .text,
                                    text: payload.text,
                                    isOutgoing: false,
                                    deliveryStatus: .deliveredAck,
                                    hopCountReceived: hops,
                                    createdAtMs: envelope.createdAtMs)
        if try await database.messages.insert(message) {
            emit(.messageReceived(conversationID: lounge.id, msgID: message.msgID))
        }
    }

    private func storeIncomingText(_ payload: InnerPayload, envelope: MeshEnvelope, conversation: ConversationRecord,
                                   sender: PeerRecord, hops: Int, via link: LinkID, notify: Bool) async throws {
        let nickname = payload.senderNickname.isEmpty ? sender.nickname : payload.senderNickname
        let message = MessageRecord(msgID: envelope.msgID,
                                    conversationID: conversation.id,
                                    senderNodeID: sender.nodeID,
                                    senderNickname: nickname,
                                    contentType: .text,
                                    text: payload.text,
                                    replyToMsgID: payload.replyToMessageID,
                                    isOutgoing: false,
                                    deliveryStatus: .deliveredAck,
                                    hopCountReceived: hops,
                                    createdAtMs: envelope.createdAtMs)
        try? await database.peers.markSeen(nodeID: sender.nodeID, hopCount: hops, rssi: links[link]?.rssi)
        if nickname != sender.nickname {
            var updated = sender
            updated.nickname = nickname
            _ = try? await database.peers.upsert(updated)
        }
        if try await database.messages.insert(message) {
            emit(.messageReceived(conversationID: conversation.id, msgID: message.msgID))
            if notify {
                await notifier.notify(message: message, conversation: conversation)
            }
        }
    }

    private func sendAck(for envelope: MeshEnvelope, to peer: PeerRecord) async throws {
        let payload = InnerPayload.deliveryAck(for: envelope.msgID, identity: identity, nickname: nickname)
        try await sendDirectPacket(type: .deliveryAck, payload: payload, to: peer, ttl: MeshProtocol.ackTTL)
    }

    private func handleAck(_ envelope: MeshEnvelope) async throws {
        let (payload, _) = try await openDirect(envelope)
        guard let msgID = payload.replyToMessageID else { return }
        try await database.messages.advanceDeliveryStatus(to: .deliveredAck, msgID: msgID)
        _ = try? await database.relayQueue.remove(msgID: msgID)
    }

    private func forwardToSubsystems(_ envelope: MeshEnvelope, hops: Int, target: Target) async throws {
        let packet: InboundPacket
        switch target {
        case .me:
            let (payload, peer) = try await openDirect(envelope)
            let conversation = try await database.conversations.ensureDirectConversation(with: peer)
            packet = InboundPacket(envelope: envelope, payload: payload, conversation: conversation, sender: peer, hopCount: hops)
        case .group(let group):
            guard let secret = group.groupSecretKey else { throw RouterError.missingGroupKey }
            let (payload, sender) = try await openShared(envelope, key: try CryptoEngine.groupKey(fromSecret: secret))
            let peer = try await database.peers.upsert(sender)
            packet = InboundPacket(envelope: envelope, payload: payload, conversation: group, sender: peer, hopCount: hops)
        case .lounge, .other:
            return
        }
        try? await database.peers.markSeen(nodeID: packet.sender.nodeID, hopCount: hops, rssi: nil)
        for continuation in inboundSubscribers.values {
            continuation.yield(packet)
        }
    }

    // MARK: - Group Key Gossip

    private func handleGossip(_ envelope: MeshEnvelope, hops: Int) async throws {
        let (payload, sender) = try await openDirect(envelope)
        let gossip = try MeshWire.parseGroupKeyGossip(payload.extraData)
        guard let groupID = UUID(rawBytes: gossip.groupUuid) else { throw RouterError.invalidGossip("group_uuid") }
        guard gossip.groupSecretKey.count == MeshProtocol.symmetricKeyLength else { throw RouterError.invalidGossip("group_secret_key") }

        let title = gossip.groupTitle.isEmpty ? String(localized: "Gruppo di viaggio") : gossip.groupTitle
        let group = try await database.conversations.createGroup(id: groupID, title: title, secretKey: gossip.groupSecretKey)
        guard group.groupSecretKey == gossip.groupSecretKey else { throw RouterError.invalidGossip("chiave diversa da quella nota") }
        knownGroupRecipientIDs.insert(group.recipientID)

        let known = Set((try await database.conversations.groupMembers(of: group.id)).map(\.nodeID))
        var learnedSomething = false

        if !known.contains(sender.nodeID) {
            try await database.conversations.addGroupMember(sender, to: group.id)
            learnedSomething = true
        }
        for member in gossip.members where member.isConsistent && member.nodeID != identity.nodeID {
            let peer = try await database.peers.upsert(member.asPeerRecord)
            if !known.contains(peer.nodeID) {
                try await database.conversations.addGroupMember(peer, to: group.id)
                learnedSomething = true
            }
        }
        try? await database.peers.markSeen(nodeID: sender.nodeID, hopCount: hops, rssi: nil)

        // Se il mittente conosce meno membri di noi (es. ha appena scansionato il QR), gli rispondiamo
        // con la lista completa: cosi' A e C si riconoscono avendo scansionato solo B.
        let theirView = Set(gossip.members.map(\.nodeID)).union([sender.nodeID])
        let ourView = Set((try await database.conversations.groupMembers(of: group.id)).map(\.nodeID)).union([identity.nodeID])
        if !ourView.isSubset(of: theirView) {
            try await sendGossip(for: group, to: sender)
        }

        if learnedSomething {
            emit(.groupMembersChanged(conversationID: group.id))
            // Convergenza: ri-propaghiamo agli altri solo quando abbiamo imparato membri nuovi.
            try await propagateGossip(for: group, excluding: sender.nodeID)
        }
    }

    private func gossipPayload(for group: ConversationRecord) async throws -> InnerPayload {
        guard let groupID = group.groupID, let key = group.groupSecretKey else { throw RouterError.missingGroupKey }
        var gossip = GroupKeyGossip()
        gossip.groupUuid = groupID.rawBytes
        gossip.groupTitle = group.title
        gossip.groupSecretKey = key
        gossip.issuedAtMs = Date.nowUnixMilliseconds
        var members = [GroupMember(identity: identity, nickname: nickname)]
        for member in try await database.conversations.groupMembers(of: group.id) {
            var proto = GroupMember()
            proto.nodeID = member.nodeID
            proto.x25519Pub = member.x25519Pub
            proto.ed25519Pub = member.ed25519Pub
            proto.nickname = member.nickname
            members.append(proto)
        }
        gossip.members = members
        return InnerPayload.extra(try MeshWire.serialize(gossip), identity: identity, nickname: nickname)
    }

    private func sendGossip(for group: ConversationRecord, to peer: PeerRecord) async throws {
        let payload = try await gossipPayload(for: group)
        try await sendDirectPacket(type: .groupKeyGossip, payload: payload, to: peer)
    }

    private func propagateGossip(for group: ConversationRecord, excluding excludedNodeID: Data?) async throws {
        let payload = try await gossipPayload(for: group)
        for member in try await database.conversations.groupMembers(of: group.id)
        where member.nodeID != identity.nodeID && member.nodeID != excludedNodeID {
            guard let peer = try await database.peers.fetch(nodeID: member.nodeID) else { continue }
            try await sendDirectPacket(type: .groupKeyGossip, payload: payload, to: peer)
        }
    }

    // MARK: - Manutenzione

    func runMaintenance(now: Date = Date()) async {
        seen.purgeExpired(nowMs: now.unixMilliseconds)
        try? await database.runMaintenance(now: now)
        if !links.isEmpty {
            await flushRelayQueue()
        }
        await emitRelayQueueCount()
    }

    // MARK: - Utility

    static func containsLink(_ text: String) -> Bool {
        let lowered = text.lowercased()
        return lowered.contains("http://") || lowered.contains("https://") || lowered.contains("www.")
    }

    private func emit(_ event: RouterEvent) {
        for continuation in subscribers.values {
            continuation.yield(event)
        }
    }

    private func emitRelayQueueCount() async {
        let count = (try? await database.relayQueue.stats().count) ?? 0
        emit(.relayQueueChanged(count: count))
    }

    private func removeSubscriber(_ id: UUID) {
        subscribers.removeValue(forKey: id)
    }

    private func removeInboundSubscriber(_ id: UUID) {
        inboundSubscribers.removeValue(forKey: id)
    }
}
