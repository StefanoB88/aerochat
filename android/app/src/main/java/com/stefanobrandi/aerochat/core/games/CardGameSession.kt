package com.stefanobrandi.aerochat.core.games

import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.proto.GameAction
import com.stefanobrandi.aerochat.proto.GameKind
import com.stefanobrandi.aerochat.proto.GameMove
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Giochi di carte napoletane (Scopa, Scopone scientifico, Briscola). */
val GameKind.isCardGame: Boolean
    get() = this == GameKind.SCOPA || this == GameKind.SCOPONE || this == GameKind.BRISCOLA

/** Giocatori al tavolo: 4 nello Scopone, 2 altrimenti. */
val GameKind.cardPlayerCount: Int get() = if (this == GameKind.SCOPONE) 4 else 2

/**
 * DEAL o MOVE di una partita a carte ricevuto fuori ordine: resta nello stato finche' non arrivano
 * quelli mancanti (la rete store-and-forward garantisce che prima o poi arrivino).
 * `sequence` e' un UInt32 su iOS: qui `Long`.
 */
class BufferedCardMove(
    val sequence: Long,
    /** `GameAction.number` (solo DEAL o MOVE). */
    val action: Int,
    val senderNodeID: ByteArray,
    /** DEAL: posto del mazziere. MOVE: id della carta giocata. */
    val cell: Int,
    /** DEAL: le 40 carte nell'ordine di distribuzione. */
    val deck: List<Int> = emptyList(),
    /** MOVE (Scopa/Scopone): carte prese dal tavolo. */
    val captured: List<Int> = emptyList(),
) {
    constructor(
        sequence: Long, action: GameAction, senderNodeID: ByteArray, cell: Int,
        deck: List<Int> = emptyList(), captured: List<Int> = emptyList(),
    ) : this(sequence, action.number, senderNodeID, cell, deck, captured)

    val gameAction: GameAction get() = GameAction.forNumber(action) ?: GameAction.GAME_ACTION_UNSPECIFIED

    internal fun toJSON(): JSONObject = JSONObject()
        .put("sequence", sequence)
        .put("action", action)
        .put("senderNodeID", Base64.getEncoder().encodeToString(senderNodeID))
        .put("cell", cell)
        .put("deck", JSONArray(deck))
        .put("captured", JSONArray(captured))

    override fun equals(other: Any?): Boolean =
        other is BufferedCardMove && sequence == other.sequence && action == other.action &&
            senderNodeID.contentEquals(other.senderNodeID) && cell == other.cell && deck == other.deck &&
            captured == other.captured

    override fun hashCode(): Int = 31 * (31 * sequence.hashCode() + action) + senderNodeID.contentHashCode()

    override fun toString(): String = "BufferedCardMove(seq=$sequence, action=$action, sender=${senderNodeID.toHex()}, cell=$cell)"

    companion object {
        /** Swift `init(_ move: GameMove, senderNodeID:)`. */
        fun from(move: GameMove, senderNodeID: ByteArray): BufferedCardMove = BufferedCardMove(
            sequence = move.sequence.toLong() and 0xFFFF_FFFFL,
            action = move.action.number,
            senderNodeID = senderNodeID,
            cell = move.cell,
            deck = move.deck.toByteArray().map { it.toInt() and 0xFF },
            captured = move.captured.toByteArray().map { it.toInt() and 0xFF },
        )

        internal fun fromJSON(json: JSONObject): BufferedCardMove = BufferedCardMove(
            sequence = json.getLong("sequence"),
            action = json.getInt("action"),
            senderNodeID = Base64.getDecoder().decode(json.getString("senderNodeID")),
            cell = json.getInt("cell"),
            deck = json.optJSONArray("deck")?.ints() ?: emptyList(),
            captured = json.optJSONArray("captured")?.ints() ?: emptyList(),
        )
    }
}

// Logica pura delle partite a carte sopra `GameSessionState`: applicazione in ordine di DEAL/MOVE,
// buffer delle mosse fuori ordine, validazione di mittente, turno, carta e presa. `GameService` si
// occupa solo di rete e persistenza; i test usano direttamente queste funzioni.

sealed class CardFailure(message: String) : Exception(message) {
    data object notAPlayer : CardFailure("notAPlayer")
    data object wrongSender : CardFailure("wrongSender")
    data object invalidMove : CardFailure("invalidMove")
    data object notReady : CardFailure("notReady")
}

