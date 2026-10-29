import Foundation
import GRDB

/// Coda Store-and-Forward: max 200 pacchetti, max 2 MB totali, scadenza 6 ore.
/// Quando i limiti vengono superati si scartano prima i pacchetti piu' vecchi (FIFO).
struct RelayQueueStore: Sendable {

    struct Stats: Sendable, Equatable {
        let count: Int
        let totalBytes: Int
    }

    enum Failure: Error, Equatable {
        case packetTooLarge(Int)
    }

    let database: AppDatabase
    var maxCount: Int = MeshProtocol.relayQueueMaxCount
    var maxTotalBytes: Int = MeshProtocol.relayQueueMaxBytes

    init(database: AppDatabase,
         maxCount: Int = MeshProtocol.relayQueueMaxCount,
         maxTotalBytes: Int = MeshProtocol.relayQueueMaxBytes) {
        self.database = database
        self.maxCount = maxCount
        self.maxTotalBytes = maxTotalBytes
    }

    /// Accoda un envelope serializzato. Un `msg_id` gia' presente viene ignorato.
    func enqueue(_ record: RelayQueueRecord, now: Date = Date()) async throws {
        guard record.sizeBytes <= maxTotalBytes else { throw Failure.packetTooLarge(record.sizeBytes) }
        let nowMs = now.unixMilliseconds
        let maxCount = self.maxCount
        let maxTotalBytes = self.maxTotalBytes
        try await database.pool.write { db in
            // 1. Pulizia scaduti.
            try RelayQueueRecord.filter(RelayQueueRecord.Columns.expiresAtMs <= nowMs).deleteAll(db)
            // 2. Idempotenza.
            if try RelayQueueRecord.exists(db, key: record.msgID) { return }
            // 3. Eviction FIFO finche' il nuovo pacchetto non rientra nei limiti.
            var count = try RelayQueueRecord.fetchCount(db)
            var totalBytes = try Int.fetchOne(db, sql: "SELECT COALESCE(SUM(size_bytes), 0) FROM relay_queue") ?? 0
            while count > 0 && (count + 1 > maxCount || totalBytes + record.sizeBytes > maxTotalBytes) {
                guard let oldest = try RelayQueueRecord.order(RelayQueueRecord.Columns.enqueuedAtMs).fetchOne(db) else { break }
                try oldest.delete(db)
                count -= 1
                totalBytes -= oldest.sizeBytes
            }
            try record.insert(db)
        }
    }

    /// Pacchetti pronti per la ritrasmissione (FIFO), esclusi quelli scaduti.
    func pending(now: Date = Date(), limit: Int? = nil) async throws -> [RelayQueueRecord] {
        let nowMs = now.unixMilliseconds
        return try await database.pool.read { db in
            var request = RelayQueueRecord
                .filter(RelayQueueRecord.Columns.expiresAtMs > nowMs)
                .order(RelayQueueRecord.Columns.enqueuedAtMs)
            if let limit { request = request.limit(limit) }
            return try request.fetchAll(db)
        }
    }

    /// Pacchetti destinati a uno specifico `recipient_id` (peer appena tornato a portata).
    func pending(recipientID: Data, now: Date = Date()) async throws -> [RelayQueueRecord] {
        let nowMs = now.unixMilliseconds
        return try await database.pool.read { db in
            try RelayQueueRecord
                .filter(RelayQueueRecord.Columns.recipientID == recipientID && RelayQueueRecord.Columns.expiresAtMs > nowMs)
                .order(RelayQueueRecord.Columns.enqueuedAtMs)
                .fetchAll(db)
        }
    }

    func remove(msgID: Data) async throws {
        try await database.pool.write { db in
            _ = try RelayQueueRecord.deleteOne(db, key: msgID)
        }
    }

    func remove(msgIDs: [Data]) async throws {
        guard !msgIDs.isEmpty else { return }
        try await database.pool.write { db in
            _ = try RelayQueueRecord.filter(keys: msgIDs).deleteAll(db)
        }
    }

    @discardableResult
    func purgeExpired(now: Date = Date()) async throws -> Int {
        let nowMs = now.unixMilliseconds
        return try await database.pool.write { db in
            try RelayQueueRecord.filter(RelayQueueRecord.Columns.expiresAtMs <= nowMs).deleteAll(db)
        }
    }

    func stats() async throws -> Stats {
        try await database.pool.read { db in
            let count = try RelayQueueRecord.fetchCount(db)
            let bytes = try Int.fetchOne(db, sql: "SELECT COALESCE(SUM(size_bytes), 0) FROM relay_queue") ?? 0
            return Stats(count: count, totalBytes: bytes)
        }
    }
}
