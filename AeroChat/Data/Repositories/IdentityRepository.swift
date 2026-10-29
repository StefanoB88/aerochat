import Foundation
import GRDB

struct IdentityRepository: Sendable {

    let database: AppDatabase

    func fetch() async throws -> IdentityRecord? {
        try await database.pool.read { db in
            try IdentityRecord.fetchOne(db, key: IdentityRecord.singletonID)
        }
    }

    func save(_ record: IdentityRecord) async throws {
        var record = record
        record.id = IdentityRecord.singletonID
        try await database.pool.write { [record] db in
            try record.save(db)
        }
    }

    func updateNickname(_ nickname: String) async throws {
        _ = try await database.pool.write { db in
            try IdentityRecord
                .filter(key: IdentityRecord.singletonID)
                .updateAll(db, IdentityRecord.Columns.nickname.set(to: nickname))
        }
    }

    /// Aggiorna lo stato Pro cache-ato (chiamato da `EntitlementManager`).
    func updateProStatus(tier: ProTier, validUntilMs: Int64?, receiptJWS: String?) async throws {
        try await database.pool.write { db in
            guard var record = try IdentityRecord.fetchOne(db, key: IdentityRecord.singletonID) else { return }
            record.proTier = tier
            record.proValidUntilMs = validUntilMs
            record.proReceiptJWS = receiptJWS
            try record.update(db)
        }
    }

    /// Stream dell'identita' per la UI (nickname, stato Pro).
    func observe() -> AsyncStream<IdentityRecord?> {
        database.pool.observeStream { db in
            try IdentityRecord.fetchOne(db, key: IdentityRecord.singletonID)
        }
    }
}

// MARK: - ValueObservation -> AsyncStream

extension DatabasePool {

    /// Espone una `ValueObservation` come `AsyncStream`: il primo valore arriva subito,
    /// poi uno per ogni commit che tocca le tabelle lette (deduplicato se `Equatable`).
    func observeStream<Value: Sendable & Equatable>(
        _ fetch: @escaping @Sendable (Database) throws -> Value
    ) -> AsyncStream<Value> {
        AsyncStream { continuation in
            let task = Task {
                do {
                    let observation = ValueObservation.tracking(fetch).removeDuplicates()
                    for try await value in observation.values(in: self) {
                        continuation.yield(value)
                    }
                } catch {
                    // Un errore di lettura termina lo stream; la UI mostrera' l'ultimo stato valido.
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }
}
