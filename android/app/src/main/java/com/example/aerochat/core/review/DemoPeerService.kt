package com.example.aerochat.core.review

import android.content.Context
import com.example.aerochat.R
import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.MessageContentType
import com.example.aerochat.data.records.MessageRecord
import com.example.aerochat.data.records.PeerRecord
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
 * Compagno di volo simulato per la revisione dello store e per provare l'app con un solo telefono:
 * un contatto fittizio che risponde ai messaggi della sua chat 1-a-1 e scrive qualche riga in Cabina
 * Pubblica. Vive solo nel database locale: nessun pacchetto radio, nessuna chiave reale.
 *
 * Acceso/spento da `AppEnvironment.setDemoMode` (impostazione [com.example.aerochat.core.moderation.AppSettings.isDemoModeEnabled]).
 */
class DemoPeerService(
    private val database: AppDatabase,
    context: Context,
    /** Pause (ms) prima di "letto" e prima della risposta: come un compagno vero. Configurabili per i test. */
    private val readDelay: Long = 1_000L,
    private val replyDelay: Long = 1_500L,
) {

    private val context: Context = context.applicationContext ?: context

    @OptIn(ExperimentalCoroutinesApi::class)
    private val actorContext = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + actorContext)

    private var observation: Job? = null
    /** `msg_id` (hex) dei nostri messaggi a cui il compagno ha gia' risposto. */
    private val answered = HashSet<String>()
    private var replyIndex = 0

    private val replies: List<String>
        get() = listOf(
            R.string.core11_demo_reply_1, R.string.core11_demo_reply_2, R.string.core11_demo_reply_3,
            R.string.core11_demo_reply_4, R.string.core11_demo_reply_5, R.string.core11_demo_reply_6,
        ).map { context.getString(it) }

    private val loungeLines: List<String>
        get() = listOf(R.string.core11_demo_lounge_1, R.string.core11_demo_lounge_2).map { context.getString(it) }

    private val nickname: String get() = nickname(context)

    suspend fun enable(): Unit = withContext(actorContext) {
        val peer = PeerRecord(
            nodeID = nodeID, x25519Pub = x25519Pub, ed25519Pub = ed25519Pub,
            nickname = nickname, isTrusted = true,
        )
        val conversation = attempt {
            val stored = database.peers.upsert(peer, authoritativeNickname = true)
            database.conversations.ensureDirectConversation(with = stored)
        } ?: return@withContext
        if (attempt { database.messages.fetch(conversationID = conversation.id, limit = 1) }.isNullOrEmpty()) {
            insertIncoming(replies[0], conversation)
            replyIndex = 1
        }
        attempt { database.conversations.currentCabinLounge() }?.let { lounge ->
            for (line in loungeLines) insertIncoming(line, lounge)
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
            }
        }
    }

    suspend fun disable(): Unit = withContext(actorContext) {
        observation?.cancel()
        observation = null
        answered.clear()
        attempt { database.conversations.delete(conversationID = "direct:${nodeID.toHex()}") }
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

        fun nickname(context: Context): String = context.getString(R.string.core11_demo_nickname)

        fun isDemoPeer(nodeID: ByteArray): Boolean = nodeID.contentEquals(this.nodeID)
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
