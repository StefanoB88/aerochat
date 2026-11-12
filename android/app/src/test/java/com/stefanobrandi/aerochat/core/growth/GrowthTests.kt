package com.stefanobrandi.aerochat.core.growth

import com.stefanobrandi.aerochat.core.flight.FlightReminder
import com.stefanobrandi.aerochat.core.flight.FlightTrip
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release 1.4: funzioni locali (RELEASE_1_4_SPEC.md §B), logica pura. */
class GrowthTests {

    private val rome: ZoneId = ZoneId.of("Europe/Rome")
    private val day = 24 * 3_600_000L

    private fun at(year: Int, month: Int, dayOfMonth: Int, hour: Int, minute: Int = 0, zone: ZoneId = rome): Long =
        LocalDateTime.of(year, month, dayOfMonth, hour, minute).atZone(zone).toInstant().toEpochMilli()

    // MARK: B2 Prima partita gratis

    @Test
    fun freeGameKeys() {
        val now = at(2026, 11, 10, 23, 30)
        assertEquals("flight:AZ|204|2026-11-10", FreeGameAllowance.key("AZ|204|2026-11-10", now, rome))
        // Giorno nel fuso locale (23:30 a Roma = 22:30 UTC, stesso giorno; 00:30 = giorno dopo).
        assertEquals("day:2026-11-10", FreeGameAllowance.key(null, now, rome))
        assertEquals("day:2026-11-11", FreeGameAllowance.key("  ", at(2026, 11, 11, 0, 30), rome))
    }

    @Test
    fun freeGameConsumption() {
        val key = "day:2026-11-10"
        var used = emptySet<String>()
        assertTrue(FreeGameAllowance.isAvailable(key, used))
        used = FreeGameAllowance.consume(key, used)
        assertFalse(FreeGameAllowance.isAvailable(key, used))
        // Il volo ha la sua partita gratis anche se quella del giorno e' gia' usata.
        assertTrue(FreeGameAllowance.isAvailable("flight:AZ|204|2026-11-10", used))
        // Memoria limitata alle chiavi recenti.
        repeat(100) { used = FreeGameAllowance.consume("day:$it", used) }
        assertEquals(FreeGameAllowance.maxRememberedKeys, used.size)
        assertTrue(used.contains("day:99"))
    }

    // MARK: B4 Premio inviti

    @Test
    fun referralUnlocksOnceAtThirdDistinctNewContact() {
        var state = ReferralProgram.State()
        val now = 1_000_000L
        state = ReferralProgram.register("a", isNewPeer = true, state = state, now = now).state
        state = ReferralProgram.register("a", isNewPeer = true, state = state, now = now).state
        state = ReferralProgram.register("b", isNewPeer = false, state = state, now = now).state
        assertEquals("doppioni e contatti gia' noti non contano", 1, state.progress)
        state = ReferralProgram.register("c", isNewPeer = true, state = state, now = now).state
        assertEquals(2, state.progress)
        assertFalse(state.isPassActive(now))
        val third = ReferralProgram.register("d", isNewPeer = true, state = state, now = now)
        assertTrue(third.unlocked)
        state = third.state
        assertEquals(3, state.progress)
        assertTrue(state.rewardGranted)
        assertTrue(state.isPassActive(now + ReferralProgram.rewardDuration - 1))
        assertFalse(state.isPassActive(now + ReferralProgram.rewardDuration))
        // Una volta per installazione.
        val again = ReferralProgram.register("e", isNewPeer = true, state = state, now = now + 2 * day)
        assertFalse(again.unlocked)
        assertEquals(state.passUntilMs, again.state.passUntilMs)
        assertEquals(3, again.state.progress)
    }

    // MARK: B5 Recensione

    @Test
    fun reviewPromptConditions() {
        val first = 1_700_000_000_000L
        val now = first + 3 * day
        assertTrue(ReviewPrompt.shouldRequest(now, first, openCount = 5, lastRequestAtMs = 0))
        assertFalse("meno di 3 giorni", ReviewPrompt.shouldRequest(now - 1, first, 5, 0))
        assertFalse("meno di 5 aperture", ReviewPrompt.shouldRequest(now, first, 4, 0))
        assertFalse("prima apertura sconosciuta", ReviewPrompt.shouldRequest(now, 0, 9, 0))
        assertFalse("gia' chiesta da meno di 90 giorni", ReviewPrompt.shouldRequest(now, first, 9, now - 89 * day))
        assertTrue(ReviewPrompt.shouldRequest(now, first, 9, now - 90 * day))
    }

