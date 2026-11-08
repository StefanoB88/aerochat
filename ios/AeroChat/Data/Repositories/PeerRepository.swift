import Foundation
import GRDB

struct PeerRepository: Sendable {

    let database: AppDatabase

    /// Inserisce o aggiorna un peer. Se gia' noto conserva `is_trusted`/`is_blocked` esistenti
    /// (un gossip non deve mai declassare un compagno fidato) e il profilo ricevuto.
    /// - Parameter authoritativeNickname: `true` se il nickname arriva firmato dal peer stesso;
    ///   un nickname riferito da terzi (gossip, QR) non sovrascrive quello di un `PROFILE_UPDATE`.
    @discardableResult
    func upsert(_ peer: PeerRecord, authoritativeNickname: Bool = false) async throws -> PeerRecord {
        try await database.pool.write { db in
            if var existing = try PeerRecord.fetchOne(db, key: peer.nodeID) {
                let original = existing
                if authoritativeNickname || existing.profileUpdatedAtMs == nil, existing.nickname != peer.nickname {
                    existing.nickname = peer.nickname
                    try Self.syncDirectTitle(db, nodeID: peer.nodeID, nickname: peer.nickname)
                }
                existing.x25519Pub = peer.x25519Pub
                existing.ed25519Pub = peer.ed25519Pub
                existing.isTrusted = existing.isTrusted || peer.isTrusted
                // Solo le colonne cambiate (spesso nessuna): niente scrittura ne' refresh delle osservazioni.
                try existing.updateChanges(db, from: original)
                return existing
            }
            try peer.insert(db)
            return peer
        }
    }

    /// Applica un `PROFILE_UPDATE` se piu' recente di quello noto. Ritorna `true` se applicato.
    @discardableResult
    func applyProfile(nodeID: Data, nickname: String, avatarColorHex: String?, avatarImage: Data?,
                      updatedAtMs: Int64) async throws -> Bool {
        try await database.pool.write { db in
            guard var peer = try PeerRecord.fetchOne(db, key: nodeID),
                  updatedAtMs > (peer.profileUpdatedAtMs ?? 0) else { return false }
            if !nickname.isEmpty, nickname != peer.nickname {
                peer.nickname = nickname
                try Self.syncDirectTitle(db, nodeID: nodeID, nickname: nickname)
            }
            peer.avatarColorHex = avatarColorHex
            peer.avatarImage = avatarImage
            peer.profileUpdatedAtMs = updatedAtMs
            try peer.update(db)
            return true
        }
    }

    /// Il titolo della chat 1-a-1 segue il nickname del compagno.
    private static func syncDirectTitle(_ db: Database, nodeID: Data, nickname: String) throws {
        try ConversationRecord
            .filter(ConversationRecord.Columns.peerNodeID == nodeID && ConversationRecord.Columns.type == ConversationType.direct.rawValue)
            .updateAll(db, ConversationRecord.Columns.title.set(to: nickname))
    }

    func fetch(nodeID: Data) async throws -> PeerRecord? {
        try await database.pool.read { db in
            try PeerRecord.fetchOne(db, key: nodeID)
        }
    }

    /// Piu' peer in una sola lettura (stato radio dei gruppi: niente query per membro).
    func fetch(nodeIDs: [Data]) async throws -> [PeerRecord] {
        guard !nodeIDs.isEmpty else { return [] }
        return try await database.pool.read { db in
            try PeerRecord.fetchAll(db, keys: nodeIDs)
        }
    }

    func fetchAll() async throws -> [PeerRecord] {
        try await database.pool.read { db in
            try PeerRecord.order(PeerRecord.Columns.nickname.collating(.localizedCaseInsensitiveCompare)).fetchAll(db)
        }
    }

    /// Compagni di viaggio verso cui mantenere la connessione GATT persistente.
    func trustedPeers() async throws -> [PeerRecord] {
        try await database.pool.read { db in
            try PeerRecord.filter(PeerRecord.Columns.isTrusted == true && PeerRecord.Columns.isBlocked == false).fetchAll(db)
        }
    }

    /// Aggiorna telemetria radio del peer (chiamato dal router a ogni pacchetto ricevuto).
    func markSeen(nodeID: Data, hopCount: Int?, rssi: Int?, at date: Date = Date()) async throws {
        let seenAt = date.unixMilliseconds
        try await database.pool.write { db in
            var assignments: [ColumnAssignment] = [PeerRecord.Columns.lastSeenAtMs.set(to: seenAt)]
            if let hopCount { assignments.append(PeerRecord.Columns.lastHopCount.set(to: hopCount)) }
            if let rssi { assignments.append(PeerRecord.Columns.lastRSSI.set(to: rssi)) }
            try PeerRecord.filter(key: nodeID).updateAll(db, assignments)
        }
    }

    func setTrusted(_ trusted: Bool, nodeID: Data) async throws {
        _ = try await database.pool.write { db in
            try PeerRecord.filter(key: nodeID).updateAll(db, PeerRecord.Columns.isTrusted.set(to: trusted))
        }
    }

    /// Blocca/silenzia un utente (pulsante rapido della Cabina Pubblica).
    func setBlocked(_ blocked: Bool, nodeID: Data) async throws {
        _ = try await database.pool.write { db in
            try PeerRecord.filter(key: nodeID).updateAll(db, PeerRecord.Columns.isBlocked.set(to: blocked))
        }
    }

    /// Memorizza l'UUID CoreBluetooth con cui abbiamo raggiunto il peer (ruolo central).
    func setBLEIdentifier(_ identifier: UUID, nodeID: Data) async throws {
        _ = try await database.pool.write { db in
            try PeerRecord.filter(key: nodeID).updateAll(db, PeerRecord.Columns.bleIdentifier.set(to: identifier.uuidString))
        }
    }

    /// Identificatori CoreBluetooth dei compagni fidati da tenere in `connect(options: nil)` perenne.
    func trustedPeripheralIdentifiers() async throws -> Set<UUID> {
        try await database.pool.read { db in
            let rows = try String.fetchAll(db, PeerRecord
                .select(PeerRecord.Columns.bleIdentifier)
                .filter(PeerRecord.Columns.isTrusted == true
                        && PeerRecord.Columns.isBlocked == false
                        && PeerRecord.Columns.bleIdentifier != nil))
            return Set(rows.compactMap(UUID.init(uuidString:)))
        }
    }

    /// Contatti non bloccati a cui annunciare il nostro profilo.
    func unblockedPeers() async throws -> [PeerRecord] {
        try await database.pool.read { db in
            try PeerRecord.filter(PeerRecord.Columns.isBlocked == false).fetchAll(db)
        }
    }

    func blockedNodeIDs() async throws -> Set<Data> {
        try await database.pool.read { db in
            Set(try Data.fetchAll(db, PeerRecord.select(PeerRecord.Columns.nodeID).filter(PeerRecord.Columns.isBlocked == true)))
        }
    }

    /// Stream di un singolo peer (header della chat 1-a-1).
    func observe(nodeID: Data) -> AsyncStream<PeerRecord?> {
        database.pool.observeStream { db in
            try PeerRecord.fetchOne(db, key: nodeID)
        }
    }

    func observeAll() -> AsyncStream<[PeerRecord]> {
        database.pool.observeStream { db in
            try PeerRecord.order(PeerRecord.Columns.nickname.collating(.localizedCaseInsensitiveCompare)).fetchAll(db)
        }
    }
}
