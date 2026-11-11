package com.stefanobrandi.aerochat.core.emergency

import android.content.Context
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.crypto.IdentityKeys
import com.stefanobrandi.aerochat.core.mesh.InboundPacket
import com.stefanobrandi.aerochat.core.mesh.MeshNotifier
import com.stefanobrandi.aerochat.core.mesh.MeshRouterActor
import com.stefanobrandi.aerochat.core.protocol.InnerPayloadFactory
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.MeshWire
import com.stefanobrandi.aerochat.core.protocol.nowUnixMilliseconds
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.data.AppDatabase
import com.stefanobrandi.aerochat.data.records.DeliveryStatus
import com.stefanobrandi.aerochat.data.records.MessageContentType
import com.stefanobrandi.aerochat.data.records.MessageRecord
import com.stefanobrandi.aerochat.proto.EmergencyBroadcast
import com.stefanobrandi.aerochat.proto.EmergencyStatus
import com.stefanobrandi.aerochat.proto.PacketType
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
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
import kotlinx.coroutines.withContext

/** Avviso ricevuto da un passeggero vicino (banner rosso + notifica prioritaria). */
class EmergencyAlert(
    val msgID: ByteArray,
    val senderNodeID: ByteArray,
    val senderNickname: String,
    val status: EmergencyStatus,
    val text: String,
    val latitude: Double?,
    val longitude: Double?,
    /** epoch ms. */
    val receivedAt: Long,
    val hopCount: Int,
) {
    /** `Identifiable.id` di Swift (hex del `msg_id`). */
    val id: String get() = msgID.toHex()

    val hasLocation: Boolean get() = latitude != null && longitude != null

    val emoji: String get() = EmergencyService.emoji(status)

    /** Titolo localizzato dello stato ("Sto bene", "Ho bisogno di aiuto", ...). */
    fun statusTitle(context: Context?): String = EmergencyService.title(status, context)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmergencyAlert) return false
        return msgID.contentEquals(other.msgID) && senderNodeID.contentEquals(other.senderNodeID) &&
            senderNickname == other.senderNickname && status == other.status && text == other.text &&
            latitude == other.latitude && longitude == other.longitude && receivedAt == other.receivedAt &&
            hopCount == other.hopCount
    }

    override fun hashCode(): Int = msgID.contentHashCode()

    override fun toString(): String = "EmergencyAlert(${msgID.toHex()}, $senderNickname, $status)"
}

/**
 * Modalita' emergenza/eventi: un messaggio prioritario a tutti i telefoni a portata (e a chi passa piu'
 * tardi, grazie alla coda store-and-forward), con stato, testo breve e posizione facoltativa.
 *
 * [context] serve solo per i titoli localizzati (senza, i testi restano in italiano come su iOS).
 */
