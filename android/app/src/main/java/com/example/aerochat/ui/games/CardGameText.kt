package com.example.aerochat.ui.games

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.example.aerochat.R
import com.example.aerochat.core.games.GameOutcome
import com.example.aerochat.core.games.GameSessionState
import com.example.aerochat.core.games.GameStatus
import com.example.aerochat.core.games.NeapolitanCards
import com.example.aerochat.core.games.cardWinnerSide
import com.example.aerochat.core.games.nameOfSeat
import com.example.aerochat.core.games.turnSeat
import com.example.aerochat.proto.GameKind

// Testi delle partite a carte condivisi da bolla e tavolo (stesse frasi dell'app iOS, `CardGameText.swift`).

/** Nickname del posto ("Giocatore N" se sconosciuto). */
@Composable
fun seatName(state: GameSessionState, seat: Int): String =
    state.nameOfSeat(seat)?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.games_player_n, seat + 1)

/** Nome di una parte: il giocatore (2 giocatori) o la coppia (Scopone, "Anna e Bruno"). */
@Composable
fun sideName(state: GameSessionState, side: Int): String =
    if (state.gameKind == GameKind.SCOPONE) {
        stringResource(R.string.games_side_pair, seatName(state, side), seatName(state, side + 2))
    } else {
        seatName(state, side)
    }

/** "Anna vs Bruno" oppure "Anna e Carlo vs Bruno e Dario". */
@Composable
fun playersLine(state: GameSessionState): String =
    stringResource(R.string.games_players_line, sideName(state, 0), sideName(state, 1))

/** Punteggio corrente per parte: punti partita (Scopa/Scopone) o punti carta (Briscola). */
fun cardScores(state: GameSessionState): List<Int>? = state.scopa?.scores ?: state.briscola?.points

@Composable
fun cardStatusText(state: GameSessionState): String = when (state.status) {
    GameStatus.invited -> {
        val mySeat = state.mySeat
        when {
            state.iAmInviter && state.gameKind == GameKind.SCOPONE ->
                stringResource(R.string.games_invite_sent_accepted, state.acceptedSeats?.size ?: 0)
            state.iAmInviter -> stringResource(R.string.games_invite_sent)
            mySeat == null -> stringResource(R.string.games_invite_group)
            state.acceptedSeats?.contains(mySeat) == true -> stringResource(R.string.games_waiting_players)
            else -> stringResource(R.string.games_invite_received)
        }
    }
    GameStatus.active -> {
        val turn = state.turnSeat
        when {
            turn == null -> stringResource(R.string.games_dealing)
            turn == state.mySeat -> stringResource(R.string.games_your_turn)
            else -> stringResource(R.string.games_turn_of, seatName(state, turn))
        }
    }
    GameStatus.finished -> {
        if (state.mySeat == null) {
            val winner = state.cardWinnerSide
            if (winner != null) stringResource(R.string.games_winner, sideName(state, winner)) else stringResource(R.string.games_draw)
        } else {
            when (state.outcome) {
                GameOutcome.iWon -> stringResource(R.string.games_you_won)
                GameOutcome.iLost -> stringResource(R.string.games_you_lost)
                GameOutcome.draw -> stringResource(R.string.games_draw)
                GameOutcome.ongoing -> stringResource(R.string.games_game_over)
            }
        }
    }
}

/** Chi ha abbandonato (riga aggiuntiva a partita finita). */
@Composable
fun resignText(state: GameSessionState): String? {
    val seat = state.resignedSeat ?: return null
    if (seat == state.mySeat) return stringResource(R.string.games_you_resigned)
    return stringResource(R.string.games_player_resigned, seatName(state, seat))
}

/** Ultima giocata: "Marco ha preso 3♦ + 4♣ con 7♦ — Scopa!", "Hai calato 5♠", "Anna prende A♦ · 2♦ (11 punti)". */
@Composable
fun lastMoveText(state: GameSessionState): String? {
    state.scopa?.lastPlay?.let { play ->
        val card = NeapolitanCards.shortName(play.card)
        val isMe = play.seat == state.mySeat
        var text = if (play.captured.isEmpty()) {
            if (isMe) stringResource(R.string.games_you_played, card)
            else stringResource(R.string.games_player_played, seatName(state, play.seat), card)
        } else {
            val taken = play.captured.joinToString(" + ") { NeapolitanCards.shortName(it) }
            if (isMe) stringResource(R.string.games_you_captured, taken, card)
            else stringResource(R.string.games_player_captured, seatName(state, play.seat), taken, card)
        }
        if (play.scopa) text += " — " + stringResource(R.string.games_scopa_exclaim)
        return text
    }
    state.briscola?.lastTrick?.let { trick ->
        val cards = trick.cards.joinToString(" · ") { NeapolitanCards.shortName(it) }
        if (trick.winner == state.mySeat) {
            return pluralStringResource(R.plurals.games_you_take_trick, trick.points, cards, trick.points)
        }
        return pluralStringResource(R.plurals.games_player_takes_trick, trick.points, seatName(state, trick.winner), cards, trick.points)
    }
    return null
}

/** Titolo localizzato delle partite a carte ("🃏 Scopa"...). */
@Composable
fun cardGameTitle(kind: GameKind): String = when (kind) {
    GameKind.SCOPA -> stringResource(R.string.games_title_scopa)
    GameKind.SCOPONE -> stringResource(R.string.games_title_scopone)
    else -> stringResource(R.string.games_title_briscola)
}
