package com.stefanobrandi.aerochat.core.games

import java.security.SecureRandom
import java.util.Random

/**
 * Mazzo napoletano da 40 carte condiviso da Scopa, Scopone scientifico e Briscola.
 *
 * Id carta `0..39` = `seme * 10 + (valore - 1)`; semi `0 = denari`, `1 = coppe`, `2 = spade`,
 * `3 = bastoni`; valori `1..10` (1 = Asso, 8 = Fante, 9 = Cavallo, 10 = Re). Lo stesso id viaggia in
 * `GameMove.cell` / `deck` / `captured` (contratto iOS <-> Android, CARD_GAMES_SPEC §3).
 */
object NeapolitanCards {

    const val deckSize = 40
    const val suitCount = 4

    /** Id della carta dato seme (0..3) e valore (1..10). */
    fun id(suit: Int, rank: Int): Int = suit * 10 + (rank - 1)

    fun suit(card: Int): Int = card / 10

    fun rank(card: Int): Int = card % 10 + 1

    fun isValid(card: Int): Boolean = card in 0 until deckSize

    /** Il settebello: 7 di denari. */
    const val settebello = 6

    /** Fante, Cavallo, Re. */
    fun isFigure(card: Int): Boolean = rank(card) >= 8

    /** Valore di primiera: 7→21, 6→18, Asso→16, 5→15, 4→14, 3→13, 2→12, figure→10. */
    fun primieraValue(card: Int): Int = when (rank(card)) {
        7 -> 21
        6 -> 18
        1 -> 16
        5 -> 15
        4 -> 14
        3 -> 13
        2 -> 12
        else -> 10
    }

    /** Primiera di un mazzetto: somma del miglior valore per seme; 0 (non valida) se manca un seme. */
    fun primiera(cards: List<Int>): Int {
        val best = IntArray(suitCount)
        for (card in cards) {
            if (!isValid(card)) continue
            best[suit(card)] = maxOf(best[suit(card)], primieraValue(card))
        }
        return if (best.contains(0)) 0 else best.sum()
    }

    /** Forza nella Briscola (piu' alto = piu' forte): Asso, 3, Re, Cavallo, Fante, 7, 6, 5, 4, 2. */
    fun briscolaStrength(card: Int): Int = when (rank(card)) {
        1 -> 10
        3 -> 9
        10 -> 8
        9 -> 7
        8 -> 6
        7 -> 5
        6 -> 4
        5 -> 3
        4 -> 2
        else -> 1
    }

    /** Punti nella Briscola: Asso 11, 3 10, Re 4, Cavallo 3, Fante 2, altre 0 (totale 120). */
    fun briscolaPoints(card: Int): Int = when (rank(card)) {
        1 -> 11
        3 -> 10
        10 -> 4
        9 -> 3
        8 -> 2
        else -> 0
    }

    /** `deck` di un DEAL valido: esattamente una permutazione di 0..39. */
    fun isValidDeck(deck: List<Int>): Boolean =
        deck.size == deckSize && deck.toSet().size == deckSize && deck.all { isValid(it) }

    /** Mescolata uniforme (Fisher-Yates con `SecureRandom`) per il DEAL del mazziere. */
    fun shuffledDeck(random: Random = SecureRandom()): List<Int> {
        val deck = (0 until deckSize).toMutableList()
        for (i in deck.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = deck[i]
            deck[i] = deck[j]
            deck[j] = tmp
        }
        return deck
    }

    /** `deck` del protobuf (40 byte) → id carta; `null` se non e' una permutazione valida. */
    fun deck(bytes: ByteArray): List<Int>? {
        val deck = bytes.map { it.toInt() and 0xFF }
        return if (isValidDeck(deck)) deck else null
    }

    fun bytes(cards: List<Int>): ByteArray = ByteArray(cards.size) { cards[it].toByte() }

    /** Etichetta d'angolo: il valore da 1 a 10 (come sulle carte napoletane numerate). */
    fun cornerLabel(card: Int): String = rank(card).toString()

    /** Simbolo del seme: ♦ denari, ♥ coppe, ♠ spade, ♣ bastoni. */
    fun suitSymbol(suit: Int): String = when (suit) {
        0 -> "♦"
        1 -> "♥"
        2 -> "♠"
        else -> "♣"
    }

    /** Nome breve senza risorse (log, test): "7 Denari", "10 Bastoni". In UI usare `cardShortNamer()`. */
    fun shortName(card: Int): String = "${rank(card)} " + when (suit(card)) {
        0 -> "Denari"
        1 -> "Coppe"
        2 -> "Spade"
        else -> "Bastoni"
    }
}
