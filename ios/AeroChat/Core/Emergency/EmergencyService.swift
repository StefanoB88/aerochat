import Foundation

/// Avviso ricevuto da un passeggero vicino (banner rosso + notifica prioritaria).
struct EmergencyAlert: Sendable, Equatable, Identifiable {
    let msgID: Data
    let senderNodeID: Data
    let senderNickname: String
    let status: EmergencyStatus
    let text: String
    let latitude: Double?
    let longitude: Double?
    let receivedAt: Date
    let hopCount: Int

    var id: Data { msgID }

    var statusTitle: String { EmergencyService.title(for: status) }
    var hasLocation: Bool { latitude != nil && longitude != nil }
}

/// Modalita' emergenza/eventi: un messaggio prioritario a tutti i telefoni a portata (e a chi passa piu'
/// tardi, grazie alla coda store-and-forward), con stato, testo breve e posizione facoltativa.
actor EmergencyService {

    static let maxTextLength = 280

    enum Event: Sendable, Equatable {
        case received(EmergencyAlert)
    }

    private let router: MeshRouterActor
    private let database: AppDatabase
    private let identity: IdentityKeys
    private let notifier: any MeshNotifier
    private var nickname: String
    private var inboundTask: Task<Void, Never>?
    private var subscribers: [UUID: AsyncStream<Event>.Continuation] = [:]

    init(router: MeshRouterActor, database: AppDatabase, identity: IdentityKeys, nickname: String, notifier: any MeshNotifier) {
        self.router = router
        self.database = database
        self.identity = identity
        self.nickname = nickname
        self.notifier = notifier
    }

    func start() async {
        guard inboundTask == nil else { return }
        let packets = await router.inboundPackets()
        inboundTask = Task { [weak self] in
            for await packet in packets where packet.envelope.packetType == .emergency {
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
        return AsyncStream(bufferingPolicy: .bufferingNewest(8)) { continuation in
            subscribers[id] = continuation
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.removeSubscriber(id) }
            }
        }
    }

    private func removeSubscriber(_ id: UUID) {
        subscribers.removeValue(forKey: id)
    }

    static func title(for status: EmergencyStatus) -> String {
        switch status {
        case .ok: return String(localized: "Sto bene")
        case .needHelp: return String(localized: "Ho bisogno di aiuto")
        case .allClear: return String(localized: "Cessato allarme / informazione")
        case .unspecified, .UNRECOGNIZED: return String(localized: "Avviso")
        }
    }

    static func emoji(for status: EmergencyStatus) -> String {
        switch status {
        case .ok: return "✅"
        case .needHelp: return "🆘"
        case .allClear: return "ℹ️"
        case .unspecified, .UNRECOGNIZED: return "⚠️"
        }
    }

    /// Invia l'avviso sulla Lounge con TTL massimo; resta in coda per chi arriva dopo.
    func broadcast(status: EmergencyStatus, text: String, latitude: Double?, longitude: Double?) async throws {
        let lounge = try await database.conversations.currentCabinLounge()
        let trimmed = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxTextLength))
        var broadcast = EmergencyBroadcast()
        broadcast.status = status
        broadcast.text = trimmed
        broadcast.issuedAtMs = Date.nowUnixMilliseconds
        if let latitude, let longitude {
            broadcast.latitude = latitude
            broadcast.longitude = longitude
            broadcast.hasLocation_p = true
        }
        let data = try MeshWire.serialize(broadcast)
        let payload = InnerPayload.extra(data, identity: identity, nickname: nickname)
        let (msgID, _) = try await router.sendPacket(type: .emergency, payload: payload, in: lounge,
                                                    ttl: MeshProtocol.maxTTL, storeIfUnreachable: true)
        // Copia locale nella Cabina, cosi' chi l'ha inviato vede cosa e' partito.
        let message = MessageRecord(msgID: msgID, conversationID: lounge.id, senderNodeID: identity.nodeID,
                                    senderNickname: nickname, contentType: .system,
                                    text: Self.systemText(nickname: nickname, broadcast: broadcast),
                                    isOutgoing: true, deliveryStatus: .sentMesh)
        _ = try? await database.messages.insert(message)
    }

    static func systemText(nickname: String, broadcast: EmergencyBroadcast) -> String {
        var parts = ["\(emoji(for: broadcast.status)) \(nickname): \(title(for: broadcast.status))"]
        if !broadcast.text.isEmpty { parts.append(broadcast.text) }
        if broadcast.hasLocation_p {
            parts.append(String(format: "📍 %.5f, %.5f", broadcast.latitude, broadcast.longitude))
        }
        return parts.joined(separator: " — ")
    }

    private func handle(_ packet: InboundPacket) async {
        guard let broadcast = try? MeshWire.parseEmergencyBroadcast(packet.payload.extraData),
              broadcast.text.count <= Self.maxTextLength,
              broadcast.status != .unspecified else { return }
        if broadcast.hasLocation_p {
            guard (-90...90).contains(broadcast.latitude), (-180...180).contains(broadcast.longitude) else { return }
        }
        let lounge = packet.conversation
        let text = Self.systemText(nickname: packet.sender.nickname, broadcast: broadcast)
        let message = MessageRecord(msgID: packet.envelope.msgID, conversationID: lounge.id,
                                    senderNodeID: packet.sender.nodeID, senderNickname: packet.sender.nickname,
                                    contentType: .system, text: text, isOutgoing: false, deliveryStatus: .deliveredAck,
                                    hopCountReceived: packet.hopCount, createdAtMs: packet.envelope.createdAtMs)
        guard (try? await database.messages.insert(message)) == true else { return }
        let alert = EmergencyAlert(msgID: packet.envelope.msgID, senderNodeID: packet.sender.nodeID,
                                   senderNickname: packet.sender.nickname, status: broadcast.status, text: broadcast.text,
                                   latitude: broadcast.hasLocation_p ? broadcast.latitude : nil,
                                   longitude: broadcast.hasLocation_p ? broadcast.longitude : nil,
                                   receivedAt: Date(), hopCount: packet.hopCount)
        for continuation in subscribers.values {
            continuation.yield(.received(alert))
        }
        if let local = notifier as? LocalNotificationCenter {
            await local.notifyEmergency(alert)
        }
    }
}
