package com.stefanobrandi.aerochat.data.records

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Token di abbinamento `pt` messo nel proprio QR / link d'invito (release 1.2). Chi scansiona lo usa per
 * cifrare il `PAIR_HELLO`; noi proviamo tutti i token validi (7 giorni, al massimo 16).
 */
@Entity(tableName = PairingTokenRecord.databaseTableName)
data class PairingTokenRecord(
    @PrimaryKey
    @ColumnInfo(name = "token")
    val token: ByteArray,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingTokenRecord) return false
        return token.contentEquals(other.token) && createdAtMs == other.createdAtMs
    }

    override fun hashCode(): Int = 31 * token.contentHashCode() + createdAtMs.hashCode()

    companion object {
        const val databaseTableName = "pairing_tokens"
    }
}
