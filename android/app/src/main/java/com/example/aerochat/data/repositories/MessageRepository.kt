package com.example.aerochat.data.repositories

import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.ConversationType
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.MessageReadRecord
import com.example.aerochat.data.records.MessageRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold

class MessageRepository(val database: AppDatabase) {

    private val dao: MessageDao get() = database.messageDao()

    /**
     * Inserisce il messaggio e aggiorna `last_message_at_ms` / `unread_count` della conversazione.
     * Idempotente: un `msg_id` gia' presente viene ignorato (ritorna `false`).
     */
    suspend fun insert(message: MessageRecord): Boolean = database.withTransaction {
        if (dao.exists(message.msgID)) return@withTransaction false
        dao.insert(message)
        database.conversationDao().registerMessage(
            id = message.conversationID,
            lastMessageAtMs = message.createdAtMs,
            unreadIncrement = if (message.isOutgoing) 0 else 1,
        )
        true
    }

    suspend fun fetch(msgID: ByteArray): MessageRecord? = dao.fetchOne(msgID)

    /** Ultimi `limit` messaggi della conversazione in ordine cronologico. */
    suspend fun fetch(conversationID: String, limit: Int = 200): List<MessageRecord> =
        dao.fetchLatest(conversationID, limit).asReversed()

    /** Tutti i messaggi dal momento indicato (statistiche della cartolina di volo), piu' recenti per primi. `since` in epoch ms. */
    suspend fun fetch(since: Long, limit: Int = 5_000): List<MessageRecord> = dao.fetchSince(since, limit)

    suspend fun updateDeliveryStatus(status: DeliveryStatus, msgID: ByteArray) {
        dao.updateDeliveryStatus(msgID, status)
    }

    /** Collega il file media ricostruito al messaggio placeholder (BlurHash/forma d'onda gia' mostrati). */
    suspend fun updateMedia(localPath: String, msgID: ByteArray) {
        dao.updateMedia(msgID, localPath)
    }

    /** Aggiorna lo stato serializzato di una partita (la bolla di gioco e' un unico messaggio che evolve). */
    suspend fun updateGameState(state: ByteArray, msgID: ByteArray) {
        dao.updateGameState(msgID, state)
    }

    /**
     * Promuove lo stato solo in avanti (`queued -> sent_mesh -> delivered_ack -> read`),
     * cosi' un ACK in ritardo non riporta indietro un messaggio gia' letto.
     */
    suspend fun advanceDeliveryStatus(to: DeliveryStatus, msgID: ByteArray) {
        database.withTransaction {
            val message = dao.fetchOne(msgID) ?: return@withTransaction
            if (to.rank > message.deliveryStatus.rank) {
                dao.update(message.copy(deliveryStatus = to))
            }
        }
    }

    /**
     * Segna come letti i messaggi ricevuti della conversazione (azzera anche il badge) e li
     * restituisce, nella stessa transazione, per inviare le conferme di lettura ai mittenti.
     * I record restituiti hanno lo stato precedente all'aggiornamento (come iOS).
     */
    suspend fun markIncomingRead(conversationID: String): List<MessageRecord> = database.withTransaction {
        val unread = dao.fetchIncomingUnread(conversationID)
        if (unread.isNotEmpty()) {
            dao.markIncomingRead(conversationID)
        }
        database.conversationDao().resetUnreadIfNeeded(conversationID)
        unread
    }

    /**
     * `READ_RECEIPT`: registra che `readerNodeID` ha letto i nostri messaggi e ne fa avanzare lo stato.
     * - Chat 1-a-1: solo il compagno della chat puo' confermare; il messaggio passa a `read`.
     * - Gruppo: il lettore viene salvato in `message_reads`; il messaggio passa a `read` solo quando i
     *   lettori coprono tutti i membri del gruppo noti in quel momento (mittente escluso), altrimenti
     *   almeno a `delivered_ack` (chi ha letto l'ha ricevuto).
     * Ritorna i `msg_id` il cui stato e' avanzato. Lo stato non torna mai indietro. `now` in epoch ms.
     */
    suspend fun markReadByRecipient(
        msgIDs: List<ByteArray>,
        readerNodeID: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): List<ByteArray> =
        database.withTransaction {
            val updated = mutableListOf<ByteArray>()
            for (msgID in msgIDs) {
                val message = dao.fetchOne(msgID) ?: continue
                if (!message.isOutgoing) continue
                val conversation = database.conversationDao().fetchOne(message.conversationID) ?: continue
                val target = when (conversation.type) {
                    ConversationType.direct -> {
                        if (!conversation.peerNodeID.contentEquals(readerNodeID)) continue
                        DeliveryStatus.read
                    }
                    ConversationType.group -> {
                        if (readerNodeID.contentEquals(message.senderNodeID)) continue
                        dao.insertRead(MessageReadRecord(msgID = msgID, readerNodeID = readerNodeID, readAtMs = now))
                        val unreadMembers = dao.countGroupMembersNotRead(message.conversationID, message.senderNodeID, msgID)
                        if (unreadMembers == 0) DeliveryStatus.read else DeliveryStatus.deliveredAck
                    }
                    ConversationType.cabinLounge -> continue
                }
                if (target.rank <= message.deliveryStatus.rank) continue
                dao.update(message.copy(deliveryStatus = target))
                updated += msgID
            }
            updated
        }