val GameSessionState.isCardGame: Boolean get() = gameKind.isCardGame
val GameSessionState.playerCount: Int get() = gameKind.cardPlayerCount
val GameSessionState.isSpectator: Boolean get() = isCardGame && mySeat == null

fun GameSessionState.seat(of: ByteArray): Int? = seats?.indexOfFirst { it.contentEquals(of) }?.takeIf { it >= 0 }

/** Nickname del posto; `null` se sconosciuto (la UI mostra "Giocatore N"). */
fun GameSessionState.nameOfSeat(seat: Int): String? = seatNames?.getOrNull(seat)

/** Posto di turno durante una mano (`null` tra una mano e l'altra o a partita finita). */
val GameSessionState.turnSeat: Int?
    get() {
        if (status != GameStatus.active) return null
        scopa?.let { if (it.phase == ScopaGame.Phase.playing) return it.turn }
        briscola?.let { if (it.phase == BriscolaGame.Phase.playing) return it.turn }
        return null
    }

/** Mazziere atteso per il prossimo DEAL (`null` se ora non serve distribuire). */
val GameSessionState.expectedDealer: Int?
    get() {
        if (status == GameStatus.finished) return null
        return when (gameKind) {
            GameKind.SCOPA, GameKind.SCOPONE -> {
                val game = scopa ?: return 0
                if (game.phase == ScopaGame.Phase.handOver) game.nextDealer else null
            }
            GameKind.BRISCOLA -> if (briscola == null) 0 else null
            else -> null
        }
    }

/** Tocca a noi distribuire: prima mano (chi invita, quando tutti hanno accettato) o mano successiva. */
val GameSessionState.shouldDeal: Boolean
    get() {
        val seat = mySeat ?: return false
        val dealer = expectedDealer ?: return false
        if (dealer != seat) return false
        if (scopa == null && briscola == null) {
            if (!iAmInviter) return false
            if (gameKind == GameKind.SCOPONE) return (acceptedSeats ?: emptyList()).containsAll(listOf(1, 2, 3))
            return status == GameStatus.active
        }
        return true
    }

/** Copia i campi di `other` su questo stato (Swift `self = updated`). */
private fun GameSessionState.assign(other: GameSessionState) {
    status = other.status
    sequence = other.sequence
    myTurn = other.myTurn
    outcome = other.outcome
    scopa = other.scopa
    briscola = other.briscola
    pendingMoves = other.pendingMoves
    acceptedSeats = other.acceptedSeats
    resignedSeat = other.resignedSeat
}

/**
 * Applica subito il DEAL/MOVE con `sequence == this.sequence + 1`, mette nel buffer quelli piu' avanti
 * (entro `maxGap`) e poi svuota il buffer finche' le sequenze sono contigue. Restituisce `true` se lo
 * stato e' cambiato. Duplicati e sequenze vecchie sono ignorati.
 */
fun GameSessionState.receiveCardMove(move: BufferedCardMove, maxGap: Long): Boolean {
    if (status == GameStatus.finished || move.sequence <= sequence || move.sequence - sequence > maxGap) return false
    var changed = false
    if (move.sequence == sequence + 1) {
        changed = runCatching { applyCardMove(move) }.isSuccess
    } else {
        val pending = (pendingMoves ?: emptyList()).toMutableList()
        if (pending.any { it.sequence == move.sequence }) return false
        pending.add(move)
        pending.sortBy { it.sequence }
        pendingMoves = pending
        changed = true
    }
    if (drainPendingMoves()) changed = true
    return changed
}

/** Applica le mosse in buffer che ora sono in sequenza. */
fun GameSessionState.drainPendingMoves(): Boolean {
    var changed = false
    while (true) {
        val pending = pendingMoves?.toMutableList() ?: break
        if (pending.isEmpty()) break
        pending.removeAll { it.sequence <= sequence }
        val index = pending.indexOfFirst { it.sequence == sequence + 1 }
        if (index < 0) {
            pendingMoves = pending.ifEmpty { null }
            break
        }
        val next = pending.removeAt(index)
        pendingMoves = pending.ifEmpty { null }
        if (runCatching { applyCardMove(next) }.isSuccess) {
            changed = true
        } else {
            // Mossa successiva non valida: resta scartata, le altre aspettano.
            break
        }
    }
    if (status == GameStatus.finished) pendingMoves = null
    return changed
}

