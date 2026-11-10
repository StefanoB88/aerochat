package com.example.aerochat.data.records

import android.content.Context
import androidx.annotation.StringRes
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.aerochat.R
import com.example.aerochat.core.protocol.toHex
import java.text.DateFormat
import java.util.Date

/** Motivo di una segnalazione (contenuti generati dagli utenti). Salvato con il raw value di iOS. */
enum class ReportReason(val rawValue: String, @StringRes val titleRes: Int, private val italianTitle: String) {
    spam("spam", R.string.core11_report_spam, "Spam o pubblicità"),
    harassment("harassment", R.string.core11_report_harassment, "Molestie o bullismo"),
    hateSpeech("hate_speech", R.string.core11_report_hate_speech, "Odio o discriminazione"),
    sexualContent("sexual_content", R.string.core11_report_sexual_content, "Contenuto sessuale"),
    violence("violence", R.string.core11_report_violence, "Violenza o minacce"),
    other("other", R.string.core11_report_other, "Altro");

    /** `Identifiable.id` di Swift. */
    val id: String get() = rawValue

    /** Titolo localizzato (in italiano senza `Context`, es. nei test JVM). */
    fun title(context: Context?): String = context?.getString(titleRes) ?: italianTitle

    companion object {
        fun fromRawValue(rawValue: String): ReportReason? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * Segnalazione di un messaggio, salvata sul dispositivo (la mesh non ha un server): il mittente viene
 * bloccato e l'utente puo' inoltrare il rapporto via email al team di sicurezza ([com.example.aerochat.core.moderation.AppLinks.safetyEmail]).
 */
@Entity(
    tableName = ReportRecord.databaseTableName,
    indices = [Index(value = ["reported_node_id"], name = "index_reports_on_reported_node_id")],
)
data class ReportRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long? = null,
    @ColumnInfo(name = "msg_id")
    val msgID: ByteArray,
    @ColumnInfo(name = "conversation_id")
    val conversationID: String,
    @ColumnInfo(name = "reported_node_id")
    val reportedNodeID: ByteArray,
    @ColumnInfo(name = "reported_nickname")
    val reportedNickname: String?,
    @ColumnInfo(name = "reason")
    val reason: ReportReason,
    @ColumnInfo(name = "details")
    val details: String?,
    /** Copia del testo segnalato: il messaggio originale puo' sparire (Cabina 12 h, blocco). */
    @ColumnInfo(name = "text_snapshot")
    val textSnapshot: String?,
    /** epoch ms. */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
) {

    /** Swift `init(message:reason:details:createdAt:)`; `createdAt` in epoch ms. */
    @Ignore
    constructor(
        message: MessageRecord,
        reason: ReportReason,
        details: String?,
        createdAt: Long = System.currentTimeMillis(),
    ) : this(
        id = null,
        msgID = message.msgID,
        conversationID = message.conversationID,
        reportedNodeID = message.senderNodeID,
        reportedNickname = message.senderNickname,
        reason = reason,
        details = details?.trim()?.takeIf { it.isNotEmpty() }?.let { prefixCodePoints(it, 500) },
        textSnapshot = message.text?.let { prefixCodePoints(it, 1_000) },
        createdAtMs = createdAt,
    )

    /** Testo del rapporto da inoltrare via email (nessun dato personale oltre a nickname e id di nodo). */
    fun emailBody(context: Context?): String {
        fun string(res: Int, italian: String, vararg args: Any): String =
            context?.getString(res, *args) ?: String.format(italian, *args)
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(createdAtMs))
        val lines = mutableListOf(
            context?.getString(R.string.core11_report_email_title) ?: "Segnalazione AeroChat",
            string(R.string.core11_report_email_reason, "Motivo: %1\$s", reason.title(context)),
            string(R.string.core11_report_email_user, "Utente segnalato: %1\$s (%2\$s)", reportedNickname ?: "-", reportedNodeID.toHex()),
            string(R.string.core11_report_email_message, "Messaggio: %1\$s", msgID.toHex()),
            string(R.string.core11_report_email_date, "Data: %1\$s", date),
        )
        details?.let { lines += string(R.string.core11_report_email_details, "Dettagli: %1\$s", it) }
        textSnapshot?.let { lines += string(R.string.core11_report_email_text, "Testo: %1\$s", it) }
        return lines.joinToString("\n")
    }

    /** Swift `emailBody` (testo italiano, senza risorse). */
    val emailBody: String get() = emailBody(null)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReportRecord) return false
        return id == other.id && msgID.contentEquals(other.msgID) && conversationID == other.conversationID &&
            reportedNodeID.contentEquals(other.reportedNodeID) && reportedNickname == other.reportedNickname &&
            reason == other.reason && details == other.details && textSnapshot == other.textSnapshot &&
            createdAtMs == other.createdAtMs
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + msgID.contentHashCode()
        result = 31 * result + conversationID.hashCode()
        result = 31 * result + reportedNodeID.contentHashCode()
        result = 31 * result + (reportedNickname?.hashCode() ?: 0)
        result = 31 * result + reason.hashCode()
        result = 31 * result + (details?.hashCode() ?: 0)
        result = 31 * result + (textSnapshot?.hashCode() ?: 0)
        result = 31 * result + createdAtMs.hashCode()
        return result
    }

    companion object {
        const val databaseTableName = "reports"

        /** Primi [count] caratteri (code point, come `String.prefix` di Swift a meno dei cluster). */
        private fun prefixCodePoints(text: String, count: Int): String {
            if (text.codePointCount(0, text.length) <= count) return text
            return text.substring(0, text.offsetByCodePoints(0, count))
        }
    }
}