class EmergencyService(
    private val router: MeshRouterActor,
    private val database: AppDatabase,
    private val identity: IdentityKeys,
    nickname: String,
    private val notifier: MeshNotifier,
    private val context: Context? = null,
) {

    sealed interface Event {
        data class Received(val alert: EmergencyAlert) : Event
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val actorContext = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + actorContext)

    private var nickname: String = nickname
    private var inboundTask: Job? = null
    private val subscriberIDs = AtomicLong(0)
    private val subscribers = ConcurrentHashMap<Long, Channel<Event>>()

    suspend fun start(): Unit = withContext(actorContext) {
        if (inboundTask != null) return@withContext
        // Iscrizione registrata subito (prima dell'accensione della radio): gli avvisi in coda presso i
        // vicini arrivano appena si apre il link.
        val packets = router.inboundPackets(PacketType.EMERGENCY)
        inboundTask = scope.launch {
            packets.collect { packet ->
                if (packet.envelope.packetType != PacketType.EMERGENCY) return@collect
                try {
                    handle(packet)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stop() {
        inboundTask?.cancel()
        inboundTask = null
    }

    suspend fun setNickname(nickname: String): Unit = withContext(actorContext) {
        this@EmergencyService.nickname = nickname
    }

    /** Avvisi ricevuti (ultimi 8 bufferizzati per iscritto). L'iscrizione e' registrata alla chiamata. */
    fun events(): Flow<Event> {
        val id = subscriberIDs.incrementAndGet()
        val channel = Channel<Event>(8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        subscribers[id] = channel
        return channel.consumeAsFlow().onCompletion { subscribers.remove(id) }
    }

    /**
     * Invia l'avviso sulla Lounge con TTL massimo; resta in coda per chi arriva dopo. `latitude`/`longitude`
     * insieme o nessuno dei due. Ritorna il messaggio di sistema salvato nella Cabina.
     */
    suspend fun broadcast(status: EmergencyStatus, text: String, latitude: Double?, longitude: Double?): MessageRecord =
        withContext(actorContext) {
            val lounge = database.conversations.currentCabinLounge()
            val trimmed = prefix(text.trim(), maxTextLength)
            val builder = EmergencyBroadcast.newBuilder()
                .setStatus(status)
                .setText(trimmed)
                .setIssuedAtMs(nowUnixMilliseconds)
            if (latitude != null && longitude != null) {
                builder.setLatitude(latitude).setLongitude(longitude).setHasLocation(true)
            }
            val broadcast = builder.build()
            val payload = InnerPayloadFactory.extra(MeshWire.serialize(broadcast), identity = identity, nickname = nickname)
            val result = router.sendPacket(
                type = PacketType.EMERGENCY, payload = payload, conversation = lounge,
                ttl = MeshProtocol.maxTTL, storeIfUnreachable = true,
            )
            // Copia locale nella Cabina, cosi' chi l'ha inviato vede cosa e' partito.
            val message = MessageRecord(
                msgID = result.msgID, conversationID = lounge.id, senderNodeID = identity.nodeID,
                senderNickname = nickname, contentType = MessageContentType.system,
                text = systemText(nickname, broadcast, context),
                isOutgoing = true, deliveryStatus = DeliveryStatus.sentMesh,
            )
            try {
                database.messages.insert(message)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            message
        }

    private suspend fun handle(packet: InboundPacket) {
        val broadcast = try {
            MeshWire.parseEmergencyBroadcast(packet.payload.extraData.toByteArray())
        } catch (_: Exception) {
            return
        }
        if (broadcast.text.codePointCount(0, broadcast.text.length) > maxTextLength) return
        if (broadcast.status == EmergencyStatus.EMERGENCY_STATUS_UNSPECIFIED) return
        if (broadcast.hasLocation) {
            if (broadcast.latitude !in -90.0..90.0 || broadcast.longitude !in -180.0..180.0) return
        }
        val lounge = packet.conversation
        val sender = packet.sender
        val message = MessageRecord(
            msgID = packet.envelope.msgId.toByteArray(), conversationID = lounge.id,
            senderNodeID = sender.nodeID, senderNickname = sender.nickname,
            contentType = MessageContentType.system, text = systemText(sender.nickname, broadcast, context),
            isOutgoing = false, deliveryStatus = DeliveryStatus.deliveredAck,
            hopCountReceived = packet.hopCount, createdAtMs = packet.envelope.createdAtMs,
        )
        if (!database.messages.insert(message)) return
        val alert = EmergencyAlert(
            msgID = message.msgID, senderNodeID = sender.nodeID, senderNickname = sender.nickname,
            status = broadcast.status, text = broadcast.text,
            latitude = if (broadcast.hasLocation) broadcast.latitude else null,
            longitude = if (broadcast.hasLocation) broadcast.longitude else null,
            receivedAt = nowUnixMilliseconds, hopCount = packet.hopCount,
        )
        val event = Event.Received(alert)
        for (channel in subscribers.values) channel.trySend(event)
        try {
            notifier.notifyEmergency(alert)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    companion object {
        const val maxTextLength = 280

        /** Titolo dello stato: localizzato con [context], altrimenti in italiano (testi iOS). */
        fun title(status: EmergencyStatus, context: Context? = null): String = when (status) {
            EmergencyStatus.OK -> context?.getString(R.string.core11_emergency_ok) ?: "Sto bene"
            EmergencyStatus.NEED_HELP -> context?.getString(R.string.core11_emergency_need_help) ?: "Ho bisogno di aiuto"
            EmergencyStatus.ALL_CLEAR -> context?.getString(R.string.core11_emergency_all_clear) ?: "Cessato allarme / informazione"
            EmergencyStatus.EMERGENCY_STATUS_UNSPECIFIED, EmergencyStatus.UNRECOGNIZED ->
                context?.getString(R.string.core11_emergency_generic) ?: "Avviso"
        }

        fun emoji(status: EmergencyStatus): String = when (status) {
            EmergencyStatus.OK -> "✅"
            EmergencyStatus.NEED_HELP -> "🆘"
            EmergencyStatus.ALL_CLEAR -> "ℹ️"
            EmergencyStatus.EMERGENCY_STATUS_UNSPECIFIED, EmergencyStatus.UNRECOGNIZED -> "⚠️"
        }

        /** `"<emoji> <nickname>: <titolo stato> — <text> — 📍 lat, lon"` (messaggio di sistema nella Cabina). */
        fun systemText(nickname: String, broadcast: EmergencyBroadcast, context: Context? = null): String {
            val parts = mutableListOf("${emoji(broadcast.status)} $nickname: ${title(broadcast.status, context)}")
            if (broadcast.text.isNotEmpty()) parts += broadcast.text
            if (broadcast.hasLocation) {
                parts += String.format(Locale.US, "📍 %.5f, %.5f", broadcast.latitude, broadcast.longitude)
            }
            return parts.joinToString(" — ")
        }

        private fun prefix(text: String, count: Int): String {
            if (text.codePointCount(0, text.length) <= count) return text
            return text.substring(0, text.offsetByCodePoints(0, count))
        }
    }
}
