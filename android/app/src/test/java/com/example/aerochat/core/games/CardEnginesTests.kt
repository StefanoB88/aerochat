package com.example.aerochat.core.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motori di Scopa / Scopone / Briscola: vettori di test condivisi con iOS (CARD_GAMES_SPEC §8).
 * JUnit puro (nessuna classe Android): i motori sono deterministici e privi di I/O.
 */
class CardEnginesTests {

    private val identityDeck = (0 until 40).toList()

    private fun card(suit: Int, rank: Int) = NeapolitanCards.id(suit, rank)

    /** Bot deterministico §8.7: prima carta della mano, prima presa legale in ordine canonico. */
    private fun playScriptedHand(game: ScopaGame) {
        while (game.phase == ScopaGame.Phase.playing) {
            val seat = game.turn
            val card = game.hands[seat].first()
            val captured = game.captureOptions(card).firstOrNull() ?: emptyList()
            game.play(seat, card, captured)
        }
    }

    // §8.1
    @Test
    fun cardIds() {
        assertEquals(6, card(suit = 0, rank = 7))
        assertEquals(39, card(suit = 3, rank = 10))
        assertEquals(10, card(suit = 1, rank = 1))
        assertEquals(NeapolitanCards.settebello, card(suit = 0, rank = 7))
        assertEquals("7♦", NeapolitanCards.shortName(6))
        assertEquals("R♣", NeapolitanCards.shortName(39))
        assertEquals("A♥", NeapolitanCards.shortName(10))
        assertEquals("F♠", NeapolitanCards.shortName(27))
        assertEquals(120, (0 until 40).sumOf { NeapolitanCards.briscolaPoints(it) })
        assertTrue(NeapolitanCards.isValidDeck(identityDeck))
        assertFalse(NeapolitanCards.isValidDeck(identityDeck.dropLast(1) + 0))
        assertFalse(NeapolitanCards.isValidDeck(identityDeck.dropLast(1)))
        val shuffled = NeapolitanCards.shuffledDeck()
        assertTrue(NeapolitanCards.isValidDeck(shuffled))
        assertEquals(shuffled, NeapolitanCards.deck(NeapolitanCards.bytes(shuffled)))
        assertNull(NeapolitanCards.deck(ByteArray(40)))
    }

    // §8.2
    @Test
    fun captureOptions() {
        val table = listOf(1, 34, 22, 13)
        assertEquals(listOf(listOf(34)), ScopaGame.captureOptions(card = 4, table = table))
        assertEquals(listOf(listOf(1, 34), listOf(13, 22)), ScopaGame.captureOptions(card = 26, table = table))
        assertEquals(listOf(listOf(1, 22, 34)), ScopaGame.captureOptions(card = 9, table = table))
        assertEquals(emptyList<List<Int>>(), ScopaGame.captureOptions(card = 20, table = table))

        // Validazione come insieme; combinazione non ammessa se c'e' una carta di pari valore.
        assertTrue(ScopaGame.isLegalCapture(listOf(22, 13), card = 26, table = table))
        assertTrue(ScopaGame.isLegalCapture(listOf(34), card = 4, table = table))
        assertFalse(ScopaGame.isLegalCapture(listOf(1, 22), card = 4, table = table))
        assertFalse(ScopaGame.isLegalCapture(emptyList(), card = 26, table = table))
        assertTrue(ScopaGame.isLegalCapture(emptyList(), card = 20, table = table))
        assertFalse(ScopaGame.isLegalCapture(listOf(1), card = 20, table = table))
        // Piu' carte di pari valore: una sola, a scelta.
        assertEquals(listOf(listOf(4), listOf(14)), ScopaGame.captureOptions(card = 24, table = listOf(14, 4, 1)))
    }

