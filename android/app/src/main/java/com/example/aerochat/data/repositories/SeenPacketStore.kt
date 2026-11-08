package com.example.aerochat.data.repositories

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.SeenPacketRecord

/**
 * Persistenza della tabella anti-loop `seen_packets`.
 * Il `MeshRouterActor` la affianca con una cache LRU in memoria per il fast-path;
 * questa tabella garantisce la deduplicazione anche dopo un riavvio dell'app.
 */
class SeenPacketStore(val database: AppDatabase) {

    private val dao: SeenPacketDao get() = database.seenPacketDao()

    /**
     * Registra `msgID`. Restituisce `true` se era nuovo, `false` se gia' visto (da scartare).
     * @param now epoch ms. @param lifetime ms.
     */
    suspend fun markSeenIfNew(
        msgID: ByteArray,
        now: Long = System.currentTimeMillis(),
        lifetime: Long = MeshProtocol.seenPacketLifetime,
    ): Boolean = dao.insertOrIgnore(SeenPacketRecord(msgID = msgID, expiresAtMs = now + lifetime)) != -1L

    suspend fun contains(msgID: ByteArray): Boolean = dao.exists(msgID)

    /** Gli id non scaduti piu' recenti (per pre-caricare la cache LRU all'avvio). `now` in epoch ms. */
    suspend fun activeMessageIDs(now: Long = System.currentTimeMillis(), limit: Int = Int.MAX_VALUE): List<ByteArray> =
        dao.activeMessageIDs(now, limit)

    /** Elimina i record scaduti e ne ritorna il numero. `now` in epoch ms. */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int = dao.purgeExpired(now)

    suspend fun count(): Int = dao.count()
}

@Dao
interface SeenPacketDao {

    /** Ritorna il rowid inserito, `-1` se il `msg_id` era gia' presente. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(record: SeenPacketRecord): Long

    @Query("SELECT EXISTS(SELECT 1 FROM seen_packets WHERE msg_id = :msgID)")
    suspend fun exists(msgID: ByteArray): Boolean

    @Query("SELECT msg_id FROM seen_packets WHERE expires_at_ms > :nowMs ORDER BY expires_at_ms DESC LIMIT :limit")
    suspend fun activeMessageIDs(nowMs: Long, limit: Int): List<ByteArray>

    @Query("DELETE FROM seen_packets WHERE expires_at_ms <= :nowMs")
    suspend fun purgeExpired(nowMs: Long): Int

    @Query("SELECT COUNT(*) FROM seen_packets")
    suspend fun count(): Int
}
