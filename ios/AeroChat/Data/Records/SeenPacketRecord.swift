import Foundation
import GRDB

/// Backing persistente della cache anti-loop del managed flooding.
struct SeenPacketRecord: Codable, Hashable, Sendable, FetchableRecord, PersistableRecord {

    static let databaseTableName = "seen_packets"

    var msgID: Data
    var expiresAtMs: Int64

    enum CodingKeys: String, CodingKey {
        case msgID = "msg_id"
        case expiresAtMs = "expires_at_ms"
    }

    enum Columns {
        static let msgID = Column(CodingKeys.msgID)
        static let expiresAtMs = Column(CodingKeys.expiresAtMs)
    }
}

/// Pacchetto cifrato in attesa di un peer (Delay-Tolerant Networking).
struct RelayQueueRecord: Codable, Hashable, Sendable, FetchableRecord, PersistableRecord {

    static let databaseTableName = "relay_queue"

    var msgID: Data
    var recipientID: Data
    var rawEnvelopeBytes: Data
    var sizeBytes: Int
    var enqueuedAtMs: Int64
    var expiresAtMs: Int64

    enum CodingKeys: String, CodingKey {
        case msgID = "msg_id"
        case recipientID = "recipient_id"
        case rawEnvelopeBytes = "raw_envelope_bytes"
        case sizeBytes = "size_bytes"
        case enqueuedAtMs = "enqueued_at_ms"
        case expiresAtMs = "expires_at_ms"
    }

    enum Columns {
        static let msgID = Column(CodingKeys.msgID)
        static let recipientID = Column(CodingKeys.recipientID)
        static let sizeBytes = Column(CodingKeys.sizeBytes)
        static let enqueuedAtMs = Column(CodingKeys.enqueuedAtMs)
        static let expiresAtMs = Column(CodingKeys.expiresAtMs)
    }

    init(msgID: Data, recipientID: Data, rawEnvelopeBytes: Data, enqueuedAt: Date = Date(),
         lifetime: TimeInterval = MeshProtocol.relayQueueLifetime) {
        self.msgID = msgID
        self.recipientID = recipientID
        self.rawEnvelopeBytes = rawEnvelopeBytes
        self.sizeBytes = rawEnvelopeBytes.count
        self.enqueuedAtMs = enqueuedAt.unixMilliseconds
        self.expiresAtMs = enqueuedAt.addingTimeInterval(lifetime).unixMilliseconds
    }
}
