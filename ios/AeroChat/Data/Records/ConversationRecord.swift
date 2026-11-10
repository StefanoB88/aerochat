import Foundation
import GRDB

enum ConversationType: String, Codable, Sendable, DatabaseValueConvertible {
    case direct
    case group
    case cabinLounge = "cabin_lounge"
}

struct ConversationRecord: Codable, Hashable, Sendable, FetchableRecord, PersistableRecord {

    static let databaseTableName = "conversations"
    /// Identificatore fisso della Cabina Pubblica corrente.
    static let cabinLoungeID = "cabin_lounge"

    var id: String
    var type: ConversationType
    var title: String
    /// 8 byte confrontati con `MeshEnvelope.recipient_id` in ricezione.
    var recipientID: Data
    var peerNodeID: Data?
    var groupSecretKey: Data?
    var proUnlockedUntilMs: Int64?
    var createdAtMs: Int64
    var expiresAtMs: Int64?
    var lastMessageAtMs: Int64?
    var unreadCount: Int
    /// Chat fissata in cima alla lista.
    var isPinned: Bool = false
    /// Cabina del volo (gruppo derivato dalla carta d'imbarco): codice canonico `AZ|204|2026-11-10`.
    var flightCode: String?

    /// Gruppo pubblico del volo: testo gratis per tutti, scade dopo il volo.
    var isFlightLounge: Bool { flightCode != nil }

    enum CodingKeys: String, CodingKey {
        case id
        case type
        case title
        case recipientID = "recipient_id"
        case peerNodeID = "peer_node_id"
        case groupSecretKey = "group_secret_key"
        case proUnlockedUntilMs = "pro_unlocked_until_ms"
        case createdAtMs = "created_at_ms"
        case expiresAtMs = "expires_at_ms"
        case lastMessageAtMs = "last_message_at_ms"
        case unreadCount = "unread_count"
        case isPinned = "is_pinned"
        case flightCode = "flight_code"
    }

    enum Columns {
        static let id = Column(CodingKeys.id)
        static let type = Column(CodingKeys.type)
        static let title = Column(CodingKeys.title)
        static let recipientID = Column(CodingKeys.recipientID)
        static let peerNodeID = Column(CodingKeys.peerNodeID)
        static let proUnlockedUntilMs = Column(CodingKeys.proUnlockedUntilMs)
        static let expiresAtMs = Column(CodingKeys.expiresAtMs)
        static let lastMessageAtMs = Column(CodingKeys.lastMessageAtMs)
        static let unreadCount = Column(CodingKeys.unreadCount)
        static let isPinned = Column(CodingKeys.isPinned)
        static let flightCode = Column(CodingKeys.flightCode)
        static let createdAtMs = Column(CodingKeys.createdAtMs)
    }

    // MARK: - Factory

    /// Chat 1-a-1: l'id e' il `node_id` esadecimale del peer, il `recipient_id` e' il suo `node_id`.
    static func direct(with peer: PeerRecord, createdAt: Date = Date()) -> ConversationRecord {
        ConversationRecord(id: "direct:\(peer.nodeID.hexString)",
                           type: .direct,
                           title: peer.nickname,
                           recipientID: peer.nodeID,
                           peerNodeID: peer.nodeID,
                           groupSecretKey: nil,
                           proUnlockedUntilMs: nil,
                           createdAtMs: createdAt.unixMilliseconds,
                           expiresAtMs: nil,
                           lastMessageAtMs: nil,
                           unreadCount: 0)
    }

    /// Gruppo di viaggio: l'id e' lo UUID del gruppo, il `recipient_id` e' `SHA256(uuid)[0..8]`.
    static func group(id groupID: UUID, title: String, secretKey: Data, createdAt: Date = Date()) -> ConversationRecord {
        ConversationRecord(id: groupID.uuidString.lowercased(),
                           type: .group,
                           title: title,
                           recipientID: CryptoEngine.groupRecipientID(groupID: groupID),
                           peerNodeID: nil,
                           groupSecretKey: secretKey,
                           proUnlockedUntilMs: nil,
                           createdAtMs: createdAt.unixMilliseconds,
                           expiresAtMs: nil,
                           lastMessageAtMs: nil,
                           unreadCount: 0)
    }

