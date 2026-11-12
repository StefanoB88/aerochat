package com.stefanobrandi.aerochat.core.growth

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Prima partita gratis (RELEASE_1_4_SPEC.md §B2): oltre al Pass, **una partita gratis al giorno e una per ogni
 * volo** per chi la avvia (accettare e' sempre gratis). Chiave di consumo `flight:<flight_code>` con un volo
 * attivo, altrimenti `day:<yyyy-MM-dd>` nel fuso locale. Si consuma quando l'invito parte.
 */
object FreeGameAllowance {

    /** Chiavi ricordate al massimo (le piu' vecchie non servono piu'). */
    const val maxRememberedKeys = 60

    private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    /** Chiave della partita gratis per adesso. */
    fun key(activeFlightCode: String?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val flight = activeFlightCode?.trim().orEmpty()
        if (flight.isNotEmpty()) return "flight:$flight"
        return "day:" + dayFormatter.format(Instant.ofEpochMilli(now).atZone(zone))
    }

    /** La partita gratis di [key] e' ancora disponibile? */
    fun isAvailable(key: String, used: Set<String>): Boolean = !used.contains(key)

    /** Insieme aggiornato dopo il consumo di [key] (limitato alle chiavi piu' recenti). */
    fun consume(key: String, used: Set<String>): Set<String> {
        val updated = LinkedHashSet(used)
        updated.remove(key)
        updated.add(key)
        while (updated.size > maxRememberedKeys) updated.remove(updated.first())
        return updated
    }
}
