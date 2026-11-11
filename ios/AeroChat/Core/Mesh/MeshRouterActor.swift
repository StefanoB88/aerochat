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
    /// Ultima ritrasmissione dei messaggi senza ACK. Con l'app sospesa in background il timer da 30 s
    /// non scatta: la ritrasmissione arretrata parte allora al primo frame ricevuto (iOS ci risveglia).
    private var lastAckRetryAt = Date()
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
    /// Revisioni (`MESSAGE_REVISION`) arrivate prima del messaggio originale: applicate quando arriva.
    private var pendingRevisions: [PendingRevision] = []
    /// `msg_id` delle nostre revisioni 1-a-1/gruppo e dei `PAIR_HELLO` ancora senza `DELIVERY_ACK` (non hanno
    /// un messaggio proprio in `messages`, quindi `awaitingAckIDs` non li vede).
    private var awaitingPacketAcks: Set<Data> = []
    /// `PAIR_HELLO` in attesa di conferma: `msg_id` -> `node_id` di chi ha mostrato il QR. All'ACK gli
    /// rimandiamo il profilo (quello inviato subito dopo la scansione non poteva ancora decifrarlo).
    private var pendingHellos: [Data: Data] = [:]
    /// File media da rimuovere quando un messaggio viene cancellato per tutti (impostato dall'app).
    private var mediaStore: MediaStore?
    /// PRESENCE: cosa annunciamo (spento finche' l'app non applica l'impostazione) e chi e' vicino.
    private var presenceSettings = PresenceSettings()
    private var presenceCache = PresenceCache()
    /// CONTACT_REQUEST inviati in attesa di ACCEPT / DECLINE, per `request_id`.
    private var outgoingContactRequests: [Data: OutgoingContactRequest] = [:]
    /// CONTACT_REQUEST ricevuti in attesa di Accetta / Rifiuta, per `request_id`.
    private var incomingContactRequests: [Data: IncomingContactRequest] = [:]
    /// Ultima REQUEST accettata in ingresso per mittente (limite di una ogni 10 minuti).
    private var lastContactRequestAt: [Data: Date] = [:]
    /// "Sfida" accettata da noi: il primo invito di quel gioco da quel peer parte gia' accettato.
    private var acceptedChallenges: [Data: (kind: Int32, expiresAt: Date)] = [:]
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
        lastAckRetryAt = Date()
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

    /// Radar di prossimita' visibile (e app in primo piano): accende/spegne il polling RSSI della radio.
    func setProximityRanging(_ enabled: Bool) async {
        await transport.setProximityRanging(enabled)
    }

    func setNickname(_ nickname: String) {
        self.nickname = nickname
    }

    /// Archivio dei media: serve a rimuovere il file di una foto / nota vocale cancellata per tutti.
    func setMediaStore(_ store: MediaStore) {
        mediaStore = store
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
        let direct = try await database.conversations.ensureDirectConversation(with: peer, showAtTop: true)
        await refreshTrustedPeers()
        // QR con token: prima il PAIR_HELLO (cosi' chi ha mostrato il QR ci conosce e vede la chat),
        // poi profilo e, per gli inviti, il Group Key Gossip che ora sa decifrare.
        if let token = payload.pairingToken {
            _ = try? await sendPairHello(token: token, to: peer)
        }
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

    // MARK: Modifica / cancellazione per tutti

    /// Modifica (EDIT, solo testo) o cancella per tutti (DELETE: testo, foto, nota vocale) un nostro
    /// messaggio entro 15 minuti dall'invio: applica la revisione in locale e la invia nella stessa
    /// conversazione dell'originale (in coda fino al `DELIVERY_ACK` in 1-a-1 e gruppi).
    /// Ritorna il path del media rimosso dal messaggio (DELETE di foto / nota vocale): il chiamante
    /// cancella il file con `MediaStore`.
    @discardableResult
    func reviseMessage(_ message: MessageRecord, action: RevisionAction, newText: String = "", now: Date = Date()) async throws -> String? {
        guard isRunning else { throw RouterError.notRunning }
        guard let current = try await database.messages.fetch(msgID: message.msgID),
              current.isOutgoing, current.senderNodeID == identity.nodeID, !current.isDeleted, current.isRevisableContent,
              let conversation = try await database.conversations.fetch(id: current.conversationID),
              !conversation.isExpired(now: now) else { throw RouterError.revisionNotAllowed }
        guard now.unixMilliseconds - current.createdAtMs <= MeshProtocol.revisionWindowMs else { throw RouterError.revisionWindowExpired }

        var revision = MessageRevision()
        revision.targetMsgID = current.msgID
        revision.action = action
        revision.revisedAtMs = now.unixMilliseconds
        var removedMediaPath: String?
        switch action {
        case .edit:
            let text = newText.trimmingCharacters(in: .whitespacesAndNewlines)
            guard current.contentType == .text, !text.isEmpty, text.count <= MeshProtocol.maxRevisionTextLength else {
                throw RouterError.revisionNotAllowed
            }
            if conversation.type == .cabinLounge {
                guard !Self.containsLink(text) else { throw RouterError.linkNotAllowedInLounge }
                guard !ContentFilter.containsProfanity(text) else { throw RouterError.languageNotAllowedInLounge }
            }
            guard text != current.text else { return nil }
            revision.newText = text
            guard try await database.messages.applyEdit(msgID: current.msgID, text: text, editedAtMs: revision.revisedAtMs) else {
                throw RouterError.revisionNotAllowed
            }
        case .delete:
            let result = try await database.messages.applyDeleteForEveryone(msgID: current.msgID)
            guard result.deleted else { throw RouterError.revisionNotAllowed }
            removedMediaPath = result.mediaPath
        default:
            throw RouterError.revisionNotAllowed
        }
        try await sendRevisionPacket(revision, in: conversation)
        return removedMediaPath
    }

    /// Invia una `MESSAGE_REVISION` gia' costruita, senza le verifiche lato UI (usata da
    /// `reviseMessage` e dai test per simulare revisioni non valide). Ritorna il `msg_id` del pacchetto.
    @discardableResult
    func sendRevisionPacket(_ revision: MessageRevision, in conversation: ConversationRecord) async throws -> Data {
        guard isRunning else { throw RouterError.notRunning }
        let payload = InnerPayload.extra(try MeshWire.serialize(revision), identity: identity, nickname: nickname)
        let msgID = CryptoEngine.randomMessageID()
        let envelope = try await makeEnvelope(type: .messageRevision, payload: payload, conversation: conversation, msgID: msgID)
        // Come i testi: 1-a-1 e gruppi restano in coda fino all'ACK, la Lounge no.
        let retainUntilAck = conversation.type != .cabinLounge
        if retainUntilAck {
            awaitingPacketAcks.insert(msgID)
        }
        _ = await dispatch(envelope, storeIfUnreachable: true, retainUntilAck: retainUntilAck)
        return msgID
    }

    // MARK: PAIR_HELLO e "sta scrivendo"

    /// `PAIR_HELLO` verso chi ha mostrato il QR: cifrato con la chiave derivata dal token del QR, con le
    /// nostre chiavi dichiarate nel payload; in coda finche' non arriva il `DELIVERY_ACK`.
    @discardableResult
    func sendPairHello(token: Data, to peer: PeerRecord) async throws -> Data {
        guard isRunning else { throw RouterError.notRunning }
        let key = try CryptoEngine.pairHelloKey(token: token)
        let payload = InnerPayload.signed(by: identity, nickname: nickname)
        let envelope = try EnvelopeBuilder(identity: identity).build(packetType: .pairHello, recipientID: peer.nodeID,
                                                                     payload: payload, key: key)
        awaitingPacketAcks.insert(envelope.msgID)
        pendingHellos[envelope.msgID] = peer.nodeID
        _ = await dispatch(envelope, storeIfUnreachable: true, retainUntilAck: true)
        return envelope.msgID
    }

    /// "Sta scrivendo" (`TYPING`): solo 1-a-1 e gruppi (mai Cabina Pubblica ne' Cabina del volo), ttl 2,
    /// niente coda ne' ACK. Ritorna `true` se almeno un vicino e' stato raggiunto.
    @discardableResult
    func sendTyping(_ isTyping: Bool, in conversation: ConversationRecord, now: Date = Date()) async -> Bool {
        guard isRunning, Self.allowsTyping(in: conversation, now: now) else { return false }
        if let peerID = conversation.peerNodeID, blocked.contains(peerID) { return false }
        var indicator = TypingIndicator()
        indicator.isTyping = isTyping
        guard let data = try? MeshWire.serialize(indicator),
              let envelope = try? await makeEnvelope(type: .typing,
                                                     payload: InnerPayload.extra(data, identity: identity, nickname: nickname),
                                                     conversation: conversation, msgID: CryptoEngine.randomMessageID(),
                                                     ttl: MeshProtocol.typingTTL) else { return false }
        return await dispatch(envelope, storeIfUnreachable: false)
    }

    /// Il "sta scrivendo" esiste solo nelle chat 1-a-1 e nei gruppi non scaduti (no Lounge, no Cabina del volo).
    static func allowsTyping(in conversation: ConversationRecord, now: Date = Date()) -> Bool {
        switch conversation.type {
        case .direct: return !conversation.isExpired(now: now)
        case .group: return conversation.flightCode == nil && !conversation.isExpired(now: now)
        case .cabinLounge: return false
        }
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
        // Passeggero virtuale (prova dell'app): vive solo in locale, nulla esce mai sulla radio.
        if DemoPeerIdentity.isLocalOnly(envelope.recipientID) { return true }
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
            if Date().timeIntervalSince(lastAckRetryAt) >= configuration.ackRetryInterval {
                await retryUnacknowledged()
            }
        }
    }

    private func peerCameOnline(_ nodeID: Data, via link: PeerLink) async {
        await markSeenThrottled(nodeID: nodeID, hopCount: 1, rssi: link.rssi)
        if link.id.role == .central {
            try? await database.peers.setBLEIdentifier(link.id.deviceID, nodeID: nodeID)
        }
        // Secondo link (dual-role) verso un vicino gia' online: coda e profilo sono gia' partiti.
        guard !links.values.contains(where: { $0.nodeID == nodeID && $0.id != link.id }) else { return }
        // "Persone vicine": ci annunciamo subito al nuovo vicino (se la visibilita' e' attiva).
        await sendPresence(toNode: nodeID)
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
        let awaiting = ((try? await database.messages.awaitingAckIDs(among: sentIDs)) ?? Set(sentIDs))
            .union(awaitingPacketAcks.intersection(sentIDs))
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
        lastAckRetryAt = Date()
        guard !links.isEmpty, let pending = try? await database.relayQueue.pending(), !pending.isEmpty else { return }
        let neighborIDs = Set(neighbors().map(\.nodeID))
        let candidates = pending.filter { neighborIDs.contains($0.recipientID) || isGroupRecipient($0.recipientID) }
        guard !candidates.isEmpty,
              let stored = try? await database.messages.awaitingAckIDs(among: candidates.map(\.msgID)) else { return }
        let awaiting = stored.union(awaitingPacketAcks)
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

        // PRESENCE: solo vicini diretti (ttl 1), mai ritrasmesso ne' salvato.
        if envelope.packetType == .presence {
            await handlePresence(envelope, via: link)
            return
        }

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
            case (.pairHello, .me):
                try await handlePairHello(envelope, hops: hops, via: link)
            case (.contactRequest, .me):
                try await handleContactRequest(envelope, hops: hops, via: link)
            case (.typing, .me), (.typing, .group):
                try await handleTyping(envelope, target: target)
            case (.messageRevision, .me), (.messageRevision, .group), (.messageRevision, .lounge):
                try await handleRevision(envelope, target: target, hops: hops, via: link)
            case (.mediaHeader, _):
                // Foto / nota vocale gia' cancellata per tutti dall'autore prima che arrivasse l'header.
                if consumePendingDelete(for: envelope) { return }
                try await forwardToSubsystems(envelope, hops: hops, target: target)
            case (.mediaChunk, _), (.proPassGrant, _), (.gameMove, _):
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
            await applyPendingRevisions(for: message)
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
            // Il messaggio e' arrivato: il suo "sta scrivendo" si spegne subito.
            emit(.typingChanged(conversationID: conversation.id, senderNodeID: sender.nodeID, nickname: nickname, isTyping: false))
            // Revisione arrivata prima dell'originale: se lo cancella, niente notifica.
            if await applyPendingRevisions(for: message) == .deleted { return }
            if notify {
                let badge = try? await database.conversations.totalUnread()
                await notifier.notify(message: message, conversation: conversation, badgeCount: badge)
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
        guard envelope.packetType == .chatText || envelope.packetType == .gameMove || envelope.packetType == .messageRevision
                || envelope.packetType == .pairHello,
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
        // Mosse di gioco, revisioni e PAIR_HELLO: non hanno un messaggio proprio (lo stato vive nella bolla dell'invito); il
        // duplicato di un msg_id gia' visto da un peer noto viene riconfermato cosi' smette di ritrasmettere.
        try? await sendAck(for: envelope, to: peer)
    }

    private func handleAck(_ envelope: MeshEnvelope) async throws {
        let (payload, _) = try await openDirect(envelope)
        guard let msgID = payload.replyToMessageID else { return }
        try await database.messages.advanceDeliveryStatus(to: .deliveredAck, msgID: msgID)
        awaitingPacketAcks.remove(msgID)
        _ = try? await database.relayQueue.remove(msgID: msgID)
        // PAIR_HELLO confermato: ora chi ha mostrato il QR ci conosce e puo' decifrare il nostro profilo.
        if let nodeID = pendingHellos.removeValue(forKey: msgID),
           let peer = try? await database.peers.fetch(nodeID: nodeID) {
            profileSentTo.remove(nodeID)
            await sendProfile(to: peer)
        }
    }

    // MARK: - PAIR_HELLO ricevuto

    /// Qualcuno ha scansionato il nostro QR: il payload si apre solo con la chiave di uno dei nostri token
    /// validi; il mittente dichiarato deve firmare l'envelope e il suo `node_id` deve derivare dalla sua
    /// chiave X25519. Diventa un contatto fidato con la chat 1-a-1 in cima alla lista.
    private func handlePairHello(_ envelope: MeshEnvelope, hops: Int, via link: LinkID) async throws {
        let tokens = (try? await database.pairingTokens.validTokens()) ?? []
        var opened: InnerPayload?
        for token in tokens {
            guard let key = try? CryptoEngine.pairHelloKey(token: token) else { continue }
            // `open` verifica anche la firma con la chiave Ed25519 dichiarata nel payload.
            if let payload = try? EnvelopeVerifier.open(envelope, key: key) {
                opened = payload
                break
            }
        }
        guard let payload = opened else { throw RouterError.pairHelloRejected }
        guard payload.senderEd25519Pub.count == MeshProtocol.publicKeyLength,
              payload.declaredSenderNodeID == envelope.senderNodeID,
              envelope.senderNodeID != identity.nodeID,
              !blocked.contains(envelope.senderNodeID) else { throw RouterError.senderMismatch }
        if let known = try await database.peers.fetch(nodeID: envelope.senderNodeID) {
            guard !known.isBlocked else { return }
            guard known.ed25519Pub == payload.senderEd25519Pub else { throw RouterError.senderMismatch }
        }
        let trimmed = String(payload.senderNickname.trimmingCharacters(in: .whitespacesAndNewlines).prefix(MeshProtocol.maxNicknameLength))
        let declared = PeerRecord(nodeID: envelope.senderNodeID, x25519Pub: payload.senderX25519Pub,
                                  ed25519Pub: payload.senderEd25519Pub,
                                  nickname: trimmed.isEmpty ? String(localized: "Passeggero") : trimmed,
                                  isTrusted: true)
        // Upsert non autorevole: un nickname gia' arrivato con PROFILE_UPDATE non viene sovrascritto.
        let peer = try await database.peers.upsert(declared)
        let conversation = try await database.conversations.ensureDirectConversation(with: peer, showAtTop: true)
        await markSeenThrottled(nodeID: peer.nodeID, hopCount: hops, rssi: links[link]?.rssi)
        await refreshTrustedPeers()
        emit(.pairedByHello(conversationID: conversation.id, peerNodeID: peer.nodeID))
        profileSentTo.remove(peer.nodeID)
        await sendProfile(to: peer)
        try await sendAck(for: envelope, to: peer)
    }

    // MARK: - "Sta scrivendo" ricevuto

    /// Stato solo in memoria (nessun DB, nessuna notifica): la UI lo fa scadere dopo 6 s.
    private func handleTyping(_ envelope: MeshEnvelope, target: Target) async throws {
        let conversationID: String
        let senderNodeID: Data
        let nickname: String
        let payload: InnerPayload
        switch target {
        case .me:
            let (opened, peer) = try await openDirect(envelope)
            // Niente chat creata da un "sta scrivendo": serve una conversazione gia' esistente.
            let candidate = ConversationRecord.direct(with: peer)
            guard let existing = try await database.conversations.fetch(id: candidate.id),
                  Self.allowsTyping(in: existing) else { return }
            (payload, conversationID, senderNodeID, nickname) = (opened, existing.id, peer.nodeID, peer.nickname)
        case .group(let group):
            guard Self.allowsTyping(in: group), let secret = group.groupSecretKey else { return }
            let (opened, sender) = try await openShared(envelope, key: try CryptoEngine.groupKey(fromSecret: secret))
            (payload, conversationID, senderNodeID, nickname) = (opened, group.id, sender.nodeID, sender.nickname)
        case .lounge, .other:
            return
        }
        let indicator = try MeshWire.parseTypingIndicator(payload.extraData)
        emit(.typingChanged(conversationID: conversationID, senderNodeID: senderNodeID, nickname: nickname,
                            isTyping: indicator.isTyping))
    }

    // MARK: - Revisioni ricevute (MESSAGE_REVISION)

    private struct PendingRevision {
        let revision: MessageRevision
        let senderNodeID: Data
        let conversationID: String
        let receivedAt: Date
    }

    enum RevisionResult: Equatable {
        case none
        case edited
        case deleted
    }

    /// Revisione ricevuta nella stessa conversazione dell'originale: verificata, applicata (o tenuta in
    /// memoria se l'originale non e' ancora arrivato) e confermata con l'ACK in 1-a-1 e gruppi.
    private func handleRevision(_ envelope: MeshEnvelope, target: Target, hops: Int, via link: LinkID) async throws {
        let payload: InnerPayload
        let sender: PeerRecord
        let conversation: ConversationRecord
        switch target {
        case .me:
            (payload, sender) = try await openDirect(envelope)
            conversation = try await database.conversations.ensureDirectConversation(with: sender)
        case .group(let group):
            guard let secret = group.groupSecretKey else { throw RouterError.missingGroupKey }
            let (opened, declared) = try await openShared(envelope, key: try CryptoEngine.groupKey(fromSecret: secret))
            payload = opened
            sender = try await database.peers.upsert(declared)
            conversation = group
        case .lounge(let lounge):
            (payload, sender) = try await openShared(envelope, key: CryptoEngine.publicCabinKey)
            conversation = lounge
        case .other:
            return
        }
        let parsed = try MeshWire.parseMessageRevision(payload.extraData)
        await markSeenThrottled(nodeID: sender.nodeID, hopCount: hops, rssi: links[link]?.rssi)
        if let revision = Self.normalizedRevision(parsed, isLounge: conversation.type == .cabinLounge) {
            if let original = try await database.messages.fetch(msgID: revision.targetMsgID) {
                _ = await applyRevision(revision, from: sender.nodeID, conversationID: conversation.id, to: original)
            } else {
                bufferPendingRevision(PendingRevision(revision: revision, senderNodeID: sender.nodeID,
                                                      conversationID: conversation.id, receivedAt: Date()))
            }
        }
        // Pacchetto autentico: confermato anche se scartato, cosi' il mittente smette di ritrasmetterlo.
        if conversation.type != .cabinLounge {
            try await sendAck(for: envelope, to: sender)
        }
    }

    /// Forma del pacchetto: `msg_id` di 16 byte, azione nota, testo dell'EDIT 1..4000 caratteri dopo il
    /// trim. Nella Lounge niente link e parolacce mascherate (come per i testi).
    static func normalizedRevision(_ revision: MessageRevision, isLounge: Bool) -> MessageRevision? {
        guard revision.targetMsgID.count == MeshProtocol.msgIDLength, revision.revisedAtMs > 0 else { return nil }
        var normalized = revision
        switch revision.action {
        case .edit:
            var text = revision.newText.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty, text.count <= MeshProtocol.maxRevisionTextLength else { return nil }
            if isLounge {
                guard !containsLink(text) else { return nil }
                if AppSettings.isLanguageFilterEnabled { text = ContentFilter.masked(text) }
            }
            normalized.newText = text
        case .delete:
            normalized.newText = ""
        default:
            return nil
        }
        return normalized
    }

    /// Regole comuni (identiche su iOS e Android): stessa conversazione, solo l'autore, entro 15 minuti
    /// (+2 di tolleranza), EDIT solo su testi non cancellati, DELETE definitivo su testo / foto / vocale.
    @discardableResult
    private func applyRevision(_ revision: MessageRevision, from senderNodeID: Data, conversationID: String,
                               to original: MessageRecord) async -> RevisionResult {
        guard original.conversationID == conversationID,
              original.senderNodeID == senderNodeID,
              !original.isOutgoing,
              !blocked.contains(senderNodeID) else { return .none }
        let elapsed = revision.revisedAtMs - original.createdAtMs
        guard elapsed >= -MeshProtocol.revisionClockToleranceMs,
              elapsed <= MeshProtocol.revisionWindowMs + MeshProtocol.revisionClockToleranceMs else { return .none }
        switch revision.action {
        case .edit:
            let applied = (try? await database.messages.applyEdit(msgID: original.msgID, text: revision.newText,
                                                                   editedAtMs: revision.revisedAtMs)) ?? false
            return applied ? .edited : .none
        case .delete:
            guard let result = try? await database.messages.applyDeleteForEveryone(msgID: original.msgID),
                  result.deleted else { return .none }
            if let path = result.mediaPath {
                mediaStore?.remove(relativePath: path)
            }
            // Il testo cancellato non deve restare nel Centro Notifiche.
            await notifier.removeNotification(msgID: original.msgID)
            return .deleted
        default:
            return .none
        }
    }

    private func bufferPendingRevision(_ pending: PendingRevision) {
        purgeExpiredPendingRevisions()
        if pendingRevisions.count >= MeshProtocol.maxPendingRevisions {
            pendingRevisions.removeFirst(pendingRevisions.count - MeshProtocol.maxPendingRevisions + 1)
        }
        pendingRevisions.append(pending)
    }

    private func purgeExpiredPendingRevisions(now: Date = Date()) {
        let lifetime = TimeInterval(MeshProtocol.revisionWindowMs / 1_000)
        pendingRevisions.removeAll { now.timeIntervalSince($0.receivedAt) > lifetime }
    }

    /// L'originale e' appena stato salvato: applica le revisioni arrivate prima (in ordine di `revised_at_ms`).
    @discardableResult
    private func applyPendingRevisions(for message: MessageRecord) async -> RevisionResult {
        guard !pendingRevisions.isEmpty else { return .none }
        purgeExpiredPendingRevisions()
        let matching = pendingRevisions
            .filter { $0.revision.targetMsgID == message.msgID }
            .sorted { $0.revision.revisedAtMs < $1.revision.revisedAtMs }
        guard !matching.isEmpty else { return .none }
        pendingRevisions.removeAll { $0.revision.targetMsgID == message.msgID }
        var outcome = RevisionResult.none
        for pending in matching {
            guard let current = try? await database.messages.fetch(msgID: message.msgID) else { break }
            let result = await applyRevision(pending.revision, from: pending.senderNodeID,
                                             conversationID: pending.conversationID, to: current)
            if result == .deleted { outcome = .deleted } else if result == .edited, outcome == .none { outcome = .edited }
        }
        return outcome
    }

    /// Header di una foto / nota vocale gia' cancellata per tutti dal suo autore: il media non viene
    /// nemmeno ricostruito (i chunk orfani scadono da soli).
    private func consumePendingDelete(for envelope: MeshEnvelope) -> Bool {
        guard !pendingRevisions.isEmpty else { return false }
        purgeExpiredPendingRevisions()
        let window = MeshProtocol.revisionWindowMs + MeshProtocol.revisionClockToleranceMs
        guard pendingRevisions.contains(where: {
            $0.revision.targetMsgID == envelope.msgID && $0.revision.action == .delete && $0.senderNodeID == envelope.senderNodeID
                && $0.revision.revisedAtMs - envelope.createdAtMs <= window
                && $0.revision.revisedAtMs - envelope.createdAtMs >= -MeshProtocol.revisionClockToleranceMs
        }) else { return false }
        pendingRevisions.removeAll { $0.revision.targetMsgID == envelope.msgID }
        return true
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
        // Foto / nota vocale / mossa in arrivo: chi la manda ha smesso di scrivere.
        if envelope.packetType == .mediaHeader || envelope.packetType == .gameMove, packet.conversation.type != .cabinLounge {
            emit(.typingChanged(conversationID: packet.conversation.id, senderNodeID: packet.sender.nodeID,
                                nickname: packet.sender.nickname, isTyping: false))
        }
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

    // MARK: - Persone vicine (PRESENCE) e "Saluta" / "Sfida" (CONTACT_REQUEST)

    struct OutgoingContactRequest: Sendable {
        let requestID: Data
        let peer: PeerRecord
        let gameKind: Int32
        let sentAt: Date
    }

    func setPresenceSettings(_ settings: PresenceSettings) {
        presenceSettings = settings
    }

    func currentPresenceSettings() -> PresenceSettings { presenceSettings }

    /// Vicini annunciati con PRESENCE ancora validi (ordinati per segnale / ultimo visto).
    func nearbyPeople(now: Date = Date()) -> [NearbyPerson] {
        presenceCache.sorted(now: now)
    }

    func pendingContactRequests(now: Date = Date()) -> [IncomingContactRequest] {
        purgeNearbyState(now: now)
        return incomingContactRequests.values.sorted { $0.receivedAt > $1.receivedAt }
    }

    /// PRESENCE: chiave della Lounge, `recipient_id` = 8 zeri, ttl 1. Con `toNode` solo a quel vicino
    /// (apertura del link), altrimenti a tutti i vicini diretti. Niente invio con la visibilita' spenta.
    @discardableResult
    func sendPresence(toNode nodeID: Data? = nil, now: Date = Date()) async -> Bool {
        guard isRunning, presenceSettings.isVisible, !links.isEmpty else { return false }
        var presence = Presence()
        presence.avatarColorHex = profile?.avatarColorHex ?? ""
        presence.flightCode = presenceSettings.flightCode ?? ""
        presence.openToGames = presenceSettings.openToGames
        presence.sentAtMs = now.unixMilliseconds
        guard let data = try? MeshWire.serialize(presence),
              let envelope = try? EnvelopeBuilder(identity: identity).build(
                packetType: .presence, recipientID: MeshProtocol.loungeRecipientID,
                payload: InnerPayload.extra(data, identity: identity, nickname: nickname),
                key: CryptoEngine.publicCabinKey, ttl: NearbyProtocol.presenceTTL),
              let bytes = try? MeshWire.serialize(envelope) else { return false }
        _ = seen.insertIfNew(envelope.msgID)
        if let nodeID {
            return (try? await transport.send(bytes, toNode: nodeID)) ?? false
        }
        return await transport.broadcast(bytes, excluding: []) > 0
    }

    /// PRESENCE ricevuto: `openShared` con la chiave della Lounge, `node_id` derivato dalla X25519
    /// dichiarata, mittenti bloccati ignorati, nickname filtrato. Solo cache in memoria.
    private func handlePresence(_ envelope: MeshEnvelope, via link: LinkID) async {
        let senderID = envelope.senderNodeID
        guard envelope.recipientID == MeshProtocol.loungeRecipientID, envelope.hopCount == 0,
              senderID != identity.nodeID, !blocked.contains(senderID) else { return }
        guard let opened = try? await openShared(envelope, key: CryptoEngine.publicCabinKey),
              opened.0.senderX25519Pub.count == MeshProtocol.publicKeyLength,
              let presence = try? MeshWire.parsePresence(opened.0.extraData) else {
            stats.undecryptable += 1
            return
        }
        let (payload, sender) = opened
        let known = try? await database.peers.fetch(nodeID: senderID)
        if known?.isBlocked == true { return }
        let declared = String(payload.senderNickname.trimmingCharacters(in: .whitespacesAndNewlines).prefix(MeshProtocol.maxNicknameLength))
        var nickname = declared.isEmpty ? (known?.nickname ?? sender.nickname) : declared
        if AppSettings.isLanguageFilterEnabled { nickname = ContentFilter.masked(nickname) }
        let color = Self.isValidColorHex(presence.avatarColorHex) ? presence.avatarColorHex.uppercased() : known?.avatarColorHex
        let flight = presence.flightCode.trimmingCharacters(in: .whitespacesAndNewlines)
        let person = NearbyPerson(nodeID: senderID, x25519Pub: payload.senderX25519Pub, ed25519Pub: payload.senderEd25519Pub,
                                  nickname: nickname, avatarColorHex: color,
                                  flightCode: flight.isEmpty || flight.count > NearbyProtocol.maxFlightCodeLength ? nil : flight,
                                  openToGames: presence.openToGames, rssi: links[link]?.rssi, lastSeen: Date())
        presenceCache.upsert(person)
        if known != nil {
            await markSeenThrottled(nodeID: senderID, hopCount: 1, rssi: links[link]?.rssi)
        }
        stats.delivered += 1
        emit(.presenceUpdated(presenceCache.person(senderID) ?? person))
    }

    /// "Saluta" (`gameKind` nil) o "Sfida": REQUEST diretta, cifrata con la chiave 1-a-1 calcolata dalla
    /// X25519 del PRESENCE (o del DB), ttl 2, niente coda ne' ACK. Ritorna il `request_id`.
    @discardableResult
    func sendContactRequest(to peer: PeerRecord, greeting: String = "", gameKind: GameKind? = nil,
                            now: Date = Date()) async throws -> Data {
        guard isRunning else { throw RouterError.notRunning }
        guard peer.nodeID != identity.nodeID, !blocked.contains(peer.nodeID),
              peer.x25519Pub.count == MeshProtocol.publicKeyLength,
              CryptoEngine.nodeID(forX25519PublicKey: peer.x25519Pub) == peer.nodeID else {
            throw RouterError.contactRequestNotAllowed
        }
        purgeNearbyState(now: now)
        var request = ContactRequest()
        request.action = .request
        request.requestID = CryptoEngine.randomMessageID()
        request.greeting = NearbyProtocol.sanitizedGreeting(greeting, maskProfanity: true)
        request.gameKind = gameKind.map { Int32($0.rawValue) } ?? 0
        outgoingContactRequests[request.requestID] = OutgoingContactRequest(requestID: request.requestID, peer: peer,
                                                                            gameKind: request.gameKind, sentAt: now)
        _ = try await sendContactPacket(request, to: peer)
        return request.requestID
    }

    /// Accetta (ACCEPT) o rifiuta (DECLINE) una richiesta ricevuta. Con Accetta il peer diventa un contatto
    /// fidato e la chat 1-a-1 compare in cima; se era una "Sfida", il primo invito di quel gioco da quel
    /// peer viene accettato da solo (l'ACCEPT vale come consenso).
    @discardableResult
    func respondToContactRequest(_ requestID: Data, accept: Bool, now: Date = Date()) async throws -> ConversationRecord? {
        guard isRunning else { throw RouterError.notRunning }
        purgeNearbyState(now: now)
        guard let request = incomingContactRequests.removeValue(forKey: requestID) else {
            throw RouterError.contactRequestNotFound
        }
        var response = ContactRequest()
        response.action = accept ? .accept : .decline
        response.requestID = requestID
        guard accept else {
            _ = try? await sendContactPacket(response, to: request.sender)
            return nil
        }
        if request.gameKind != 0 {
            acceptedChallenges[request.sender.nodeID] = (request.gameKind, now.addingTimeInterval(NearbyProtocol.requestLifetime))
        }
        let conversation = try await establishContact(request.sender, nickname: request.sender.nickname,
                                                      avatarColorHex: request.avatarColorHex)
        // Prima l'ACCEPT (chi ha chiesto ci registra), poi il profilo che ora sa decifrare.
        _ = try? await sendContactPacket(response, to: request.sender)
        profileSentTo.remove(request.sender.nodeID)
        await sendProfile(to: request.sender)
        return conversation
    }

    /// `true` (una sola volta) se abbiamo accettato una "Sfida" a quel gioco da quel peer.
    func consumeAcceptedChallenge(from nodeID: Data, kind: GameKind, now: Date = Date()) -> Bool {
        guard let challenge = acceptedChallenges[nodeID], challenge.expiresAt > now,
              challenge.kind == Int32(kind.rawValue) else { return false }
        acceptedChallenges.removeValue(forKey: nodeID)
        return true
    }

    private func sendContactPacket(_ request: ContactRequest, to peer: PeerRecord) async throws -> Bool {
        let key = try identity.directChatKey(peerX25519PublicKeyRaw: peer.x25519Pub)
        let payload = InnerPayload.extra(try MeshWire.serialize(request), identity: identity, nickname: nickname)
        let envelope = try EnvelopeBuilder(identity: identity).build(packetType: .contactRequest, recipientID: peer.nodeID,
                                                                     payload: payload, key: key,
                                                                     ttl: NearbyProtocol.contactRequestTTL)
        return await dispatch(envelope, storeIfUnreachable: false)
    }

    /// Peer fidato (come dopo il QR, senza sovrascrivere un nickname da PROFILE_UPDATE) con la chat in cima.
    private func establishContact(_ peer: PeerRecord, nickname: String, avatarColorHex: String?) async throws -> ConversationRecord {
        var trusted = peer
        trusted.isTrusted = true
        trusted.isBlocked = false
        trusted.nickname = nickname.isEmpty ? String(localized: "Passeggero") : nickname
        if trusted.avatarColorHex == nil { trusted.avatarColorHex = avatarColorHex }
        let stored = try await database.peers.upsert(trusted)
        let conversation = try await database.conversations.ensureDirectConversation(with: stored, showAtTop: true)
        await refreshTrustedPeers()
        return conversation
    }

    /// CONTACT_REQUEST ricevuto: chiavi del mittente note (DB, cache PRESENCE o nostra richiesta in
    /// corso) altrimenti scartato; firma verificata con la Ed25519 nota e chiavi dichiarate coincidenti.
    private func handleContactRequest(_ envelope: MeshEnvelope, hops: Int, via link: LinkID) async throws {
        let senderID = envelope.senderNodeID
        guard senderID != identity.nodeID, !blocked.contains(senderID) else { return }
        let now = Date()
        purgeNearbyState(now: now)
        let known = try await database.peers.fetch(nodeID: senderID)
        if known?.isBlocked == true { return }
        let cached = presenceCache.person(senderID, now: now)
        let pending = outgoingContactRequests.values.first { $0.peer.nodeID == senderID }
        let senderKeys: (x25519: Data, ed25519: Data)
        if let known {
            senderKeys = (known.x25519Pub, known.ed25519Pub)
        } else if let cached {
            senderKeys = (cached.x25519Pub, cached.ed25519Pub)
        } else if let pending {
            senderKeys = (pending.peer.x25519Pub, pending.peer.ed25519Pub)
        } else {
            throw RouterError.unknownPeer(senderID)
        }
        let key = try identity.directChatKey(peerX25519PublicKeyRaw: senderKeys.x25519)
        let payload = try EnvelopeVerifier.open(envelope, key: key, expectedSenderEd25519PublicKeyRaw: senderKeys.ed25519)
        guard payload.senderX25519Pub == senderKeys.x25519, payload.senderEd25519Pub == senderKeys.ed25519 else {
            throw RouterError.senderMismatch
        }
        let request = try MeshWire.parseContactRequest(payload.extraData)
        guard request.requestID.count == NearbyProtocol.requestIDLength else { return }
        let declared = String(payload.senderNickname.trimmingCharacters(in: .whitespacesAndNewlines).prefix(MeshProtocol.maxNicknameLength))
        var nickname = declared.isEmpty ? (known?.nickname ?? cached?.nickname ?? pending?.peer.nickname ?? String(localized: "Passeggero")) : declared
        if AppSettings.isLanguageFilterEnabled { nickname = ContentFilter.masked(nickname) }
        let color = known?.avatarColorHex ?? cached?.avatarColorHex

        switch request.action {
        case .request:
            if let last = lastContactRequestAt[senderID], now.timeIntervalSince(last) < NearbyProtocol.requestRateLimit { return }
            guard incomingContactRequests.count < NearbyProtocol.maxPendingRequests,
                  incomingContactRequests[request.requestID] == nil else { return }
            lastContactRequestAt[senderID] = now
            let sender = PeerRecord(nodeID: senderID, x25519Pub: senderKeys.x25519, ed25519Pub: senderKeys.ed25519, nickname: nickname)
            let gameKind = NearbyProtocol.isChallengeKind(request.gameKind) ? request.gameKind : 0
            let incoming = IncomingContactRequest(requestID: request.requestID, sender: sender, avatarColorHex: color,
                                                  greeting: NearbyProtocol.sanitizedGreeting(request.greeting,
                                                                                             maskProfanity: AppSettings.isLanguageFilterEnabled),
                                                  gameKind: gameKind, receivedAt: now)
            incomingContactRequests[request.requestID] = incoming
            emit(.contactRequestReceived(incoming))
            await notifier.notifyContactRequest(incoming)
        case .accept:
            guard let outgoing = outgoingContactRequests[request.requestID], outgoing.peer.nodeID == senderID else { return }
            outgoingContactRequests.removeValue(forKey: request.requestID)
            let conversation = try await establishContact(outgoing.peer, nickname: nickname, avatarColorHex: color)
            await markSeenThrottled(nodeID: senderID, hopCount: hops, rssi: links[link]?.rssi)
            profileSentTo.remove(senderID)
            if let stored = try? await database.peers.fetch(nodeID: senderID) {
                await sendProfile(to: stored)
            }
            emit(.contactRequestAnswered(requestID: request.requestID, peerNodeID: senderID, nickname: nickname,
                                         outcome: .accepted(conversationID: conversation.id), gameKind: outgoing.gameKind))
        case .decline:
            guard let outgoing = outgoingContactRequests.removeValue(forKey: request.requestID) else { return }
            guard outgoing.peer.nodeID == senderID else {
                outgoingContactRequests[request.requestID] = outgoing
                return
            }
            emit(.contactRequestAnswered(requestID: request.requestID, peerNodeID: senderID, nickname: nickname,
                                         outcome: .declined, gameKind: outgoing.gameKind))
        default:
            return
        }
    }

    private func purgeNearbyState(now: Date = Date()) {
        presenceCache.purgeExpired(now: now)
        let lifetime = NearbyProtocol.requestLifetime
        incomingContactRequests = incomingContactRequests.filter { !$0.value.isExpired(now: now) }
        outgoingContactRequests = outgoingContactRequests.filter { now.timeIntervalSince($0.value.sentAt) < lifetime }
        lastContactRequestAt = lastContactRequestAt.filter { now.timeIntervalSince($0.value) < NearbyProtocol.requestRateLimit }
        acceptedChallenges = acceptedChallenges.filter { $0.value.expiresAt > now }
    }

    // MARK: - Manutenzione

    func runMaintenance(now: Date = Date()) async {
        seen.purgeExpired(nowMs: now.unixMilliseconds)
        let throttle = configuration.reackThrottle
        lastAckSentAt = lastAckSentAt.filter { now.timeIntervalSince($0.value) < throttle }
        let seenInterval = configuration.lastSeenWriteInterval
        lastSeenWrites = lastSeenWrites.filter { now.timeIntervalSince($0.value.date) < seenInterval }
        purgeExpiredPendingRevisions(now: now)
        purgeNearbyState(now: now)
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
