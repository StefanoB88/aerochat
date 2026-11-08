import Foundation
import GRDB

struct PeerRepository: Sendable {

    let database: AppDatabase

    /// Inserisce o aggiorna un peer. Se gia' noto conserva `is_trusted`/`is_blocked` esistenti
    /// (un gossip non deve mai declassare un compagno fidato).
    @discardableResult
    func upsert(_ peer: PeerRecord) async throws -> PeerRecord {
        try await database.pool.write { db in
            if var existing = try PeerRecord.fetchOne(db, key: peer.nodeID) {
                existing.nickname = peer.nickname
                existing.x25519Pub = peer.x25519Pub
                existing.ed25519Pub = peer.ed25519Pub
                existing.isTrusted = existing.isTrusted || peer.isTrusted
                try existing.update(db)
                return existing
            }
            try peer.insert(db)
            return peer
        }
    }

    func fetch(nodeID: Data) async throws -> PeerRecord? {
        try await database.pool.read { db in
            try PeerRecord.fetchOne(db, key: nodeID)
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

    func blockedNodeIDs() async throws -> Set<Data> {
        try await database.pool.read { db in
            Set(try Data.fetchAll(db, PeerRecord.select(PeerRecord.Columns.nodeID).filter(PeerRecord.Columns.isBlocked == true)))
        }
    }

    func observeAll() -> AsyncStream<[PeerRecord]> {
        database.pool.observeStream { db in
            try PeerRecord.order(PeerRecord.Columns.nickname.collating(.localizedCaseInsensitiveCompare)).fetchAll(db)
        }
    }
}
