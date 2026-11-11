package com.stefanobrandi.aerochat.core.review

import android.content.Context
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.crypto.CryptoEngine
import com.stefanobrandi.aerochat.core.games.GameService
import com.stefanobrandi.aerochat.core.games.GameSessionState
import com.stefanobrandi.aerochat.core.games.GameStatus
import com.stefanobrandi.aerochat.core.games.NeapolitanCards
import com.stefanobrandi.aerochat.core.mesh.InboundPacket
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.nowUnixMilliseconds
import com.stefanobrandi.aerochat.core.protocol.toByteString
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.data.AppDatabase
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.DeliveryStatus
import com.stefanobrandi.aerochat.data.records.MessageContentType
import com.stefanobrandi.aerochat.data.records.MessageRecord
import com.stefanobrandi.aerochat.data.records.PeerRecord
import com.stefanobrandi.aerochat.proto.GameAction
import com.stefanobrandi.aerochat.proto.GameKind
import com.stefanobrandi.aerochat.proto.GameMove
import com.stefanobrandi.aerochat.proto.InnerPayload
import com.stefanobrandi.aerochat.proto.MeshEnvelope
import com.stefanobrandi.aerochat.proto.PacketType
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Passeggero virtuale della modalita' prova ("Prova subito") e della revisione dello store: un contatto
 * fittizio che risponde ai messaggi della sua chat 1-a-1, gioca a carte (Scopa, Briscola) con un bot
 * semplice ([DemoCardBot]) e scrive qualche riga in Cabina Pubblica. Vive solo nel database locale:
 * nessun pacchetto radio (il router non trasmette nulla verso il suo `node_id`), nessuna chiave reale.
 *
 * Acceso/spento da `AppEnvironment.setDemoMode` (impostazione [com.stefanobrandi.aerochat.core.moderation.AppSettings.isDemoModeEnabled]);
 * eliminare la sua chat lo spegne.
 */
