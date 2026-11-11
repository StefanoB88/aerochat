package com.stefanobrandi.aerochat.core.review

import com.stefanobrandi.aerochat.core.games.BriscolaGame
import com.stefanobrandi.aerochat.core.games.GameSessionState
import com.stefanobrandi.aerochat.core.games.GameStatus
import com.stefanobrandi.aerochat.core.games.NeapolitanCards
import com.stefanobrandi.aerochat.core.games.ScopaGame
import com.stefanobrandi.aerochat.proto.GameKind
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bot del passeggero virtuale: sempre una carta legale; a Scopa preferisce una presa. */
class DemoCardBotTests {

    @Test
    fun scopaBotAlwaysPlaysLegallyAndPrefersCaptures() {
        for (seed in 1L..20L) {
            val random = Random(seed)
            val game = ScopaGame(playerCount = 2, dealer = 0, deck = NeapolitanCards.shuffledDeck(random))
            var plays = 0
            while (!game.isMatchOver && plays < 2_000) {
                if (game.phase == ScopaGame.Phase.handOver) {
                    game.deal(NeapolitanCards.shuffledDeck(random), game.nextDealer)
                    continue
                }
                val seat = game.turn
                val (card, captured) = requireNotNull(DemoCardBot.scopaMove(game, seat))
                game.validate(seat, card, captured) // lancia se illegale
                val anyCapture = game.hands[seat].any { game.captureOptions(it).isNotEmpty() }
                if (anyCapture) assertTrue("con una presa possibile il bot prende (seed $seed)", captured.isNotEmpty())
                game.play(seat, card, captured)
                plays += 1
            }
            assertTrue("la partita finisce (seed $seed)", game.isMatchOver)
        }
    }

    @Test
    fun briscolaBotAlwaysPlaysACardInHand() {
        for (seed in 1L..20L) {
            val game = BriscolaGame(dealer = 0, deck = NeapolitanCards.shuffledDeck(Random(seed)))
            var plays = 0
            while (!game.isFinished && plays < 100) {
                val seat = game.turn
                val card = requireNotNull(DemoCardBot.briscolaMove(game, seat))
                assertTrue(game.hands[seat].contains(card))
                game.play(seat, card)
                plays += 1
            }
            assertTrue(game.isFinished)
            assertEquals(40, plays)
        }
    }

    @Test
    fun nextActionFollowsTheTable() {
        val me = ByteArray(8) { 1 }
        val bot = DemoPeerService.nodeID
        // L'utente invita il bot: il bot accetta.
        val invited = GameSessionState(
            gameID = "00", kind = GameKind.SCOPA.number, conversationID = "c", opponentNodeID = bot, iAmInviter = true,
            seats = listOf(me, bot), mySeat = 0,
        )
        assertEquals(DemoCardBot.Action.Accept, DemoCardBot.nextAction(invited, bot))
        // Il bot ha invitato e l'utente ha accettato: distribuisce il bot (posto 0).
        val accepted = GameSessionState(
            gameID = "01", kind = GameKind.SCOPA.number, conversationID = "c", opponentNodeID = bot, iAmInviter = false,
            status = GameStatus.active, seats = listOf(bot, me), mySeat = 1,
        )
        val deal = DemoCardBot.nextAction(accepted, bot, deck = { (0 until 40).toList() })
        assertEquals(DemoCardBot.Action.Deal(seat = 0, deck = (0 until 40).toList()), deal)
        // Invito del bot non ancora accettato: tocca all'utente.
        assertNull(DemoCardBot.nextAction(accepted.copy(status = GameStatus.invited), bot))
        // Tris: il bot non gioca.
        assertNull(DemoCardBot.nextAction(invited.copy(kind = GameKind.TIC_TAC_TOE.number), bot))
        // Mano in corso e turno del bot (dopo il DEAL del bot muove l'utente, posto 1; poi il bot).
        val playing = accepted.deepCopy().apply { scopa = ScopaGame(playerCount = 2, dealer = 1, deck = (0 until 40).toList()) }
        val play = DemoCardBot.nextAction(playing, bot)
        assertNotNull(play)
        assertTrue(play is DemoCardBot.Action.Play)
    }
}
