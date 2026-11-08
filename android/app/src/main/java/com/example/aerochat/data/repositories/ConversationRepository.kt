package com.example.aerochat.data.repositories

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.GroupMemberRecord
import com.example.aerochat.data.records.PeerRecord
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

class ConversationRepository(val database: AppDatabase) {

    private val dao: ConversationDao get() = database.conversationDao()
    private val members: GroupMemberDao get() = database.groupMemberDao()

    suspend fun fetch(id: String): ConversationRecord? = dao.fetchOne(id)

    /** Tutte le conversazioni permanenti (Tab 1): prima le fissate, poi per ultimo messaggio. */
    suspend fun fetchPermanent(): List<ConversationRecord> = dao.fetchPermanent()

    /** Lookup usato dal router: `recipient_id` dell'envelope -> conversazione locale. */
    suspend fun fetch(recipientID: ByteArray): ConversationRecord? = dao.fetchByRecipientID(recipientID)

    /** Restituisce (creandola se serve) la chat 1-a-1 con il peer. */
    suspend fun ensureDirectConversation(with: PeerRecord): ConversationRecord = database.withTransaction {
        val candidate = ConversationRecord.direct(with = with)
        dao.fetchOne(candidate.id)?.let { return@withTransaction it }
        dao.insert(candidate)
        candidate
    }

    /** Crea un gruppo di viaggio locale con chiave simmetrica nuova (o importata da un QR). */
    suspend fun createGroup(
        id: UUID = UUID.randomUUID(),
        title: String,
        secretKey: ByteArray = CryptoEngine.randomGroupSecretKey(),
        members: List<PeerRecord> = emptyList(),
    ): ConversationRecord = database.withTransaction {
        val record = ConversationRecord.group(id = id, title = title, secretKey = secretKey)
        dao.fetchOne(record.id)?.let { return@withTransaction it }
        dao.insert(record)
        for (member in members) {
            this.members.upsert(GroupMemberRecord(conversationID = record.id, peer = member))
        }
        record
    }

    suspend fun addGroupMember(peer: PeerRecord, to: String) {
        members.upsert(GroupMemberRecord(conversationID = to, peer = peer))
    }

    suspend fun groupMembers(of: String): List<GroupMemberRecord> = members.membersOf(of)

    /** Gruppi di cui fa parte il peer: usato per il filtro relay "ponte membro dello stesso gruppo". */
    suspend fun groupIDs(containing: ByteArray): Set<String> = members.conversationIDsContaining(containing).toSet()

    /**
     * Cabina Pubblica valida in questo istante: se quella esistente e' scaduta viene
     * eliminata (con i suoi messaggi) e ricreata per altre 12 ore. `now` in epoch ms.
     */
    suspend fun currentCabinLounge(now: Long = System.currentTimeMillis()): ConversationRecord =
        database.withTransaction {
            val existing = dao.fetchOne(ConversationRecord.cabinLoungeID)
            if (existing != null) {
                if (!existing.isExpired(now = now)) return@withTransaction existing
                dao.deleteById(existing.id)
            }
            val lounge = ConversationRecord.cabinLounge(createdAt = now)
            dao.insert(lounge)
            lounge
        }

    /** Applica un Flight Group Pass alla conversazione (da acquisto locale o `PRO_PASS_GRANT`). */
    suspend fun setProUnlocked(until: Long, conversationID: String) {
        dao.setProUnlockedUntil(conversationID, until)
    }

    suspend fun markRead(conversationID: String) {
        database.withTransaction {
            dao.setUnreadCount(conversationID, 0)
            database.messageDao().markIncomingRead(conversationID)
        }
    }

    /** Fissa / sblocca una chat in cima alla lista. */
    suspend fun setPinned(pinned: Boolean, conversationID: String) {
        dao.setPinned(conversationID, pinned)
    }

    suspend fun updateTitle(title: String, conversationID: String) {
        dao.updateTitle(conversationID, title)
    }

    suspend fun delete(conversationID: String) {
        dao.deleteById(conversationID)
    }

    /** Stream di una singola conversazione (sblocco Pro, titolo, unread). */
    fun observe(id: String): Flow<ConversationRecord?> = dao.observeOne(id).distinctUntilChanged()

    /** Stream dei membri di un gruppo (scheda info gruppo): si aggiorna con QR e gossip. */
    fun observeGroupMembers(of: String): Flow<List<GroupMemberRecord>> =
        members.observeMembersOf(of).distinctUntilChanged()

    fun observePermanent(): Flow<List<ConversationRecord>> = dao.observePermanent().distinctUntilChanged()
}

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun fetchOne(id: String): ConversationRecord?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeOne(id: String): Flow<ConversationRecord?>

    @Query(
        "SELECT * FROM conversations WHERE type != 'cabin_lounge' " +
            "ORDER BY is_pinned DESC, last_message_at_ms DESC, title",
    )
    suspend fun fetchPermanent(): List<ConversationRecord>

    @Query(
        "SELECT * FROM conversations WHERE type != 'cabin_lounge' " +
            "ORDER BY is_pinned DESC, last_message_at_ms DESC, title",
    )
    fun observePermanent(): Flow<List<ConversationRecord>>

    @Query("SELECT * FROM conversations WHERE recipient_id = :recipientID LIMIT 1")
    suspend fun fetchByRecipientID(recipientID: ByteArray): ConversationRecord?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: ConversationRecord)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE conversations SET pro_unlocked_until_ms = :validUntilMs WHERE id = :id")
    suspend fun setProUnlockedUntil(id: String, validUntilMs: Long)

    @Query("UPDATE conversations SET unread_count = :count WHERE id = :id")
    suspend fun setUnreadCount(id: String, count: Int)

    /** Azzera il badge solo se serve (nessuna scrittura / invalidazione inutile). */
    @Query("UPDATE conversations SET unread_count = 0 WHERE id = :id AND unread_count != 0")
    suspend fun resetUnreadIfNeeded(id: String): Int

    @Query("UPDATE conversations SET is_pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    /** Dopo un nuovo messaggio: `last_message_at_ms` e incremento del badge. */
    @Query(
        "UPDATE conversations SET last_message_at_ms = :lastMessageAtMs, " +
            "unread_count = unread_count + :unreadIncrement WHERE id = :id",
    )
    suspend fun registerMessage(id: String, lastMessageAtMs: Long, unreadIncrement: Int)

    @Query("UPDATE conversations SET title = :nickname WHERE peer_node_id = :nodeID AND type = 'direct'")
    suspend fun syncDirectTitle(nodeID: ByteArray, nickname: String)

    @Query("DELETE FROM conversations WHERE expires_at_ms IS NOT NULL AND expires_at_ms <= :nowMs")
    suspend fun purgeExpired(nowMs: Long): Int
}

@Dao
interface GroupMemberDao {

    @Upsert
    suspend fun upsert(member: GroupMemberRecord)

    @Query("SELECT * FROM group_members WHERE conversation_id = :conversationID")
    suspend fun membersOf(conversationID: String): List<GroupMemberRecord>

    @Query("SELECT * FROM group_members WHERE conversation_id = :conversationID")
    fun observeMembersOf(conversationID: String): Flow<List<GroupMemberRecord>>

    @Query("SELECT conversation_id FROM group_members WHERE node_id = :nodeID")
    suspend fun conversationIDsContaining(nodeID: ByteArray): List<String>
}
