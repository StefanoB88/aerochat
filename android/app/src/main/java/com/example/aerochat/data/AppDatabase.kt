package com.example.aerochat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.GroupMemberRecord
import com.example.aerochat.data.records.IdentityRecord
import com.example.aerochat.data.records.MessageFtsRecord
import com.example.aerochat.data.records.MessageReadRecord
import com.example.aerochat.data.records.MessageRecord
import com.example.aerochat.data.records.PeerRecord
import com.example.aerochat.data.records.RelayQueueRecord
import com.example.aerochat.data.records.ReportRecord
import com.example.aerochat.data.records.SeenPacketRecord
import com.example.aerochat.data.repositories.ConversationDao
import com.example.aerochat.data.repositories.ConversationRepository
import com.example.aerochat.data.repositories.GroupMemberDao
import com.example.aerochat.data.repositories.IdentityDao
import com.example.aerochat.data.repositories.IdentityRepository
import com.example.aerochat.data.repositories.MessageDao
import com.example.aerochat.data.repositories.MessageRepository
import com.example.aerochat.data.repositories.PeerDao
import com.example.aerochat.data.repositories.PeerRepository
import com.example.aerochat.data.repositories.RelayQueueDao
import com.example.aerochat.data.repositories.RelayQueueStore
import com.example.aerochat.data.repositories.ReportDao
import com.example.aerochat.data.repositories.ReportRepository
import com.example.aerochat.data.repositories.SeenPacketDao
import com.example.aerochat.data.repositories.SeenPacketStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Database SQLite dell'app (Room).
 *
 * - File DB in modalita' **WAL**: le letture della UI (Flow) non vengono bloccate dalle scritture del
 *   `MeshRouterActor` in background.
 * - Le transazioni che coinvolgono piu' repository si fanno con `database.withTransaction { ... }`
 *   (`androidx.room.withTransaction`).
 */
@Database(
    entities = [
        IdentityRecord::class,
        PeerRecord::class,
        ConversationRecord::class,
        GroupMemberRecord::class,
        MessageRecord::class,
        MessageFtsRecord::class,
        SeenPacketRecord::class,
        RelayQueueRecord::class,
        MessageReadRecord::class,
        ReportRecord::class,
    ],
    version = AppDatabaseMigrations.schemaVersion,
    exportSchema = true,
)
@TypeConverters(AppDatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    // MARK: - DAO (uso interno dei repository)

    abstract fun identityDao(): IdentityDao
    abstract fun peerDao(): PeerDao
    abstract fun conversationDao(): ConversationDao
    abstract fun groupMemberDao(): GroupMemberDao
    abstract fun messageDao(): MessageDao
    abstract fun seenPacketDao(): SeenPacketDao
    abstract fun relayQueueDao(): RelayQueueDao
    abstract fun reportDao(): ReportDao

    // MARK: - Repository

    val identities: IdentityRepository get() = IdentityRepository(this)
    val peers: PeerRepository get() = PeerRepository(this)
    val conversations: ConversationRepository get() = ConversationRepository(this)
    val messages: MessageRepository get() = MessageRepository(this)
    val seenPackets: SeenPacketStore get() = SeenPacketStore(this)
    val relayQueue: RelayQueueStore get() = RelayQueueStore(this)
    val reports: ReportRepository get() = ReportRepository(this)

    /** Percorso del file (diagnostica); `":memory:"` per il DB temporaneo. */
    val path: String
        get() = openHelper.readableDatabase.path ?: ":memory:"

    // MARK: - Diagnostica

    /** Nomi di tabelle e tabelle virtuali presenti (esclusi gli oggetti interni di SQLite). */
    suspend fun tableNames(): Set<String> = withContext(Dispatchers.IO) {
        val names = mutableSetOf<String>()
        openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
            .use { cursor -> while (cursor.moveToNext()) names += cursor.getString(0) }
        names
    }

    /** `true` se il database gira in modalita' WAL. */
    suspend fun isWALEnabled(): Boolean = withContext(Dispatchers.IO) {
        openHelper.readableDatabase.query("PRAGMA journal_mode").use { cursor ->
            cursor.moveToFirst() && (cursor.getString(0) ?: "").lowercase() == "wal"
        }
    }

    // MARK: - Manutenzione

    /** Job periodico di pulizia: pacchetti visti scaduti, coda relay scaduta, Lounge oltre le 12h. `now` in epoch ms. */
    suspend fun runMaintenance(now: Long = System.currentTimeMillis()) {
        withTransaction {
            seenPacketDao().purgeExpired(now)
            relayQueueDao().purgeExpired(now)
            MessageRecord.purgeExpiredLoungeMessages(this, nowMs = now)
            ConversationRecord.purgeExpired(this, nowMs = now)
        }
    }

    companion object {

        /** Nome del file del database condiviso (come iOS `aeromesh.sqlite`). */
        const val fileName = "aeromesh.sqlite"

        @Volatile
        private var shared: AppDatabase? = null

        /** Database condiviso dell'app (singleton, file `databases/aeromesh.sqlite`). */
        fun makeShared(context: Context): AppDatabase =
            shared ?: synchronized(this) {
                shared ?: makeFile(context, fileName).also { shared = it }
            }

        /** Database usa-e-getta in memoria (test, preview). */
        fun makeTemporary(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
                .addCallback(AppDatabaseMigrations.callback)
                .build()

        /** Apre (e migra) un database su file con il nome indicato (non singleton). */
        fun makeFile(context: Context, name: String): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*AppDatabaseMigrations.all)
                .addCallback(AppDatabaseMigrations.callback)
                .build()
    }
}
