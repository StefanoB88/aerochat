package com.example.aerochat.core.games

import org.json.JSONArray
import org.json.JSONObject

/**
 * Motore condiviso di Scopa (2 giocatori) e Scopone scientifico (4 giocatori, squadre {0,2} contro {1,3}).
 * Deterministico: tutti i dispositivi applicano gli stessi DEAL/MOVE nello stesso ordine e ottengono lo
 * stesso stato. Le "parti" (side) sono sempre 2: `side = posto % 2`. Regole: CARD_GAMES_SPEC §4-§5.
 *
 * Su iOS e' una `struct ScopaGame` (valore, `Codable`): qui e' una classe mutabile con [copy] esplicito;
 * le proprieta' hanno gli stessi nomi (= chiavi JSON in `messages.game_state`).
 */
class ScopaGame private constructor(val playerCount: Int) {

    enum class Phase(val rawValue: String) {
        /** Mano in corso. */
        playing("playing"),
        /** Mano finita e punteggio sommato: si attende il DEAL del prossimo mazziere. */
        handOver("handOver"),
        /** Partita finita (una parte ha raggiunto 11 punti con totali diversi). */
        matchOver("matchOver");

        companion object {
            fun fromRawValue(rawValue: String): Phase? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    sealed class Failure(message: String) : Exception(message) {
        data object invalidDeck : Failure("invalidDeck")
        data object invalidSeat : Failure("invalidSeat")
        data object wrongDealer : Failure("wrongDealer")
        data object notPlaying : Failure("notPlaying")
        data object notYourTurn : Failure("notYourTurn")
        data object cardNotInHand : Failure("cardNotInHand")
        data object illegalCapture : Failure("illegalCapture")
    }

    /** Una giocata applicata (per l'ultima mossa mostrata al tavolo). */
    data class Play(val seat: Int, val card: Int, val captured: List<Int>, val scopa: Boolean)

    /** Punteggio di una mano, per parte (indice 0 = posti pari, 1 = posti dispari). */
    data class HandScore(
        val cards: List<Int>,
        val denari: List<Int>,
        val settebello: List<Int>,
        /** Valore di primiera (0 = non valida, manca un seme). */
        val primieraValue: List<Int>,
        val primiera: List<Int>,
        val scope: List<Int>,
        /** Punti della mano: carte + denari + settebello + primiera + scope. */
        val total: List<Int>,
    ) {
        /** Punto "carte": a chi ne ha di piu'. */
        val cardsPoint: List<Int> get() = majority(cards)
        val denariPoint: List<Int> get() = majority(denari)

        companion object {
            fun majority(values: List<Int>): List<Int> {
                if (values.size != 2 || values[0] == values[1]) return listOf(0, 0)
                return if (values[0] > values[1]) listOf(1, 0) else listOf(0, 1)
            }
        }
    }

    var handNumber: Int = 0
        private set
    var dealer: Int = 0
        private set
    /** Ordine delle 40 carte della mano corrente (dal DEAL). */
    var deck: List<Int> = emptyList()
        private set
    /** Carte gia' distribuite da `deck`. */
    var dealtCount: Int = 0
        private set
    /** Carte in mano per posto, nell'ordine di distribuzione. */
    var hands: List<List<Int>> = List(playerCount) { emptyList() }
        private set
    var table: List<Int> = emptyList()
        private set
    /** Mazzetti delle prese per parte. */
    var captures: List<List<Int>> = listOf(emptyList(), emptyList())
        private set
    var scope: List<Int> = listOf(0, 0)
        private set
    var lastCaptureSeat: Int? = null
        private set
    var turn: Int = 0
        private set
    var phase: Phase = Phase.playing
        private set
    /** Totali della partita per parte. */
    var scores: List<Int> = listOf(0, 0)
        private set
    var lastHandScore: HandScore? = null
        private set
    var lastPlay: Play? = null
        private set
    var winnerSide: Int? = null
        private set

    /** Prima mano: `dealer` distribuisce secondo `deck` (Scopa 3+3 e 4 in tavola, Scopone 10 a testa). */
    constructor(playerCount: Int, dealer: Int, deck: List<Int>) : this(playerCount) {
        if (playerCount != 2 && playerCount != 4) throw Failure.invalidSeat
        if (dealer !in 0 until playerCount) throw Failure.invalidSeat
        if (!NeapolitanCards.isValidDeck(deck)) throw Failure.invalidDeck
        startHand(dealer, deck)
    }

    val isScopone: Boolean get() = playerCount == 4
    /** Mazziere della prossima mano (ruota in ordine di turno). */
    val nextDealer: Int get() = (dealer + 1) % playerCount
    val remainingInDeck: Int get() = deck.size - dealtCount
    val isMatchOver: Boolean get() = phase == Phase.matchOver

    /** Nuova mano dopo `handOver`: solo il mazziere atteso puo' distribuire. */
    fun deal(deck: List<Int>, dealer: Int) {
        if (phase != Phase.handOver) throw Failure.notPlaying
        if (dealer != nextDealer) throw Failure.wrongDealer
        if (!NeapolitanCards.isValidDeck(deck)) throw Failure.invalidDeck
        startHand(dealer, deck)
    }

    private fun startHand(dealer: Int, deck: List<Int>) {
        handNumber += 1
        this.dealer = dealer
        this.deck = deck.toList()
        dealtCount = 0
        hands = List(playerCount) { emptyList() }
        table = emptyList()
        captures = listOf(emptyList(), emptyList())
        scope = listOf(0, 0)
        lastCaptureSeat = null
        lastPlay = null
        turn = (dealer + 1) % playerCount
        phase = Phase.playing
        if (isScopone) {
            dealRound(cardsEach = 10)
        } else {
            dealRound(cardsEach = 3)
            table = deck.subList(dealtCount, dealtCount + 4).toList()
            dealtCount += 4
        }
    }

    /** Una carta alla volta in ordine di turno partendo dal giocatore dopo il mazziere. */
    private fun dealRound(cardsEach: Int) {
        val count = minOf(cardsEach * playerCount, remainingInDeck)
        val newHands = hands.map { it.toMutableList() }
        for (k in 0 until count) newHands[(dealer + 1 + k) % playerCount].add(deck[dealtCount + k])
        hands = newHands
        dealtCount += count
    }

    // MARK: Prese

    fun captureOptions(card: Int): List<List<Int>> = captureOptions(card, table)

    // MARK: Giocata

    /** Verifica la giocata senza applicarla (stesse condizioni di [play]). */
    fun validate(seat: Int, card: Int, captured: List<Int>) {
        if (phase != Phase.playing) throw Failure.notPlaying
        if (seat !in 0 until playerCount) throw Failure.invalidSeat
        if (seat != turn) throw Failure.notYourTurn
        if (!hands[seat].contains(card)) throw Failure.cardNotInHand
        if (!isLegalCapture(captured, card, table)) throw Failure.illegalCapture
    }

    /** Applica la giocata del posto `seat`. Restituisce la giocata (con l'eventuale scopa). */
    fun play(seat: Int, card: Int, captured: List<Int>): Play {
        validate(seat, card, captured)
        hands = hands.mapIndexed { index, hand -> if (index == seat) hand - card else hand }
        val side = side(seat)
        val isLastPlay = remainingInDeck == 0 && hands.all { it.isEmpty() }
        var isScopa = false
        if (captured.isEmpty()) {
            table = table + card
        } else {
            val taken = captured.toSet()
            table = table.filter { it !in taken }
            captures = captures.mapIndexed { index, pile -> if (index == side) pile + captured.sorted() + card else pile }
            lastCaptureSeat = seat
            // L'ultima giocata della mano non vale mai scopa.
            if (table.isEmpty() && !isLastPlay) {
                scope = scope.mapIndexed { index, count -> if (index == side) count + 1 else count }
                isScopa = true
            }
        }
        val applied = Play(seat = seat, card = card, captured = captured.sorted(), scopa = isScopa)
        lastPlay = applied
        turn = (seat + 1) % playerCount

        if (hands.all { it.isEmpty() }) {
            if (remainingInDeck > 0) dealRound(cardsEach = 3) else endHand()
        }
        return applied
    }

    private fun endHand() {
        // Le carte rimaste in tavola vanno a chi ha fatto l'ultima presa (non e' scopa).
        lastCaptureSeat?.let { seat ->
            val side = side(seat)
            captures = captures.mapIndexed { index, pile -> if (index == side) pile + table else pile }
        }
        table = emptyList()
        val score = score(captures, scope)
        lastHandScore = score
        scores = scores.mapIndexed { side, total -> total + score.total[side] }
        if (scores.any { it >= targetScore } && scores[0] != scores[1]) {
            winnerSide = if (scores[0] > scores[1]) 0 else 1
            phase = Phase.matchOver
        } else {
            phase = Phase.handOver
        }
    }

    /** JSON con le chiavi del `Codable` iOS (gli opzionali `nil` sono omessi, come `encodeIfPresent`). */
    internal fun toJSON(): JSONObject {
        val json = JSONObject()
            .put("playerCount", playerCount)
            .put("handNumber", handNumber)
            .put("dealer", dealer)
            .put("deck", JSONArray(deck))
            .put("dealtCount", dealtCount)
            .put("hands", JSONArray(hands.map { JSONArray(it) }))
            .put("table", JSONArray(table))
            .put("captures", JSONArray(captures.map { JSONArray(it) }))
            .put("scope", JSONArray(scope))
            .put("turn", turn)
            .put("phase", phase.rawValue)
            .put("scores", JSONArray(scores))
        lastCaptureSeat?.let { json.put("lastCaptureSeat", it) }
        lastHandScore?.let { score ->
            json.put(
                "lastHandScore",
                JSONObject()
                    .put("cards", JSONArray(score.cards))
                    .put("denari", JSONArray(score.denari))
                    .put("settebello", JSONArray(score.settebello))
                    .put("primieraValue", JSONArray(score.primieraValue))
                    .put("primiera", JSONArray(score.primiera))
                    .put("scope", JSONArray(score.scope))
                    .put("total", JSONArray(score.total)),
            )
        }
        lastPlay?.let { play ->
            json.put(
                "lastPlay",
                JSONObject().put("seat", play.seat).put("card", play.card)
                    .put("captured", JSONArray(play.captured)).put("scopa", play.scopa),
            )
        }
        winnerSide?.let { json.put("winnerSide", it) }
        return json
    }

    fun copy(): ScopaGame {
        val other = ScopaGame(playerCount)
        other.handNumber = handNumber
        other.dealer = dealer
        other.deck = deck
        other.dealtCount = dealtCount
        other.hands = hands
        other.table = table
        other.captures = captures
        other.scope = scope
        other.lastCaptureSeat = lastCaptureSeat
        other.turn = turn
        other.phase = phase
        other.scores = scores
        other.lastHandScore = lastHandScore
        other.lastPlay = lastPlay
        other.winnerSide = winnerSide
        return other
    }

    override fun equals(other: Any?): Boolean =
        other is ScopaGame && playerCount == other.playerCount && handNumber == other.handNumber &&
            dealer == other.dealer && deck == other.deck && dealtCount == other.dealtCount && hands == other.hands &&
            table == other.table && captures == other.captures && scope == other.scope &&
            lastCaptureSeat == other.lastCaptureSeat && turn == other.turn && phase == other.phase &&
            scores == other.scores && lastHandScore == other.lastHandScore && lastPlay == other.lastPlay &&
            winnerSide == other.winnerSide

    override fun hashCode(): Int {
        var result = playerCount
        result = 31 * result + handNumber
        result = 31 * result + deck.hashCode()
        result = 31 * result + hands.hashCode()
        result = 31 * result + table.hashCode()
        result = 31 * result + scores.hashCode()
        return result
    }

    override fun toString(): String =
        "ScopaGame(players=$playerCount, hand=$handNumber, dealer=$dealer, turn=$turn, phase=$phase, hands=$hands, " +
            "table=$table, captures=${captures.map { it.size }}, scope=$scope, scores=$scores, winner=$winnerSide)"

    companion object {
        const val targetScore = 11
        const val sides = 2

        fun side(seat: Int): Int = seat % 2

        /**
         * Prese legali giocando `card` sul tavolo `table`: carte di pari valore (una sola, a scelta) che
         * escludono le combinazioni; altrimenti le combinazioni di 2+ carte con la stessa somma. Ordine
         * canonico: ogni presa ordinata per id, poi ordine lessicografico crescente.
         */
        fun captureOptions(card: Int, table: List<Int>): List<List<Int>> {
            val value = NeapolitanCards.rank(card)
            val sameValue = table.filter { NeapolitanCards.rank(it) == value }.sorted()
            if (sameValue.isNotEmpty()) return sameValue.map { listOf(it) }

            val sortedTable = table.sorted()
            val options = ArrayList<List<Int>>()
            val current = ArrayList<Int>()
            fun search(from: Int, remaining: Int) {
                if (remaining == 0) {
                    if (current.size >= 2) options.add(current.toList())
                    return
                }
                for (next in from until sortedTable.size) {
                    val cardValue = NeapolitanCards.rank(sortedTable[next])
                    if (cardValue > remaining) continue
                    current.add(sortedTable[next])
                    search(next + 1, remaining - cardValue)
                    current.removeAt(current.size - 1)
                }
            }
            search(0, value)
            return options.sortedWith(lexicographicOrder)
        }

        /** `captured` legale come insieme (ordine indifferente); vuoto solo se non esiste alcuna presa. */
        fun isLegalCapture(captured: List<Int>, card: Int, table: List<Int>): Boolean {
            val options = captureOptions(card, table)
            if (options.isEmpty()) return captured.isEmpty()
            return options.contains(captured.sorted())
        }

        /** Swift `lexicographicallyPrecedes`. */
        val lexicographicOrder: Comparator<List<Int>> = Comparator { a, b ->
            for (i in 0 until minOf(a.size, b.size)) {
                if (a[i] != b[i]) return@Comparator a[i].compareTo(b[i])
            }
            a.size.compareTo(b.size)
        }

        /** Punteggio di una mano a partire dai mazzetti delle due parti. */
        fun score(captures: List<List<Int>>, scope: List<Int>): HandScore {
            val cards = captures.map { it.size }
            val denari = captures.map { pile -> pile.count { NeapolitanCards.suit(it) == 0 } }
            val settebello = captures.map { if (it.contains(NeapolitanCards.settebello)) 1 else 0 }
            val primieraValue = captures.map { NeapolitanCards.primiera(it) }
            val primiera = HandScore.majority(primieraValue)
            val cardsPoint = HandScore.majority(cards)
            val denariPoint = HandScore.majority(denari)
            val total = (0 until sides).map { side ->
                cardsPoint[side] + denariPoint[side] + settebello[side] + primiera[side] + scope[side]
            }
            return HandScore(
                cards = cards, denari = denari, settebello = settebello, primieraValue = primieraValue,
                primiera = primiera, scope = scope, total = total,
            )
        }

        /** Decodifica del JSON `Codable` di iOS (chiavi = nomi delle proprieta'). */
        internal fun fromJSON(json: JSONObject): ScopaGame? {
            val lastHandScore = json.optJSONObject("lastHandScore")?.let { obj ->
                HandScore(
                    cards = obj.getJSONArray("cards").ints(), denari = obj.getJSONArray("denari").ints(),
                    settebello = obj.getJSONArray("settebello").ints(),
                    primieraValue = obj.getJSONArray("primieraValue").ints(),
                    primiera = obj.getJSONArray("primiera").ints(), scope = obj.getJSONArray("scope").ints(),
                    total = obj.getJSONArray("total").ints(),
                )
            }
            val lastPlay = json.optJSONObject("lastPlay")?.let { obj ->
                Play(
                    seat = obj.getInt("seat"), card = obj.getInt("card"),
                    captured = obj.getJSONArray("captured").ints(), scopa = obj.getBoolean("scopa"),
                )
            }
            return restore(
                playerCount = json.getInt("playerCount"),
                handNumber = json.optInt("handNumber", 1),
                dealer = json.getInt("dealer"),
                deck = json.getJSONArray("deck").ints(),
                dealtCount = json.getInt("dealtCount"),
                hands = json.getJSONArray("hands").intLists(),
                table = json.getJSONArray("table").ints(),
                captures = json.getJSONArray("captures").intLists(),
                scope = json.getJSONArray("scope").ints(),
                lastCaptureSeat = json.optIntOrNull("lastCaptureSeat"),
                turn = json.getInt("turn"),
                phase = Phase.fromRawValue(json.getString("phase")) ?: return null,
                scores = json.getJSONArray("scores").ints(),
                lastHandScore = lastHandScore,
                lastPlay = lastPlay,
                winnerSide = json.optIntOrNull("winnerSide"),
            )
        }

        /** Ricostruzione da `messages.game_state` (stato locale: validazione minima). */
        internal fun restore(
            playerCount: Int, handNumber: Int, dealer: Int, deck: List<Int>, dealtCount: Int,
            hands: List<List<Int>>, table: List<Int>, captures: List<List<Int>>, scope: List<Int>,
            lastCaptureSeat: Int?, turn: Int, phase: Phase, scores: List<Int>, lastHandScore: HandScore?,
            lastPlay: Play?, winnerSide: Int?,
        ): ScopaGame? {
            if (playerCount != 2 && playerCount != 4) return null
            if (hands.size != playerCount || captures.size != sides || scope.size != sides || scores.size != sides) return null
            if (dealtCount !in 0..deck.size) return null
            val game = ScopaGame(playerCount)
            game.handNumber = handNumber
            game.dealer = dealer
            game.deck = deck
            game.dealtCount = dealtCount
            game.hands = hands
            game.table = table
            game.captures = captures
            game.scope = scope
            game.lastCaptureSeat = lastCaptureSeat
            game.turn = turn
            game.phase = phase
            game.scores = scores
            game.lastHandScore = lastHandScore
            game.lastPlay = lastPlay
            game.winnerSide = winnerSide
            return game
        }
    }
}

internal fun JSONArray.ints(): List<Int> = (0 until length()).map { getInt(it) }

internal fun JSONArray.intLists(): List<List<Int>> = (0 until length()).map { getJSONArray(it).ints() }

internal fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
