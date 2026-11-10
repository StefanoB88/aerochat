import Foundation
import GRDB

struct ConversationRepository: Sendable {

    let database: AppDatabase

    func fetch(id: String) async throws -> ConversationRecord? {
        try await database.pool.read { db in
            try ConversationRecord.fetchOne(db, key: id)
        }
    }

    /// Tutte le conversazioni permanenti (Tab 1): prima le fissate, poi per ultimo messaggio.
    func fetchPermanent() async throws -> [ConversationRecord] {
        try await database.pool.read { db in
            try ConversationRecord
                .filter(ConversationRecord.Columns.type != ConversationType.cabinLounge.rawValue)
                .order(ConversationRecord.Columns.isPinned.desc, ConversationRecord.Columns.lastMessageAtMs.desc, ConversationRecord.Columns.title)
                .fetchAll(db)
        }
    }

    /// Lookup O(1) usato dal router: `recipient_id` dell'envelope -> conversazione locale.
    func fetch(recipientID: Data) async throws -> ConversationRecord? {
        try await database.pool.read { db in
            try ConversationRecord.filter(ConversationRecord.Columns.recipientID == recipientID).fetchOne(db)
        }
    }

    /// Restituisce (creandola se serve) la chat 1-a-1 con il peer.
    @discardableResult
    func ensureDirectConversation(with peer: PeerRecord) async throws -> ConversationRecord {
        try await database.pool.write { db in
            let candidate = ConversationRecord.direct(with: peer)
            if let existing = try ConversationRecord.fetchOne(db, key: candidate.id) {
                return existing
            }
            try candidate.insert(db)
            return candidate
        }
    }

    /// Crea un gruppo di viaggio locale con chiave simmetrica nuova (o importata da un QR).
    @discardableResult
    func createGroup(id groupID: UUID = UUID(), title: String, secretKey: Data = CryptoEngine.randomGroupSecretKey(),
                     members: [PeerRecord] = []) async throws -> ConversationRecord {
        try await database.pool.write { db in
            let record = ConversationRecord.group(id: groupID, title: title, secretKey: secretKey)
            if let existing = try ConversationRecord.fetchOne(db, key: record.id) {
                return existing
            }
            try record.insert(db)
            for member in members {
                try GroupMemberRecord(conversationID: record.id, peer: member).save(db)
            }
            return record
        }
    }

    /// Entra (o rientra) nella Cabina del volo della carta d'imbarco; idempotente.
    @discardableResult
    func joinFlightLounge(_ pass: BoardingPass) async throws -> ConversationRecord {
        try await database.pool.write { db in
            let record = ConversationRecord.flightLounge(pass)
            if var existing = try ConversationRecord.fetchOne(db, key: record.id) {
                // Gruppo arrivato prima via gossip (senza codice volo): ora sappiamo che e' la Cabina del volo.
                if existing.flightCode == nil {
                    existing.flightCode = record.flightCode
                    existing.title = record.title
                    existing.expiresAtMs = record.expiresAtMs
                    try existing.update(db)
                }
                return existing
            }
            try record.insert(db)
            return record
        }
    }

    /// Non letti totali delle chat permanenti (badge dell'icona, del tab "Chat & Gruppi" e della Live
    /// Activity). La Cabina Pubblica e' esclusa: e' una bacheca senza notifiche ne' contatore in lista,
    /// e il suo `unread_count` non viene mai azzerato.
    func totalUnread() async throws -> Int {
        try await database.pool.read { db in
            try Int.fetchOne(db, sql: "SELECT COALESCE(SUM(unread_count), 0) FROM conversations WHERE type != ?",
                             arguments: [ConversationType.cabinLounge.rawValue]) ?? 0
        }
    }

    /// Cabine del volo attive (per la cartolina e il badge "a bordo").
    func activeFlightLounges(now: Date = Date()) async throws -> [ConversationRecord] {
        try await database.pool.read { db in
            try ConversationRecord
                .filter(ConversationRecord.Columns.flightCode != nil)
                .filter(ConversationRecord.Columns.expiresAtMs == nil || ConversationRecord.Columns.expiresAtMs > now.unixMilliseconds)
                .order(ConversationRecord.Columns.createdAtMs.desc)
                .fetchAll(db)
        }
    }

    func addGroupMember(_ peer: PeerRecord, to conversationID: String) async throws {
        try await database.pool.write { db in
            try GroupMemberRecord(conversationID: conversationID, peer: peer).save(db)
        }
    }

