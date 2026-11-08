package com.example.aerochat.data.records

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.AppDatabase
import java.util.UUID

enum class ConversationType(val rawValue: String) {
    direct("direct"),
    group("group"),
    cabinLounge("cabin_lounge");

    companion object {
        fun fromRawValue(rawValue: String): ConversationType? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

@Entity(
    tableName = ConversationRecord.databaseTableName,
    foreignKeys = [
        ForeignKey(
            entity = PeerRecord::class,
            parentColumns = ["node_id"],
            childColumns = ["peer_node_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["recipient_id"], name = "index_conversations_on_recipient_id"),
        Index(value = ["expires_at_ms"], name = "index_conversations_on_expires_at_ms"),
    ],
)
data class ConversationRecord(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "type")
    val type: ConversationType,
    @ColumnInfo(name = "title")
    val title: String,
    /** 8 byte confrontati con `MeshEnvelope.recipient_id` in ricezione. */
    @ColumnInfo(name = "recipient_id")
    val recipientID: ByteArray,
    @ColumnInfo(name = "peer_node_id")
    val peerNodeID: ByteArray?,
    @ColumnInfo(name = "group_secret_key")
    val groupSecretKey: ByteArray?,
    @ColumnInfo(name = "pro_unlocked_until_ms")
    val proUnlockedUntilMs: Long?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long?,
    @ColumnInfo(name = "last_message_at_ms")
    val lastMessageAtMs: Long?,
    @ColumnInfo(name = "unread_count", defaultValue = "0")
    val unreadCount: Int,
    /** Chat fissata in cima alla lista. */
    @ColumnInfo(name = "is_pinned", defaultValue = "0")
    val isPinned: Boolean = false,
) {

    /** UUID del gruppo (solo per `type == group`). */
    val groupID: UUID?
        get() = if (type == ConversationType.group) runCatching { UUID.fromString(id) }.getOrNull() else null

    /** `true` se la conversazione ha un Flight Group Pass attivo. `now` in epoch ms. */
    fun isProUnlocked(now: Long = System.currentTimeMillis()): Boolean = (proUnlockedUntilMs ?: 0L) > now

    /** `now` in epoch ms. */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        val expiresAtMs = expiresAtMs ?: return false
        return expiresAtMs <= now
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConversationRecord) return false
        return id == other.id &&
            type == other.type &&
            title == other.title &&
            recipientID.contentEquals(other.recipientID) &&
            peerNodeID.contentEquals(other.peerNodeID) &&
            groupSecretKey.contentEquals(other.groupSecretKey) &&
            proUnlockedUntilMs == other.proUnlockedUntilMs &&
            createdAtMs == other.createdAtMs &&
            expiresAtMs == other.expiresAtMs &&
            lastMessageAtMs == other.lastMessageAtMs &&
            unreadCount == other.unreadCount &&
            isPinned == other.isPinned
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + recipientID.contentHashCode()
        result = 31 * result + peerNodeID.contentHashCode()
        result = 31 * result + groupSecretKey.contentHashCode()
        result = 31 * result + (proUnlockedUntilMs?.hashCode() ?: 0)
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + (expiresAtMs?.hashCode() ?: 0)
        result = 31 * result + (lastMessageAtMs?.hashCode() ?: 0)
        result = 31 * result + unreadCount
        result = 31 * result + isPinned.hashCode()
        return result
    }

    companion object {
        const val databaseTableName = "conversations"

        /** Identificatore fisso della Cabina Pubblica corrente. */
        const val cabinLoungeID = "cabin_lounge"

        /** Titolo della Cabina Pubblica (testo iOS). */
        const val cabinLoungeTitle = "Cabina Pubblica"

        /** Chat 1-a-1: l'id e' `direct:<node_id hex>`, il `recipient_id` e' il `node_id` del peer. `createdAt` in epoch ms. */
        fun direct(with: PeerRecord, createdAt: Long = System.currentTimeMillis()): ConversationRecord =
            ConversationRecord(
                id = "direct:${with.nodeID.toHex()}",
                type = ConversationType.direct,
                title = with.nickname,
                recipientID = with.nodeID,
                peerNodeID = with.nodeID,
                groupSecretKey = null,
                proUnlockedUntilMs = null,
                createdAtMs = createdAt,
                expiresAtMs = null,
                lastMessageAtMs = null,
                unreadCount = 0,
            )

        /** Gruppo di viaggio: l'id e' lo UUID minuscolo, il `recipient_id` e' `SHA256(uuid)[0..8]`. `createdAt` in epoch ms. */
        fun group(id: UUID, title: String, secretKey: ByteArray, createdAt: Long = System.currentTimeMillis()): ConversationRecord =
            ConversationRecord(
                id = id.toString().lowercase(),
                type = ConversationType.group,
                title = title,
                recipientID = CryptoEngine.groupRecipientID(groupID = id),
                peerNodeID = null,
                groupSecretKey = secretKey,
                proUnlockedUntilMs = null,
                createdAtMs = createdAt,
                expiresAtMs = null,
                lastMessageAtMs = null,
                unreadCount = 0,
            )

        /** Cabina Pubblica: `recipient_id` tutto a zero, scade dopo 12 ore. `createdAt` in epoch ms. */
        fun cabinLounge(createdAt: Long = System.currentTimeMillis()): ConversationRecord =
            ConversationRecord(
                id = cabinLoungeID,
                type = ConversationType.cabinLounge,
                title = cabinLoungeTitle,
                recipientID = MeshProtocol.loungeRecipientID,
                peerNodeID = null,
                groupSecretKey = null,
                proUnlockedUntilMs = null,
                createdAtMs = createdAt,
                expiresAtMs = createdAt + MeshProtocol.loungeLifetime,
                lastMessageAtMs = null,
                unreadCount = 0,
            )

        /** Elimina le conversazioni scadute (Lounge oltre le 12h); i messaggi seguono per `ON DELETE CASCADE`. */
        suspend fun purgeExpired(db: AppDatabase, nowMs: Long): Int = db.conversationDao().purgeExpired(nowMs)
    }
}

/** Membro di un gruppo di viaggio (popolato dal QR e dal `GROUP_KEY_GOSSIP`). */
@Entity(
    tableName = GroupMemberRecord.databaseTableName,
    primaryKeys = ["conversation_id", "node_id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationRecord::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GroupMemberRecord(
    @ColumnInfo(name = "conversation_id")
    val conversationID: String,
    @ColumnInfo(name = "node_id")
    val nodeID: ByteArray,
    @ColumnInfo(name = "nickname")
    val nickname: String,
    @ColumnInfo(name = "x25519_pub")
    val x25519Pub: ByteArray,
    @ColumnInfo(name = "ed25519_pub")
    val ed25519Pub: ByteArray,
    @ColumnInfo(name = "joined_at_ms")
    val joinedAtMs: Long,
) {

    /** `joinedAt` in epoch ms. */
    @Ignore
    constructor(conversationID: String, peer: PeerRecord, joinedAt: Long = System.currentTimeMillis()) : this(
        conversationID = conversationID,
        nodeID = peer.nodeID,
        nickname = peer.nickname,
        x25519Pub = peer.x25519Pub,
        ed25519Pub = peer.ed25519Pub,
        joinedAtMs = joinedAt,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupMemberRecord) return false
        return conversationID == other.conversationID &&
            nodeID.contentEquals(other.nodeID) &&
            nickname == other.nickname &&
            x25519Pub.contentEquals(other.x25519Pub) &&
            ed25519Pub.contentEquals(other.ed25519Pub) &&
            joinedAtMs == other.joinedAtMs
    }

    override fun hashCode(): Int {
        var result = conversationID.hashCode()
        result = 31 * result + nodeID.contentHashCode()
        result = 31 * result + nickname.hashCode()
        result = 31 * result + x25519Pub.contentHashCode()
        result = 31 * result + ed25519Pub.contentHashCode()
        result = 31 * result + joinedAtMs.hashCode()
        return result
    }

    companion object {
        const val databaseTableName = "group_members"
    }
}
