package com.stefanobrandi.aerochat.core.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/** Backoff esponenziale dei ritentativi radio (permessi, setup peripheral, advertising, scansione). */
class RetryBackoffTests {

    @Test
    fun doublesUpToCapAndResets() {
        val backoff = RetryBackoff(initialMs = 3_000L, maxMs = 60_000L)
        val delays = List(8) { backoff.next() }
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L, 60_000L, 60_000L, 60_000L), delays)
        assertEquals(8, backoff.attempts)

        backoff.reset()
        assertEquals(0, backoff.attempts)
        assertEquals("dopo un successo si riparte dall'intervallo iniziale", 3_000L, backoff.next())
    }

    @Test
    fun manyFailuresDoNotOverflow() {
        val backoff = RetryBackoff(initialMs = 5_000L, maxMs = 60_000L)
        repeat(200) { backoff.next() }
        assertEquals(60_000L, backoff.next())
    }
}
