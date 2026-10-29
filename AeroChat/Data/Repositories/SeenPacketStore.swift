import Foundation
import GRDB

/// Persistenza della tabella anti-loop `seen_packets`.
/// Il `MeshRouterActor` (Fase 3) la affianca con una cache LRU in memoria per il fast-path < 1 ms;
/// questa tabella garantisce la deduplicazione anche dopo un riavvio dell'app.
struct SeenPacketStore: Sendable {

    let database: AppDatabase

    /// Registra `msgID`. Restituisce `true` se era nuovo, `false` se gia' visto (da scartare).
    func markSeenIfNew(msgID: Data, now: Date = Date(), lifetime: TimeInterval = MeshProtocol.seenPacketLifetime) async throws -> Bool {
        let expiresAt = now.addingTimeInterval(lifetime).unixMilliseconds
        return try await database.pool.write { db in
            try db.execute(
                sql: "INSERT OR IGNORE INTO seen_packets (msg_id, expires_at_ms) VALUES (?, ?)",
                arguments: [msgID, expiresAt]
            )
            return db.changesCount == 1
        }
    }

    func contains(msgID: Data) async throws -> Bool {
        try await database.pool.read { db in
            try SeenPacketRecord.exists(db, key: msgID)
        }
    }

    /// Tutti gli id non scaduti (per pre-caricare la cache LRU all'avvio).
    func activeMessageIDs(now: Date = Date()) async throws -> [Data] {
        let nowMs = now.unixMilliseconds
        return try await database.pool.read { db in
            try Data.fetchAll(db, SeenPacketRecord
                .select(SeenPacketRecord.Columns.msgID)
                .filter(SeenPacketRecord.Columns.expiresAtMs > nowMs))
        }
    }

    @discardableResult
    func purgeExpired(now: Date = Date()) async throws -> Int {
        let nowMs = now.unixMilliseconds
        return try await database.pool.write { db in
            try SeenPacketRecord.filter(SeenPacketRecord.Columns.expiresAtMs <= nowMs).deleteAll(db)
        }
    }

    func count() async throws -> Int {
        try await database.pool.read { db in
            try SeenPacketRecord.fetchCount(db)
        }
    }
}
