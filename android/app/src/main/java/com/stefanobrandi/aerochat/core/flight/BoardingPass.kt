package com.stefanobrandi.aerochat.core.flight

import com.stefanobrandi.aerochat.core.protocol.uuidFromRawBytes
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Carta d'imbarco letta dal codice a barre IATA BCBP (PDF417 / Aztec / QR) o inserita a mano.
 * Dal volo + data si deriva in modo deterministico la "Cabina del volo": un gruppo cifrato che tutti
 * i passeggeri dello stesso volo ottengono senza scambiarsi QR (derivazione identica a iOS, byte per byte).
 *
 * Costruzione (come gli `init` Swift):
 * - `BoardingPass(carrier = ..., flightNumber = ..., origin, destination, seat, departureDay)`
 * - [fromFlightText] (inserimento manuale, `init?(flightText:departureDay:)`)
 * - [fromBCBP] (codice a barre, `init(bcbp:now:)`, lancia [ParseError])
 */
class BoardingPass private constructor(
    /** Codice IATA della compagnia (2-3 caratteri, es. "AZ"). */
    val carrier: String,
    /** Numero di volo senza zeri iniziali (es. "204"). */
    val flightNumber: String,
    val origin: String?,
    val destination: String?,
    val seat: String?,
    /** Giorno di partenza: mezzanotte UTC, epoch ms. */
    val departureDay: Long,
) {

    sealed class ParseError(message: String) : Exception(message) {
        data object NotBoardingPass : ParseError("not a boarding pass") {
            private fun readResolve(): Any = NotBoardingPass
        }
        data object InvalidFlight : ParseError("invalid flight") {
            private fun readResolve(): Any = InvalidFlight
        }
        data object InvalidDate : ParseError("invalid date") {
            private fun readResolve(): Any = InvalidDate
        }
    }

    /** Etichetta leggibile: "AZ 204". */
    val flightCode: String get() = "$carrier $flightNumber"

    /** Tratta leggibile: "FCO → LHR" (vuota se sconosciuta). */
    val routeText: String
        get() {
            val origin = origin ?: return ""
            val destination = destination ?: return ""
            return "$origin → $destination"
        }

    /** Chiave canonica condivisa con iOS: `CARRIER|FLIGHT|yyyy-MM-dd` (giorno del calendario UTC). */
    val canonicalKey: String
        get() = "${carrier.uppercase(Locale.ROOT)}|$flightNumber|${dayFormatter.format(Instant.ofEpochMilli(departureDay))}"

    /** Titolo della conversazione: "AZ 204 · FCO → LHR · 10 nov" (giorno e mese abbreviato nella lingua del telefono). */
    fun loungeTitle(locale: Locale = Locale.getDefault()): String {
        val day = DateTimeFormatter.ofPattern("d MMM", locale).withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(departureDay)).trimEnd('.')
        val route = routeText
        return if (route.isEmpty()) "$flightCode · $day" else "$flightCode · $route · $day"
    }

    /** Swift `loungeTitle` (lingua del telefono). */
    val loungeTitle: String get() = loungeTitle(Locale.getDefault())

    /** La Cabina del volo scade 36 ore dopo la mezzanotte del giorno di partenza (copre voli notturni e scali). Epoch ms. */
    val loungeExpiry: Long get() = departureDay + 36 * 3_600_000L

    // MARK: - Cabina del volo (derivazione condivisa con iOS)

    /** Chiave di gruppo: `SHA256("AeroMesh-Flight-Key-v1|" + canonicalKey)` (32 byte). */
    val loungeSecretKey: ByteArray
        get() = sha256("AeroMesh-Flight-Key-v1|$canonicalKey".toByteArray(Charsets.UTF_8))

    /**
     * UUID del gruppo: primi 16 byte di `SHA256("AeroMesh-Flight-UUID-v1|" + canonicalKey)` con i bit di
     * versione (4) e variante (RFC 4122) forzati, cosi' resta uno UUID valido su entrambe le piattaforme.
     */
    val loungeGroupID: UUID
        get() {
            val bytes = sha256("AeroMesh-Flight-UUID-v1|$canonicalKey".toByteArray(Charsets.UTF_8)).copyOf(16)
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            return uuidFromRawBytes(bytes) ?: error("16 byte sempre validi")
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoardingPass) return false
        return carrier == other.carrier && flightNumber == other.flightNumber && origin == other.origin &&
            destination == other.destination && seat == other.seat && departureDay == other.departureDay
    }

    override fun hashCode(): Int {
        var result = carrier.hashCode()
        result = 31 * result + flightNumber.hashCode()
        result = 31 * result + (origin?.hashCode() ?: 0)
        result = 31 * result + (destination?.hashCode() ?: 0)
        result = 31 * result + (seat?.hashCode() ?: 0)
        result = 31 * result + departureDay.hashCode()
        return result
    }

    override fun toString(): String = "BoardingPass($canonicalKey, origin=$origin, destination=$destination, seat=$seat)"

    companion object {

        private val dayFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(ZoneOffset.UTC)

        /** Memberwise init di Swift: `departureDay` (epoch ms) viene ricondotto alla mezzanotte UTC. */
        operator fun invoke(
            carrier: String,
            flightNumber: String,
            origin: String?,
            destination: String?,
            seat: String?,
            departureDay: Long,
        ): BoardingPass = BoardingPass(carrier, flightNumber, origin, destination, seat, startOfDay(departureDay))

        // MARK: Inserimento manuale

        /** Da "AZ204", "AZ 0204", "az-204" + data (epoch ms); `null` se il numero di volo non e' valido. */
        fun fromFlightText(flightText: String, departureDay: Long): BoardingPass? {
            val cleaned = flightText.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
            val split = cleaned.indexOfFirst { it.isDigit() }
            if (split < 0) return null
            val carrier = cleaned.substring(0, split)
            val digits = cleaned.substring(split)
            if (carrier.length !in 2..3 || digits.length !in 1..4 || !digits.all { it.isDigit() }) return null
            val number = digits.toIntOrNull() ?: return null
            if (number <= 0) return null
            return BoardingPass(carrier, number.toString(), null, null, null, startOfDay(departureDay))
        }

        // MARK: IATA BCBP

        /**
         * Decodifica il formato IATA Resolution 792 (BCBP), primo segmento di volo:
         * `M1NOME/COGNOME       EABC123 FCOLHRAZ 0204 314Y012A0001 100`
         * - [0] "M" (o "S"), [1] numero di segmenti, [2..<22] nome, [22] e-ticket, [23..<30] PNR,
         *   [30..<33] partenza, [33..<36] arrivo, [36..<39] compagnia, [39..<44] volo, [44..<47] giorno giuliano,
         *   [47] classe, [48..<52] posto.
         * @param now epoch ms (sceglie l'anno del giorno giuliano).
         */
        @Throws(ParseError::class)
        fun fromBCBP(raw: String, now: Long = System.currentTimeMillis()): BoardingPass {
            val chars = raw.replace("\r", "").replace("\n", "")
            if (chars.length < 47 || (chars[0] != 'M' && chars[0] != 'S') || !chars[1].isDigit()) {
                throw ParseError.NotBoardingPass
            }
            fun field(from: Int, to: Int): String {
                if (to > chars.length) return ""
                return chars.substring(from, to).trim { it == ' ' || it == '\t' }
            }
            val origin = field(30, 33)
            val destination = field(33, 36)
            val carrier = field(36, 39)
            // Come iOS (`trimmingCharacters(in: "0 ")`): zeri e spazi tolti da entrambi i lati.
            val flightRaw = field(39, 44).trim { it == '0' || it == ' ' }
            val julian = field(44, 47)
            val seat = if (chars.length >= 52) field(48, 52).trim { it == '0' } else null

            if (carrier.length !in 2..3 || !carrier.all { it.isLetterOrDigit() } ||
                flightRaw.isEmpty() || !flightRaw.all { it.isDigit() }
            ) throw ParseError.InvalidFlight
            val dayOfYear = julian.toIntOrNull()
            if (dayOfYear == null || dayOfYear !in 1..366) throw ParseError.InvalidDate

            return BoardingPass(
                carrier = carrier,
                flightNumber = flightRaw,
                origin = if (origin.length == 3) origin else null,
                destination = if (destination.length == 3) destination else null,
                seat = if (seat.isNullOrEmpty()) null else seat,
                departureDay = date(julianDay = dayOfYear, near = now),
            )
        }

        /**
         * Il BCBP porta solo il giorno dell'anno: si sceglie l'anno che rende la data piu' vicina a oggi
         * (una carta scansionata il 2 gennaio per il volo del 31 dicembre appartiene all'anno precedente).
         * `near` e risultato in epoch ms (mezzanotte UTC).
         */
        fun date(julianDay: Int, near: Long): Long {
            val year = Instant.ofEpochMilli(near).atZone(ZoneOffset.UTC).year
            val candidates = (year - 1..year + 1).map { candidateYear ->
                LocalDate.of(candidateYear, 1, 1).plusDays((julianDay - 1).toLong())
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
            val best = candidates.minByOrNull { abs(it - near) } ?: near
            return startOfDay(best)
        }

        /** Mezzanotte UTC del giorno (epoch ms). */
        fun startOfDay(epochMs: Long): Long =
            Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    }
}