    // §8.3
    @Test
    fun lastPlayIsNeverAScopa() {
        // Scopa a 2 con mazzo identita': si giocano tutte le carte fino all'ultima, che svuota il tavolo.
        // Costruiamo un mazzo in cui l'ultima carta giocata (mazziere, ultimo giro) prende l'unica carta in tavola.
        val game = ScopaGame(playerCount = 2, dealer = 0, deck = identityDeck)
        playScriptedHand(game)
        val last = game.lastPlay!!
        if (last.captured.isNotEmpty()) assertFalse(last.scopa)

        // Caso esplicito: mano finale con 1 carta a testa.
        val forced = forcedLastPlayGame()
        val seat = forced.turn
        val playerCard = forced.hands[seat].single()
        val options = forced.captureOptions(playerCard)
        val play = forced.play(seat, playerCard, options.first())
        assertTrue(play.captured.isNotEmpty())
        assertFalse("l'ultima giocata non e' scopa", play.scopa)
        assertEquals(ScopaGame.Phase.handOver, forced.phase)
    }

    /**
     * Mano di Scopa giocata con il bot fino a lasciare la sola ultima giocata; poi verifichiamo che,
     * se l'ultima carta svuota il tavolo, non sia scopa. Mazzo costruito in modo che l'ultima carta del
     * mazziere (deck[39]) prenda esattamente il tavolo rimasto.
     */
    private fun forcedLastPlayGame(): ScopaGame {
        // Cerca un mazzo (permutazioni deterministiche) in cui l'ultima giocata svuota il tavolo con una presa.
        val random = java.util.Random(1)
        repeat(20_000) {
            val deck = identityDeck.shuffled(random)
            val game = ScopaGame(playerCount = 2, dealer = 0, deck = deck)
            while (game.phase == ScopaGame.Phase.playing) {
                val remainingCards = game.hands.sumOf { it.size } + game.remainingInDeck
                if (remainingCards == 1) {
                    val seat = game.turn
                    val options = game.captureOptions(game.hands[seat].single())
                    if (options.isNotEmpty() && options.first().size == game.table.size) return game
                    break
                }
                val seat = game.turn
                val card = game.hands[seat].first()
                game.play(seat, card, game.captureOptions(card).firstOrNull() ?: emptyList())
            }
        }
        throw AssertionError("nessun mazzo trovato")
    }

    @Test
    fun scopaCountsMidHand() {
        // Tavolo [A♦ (0), 2♦ (1), 3♦ (2), 4♦ (3)]; il mazziere 0 da' a seat 1 la carta deck[0].
        // Mettiamo in testa al mazzo un Re (9 = Re di denari) per seat 1: 1+2+3+4 = 10 → scopa.
        val rest = identityDeck.filter { it !in listOf(9, 0, 1, 2, 3, 11, 12, 13, 14, 15) }
        val deck = listOf(9, 11, 12, 13, 14, 15) + listOf(0, 1, 2, 3) + rest
        val game = ScopaGame(playerCount = 2, dealer = 0, deck = deck)
        assertEquals(listOf(0, 1, 2, 3), game.table)
        assertEquals(1, game.turn)
        assertEquals(listOf(listOf(0, 1, 2, 3)), game.captureOptions(9))
        assertThrows(ScopaGame.Failure.illegalCapture::class.java) { game.play(1, 9, emptyList()) }
        assertThrows(ScopaGame.Failure.notYourTurn::class.java) { game.play(0, 11, emptyList()) }
        assertThrows(ScopaGame.Failure.cardNotInHand::class.java) { game.play(1, 11, emptyList()) }
        val play = game.play(1, 9, listOf(3, 2, 1, 0))
        assertTrue(play.scopa)
        assertEquals(listOf(0, 1), game.scope)
        assertEquals(listOf(emptyList(), listOf(0, 1, 2, 3, 9)), game.captures)
        assertEquals(0, game.turn)
        assertEquals(1, game.lastCaptureSeat)
    }

    // §8.4
    @Test
    fun primiera() {
        val sideA = listOf(card(0, 7), card(3, 7), card(2, 6), card(1, 1))
        assertEquals(76, NeapolitanCards.primiera(sideA))
        val sideB = listOf(card(0, 1), card(1, 7), card(2, 7))
        assertEquals(0, NeapolitanCards.primiera(sideB))
        val score = ScopaGame.score(captures = listOf(sideA, sideB), scope = listOf(0, 2))
        assertEquals(listOf(1, 0), score.primiera)
        assertEquals(listOf(1, 0), score.settebello)
        assertEquals(listOf(76, 0), score.primieraValue)
        assertEquals(listOf(1, 0), score.cardsPoint)
        assertEquals(listOf(0, 0), score.denariPoint)
        assertEquals(listOf(3, 2), score.total)
        // Entrambe non valide: nessuno.
        assertEquals(listOf(0, 0), ScopaGame.score(listOf(sideB, sideB), listOf(0, 0)).primiera)
    }