/**
 * Applica un DEAL/MOVE gia' in sequenza (anche le nostre mosse passano da qui). Valida il mittente
 * (mazziere atteso / posto di turno), la carta e la presa; in caso di errore lo stato non cambia.
 */
fun GameSessionState.applyCardMove(move: BufferedCardMove) {
    if (status == GameStatus.finished) throw CardFailure.invalidMove
    val senderSeat = seat(of = move.senderNodeID) ?: throw CardFailure.notAPlayer
    val updated = deepCopy()
    when (move.gameAction) {
        GameAction.DEAL -> {
            val dealer = expectedDealer
            if (dealer == null || dealer != senderSeat || move.cell != dealer) throw CardFailure.wrongSender
            if (!NeapolitanCards.isValidDeck(move.deck)) throw CardFailure.invalidMove
            when (gameKind) {
                GameKind.SCOPA, GameKind.SCOPONE -> {
                    val game = updated.scopa
                    if (game != null) {
                        game.deal(move.deck, dealer)
                    } else {
                        updated.scopa = ScopaGame(playerCount = playerCount, dealer = dealer, deck = move.deck)
                    }
                }
                GameKind.BRISCOLA -> updated.briscola = BriscolaGame(dealer = dealer, deck = move.deck)
                else -> throw CardFailure.invalidMove
            }
            updated.status = GameStatus.active
        }
        GameAction.MOVE -> {
            val turn = updated.turnSeat
            if (updated.status != GameStatus.active || turn == null) throw CardFailure.notReady
            if (turn != senderSeat) throw CardFailure.wrongSender
            if (!NeapolitanCards.isValid(move.cell) || !move.captured.all { NeapolitanCards.isValid(it) }) {
                throw CardFailure.invalidMove
            }
            when (gameKind) {
                GameKind.SCOPA, GameKind.SCOPONE -> {
                    val game = updated.scopa ?: throw CardFailure.notReady
                    try {
                        game.play(seat = senderSeat, card = move.cell, captured = move.captured)
                    } catch (e: ScopaGame.Failure) {
                        throw CardFailure.invalidMove
                    }
                }
                GameKind.BRISCOLA -> {
                    val game = updated.briscola
                    if (game == null || move.captured.isNotEmpty()) throw CardFailure.invalidMove
                    try {
                        game.play(seat = senderSeat, card = move.cell)
                    } catch (e: BriscolaGame.Failure) {
                        throw CardFailure.invalidMove
                    }
                }
                else -> throw CardFailure.invalidMove
            }
        }
        else -> throw CardFailure.invalidMove
    }
    updated.sequence = move.sequence
    updated.refreshCardOutcome()
    assign(updated)
}

/** Abbandono di un giocatore: la partita finisce per tutti e la sua parte perde. */
fun GameSessionState.applyCardResign(seat: Int) {
    if (status == GameStatus.finished) return
    resignedSeat = seat
    status = GameStatus.finished
    pendingMoves = null
    myTurn = false
    mySeat?.let { mine -> outcome = if (mine % 2 == seat % 2) GameOutcome.iLost else GameOutcome.iWon }
}

/** Parte vincitrice a partita finita (`null` = pareggio o non ancora finita). */
val GameSessionState.cardWinnerSide: Int?
    get() {
        resignedSeat?.let { return 1 - it % 2 }
        scopa?.let { if (it.isMatchOver) return it.winnerSide }
        briscola?.let { if (it.isFinished) return it.winnerSeat }
        return null
    }

val GameSessionState.isCardMatchOver: Boolean
    get() = resignedSeat != null || scopa?.isMatchOver == true || briscola?.isFinished == true

/** Aggiorna `myTurn`, `status` e `outcome` dopo ogni mossa applicata. */
fun GameSessionState.refreshCardOutcome() {
    if (isCardMatchOver) {
        status = GameStatus.finished
        pendingMoves = null
        mySeat?.let { mine ->
            val winner = cardWinnerSide
            outcome = when {
                winner == null -> GameOutcome.draw
                winner == mine % 2 -> GameOutcome.iWon
                else -> GameOutcome.iLost
            }
        }
    }
    myTurn = mySeat != null && turnSeat == mySeat
}

/** Campo `result` dell'ultima MOVE (informativo): 1 vince la parte del mittente, 2 perde, 3 pareggio. */
fun GameSessionState.cardResult(forSenderSeat: Int): Int {
    if (!isCardMatchOver) return 0
    val winner = cardWinnerSide ?: return 3
    return if (winner == forSenderSeat % 2) 1 else 2
}
