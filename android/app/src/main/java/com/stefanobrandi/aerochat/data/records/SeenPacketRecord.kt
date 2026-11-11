package com.stefanobrandi.aerochat.data.records

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol

/** Backing persistente della cache anti-loop del managed flooding. */
@Entity(
    tableName = SeenPacketRecord.databaseTableName,
    indices = [Index(value = ["expires_at_ms"], name = "index_seen_packets_on_expires_at_ms")],
)
data class SeenPacketRecord(
    @PrimaryKey
    @ColumnInfo(name = "msg_id")
    val msgID: ByteArray,
    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SeenPacketRecord) return false
        return msgID.contentEquals(other.msgID) && expiresAtMs == other.expiresAtMs
    }

    override fun hashCode(): Int = 31 * msgID.contentHashCode() + expiresAtMs.hashCode()

    companion object {
        const val databaseTableName = "seen_packets"
    }
}

/** Pacchetto cifrato in attesa di un peer (Delay-Tolerant Networking). */
@Entity(
    tableName = RelayQueueRecord.databaseTableName,
    indices = [
        Index(value = ["expires_at_ms"], name = "index_relay_queue_on_expires_at_ms"),
        Index(value = ["enqueued_at_ms"], name = "index_relay_queue_on_enqueued_at_ms"),
    ],
)
data class RelayQueueRecord(
    @PrimaryKey
    @ColumnInfo(name = "msg_id")
    val msgID: ByteArray,
    @ColumnInfo(name = "recipient_id")
    val recipientID: ByteArray,
    @ColumnInfo(name = "raw_envelope_bytes")
    val rawEnvelopeBytes: ByteArray,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Int,
    @ColumnInfo(name = "enqueued_at_ms")
    val enqueuedAtMs: Long,
    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long,
) {

    /** `enqueuedAt` in epoch ms, `lifetime` in ms. */
    @Ignore
    constructor(
        msgID: ByteArray,
        recipientID: ByteArray,
        rawEnvelopeBytes: ByteArray,
        enqueuedAt: Long = System.currentTimeMillis(),
        lifetime: Long = MeshProtocol.relayQueueLifetime,
    ) : this(
        msgID = msgID,
        recipientID = recipientID,
        rawEnvelopeBytes = rawEnvelopeBytes,
        sizeBytes = rawEnvelopeBytes.size,
        enqueuedAtMs = enqueuedAt,
        expiresAtMs = enqueuedAt + lifetime,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayQueueRecord) return false
        return msgID.contentEquals(other.msgID) &&
            recipientID.contentEquals(other.recipientID) &&
            rawEnvelopeBytes.contentEquals(other.rawEnvelopeBytes) &&
            sizeBytes == other.sizeBytes &&
            enqueuedAtMs == other.enqueuedAtMs &&
            expiresAtMs == other.expiresAtMs
    }

    override fun hashCode(): Int {
        var result = msgID.contentHashCode()
        result = 31 * result + recipientID.contentHashCode()
        result = 31 * result + rawEnvelopeBytes.contentHashCode()
        result = 31 * result + sizeBytes
        result = 31 * result + enqueuedAtMs.hashCode()
        result = 31 * result + expiresAtMs.hashCode()
        return result
    }

    companion object {
        const val databaseTableName = "relay_queue"
    }
}