    func groupMembers(of conversationID: String) async throws -> [GroupMemberRecord] {
        try await database.pool.read { db in
            try GroupMemberRecord.filter(GroupMemberRecord.Columns.conversationID == conversationID).fetchAll(db)
        }
    }

    /// Gruppi di cui fa parte il peer: usato per il filtro relay "ponte membro dello stesso gruppo".
    func groupIDs(containing nodeID: Data) async throws -> Set<String> {
        try await database.pool.read { db in
            Set(try String.fetchAll(db, GroupMemberRecord
                .select(GroupMemberRecord.Columns.conversationID)
                .filter(GroupMemberRecord.Columns.nodeID == nodeID)))
        }
    }

    /// Cabina Pubblica valida in questo istante: se quella esistente e' scaduta viene
    /// eliminata (con i suoi messaggi) e ricreata per altre 12 ore.
    @discardableResult
    func currentCabinLounge(now: Date = Date()) async throws -> ConversationRecord {
        // Fast path in sola lettura: il router la chiama per ogni pacchetto della Lounge e una transazione
        // di scrittura a pacchetto si accodava alle scritture vere (messaggi, seen_packets).
        if let existing = try await database.pool.read({ db in try ConversationRecord.fetchOne(db, key: ConversationRecord.cabinLoungeID) }),
           !existing.isExpired(now: now) {
            return existing
        }
        return try await database.pool.write { db in
            if let existing = try ConversationRecord.fetchOne(db, key: ConversationRecord.cabinLoungeID) {
                if !existing.isExpired(now: now) {
                    return existing
                }
                try existing.delete(db)
            }
            let lounge = ConversationRecord.cabinLounge(createdAt: now)
            try lounge.insert(db)
            return lounge
        }
    }

    /// Applica un Flight Group Pass alla conversazione (da acquisto locale o `PRO_PASS_GRANT`).
    func setProUnlocked(until validUntilMs: Int64, conversationID: String) async throws {
        _ = try await database.pool.write { db in
            try ConversationRecord
                .filter(key: conversationID)
                .updateAll(db, ConversationRecord.Columns.proUnlockedUntilMs.set(to: validUntilMs))
        }
    }

    func markRead(conversationID: String) async throws {
        try await database.pool.write { db in
            try ConversationRecord
                .filter(key: conversationID)
                .updateAll(db, ConversationRecord.Columns.unreadCount.set(to: 0))
            try MessageRecord
                .filter(MessageRecord.Columns.conversationID == conversationID
                        && MessageRecord.Columns.isOutgoing == false
                        && MessageRecord.Columns.deliveryStatus != DeliveryStatus.read.rawValue)
                .updateAll(db, MessageRecord.Columns.deliveryStatus.set(to: DeliveryStatus.read.rawValue))
        }
    }

    /// Fissa / sblocca una chat in cima alla lista.
    func setPinned(_ pinned: Bool, conversationID: String) async throws {
        _ = try await database.pool.write { db in
            try ConversationRecord
                .filter(key: conversationID)
                .updateAll(db, ConversationRecord.Columns.isPinned.set(to: pinned))
        }
    }

    func updateTitle(_ title: String, conversationID: String) async throws {
        _ = try await database.pool.write { db in
            try ConversationRecord
                .filter(key: conversationID)
                .updateAll(db, ConversationRecord.Columns.title.set(to: title))
        }
    }

    func delete(conversationID: String) async throws {
        try await database.pool.write { db in
            _ = try ConversationRecord.deleteOne(db, key: conversationID)
        }
    }

    /// Stream di una singola conversazione (sblocco Pro, titolo, unread).
    func observe(id: String) -> AsyncStream<ConversationRecord?> {
        database.pool.observeStream { db in
            try ConversationRecord.fetchOne(db, key: id)
        }
    }

    /// Stream dei membri di un gruppo (scheda info gruppo): si aggiorna con QR e gossip.
    func observeGroupMembers(of conversationID: String) -> AsyncStream<[GroupMemberRecord]> {
        database.pool.observeStream { db in
            try GroupMemberRecord.filter(GroupMemberRecord.Columns.conversationID == conversationID).fetchAll(db)
        }
    }

    func observePermanent() -> AsyncStream<[ConversationRecord]> {
        database.pool.observeStream { db in
            try ConversationRecord
                .filter(ConversationRecord.Columns.type != ConversationType.cabinLounge.rawValue)
                .order(ConversationRecord.Columns.isPinned.desc, ConversationRecord.Columns.lastMessageAtMs.desc, ConversationRecord.Columns.title)
                .fetchAll(db)
        }
    }
}
