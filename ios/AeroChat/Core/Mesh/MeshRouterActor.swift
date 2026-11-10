import Foundation
import CryptoKit

/// Motore mesh: managed flooding anti-loop, routing verso le nostre conversazioni, relay con filtro
/// media, Store-and-Forward, `DELIVERY_ACK` e Group Key Gossip.
actor MeshRouterActor {

    struct Configuration: Sendable {
        /// 16 byte per id: 65k id ~ 1 MB, copre la finestra anti-replay anche con molti chunk media.
        var seenCacheCapacity = 65_536
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
        /// Ogni quanto ritrasmettere ai vicini i nostri messaggi ancora senza `DELIVERY_ACK`
        /// (il link puo' sembrare aperto mentre l'app del destinatario e' sospesa).
        var ackRetryInterval: TimeInterval = 30
        /// Intervallo minimo tra due ACK per lo stesso `msg_id` (ritrasmissioni duplicate).
        var reackThrottle: TimeInterval = 15
        /// Intervallo minimo tra due scritture di `last_seen` per lo stesso peer (a meno che cambi l'hop):
        /// evita una transazione e un refresh della UI per ogni chunk ricevuto.
        var lastSeenWriteInterval: TimeInterval = 15
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
    private var ackRetryTask: Task<Void, Never>?
    /// Ultimo `DELIVERY_ACK` inviato per `msg_id`, per non rispondere a ogni duplicato del flooding.
    private var lastAckSentAt: [Data: Date] = [:]
    private var subscribers: [UUID: AsyncStream<RouterEvent>.Continuation] = [:]
    private var inboundSubscribers: [UUID: AsyncStream<InboundPacket>.Continuation] = [:]
    private var callAudioSubscribers: [UUID: AsyncStream<CallAudioPacket>.Continuation] = [:]
    /// Peer e chiave 1-a-1 per l'audio delle chiamate: ~17 pacchetti/s non devono leggere il DB.
    private var callPeerCache: [Data: (peer: PeerRecord, key: SymmetricKey)] = [:]
    /// Relay in attesa del backoff, per `msg_id`.
    private var pendingRelays: [Data: Task<Void, Never>] = [:]
    /// Ultima scrittura di `last_seen` per peer (throttle di `markSeen`).
    private var lastSeenWrites: [Data: (date: Date, hops: Int?)] = [:]
    /// Coalescenza dello svuotamento della relay queue: niente flush sovrapposti (rientranza dell'attore).
    private var isFlushingRelayQueue = false
    private var pendingFlushTargets: [Data?] = []
    /// Profilo da annunciare e nodi a cui e' gia' stato inviato da quando il link e' aperto.
    private var profile: LocalProfile?
    private var profileSentTo: Set<Data> = []
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
        if let persisted = try? await database.seenPackets.activeMessageIDs(now: now, limit: configuration.seenCacheCapacity) {
            seen.preload(persisted, expiresAtMs: now.addingTimeInterval(MeshProtocol.seenPacketLifetime).unixMilliseconds)
        }
        blocked = (try? await database.peers.blockedNodeIDs()) ?? []
        if let conversations = try? await database.conversations.fetchPermanent() {
            knownGroupRecipientIDs = Set(conversations.filter { $0.type == .group }.map(\.recipientID))
        }

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

        let retryInterval = configuration.ackRetryInterval
        ackRetryTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(retryInterval))
                guard !Task.isCancelled, let self else { break }
                await self.retryUnacknowledged()
            }
        }
    }

    func stop() async {
        guard isRunning else { return }
        isRunning = false
        eventTask?.cancel()
        maintenanceTask?.cancel()
        ackRetryTask?.cancel()
        eventTask = nil
        maintenanceTask = nil
        ackRetryTask = nil
        pendingRelays.values.forEach { $0.cancel() }
        pendingRelays.removeAll()
        await transport.stop()
        links.removeAll()
    }

    func setNickname(_ nickname: String) {
        self.nickname = nickname
    }

    /// Imposta il profilo pubblico; con `announce` lo invia subito a tutti i contatti non bloccati.
    func setProfile(_ profile: LocalProfile, announce: Bool) async {
        self.profile = profile
        nickname = profile.nickname
        profileSentTo.removeAll()
        guard announce, isRunning, let peers = try? await database.peers.unblockedPeers() else { return }
        for peer in peers {
            await sendProfile(to: peer)
        }
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

    /// Audio delle chiamate in arrivo (solo vicini diretti, gia' verificato).
    func callAudioPackets() -> AsyncStream<CallAudioPacket> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(64)) { continuation in
            callAudioSubscribers[id] = continuation
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.removeCallAudioSubscriber(id) }
            }
        }
    }

    /// `true` se esiste un link radio diretto (1 hop) verso il nodo.
    func isDirectNeighbor(_ nodeID: Data) -> Bool {
        links.values.contains { $0.nodeID == nodeID }
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
        guard let peer = try? await database.peers.fetch(nodeID: nodeID) else { return .outOfRange }
        return bridgeReachability(of: peer, now: now)
    }

    /// Stato "via ponte" dalla telemetria salvata del peer (nessun link diretto).
    private func bridgeReachability(of peer: PeerRecord, now: Date) -> Reachability {
        guard let seenAt = peer.lastSeenAtMs,
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
            // Vicini calcolati una volta e peer letti in un'unica query: la lista chat interroga ogni
            // gruppo ogni pochi secondi e prima faceva una lettura DB per ciascun membro (N+1).
            let current = neighbors()
            if let neighbor = current.first(where: { neighbor in members.contains { $0.nodeID == neighbor.nodeID } }) {
                return .direct(rssi: neighbor.rssi)
            }
            let peers = (try? await database.peers.fetch(nodeIDs: members.map(\.nodeID))) ?? []
            let now = Date()
            var best: Reachability = .outOfRange
            for peer in peers {
                let candidate = bridgeReachability(of: peer, now: now)
                switch (candidate, best) {
                case (.viaBridge, .outOfRange): best = candidate
                case (.viaBridge(let a), .viaBridge(let b)) where a < b: best = candidate
                default: break
                }
            }
            return best
        case .cabinLounge:
            let current = neighbors()
            return current.isEmpty ? .outOfRange : .direct(rssi: current.compactMap(\.rssi).max())
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
        await sendProfile(to: peer)
        if payload.isGroupInvite {
            return try await joinGroup(from: payload, inviter: peer)
        }
        return direct
    }

    /// Crea un gruppo di viaggio locale (noi siamo il primo membro).
    func createGroup(title: String) async throws -> ConversationRecord {
        let group = try await database.conversations.createGroup(title: title)
        knownGroupRecipientIDs.insert(group.recipientID)
        return group
    }

    /// Cabina del volo: gruppo derivato dalla carta d'imbarco, nessun invito necessario. I membri si
    /// scoprono dai messaggi (ogni testo di gruppo registra il mittente tra i membri).
    func joinFlightLounge(_ pass: BoardingPass) async throws -> ConversationRecord {
        let lounge = try await database.conversations.joinFlightLounge(pass)
        knownGroupRecipientIDs.insert(lounge.recipientID)
        emit(.groupMembersChanged(conversationID: lounge.id))
        return lounge
    }

    /// Entra nel gruppo descritto dal QR e annuncia la propria adesione all'invitante (Group Key Gossip).
    func joinGroup(from payload: PairingPayload, inviter: PeerRecord) async throws -> ConversationRecord {
        guard let groupID = payload.groupID, let key = payload.groupSecretKey else { throw RouterError.invalidGossip("QR senza gruppo") }
        var group = try await database.conversations.createGroup(id: groupID, title: payload.groupTitle ?? Self.placeholderGroupTitle,
                                                                 secretKey: key, members: [inviter])
        if let title = payload.groupTitle {
            group = try await adoptGroupTitle(title, for: group)
        }
        try await database.conversations.addGroupMember(inviter, to: group.id)
        knownGroupRecipientIDs.insert(group.recipientID)
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
        return PairingPayload(identity: identity, nickname: nickname, groupID: groupID, groupSecretKey: key, groupTitle: group.title)
    }

    /// Titolo provvisorio di un gruppo di cui non conosciamo ancora il nome.
    static var placeholderGroupTitle: String { String(localized: "Gruppo di viaggio") }

    /// Sostituisce il titolo provvisorio con quello vero (dal QR o dal gossip); un titolo gia' noto non cambia.
    private func adoptGroupTitle(_ title: String, for group: ConversationRecord) async throws -> ConversationRecord {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != group.title, group.title == Self.placeholderGroupTitle else { return group }
        try await database.conversations.updateTitle(trimmed, conversationID: group.id)
        var updated = group
        updated.title = trimmed
        return updated
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
        // Chat e gruppi: il messaggio resta in coda finche' non arriva il `DELIVERY_ACK`, anche se un
        // vicino e' stato raggiunto (l'app del destinatario puo' essere sospesa con il link "aperto").
        let reached = await dispatch(envelope, storeIfUnreachable: true, retainUntilAck: conversation.type != .cabinLounge)
        message.deliveryStatus = reached ? .sentMesh : .queued
        if reached {
            // Solo in avanti: l'ACK puo' essere gia' arrivato mentre `dispatch` era in corso.
            try await database.messages.advanceDeliveryStatus(to: .sentMesh, msgID: message.msgID)
        }
        return message
    }

    /// Invio generico (media, giochi, Pro Pass). Ritorna il `msg_id` e se almeno un vicino e' stato raggiunto.
    @discardableResult
    func sendPacket(type: PacketType,
                    payload: InnerPayload,
                    in conversation: ConversationRecord,
                    msgID: Data = CryptoEngine.randomMessageID(),
                    ttl: UInt32 = MeshProtocol.defaultTTL,
                    storeIfUnreachable: Bool = true,
                    retainUntilAck: Bool = false) async throws -> (msgID: Data, reached: Bool) {
        guard isRunning else { throw RouterError.notRunning }
        let envelope = try await makeEnvelope(type: type, payload: payload, conversation: conversation, msgID: msgID, ttl: ttl)
        // Mosse di gioco: come i testi, restano in coda finche' il destinatario non conferma (una mossa
        // persa bloccava la partita per sempre).
        let reached = await dispatch(envelope, storeIfUnreachable: storeIfUnreachable, retainUntilAck: retainUntilAck)
        return (msgID, reached)
    }

    /// Chiamate: solo sul link diretto, ttl 1, mai flooding ne' coda. Ritorna `false` se il vicino non c'e'.
    @discardableResult
    func sendCallPacket(type: PacketType, payload: InnerPayload, to peer: PeerRecord) async -> Bool {
        guard isRunning, let key = try? callKey(for: peer),
              let envelope = try? EnvelopeBuilder(identity: identity).build(packetType: type, recipientID: peer.nodeID,
                                                                            payload: payload, key: key, ttl: 1),
              let bytes = try? MeshWire.serialize(envelope) else { return false }
        if type == .callSignal { _ = seen.insertIfNew(envelope.msgID) }
        return (try? await transport.send(bytes, toNode: peer.nodeID)) ?? false
    }

    private func callKey(for peer: PeerRecord) throws -> SymmetricKey {
        if let cached = callPeerCache[peer.nodeID], cached.peer.x25519Pub == peer.x25519Pub { return cached.key }
        let key = try identity.directChatKey(peerX25519PublicKeyRaw: peer.x25519Pub)
        if callPeerCache.count > 32 { callPeerCache.removeAll() }
        callPeerCache[peer.nodeID] = (peer, key)
        return key
    }

    /// Fast path dell'audio: niente cache anti-loop ne' DB (la sequenza della chiamata scarta i replay),
    /// solo pacchetti diretti (hop 0) indirizzati a noi.
    private func receiveCallAudio(_ envelope: MeshEnvelope) async {
        guard envelope.recipientID == identity.nodeID, envelope.hopCount == 0,
              !blocked.contains(envelope.senderNodeID), !callAudioSubscribers.isEmpty else { return }
        let peer: PeerRecord
        let key: SymmetricKey
        if let cached = callPeerCache[envelope.senderNodeID] {
            (peer, key) = cached
        } else {
            guard let fetched = try? await database.peers.fetch(nodeID: envelope.senderNodeID),
                  let derived = try? callKey(for: fetched) else { return }
            (peer, key) = (fetched, derived)
        }
        guard let payload = try? EnvelopeVerifier.open(envelope, key: key, expectedSenderEd25519PublicKeyRaw: peer.ed25519Pub),
              let audio = try? MeshWire.parseCallAudio(payload.extraData) else {
            stats.undecryptable += 1
            return
        }
        let packet = CallAudioPacket(senderNodeID: peer.nodeID, audio: audio)
        for continuation in callAudioSubscribers.values {
            continuation.yield(packet)
        }
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
    /// Con `retainUntilAck` il pacchetto viene accodato comunque e rimosso solo dal `DELIVERY_ACK`.
    private func dispatch(_ envelope: MeshEnvelope, storeIfUnreachable: Bool, retainUntilAck: Bool = false,
                          excluding: Set<LinkID> = []) async -> Bool {
        _ = seen.insertIfNew(envelope.msgID)
        guard let bytes = try? MeshWire.serialize(envelope) else { return false }

        let eligible = envelope.packetType.isStoreAndForwardEligible
        if retainUntilAck, eligible {
            // Accodato prima dell'invio: un ACK rapidissimo deve trovare il record da rimuovere.
            let record = RelayQueueRecord(msgID: envelope.msgID, recipientID: envelope.recipientID, rawEnvelopeBytes: bytes)
            if (try? await database.relayQueue.enqueue(record)) != nil {
                await emitRelayQueueCount()
            }
        }

        var reached = 0
        let isMulticast = envelope.recipientID == MeshProtocol.loungeRecipientID || isGroupRecipient(envelope.recipientID)
        if !isMulticast, (try? await transport.send(bytes, toNode: envelope.recipientID)) == true {
            reached = 1
        } else {
            reached = await transport.broadcast(bytes, excluding: excluding)
        }

        if reached == 0, retainUntilAck, eligible {
            stats.queued += 1
        } else if reached == 0, storeIfUnreachable, eligible {
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
            // RSSI dalla scansione o dal polling periodico (radar): la UI si aggiorna solo se cambia davvero.
            var changed = false
            for (id, var link) in links where id.deviceID == deviceID {
                if abs((link.rssi ?? Int.min) - rssi) >= 2 { changed = true }
                link.rssi = rssi
                links[id] = link
            }
            if changed { emit(.neighborsChanged(neighbors())) }
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
            let closed = links.removeValue(forKey: id)
            // Alla prossima riconnessione il profilo viene ripetuto (il precedente puo' essere andato perso).
            if let nodeID = closed?.nodeID, !links.values.contains(where: { $0.nodeID == nodeID }) {
                profileSentTo.remove(nodeID)
            }
            emit(.neighborsChanged(neighbors()))
        case .frameReceived(let data, let from, _):
            await receive(data, from: from)
        }
    }

    private func peerCameOnline(_ nodeID: Data, via link: PeerLink) async {
        await markSeenThrottled(nodeID: nodeID, hopCount: 1, rssi: link.rssi)
        if link.id.role == .central {
            try? await database.peers.setBLEIdentifier(link.id.deviceID, nodeID: nodeID)
        }
        // Secondo link (dual-role) verso un vicino gia' online: coda e profilo sono gia' partiti.
        guard !links.values.contains(where: { $0.nodeID == nodeID && $0.id != link.id }) else { return }
        await flushRelayQueue(toward: nodeID)
        if let peer = try? await database.peers.fetch(nodeID: nodeID) {
            await sendProfile(to: peer)
        }
    }

    /// Svuota la coda Store-and-Forward verso il nuovo vicino (diretto se destinatario, altrimenti flooding).
    /// Le richieste arrivate durante un flush (raffica di link aperti, manutenzione, foreground) vengono
    /// unite nel giro successivo invece di ritrasmettere tutta la coda piu' volte in parallelo.
    func flushRelayQueue(toward nodeID: Data? = nil) async {
        pendingFlushTargets.append(nodeID)
        guard !isFlushingRelayQueue else { return }
        isFlushingRelayQueue = true
        defer { isFlushingRelayQueue = false }
        while !pendingFlushTargets.isEmpty {
            let targets = Set(pendingFlushTargets.compactMap { $0 })
            pendingFlushTargets.removeAll()
            await performFlush(toward: targets)
        }
    }

    private func performFlush(toward targets: Set<Data>) async {
        guard let pending = try? await database.relayQueue.pending(), !pending.isEmpty else { return }
        var sentIDs: [Data] = []
        for record in pending {
            var sent = false
            if targets.contains(record.recipientID) {
                sent = (try? await transport.send(record.rawEnvelopeBytes, toNode: record.recipientID)) ?? false
            }
            if !sent {
                sent = await transport.broadcast(record.rawEnvelopeBytes, excluding: []) > 0
            }
            if sent { sentIDs.append(record.msgID) }
        }
        guard !sentIDs.isEmpty else { return }
        try? await database.messages.markSentMesh(msgIDs: sentIDs)
        // I nostri messaggi restano in coda finche' il destinatario non conferma con l'ACK.
        let awaiting = (try? await database.messages.awaitingAckIDs(among: sentIDs)) ?? Set(sentIDs)
        let flushed = sentIDs.filter { !awaiting.contains($0) }
        if !flushed.isEmpty {
            try? await database.relayQueue.remove(msgIDs: flushed)
            await emitRelayQueueCount()
        }
    }

    /// Ritrasmissione periodica dei nostri messaggi senza ACK: solo verso destinatari che sono vicini
    /// diretti (o gruppi), cosi' la coda non viene ri-inondata a tutta la cabina ogni 30 secondi.
    /// I destinatari lontani vengono ritentati all'apertura di ogni nuovo link (`flushRelayQueue`).
    func retryUnacknowledged() async {
        guard !links.isEmpty, let pending = try? await database.relayQueue.pending(), !pending.isEmpty else { return }
        let neighborIDs = Set(neighbors().map(\.nodeID))
        let candidates = pending.filter { neighborIDs.contains($0.recipientID) || isGroupRecipient($0.recipientID) }
        guard !candidates.isEmpty,
              let awaiting = try? await database.messages.awaitingAckIDs(among: candidates.map(\.msgID)) else { return }
        for record in candidates where awaiting.contains(record.msgID) {
            let isGroup = isGroupRecipient(record.recipientID)
            if isGroup {
                _ = await transport.broadcast(record.rawEnvelopeBytes, excluding: [])
            } else {
                _ = try? await transport.send(record.rawEnvelopeBytes, toNode: record.recipientID)
            }
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
        let (ageMs, overflow) = Date.nowUnixMilliseconds.subtractingReportingOverflow(envelope.createdAtMs)
        if overflow || ageMs > Int64(configuration.replayWindow * 1_000) || ageMs < -Int64(configuration.futureSkewTolerance * 1_000) {
            stats.replayRejected += 1
            return
        }
        // Audio delle chiamate: fast path senza deduplicazione persistente (17 pacchetti/s).
        if envelope.packetType == .callAudio {
            await receiveCallAudio(envelope)
            return
        }
        // 2. Managed flooding anti-loop: scarto in memoria, prima di qualsiasi I/O.
        guard seen.insertIfNew(envelope.msgID) else {
            stats.duplicates += 1
            // Un vicino ha gia' ritrasmesso questo pacchetto: il nostro relay in attesa e' superfluo.
            cancelPendingRelay(for: envelope.msgID)
            // Il mittente ritrasmette finche' non riceve l'ACK: se il nostro e' andato perso, lo rimandiamo.
            await reacknowledgeDuplicate(envelope)
            return
        }
        // La cache in memoria puo' aver dimenticato l'id (riavvio, eviction): il DB e' la verita'.
        if (try? await database.seenPackets.markSeenIfNew(msgID: envelope.msgID)) == false {
            stats.duplicates += 1
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
        // Un utente bloccato non viene nemmeno ritrasmesso: niente ponte per chi molesta.
        guard !blocked.contains(envelope.senderNodeID) else { return }
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
            case (.readReceipt, .me):
                try await handleReadReceipt(envelope)
            case (.profileUpdate, .me):
                try await handleProfileUpdate(envelope)
            case (.callSignal, .me):
                // Solo dal vicino diretto: con un ponte di mezzo la chiamata non reggerebbe.
                guard envelope.hopCount == 0 else { return }
                try await forwardToSubsystems(envelope, hops: hops, target: target)
            case (.mediaHeader, _), (.mediaChunk, _), (.proPassGrant, _), (.gameMove, _):
                try await forwardToSubsystems(envelope, hops: hops, target: target)
            // Quiz ed emergenze viaggiano sulla Lounge (chiave pubblica) e vengono gestiti dai loro servizi.
            case (.quiz, .lounge), (.emergency, .lounge):
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
        // Bacheca tra sconosciuti: le parolacce vengono mascherate (impostazione dell'utente).
        let text = AppSettings.isLanguageFilterEnabled ? ContentFilter.masked(payload.text) : payload.text
        let message = MessageRecord(msgID: envelope.msgID,
                                    conversationID: lounge.id,
                                    senderNodeID: sender.nodeID,
                                    senderNickname: sender.nickname,
                                    contentType: .text,
                                    text: text,
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
        await markSeenThrottled(nodeID: sender.nodeID, hopCount: hops, rssi: links[link]?.rssi)
        if nickname != sender.nickname {
            var updated = sender
            updated.nickname = nickname
            _ = try? await database.peers.upsert(updated, authoritativeNickname: true)
        }
        if try await database.messages.insert(message) {
            emit(.messageReceived(conversationID: conversation.id, msgID: message.msgID))
            if notify {
                await notifier.notify(message: message, conversation: conversation)
            }
        }
    }

    private func sendAck(for envelope: MeshEnvelope, to peer: PeerRecord) async throws {
        lastAckSentAt[envelope.msgID] = Date()
        let payload = InnerPayload.deliveryAck(for: envelope.msgID, identity: identity, nickname: nickname)
        try await sendDirectPacket(type: .deliveryAck, payload: payload, to: peer, ttl: MeshProtocol.ackTTL)
    }

    /// Rimanda l'ACK per un testo gia' consegnato e salvato (mai per pacchetti non verificati),
    /// al massimo una volta ogni `reackThrottle` per non rispondere a ogni copia del flooding.
    private func reacknowledgeDuplicate(_ envelope: MeshEnvelope) async {
        guard envelope.packetType == .chatText || envelope.packetType == .gameMove,
              envelope.recipientID == identity.nodeID || isGroupRecipient(envelope.recipientID) else { return }
        let now = Date()
        if let last = lastAckSentAt[envelope.msgID], now.timeIntervalSince(last) < configuration.reackThrottle { return }
        // Anche un esito negativo viene ricordato: le copie successive non rileggono il DB.
        lastAckSentAt[envelope.msgID] = now
        guard let peer = try? await database.peers.fetch(nodeID: envelope.senderNodeID) else { return }
        if envelope.packetType == .chatText {
            // Basta il messaggio salvato: esiste solo se era per noi (diretto o gruppo) e gia' verificato.
            guard let stored = try? await database.messages.fetch(msgID: envelope.msgID),
                  !stored.isOutgoing, stored.senderNodeID == envelope.senderNodeID else { return }
        }
        // Mosse di gioco: non hanno un messaggio proprio (lo stato vive nella bolla dell'invito); il
        // duplicato di un msg_id gia' visto da un peer noto viene riconfermato cosi' smette di ritrasmettere.
        try? await sendAck(for: envelope, to: peer)
    }

    private func handleAck(_ envelope: MeshEnvelope) async throws {
        let (payload, _) = try await openDirect(envelope)
        guard let msgID = payload.replyToMessageID else { return }
        try await database.messages.advanceDeliveryStatus(to: .deliveredAck, msgID: msgID)
        _ = try? await database.relayQueue.remove(msgID: msgID)
    }

    // MARK: - Conferme di lettura

    /// L'utente ha aperto la chat: segna letti i messaggi ricevuti e invia un `READ_RECEIPT` a ogni mittente.
    func markConversationRead(_ conversation: ConversationRecord) async {
        guard let unread = try? await database.messages.markIncomingRead(conversationID: conversation.id),
              !unread.isEmpty, conversation.type != .cabinLounge, isRunning else { return }
        let bySender = Dictionary(grouping: unread, by: \.senderNodeID)
        for (senderID, messages) in bySender where senderID != identity.nodeID {
            guard let peer = try? await database.peers.fetch(nodeID: senderID) else { continue }
            let ids = messages.map(\.msgID)
            for start in stride(from: 0, to: ids.count, by: Self.readReceiptBatch) {
                let batch = ids[start..<min(start + Self.readReceiptBatch, ids.count)]
                let payload = InnerPayload.extra(Data(batch.joined()), identity: identity, nickname: nickname)
                _ = try? await sendDirectPacket(type: .readReceipt, payload: payload, to: peer, ttl: MeshProtocol.ackTTL)
            }
        }
    }

    static let readReceiptBatch = 64

    private func handleReadReceipt(_ envelope: MeshEnvelope) async throws {
        let (payload, peer) = try await openDirect(envelope)
        let data = payload.extraData
        let length = MeshProtocol.msgIDLength
        guard !data.isEmpty, data.count % length == 0, data.count <= length * Self.readReceiptBatch else { return }
        let ids = stride(from: 0, to: data.count, by: length).map { offset in
            Data(data[(data.startIndex + offset)..<(data.startIndex + offset + length)])
        }
        // Nei gruppi il lettore viene registrato e la spunta blu arriva solo quando hanno letto tutti
        // i membri; nel frattempo il messaggio avanza almeno a consegnato.
        let updated = try await database.messages.markReadByRecipient(msgIDs: ids, readerNodeID: peer.nodeID)
        // Letto implica consegnato: se l'ACK si e' perso, il messaggio non va piu' ritrasmesso.
        if !updated.isEmpty, (try? await database.relayQueue.remove(msgIDs: updated)) != nil {
            await emitRelayQueueCount()
        }
    }

    // MARK: - Profilo

    private func sendProfile(to peer: PeerRecord) async {
        guard isRunning, let profile, peer.nodeID != identity.nodeID, !blocked.contains(peer.nodeID),
              profileSentTo.insert(peer.nodeID).inserted else { return }
        var update = ProfileUpdate()
        update.nickname = profile.nickname
        update.avatarColorHex = profile.avatarColorHex
        update.avatarWebp = profile.avatarImage ?? Data()
        update.updatedAtMs = profile.updatedAtMs
        guard let data = try? MeshWire.serialize(update) else { return }
        let payload = InnerPayload.extra(data, identity: identity, nickname: nickname)
        _ = try? await sendDirectPacket(type: .profileUpdate, payload: payload, to: peer, storeIfUnreachable: false)
    }

    private func handleProfileUpdate(_ envelope: MeshEnvelope) async throws {
        let (payload, peer) = try await openDirect(envelope)
        let update = try MeshWire.parseProfileUpdate(payload.extraData)
        guard update.updatedAtMs > 0 else { return }
        let nickname = String(update.nickname.trimmingCharacters(in: .whitespacesAndNewlines).prefix(MeshProtocol.maxNicknameLength))
        let color = Self.isValidColorHex(update.avatarColorHex) ? update.avatarColorHex.uppercased() : nil
        let avatar = update.avatarWebp
        let validAvatar = !avatar.isEmpty && avatar.count <= MeshProtocol.maxAvatarBytes && WebPEncoder.isWebP(avatar) ? avatar : nil
        try await database.peers.applyProfile(nodeID: peer.nodeID, nickname: nickname, avatarColorHex: color,
                                              avatarImage: validAvatar, updatedAtMs: update.updatedAtMs)
    }

    static func isValidColorHex(_ hex: String) -> Bool {
        hex.count == 6 && hex.allSatisfy(\.isHexDigit)
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
        case .lounge(let lounge):
            // Lounge anonima: il mittente non viene registrato tra i peer (resta un record temporaneo).
            let (payload, sender) = try await openShared(envelope, key: CryptoEngine.publicCabinKey)
            packet = InboundPacket(envelope: envelope, payload: payload, conversation: lounge, sender: sender, hopCount: hops)
        case .other:
            return
        }
        await markSeenThrottled(nodeID: packet.sender.nodeID, hopCount: hops, rssi: nil)
        for continuation in inboundSubscribers.values {
            continuation.yield(packet)
        }
        // Le mosse di gioco vengono confermate come i testi: il mittente le ritrasmette finche' non arriva l'ACK.
        if envelope.packetType == .gameMove {
            switch target {
            case .me, .group: try? await sendAck(for: envelope, to: packet.sender)
            case .lounge, .other: break
            }
        }
    }

    // MARK: - Group Key Gossip

    private func handleGossip(_ envelope: MeshEnvelope, hops: Int) async throws {
        let (payload, sender) = try await openDirect(envelope)
        let gossip = try MeshWire.parseGroupKeyGossip(payload.extraData)
        guard let groupID = UUID(rawBytes: gossip.groupUuid) else { throw RouterError.invalidGossip("group_uuid") }
        guard gossip.groupSecretKey.count == MeshProtocol.symmetricKeyLength else { throw RouterError.invalidGossip("group_secret_key") }
        guard gossip.members.count <= MeshProtocol.maxGossipMembers else { throw RouterError.invalidGossip("members") }

        let title = gossip.groupTitle.isEmpty ? Self.placeholderGroupTitle : gossip.groupTitle
        var group = try await database.conversations.createGroup(id: groupID, title: title, secretKey: gossip.groupSecretKey)
        guard group.groupSecretKey == gossip.groupSecretKey else { throw RouterError.invalidGossip("chiave diversa da quella nota") }
        group = try await adoptGroupTitle(gossip.groupTitle, for: group)
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
                await sendProfile(to: peer)
            }
        }
        await markSeenThrottled(nodeID: sender.nodeID, hopCount: hops, rssi: nil)

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
        let throttle = configuration.reackThrottle
        lastAckSentAt = lastAckSentAt.filter { now.timeIntervalSince($0.value) < throttle }
        let seenInterval = configuration.lastSeenWriteInterval
        lastSeenWrites = lastSeenWrites.filter { now.timeIntervalSince($0.value.date) < seenInterval }
        try? await database.runMaintenance(now: now)
        if !links.isEmpty {
            await flushRelayQueue()
        }
        await emitRelayQueueCount()
    }

    // MARK: - Utility

    /// `markSeen` al massimo ogni `lastSeenWriteInterval` per peer, salvo cambio di hop o RSSI nuovo
    /// da un link diretto (servono all'indicatore "Stato Radio").
    private func markSeenThrottled(nodeID: Data, hopCount: Int?, rssi: Int?) async {
        let now = Date()
        if let last = lastSeenWrites[nodeID], last.hops == hopCount,
           now.timeIntervalSince(last.date) < configuration.lastSeenWriteInterval {
            return
        }
        lastSeenWrites[nodeID] = (now, hopCount)
        try? await database.peers.markSeen(nodeID: nodeID, hopCount: hopCount, rssi: rssi)
    }

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

    private func removeCallAudioSubscriber(_ id: UUID) {
        callAudioSubscribers.removeValue(forKey: id)
    }
}