    /** `node_id` dei membri che hanno confermato la lettura di un nostro messaggio di gruppo. */
    suspend fun readerNodeIDs(msgID: ByteArray): List<ByteArray> = dao.readerNodeIDs(msgID)

    /**
     * Tra `among`, i nostri messaggi in uscita non ancora confermati dal destinatario.
     * Ritorna i `msg_id` come stringhe esadecimali minuscole (`ByteArray.toHex()`).
     */
    suspend fun awaitingAckIDs(among: List<ByteArray>): Set<String> {
        if (among.isEmpty()) return emptySet()
        val result = mutableSetOf<String>()
        for (chunk in among.chunked(maxBindArguments)) {
            dao.awaitingAckIDs(chunk).mapTo(result) { it.toHex() }
        }
        return result
    }

    /** `queued -> sent_mesh` per piu' messaggi in una transazione (gli stati successivi restano invariati). */
    suspend fun markSentMesh(msgIDs: List<ByteArray>) {
        if (msgIDs.isEmpty()) return
        database.withTransaction {
            for (chunk in msgIDs.chunked(maxBindArguments)) dao.markSentMesh(chunk)
        }
    }

    /** Messaggi in uscita ancora in coda (da ritrasmettere quando un peer torna a portata). */
    suspend fun queuedOutgoing(): List<MessageRecord> = dao.queuedOutgoing()

    /** Ricerca istantanea full-text (prefissi su ogni token, es. "tax" trova "taxi"). */
    suspend fun search(query: String, limit: Int = 50): List<MessageRecord> {
        val pattern = prefixPattern(query) ?: return emptyList()
        return dao.search(pattern, limit)
    }

    /** Path dei media su disco di una conversazione (da rimuovere quando la chat viene eliminata). */
    suspend fun mediaPaths(conversationID: String): List<String> = dao.mediaPaths(conversationID)

    suspend fun delete(msgID: ByteArray) {
        dao.deleteById(msgID)
    }

    /**
     * Stream live dei messaggi di una conversazione (ordine cronologico).
     *
     * Le righe invariate conservano l'istanza dell'emissione precedente: Room ricrea tutti gli oggetti a
     * ogni scrittura sulla tabella e Compose (strong skipping) confronta i parametri per riferimento, quindi
     * ogni bolla visibile veniva ricomposta per un solo ACK arrivato. Confronto e riuso fuori dal main thread.
     */
    fun observe(conversationID: String, limit: Int = 200): Flow<List<MessageRecord>> =
        dao.observeLatest(conversationID, limit)
            .map { it.asReversed() }
            .distinctUntilChanged()
            .runningFold(emptyList<MessageRecord>()) { previous, next ->
                if (previous.isEmpty()) {
                    next
                } else {
                    val known = HashMap<String, MessageRecord>(previous.size * 2)
                    for (record in previous) known[record.msgID.toHex()] = record
                    next.map { record -> known[record.msgID.toHex()]?.takeIf { it == record } ?: record }
                }
            }
            .drop(1)
            .flowOn(Dispatchers.Default)

    companion object {
        /** Sotto il limite storico di 999 variabili SQLite. */
        private const val maxBindArguments = 500

        /**
         * Equivalente di `FTS5Pattern(matchingAllPrefixesIn:)`: ogni token (lettere/cifre) diventa una
         * ricerca per prefisso, tutti i token in AND. `null` se la query non contiene token.
         */
        internal fun prefixPattern(query: String): String? {
            val tokens = query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            return tokens.joinToString(" ") { "\"${it.lowercase()}*\"" }
        }
    }
}

