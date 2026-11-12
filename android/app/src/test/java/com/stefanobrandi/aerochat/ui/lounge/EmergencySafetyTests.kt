package com.stefanobrandi.aerochat.ui.lounge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emergenza in volo (RELEASE_1_4_SPEC.md §B12): al massimo un avviso ogni 10 minuti. */
class EmergencySafetyTests {
    @Test
    fun cooldownOfTenMinutes() {
        val sent = 1_000_000L
        assertTrue(EmergencySafety.canSend(0L, sent))
        assertFalse(EmergencySafety.canSend(sent, sent + 9 * 60_000L))
        assertTrue(EmergencySafety.canSend(sent, sent + 10 * 60_000L))
        assertEquals(10, EmergencySafety.minutesUntilNext(sent, sent))
        assertEquals(1, EmergencySafety.minutesUntilNext(sent, sent + 9 * 60_000L + 30_000L))
        assertEquals(10, EmergencySafety.minimumHelpTextLength)
    }
}
