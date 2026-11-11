package com.stefanobrandi.aerochat.data.repositories

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import com.stefanobrandi.aerochat.core.crypto.CryptoEngine
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.data.AppDatabase
import com.stefanobrandi.aerochat.data.records.PairingTokenRecord

/**
 * Token di abbinamento `pt` dei propri QR: validi [MeshProtocol.pairingTokenLifetime] (7 giorni), al
 * massimo [MeshProtocol.maxPairingTokens] (i piu' vecchi vengono scartati). Un token non viene consumato
 * dal `PAIR_HELLO`: lo stesso QR puo' essere scansionato da piu' persone finche' e' valido.
 */
class PairingTokenStore(val database: AppDatabase) {

    private val dao: PairingTokenDao get() = database.pairingTokenDao()

    /** Genera e salva un token nuovo (da mettere nel QR appena mostrato). `now` in epoch ms. */
    suspend fun issue(now: Long = System.currentTimeMillis()): ByteArray {
        val token = CryptoEngine.randomPairingToken()
        database.withTransaction {
            dao.insert(PairingTokenRecord(token = token, createdAtMs = now))
            dao.purgeOlderThan(now - MeshProtocol.pairingTokenLifetime)
            dao.keepNewest(MeshProtocol.maxPairingTokens)
        }
        return token
    }

    /** Token ancora validi, dal piu' recente. */
    suspend fun valid(now: Long = System.currentTimeMillis()): List<ByteArray> =
        dao.validTokens(minCreatedAtMs = now - MeshProtocol.pairingTokenLifetime, limit = MeshProtocol.maxPairingTokens)

    suspend fun count(): Int = dao.count()
}

@Dao
interface PairingTokenDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: PairingTokenRecord)

    @Query(
        "SELECT token FROM pairing_tokens WHERE created_at_ms > :minCreatedAtMs " +
            "ORDER BY created_at_ms DESC LIMIT :limit",
    )
    suspend fun validTokens(minCreatedAtMs: Long, limit: Int): List<ByteArray>

    @Query("DELETE FROM pairing_tokens WHERE created_at_ms <= :minCreatedAtMs")
    suspend fun purgeOlderThan(minCreatedAtMs: Long): Int

    @Query(
        "DELETE FROM pairing_tokens WHERE token NOT IN " +
            "(SELECT token FROM pairing_tokens ORDER BY created_at_ms DESC LIMIT :count)",
    )
    suspend fun keepNewest(count: Int): Int

    @Query("SELECT COUNT(*) FROM pairing_tokens")
    suspend fun count(): Int
}
