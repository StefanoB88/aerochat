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
    /// Ritorna `false` se il messaggio non c'e' piu' o e' stato cancellato per tutti nel frattempo
    /// (il chiamante rimuove il file appena scritto).
    @discardableResult
    func updateMedia(localPath: String, msgID: Data) async throws -> Bool {
        try await database.pool.write { db in
            try MessageRecord
                .filter(key: msgID)
                .filter(MessageRecord.Columns.isDeleted == false)
                .updateAll(db, Column("media_local_path").set(to: localPath)) > 0
        }
    }

    // MARK: Modifica / cancellazione per tutti (MESSAGE_REVISION)

    /// EDIT: sostituisce il testo di un messaggio testuale non cancellato. Tra piu' modifiche vince quella
    /// con `editedAtMs` maggiore (una piu' vecchia arrivata dopo viene ignorata). Ritorna `true` se applicata.
    @discardableResult
    func applyEdit(msgID: Data, text: String, editedAtMs: Int64) async throws -> Bool {
        try await database.pool.write { db in
            guard var message = try MessageRecord.fetchOne(db, key: msgID),
                  message.contentType == .text, !message.isDeleted else { return false }
            if let previous = message.editedAtMs, previous >= editedAtMs { return false }
            message.text = text
            message.editedAtMs = editedAtMs
            try message.update(db)
            return true
        }
    }

    /// DELETE (definitivo): azzera testo e media del messaggio e lo segna cancellato. Un messaggio ricevuto
    /// e non ancora letto smette di contare nei non letti. Ritorna se e' stato applicato e il path del media
    /// da rimuovere dal disco (lo fa il chiamante tramite `MediaStore`).
    @discardableResult
    func applyDeleteForEveryone(msgID: Data) async throws -> (deleted: Bool, mediaPath: String?) {
        try await database.pool.write { db in
            guard var message = try MessageRecord.fetchOne(db, key: msgID),
                  !message.isDeleted, message.isRevisableContent else { return (false, nil) }
            let mediaPath = message.mediaLocalPath
            let wasUnread = !message.isOutgoing && message.deliveryStatus != .read
            message.isDeleted = true
            message.text = nil
            message.mediaLocalPath = nil
            message.blurhash = nil
            message.audioWaveform = nil
            if wasUnread {
                message.deliveryStatus = .read
            }
            try message.update(db)
            if wasUnread {
                try db.execute(sql: "UPDATE conversations SET unread_count = unread_count - 1 WHERE id = ? AND unread_count > 0",
                               arguments: [message.conversationID])
            }
            return (true, mediaPath)
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

    /// Segna come letti i messaggi ricevuti della conversazione (azzera anche il badge) e li
    /// restituisce, nella stessa transazione, per inviare le conferme di lettura ai mittenti.
    func markIncomingRead(conversationID: String) async throws -> [MessageRecord] {
        try await database.pool.write { db in
            let unread = try MessageRecord
                .filter(MessageRecord.Columns.conversationID == conversationID
                        && MessageRecord.Columns.isOutgoing == false
                        && MessageRecord.Columns.deliveryStatus != DeliveryStatus.read.rawValue)
                .fetchAll(db)
            if !unread.isEmpty {
                try MessageRecord
                    .filter(keys: unread.map(\.msgID))
                    .updateAll(db, MessageRecord.Columns.deliveryStatus.set(to: DeliveryStatus.read.rawValue))
            }
            try ConversationRecord
                .filter(key: conversationID)
                .filter(ConversationRecord.Columns.unreadCount != 0)
                .updateAll(db, ConversationRecord.Columns.unreadCount.set(to: 0))
            return unread
        }
    }

    /// `READ_RECEIPT`: registra che `readerNodeID` ha letto i nostri messaggi e ne fa avanzare lo stato.
    /// - Chat 1-a-1: solo il compagno della chat puo' confermare; il messaggio passa a `read`.
    /// - Gruppo: il lettore viene salvato in `message_reads`; il messaggio passa a `read` solo quando i
    ///   lettori coprono tutti i membri del gruppo noti in quel momento (mittente escluso), altrimenti
    ///   almeno a `delivered_ack` (chi ha letto l'ha ricevuto).
    /// Ritorna i `msg_id` il cui stato e' avanzato. Lo stato non torna mai indietro.
    func markReadByRecipient(msgIDs: [Data], readerNodeID: Data, now: Date = Date()) async throws -> [Data] {
        try await database.pool.write { db in
            var updated: [Data] = []
            for msgID in msgIDs {
                guard var message = try MessageRecord.fetchOne(db, key: msgID),
                      message.isOutgoing,
                      let conversation = try ConversationRecord.fetchOne(db, key: message.conversationID) else { continue }
                let target: DeliveryStatus
                switch conversation.type {
                case .direct:
                    guard conversation.peerNodeID == readerNodeID else { continue }
                    target = .read
                case .group:
                    guard readerNodeID != message.senderNodeID else { continue }
                    try db.execute(sql: "INSERT OR IGNORE INTO message_reads (msg_id, reader_node_id, read_at_ms) VALUES (?, ?, ?)",
                                   arguments: [msgID, readerNodeID, now.unixMilliseconds])
                    target = try Self.allGroupMembersRead(db, message: message) ? .read : .deliveredAck
                case .cabinLounge:
                    continue
                }
                guard target.rank > message.deliveryStatus.rank else { continue }
                message.deliveryStatus = target
                try message.update(db)
                updated.append(msgID)
            }
            return updated
        }
    }

    /// `true` se tutti i membri del gruppo noti ora (escluso il mittente) compaiono tra i lettori del messaggio.
    private static func allGroupMembersRead(_ db: Database, message: MessageRecord) throws -> Bool {
        let unread = try Int.fetchOne(db, sql: """
            SELECT COUNT(*) FROM group_members
            WHERE conversation_id = ? AND node_id != ?
              AND node_id NOT IN (SELECT reader_node_id FROM message_reads WHERE msg_id = ?)
            """, arguments: [message.conversationID, message.senderNodeID, message.msgID]) ?? 0
        return unread == 0
    }

    /// `node_id` dei membri che hanno confermato la lettura di un nostro messaggio di gruppo.
    func readerNodeIDs(msgID: Data) async throws -> [Data] {
        try await database.pool.read { db in
            try Data.fetchAll(db, sql: "SELECT reader_node_id FROM message_reads WHERE msg_id = ? ORDER BY read_at_ms",
                              arguments: [msgID])
        }
    }

    /// Tra `msgIDs`, i nostri messaggi in uscita non ancora confermati dal destinatario (una sola query).
    func awaitingAckIDs(among msgIDs: [Data]) async throws -> Set<Data> {
        guard !msgIDs.isEmpty else { return [] }
        return try await database.pool.read { db in
            Set(try Data.fetchAll(db, MessageRecord
                .select(MessageRecord.Columns.msgID)
                .filter(keys: msgIDs)
                .filter(MessageRecord.Columns.isOutgoing == true)
                .filter([DeliveryStatus.queued.rawValue, DeliveryStatus.sentMesh.rawValue].contains(MessageRecord.Columns.deliveryStatus))))
        }
    }

    /// `queued -> sent_mesh` per piu' messaggi in una transazione (gli stati successivi restano invariati).
    func markSentMesh(msgIDs: [Data]) async throws {
        guard !msgIDs.isEmpty else { return }
        _ = try await database.pool.write { db in
            try MessageRecord
                .filter(keys: msgIDs)
                .filter(MessageRecord.Columns.deliveryStatus == DeliveryStatus.queued.rawValue)
                .updateAll(db, MessageRecord.Columns.deliveryStatus.set(to: DeliveryStatus.sentMesh.rawValue))
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

    /// Tutti i messaggi dal momento indicato (statistiche della cartolina di volo), piu' recenti per primi.
    func fetch(since: Date, limit: Int = 5_000) async throws -> [MessageRecord] {
        try await database.pool.read { db in
            try MessageRecord
                .filter(MessageRecord.Columns.createdAtMs >= since.unixMilliseconds)
                .order(MessageRecord.Columns.createdAtMs.desc)
                .limit(limit)
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

    /// Path dei media su disco di una conversazione (da rimuovere quando la chat viene eliminata).
    func mediaPaths(conversationID: String) async throws -> [String] {
        try await database.pool.read { db in
            try String.fetchAll(db, MessageRecord
                .select(Column("media_local_path"))
                .filter(MessageRecord.Columns.conversationID == conversationID && Column("media_local_path") != nil))
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
