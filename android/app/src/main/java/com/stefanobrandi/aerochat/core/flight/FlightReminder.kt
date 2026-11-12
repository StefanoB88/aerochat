package com.stefanobrandi.aerochat.core.flight

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stefanobrandi.aerochat.core.mesh.LocalNotificationCenter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Promemoria prima del volo (RELEASE_1_4_SPEC.md §B7): aggiungendo una carta d'imbarco con giorno di partenza
 * futuro, notifica locale alle 07:00 del giorno di partenza (fuso del telefono). "Oggi voli con AZ204: invita
 * ora chi viaggia con te, in aereo non potra' scaricare AeroChat." Tocco -> foglio "Invita amici".
 */
object FlightReminder {

    /** Ora del promemoria nel giorno di partenza. */
    val reminderTime: LocalTime = LocalTime.of(7, 0)

    /**
     * Istante del promemoria (epoch ms) per una partenza il giorno [departureDay] (mezzanotte UTC, come
     * `BoardingPass.departureDay`), oppure `null` se la partenza non e' futura o le 07:00 sono gia' passate.
     */
    fun fireAt(departureDay: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val day = Instant.ofEpochMilli(departureDay).atZone(ZoneOffset.UTC).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (day.isBefore(today)) return null
        val at = LocalDate.from(day).atTime(reminderTime).atZone(zone).toInstant().toEpochMilli()
        // Partenza oggi con le 07:00 gia' passate: nessun promemoria.
        return if (at > now) at else null
    }

    /** "AZ|204|2026-11-10" -> "AZ204". */
    fun label(canonicalKey: String): String {
        val parts = canonicalKey.split('|')
        return if (parts.size >= 2) parts[0] + parts[1] else canonicalKey
    }

    /** Programma (o riprogramma) il promemoria della carta d'imbarco. Ritorna `true` se programmato. */
    fun schedule(context: Context, pass: BoardingPass, now: Long = System.currentTimeMillis()): Boolean {
        val at = fireAt(pass.departureDay, now) ?: return false
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        val intent = Intent(context, FlightReminderReceiver::class.java).putExtra(extraFlight, label(pass.canonicalKey))
        val pending = PendingIntent.getBroadcast(
            context, pass.canonicalKey.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Allarme inesatto (nessun permesso SCHEDULE_EXACT_ALARM): qualche minuto di ritardo va bene.
        return runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }.isSuccess
    }

    internal const val extraFlight = "flight"
}

/** Allarme del promemoria: pubblica la notifica "Oggi voli con ...". */
class FlightReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val flight = intent.getStringExtra(FlightReminder.extraFlight) ?: return
        LocalNotificationCenter(context.applicationContext).notifyFlightReminder(flight)
    }
}
