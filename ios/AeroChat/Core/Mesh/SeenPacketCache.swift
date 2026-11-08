import Foundation

/// Cache LRU in memoria dei `msg_id` gia' visti: il fast-path (< 1 ms) del managed flooding.
/// La persistenza su `seen_packets` serve solo a sopravvivere ai riavvii.
struct SeenPacketCache: Sendable {

    private struct Entry: Sendable {
        let msgID: Data
        let expiresAtMs: Int64
    }

    let capacity: Int
    let lifetimeMs: Int64

    private var expiry: [Data: Int64] = [:]
    /// Ordine di inserimento (ring compattato periodicamente): per l'eviction FIFO.
    private var order: [Entry] = []
    private var head = 0

    init(capacity: Int = 4_096, lifetime: TimeInterval = MeshProtocol.seenPacketLifetime) {
        self.capacity = max(16, capacity)
        self.lifetimeMs = Int64(lifetime * 1_000)
    }

    var count: Int { expiry.count }

    /// Registra `msgID`. Ritorna `true` se nuovo (da processare), `false` se gia' visto (da scartare).
    mutating func insertIfNew(_ msgID: Data, nowMs: Int64 = Date.nowUnixMilliseconds) -> Bool {
        if let existing = expiry[msgID], existing > nowMs {
            return false
        }
        let expiresAt = nowMs + lifetimeMs
        expiry[msgID] = expiresAt
        order.append(Entry(msgID: msgID, expiresAtMs: expiresAt))
        evictIfNeeded()
        return true
    }

    func contains(_ msgID: Data, nowMs: Int64 = Date.nowUnixMilliseconds) -> Bool {
        guard let existing = expiry[msgID] else { return false }
        return existing > nowMs
    }

    /// Pre-carica gli id persistiti (al riavvio dell'app).
    mutating func preload(_ msgIDs: [Data], expiresAtMs: Int64) {
        for msgID in msgIDs where expiry[msgID] == nil {
            expiry[msgID] = expiresAtMs
            order.append(Entry(msgID: msgID, expiresAtMs: expiresAtMs))
        }
        evictIfNeeded()
    }

    /// Rimuove le voci scadute (job periodico).
    mutating func purgeExpired(nowMs: Int64 = Date.nowUnixMilliseconds) {
        expiry = expiry.filter { $0.value > nowMs }
        order.removeAll { $0.expiresAtMs <= nowMs }
        head = 0
    }

    private mutating func evictIfNeeded() {
        while expiry.count > capacity, head < order.count {
            let oldest = order[head]
            head += 1
            // Rimuove solo se la voce in mappa e' ancora quella inserita allora (non una piu' recente).
            if expiry[oldest.msgID] == oldest.expiresAtMs {
                expiry.removeValue(forKey: oldest.msgID)
            }
        }
        if head > 1_024 {
            order.removeFirst(head)
            head = 0
        }
    }
}