    // §8.5
    @Test
    fun briscolaTrick() {
        val coppe = 1
        assertTrue(BriscolaGame.followerWins(lead = 20, follow = 11, trump = coppe))
        assertFalse(BriscolaGame.followerWins(lead = 2, follow = 9, trump = coppe))
        assertFalse(BriscolaGame.followerWins(lead = 9, follow = 20, trump = coppe))
        // Due briscole: la piu' forte; stesso seme: la piu' forte.
        assertTrue(BriscolaGame.followerWins(lead = 11, follow = 10, trump = coppe))
        assertTrue(BriscolaGame.followerWins(lead = 9, follow = 2, trump = coppe))
        assertFalse(BriscolaGame.followerWins(lead = 10, follow = 25, trump = coppe))
    }

    // §8.6
    @Test
    fun deal() {
        val scopa = ScopaGame(playerCount = 2, dealer = 0, deck = identityDeck)
        assertEquals(listOf(0, 2, 4), scopa.hands[1])
        assertEquals(listOf(1, 3, 5), scopa.hands[0])
        assertEquals(listOf(6, 7, 8, 9), scopa.table)
        assertEquals(1, scopa.turn)
        assertEquals(30, scopa.remainingInDeck)

        val scopone = ScopaGame(playerCount = 4, dealer = 0, deck = identityDeck)
        assertEquals((0 until 10).map { 4 * it }, scopone.hands[1])
        assertEquals((0 until 10).map { 4 * it + 1 }, scopone.hands[2])
        assertEquals((0 until 10).map { 4 * it + 2 }, scopone.hands[3])
        assertEquals((0 until 10).map { 4 * it + 3 }, scopone.hands[0])
        assertTrue(scopone.table.isEmpty())
        assertEquals(1, scopone.turn)

        val briscola = BriscolaGame(dealer = 0, deck = identityDeck)
        assertEquals(listOf(0, 2, 4), briscola.hands[1])
        assertEquals(listOf(1, 3, 5), briscola.hands[0])
        assertEquals(6, briscola.briscolaCard)
        assertEquals(0, briscola.trumpSuit)
        assertEquals((7 until 40).toList() + 6, briscola.stock)
        assertEquals(1, briscola.turn)

        // Mazziere 1: si parte dal posto 0.
        val other = ScopaGame(playerCount = 2, dealer = 1, deck = identityDeck)
        assertEquals(listOf(0, 2, 4), other.hands[0])
        assertEquals(0, other.turn)
    }

    @Test
    fun scopaDealsAgainAndRotatesDealer() {
        val game = ScopaGame(playerCount = 2, dealer = 0, deck = identityDeck)
        repeat(6) {
            val seat = game.turn
            val card = game.hands[seat].first()
            game.play(seat, card, game.captureOptions(card).firstOrNull() ?: emptyList())
        }
        // Mani vuote, mazzo non finito: altre 3 carte a testa, riparte il giocatore dopo il mazziere.
        assertEquals(listOf(3, 3), game.hands.map { it.size })
        assertEquals(1, game.turn)
        assertEquals(24, game.remainingInDeck)
        playScriptedHand(game)
        assertEquals(ScopaGame.Phase.handOver, game.phase)
        assertEquals(40, game.lastHandScore!!.cards.sum())
        assertThrows(ScopaGame.Failure.wrongDealer::class.java) { game.deal(identityDeck, dealer = 0) }
        assertThrows(ScopaGame.Failure.invalidDeck::class.java) { game.deal(identityDeck.drop(1) + 1, dealer = 1) }
        game.deal(identityDeck.reversed(), dealer = 1)
        assertEquals(2, game.handNumber)
        assertEquals(0, game.turn)
        assertEquals(ScopaGame.Phase.playing, game.phase)
        assertThrows(ScopaGame.Failure.notPlaying::class.java) { game.deal(identityDeck, dealer = 0) }
    }

