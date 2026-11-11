package com.stefanobrandi.aerochat.core.review

import com.stefanobrandi.aerochat.core.games.BriscolaGame
import com.stefanobrandi.aerochat.core.games.GameSessionState
import com.stefanobrandi.aerochat.core.games.GameStatus
import com.stefanobrandi.aerochat.core.games.NeapolitanCards
import com.stefanobrandi.aerochat.core.games.ScopaGame
import com.stefanobrandi.aerochat.core.games.expectedDealer
import com.stefanobrandi.aerochat.core.games.seat
import com.stefanobrandi.aerochat.core.games.turnSeat
import com.stefanobrandi.aerochat.proto.GameKind

/**
 * Avversario semplice del passeggero virtuale (modalita' prova): gioca sempre una carta legale; a Scopa
 * preferisce una presa (prima la scopa, poi il settebello, poi i denari e il numero di carte), a Briscola
 * prende la mano con la carta meno preziosa che basta, altrimenti scarta la piu' povera.
 *
 * Logica pura sopra lo stato della partita visto dal telefono dell'utente (che conosce tutte le carte
 * dopo il DEAL): nessuna rete, testabile.
 */
object DemoCardBot {

    /** Prossima azione del bot. */
    sealed interface Action {
        /** Accetta l'invito dell'utente (`ACCEPT`, sequence 1). */
        data object Accept : Action

        /** Distribuisce (`DEAL`) con il mazzo indicato. */
        data class Deal(val seat: Int, val deck: List<Int>) : Action

        /** Gioca una carta (con le carte prese, Scopa). */
        data class Play(val card: Int, val captured: List<Int>) : Action
    }

    /** Solo i giochi di carte a 2 (Scopa, Briscola). */
    fun supports(kind: GameKind): Boolean = kind == GameKind.SCOPA || kind == GameKind.BRISCOLA

    /** Cosa deve fare il bot ([botNodeID]) in questa partita, `null` se tocca all'utente o e' finita. */
    fun nextAction(state: GameSessionState, botNodeID: ByteArray, deck: () -> List<Int> = { NeapolitanCards.shuffledDeck() }): Action? {
        if (!supports(state.gameKind) || state.status == GameStatus.finished) return null
        val botSeat = state.seat(of = botNodeID) ?: return null
        if (state.status == GameStatus.invited) {
            // Invito dell'utente: il bot accetta; un invito del bot aspetta l'utente.
            return if (state.iAmInviter) Action.Accept else null
        }
        if (state.expectedDealer == botSeat) {
            val firstHand = state.scopa == null && state.briscola == null
            // Prima mano: distribuisce chi ha invitato (qui il bot, se l'utente ha accettato il suo invito).
            if (!firstHand || !state.iAmInviter) return Action.Deal(botSeat, deck())
        }
        if (state.turnSeat != botSeat) return null
        state.scopa?.let { game -> return scopaMove(game, botSeat)?.let { Action.Play(it.first, it.second) } }
        state.briscola?.let { game -> return briscolaMove(game, botSeat)?.let { Action.Play(it, emptyList()) } }
        return null
    }

    /** Scopa: carta e presa legali, preferendo sempre una presa. `null` se il posto non ha carte. */
    fun scopaMove(game: ScopaGame, seat: Int): Pair<Int, List<Int>>? {
        val hand = game.hands.getOrNull(seat) ?: return null
        if (hand.isEmpty()) return null
        var best: Triple<Int, List<Int>, Int>? = null
        for (card in hand) {
            for (option in game.captureOptions(card)) {
                val score = captureScore(card, option, game.table)
                if (best == null || score > best.third) best = Triple(card, option, score)
            }
        }
        best?.let { return it.first to it.second }
        // Nessuna presa possibile: la carta meno preziosa (mai il settebello se c'e' altro).
        val card = hand.minWith(
            compareBy<Int>({ it == NeapolitanCards.settebello }, { NeapolitanCards.suit(it) == 0 }, { NeapolitanCards.rank(it) }),
        )
        return card to emptyList()
    }

    private fun captureScore(card: Int, captured: List<Int>, table: List<Int>): Int {
        val taken = captured + card
        var score = 100 + captured.size * 10
        if (captured.size == table.size) score += 60 // scopa
        if (taken.contains(NeapolitanCards.settebello)) score += 40
        score += taken.count { NeapolitanCards.suit(it) == 0 } * 5
        score += taken.count { NeapolitanCards.rank(it) == 7 } * 4
        return score
    }

    /** Briscola: una carta della mano (sempre legale). `null` se il posto non ha carte. */
    fun briscolaMove(game: BriscolaGame, seat: Int): Int? {
        val hand = game.hands.getOrNull(seat) ?: return null
        if (hand.isEmpty()) return null
        val trump = game.trumpSuit
        val cheapest = compareBy<Int>({ NeapolitanCards.suit(it) == trump }, { NeapolitanCards.briscolaPoints(it) }, { NeapolitanCards.briscolaStrength(it) })
        val lead = game.trick.firstOrNull() ?: return hand.minWith(cheapest)
        val winning = hand.filter { BriscolaGame.followerWins(lead = lead, follow = it, trump = trump) }
        val worthTaking = NeapolitanCards.briscolaPoints(lead) > 0
        if (winning.isNotEmpty()) {
            val pick = winning.minWith(cheapest)
            // Si prende con una briscola solo se sul tavolo ci sono punti.
            if (NeapolitanCards.suit(pick) != trump || worthTaking) return pick
        }
        return hand.minWith(cheapest)
    }
}
