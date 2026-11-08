package com.example.aerochat.core.mesh

import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.nowUnixMilliseconds
import com.example.aerochat.core.protocol.toHex

/**
 * Cache LRU in memoria dei `msg_id` gia' visti: il fast-path (< 1 ms) del managed flooding.
 * La persistenza su `seen_packets` serve solo a sopravvivere ai riavvii.
 *
 * Non thread-safe (struct `mutating` su iOS): va usata da un solo contesto (il router).
 * @param lifetime durata di una voce in ms.
 */
class SeenPacketCache(
    capacity: Int = 4_096,
    lifetime: Long = MeshProtocol.seenPacketLifetime,
) {

    private class Entry(val msgID: String, val expiresAtMs: Long)

    val capacity: Int = maxOf(16, capacity)
    val lifetimeMs: Long = lifetime

    /** Chiave: `msg_id` esadecimale. */
    private val expiry = HashMap<String, Long>()
    /** Ordine di inserimento (ring compattato periodicamente): per l'eviction FIFO. */
    private val order = ArrayList<Entry>()
    private var head = 0

    val count: Int get() = expiry.size

    /** Registra `msgID`. Ritorna `true` se nuovo (da processare), `false` se gia' visto (da scartare). */
    fun insertIfNew(msgID: ByteArray, nowMs: Long = nowUnixMilliseconds): Boolean {
        val key = msgID.toHex()
        val existing = expiry[key]
        if (existing != null && existing > nowMs) return false
        val expiresAt = nowMs + lifetimeMs
        expiry[key] = expiresAt
        order.add(Entry(key, expiresAt))
        evictIfNeeded()
        return true
    }

    fun contains(msgID: ByteArray, nowMs: Long = nowUnixMilliseconds): Boolean {
        val existing = expiry[msgID.toHex()] ?: return false
        return existing > nowMs
    }

    /** Pre-carica gli id persistiti (al riavvio dell'app). */
    fun preload(msgIDs: List<ByteArray>, expiresAtMs: Long) {
        for (msgID in msgIDs) {
            val key = msgID.toHex()
            if (expiry.containsKey(key)) continue
            expiry[key] = expiresAtMs
            order.add(Entry(key, expiresAtMs))
        }
        evictIfNeeded()
    }

    /** Rimuove le voci scadute (job periodico). */
    fun purgeExpired(nowMs: Long = nowUnixMilliseconds) {
        expiry.entries.removeAll { it.value <= nowMs }
        order.removeAll { it.expiresAtMs <= nowMs }
        head = 0
    }

    private fun evictIfNeeded() {
        while (expiry.size > capacity && head < order.size) {
            val oldest = order[head]
            head += 1
            // Rimuove solo se la voce in mappa e' ancora quella inserita allora (non una piu' recente).
            if (expiry[oldest.msgID] == oldest.expiresAtMs) {
                expiry.remove(oldest.msgID)
            }
        }
        if (head > 1_024) {
            order.subList(0, head).clear()
            head = 0
        }
    }
}
