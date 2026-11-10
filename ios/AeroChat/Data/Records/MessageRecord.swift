import Foundation
import GRDB

enum MessageContentType: String, Codable, Sendable, DatabaseValueConvertible {
    case text
    case image
    case audio
    case game
    case system
}

/// Stato di consegna mostrato nella bolla (orologio / antenna / doppia spunta).
enum DeliveryStatus: String, Codable, Sendable, DatabaseValueConvertible {
    /// Nessun peer a portata: in `relay_queue`, partira' da solo.
    case queued
    /// Consegnato ad almeno un peer BLE (in viaggio nella mesh).
    case sentMesh = "sent_mesh"
    /// Ricevuto `DELIVERY_ACK` dal destinatario.
    case deliveredAck = "delivered_ack"
    case read
}

struct MessageRecord: Codable, Hashable, Sendable, Identifiable, FetchableRecord, PersistableRecord {

    static let databaseTableName = "messages"

    var id: Data { msgID }

    var msgID: Data
    var conversationID: String
    var senderNodeID: Data
    var senderNickname: String?
    var contentType: MessageContentType
    var text: String?
    var mediaLocalPath: String?
    var blurhash: String?
    var audioWaveform: Data?
    var durationMs: Int?
    var replyToMsgID: Data?
    var gameState: Data?
    var isOutgoing: Bool
    var deliveryStatus: DeliveryStatus
    /// `hop_count` dell'envelope ricevuto: alimenta l'indicatore "Stato Radio".
    var hopCountReceived: Int?
    var createdAtMs: Int64

    enum CodingKeys: String, CodingKey {
        case msgID = "msg_id"
        case conversationID = "conversation_id"
        case senderNodeID = "sender_node_id"
        case senderNickname = "sender_nickname"
        case contentType = "content_type"
        case text
        case mediaLocalPath = "media_local_path"
        case blurhash
        case audioWaveform = "audio_waveform"
        case durationMs = "duration_ms"
        case replyToMsgID = "reply_to_msg_id"
        case gameState = "game_state"
        case isOutgoing = "is_outgoing"
        case deliveryStatus = "delivery_status"
        case hopCountReceived = "hop_count_received"
        case createdAtMs = "created_at_ms"
    }

    enum Columns {
        static let msgID = Column(CodingKeys.msgID)
        static let conversationID = Column(CodingKeys.conversationID)
        static let senderNodeID = Column(CodingKeys.senderNodeID)
        static let contentType = Column(CodingKeys.contentType)
        static let text = Column(CodingKeys.text)
        static let isOutgoing = Column(CodingKeys.isOutgoing)
        static let deliveryStatus = Column(CodingKeys.deliveryStatus)
        static let hopCountReceived = Column(CodingKeys.hopCountReceived)
        static let createdAtMs = Column(CodingKeys.createdAtMs)
    }

    init(msgID: Data,
         conversationID: String,
         senderNodeID: Data,
         senderNickname: String? = nil,
         contentType: MessageContentType,
         text: String? = nil,
         mediaLocalPath: String? = nil,
         blurhash: String? = nil,
         audioWaveform: Data? = nil,
         durationMs: Int? = nil,
         replyToMsgID: Data? = nil,
         gameState: Data? = nil,
         isOutgoing: Bool,
         deliveryStatus: DeliveryStatus,
         hopCountReceived: Int? = nil,
         createdAtMs: Int64 = Date.nowUnixMilliseconds) {
        self.msgID = msgID
        self.conversationID = conversationID
        self.senderNodeID = senderNodeID
        self.senderNickname = senderNickname
        self.contentType = contentType
        self.text = text
        self.mediaLocalPath = mediaLocalPath
        self.blurhash = blurhash
        self.audioWaveform = audioWaveform
        self.durationMs = durationMs
        self.replyToMsgID = replyToMsgID
        self.gameState = gameState
        self.isOutgoing = isOutgoing
        self.deliveryStatus = deliveryStatus
        self.hopCountReceived = hopCountReceived
        self.createdAtMs = createdAtMs
    }

    /// Messaggio testuale in uscita, accettato subito nella chat con stato `queued`.
    static func outgoingText(_ text: String, conversationID: String, sender: IdentityKeys, senderNickname: String? = nil,
                             replyTo: Data? = nil, msgID: Data = CryptoEngine.randomMessageID()) -> MessageRecord {
        MessageRecord(msgID: msgID,
                      conversationID: conversationID,
                      senderNodeID: sender.nodeID,
                      senderNickname: senderNickname,
                      contentType: .text,
                      text: text,
                      replyToMsgID: replyTo,
                      isOutgoing: true,
                      deliveryStatus: .queued)
    }

    /// Elimina i messaggi della Cabina Pubblica piu' vecchi di 12 ore.
    static func purgeExpiredLoungeMessages(_ db: Database, nowMs: Int64) throws {
        let cutoff = nowMs - Int64(MeshProtocol.loungeLifetime * 1_000)
        try MessageRecord
            .filter(Columns.conversationID == ConversationRecord.cabinLoungeID && Columns.createdAtMs < cutoff)
            .deleteAll(db)
    }
}
