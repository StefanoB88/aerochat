package com.example.aerochat.data.repositories

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.RelayQueueRecord

/**
 * Coda Store-and-Forward: max 200 pacchetti, max 2 MB totali, scadenza 6 ore.
 * Quando i limiti vengono superati si scartano prima i pacchetti piu' vecchi (FIFO).
 */
class RelayQueueStore(
    val database: AppDatabase,
    val maxCount: Int = MeshProtocol.relayQueueMaxCount,
    val maxTotalBytes: Int = MeshProtocol.relayQueueMaxBytes,
) {

    data class Stats(val count: Int, val totalBytes: Int)

    sealed class Failure : Exception() {
        data class PacketTooLarge(val sizeBytes: Int) : Failure()
    }

    private val dao: RelayQueueDao get() = database.relayQueueDao()

    /**
     * Accoda un envelope serializzato. Un `msg_id` gia' presente viene ignorato.
     * @param now epoch ms.
     * @throws Failure.PacketTooLarge se il pacchetto supera da solo `maxTotalBytes`.
     */
    suspend fun enqueue(record: RelayQueueRecord, now: Long = System.currentTimeMillis()) {
        if (record.sizeBytes > maxTotalBytes) throw Failure.PacketTooLarge(record.sizeBytes)
        database.withTransaction {
            // 1. Pulizia scaduti.
            dao.purgeExpired(now)
            // 2. Idempotenza.
            if (dao.exists(record.msgID)) return@withTransaction
            // 3. Eviction FIFO finche' il nuovo pacchetto non rientra nei limiti.
            var count = dao.count()
            var totalBytes = dao.totalBytes()
            while (count > 0 && (count + 1 > maxCount || totalBytes + record.sizeBytes > maxTotalBytes)) {
                val oldest = dao.oldest() ?: break
                dao.deleteById(oldest.msgID)
                count -= 1
                totalBytes -= oldest.sizeBytes
            }
            dao.insert(record)
        }
    }

    /** Pacchetti pronti per la ritrasmissione (FIFO), esclusi quelli scaduti. `now` in epoch ms. */
    suspend fun pending(now: Long = System.currentTimeMillis(), limit: Int? = null): List<RelayQueueRecord> =
        dao.pending(now, limit ?: -1)

    /** Pacchetti destinati a uno specifico `recipient_id` (peer appena tornato a portata). `now` in epoch ms. */
    suspend fun pending(recipientID: ByteArray, now: Long = System.currentTimeMillis()): List<RelayQueueRecord> =
        dao.pendingFor(recipientID, now)

    suspend fun remove(msgID: ByteArray) {
        dao.deleteById(msgID)
    }

    suspend fun remove(msgIDs: List<ByteArray>) {
        if (msgIDs.isEmpty()) return
        database.withTransaction {
            for (chunk in msgIDs.chunked(500)) dao.deleteByIds(chunk)
        }
    }

    /** Elimina i pacchetti scaduti e ne ritorna il numero. `now` in epoch ms. */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int = dao.purgeExpired(now)

    suspend fun stats(): Stats = database.withTransaction {
        Stats(count = dao.count(), totalBytes = dao.totalBytes())
    }
}

@Dao
interface RelayQueueDao {

    @Query("DELETE FROM relay_queue WHERE expires_at_ms <= :nowMs")
    suspend fun purgeExpired(nowMs: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM relay_queue WHERE msg_id = :msgID)")
    suspend fun exists(msgID: ByteArray): Boolean

    @Query("SELECT COUNT(*) FROM relay_queue")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM relay_queue")
    suspend fun totalBytes(): Int

    @Query("SELECT * FROM relay_queue ORDER BY enqueued_at_ms LIMIT 1")
    suspend fun oldest(): RelayQueueRecord?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: RelayQueueRecord)

    @Query("DELETE FROM relay_queue WHERE msg_id = :msgID")
    suspend fun deleteById(msgID: ByteArray): Int

    @Query("DELETE FROM relay_queue WHERE msg_id IN (:msgIDs)")
    suspend fun deleteByIds(msgIDs: List<ByteArray>): Int

    /** `limit` negativo = nessun limite (semantica SQLite). */
    @Query("SELECT * FROM relay_queue WHERE expires_at_ms > :nowMs ORDER BY enqueued_at_ms LIMIT :limit")
    suspend fun pending(nowMs: Long, limit: Int): List<RelayQueueRecord>

    @Query(
        "SELECT * FROM relay_queue WHERE recipient_id = :recipientID AND expires_at_ms > :nowMs " +
            "ORDER BY enqueued_at_ms",
    )
    suspend fun pendingFor(recipientID: ByteArray, nowMs: Long): List<RelayQueueRecord>
}
