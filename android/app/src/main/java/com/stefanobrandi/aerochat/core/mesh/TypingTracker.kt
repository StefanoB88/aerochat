package com.stefanobrandi.aerochat.core.mesh

import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Un compagno che sta scrivendo in una conversazione. */
data class TypingPeer(val nodeHex: String, val nickname: String, val expiresAtMs: Long)

/**
 * Stato in memoria del "sta scrivendo" ricevuto (`TYPING`), per conversazione + mittente. Mai su DB.
 * - `true` lo (ri)accende per [expiry] ms (6 s dall'ultimo `true`);
 * - `false` o un messaggio in arrivo dallo stesso mittente lo azzera subito.
 *
 * Thread-safe: le mutazioni sono sincronizzate, la lettura passa da [state] (snapshot immutabile).
 * [clock] in epoch ms (sostituibile nei test).
 */
class TypingTracker(
    private val scope: CoroutineScope,
    private val expiry: Long = MeshProtocol.typingExpiry,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, LinkedHashMap<String, TypingPeer>>()
    private var expiryJob: Job? = null

    private val _state = MutableStateFlow<Map<String, List<TypingPeer>>>(emptyMap())

    /** Per `conversation_id`: chi sta scrivendo (in ordine di inizio). Solo voci non scadute. */
    val state: StateFlow<Map<String, List<TypingPeer>>> = _state.asStateFlow()

    /** Chi sta scrivendo ora in [conversationID]. */
    fun typing(conversationID: String): List<TypingPeer> = _state.value[conversationID].orEmpty()

    fun update(conversationID: String, senderHex: String, nickname: String, isTyping: Boolean) {
        synchronized(lock) {
            if (isTyping) {
                val peers = entries.getOrPut(conversationID) { LinkedHashMap() }
                peers[senderHex] = TypingPeer(senderHex, nickname, clock() + expiry)
            } else {
                removeLocked(conversationID, senderHex)
            }
            publishLocked()
        }
    }

    /** Messaggio arrivato da [senderHex] in [conversationID]: non sta piu' scrivendo. */
    fun clear(conversationID: String, senderHex: String) {
        synchronized(lock) {
            if (entries[conversationID]?.containsKey(senderHex) != true) return
            removeLocked(conversationID, senderHex)
            publishLocked()
        }
    }

    /** Elimina le voci scadute (chiamato dal timer interno; pubblico per i test). */
    fun purgeExpired() {
        synchronized(lock) { publishLocked() }
    }

    private fun removeLocked(conversationID: String, senderHex: String) {
        val peers = entries[conversationID] ?: return
        peers.remove(senderHex)
        if (peers.isEmpty()) entries.remove(conversationID)
    }

    /** Toglie le voci scadute, pubblica lo snapshot e programma la prossima scadenza. */
    private fun publishLocked() {
        val now = clock()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val conversation = iterator.next()
            conversation.value.values.removeAll { it.expiresAtMs <= now }
            if (conversation.value.isEmpty()) iterator.remove()
        }
        val snapshot = entries.mapValues { it.value.values.toList() }
        if (snapshot != _state.value) _state.value = snapshot
        expiryJob?.cancel()
        expiryJob = null
        val next = entries.values.flatMap { it.values }.minOfOrNull { it.expiresAtMs } ?: return
        val wait = (next - now).coerceAtLeast(0L)
        expiryJob = scope.launch {
            delay(wait + 1)
            purgeExpired()
        }
    }
}
