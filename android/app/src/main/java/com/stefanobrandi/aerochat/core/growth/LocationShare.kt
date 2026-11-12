package com.stefanobrandi.aerochat.core.growth

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Posizione condivisa in chat (RELEASE_1_4_SPEC.md §B11): un normale `CHAT_TEXT`
 * `"📍 <lat 5 decimali>, <lon 5 decimali> (±<accuratezza> m)"`. Le bolle che lo contengono mostrano
 * "Apri in Mappe" (`geo:lat,lon?q=lat,lon`).
 */
object LocationShare {

    data class Point(val latitude: Double, val longitude: Double, val accuracyMeters: Int?)

    private val pattern = Regex("""📍\s*(-?\d{1,2}(?:\.\d+)?),\s*(-?\d{1,3}(?:\.\d+)?)(?:\s*\(±\s*(\d+)\s*m\))?""")

    /** Testo del messaggio (punto decimale e cifre fisse, qualunque sia la lingua del telefono). */
    fun format(latitude: Double, longitude: Double, accuracyMeters: Float?): String {
        val base = String.format(Locale.US, "📍 %.5f, %.5f", latitude, longitude)
        val accuracy = accuracyMeters?.takeIf { it.isFinite() && it >= 0f }?.roundToInt() ?: return base
        return "$base (±$accuracy m)"
    }

    /** Prima posizione valida nel testo, se c'e'. */
    fun parse(text: String): Point? {
        val match = pattern.find(text) ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val accuracy = match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        return Point(latitude, longitude, accuracy)
    }

    /** URI per l'app di mappe: `geo:lat,lon?q=lat,lon`. */
    fun geoURI(point: Point): String {
        val coordinates = String.format(Locale.US, "%.5f,%.5f", point.latitude, point.longitude)
        return "geo:$coordinates?q=$coordinates"
    }
}