class DemoPeerService(
    private val database: AppDatabase,
    context: Context,
    /** Partite: le mosse del bot entrano da qui come se arrivassero dalla radio. */
    private val games: () -> GameService? = { null },
    /** Pause (ms) prima di "letto" e prima della risposta: come un compagno vero. Configurabili per i test. */
    private val readDelay: Long = 1_000L,
    private val replyDelay: Long = 1_500L,
    /** Pausa (ms) prima di ogni mossa del bot. */
    private val moveDelay: Long = 1_200L,
    /** Il bot propone subito una partita a Scopa alla prima attivazione. */
    private val invitesOnEnable: Boolean = true,
) {

    private val context: Context = context.applicationContext ?: context

    @OptIn(ExperimentalCoroutinesApi::class)
    private val actorContext = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + actorContext)

    private var observation: Job? = null
    /** `msg_id` (hex) dei nostri messaggi a cui il compagno ha gia' risposto. */
    private val answered = HashSet<String>()
    /** Ultima azione del bot per partita (gameID -> stato su cui ha agito), per non ripeterla. */
    private val actedOn = HashMap<String, String>()
    /** Partite gia' commentate (fine partita, gioco non supportato). */
    private val commented = HashSet<String>()
    private var replyIndex = 0

    private val replies: List<String>
        get() = listOf(
            R.string.nearby_demo_welcome, R.string.core11_demo_reply_2, R.string.core11_demo_reply_3,
            R.string.core11_demo_reply_4, R.string.core11_demo_reply_5, R.string.nearby_demo_invite_hint,
        ).map { context.getString(it) }

    private val loungeLines: List<String>
        get() = listOf(R.string.core11_demo_lounge_1, R.string.core11_demo_lounge_2).map { context.getString(it) }

    private val nickname: String get() = nickname(context)

    private val peer: PeerRecord
        get() = PeerRecord(nodeID = nodeID, x25519Pub = x25519Pub, ed25519Pub = ed25519Pub, nickname = nickname, isTrusted = true)

    /** Attiva il passeggero virtuale e ritorna la sua chat (in cima alla lista). */
    suspend fun enable(): ConversationRecord? = withContext(actorContext) {
        val conversation = attempt {
            val stored = database.peers.upsert(peer, authoritativeNickname = true)
            database.conversations.ensureDirectConversation(with = stored, touchedAtMs = nowUnixMilliseconds)
        } ?: return@withContext null
        val isNew = attempt { database.messages.fetch(conversationID = conversation.id, limit = 1) }.isNullOrEmpty()
        if (isNew) {
            insertIncoming(replies[0], conversation)
            replyIndex = 1
        }
        attempt { database.conversations.currentCabinLounge() }?.let { lounge ->
            val existing = attempt { database.messages.fetch(conversationID = lounge.id, limit = 500) } ?: emptyList()
            if (existing.none { it.senderNodeID.contentEquals(nodeID) }) {
                for (line in loungeLines) insertIncoming(line, lounge)
            }
        }
        observation?.cancel()
        observation = scope.launch {
            database.messages.observe(conversationID = conversation.id).collect { list ->
                val pending = list.filter {
                    it.isOutgoing && it.contentType == MessageContentType.text &&
                        it.deliveryStatus != DeliveryStatus.read && !answered.contains(it.msgID.toHex())
                }
                for (message in pending) {
                    answered.add(message.msgID.toHex())
                    reply(message, conversation)
                }
                for (message in list) {
                    if (message.contentType == MessageContentType.game) driveGame(message, conversation)
                }
            }
        }
        if (isNew && invitesOnEnable) {
            scope.launch {
                delay(replyDelay)
                attempt { inviteToScopa(conversation) }
            }
        }
        conversation
    }

    suspend fun disable(): Unit = withContext(actorContext) {
        observation?.cancel()
        observation = null
        answered.clear()
        actedOn.clear()
        commented.clear()
        attempt { database.conversations.delete(conversationID = conversationID) }
        val lounge = attempt { database.conversations.fetch(id = ConversationRecord.cabinLoungeID) } ?: return@withContext
        val messages = attempt { database.messages.fetch(conversationID = lounge.id, limit = 500) } ?: return@withContext
        for (message in messages) {
            if (message.senderNodeID.contentEquals(nodeID)) attempt { database.messages.delete(message.msgID) }
        }
    }

    /** Rilascia lo scope interno (fine vita dell'oggetto). */
    fun close() {
        observation?.cancel()
        observation = null
    }

    private suspend fun reply(message: MessageRecord, conversation: ConversationRecord) {
        // Consegnato subito, letto dopo un attimo, risposta dopo un paio di secondi: come un compagno vero.
        attempt { database.messages.advanceDeliveryStatus(to = DeliveryStatus.deliveredAck, msgID = message.msgID) }
        delay(readDelay)
        attempt { database.messages.advanceDeliveryStatus(to = DeliveryStatus.read, msgID = message.msgID) }
        delay(replyDelay)
        kotlin.coroutines.coroutineContext.ensureActive()
        val all = replies
        val text = all[replyIndex % all.size]
        replyIndex += 1
        insertIncoming(text, conversation)
    }

    // MARK: - Partite

    /** Il bot sfida l'utente a Scopa (invito come se arrivasse dalla radio). */
    private suspend fun inviteToScopa(conversation: ConversationRecord) {
        val service = games() ?: return
        val msgID = CryptoEngine.randomMessageID()
        val move = GameMove.newBuilder()
            .setGameId(msgID.toHex())
            .setKind(GameKind.SCOPA)
            .setAction(GameAction.INVITE)
            .setSequence(0)
            .build()
        service.injectLocal(packet(move, conversation, msgID = msgID))
    }

    /** Reagisce allo stato di una partita della chat demo: accetta, distribuisce, gioca o commenta. */
    private suspend fun driveGame(message: MessageRecord, conversation: ConversationRecord) {
        val service = games() ?: return
        val state = message.gameState?.let { runCatching { GameSessionState.decode(it) }.getOrNull() } ?: return
        if (!DemoCardBot.supports(state.gameKind)) {
            // Tris e battaglia navale: il passeggero virtuale gioca solo a carte.
            if (state.iAmInviter && commented.add(state.gameID)) {
                insertIncoming(context.getString(R.string.nearby_demo_cards_only), conversation)
            }
            return
        }
        if (state.status == GameStatus.finished) {
            if (commented.add(state.gameID)) {
                delay(moveDelay)
                insertIncoming(context.getString(R.string.nearby_demo_game_over), conversation)
            }
            return
        }
        val key = "${state.status.rawValue}:${state.sequence}"
        if (actedOn[state.gameID] == key) return
        val action = DemoCardBot.nextAction(state, botNodeID = nodeID) ?: return
        actedOn[state.gameID] = key
        delay(moveDelay)
        kotlin.coroutines.coroutineContext.ensureActive()
        val builder = GameMove.newBuilder().setGameId(state.gameID).setKind(state.gameKind)
        when (action) {
            DemoCardBot.Action.Accept -> builder.setAction(GameAction.ACCEPT).setSequence(1)
            is DemoCardBot.Action.Deal -> builder.setAction(GameAction.DEAL)
                .setSequence((state.sequence + 1).toInt())
                .setCell(action.seat)
                .setDeck(NeapolitanCards.bytes(action.deck).toByteString())
            is DemoCardBot.Action.Play -> builder.setAction(GameAction.MOVE)
                .setSequence((state.sequence + 1).toInt())
                .setCell(action.card)
                .setCaptured(NeapolitanCards.bytes(action.captured.sorted()).toByteString())
        }
        attempt { service.injectLocal(packet(builder.build(), conversation, msgID = CryptoEngine.randomMessageID())) }
    }

    private fun packet(move: GameMove, conversation: ConversationRecord, msgID: ByteArray): InboundPacket {
        val envelope = MeshEnvelope.newBuilder()
            .setMsgId(msgID.toByteString())
            .setSenderNodeId(nodeID.toByteString())
            .setRecipientId(conversation.recipientID.toByteString())
            .setTtl(1)
            .setCreatedAtMs(nowUnixMilliseconds)
            .setPacketType(PacketType.GAME_MOVE)
            .build()
        val payload = InnerPayload.newBuilder()
            .setSenderNickname(nickname)
            .setExtraData(move.toByteArray().toByteString())
            .build()
        return InboundPacket(envelope, payload, conversation, peer, hopCount = 1)
    }

    private suspend fun insertIncoming(text: String, conversation: ConversationRecord) {
        val message = MessageRecord(
            msgID = CryptoEngine.randomMessageID(),
            conversationID = conversation.id,
            senderNodeID = nodeID,
            senderNickname = nickname,
            contentType = MessageContentType.text,
            text = text,
            isOutgoing = false,
            deliveryStatus = DeliveryStatus.deliveredAck,
            hopCountReceived = 1,
        )
        attempt { database.messages.insert(message) }
    }

    companion object {
        private fun sha256(label: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(label.toByteArray(Charsets.UTF_8))

        /** Identita' fissa (derivata da etichette costanti) cosi' riattivare la demo ritrova la stessa chat. Copia nuova a ogni accesso. */
        val nodeID: ByteArray get() = sha256("AeroMesh-Demo-Peer-Node").copyOf(MeshProtocol.nodeIDLength)
        private val x25519Pub: ByteArray get() = sha256("AeroMesh-Demo-Peer-X25519")
        private val ed25519Pub: ByteArray get() = sha256("AeroMesh-Demo-Peer-Ed25519")

        /** Id della chat con il passeggero virtuale. */
        val conversationID: String get() = "direct:${nodeID.toHex()}"

        fun nickname(context: Context): String = context.getString(R.string.nearby_demo_nickname)

        fun isDemoPeer(nodeID: ByteArray): Boolean = nodeID.contentEquals(this.nodeID)

        /** `true` se la chat e' quella con il passeggero virtuale. */
        fun isDemoConversation(conversation: ConversationRecord): Boolean =
            conversation.peerNodeID?.let { isDemoPeer(it) } == true
    }
}

/** Swift `try?`: `null` su errore, ma senza inghiottire la cancellazione delle coroutine. */
private inline fun <T> attempt(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