    @Test
    fun matchPlaysUntilElevenWithDistinctTotals() {
        val random = java.util.Random(11)
        val game = ScopaGame(playerCount = 2, dealer = 0, deck = identityDeck.shuffled(random))
        var hands = 0
        while (true) {
            playScriptedHand(game)
            hands += 1
            if (game.isMatchOver) break
            assertEquals(ScopaGame.Phase.handOver, game.phase)
            assertTrue(game.scores.max() < ScopaGame.targetScore || game.scores[0] == game.scores[1])
            game.deal(identityDeck.shuffled(random), dealer = game.nextDealer)
        }
        assertTrue(game.scores.max() >= 11 && game.scores[0] != game.scores[1])
        assertEquals(if (game.scores[0] > game.scores[1]) 0 else 1, game.winnerSide)
        assertEquals(hands, game.handNumber)
    }

    @Test
    fun briscolaFullHandAndDraws() {
        val game = BriscolaGame(dealer = 0, deck = identityDeck)
        // Seat 1 apre con 0 (A♦, briscola), seat 0 risponde con 1 (2♦): vince seat 1, pesca per primo.
        assertThrows(BriscolaGame.Failure.notYourTurn::class.java) { game.play(0, 1) }
        assertNull(game.play(1, 0))
        val trick = game.play(0, 1)!!
        assertEquals(1, trick.winner)
        assertEquals(11, trick.points)
        assertEquals(listOf(0, 11), game.points)
        assertEquals(listOf(3, 5, 8), game.hands[0])
        assertEquals(listOf(2, 4, 7), game.hands[1])
        assertEquals(1, game.turn)
        while (!game.isFinished) {
            val seat = game.turn
            game.play(seat, game.hands[seat].first())
        }
        assertEquals(120, game.points.sum())
        assertEquals(40, game.won.sumOf { it.size })
        assertTrue(game.won.flatten().contains(6))
    }

    // §8.7 — mano scriptata dal mazzo identita' (valori attesi calcolati e confrontati con iOS).
    @Test
    fun scriptedScopaHand() {
        val game = ScopaGame(playerCount = 2, dealer = 0, deck = identityDeck)
        playScriptedHand(game)
        val score = game.lastHandScore!!
        println("§8.7 Scopa: $score scores=${game.scores}")
        assertEquals(SCOPA_EXPECTED, score)
    }

    @Test
    fun scriptedScoponeHand() {
        val game = ScopaGame(playerCount = 4, dealer = 0, deck = identityDeck)
        playScriptedHand(game)
        val score = game.lastHandScore!!
        println("§8.7 Scopone: $score scores=${game.scores}")
        assertEquals(SCOPONE_EXPECTED, score)
    }

    @Test
    fun scriptedBriscolaHand() {
        val game = BriscolaGame(dealer = 0, deck = identityDeck)
        while (!game.isFinished) {
            val seat = game.turn
            game.play(seat, game.hands[seat].first())
        }
        println("§8.7 Briscola: points=${game.points} winner=${game.winnerSeat}")
        assertEquals(BRISCOLA_EXPECTED_POINTS, game.points)
        assertEquals(BRISCOLA_EXPECTED_WINNER, game.winnerSeat)
    }

    companion object {
        /** §8.7 Scopa (2 giocatori, mazziere 0, mazzo identita'): parte 0 = posto 0, parte 1 = posto 1. */
        val SCOPA_EXPECTED = ScopaGame.HandScore(
            cards = listOf(14, 26), denari = listOf(4, 6), settebello = listOf(0, 1), primieraValue = listOf(72, 84),
            primiera = listOf(0, 1), scope = listOf(1, 1), total = listOf(1, 5),
        )
        /** §8.7 Scopone (mazziere 0, mazzo identita'): parte 0 = posti {0, 2}, parte 1 = posti {1, 3}. */
        val SCOPONE_EXPECTED = ScopaGame.HandScore(
            cards = listOf(12, 28), denari = listOf(3, 7), settebello = listOf(0, 1), primieraValue = listOf(72, 84),
            primiera = listOf(0, 1), scope = listOf(1, 2), total = listOf(1, 6),
        )
        /** §8.7 Briscola (mazziere 0, mazzo identita'): punti per posto. */
        val BRISCOLA_EXPECTED_POINTS = listOf(59, 61)
        val BRISCOLA_EXPECTED_WINNER: Int? = 1
    }
}