/** Ordine di avanzamento dello stato di consegna. */
val DeliveryStatus.rank: Int
    get() = when (this) {
        DeliveryStatus.queued -> 0
        DeliveryStatus.sentMesh -> 1
        DeliveryStatus.deliveredAck -> 2
        DeliveryStatus.read -> 3
    }

@Dao
interface MessageDao {

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE msg_id = :msgID)")
    suspend fun exists(msgID: ByteArray): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageRecord)

    @Update
    suspend fun update(message: MessageRecord)

    @Query("SELECT * FROM messages WHERE msg_id = :msgID")
    suspend fun fetchOne(msgID: ByteArray): MessageRecord?

    @Query("SELECT * FROM messages WHERE created_at_ms >= :sinceMs ORDER BY created_at_ms DESC LIMIT :limit")
    suspend fun fetchSince(sinceMs: Long, limit: Int): List<MessageRecord>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationID ORDER BY created_at_ms DESC LIMIT :limit")
    suspend fun fetchLatest(conversationID: String, limit: Int): List<MessageRecord>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationID ORDER BY created_at_ms DESC LIMIT :limit")
    fun observeLatest(conversationID: String, limit: Int): Flow<List<MessageRecord>>

    @Query("UPDATE messages SET delivery_status = :status WHERE msg_id = :msgID")
    suspend fun updateDeliveryStatus(msgID: ByteArray, status: DeliveryStatus)

    @Query("UPDATE messages SET media_local_path = :localPath WHERE msg_id = :msgID")
    suspend fun updateMedia(msgID: ByteArray, localPath: String)

    @Query("UPDATE messages SET game_state = :state WHERE msg_id = :msgID")
    suspend fun updateGameState(msgID: ByteArray, state: ByteArray)

    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationID " +
            "AND is_outgoing = 0 AND delivery_status != 'read'",
    )
    suspend fun fetchIncomingUnread(conversationID: String): List<MessageRecord>

    @Query(
        "UPDATE messages SET delivery_status = 'read' WHERE conversation_id = :conversationID " +
            "AND is_outgoing = 0 AND delivery_status != 'read'",
    )
    suspend fun markIncomingRead(conversationID: String): Int

    @Query(
        "SELECT msg_id FROM messages WHERE msg_id IN (:msgIDs) " +
            "AND is_outgoing = 1 AND delivery_status IN ('queued', 'sent_mesh')",
    )
    suspend fun awaitingAckIDs(msgIDs: List<ByteArray>): List<ByteArray>

    @Query("UPDATE messages SET delivery_status = 'sent_mesh' WHERE msg_id IN (:msgIDs) AND delivery_status = 'queued'")
    suspend fun markSentMesh(msgIDs: List<ByteArray>): Int

    @Query("SELECT * FROM messages WHERE is_outgoing = 1 AND delivery_status = 'queued' ORDER BY created_at_ms")
    suspend fun queuedOutgoing(): List<MessageRecord>

    @Query(
        "SELECT messages.* FROM messages " +
            "JOIN messages_fts ON messages_fts.rowid = messages.rowid " +
            "WHERE messages_fts MATCH :pattern " +
            "ORDER BY messages.created_at_ms DESC LIMIT :limit",
    )
    suspend fun search(pattern: String, limit: Int): List<MessageRecord>

    @Query("SELECT media_local_path FROM messages WHERE conversation_id = :conversationID AND media_local_path IS NOT NULL")
    suspend fun mediaPaths(conversationID: String): List<String>

    @Query("DELETE FROM messages WHERE msg_id = :msgID")
    suspend fun deleteById(msgID: ByteArray): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRead(read: MessageReadRecord)

    @Query("SELECT reader_node_id FROM message_reads WHERE msg_id = :msgID ORDER BY read_at_ms")
    suspend fun readerNodeIDs(msgID: ByteArray): List<ByteArray>

    /** Membri del gruppo noti ora (escluso il mittente) che non compaiono tra i lettori del messaggio. */
    @Query(
        "SELECT COUNT(*) FROM group_members WHERE conversation_id = :conversationID AND node_id != :senderNodeID " +
            "AND node_id NOT IN (SELECT reader_node_id FROM message_reads WHERE msg_id = :msgID)",
    )
    suspend fun countGroupMembersNotRead(conversationID: String, senderNodeID: ByteArray, msgID: ByteArray): Int

    @Query("DELETE FROM messages WHERE conversation_id = :conversationID AND created_at_ms < :cutoffMs")
    suspend fun purgeLoungeMessagesOlderThan(conversationID: String, cutoffMs: Long): Int
}
