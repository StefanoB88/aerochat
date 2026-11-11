package com.stefanobrandi.aerochat.core.flight

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ricordo del viaggio: contatori del volo e una sola notifica "cartolina pronta" per volo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripMemoryTests {

    private val key = "AZ|204|2026-11-10"
    private val title = "AZ 204 · FCO → LHR · 10 nov"

    @Test
    fun countersDuringFlight() {
        val memory = TripMemory(TripMemoryStore(preferences = null))
        // A terra nessun contatore.
        memory.recordMessage(outgoing = true)
        assertNull(memory.activeTrip)

        assertTrue(memory.update(mapOf(key to title), airplaneMode = false, now = 1_000).isEmpty())
        memory.recordPerson("aa")
        memory.recordPerson("aa")
        memory.recordPerson("bb")
        memory.recordMessage(outgoing = true)
        memory.recordMessage(outgoing = true)
        memory.recordMessage(outgoing = false)
        memory.recordGameFinished("g1", won = true)
        memory.recordGameFinished("g1", won = true)
        memory.recordGameFinished("g2", won = false)

        val trip = requireNotNull(memory.activeTrip)
        assertEquals(2, trip.people.size)
        assertEquals(2, trip.messagesSent)
        assertEquals(1, trip.messagesReceived)
        assertEquals(2, trip.gamesPlayed)
        assertEquals(1, trip.gamesWon)
        assertEquals("AZ 204", trip.flightCode)
        assertEquals("2026-11-10", trip.flightDate)
    }

    @Test
    fun airplaneModeOffEndsTheFlightOnce() {
        val memory = TripMemory(TripMemoryStore(preferences = null))
        val flights = mapOf(key to title)
        assertTrue(memory.update(flights, airplaneMode = false, now = 1_000).isEmpty())
        // Modalita' aereo spenta senza essere stata accesa: il volo continua.
        assertTrue(memory.update(flights, airplaneMode = false, now = 2_000).isEmpty())
        assertTrue(memory.update(flights, airplaneMode = true, now = 3_000).isEmpty())
        assertTrue(memory.update(flights, airplaneMode = true, now = 4_000).isEmpty())
        val ended = memory.update(flights, airplaneMode = false, now = 5_000)
        assertEquals(1, ended.size)
        assertEquals(key, ended.single().key)
        assertEquals(5_000L, ended.single().endedAtMs)
        assertTrue(ended.single().notified)
        assertNull(memory.activeTrip)
        // La Cabina resta valida per ore: il volo non riparte e non si notifica di nuovo.
        assertTrue(memory.update(flights, airplaneMode = true, now = 6_000).isEmpty())
        assertTrue(memory.update(flights, airplaneMode = false, now = 7_000).isEmpty())
        assertTrue(memory.update(emptyMap(), airplaneMode = false, now = 8_000).isEmpty())
        assertNull(memory.activeTrip)
        assertNotNull(memory.lastEndedTrip())
    }

    @Test
    fun expiredCabinEndsTheFlightOnceAndPersists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(TripMemoryStore.preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        val memory = TripMemory(TripMemoryStore(context))
        memory.update(mapOf(key to title), airplaneMode = false, now = 1_000)
        memory.recordPerson("cc")
        memory.recordGameFinished("g", won = true)

        // Un nuovo processo ritrova i contatori.
        val reloaded = TripMemory(TripMemoryStore(context))
        assertEquals(setOf("cc"), reloaded.activeTrip?.people)

        val ended = reloaded.update(emptyMap(), airplaneMode = false, now = 2_000)
        assertEquals(1, ended.size)
        assertEquals(1, ended.single().gamesWon)
        assertFalse(TripMemory(TripMemoryStore(context)).trip(key)?.isActive ?: true)
        assertTrue("una sola volta per volo", TripMemory(TripMemoryStore(context)).update(emptyMap(), false, 3_000).isEmpty())
    }
}
