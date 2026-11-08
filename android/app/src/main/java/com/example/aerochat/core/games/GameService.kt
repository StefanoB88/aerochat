package com.example.aerochat.core.games

import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.crypto.IdentityKeys
import com.example.aerochat.core.mesh.InboundPacket
import com.example.aerochat.core.mesh.MeshRouterActor
import com.example.aerochat.core.protocol.InnerPayloadFactory
import com.example.aerochat.core.protocol.MeshWire
import com.example.aerochat.core.protocol.hexToByteArray
import com.example.aerochat.core.protocol.nowUnixMilliseconds
import com.example.aerochat.core.protocol.toByteString
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.ConversationType
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.MessageContentType
import com.example.aerochat.data.records.MessageRecord
import com.example.aerochat.proto.GameAction
import com.example.aerochat.proto.GameKind
import com.example.aerochat.proto.GameMove
import com.example.aerochat.proto.InnerPayload
import com.example.aerochat.proto.PacketType
import com.example.aerochat.proto.ShotReport
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class GameStatus(val rawValue: String) {
    invited("invited"),
    active("active"),
    finished("finished");

    companion object {
        fun fromRawValue(rawValue: String): GameStatus? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class GameOutcome(val rawValue: String) {
    ongoing("ongoing"),
    iWon("iWon"),
    iLost("iLost"),
    draw("draw");

    companion object {
        fun fromRawValue(rawValue: String): GameOutcome? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * Stato locale di una partita: vive in `messages.game_state` (la bolla di gioco e' un solo messaggio
 * che evolve a ogni mossa). `gameID` = hex del `msg_id` dell'invito, uguale per entrambi i giocatori.
 *
 * Serializzato in JSON con le stesse chiavi del `Codable` iOS (`Data` in base64, dizionari a chiave
 * intera come oggetti con chiavi stringa). `sequence` e' un UInt32 su iOS: qui `Long` (0...2^32-1).
 * `updatedAtMs` epoch ms.
 */
data class GameSessionState(
    var gameID: String,
    var kind: Int,
    var conversationID: String,
    var opponentNodeID: ByteArray,
    var iAmInviter: Boolean,
    var status: GameStatus = GameStatus.invited,
    var sequence: Long = 0,
    var myTurn: Boolean = false,
    var outcome: GameOutcome = GameOutcome.ongoing,
    var ticTacToe: TicTacToe? = null,
    var battleship: Battleship? = null,
    /** Battaglia navale: cella del nostro ultimo colpo in attesa di REPORT. */
    var pendingShot: Int? = null,
    /** Carte: node_id dei giocatori per posto (0 = chi invita; Scopone: squadre {0,2} contro {1,3}). */
    var seats: List<ByteArray>? = null,
    /** Carte: nickname per posto (solo per la UI). */
    var seatNames: List<String>? = null,
    /** Carte: il nostro posto; `null` = spettatore (membro del gruppo non al tavolo). */
    var mySeat: Int? = null,
    /** Scopone: posti che hanno accettato l'invito. */
    var acceptedSeats: List<Int>? = null,
    /** Scopa e Scopone scientifico (stesso motore). */
    var scopa: ScopaGame? = null,
    var briscola: BriscolaGame? = null,
    /** Carte: DEAL/MOVE arrivati fuori ordine, applicati quando arrivano i precedenti. */
    var pendingMoves: List<BufferedCardMove>? = null,
    /** Carte: posto di chi ha abbandonato (la sua parte perde). */
    var resignedSeat: Int? = null,
    var updatedAtMs: Long = nowUnixMilliseconds,
) {

    val gameKind: GameKind get() = GameKind.forNumber(kind) ?: GameKind.GAME_KIND_UNSPECIFIED
    val myMark: TicTacToe.Mark get() = if (iAmInviter) TicTacToe.Mark.x else TicTacToe.Mark.o
    val messageID: ByteArray? get() = gameID.hexToByteArray()

    /** Copia profonda (anche tabelloni). */
    fun deepCopy(): GameSessionState = copy(
        opponentNodeID = opponentNodeID.copyOf(),
        ticTacToe = ticTacToe?.copy(),
        battleship = battleship?.copy(),
        seats = seats?.map { it.copyOf() },
        scopa = scopa?.copy(),
        briscola = briscola?.copy(),
    )

    fun encoded(): ByteArray {
        val json = JSONObject()
        json.put("gameID", gameID)
        json.put("kind", kind)
        json.put("conversationID", conversationID)
        json.put("opponentNodeID", Base64.getEncoder().encodeToString(opponentNodeID))
        json.put("iAmInviter", iAmInviter)
        json.put("status", status.rawValue)
        json.put("sequence", sequence)
        json.put("myTurn", myTurn)
        json.put("outcome", outcome.rawValue)
        ticTacToe?.let { board ->
            json.put(
                "ticTacToe",
                JSONObject()
                    .put("board", JSONArray(board.board.map { it.rawValue }))
                    .put("next", board.next.rawValue),
            )
        }
        battleship?.let { board ->
            val ships = JSONArray()
            for (ship in board.myShips) {
                ships.put(JSONObject().put("cells", JSONArray(ship.cells)).put("hits", JSONArray(ship.hits.sorted())))
            }
            val shots = JSONObject()
            for ((cell, outcome) in board.myShots) shots.put(cell.toString(), outcome.rawValue)
            json.put(
                "battleship",
                JSONObject()
                    .put("myShips", ships)
                    .put("shotsReceived", JSONArray(board.shotsReceived.sorted()))
                    .put("myShots", shots)
                    .put("enemyShipsSunk", board.enemyShipsSunk),
            )
        }
        pendingShot?.let { json.put("pendingShot", it) }
        seats?.let { list -> json.put("seats", JSONArray(list.map { Base64.getEncoder().encodeToString(it) })) }
        seatNames?.let { json.put("seatNames", JSONArray(it)) }
        mySeat?.let { json.put("mySeat", it) }
        acceptedSeats?.let { json.put("acceptedSeats", JSONArray(it)) }
        scopa?.let { json.put("scopa", it.toJSON()) }
        briscola?.let { json.put("briscola", it.toJSON()) }
        pendingMoves?.let { moves -> json.put("pendingMoves", JSONArray(moves.map { it.toJSON() })) }
        resignedSeat?.let { json.put("resignedSeat", it) }
        json.put("updatedAtMs", updatedAtMs)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameSessionState) return false
        return gameID == other.gameID && kind == other.kind && conversationID == other.conversationID &&
            opponentNodeID.contentEquals(other.opponentNodeID) && iAmInviter == other.iAmInviter &&
            status == other.status && sequence == other.sequence && myTurn == other.myTurn &&
            outcome == other.outcome && ticTacToe == other.ticTacToe && battleship == other.battleship &&
            pendingShot == other.pendingShot &&
            seats?.map { it.toHex() } == other.seats?.map { it.toHex() } && seatNames == other.seatNames &&
            mySeat == other.mySeat && acceptedSeats == other.acceptedSeats && scopa == other.scopa &&
            briscola == other.briscola && pendingMoves == other.pendingMoves && resignedSeat == other.resignedSeat &&
            updatedAtMs == other.updatedAtMs
    }

    override fun hashCode(): Int {
        var result = gameID.hashCode()
        result = 31 * result + kind
        result = 31 * result + conversationID.hashCode()
        result = 31 * result + opponentNodeID.contentHashCode()
        result = 31 * result + iAmInviter.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + myTurn.hashCode()
        result = 31 * result + outcome.hashCode()
        result = 31 * result + (ticTacToe?.hashCode() ?: 0)
        result = 31 * result + (battleship?.hashCode() ?: 0)
        result = 31 * result + (pendingShot ?: 0)
        result = 31 * result + (seats?.map { it.toHex() }?.hashCode() ?: 0)
        result = 31 * result + (mySeat ?: -1)
        result = 31 * result + (scopa?.hashCode() ?: 0)
        result = 31 * result + (briscola?.hashCode() ?: 0)
        result = 31 * result + (resignedSeat ?: -1)
        result = 31 * result + updatedAtMs.hashCode()
        return result
    }

    companion object {
        /** Swift `GameSessionState.decode(_:)`; lancia se il JSON non e' valido. */
        fun decode(data: ByteArray): GameSessionState {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val ticTacToe = json.optJSONObject("ticTacToe")?.let { obj ->
                val board = obj.getJSONArray("board")
                val marks = (0 until board.length()).map {
                    TicTacToe.Mark.fromRawValue(board.getInt(it)) ?: throw IllegalArgumentException("mark")
                }
                val next = TicTacToe.Mark.fromRawValue(obj.getInt("next")) ?: throw IllegalArgumentException("next")
                TicTacToe.restore(marks, next) ?: throw IllegalArgumentException("board")
            }
            val battleship = json.optJSONObject("battleship")?.let { obj ->
                val shipsJSON = obj.getJSONArray("myShips")
                val ships = (0 until shipsJSON.length()).map { index ->
                    val ship = shipsJSON.getJSONObject(index)
                    Battleship.Ship(cells = ship.getJSONArray("cells").ints(), hits = ship.optJSONArray("hits")?.ints()?.toSet() ?: emptySet())
                }
                val received = obj.optJSONArray("shotsReceived")?.ints()?.toSet() ?: emptySet()
                val shots = LinkedHashMap<Int, Battleship.Outcome>()
                when (val raw = obj.opt("myShots")) {
                    is JSONObject -> raw.keys().forEach { key ->
                        val outcome = Battleship.Outcome.fromRawValue(raw.getInt(key)) ?: return@forEach
                        shots[key.toInt()] = outcome
                    }
                    // Formato "unkeyed" [chiave, valore, ...] (tollerato in lettura).
                    is JSONArray -> for (i in 0 until raw.length() / 2) {
                        val outcome = Battleship.Outcome.fromRawValue(raw.getInt(2 * i + 1)) ?: continue
                        shots[raw.getInt(2 * i)] = outcome
                    }
                }
                Battleship.restore(ships, received, shots, obj.optInt("enemyShipsSunk", 0))
            }
            return GameSessionState(
                gameID = json.getString("gameID"),
                kind = json.getInt("kind"),
                conversationID = json.getString("conversationID"),
                opponentNodeID = Base64.getDecoder().decode(json.getString("opponentNodeID")),
                iAmInviter = json.getBoolean("iAmInviter"),
                status = GameStatus.fromRawValue(json.optString("status")) ?: GameStatus.invited,
                sequence = json.optLong("sequence", 0),
                myTurn = json.optBoolean("myTurn", false),
                outcome = GameOutcome.fromRawValue(json.optString("outcome")) ?: GameOutcome.ongoing,
                ticTacToe = ticTacToe,
                battleship = battleship,
                pendingShot = if (json.has("pendingShot") && !json.isNull("pendingShot")) json.getInt("pendingShot") else null,
                seats = json.optJSONArray("seats")?.let { array ->
                    (0 until array.length()).map { Base64.getDecoder().decode(array.getString(it)) }
                },
                seatNames = json.optJSONArray("seatNames")?.let { array -> (0 until array.length()).map { array.getString(it) } },
                mySeat = json.optIntOrNull("mySeat"),
                acceptedSeats = json.optJSONArray("acceptedSeats")?.ints(),
                scopa = json.optJSONObject("scopa")?.let { ScopaGame.fromJSON(it) ?: throw IllegalArgumentException("scopa") },
                briscola = json.optJSONObject("briscola")?.let { BriscolaGame.fromJSON(it) ?: throw IllegalArgumentException("briscola") },
                pendingMoves = json.optJSONArray("pendingMoves")?.let { array ->
                    (0 until array.length()).map { BufferedCardMove.fromJSON(array.getJSONObject(it)) }
                },
                resignedSeat = json.optIntOrNull("resignedSeat"),
                updatedAtMs = json.optLong("updatedAtMs", 0),
            )
        }

    }
}

/**
 * Motore dei mini-giochi in chat (Tris, Battaglia navale, Scopa, Scopone scientifico, Briscola) sopra
 * i pacchetti `GAME_MOVE`.
 */
class GameService(
    private val router: MeshRouterActor,
    private val database: AppDatabase,
    private val identity: IdentityKeys,
    nickname: String,
) {

    sealed class Failure(message: String) : Exception(message) {
        data object directConversationOnly : Failure("directConversationOnly")
        data object gameNotFound : Failure("gameNotFound")
        data object notYourTurn : Failure("notYourTurn")
        data object invalidState : Failure("invalidState")
        data object unsupportedKind : Failure("unsupportedKind")
        /** Scopone: si gioca solo in una chat di gruppo. */
        data object groupConversationOnly : Failure("groupConversationOnly")
        /** Scopone: servono esattamente 3 altri membri del gruppo, distinti. */
        data object invalidPlayers : Failure("invalidPlayers")
        /** Carta o presa non valida. */
        data object invalidMove : Failure("invalidMove")
    }

    sealed interface Event {
        data class Updated(val gameID: String, val conversationID: String) : Event
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val actorContext = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + actorContext)

    private var nickname: String = nickname
    private var inboundTask: Job? = null
    private val subscriberIDs = AtomicLong(0)
    private val subscribers = ConcurrentHashMap<Long, Channel<Event>>()
    /** Carte: mosse arrivate prima dell'invito della loro partita (rigiocate quando arriva l'invito). */
    private val orphanCardMoves = LinkedHashMap<String, MutableList<Pair<GameMove, InboundPacket>>>()
    /**
     * Carte: le operazioni (lettura stato → mossa → persistenza → eventuale DEAL) non si intrecciano
     * ai punti di sospensione (il dispatcher seriale, come l'actor Swift, e' rientrante).
     */
    private val cardLock = Mutex()

    suspend fun start(): Unit = withContext(actorContext) {
        if (inboundTask != null) return@withContext
        // Iscrizione registrata subito: nessun pacchetto perso prima della raccolta.
        val packets = router.inboundPackets()
        inboundTask = scope.launch {
            packets.collect { packet ->
                if (packet.envelope.packetType == PacketType.GAME_MOVE) handle(packet)
            }
        }
    }

    fun stop() {
        inboundTask?.cancel()
        inboundTask = null
    }

    suspend fun setNickname(nickname: String): Unit = withContext(actorContext) {
        this@GameService.nickname = nickname
    }

    /** Aggiornamenti delle partite (ultimi 32 bufferizzati per iscritto). L'iscrizione e' registrata alla chiamata. */
    fun events(): Flow<Event> {
        val id = subscriberIDs.incrementAndGet()
        val channel = Channel<Event>(32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        subscribers[id] = channel
        return channel.consumeAsFlow().onCompletion { subscribers.remove(id) }
    }

    // MARK: - Lettura

    suspend fun session(gameID: String): GameSessionState = withContext(actorContext) {
        val msgID = gameID.hexToByteArray() ?: throw Failure.gameNotFound
        val message = database.messages.fetch(msgID = msgID) ?: throw Failure.gameNotFound
        val data = message.gameState ?: throw Failure.gameNotFound
        GameSessionState.decode(data)
    }

    // MARK: - Azioni locali

    /**
     * Invita l'interlocutore (chat 1-a-1) o, per lo Scopone, 3 membri del gruppo (`players` in ordine
     * di posto 1..3: il secondo scelto e' il compagno). La bolla appare subito come "invito inviato".
     */
    suspend fun invite(
        kind: GameKind,
        conversation: ConversationRecord,
        players: List<ByteArray> = emptyList(),
    ): GameSessionState = withContext(actorContext) {
        val opponent: ByteArray
        var seats: List<ByteArray>? = null
        if (kind == GameKind.SCOPONE) {
            if (conversation.type != ConversationType.group) throw Failure.groupConversationOnly
            val members = database.conversations.groupMembers(of = conversation.id).map { it.nodeID.toHex() }.toSet()
            val chosen = players.map { it.toHex() }
            if (players.size != 3 || chosen.toSet().size != 3 || chosen.contains(identity.nodeID.toHex()) ||
                !members.containsAll(chosen)
            ) throw Failure.invalidPlayers
            opponent = players[0]
            seats = listOf(identity.nodeID) + players
        } else {
            val peer = conversation.peerNodeID
            if (conversation.type != ConversationType.direct || peer == null) throw Failure.directConversationOnly
            if (kind != GameKind.TIC_TAC_TOE && kind != GameKind.BATTLESHIP && kind != GameKind.SCOPA &&
                kind != GameKind.BRISCOLA
            ) throw Failure.unsupportedKind
            opponent = peer
            if (kind.isCardGame) seats = listOf(identity.nodeID, peer)
        }
        val msgID = CryptoEngine.randomMessageID()
        val state = GameSessionState(
            gameID = msgID.toHex(), kind = kind.number, conversationID = conversation.id,
            opponentNodeID = opponent, iAmInviter = true,
        )
        if (kind == GameKind.TIC_TAC_TOE) state.ticTacToe = TicTacToe()
        if (seats != null) {
            state.seats = seats
            state.mySeat = 0
            state.seatNames = seatNames(seats, conversationID = conversation.id)
            if (kind == GameKind.SCOPONE) state.acceptedSeats = emptyList()
        }
        val message = MessageRecord(
            msgID = msgID, conversationID = conversation.id, senderNodeID = identity.nodeID,
            senderNickname = nickname, contentType = MessageContentType.game, text = title(kind),
            gameState = state.encoded(), isOutgoing = true, deliveryStatus = DeliveryStatus.queued,
        )
        database.messages.insert(message)

        val move = GameMove.newBuilder()
            .setGameId(state.gameID)
            .setKind(kind)
            .setAction(GameAction.INVITE)
            .setSequence(0)
            .apply { if (kind == GameKind.SCOPONE) seats?.forEach { addPlayers(it.toByteString()) } }
            .build()
        val result = router.sendPacket(type = PacketType.GAME_MOVE, payload = payload(move), conversation = conversation, msgID = msgID)
        database.messages.updateDeliveryStatus(if (result.reached) DeliveryStatus.sentMesh else DeliveryStatus.queued, msgID = msgID)
        emit(Event.Updated(gameID = state.gameID, conversationID = conversation.id))
        state
    }

    /** Accetta un invito ricevuto: chi invita muove/spara per primo. */
    suspend fun accept(gameID: String): GameSessionState = withContext(actorContext) {
        if (session(gameID).isCardGame) return@withContext cardLock.withLock { acceptCardGame(gameID) }
        val state = session(gameID)
        if (state.status != GameStatus.invited || state.iAmInviter) throw Failure.invalidState
        state.status = GameStatus.active
        state.myTurn = false
        when (state.gameKind) {
            GameKind.TIC_TAC_TOE -> state.ticTacToe = TicTacToe()
            GameKind.BATTLESHIP -> state.battleship = Battleship(Battleship.randomFleet())
            else -> throw Failure.unsupportedKind
        }
        state.sequence = nextSequence(state.sequence)
        val move = GameMove.newBuilder()
            .setGameId(gameID)
            .setKind(state.gameKind)
            .setAction(GameAction.ACCEPT)
            .setSequence(state.sequence.toInt())
            .build()
        send(move, state)
        state
    }

    /** Gioca una cella: mossa nel Tris, colpo nella Battaglia navale. */
    suspend fun play(gameID: String, cell: Int): GameSessionState = withContext(actorContext) {
        val state = session(gameID)
        if (state.status != GameStatus.active || !state.myTurn) throw Failure.notYourTurn
        val move = GameMove.newBuilder()
            .setGameId(gameID)
            .setKind(state.gameKind)
            .setAction(GameAction.MOVE)
            .setCell(cell)

        when (state.gameKind) {
            GameKind.TIC_TAC_TOE -> {
                val board = state.ticTacToe?.copy() ?: throw Failure.invalidState
                board.play(cell, state.myMark)
                state.ticTacToe = board
                state.myTurn = false
                move.boardSnapshot = board.snapshot.toByteString()
                if (board.winner == state.myMark) {
                    state.outcome = GameOutcome.iWon
                    state.status = GameStatus.finished
                    move.result = 1
                } else if (board.isDraw) {
                    state.outcome = GameOutcome.draw
                    state.status = GameStatus.finished
                    move.result = 3
                }
            }
            GameKind.BATTLESHIP -> {
                val board = state.battleship
                if (board == null || !board.canShoot(cell) || state.pendingShot != null) throw Failure.invalidState
                state.pendingShot = cell
                state.myTurn = false
                move.boardSnapshot = board.shotsSnapshot.toByteString()
            }
            else -> throw Failure.unsupportedKind
        }
        state.sequence = nextSequence(state.sequence)
        move.sequence = state.sequence.toInt()
        send(move.build(), state)
        state
    }

    suspend fun resign(gameID: String): GameSessionState = withContext(actorContext) {
        if (session(gameID).isCardGame) return@withContext cardLock.withLock { resignCardGame(gameID) }
        val state = session(gameID)
        if (state.status == GameStatus.finished) throw Failure.invalidState
        state.status = GameStatus.finished
        state.outcome = GameOutcome.iLost
        state.sequence = nextSequence(state.sequence)
        val move = GameMove.newBuilder()
            .setGameId(gameID)
            .setKind(state.gameKind)
            .setAction(GameAction.RESIGN)
            .setSequence(state.sequence.toInt())
            .setResult(2)
            .build()
        send(move, state)
        state
    }

    // MARK: - Ricezione

    private suspend fun handle(packet: InboundPacket) {
        val parsed = runCatching { MeshWire.parseGameMove(packet.payload.extraData.toByteArray()) }.getOrNull()
        if (parsed != null && parsed.gameId.isNotEmpty() && parsed.kind.isCardGame) {
            cardLock.withLock { handleCardMove(parsed, packet) }
            return
        }
        if (packet.conversation.type != ConversationType.direct) return
        val move = parsed ?: return
        if (move.gameId.isEmpty()) return

        if (move.action == GameAction.INVITE) {
            handleInvite(move, packet)
            return
        }
        val state = runCatching { session(move.gameId) }.getOrNull() ?: return
        val moveSequence = move.sequence.toUInt32()
        if (state.isCardGame || !state.opponentNodeID.contentEquals(packet.sender.nodeID) ||
            moveSequence <= state.sequence ||
            // Solo piccoli salti in avanti (mosse perse): un valore enorme farebbe andare in overflow
            // il nostro `sequence += 1` alla mossa successiva.
            moveSequence - state.sequence > maxSequenceGap
        ) return
        state.sequence = moveSequence

        when (move.action) {
            GameAction.ACCEPT -> {
                if (state.status != GameStatus.invited || !state.iAmInviter) return
                state.status = GameStatus.active
                state.myTurn = true
                if (state.gameKind == GameKind.BATTLESHIP && state.battleship == null) {
                    state.battleship = Battleship(Battleship.randomFleet())
                }
                if (state.gameKind == GameKind.TIC_TAC_TOE && state.ticTacToe == null) {
                    state.ticTacToe = TicTacToe()
                }
                persist(state)
            }
            GameAction.MOVE -> handleOpponentMove(move, state)
            GameAction.REPORT -> handleReport(move, state)
            GameAction.RESIGN -> {
                state.status = GameStatus.finished
                state.outcome = GameOutcome.iWon
                state.myTurn = false
                persist(state)
            }
            else -> return
        }
    }

    private suspend fun handleInvite(move: GameMove, packet: InboundPacket) {
        if (move.kind != GameKind.TIC_TAC_TOE && move.kind != GameKind.BATTLESHIP) return
        val msgID = packet.envelope.msgId.toByteArray()
        if (msgID.toHex() != move.gameId) return
        if (runCatching { database.messages.fetch(msgID = msgID) }.getOrNull() != null) return
        val sequence = move.sequence.toUInt32()
        if (sequence > maxSequenceGap) return
        val state = GameSessionState(
            gameID = move.gameId, kind = move.kind.number, conversationID = packet.conversation.id,
            opponentNodeID = packet.sender.nodeID, iAmInviter = false,
        )
        state.sequence = sequence
        val message = MessageRecord(
            msgID = msgID, conversationID = packet.conversation.id, senderNodeID = packet.sender.nodeID,
            senderNickname = packet.sender.nickname, contentType = MessageContentType.game, text = title(move.kind),
            gameState = runCatching { state.encoded() }.getOrNull(), isOutgoing = false,
            deliveryStatus = DeliveryStatus.deliveredAck, hopCountReceived = packet.hopCount,
            createdAtMs = packet.envelope.createdAtMs,
        )
        runCatching { database.messages.insert(message) }
        emit(Event.Updated(gameID = state.gameID, conversationID = packet.conversation.id))
    }

    private suspend fun handleOpponentMove(move: GameMove, state: GameSessionState) {
        if (state.status != GameStatus.active) return
        when (state.gameKind) {
            GameKind.TIC_TAC_TOE -> {
                var board = state.ticTacToe?.copy() ?: return
                val cell = move.cell
                val played = runCatching { board.play(cell, state.myMark.opponent) }.isSuccess
                if (!played) {
                    // Mossa incoerente con il nostro stato: ci allineiamo allo snapshot autorevole del mittente.
                    board = TicTacToe(snapshot = move.boardSnapshot.toByteArray()) ?: return
                }
                state.ticTacToe = board
                if (board.winner == state.myMark.opponent || move.result == 1) {
                    state.outcome = GameOutcome.iLost
                    state.status = GameStatus.finished
                    state.myTurn = false
                } else if (board.isDraw || move.result == 3) {
                    state.outcome = GameOutcome.draw
                    state.status = GameStatus.finished
                    state.myTurn = false
                } else {
                    state.myTurn = true
                }
                persist(state)
            }
            GameKind.BATTLESHIP -> {
                val board = state.battleship?.copy() ?: return
                val outcome = runCatching { board.receiveShot(move.cell) }.getOrNull() ?: return
                state.battleship = board
                val report = GameMove.newBuilder()
                    .setGameId(state.gameID)
                    .setKind(GameKind.BATTLESHIP)
                    .setAction(GameAction.REPORT)
                    .setCell(move.cell)
                    .setReport(shotReport(outcome))
                if (board.allMyShipsSunk) {
                    state.status = GameStatus.finished
                    state.outcome = GameOutcome.iLost
                    state.myTurn = false
                    report.result = 2
                } else {
                    state.myTurn = true
                }
                state.sequence = nextSequence(state.sequence)
                report.sequence = state.sequence.toInt()
                runCatching { send(report.build(), state) }
            }
            else -> return
        }
    }

    private suspend fun handleReport(move: GameMove, state: GameSessionState) {
        if (state.gameKind != GameKind.BATTLESHIP) return
        val board = state.battleship?.copy() ?: return
        val shot = state.pendingShot ?: return
        if (move.cell != shot) return
        val outcome = outcome(move.report) ?: return
        board.recordMyShot(shot, outcome)
        state.battleship = board
        state.pendingShot = null
        if (move.result == 2 || board.enemyFleetDestroyed) {
            state.status = GameStatus.finished
            state.outcome = GameOutcome.iWon
            state.myTurn = false
        } else {
            state.myTurn = false
        }
        persist(state)
    }

    // MARK: - Utility

    /** Swift `send(_:state: inout)`: aggiorna `state` sul posto, lo salva e invia la mossa. */
    private suspend fun send(move: GameMove, state: GameSessionState) {
        val conversation = database.conversations.fetch(id = state.conversationID) ?: throw Failure.gameNotFound
        state.updatedAtMs = nowUnixMilliseconds
        persist(state)
        router.sendPacket(type = PacketType.GAME_MOVE, payload = payload(move), conversation = conversation)
    }

    private suspend fun persist(state: GameSessionState) {
        val updated = state.copy(updatedAtMs = nowUnixMilliseconds)
        val msgID = updated.messageID ?: return
        val data = runCatching { updated.encoded() }.getOrNull() ?: return
        runCatching { database.messages.updateGameState(data, msgID = msgID) }
        emit(Event.Updated(gameID = updated.gameID, conversationID = updated.conversationID))
    }

    private fun payload(move: GameMove): InnerPayload =
        InnerPayloadFactory.extra(MeshWire.serialize(move), identity = identity, nickname = nickname)

    private fun emit(event: Event) {
        for (channel in subscribers.values) channel.trySend(event)
    }

    // MARK: - Carte napoletane (Scopa, Scopone scientifico, Briscola)

    /** Gioca una carta: `captured` = carte prese dal tavolo (Scopa/Scopone; vuoto se non c'e' presa). */
    suspend fun playCard(gameID: String, card: Int, captured: List<Int> = emptyList()): GameSessionState =
        withContext(actorContext) {
            cardLock.withLock {
                val state = session(gameID)
                val seat = state.mySeat
                if (!state.isCardGame || seat == null) throw Failure.invalidState
                if (state.status != GameStatus.active || state.turnSeat != seat) throw Failure.notYourTurn
                val applied = BufferedCardMove(
                    sequence = nextSequence(state.sequence), action = GameAction.MOVE, senderNodeID = identity.nodeID,
                    cell = card, captured = captured,
                )
                try {
                    state.applyCardMove(applied)
                } catch (e: CardFailure) {
                    throw Failure.invalidMove
                }
                val move = GameMove.newBuilder()
                    .setGameId(gameID)
                    .setKind(state.gameKind)
                    .setAction(GameAction.MOVE)
                    .setSequence(state.sequence.toInt())
                    .setCell(card)
                    .setCaptured(NeapolitanCards.bytes(captured.sorted()).toByteString())
                    .setResult(state.cardResult(forSenderSeat = seat))
                    .build()
                send(move, state)
                dealIfNeeded(state)
            }
        }

    private suspend fun acceptCardGame(gameID: String): GameSessionState {
        val state = session(gameID)
        val seat = state.mySeat
        if (state.status != GameStatus.invited || state.iAmInviter || seat == null) throw Failure.invalidState
        val move = GameMove.newBuilder()
            .setGameId(gameID)
            .setKind(state.gameKind)
            .setAction(GameAction.ACCEPT)
        if (state.gameKind == GameKind.SCOPONE) {
            // Scopone: ACCEPT con sequence 0, non fa avanzare il contatore; si aspetta il DEAL di chi invita.
            val accepted = (state.acceptedSeats ?: emptyList()).toMutableSet()
            if (!accepted.add(seat)) throw Failure.invalidState
            state.acceptedSeats = accepted.sorted()
            move.sequence = 0
        } else {
            state.status = GameStatus.active
            state.sequence = 1
            move.sequence = 1
        }
        state.myTurn = false
        send(move.build(), state)
        return state
    }

    private suspend fun resignCardGame(gameID: String): GameSessionState {
        val state = session(gameID)
        val seat = state.mySeat
        if (state.status == GameStatus.finished || seat == null) throw Failure.invalidState
        state.applyCardResign(seat = seat)
        state.sequence = nextSequence(state.sequence)
        val move = GameMove.newBuilder()
            .setGameId(gameID)
            .setKind(state.gameKind)
            .setAction(GameAction.RESIGN)
            .setSequence(state.sequence.toInt())
            .setResult(2)
            .build()
        send(move, state)
        return state
    }

    /** Ricezione di una mossa di carte: Scopa/Briscola solo in chat 1-a-1, Scopone solo nel gruppo. */
    private suspend fun handleCardMove(move: GameMove, packet: InboundPacket) {
        val sender = packet.sender.nodeID
        if (sender.contentEquals(identity.nodeID)) return
        val expectedType = if (move.kind == GameKind.SCOPONE) ConversationType.group else ConversationType.direct
        if (packet.conversation.type != expectedType) return
        if (move.action == GameAction.INVITE) {
            handleCardInvite(move, packet)
            return
        }
        val state = runCatching { session(move.gameId) }.getOrNull()
        if (state == null) {
            rememberOrphan(move, packet)
            return
        }
        if (state.gameKind != move.kind || state.conversationID != packet.conversation.id) return
        val senderSeat = state.seat(of = sender) ?: return
        val moveSequence = move.sequence.toUInt32()

        when (move.action) {
            GameAction.ACCEPT -> {
                if (state.status != GameStatus.invited) return
                if (move.kind == GameKind.SCOPONE) {
                    if (senderSeat !in 1..3 || moveSequence != 0L) return
                    val accepted = (state.acceptedSeats ?: emptyList()).toMutableSet()
                    if (!accepted.add(senderSeat)) return
                    state.acceptedSeats = accepted.sorted()
                } else {
                    if (!state.iAmInviter || senderSeat != 1 || moveSequence != 1L || state.sequence != 0L) return
                    state.sequence = 1
                    state.status = GameStatus.active
                }
                persist(state)
                dealIfNeeded(state)
            }
            GameAction.RESIGN -> {
                // L'abbandono vale subito, anche fuori ordine (puo' incrociarsi con una mossa avversaria).
                if (state.status == GameStatus.finished ||
                    !(moveSequence <= state.sequence || moveSequence - state.sequence <= maxSequenceGap)
                ) return
                state.applyCardResign(seat = senderSeat)
                state.sequence = maxOf(state.sequence, moveSequence)
                persist(state)
            }
            GameAction.DEAL, GameAction.MOVE -> {
                if (!state.receiveCardMove(BufferedCardMove.from(move, senderNodeID = sender), maxGap = maxSequenceGap)) return
                persist(state)
                dealIfNeeded(state)
            }
            else -> return
        }
    }

    private suspend fun handleCardInvite(move: GameMove, packet: InboundPacket) {
        val msgID = packet.envelope.msgId.toByteArray()
        val sender = packet.sender.nodeID
        if (msgID.toHex() != move.gameId || move.sequence != 0) return
        if (runCatching { database.messages.fetch(msgID = msgID) }.getOrNull() != null) return
        val seats: List<ByteArray>
        if (move.kind == GameKind.SCOPONE) {
            val players = move.playersList.map { it.toByteArray() }
            if (players.size != 4 || players.any { it.size != identity.nodeID.size } ||
                players.map { it.toHex() }.toSet().size != 4 || !players[0].contentEquals(sender)
            ) return
            seats = players
        } else {
            seats = listOf(sender, identity.nodeID)
        }
        val state = GameSessionState(
            gameID = move.gameId, kind = move.kind.number, conversationID = packet.conversation.id,
            opponentNodeID = sender, iAmInviter = false,
        )
        state.seats = seats
        state.mySeat = seats.indexOfFirst { it.contentEquals(identity.nodeID) }.takeIf { it >= 0 }
        state.seatNames = seatNames(seats, conversationID = packet.conversation.id, known = mapOf(sender.toHex() to packet.sender.nickname))
        if (move.kind == GameKind.SCOPONE) state.acceptedSeats = emptyList()
        val message = MessageRecord(
            msgID = msgID, conversationID = packet.conversation.id, senderNodeID = sender,
            senderNickname = packet.sender.nickname, contentType = MessageContentType.game, text = title(move.kind),
            gameState = runCatching { state.encoded() }.getOrNull(), isOutgoing = false,
            deliveryStatus = DeliveryStatus.deliveredAck, hopCountReceived = packet.hopCount,
            createdAtMs = packet.envelope.createdAtMs,
        )
        if (runCatching { database.messages.insert(message) }.isFailure) return
        emit(Event.Updated(gameID = state.gameID, conversationID = packet.conversation.id))

        // Mosse della stessa partita arrivate prima dell'invito.
        for ((orphan, orphanPacket) in orphanCardMoves.remove(move.gameId) ?: emptyList()) {
            handleCardMove(orphan, orphanPacket)
        }
    }

    /** Se tocca a noi distribuire, invia il DEAL (mazzo mescolato qui) e lo applica subito. */
    private suspend fun dealIfNeeded(incoming: GameSessionState): GameSessionState {
        val state = incoming.deepCopy()
        val dealer = state.mySeat
        if (!state.shouldDeal || dealer == null) return incoming
        val deck = NeapolitanCards.shuffledDeck()
        val applied = BufferedCardMove(
            sequence = nextSequence(state.sequence), action = GameAction.DEAL, senderNodeID = identity.nodeID,
            cell = dealer, deck = deck,
        )
        if (runCatching { state.applyCardMove(applied) }.isFailure) return incoming
        state.drainPendingMoves()
        val move = GameMove.newBuilder()
            .setGameId(state.gameID)
            .setKind(state.gameKind)
            .setAction(GameAction.DEAL)
            .setSequence(applied.sequence.toInt())
            .setCell(dealer)
            .setDeck(NeapolitanCards.bytes(deck).toByteString())
            .build()
        runCatching { send(move, state) }
        return state
    }

    private fun rememberOrphan(move: GameMove, packet: InboundPacket) {
        if (move.action != GameAction.ACCEPT && move.action != GameAction.DEAL && move.action != GameAction.MOVE &&
            move.action != GameAction.RESIGN
        ) return
        val total = orphanCardMoves.values.sumOf { it.size }
        if (total >= maxOrphanCardMoves) return
        orphanCardMoves.getOrPut(move.gameId) { mutableListOf() }.add(move to packet)
    }

    /** Nickname per posto: noi, poi i contatti noti, poi i membri del gruppo ("" se sconosciuto). */
    private suspend fun seatNames(
        seats: List<ByteArray>,
        conversationID: String,
        known: Map<String, String> = emptyMap(),
    ): List<String> {
        val members = runCatching { database.conversations.groupMembers(of = conversationID) }.getOrNull() ?: emptyList()
        return seats.map { nodeID ->
            val hex = nodeID.toHex()
            when {
                nodeID.contentEquals(identity.nodeID) -> nickname
                !known[hex].isNullOrEmpty() -> known.getValue(hex)
                else -> runCatching { database.peers.fetch(nodeID = nodeID) }.getOrNull()?.nickname
                    ?: members.firstOrNull { it.nodeID.contentEquals(nodeID) }?.nickname
                    ?: ""
            }
        }
    }

    companion object {
        /** Salto massimo accettato nel numero di sequenza di una mossa ricevuta. */
        const val maxSequenceGap: Long = 16

        /** Massimo di mosse tenute in memoria per partite di cui non e' ancora arrivato l'invito. */
        const val maxOrphanCardMoves = 64

        /** Swift `title(for:)` (testo italiano di iOS; la UI puo' localizzarlo). */
        fun title(kind: GameKind): String = when (kind) {
            GameKind.TIC_TAC_TOE -> "🎮 Tris"
            GameKind.BATTLESHIP -> "🚢 Battaglia navale"
            GameKind.SCOPA -> "🃏 Scopa"
            GameKind.SCOPONE -> "🃏 Scopone"
            GameKind.BRISCOLA -> "🃏 Briscola"
            else -> "🎮 Gioco"
        }

        fun shotReport(outcome: Battleship.Outcome): ShotReport = when (outcome) {
            Battleship.Outcome.miss -> ShotReport.MISS
            Battleship.Outcome.hit -> ShotReport.HIT
            Battleship.Outcome.sunk -> ShotReport.SUNK
        }

        fun outcome(report: ShotReport): Battleship.Outcome? = when (report) {
            ShotReport.MISS -> Battleship.Outcome.miss
            ShotReport.HIT -> Battleship.Outcome.hit
            ShotReport.SUNK -> Battleship.Outcome.sunk
            else -> null
        }

        /** `UInt32` di protobuf (Int con segno in Java) come valore senza segno. */
        private fun Int.toUInt32(): Long = toLong() and 0xFFFF_FFFFL

        /** `sequence += 1` di Swift (UInt32: oltre 2^32-1 andrebbe in overflow; il gap massimo lo impedisce). */
        private fun nextSequence(sequence: Long): Long = (sequence + 1) and 0xFFFF_FFFFL
    }
}
