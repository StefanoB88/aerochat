package com.stefanobrandi.aerochat.core.flight

import android.content.Context
import androidx.annotation.StringRes
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.games.GameOutcome
import com.stefanobrandi.aerochat.core.games.GameSessionState
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.data.AppDatabase
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.MessageContentType

/** Numeri del viaggio per la cartolina condivisibile: solo conteggi, nessun contenuto ne' nome altrui. */
data class FlightStats(
    val period: Period,
    val messagesSent: Int = 0,
    val messagesReceived: Int = 0,
    /** Persone diverse da cui abbiamo ricevuto almeno un messaggio (chat, gruppi e Cabina). */
    val peopleMet: Int = 0,
    val photos: Int = 0,
    val voiceNotes: Int = 0,
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    /** Salti massimi attraversati da un messaggio ricevuto (quanto era lunga la catena di telefoni). */
    val longestHop: Int = 0,
    val loungeMessages: Int = 0,
    /** Titoli delle Cabine del volo attive (es. "AZ 204 · FCO → LHR · 10 nov"). */
    val flightTitles: List<String> = emptyList(),
) {

    enum class Period(val rawValue: String, @StringRes val titleRes: Int) {
        day("day", R.string.core11_stats_day),
        trip("trip", R.string.core11_stats_trip),
        all("all", R.string.core11_stats_all);

        val id: String get() = rawValue

        fun title(context: Context): String = context.getString(titleRes)

        /** Inizio del periodo (epoch ms). */
        fun since(now: Long = System.currentTimeMillis()): Long = when (this) {
            day -> now - 24 * 3_600_000L
            trip -> now - 72 * 3_600_000L
            all -> Long.MIN_VALUE
        }
    }

    val totalMessages: Int get() = messagesSent + messagesReceived

    companion object {

        suspend fun compute(database: AppDatabase, period: Period, now: Long = System.currentTimeMillis()): FlightStats {
            var sent = 0
            var received = 0
            var photos = 0
            var voiceNotes = 0
            var gamesPlayed = 0
            var gamesWon = 0
            var longestHop = 0
            var loungeMessages = 0
            val senders = HashSet<String>()
            for (message in database.messages.fetch(since = period.since(now))) {
                if (message.isOutgoing) {
                    sent += 1
                } else {
                    received += 1
                    senders += message.senderNodeID.toHex()
                    longestHop = maxOf(longestHop, message.hopCountReceived ?: 0)
                }
                if (message.conversationID == ConversationRecord.cabinLoungeID) loungeMessages += 1
                when (message.contentType) {
                    MessageContentType.image -> photos += 1
                    MessageContentType.audio -> voiceNotes += 1
                    MessageContentType.game -> {
                        gamesPlayed += 1
                        val state = message.gameState?.let { runCatching { GameSessionState.decode(it) }.getOrNull() }
                        if (state?.outcome == GameOutcome.iWon) gamesWon += 1
                    }
                    MessageContentType.text, MessageContentType.system -> Unit
                }
            }
            val titles = runCatching { database.conversations.activeFlightLounges(now) }.getOrNull()?.map { it.title } ?: emptyList()
            return FlightStats(
                period = period,
                messagesSent = sent,
                messagesReceived = received,
                peopleMet = senders.size,
                photos = photos,
                voiceNotes = voiceNotes,
                gamesPlayed = gamesPlayed,
                gamesWon = gamesWon,
                longestHop = longestHop,
                loungeMessages = loungeMessages,
                flightTitles = titles,
            )
        }
    }
}
