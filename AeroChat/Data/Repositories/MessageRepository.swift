import Foundation
import GRDB

struct MessageRepository: Sendable {

    let database: AppDatabase

    /// Inserisce il messaggio e aggiorna `last_message_at_ms` / `unread_count` della conversazione.
    /// Idempotente: un `msg_id` gia' presente viene ignorato (ritorna `false`).
    @discardableResult
    func insert(_ message: MessageRecord) async throws -> Bool {
        try await database.pool.write { db in
            if try MessageRecord.exists(db, key: message.msgID) {
                return false
            }
            try message.insert(db)
            var assignments: [ColumnAssignment] = [
                ConversationRecord.Columns.lastMessageAtMs.set(to: message.createdAtMs),
            ]
            if !message.isOutgoing {
                assignments.append(ConversationRecord.Columns.unreadCount.set(to: ConversationRecord.Columns.unreadCount + 1))
            }
            try ConversationRecord.filter(key: message.conversationID).updateAll(db, assignments)
            return true
        }
    }

    func fetch(msgID: Data) async throws -> MessageRecord? {
        try await database.pool.read { db in
            try MessageRecord.fetchOne(db, key: msgID)
        }
    }

    /// Ultimi `limit` messaggi della conversazione in ordine cronologico.
    func fetch(conversationID: String, limit: Int = 200) async throws -> [MessageRecord] {
        try await database.pool.read { db in
            try MessageRecord
                .filter(MessageRecord.Columns.conversationID == conversationID)
                .order(MessageRecord.Columns.createdAtMs.desc)
                .limit(limit)
                .fetchAll(db)
                .reversed()
        }
    }

    func updateDeliveryStatus(_ status: DeliveryStatus, msgID: Data) async throws {
        _ = try await database.pool.write { db in
            try MessageRecord.filter(key: msgID).updateAll(db, MessageRecord.Columns.deliveryStatus.set(to: status.rawValue))
        }
    }

    /// Collega il file media ricostruito al messaggio placeholder (BlurHash/forma d'onda gia' mostrati).
    func updateMedia(localPath: String, msgID: Data) async throws {
        _ = try await database.pool.write { db in
            try MessageRecord.filter(key: msgID).updateAll(db, Column("media_local_path").set(to: localPath))
        }
    }

    /// Aggiorna lo stato serializzato di una partita (la bolla di gioco e' un unico messaggio che evolve).
    func updateGameState(_ state: Data, msgID: Data) async throws {
        _ = try await database.pool.write { db in
            try MessageRecord.filter(key: msgID).updateAll(db, Column("game_state").set(to: state))
        }
    }

    /// Promuove lo stato solo in avanti (`queued -> sent_mesh -> delivered_ack -> read`),
    /// cosi' un ACK in ritardo non riporta indietro un messaggio gia' letto.
    func advanceDeliveryStatus(to status: DeliveryStatus, msgID: Data) async throws {
        try await database.pool.write { db in
            guard var message = try MessageRecord.fetchOne(db, key: msgID) else { return }
            if status.rank > message.deliveryStatus.rank {
                message.deliveryStatus = status
                try message.update(db)
            }
        }
    }

    /// Messaggi in uscita ancora in coda (da ritrasmettere quando un peer torna a portata).
    func queuedOutgoing() async throws -> [MessageRecord] {
        try await database.pool.read { db in
            try MessageRecord
                .filter(MessageRecord.Columns.isOutgoing == true && MessageRecord.Columns.deliveryStatus == DeliveryStatus.queued.rawValue)
                .order(MessageRecord.Columns.createdAtMs)
                .fetchAll(db)
        }
    }

    /// Ricerca istantanea FTS5 (prefissi su ogni token, es. "tax" trova "taxi").
    func search(_ query: String, limit: Int = 50) async throws -> [MessageRecord] {
        guard let pattern = FTS5Pattern(matchingAllPrefixesIn: query) else { return [] }
        return try await database.pool.read { db in
            try MessageRecord.fetchAll(
                db,
                sql: """
                    SELECT messages.*
                    FROM messages
                    JOIN messages_fts ON messages_fts.rowid = messages.rowid
                    WHERE messages_fts MATCH ?
                    ORDER BY messages.created_at_ms DESC
                    LIMIT ?
                    """,
                arguments: [pattern, limit]
            )
        }
    }

    func delete(msgID: Data) async throws {
        try await database.pool.write { db in
            _ = try MessageRecord.deleteOne(db, key: msgID)
        }
    }

    /// Stream live dei messaggi di una conversazione (ordine cronologico).
    func observe(conversationID: String, limit: Int = 500) -> AsyncStream<[MessageRecord]> {
        database.pool.observeStream { db in
            try MessageRecord
                .filter(MessageRecord.Columns.conversationID == conversationID)
                .order(MessageRecord.Columns.createdAtMs.desc)
                .limit(limit)
                .fetchAll(db)
                .reversed()
        }
    }
}

extension DeliveryStatus {
    /// Ordine di avanzamento dello stato di consegna.
    var rank: Int {
        switch self {
        case .queued: return 0
        case .sentMesh: return 1
        case .deliveredAck: return 2
        case .read: return 3
        }
    }
}
