package com.example.aerochat.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.aerochat.data.records.DeliveryStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migrazioni Room (equivalente della catena di migrazioni GRDB iOS).
 *
 * Il database v1 viene ricostruito dallo schema esportato `app/schemas/.../1.json`; poi lo apre il
 * builder reale dell'app ([AppDatabase.makeFile]), che applica [AppDatabaseMigrations.all] e **valida**
 * lo schema migrato contro le entity correnti (Room lancia `IllegalStateException` se non coincide).
 * Stessa verifica di `MigrationTestHelper.runMigrationsAndValidate`, senza dover impacchettare gli
 * schemi come asset (Robolectric non vede gli asset del source set di test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTests {

    private val databaseName = "migration-test.sqlite"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    /** Crea il file del database con lo schema esportato della versione [version] (tabelle, indici, FTS, trigger). */
    private fun createDatabase(version: Int): SQLiteDatabase {
        val schemaFile = File("schemas/${AppDatabase::class.java.name}/$version.json")
        assertTrue("schema esportato mancante: ${schemaFile.absolutePath}", schemaFile.exists())
        val database = JSONObject(schemaFile.readText()).getJSONObject("database")
        val path = context.getDatabasePath(databaseName).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            entity.optJSONArray("indices")?.let { indices ->
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            entity.optJSONArray("contentSyncTriggers")?.let { triggers ->
                for (j in 0 until triggers.length()) db.execSQL(triggers.getString(j))
            }
        }
        val setup = database.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
        return db
    }

    @Test
    fun migrate1To2CreatesMessageReadsAndKeepsData() = runBlocking {
        val msgID = ByteArray(16) { it.toByte() }
        val reader = ByteArray(8) { 7 }
        createDatabase(1).use { db ->
            db.execSQL(
                "INSERT INTO conversations (id, type, title, recipient_id, created_at_ms, unread_count, is_pinned) " +
                    "VALUES ('g1', 'group', 'Volo', ?, 1, 0, 0)",
                arrayOf<Any>(ByteArray(8)),
            )
            db.execSQL(
                "INSERT INTO messages (msg_id, conversation_id, sender_node_id, content_type, is_outgoing, " +
                    "delivery_status, created_at_ms) VALUES (?, 'g1', ?, 'text', 1, 'delivered_ack', 2)",
                arrayOf<Any>(msgID, ByteArray(8)),
            )
        }

        val migrated = AppDatabase.makeFile(context, databaseName)
        try {
            assertEquals("catena completa fino alla versione corrente", AppDatabaseMigrations.schemaVersion, migrated.openHelper.readableDatabase.version)
            assertTrue(migrated.tableNames().contains("message_reads"))
            assertEquals("i messaggi esistenti restano", DeliveryStatus.deliveredAck, migrated.messages.fetch(msgID)?.deliveryStatus)

            migrated.openHelper.writableDatabase.execSQL(
                "INSERT INTO message_reads (msg_id, reader_node_id, read_at_ms) VALUES (?, ?, 3)",
                arrayOf<Any>(msgID, reader),
            )
            assertEquals(1, migrated.messages.readerNodeIDs(msgID).size)
            migrated.messages.delete(msgID)
            assertTrue("i lettori seguono il messaggio eliminato", migrated.messages.readerNodeIDs(msgID).isEmpty())
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrate2To3AddsReportsAndFlightCode() = runBlocking {
        createDatabase(2).use { db ->
            db.execSQL(
                "INSERT INTO conversations (id, type, title, recipient_id, created_at_ms, unread_count, is_pinned) " +
                    "VALUES ('g1', 'group', 'Volo', ?, 1, 3, 0)",
                arrayOf<Any>(ByteArray(8)),
            )
        }

        val migrated = AppDatabase.makeFile(context, databaseName)
        try {
            assertEquals(AppDatabaseMigrations.schemaVersion, migrated.openHelper.readableDatabase.version)
            assertTrue(migrated.tableNames().contains("reports"))
            val existing = migrated.conversations.fetch(id = "g1")
            assertEquals("i gruppi esistenti restano, senza codice volo", null, existing?.flightCode)
            assertEquals(3, migrated.conversations.totalUnread())
            assertEquals(0, migrated.reports.count())
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migrate3To4AddsRevisionColumns() = runBlocking {
        val msgID = ByteArray(16) { (it + 1).toByte() }
        createDatabase(3).use { db ->
            db.execSQL(
                "INSERT INTO conversations (id, type, title, recipient_id, created_at_ms, unread_count, is_pinned) " +
                    "VALUES ('g1', 'group', 'Volo', ?, 1, 1, 0)",
                arrayOf<Any>(ByteArray(8)),
            )
            db.execSQL(
                "INSERT INTO messages (msg_id, conversation_id, sender_node_id, content_type, text, is_outgoing, " +
                    "delivery_status, created_at_ms) VALUES (?, 'g1', ?, 'text', 'Ciao', 0, 'delivered_ack', 2)",
                arrayOf<Any>(msgID, ByteArray(8)),
            )
        }

        val migrated = AppDatabase.makeFile(context, databaseName)
        try {
            assertEquals(4, migrated.openHelper.readableDatabase.version)
            val existing = migrated.messages.fetch(msgID)
            assertEquals("Ciao", existing?.text)
            assertEquals("mai modificato", null, existing?.editedAtMs)
            assertEquals("non cancellato", false, existing?.isDeleted)
            assertTrue(migrated.messages.applyEdit(msgID, newText = "Ciao a tutti", editedAtMs = 10))
            assertEquals(10L, migrated.messages.fetch(msgID)?.editedAtMs)
        } finally {
            migrated.close()
        }
    }
}