    // MARK: B9 Passaporto

    private fun trip(key: String, people: Int, sent: Int, received: Int, played: Int, won: Int, hops: Int = 0) = FlightTrip(
        key = key, title = key, startedAtMs = 0, endedAtMs = 1, people = (0 until people).map { "$key-$it" }.toSet(),
        messagesSent = sent, messagesReceived = received, gamesPlayed = played, gamesWon = won, longestHop = hops,
    )

    @Test
    fun passportAccumulatesAndUnlocksBadges() {
        var stats = PassportStats()
        val first = TravelPassport.record(trip("AZ|1|2026-01-01", people = 6, sent = 40, received = 30, played = 2, won = 1, hops = 3), stats, now = 10)
        stats = first.stats
        assertEquals(setOf(PassportBadge.firstFlight, PassportBadge.firstWin), first.newBadges.toSet())
        assertEquals(1, stats.flights)
        assertEquals(3, stats.longestChain)
        // Lo stesso volo non si conta due volte.
        assertEquals(stats, TravelPassport.record(trip("AZ|1|2026-01-01", 6, 40, 30, 2, 1), stats, now = 20).stats)

        val second = TravelPassport.record(trip("AZ|2|2026-02-01", people = 5, sent = 20, received = 15, played = 1, won = 0, hops = 2), stats, now = 30)
        stats = second.stats
        assertEquals(setOf(PassportBadge.tenPeople, PassportBadge.hundredMessages), second.newBadges.toSet())
        assertEquals(11, stats.peopleMet)
        assertEquals(105, stats.messages)
        assertEquals(3, stats.longestChain)
        assertEquals(10L, stats.badges[PassportBadge.firstFlight])
        assertEquals(30L, stats.badges[PassportBadge.tenPeople])

        for (index in 3..10) {
            stats = TravelPassport.record(trip("AZ|$index|2026-03-01", 5, 0, 0, 2, 2), stats, now = 100L + index).stats
        }
        assertEquals(10, stats.flights)
        assertTrue(stats.badges.keys.containsAll(listOf(PassportBadge.fiveFlights, PassportBadge.tenFlights, PassportBadge.fiftyPeople, PassportBadge.tenWins)))
        assertEquals(PassportBadge.entries.toSet(), stats.badges.keys)
    }

    // MARK: B11 Posizione

    @Test
    fun locationTextFormatAndParse() {
        val text = LocationShare.format(45.464211, 9.190006, 12.4f)
        assertEquals("📍 45.46421, 9.19001 (±12 m)", text)
        assertEquals("📍 -33.86882, 151.20930", LocationShare.format(-33.868820, 151.209296, null))
        val point = requireNotNull(LocationShare.parse(text))
        assertEquals(45.46421, point.latitude, 1e-9)
        assertEquals(9.19001, point.longitude, 1e-9)
        assertEquals(12, point.accuracyMeters)
        val noAccuracy = requireNotNull(LocationShare.parse("Siamo qui 📍 -33.86882, 151.20930 al rifugio"))
        assertNull(noAccuracy.accuracyMeters)
        assertEquals(-33.86882, noAccuracy.latitude, 1e-9)
        assertNull(LocationShare.parse("45.46421, 9.19001"))
        assertNull(LocationShare.parse("📍 95.00000, 9.00000"))
        assertEquals("geo:45.46421,9.19001?q=45.46421,9.19001", LocationShare.geoURI(point))
    }

    // MARK: B7 Promemoria

    @Test
    fun flightReminderAtSevenOnDepartureDay() {
        val departure = LocalDate.of(2026, 11, 12).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val expected = at(2026, 11, 12, 7)
        assertEquals(expected, FlightReminder.fireAt(departure, now = at(2026, 11, 10, 18), zone = rome))
        // Oggi prima delle 07:00: ancora in tempo.
        assertEquals(expected, FlightReminder.fireAt(departure, now = at(2026, 11, 12, 6, 30), zone = rome))
        // Oggi dopo le 07:00, o partenza passata: nessun promemoria.
        assertNull(FlightReminder.fireAt(departure, now = at(2026, 11, 12, 8), zone = rome))
        assertNull(FlightReminder.fireAt(departure, now = at(2026, 11, 13, 6), zone = rome))
        assertEquals("AZ204", FlightReminder.label("AZ|204|2026-11-12"))
        assertNotNull(FlightReminder.fireAt(departure + day, now = at(2026, 11, 12, 8), zone = rome))
    }
}
