import Foundation
import GRDB

/// Token di abbinamento (`pt`) dei QR mostrati da questo iPhone: 16 byte casuali validi 7 giorni,
/// al massimo 16 (i piu' vecchi vengono scartati). Un token non viene consumato dal PAIR_HELLO:
/// lo stesso QR puo' essere scansionato da piu' persone finche' e' valido.
struct PairingTokenStore: Sendable {

    static let tokenLength = 16
    static let lifetimeMs: Int64 = 7 * 24 * 3_600 * 1_000
    static let maxTokens = 16

    let database: AppDatabase

    /// Genera e salva un nuovo token (uno per ogni presentazione del QR / link d'invito).
    func issue(now: Date = Date()) async throws -> Data {
        let token = Self.randomToken()
        try await save(token, createdAt: now)
        return token
    }

    /// Salva un token con la data di creazione indicata (anche per i test dei token scaduti).
    func save(_ token: Data, createdAt: Date) async throws {
        let createdAtMs = createdAt.unixMilliseconds
        let cutoff = createdAtMs - Self.lifetimeMs
        let maxTokens = Self.maxTokens
        try await database.pool.write { db in
            try db.execute(sql: "DELETE FROM pairing_tokens WHERE created_at_ms <= ?", arguments: [cutoff])
            try db.execute(sql: "INSERT OR REPLACE INTO pairing_tokens (token, created_at_ms) VALUES (?, ?)",
                           arguments: [token, createdAtMs])
            // Solo i 16 piu' recenti restano validi.
            try db.execute(sql: """
                DELETE FROM pairing_tokens WHERE token NOT IN (
                    SELECT token FROM pairing_tokens ORDER BY created_at_ms DESC LIMIT ?)
                """, arguments: [maxTokens])
        }
    }

    /// Token ancora validi, dal piu' recente.
    func validTokens(now: Date = Date()) async throws -> [Data] {
        let cutoff = now.unixMilliseconds - Self.lifetimeMs
        let maxTokens = Self.maxTokens
        return try await database.pool.read { db in
            try Data.fetchAll(db, sql: """
                SELECT token FROM pairing_tokens WHERE created_at_ms > ? ORDER BY created_at_ms DESC LIMIT ?
                """, arguments: [cutoff, maxTokens])
        }
    }

    static func randomToken() -> Data {
        var bytes = [UInt8](repeating: 0, count: tokenLength)
        for index in bytes.indices { bytes[index] = UInt8.random(in: .min ... .max) }
        return Data(bytes)
    }
}
