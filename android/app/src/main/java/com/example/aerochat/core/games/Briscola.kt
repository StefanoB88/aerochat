package com.example.aerochat.core.games

import org.json.JSONArray
import org.json.JSONObject

/**
 * Briscola a 2 giocatori, una mano da 120 punti carta. Deterministica a partire dal `deck` del DEAL:
 * 3 carte a testa, `deck[6]` scoperta e' la briscola (seme di briscola) e finisce in fondo al tallone.
 * Dopo ogni presa pesca prima chi ha preso, poi l'altro (CARD_GAMES_SPEC §4-§5).
 *
 * Su iOS e' una `struct BriscolaGame` (valore, `Codable`): qui e' una classe mutabile con [copy] esplicito;
 * le proprieta' hanno gli stessi nomi (= chiavi JSON in `messages.game_state`).
 */
class BriscolaGame private constructor() {

    enum class Phase(val rawValue: String) {
        playing("playing"),
        finished("finished");

        companion object {
            fun fromRawValue(rawValue: String): Phase? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    sealed class Failure(message: String) : Exception(message) {
        data object invalidDeck : Failure("invalidDeck")
        data object invalidSeat : Failure("invalidSeat")
        data object notPlaying : Failure("notPlaying")
        data object notYourTurn : Failure("notYourTurn")
        data object cardNotInHand : Failure("cardNotInHand")
    }

    /** Presa completata: carte nell'ordine di gioco (prima quella di chi apre). */
    data class Trick(val leader: Int, val cards: List<Int>, val winner: Int, val points: Int)

    var dealer: Int = 0
        private set
    var hands: List<List<Int>> = listOf(emptyList(), emptyList())
        private set
    /** Tallone: si pesca dal primo elemento; l'ultimo e' la briscola scoperta. */
    var stock: List<Int> = emptyList()
        private set
    var briscolaCard: Int = 0
        private set
    /** Presa in corso: carte gia' giocate (0, 1). */
    var trick: List<Int> = emptyList()
        private set
    var leader: Int = 0
        private set
    var turn: Int = 0
        private set
    var points: List<Int> = listOf(0, 0)
        private set
    /** Carte vinte per posto. */
    var won: List<List<Int>> = listOf(emptyList(), emptyList())
        private set
    var lastTrick: Trick? = null
        private set
    var phase: Phase = Phase.playing
        private set
    /** Vincitore a fine mano; `null` con `phase == finished` = pareggio 60-60. */
    var winnerSeat: Int? = null
        private set

    constructor(dealer: Int, deck: List<Int>) : this() {
        if (dealer !in 0 until playerCount) throw Failure.invalidSeat
        if (!NeapolitanCards.isValidDeck(deck)) throw Failure.invalidDeck
        this.dealer = dealer
        val hands = listOf(mutableListOf<Int>(), mutableListOf())
        for (k in 0 until 6) hands[(dealer + 1 + k) % playerCount].add(deck[k])
        this.hands = hands
        briscolaCard = deck[6]
        stock = deck.drop(7) + deck[6]
        leader = (dealer + 1) % playerCount
        turn = leader
    }

    val trumpSuit: Int get() = NeapolitanCards.suit(briscolaCard)
    val isFinished: Boolean get() = phase == Phase.finished

    fun validate(seat: Int, card: Int) {
        if (phase != Phase.playing) throw Failure.notPlaying
        if (seat !in 0 until playerCount) throw Failure.invalidSeat
        if (seat != turn) throw Failure.notYourTurn
        if (!hands[seat].contains(card)) throw Failure.cardNotInHand
    }

    /** Gioca una carta; restituisce la presa se questa carta la completa. */
    fun play(seat: Int, card: Int): Trick? {
        validate(seat, card)
        hands = hands.mapIndexed { index, hand -> if (index == seat) hand - card else hand }
        trick = trick + card
        if (trick.size < playerCount) {
            turn = (seat + 1) % playerCount
            return null
        }

        val follower = (leader + 1) % playerCount
        val winner = if (followerWins(lead = trick[0], follow = trick[1], trump = trumpSuit)) follower else leader
        val trickPoints = trick.sumOf { NeapolitanCards.briscolaPoints(it) }
        points = points.mapIndexed { index, value -> if (index == winner) value + trickPoints else value }
        won = won.mapIndexed { index, pile -> if (index == winner) pile + trick else pile }
        val completed = Trick(leader = leader, cards = trick, winner = winner, points = trickPoints)
        lastTrick = completed
        trick = emptyList()
        // Pesca: prima chi ha preso, poi l'altro, finche' c'e' tallone.
        if (stock.isNotEmpty()) {
            val drawn = hands.map { it.toMutableList() }
            drawn[winner].add(stock.first())
            stock = stock.drop(1)
            if (stock.isNotEmpty()) {
                drawn[(winner + 1) % playerCount].add(stock.first())
                stock = stock.drop(1)
            }
            hands = drawn
        }
        leader = winner
        turn = winner
        if (stock.isEmpty() && hands.all { it.isEmpty() }) {
            phase = Phase.finished
            if (points[0] != points[1]) winnerSeat = if (points[0] > points[1]) 0 else 1
        }
        return completed
    }

    /** JSON con le chiavi del `Codable` iOS (gli opzionali `nil` sono omessi, come `encodeIfPresent`). */
    internal fun toJSON(): JSONObject {
        val json = JSONObject()
            .put("dealer", dealer)
            .put("hands", JSONArray(hands.map { JSONArray(it) }))
            .put("stock", JSONArray(stock))
            .put("briscolaCard", briscolaCard)
            .put("trick", JSONArray(trick))
            .put("leader", leader)
            .put("turn", turn)
            .put("points", JSONArray(points))
            .put("won", JSONArray(won.map { JSONArray(it) }))
            .put("phase", phase.rawValue)
        lastTrick?.let { trick ->
            json.put(
                "lastTrick",
                JSONObject().put("leader", trick.leader).put("cards", JSONArray(trick.cards))
                    .put("winner", trick.winner).put("points", trick.points),
            )
        }
        winnerSeat?.let { json.put("winnerSeat", it) }
        return json
    }

    fun copy(): BriscolaGame {
        val other = BriscolaGame()
        other.dealer = dealer
        other.hands = hands
        other.stock = stock
        other.briscolaCard = briscolaCard
        other.trick = trick
        other.leader = leader
        other.turn = turn
        other.points = points
        other.won = won
        other.lastTrick = lastTrick
        other.phase = phase
        other.winnerSeat = winnerSeat
        return other
    }

    override fun equals(other: Any?): Boolean =
        other is BriscolaGame && dealer == other.dealer && hands == other.hands && stock == other.stock &&
            briscolaCard == other.briscolaCard && trick == other.trick && leader == other.leader &&
            turn == other.turn && points == other.points && won == other.won && lastTrick == other.lastTrick &&
            phase == other.phase && winnerSeat == other.winnerSeat

    override fun hashCode(): Int = 31 * (31 * hands.hashCode() + stock.hashCode()) + points.hashCode()

    override fun toString(): String =
        "BriscolaGame(dealer=$dealer, turn=$turn, hands=$hands, briscola=$briscolaCard, stock=${stock.size}, " +
            "trick=$trick, points=$points, phase=$phase)"

    companion object {
        const val playerCount = 2
        const val totalPoints = 120

        /**
         * `true` se vince chi risponde: una sola briscola vince; due briscole o stesso seme → piu' forte;
         * seme diverso non di briscola → vince chi ha aperto.
         */
        fun followerWins(lead: Int, follow: Int, trump: Int): Boolean {
            val leadSuit = NeapolitanCards.suit(lead)
            val followSuit = NeapolitanCards.suit(follow)
            val leadTrump = leadSuit == trump
            val followTrump = followSuit == trump
            if (leadTrump != followTrump) return followTrump
            if (followSuit != leadSuit) return false
            return NeapolitanCards.briscolaStrength(follow) > NeapolitanCards.briscolaStrength(lead)
        }

        /** Decodifica del JSON `Codable` di iOS. */
        internal fun fromJSON(json: JSONObject): BriscolaGame? {
            val lastTrick = json.optJSONObject("lastTrick")?.let { obj ->
                Trick(
                    leader = obj.getInt("leader"), cards = obj.getJSONArray("cards").ints(),
                    winner = obj.getInt("winner"), points = obj.getInt("points"),
                )
            }
            return restore(
                dealer = json.getInt("dealer"),
                hands = json.getJSONArray("hands").intLists(),
                stock = json.getJSONArray("stock").ints(),
                briscolaCard = json.getInt("briscolaCard"),
                trick = json.getJSONArray("trick").ints(),
                leader = json.getInt("leader"),
                turn = json.getInt("turn"),
                points = json.getJSONArray("points").ints(),
                won = json.getJSONArray("won").intLists(),
                lastTrick = lastTrick,
                phase = Phase.fromRawValue(json.getString("phase")) ?: return null,
                winnerSeat = json.optIntOrNull("winnerSeat"),
            )
        }

        /** Ricostruzione da `messages.game_state` (stato locale: validazione minima). */
        internal fun restore(
            dealer: Int, hands: List<List<Int>>, stock: List<Int>, briscolaCard: Int, trick: List<Int>,
            leader: Int, turn: Int, points: List<Int>, won: List<List<Int>>, lastTrick: Trick?, phase: Phase,
            winnerSeat: Int?,
        ): BriscolaGame? {
            if (hands.size != playerCount || points.size != playerCount || won.size != playerCount) return null
            val game = BriscolaGame()
            game.dealer = dealer
            game.hands = hands
            game.stock = stock
            game.briscolaCard = briscolaCard
            game.trick = trick
            game.leader = leader
            game.turn = turn
            game.points = points
            game.won = won
            game.lastTrick = lastTrick
            game.phase = phase
            game.winnerSeat = winnerSeat
            return game
        }
    }
}
