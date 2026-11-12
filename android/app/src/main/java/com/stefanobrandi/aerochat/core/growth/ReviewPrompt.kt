package com.stefanobrandi.aerochat.core.growth

/**
 * Richiesta di recensione (RELEASE_1_4_SPEC.md §B5): nei momenti felici (cartolina di fine volo mostrata,
 * partita vinta) solo se sono passati almeno 3 giorni dalla prima apertura, con almeno 5 aperture e al
 * massimo una richiesta ogni 90 giorni. Tempi in epoch ms.
 */
object ReviewPrompt {
    const val minimumDaysSinceFirstOpen = 3
    const val minimumOpens = 5
    const val minimumDaysBetweenRequests = 90
    private const val day = 24 * 3_600_000L

    /** Momento in cui si puo' chiedere la recensione. */
    enum class Moment { tripCardShown, gameWon }

    /** [firstOpenAtMs] 0 = sconosciuta; [lastRequestAtMs] 0 = mai chiesta. */
    fun shouldRequest(now: Long, firstOpenAtMs: Long, openCount: Int, lastRequestAtMs: Long): Boolean {
        if (firstOpenAtMs <= 0L || now - firstOpenAtMs < minimumDaysSinceFirstOpen * day) return false
        if (openCount < minimumOpens) return false
        if (lastRequestAtMs > 0L && now - lastRequestAtMs < minimumDaysBetweenRequests * day) return false
        return true
    }
}
