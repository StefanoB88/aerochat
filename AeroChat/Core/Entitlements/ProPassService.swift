import Foundation

/// Flight Group Pass "1 paga, il gruppo usa": l'utente Pro emette un `PRO_PASS_GRANT` firmato Ed25519
/// per ogni gruppo di viaggio; tutti i membri (anche Android, anche gratuiti) sbloccano la conversazione.
actor ProPassService {

    enum Event: Sendable, Equatable {
        case unlocked(conversationID: String, validUntilMs: Int64, issuerNodeID: Data)
    }

    /// Tolleranza sulla durata massima del grant (orologi non sincronizzati).
    static let clockSkew: TimeInterval = 15 * 60

    private let router: MeshRouterActor
    private let database: AppDatabase
    private let entitlements: EntitlementManager
    private let identity: IdentityKeys
    private var nickname: String
    private var inboundTask: Task<Void, Never>?
    private var subscribers: [UUID: AsyncStream<Event>.Continuation] = [:]

    init(router: MeshRouterActor, database: AppDatabase, entitlements: EntitlementManager, identity: IdentityKeys, nickname: String) {
        self.router = router
        self.database = database
        self.entitlements = entitlements
        self.identity = identity
        self.nickname = nickname
    }

    func start() async {
        guard inboundTask == nil else { return }
        let packets = await router.inboundPackets()
        inboundTask = Task { [weak self] in
            for await packet in packets where packet.envelope.packetType == .proPassGrant {
                guard let self else { break }
                await self.handle(packet)
            }
        }
    }

    func stop() {
        inboundTask?.cancel()
        inboundTask = nil
    }

    func setNickname(_ nickname: String) {
        self.nickname = nickname
    }

    func events() -> AsyncStream<Event> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(16)) { continuation in
            subscribers[id] = continuation
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.removeSubscriber(id) }
            }
        }
    }

    // MARK: - Emissione

    /// Se siamo Pro, sblocca il gruppo localmente e trasmette il grant ai membri. Ritorna `true` se emesso.
    @discardableResult
    func broadcastGrantIfEntitled(for group: ConversationRecord, now: Date = Date()) async throws -> Bool {
        guard group.type == .group, let groupID = group.groupID else { return false }
        let state = await entitlements.state
        guard state.isPro(now: now) else { return false }

        var validUntil = now.addingTimeInterval(MeshProtocol.proPassDuration).unixMilliseconds
        if state.tier == .pass72h, let ownLimit = state.validUntilMs {
            validUntil = min(validUntil, ownLimit)
        }
        let productID = state.tier == .lifetime ? MeshProtocol.ProductID.lifetime : MeshProtocol.ProductID.pass72h
        let grant = try Self.makeGrant(groupID: groupID, validUntilMs: validUntil, productID: productID, identity: identity)

        try await database.conversations.setProUnlocked(until: validUntil, conversationID: group.id)
        emit(.unlocked(conversationID: group.id, validUntilMs: validUntil, issuerNodeID: identity.nodeID))

        let payload = InnerPayload.extra(try MeshWire.serialize(grant), identity: identity, nickname: nickname)
        _ = try await router.sendPacket(type: .proPassGrant, payload: payload, in: group)
        return true
    }

    /// Ri-emette il grant per tutti i gruppi (dopo un acquisto).
    func broadcastGrantsForAllGroups() async {
        guard let groups = try? await database.conversations.fetchPermanent() else { return }
        for group in groups where group.type == .group {
            _ = try? await broadcastGrantIfEntitled(for: group)
        }
    }

    /// Messaggio firmato: `group_uuid (16) || valid_until_ms (Int64 Big-Endian)`.
    static func signedMessage(groupUUID: Data, validUntilMs: Int64) -> Data {
        groupUUID + validUntilMs.bigEndianBytes
    }

    static func makeGrant(groupID: UUID, validUntilMs: Int64, productID: String, identity: IdentityKeys) throws -> ProPassGrant {
        var grant = ProPassGrant()
        grant.groupUuid = groupID.rawBytes
        grant.validUntilMs = validUntilMs
        grant.issuerEd25519Pub = identity.ed25519PublicKeyRaw
        grant.productID = productID
        grant.signature = try CryptoEngine.sign(message: signedMessage(groupUUID: grant.groupUuid, validUntilMs: validUntilMs),
                                                with: identity.signingKey)
        return grant
    }

    /// Verifica firma, scadenza e durata massima (72 h + tolleranza) del grant.
    static func verify(_ grant: ProPassGrant, now: Date = Date()) -> Bool {
        guard grant.groupUuid.count == 16,
              grant.issuerEd25519Pub.count == MeshProtocol.publicKeyLength,
              grant.signature.count == MeshProtocol.signatureLength else { return false }
        let nowMs = now.unixMilliseconds
        let maximum = now.addingTimeInterval(MeshProtocol.proPassDuration + clockSkew).unixMilliseconds
        guard grant.validUntilMs > nowMs, grant.validUntilMs <= maximum else { return false }
        return CryptoEngine.verify(signature: grant.signature,
                                   message: signedMessage(groupUUID: grant.groupUuid, validUntilMs: grant.validUntilMs),
                                   publicKeyRaw: grant.issuerEd25519Pub)
    }

    // MARK: - Ricezione

    private func handle(_ packet: InboundPacket, now: Date = Date()) async {
        guard packet.conversation.type == .group,
              let grant = try? MeshWire.parseProPassGrant(packet.payload.extraData),
              Self.verify(grant, now: now),
              grant.issuerEd25519Pub == packet.sender.ed25519Pub,
              let groupID = packet.conversation.groupID,
              grant.groupUuid == groupID.rawBytes else { return }

        let current = packet.conversation.proUnlockedUntilMs ?? 0
        guard grant.validUntilMs > current else { return }
        try? await database.conversations.setProUnlocked(until: grant.validUntilMs, conversationID: packet.conversation.id)
        emit(.unlocked(conversationID: packet.conversation.id, validUntilMs: grant.validUntilMs, issuerNodeID: packet.sender.nodeID))
    }

    private func emit(_ event: Event) {
        for continuation in subscribers.values {
            continuation.yield(event)
        }
    }

    private func removeSubscriber(_ id: UUID) {
        subscribers.removeValue(forKey: id)
    }
}

// MARK: - Feature gate

enum ProFeature: Sendable, Equatable {
    case groupChat
    case voiceNote
    case photo
    case game
}

enum FeatureDecision: Sendable, Equatable {
    case allowed
    case requiresPass
    case photosNeedDirectLink
    case peerOutOfRange
    case loungeTextOnly

    var isAllowed: Bool { self == .allowed }
}

/// Regole del modello Freemium: testo 1-a-1, Lounge e store-and-forward sempre gratis;
/// gruppi 3+, vocali, foto e giochi con Pro o Flight Group Pass attivo sulla conversazione.
enum FeatureGate {

    static func decision(for feature: ProFeature,
                         userIsPro: Bool,
                         conversation: ConversationRecord,
                         reachability: Reachability,
                         now: Date = Date()) -> FeatureDecision {
        if conversation.type == .cabinLounge {
            return .loungeTextOnly
        }
        let unlocked = userIsPro || conversation.isProUnlocked(now: now)
        guard unlocked else { return .requiresPass }
        switch feature {
        case .groupChat, .game:
            return .allowed
        case .voiceNote:
            return reachability.allowsRealtimeDelivery ? .allowed : .peerOutOfRange
        case .photo:
            if !reachability.allowsRealtimeDelivery { return .peerOutOfRange }
            return reachability.allowsPhotos ? .allowed : .photosNeedDirectLink
        }
    }
}
