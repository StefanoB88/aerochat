package com.example.aerochat.data.repositories

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.withTransaction
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.IdentityRecord
import com.example.aerochat.data.records.ProTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

class IdentityRepository(val database: AppDatabase) {

    private val dao: IdentityDao get() = database.identityDao()

    suspend fun fetch(): IdentityRecord? = dao.fetchOne(IdentityRecord.singletonID)

    suspend fun save(record: IdentityRecord) {
        dao.upsert(record.copy(id = IdentityRecord.singletonID))
    }

    suspend fun updateNickname(nickname: String) {
        dao.updateNickname(IdentityRecord.singletonID, nickname)
    }

    /**
     * Salva nickname, colore e foto profilo e incrementa la versione annunciata ai contatti.
     * @param at epoch ms.
     */
    suspend fun updateProfile(
        nickname: String,
        avatarColorHex: String,
        avatarImage: ByteArray?,
        at: Long = System.currentTimeMillis(),
    ): IdentityRecord? = database.withTransaction {
        val record = dao.fetchOne(IdentityRecord.singletonID) ?: return@withTransaction null
        val updated = record.copy(
            nickname = nickname,
            avatarColorHex = avatarColorHex,
            avatarImage = avatarImage,
            // Sempre crescente, anche con l'orologio spostato indietro: i contatti scartano le versioni vecchie.
            profileUpdatedAtMs = maxOf(at, (record.profileUpdatedAtMs ?: 0L) + 1),
        )
        dao.update(updated)
        updated
    }

    /** Aggiorna lo stato Pro cache-ato (chiamato da `EntitlementManager`). */
    suspend fun updateProStatus(tier: ProTier, validUntilMs: Long?, receiptJWS: String?) {
        database.withTransaction {
            val record = dao.fetchOne(IdentityRecord.singletonID) ?: return@withTransaction
            dao.update(record.copy(proTier = tier, proValidUntilMs = validUntilMs, proReceiptJWS = receiptJWS))
        }
    }

    /** Stream dell'identita' per la UI (nickname, stato Pro): valore iniziale subito, poi uno per ogni modifica. */
    fun observe(): Flow<IdentityRecord?> = dao.observeOne(IdentityRecord.singletonID).distinctUntilChanged()
}

@Dao
interface IdentityDao {

    @Query("SELECT * FROM identity WHERE id = :id")
    suspend fun fetchOne(id: Long): IdentityRecord?

    @Query("SELECT * FROM identity WHERE id = :id")
    fun observeOne(id: Long): Flow<IdentityRecord?>

    @Upsert
    suspend fun upsert(record: IdentityRecord)

    @Update
    suspend fun update(record: IdentityRecord)

    @Query("UPDATE identity SET nickname = :nickname WHERE id = :id")
    suspend fun updateNickname(id: Long, nickname: String)
}
