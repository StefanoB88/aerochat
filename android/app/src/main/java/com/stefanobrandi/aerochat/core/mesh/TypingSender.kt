package com.stefanobrandi.aerochat.core.mesh

import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Regole di invio del "sta scrivendo" dalla barra di scrittura (PAIRING_TYPING_SPEC §3):
 * - il testo diventa non vuoto -> `true`, poi ripetuto al massimo ogni [refreshInterval] (4 s) finche' si scrive;
 * - `false` quando il testo torna vuoto, all'invio, uscendo dalla chat / app in background ([stop]) o dopo
 *   [idleTimeout] (5 s) senza battiture;
 * - niente invio se [canSend] e' falso (chat scaduta, compagno bloccato, Cabina, modifica di un messaggio).
 *
 * Da usare da un solo thread (il main thread della chat). [clock] in ms monotoni (sostituibile nei test).
 */
class TypingSender(
    private val scope: CoroutineScope,
    private val canSend: () -> Boolean,
    private val send: (Boolean) -> Unit,
    private val refreshInterval: Long = MeshProtocol.typingRefreshInterval,
    private val idleTimeout: Long = MeshProtocol.typingIdleTimeout,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** `true` se l'ultimo indicatore inviato era "sta scrivendo". */
    var isTyping: Boolean = false
        private set
    private var lastTrueAt = 0L
    private var idleJob: Job? = null

    /** L'utente ha modificato il testo nella barra. */
    fun textChanged(text: String) {
        if (text.isBlank() || !canSend()) {
            stop()
            return
        }
        val now = clock()
        if (!isTyping || now - lastTrueAt >= refreshInterval) {
            isTyping = true
            lastTrueAt = now
            send(true)
        }
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleTimeout)
            idleJob = null
            stop()
        }
    }

    /** Invio del messaggio, uscita dalla chat o app in background: `false` se stavamo scrivendo. */
    fun stop() {
        idleJob?.cancel()
        idleJob = null
        if (!isTyping) return
        isTyping = false
        send(false)
    }
}
