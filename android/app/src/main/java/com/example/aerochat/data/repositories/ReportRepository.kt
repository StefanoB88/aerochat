package com.example.aerochat.data.repositories

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.ReportRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Segnalazioni salvate in locale (schermata Impostazioni › Segnalazioni). */
class ReportRepository(val database: AppDatabase) {

    private val dao: ReportDao get() = database.reportDao()

    /** Inserisce il rapporto e lo restituisce con l'`id` assegnato. */
    suspend fun insert(report: ReportRecord): ReportRecord {
        val rowID = dao.insert(report.copy(id = null))
        return report.copy(id = rowID)
    }

    /** Piu' recenti per prime. */
    suspend fun fetchAll(): List<ReportRecord> = dao.fetchAll()

    suspend fun count(): Int = dao.count()

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    fun observeAll(): Flow<List<ReportRecord>> = dao.observeAll().distinctUntilChanged()
}

@Dao
interface ReportDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(report: ReportRecord): Long

    @Query("SELECT * FROM reports ORDER BY created_at_ms DESC")
    suspend fun fetchAll(): List<ReportRecord>

    @Query("SELECT * FROM reports ORDER BY created_at_ms DESC")
    fun observeAll(): Flow<List<ReportRecord>>

    @Query("SELECT COUNT(*) FROM reports")
    suspend fun count(): Int

    @Query("DELETE FROM reports WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
