package com.stefanobrandi.aerochat.core.growth

/**
 * Premio inviti (RELEASE_1_4_SPEC.md §B4): i contatti nuovi arrivati con il nostro QR/link (`PAIR_HELLO` da un
 * `node_id` mai visto) si contano una volta; al **3°** distinto si sblocca, **una volta per installazione**, un
 * Pass locale di **24 h** solo per uso personale (non autorizza a regalare il Pass ai gruppi).
 */
object ReferralProgram {
    const val goal = 3
    /** Durata del Pass locale (ms). */
    const val rewardDuration: Long = 24 * 3_600_000L

    /** Stato del programma (persistito in `AppSettings`). */
    data class State(
        val invited: Set<String> = emptySet(),
        val rewardGranted: Boolean = false,
        /** Scadenza del Pass inviti (epoch ms), 0 = mai sbloccato. */
        val passUntilMs: Long = 0L,
    ) {
        /** Amici contati per il contatore "2 su 3", al massimo [goal]. */
        val progress: Int get() = minOf(invited.size, goal)

        fun isPassActive(now: Long): Boolean = passUntilMs > now
    }

    /** Esito della registrazione: stato nuovo e se il Pass e' stato appena sbloccato. */
    data class Outcome(val state: State, val unlocked: Boolean)

    /**
     * Nuovo contatto arrivato con il nostro QR. [isNewPeer] = `node_id` mai visto prima nel database (un
     * contatto gia' noto che riscansiona non conta).
     */
    fun register(nodeHex: String, isNewPeer: Boolean, state: State, now: Long): Outcome {
        if (!isNewPeer || state.invited.contains(nodeHex)) return Outcome(state, unlocked = false)
        // Dopo il premio basta ricordare il traguardo raggiunto: niente lista che cresce per sempre.
        val invited = if (state.invited.size >= goal) state.invited else state.invited + nodeHex
        if (state.rewardGranted || invited.size < goal) return Outcome(state.copy(invited = invited), unlocked = false)
        return Outcome(State(invited = invited, rewardGranted = true, passUntilMs = now + rewardDuration), unlocked = true)
    }
}