    /// Cabina del volo: un gruppo con id, chiave e titolo derivati dalla carta d'imbarco, uguali per
    /// tutti i passeggeri dello stesso volo. Scade 36 ore dopo il giorno di partenza.
    static func flightLounge(_ pass: BoardingPass, createdAt: Date = Date()) -> ConversationRecord {
        var record = group(id: pass.loungeGroupID, title: pass.loungeTitle, secretKey: pass.loungeSecretKey, createdAt: createdAt)
        record.flightCode = pass.canonicalKey
        record.expiresAtMs = pass.loungeExpiry.unixMilliseconds
        return record
    }

    /// Cabina Pubblica: `recipient_id` tutto a zero, scade dopo 12 ore.
    static func cabinLounge(createdAt: Date = Date()) -> ConversationRecord {
        ConversationRecord(id: cabinLoungeID,
                           type: .cabinLounge,
                           title: String(localized: "Cabina Pubblica"),
                           recipientID: MeshProtocol.loungeRecipientID,
                           peerNodeID: nil,
                           groupSecretKey: nil,
                           proUnlockedUntilMs: nil,
                           createdAtMs: createdAt.unixMilliseconds,
                           expiresAtMs: createdAt.addingTimeInterval(MeshProtocol.loungeLifetime).unixMilliseconds,
                           lastMessageAtMs: nil,
                           unreadCount: 0)
    }

    /// UUID del gruppo (solo per `type == .group`).
    var groupID: UUID? {
        type == .group ? UUID(uuidString: id) : nil
    }

    /// `true` se la conversazione ha un Flight Group Pass attivo.
    func isProUnlocked(now: Date = Date()) -> Bool {
        (proUnlockedUntilMs ?? 0) > now.unixMilliseconds
    }

    func isExpired(now: Date = Date()) -> Bool {
        guard let expiresAtMs else { return false }
        return expiresAtMs <= now.unixMilliseconds
    }

    /// Elimina le conversazioni scadute (Lounge oltre le 12h); i messaggi seguono per `ON DELETE CASCADE`.
    static func purgeExpired(_ db: Database, nowMs: Int64) throws {
        try ConversationRecord
            .filter(Columns.expiresAtMs != nil && Columns.expiresAtMs <= nowMs)
            .deleteAll(db)
    }
}

/// Membro di un gruppo di viaggio (popolato dal QR e dal `GROUP_KEY_GOSSIP`).
struct GroupMemberRecord: Codable, Hashable, Sendable, FetchableRecord, PersistableRecord {

    static let databaseTableName = "group_members"

    var conversationID: String
    var nodeID: Data
    var nickname: String
    var x25519Pub: Data
    var ed25519Pub: Data
    var joinedAtMs: Int64

    enum CodingKeys: String, CodingKey {
        case conversationID = "conversation_id"
        case nodeID = "node_id"
        case nickname
        case x25519Pub = "x25519_pub"
        case ed25519Pub = "ed25519_pub"
        case joinedAtMs = "joined_at_ms"
    }

    enum Columns {
        static let conversationID = Column(CodingKeys.conversationID)
        static let nodeID = Column(CodingKeys.nodeID)
    }

    init(conversationID: String, peer: PeerRecord, joinedAt: Date = Date()) {
        self.conversationID = conversationID
        self.nodeID = peer.nodeID
        self.nickname = peer.nickname
        self.x25519Pub = peer.x25519Pub
        self.ed25519Pub = peer.ed25519Pub
        self.joinedAtMs = joinedAt.unixMilliseconds
    }
}
