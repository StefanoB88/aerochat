package com.example.aerochat.data.records

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.crypto.IdentityKeys
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.data.AppDatabase

enum class MessageContentType(val rawValue: String) {
    text("text"),
    image("image"),
    audio("audio"),
    game("game"),
    system("system");

    companion object {
        fun fromRawValue(rawValue: String): MessageContentType? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** Stato di consegna mostrato nella bolla (orologio / antenna / doppia spunta). */
enum class DeliveryStatus(val rawValue: String) {
    /** Nessun peer a portata: in `relay_queue`, partira' da solo. */
    queued("queued"),
    /** Consegnato ad almeno un peer BLE (in viaggio nella mesh). */
    sentMesh("sent_mesh"),
    /** Ricevuto `DELIVERY_ACK` dal destinatario. */
    deliveredAck("delivered_ack"),
    read("read");

    companion object {
        fun fromRawValue(rawValue: String): DeliveryStatus? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

@Entity(
    tableName = MessageRecord.databaseTableName,
    foreignKeys = [
        ForeignKey(
            entity = ConversationRecord::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "created_at_ms"], name = "index_messages_on_conversation_id_created_at_ms"),
    ],
)
data class MessageRecord(
    @PrimaryKey
    @ColumnInfo(name = "msg_id")
    val msgID: ByteArray,
    @ColumnInfo(name = "conversation_id")
    val conversationID: String,
    @ColumnInfo(name = "sender_node_id")
    val senderNodeID: ByteArray,
    @ColumnInfo(name = "sender_nickname")
    val senderNickname: String? = null,
    @ColumnInfo(name = "content_type")
    val contentType: MessageContentType,
    @ColumnInfo(name = "text")
    val text: String? = null,
    @ColumnInfo(name = "media_local_path")
    val mediaLocalPath: String? = null,
    @ColumnInfo(name = "blurhash")
    val blurhash: String? = null,
    @ColumnInfo(name = "audio_waveform")
    val audioWaveform: ByteArray? = null,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Int? = null,
    @ColumnInfo(name = "reply_to_msg_id")
    val replyToMsgID: ByteArray? = null,
    @ColumnInfo(name = "game_state")
    val gameState: ByteArray? = null,
    @ColumnInfo(name = "is_outgoing")
    val isOutgoing: Boolean,
    @ColumnInfo(name = "delivery_status")
    val deliveryStatus: DeliveryStatus,
    /** `hop_count` dell'envelope ricevuto: alimenta l'indicatore "Stato Radio". */
    @ColumnInfo(name = "hop_count_received")
    val hopCountReceived: Int? = null,
    /** epoch ms. */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long = System.currentTimeMillis(),
    /** Ultima modifica dell'autore (`MESSAGE_REVISION` EDIT), epoch ms; `null` se mai modificato. */
    @ColumnInfo(name = "edited_at_ms")
    val editedAtMs: Long? = null,
    /** Cancellato per tutti (`MESSAGE_REVISION` DELETE): testo e media azzerati, la bolla mostra il segnaposto. */
    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean = false,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageRecord) return false
        return msgID.contentEquals(other.msgID) &&
            conversationID == other.conversationID &&
            senderNodeID.contentEquals(other.senderNodeID) &&
            senderNickname == other.senderNickname &&
            contentType == other.contentType &&
            text == other.text &&
            mediaLocalPath == other.mediaLocalPath &&
            blurhash == other.blurhash &&
            audioWaveform.contentEquals(other.audioWaveform) &&
            durationMs == other.durationMs &&
            replyToMsgID.contentEquals(other.replyToMsgID) &&
            gameState.contentEquals(other.gameState) &&
            isOutgoing == other.isOutgoing &&
            deliveryStatus == other.deliveryStatus &&
            hopCountReceived == other.hopCountReceived &&
            createdAtMs == other.createdAtMs &&
            editedAtMs == other.editedAtMs &&
            isDeleted == other.isDeleted
    }

    override fun hashCode(): Int {
        var result = msgID.contentHashCode()
        result = 31 * result + conversationID.hashCode()
        result = 31 * result + senderNodeID.contentHashCode()
        result = 31 * result + (senderNickname?.hashCode() ?: 0)
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (mediaLocalPath?.hashCode() ?: 0)
        result = 31 * result + (blurhash?.hashCode() ?: 0)
        result = 31 * result + audioWaveform.contentHashCode()
        result = 31 * result + (durationMs ?: 0)
        result = 31 * result + replyToMsgID.contentHashCode()
        result = 31 * result + gameState.contentHashCode()
        result = 31 * result + isOutgoing.hashCode()
        result = 31 * result + deliveryStatus.hashCode()
        result = 31 * result + (hopCountReceived ?: 0)
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + (editedAtMs?.hashCode() ?: 0)
        result = 31 * result + isDeleted.hashCode()
        return result
    }

    companion object {
        const val databaseTableName = "messages"

        /** Messaggio testuale in uscita, accettato subito nella chat con stato `queued`. */
        fun outgoingText(
            text: String,
            conversationID: String,
            sender: IdentityKeys,
            senderNickname: String? = null,
            replyTo: ByteArray? = null,
            msgID: ByteArray = CryptoEngine.randomMessageID(),
        ): MessageRecord = MessageRecord(
            msgID = msgID,
            conversationID = conversationID,
            senderNodeID = sender.nodeID,
            senderNickname = senderNickname,
            contentType = MessageContentType.text,
            text = text,
            replyToMsgID = replyTo,
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.queued,
        )

        /** Elimina i messaggi della Cabina Pubblica piu' vecchi di 12 ore. */
        suspend fun purgeExpiredLoungeMessages(db: AppDatabase, nowMs: Long): Int {
            val cutoff = nowMs - MeshProtocol.loungeLifetime
            return db.messageDao().purgeLoungeMessagesOlderThan(ConversationRecord.cabinLoungeID, cutoff)
        }
    }
}

/** Testo, foto e note vocali si possono cancellare per tutti; giochi e messaggi di sistema no. */
val MessageRecord.isRevisableContent: Boolean
    get() = contentType == MessageContentType.text || contentType == MessageContentType.image ||
        contentType == MessageContentType.audio

/**
 * `true` se l'utente puo' ancora modificare/cancellare per tutti questo messaggio: nostro, non cancellato,
 * di un tipo ammesso ed entro [MeshProtocol.revisionWindow] dall'invio. `now` in epoch ms.
 */
fun MessageRecord.canBeRevised(now: Long = System.currentTimeMillis()): Boolean =
    isOutgoing && !isDeleted && isRevisableContent && now - createdAtMs <= MeshProtocol.revisionWindow

/** Solo i messaggi di testo si possono modificare. */
fun MessageRecord.canBeEdited(now: Long = System.currentTimeMillis()): Boolean =
    canBeRevised(now) && contentType == MessageContentType.text

/**
 * Indice full-text `messages_fts` (external content su `messages`, sincronizzato da trigger generati da Room).
 * Su iOS e' FTS5; Room supporta solo FTS4 (l'SQLite di sistema Android non garantisce FTS5).
 * Stesso tokenizer `unicode61` e stessa semantica di ricerca per prefissi.
 */
@Fts4(contentEntity = MessageRecord::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = MessageFtsRecord.databaseTableName)
data class MessageFtsRecord(
    @ColumnInfo(name = "text")
    val text: String?,
) {
    companion object {
        const val databaseTableName = "messages_fts"
    }
}

/**
 * Lettore di un nostro messaggio di gruppo (`READ_RECEIPT`), tabella `message_reads` (iOS `v5_message_reads`).
 * La spunta blu arriva solo quando i lettori coprono tutti i membri del gruppo (mittente escluso).
 * I record seguono il messaggio eliminato (`ON DELETE CASCADE`).
 */
@Entity(
    tableName = MessageReadRecord.databaseTableName,
    primaryKeys = ["msg_id", "reader_node_id"],
    foreignKeys = [
        ForeignKey(
            entity = MessageRecord::class,
            parentColumns = ["msg_id"],
            childColumns = ["msg_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageReadRecord(
    @ColumnInfo(name = "msg_id")
    val msgID: ByteArray,
    @ColumnInfo(name = "reader_node_id")
    val readerNodeID: ByteArray,
    /** Epoch ms. */
    @ColumnInfo(name = "read_at_ms")
    val readAtMs: Long,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageReadRecord) return false
        return msgID.contentEquals(other.msgID) && readerNodeID.contentEquals(other.readerNodeID) && readAtMs == other.readAtMs
    }

    override fun hashCode(): Int {
        var result = msgID.contentHashCode()
        result = 31 * result + readerNodeID.contentHashCode()
        result = 31 * result + readAtMs.hashCode()
        return result
    }

    companion object {
        const val databaseTableName = "message_reads"
    }
}
