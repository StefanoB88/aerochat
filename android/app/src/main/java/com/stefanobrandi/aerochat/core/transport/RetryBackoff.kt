package com.stefanobrandi.aerochat.core.transport

/**
 * Backoff esponenziale per i ritentativi della radio (permessi, setup del peripheral, advertising,
 * scansione): [initialMs], poi il doppio a ogni tentativo fallito fino a [maxMs]. [reset] al primo
 * successo. Non thread-safe: va usato dal thread della coda del controller.
 */
internal class RetryBackoff(private val initialMs: Long, private val maxMs: Long) {

    init {
        require(initialMs > 0 && maxMs >= initialMs) { "intervalli non validi" }
    }

    /** Tentativi falliti consecutivi dall'ultimo [reset]. */
    var attempts: Int = 0
        private set

    /** Ritardo del prossimo tentativo (e lo conta): initial, 2×, 4×… fino a [maxMs]. */
    fun next(): Long {
        val shift = attempts.coerceAtMost(MAX_SHIFT)
        attempts++
        return minOf(maxMs, initialMs shl shift)
    }

    fun reset() {
        attempts = 0
    }

    private companion object {
        /** Oltre questa soglia `initialMs shl n` potrebbe traboccare: il tetto e' comunque [maxMs]. */
        const val MAX_SHIFT = 20
    }
}
