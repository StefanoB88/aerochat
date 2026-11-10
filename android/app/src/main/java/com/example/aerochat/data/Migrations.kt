package com.example.aerochat.data

import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.aerochat.data.records.ConversationType
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.MessageContentType
import com.example.aerochat.data.records.ProTier
import com.example.aerochat.data.records.ReportReason

/**
 * Schema del database.
 *
 * Su iOS lo schema e' costruito da una catena di migrazioni GRDB:
 * - `v1_initial_schema`: identity, peers, conversations, group_members, messages, messages_fts,
 *   seen_packets, relay_queue (+ indici)
 * - `v2_peer_ble_identifier`: `peers.ble_identifier`
 * - `v3_conversation_pinned`: `conversations.is_pinned NOT NULL DEFAULT 0`
 * - `v4_profiles`: `identity.avatar_image`, `identity.profile_updated_at_ms`,
 *   `peers.avatar_color_hex`, `peers.avatar_image`, `peers.profile_updated_at_ms`
 * - `v5_message_reads`: tabella `message_reads` (lettori dei messaggi di gruppo) -> Room versione 2
 * - `v6_reports` + `v7_flight_lounge` (release 1.1): tabella `reports` (segnalazioni locali) e
 *   `conversations.flight_code` (Cabina del volo) -> Room versione 3
 *
 * Su Android (installazione nuova) la catena e' collassata nella versione Room 1, che contiene lo
 * schema finale con gli stessi nomi di tabelle, colonne e indici (le entity in `data.records`).
 * Ogni cambiamento futuro va aggiunto come nuova `Migration` Room (versione 2, 3, ...) in [AppDatabaseMigrations.all],
 * mai modificando la versione gia' pubblicata.
 */
object AppDatabaseMigrations {

    /** Versione Room dello schema corrente. */
    const val schemaVersion = 3

    /**
     * 1 -> 2 (iOS `v5_message_reads`): lettori dei nostri messaggi di gruppo. La spunta blu arriva solo
     * quando i lettori coprono tutti i membri del gruppo (mittente escluso).
     */
    val MIGRATION_1_2: androidx.room.migration.Migration = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `message_reads` (`msg_id` BLOB NOT NULL, `reader_node_id` BLOB NOT NULL, " +
                    "`read_at_ms` INTEGER NOT NULL, PRIMARY KEY(`msg_id`, `reader_node_id`), " +
                    "FOREIGN KEY(`msg_id`) REFERENCES `messages`(`msg_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }

    /**
     * 2 -> 3 (iOS `v6_reports` + `v7_flight_lounge`, release 1.1):
     * - segnalazioni dei contenuti, salvate in locale e inoltrabili via email. Nessuna chiave esterna:
     *   il rapporto sopravvive al messaggio (Cabina 12 h, chat eliminata);
     * - Cabina del volo: codice canonico (`AZ|204|2026-11-10`) per icona, titolo e gate Pro.
     */
    val MIGRATION_2_3: androidx.room.migration.Migration = object : androidx.room.migration.Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reports` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `msg_id` BLOB NOT NULL, " +
                    "`conversation_id` TEXT NOT NULL, `reported_node_id` BLOB NOT NULL, `reported_nickname` TEXT, " +
                    "`reason` TEXT NOT NULL, `details` TEXT, `text_snapshot` TEXT, `created_at_ms` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reports_on_reported_node_id` ON `reports` (`reported_node_id`)")
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `flight_code` TEXT")
        }
    }

    /** Migrazioni Room successive alla versione 1, in ordine. */
    val all: Array<androidx.room.migration.Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

    /**
     * Configurazione della connessione: `synchronous = NORMAL` (con WAL: durabilita' a livello di
     * checkpoint, nessun fsync su ogni scrittura radio), come la `prepareDatabase` iOS.
     */
    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.query("PRAGMA synchronous = NORMAL").close()
        }
    }
}

/** Le enum Swift salvate come raw string sono salvate con gli stessi valori. */
class AppDatabaseConverters {

    @TypeConverter
    fun proTierToRaw(value: ProTier): String = value.rawValue

    @TypeConverter
    fun proTierFromRaw(raw: String): ProTier = ProTier.fromRawValue(raw) ?: ProTier.free

    @TypeConverter
    fun conversationTypeToRaw(value: ConversationType): String = value.rawValue

    @TypeConverter
    fun conversationTypeFromRaw(raw: String): ConversationType =
        ConversationType.fromRawValue(raw) ?: error("ConversationType sconosciuto: $raw")

    @TypeConverter
    fun contentTypeToRaw(value: MessageContentType): String = value.rawValue

    @TypeConverter
    fun contentTypeFromRaw(raw: String): MessageContentType =
        MessageContentType.fromRawValue(raw) ?: MessageContentType.system

    @TypeConverter
    fun reportReasonToRaw(value: ReportReason): String = value.rawValue

    @TypeConverter
    fun reportReasonFromRaw(raw: String): ReportReason = ReportReason.fromRawValue(raw) ?: ReportReason.other

    @TypeConverter
    fun deliveryStatusToRaw(value: DeliveryStatus): String = value.rawValue

    @TypeConverter
    fun deliveryStatusFromRaw(raw: String): DeliveryStatus =
        DeliveryStatus.fromRawValue(raw) ?: DeliveryStatus.queued
}
