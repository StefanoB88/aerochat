package com.stefanobrandi.aerochat.core.calls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.stefanobrandi.aerochat.MainActivity
import com.stefanobrandi.aerochat.core.crypto.CryptoEngine
import com.stefanobrandi.aerochat.core.crypto.IdentityKeys
import com.stefanobrandi.aerochat.core.mesh.CallAudioPacket
import com.stefanobrandi.aerochat.core.mesh.InboundPacket
import com.stefanobrandi.aerochat.core.mesh.LocalNotificationCenter
import com.stefanobrandi.aerochat.core.mesh.MeshRouterActor
import com.stefanobrandi.aerochat.core.protocol.InnerPayloadFactory
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.MeshWire
import com.stefanobrandi.aerochat.core.protocol.nowUnixMilliseconds
import com.stefanobrandi.aerochat.core.protocol.toByteString
import com.stefanobrandi.aerochat.data.AppDatabase
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.ConversationType
import com.stefanobrandi.aerochat.data.records.DeliveryStatus
import com.stefanobrandi.aerochat.data.records.MessageContentType
import com.stefanobrandi.aerochat.data.records.MessageRecord
import com.stefanobrandi.aerochat.data.records.PeerRecord
import com.stefanobrandi.aerochat.proto.CallAction
import com.stefanobrandi.aerochat.proto.CallAudio
import com.stefanobrandi.aerochat.proto.CallSignal
import com.stefanobrandi.aerochat.proto.PacketType
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pipeline audio di una chiamata (cattura + Opus + riproduzione). In produzione e' [CallAudioEngine];
 * l'interfaccia esiste solo come seam per i test (su iOS l'engine e' creato internamente).
 */
interface CallAudioPipeline {
    /** Avvia cattura e riproduzione; `onPacket` riceve i frame Opus da inviare (3 per pacchetto). Lancia se fallisce. */
    fun start(onPacket: (List<ByteArray>) -> Unit)
    fun stop()
    fun setMuted(muted: Boolean)
    fun setSpeaker(enabled: Boolean)
    fun receive(sequence: Int, frames: List<ByteArray>)
}

/** Adattatore di [CallAudioEngine] a [CallAudioPipeline]. */
class CallAudioEnginePipeline(private val engine: CallAudioEngine) : CallAudioPipeline {
    override fun start(onPacket: (List<ByteArray>) -> Unit) = engine.start { frames -> onPacket(frames) }
    override fun stop() = engine.stop()
    override fun setMuted(muted: Boolean) = engine.setMuted(muted)
    override fun setSpeaker(enabled: Boolean) = engine.setSpeaker(enabled)
    override fun receive(sequence: Int, frames: List<ByteArray>) = engine.receive(sequence, frames)
}

/**
 * Chiamate vocali in tempo reale tra vicini diretti: segnalazione `CALL_SIGNAL`, audio `CALL_AUDIO`,
 * squillo con timeout, monitoraggio del link e registro chiamate nella chat.
 *
 * `@MainActor @Observable` di iOS → stato protetto da un lock ed esposto come [StateFlow] ([current]).
 * Le azioni dell'utente ([accept], [decline], [hangUp], [toggleMute], [toggleSpeaker]) sono sincrone
 * come su iOS; [placeCall] e [start] sono `suspend`.
 *
 * Il gate "Pass / link diretto / segnale >= [MeshProtocol.minimumCallRSSI]" e' applicato dalla UI
 * (`FeatureGate`), come su iOS. Il permesso `RECORD_AUDIO` deve essere chiesto dalla UI prima di
 * [placeCall]/[accept]: qui viene solo verificato (negato → `EndReason.microphoneDenied`).
 *
 * Foreground service: osservare [isAudioActive] (true mentre la chiamata e' `Active`, cioe' microfono
 * acceso) per avviare/fermare il servizio con tipo `microphone`.
 */
class CallService(
    context: Context,
    private val router: MeshRouterActor,
    private val database: AppDatabase,
    private val identity: IdentityKeys,
    nickname: String,
    private val audio: CallAudioPipeline = CallAudioEnginePipeline(CallAudioEngine(context)),
    /** Verifica del permesso microfono (sostituibile nei test). */
    private val hasRecordPermission: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    },
) {

    enum class Direction {
        outgoing,
        incoming,
    }

    enum class EndReason {
        hungUp,
        rejected,
        busy,
        noAnswer,
        missed,
        connectionLost,
        unreachable,
        microphoneDenied,
        failed,
    }

    sealed interface Phase {
        /** In uscita: squilla dal compagno. In entrata: sta squillando qui. */
        data object Ringing : Phase
        data object Active : Phase
        data class Ended(val reason: EndReason) : Phase
    }

    /** `connectedAt` epoch ms. */
    data class Call(
        val id: String,
        val peer: PeerRecord,
        val conversation: ConversationRecord,
        val direction: Direction,
        val phase: Phase = Phase.Ringing,
        val connectedAt: Long? = null,
        val isMuted: Boolean = false,
        val isSpeakerOn: Boolean = false,
        /** Pacchetti audio in ritardo: il link radio fatica (avvicinarsi). */
        val isSignalWeak: Boolean = false,
    )

    private val context: Context = context.applicationContext
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _current = MutableStateFlow<Call?>(null)
    /** Chiamata in corso (o appena terminata: resta 1,5 s con `Phase.Ended` per il riepilogo). */
    val current: StateFlow<Call?> = _current.asStateFlow()

    private val _isAudioActive = MutableStateFlow(false)
    /** Hook per il foreground service `microphone`: `true` mentre audio e microfono sono attivi. */
    val isAudioActive: StateFlow<Boolean> = _isAudioActive.asStateFlow()

    @Volatile
    private var nickname: String = nickname
    private val listenTasks = ArrayList<Job>()
    private val callTasks = ArrayList<Job>()
    private var audioChannel: Channel<List<ByteArray>>? = null
    /**
     * Chiamata per cui l'accettazione / l'avvio dell'audio e' gia' in corso (protetto da [lock]): un doppio
     * tocco su "Rispondi" (o un CALL_ACCEPT duplicato) avvierebbe due pipeline e il secondo canale audio
     * sostituirebbe il primo, lasciando il compagno senza audio.
     */
    private var acceptingCallID: String? = null
    private var mediaStartingCallID: String? = null
    @Volatile
    private var lastAudioAt: Long = 0

    fun setNickname(nickname: String) {
        this.nickname = nickname
    }

    suspend fun start() {
        synchronized(lock) { if (listenTasks.isNotEmpty()) return }
        // Iscrizioni registrate subito (prima della raccolta).
        val packets = router.inboundPackets()
        val audioPackets = router.callAudioPackets()
        synchronized(lock) {
            if (listenTasks.isNotEmpty()) return
            listenTasks += scope.launch {
                packets.collect { packet ->
                    if (packet.envelope.packetType == PacketType.CALL_SIGNAL) handleSignal(packet)
                }
            }
            listenTasks += scope.launch {
                audioPackets.collect { packet -> handleAudio(packet) }
            }
        }
    }

    fun stop() {
        if (_current.value != null) hangUp()
        val tasks = synchronized(lock) { listenTasks.toList().also { listenTasks.clear() } }
        tasks.forEach { it.cancel() }
    }

    // MARK: - Azioni dell'utente

    /** Chiama il compagno della chat 1-a-1 (il gate su Pass/segnale e' gia' stato applicato dalla UI). */
    suspend fun placeCall(conversation: ConversationRecord) {
        if (_current.value != null || conversation.type != ConversationType.direct) return
        val peerID = conversation.peerNodeID ?: return
        val peer = attempt { database.peers.fetch(nodeID = peerID) } ?: return
        val call = Call(id = UUID.randomUUID().toString().lowercase(), peer = peer, conversation = conversation, direction = Direction.outgoing)
        synchronized(lock) {
            if (_current.value != null) return
            setCurrent(call)
        }
        if (!hasRecordPermission()) {
            finish(call.id, EndReason.microphoneDenied, notifyPeer = false)
            return
        }
        if (!send(CallAction.CALL_OFFER, call.id, peer)) {
            finish(call.id, EndReason.unreachable, notifyPeer = false)
            return
        }
        armRingTimeout(call.id)
    }

    fun accept() {
        val call = _current.value ?: return
        if (call.direction != Direction.incoming || call.phase != Phase.Ringing) return
        synchronized(lock) {
            if (acceptingCallID == call.id) return
            acceptingCallID = call.id
        }
        scope.launch {
            if (!hasRecordPermission()) {
                send(CallAction.CALL_REJECT, call.id, call.peer)
                finish(call.id, EndReason.microphoneDenied, notifyPeer = false)
                return@launch
            }
            val now = _current.value
            if (now?.id != call.id || now.phase != Phase.Ringing) return@launch
            if (!send(CallAction.CALL_ACCEPT, call.id, call.peer)) {
                finish(call.id, EndReason.unreachable, notifyPeer = false)
                return@launch
            }
            beginMedia(call.id)
        }
    }

    fun decline() {
        val call = _current.value ?: return
        if (call.direction != Direction.incoming || call.phase != Phase.Ringing) return
        scope.launch { send(CallAction.CALL_REJECT, call.id, call.peer) }
        finish(call.id, EndReason.rejected, notifyPeer = false)
    }

    fun hangUp() {
        // Anche prima della risposta: il compagno smette di squillare (per lui e' una chiamata persa).
        val call = _current.value ?: return
        finish(call.id, EndReason.hungUp, notifyPeer = true)
    }

    fun toggleMute() {
        val updated = synchronized(lock) {
            val call = _current.value ?: return
            if (call.phase != Phase.Active) return
            call.copy(isMuted = !call.isMuted).also { setCurrent(it) }
        }
        audio.setMuted(updated.isMuted)
    }

    fun toggleSpeaker() {
        val updated = synchronized(lock) {
            val call = _current.value ?: return
            if (call.phase != Phase.Active) return
            call.copy(isSpeakerOn = !call.isSpeakerOn).also { setCurrent(it) }
        }
        audio.setSpeaker(updated.isSpeakerOn)
    }

    // MARK: - Segnalazione

    private suspend fun handleSignal(packet: InboundPacket) {
        val signal = attempt { MeshWire.parseCallSignal(packet.payload.extraData.toByteArray()) } ?: return
        if (signal.callId.isEmpty() || signal.callId.length > 64) return
        val sender = packet.sender
        when (signal.action) {
            CallAction.CALL_OFFER -> {
                val existing = _current.value
                if (existing != null) {
                    // Gia' al telefono (o lo stesso OFFER ritrasmesso): occupato per chiunque altro.
                    if (existing.id != signal.callId) send(CallAction.CALL_BUSY, signal.callId, sender)
                    return
                }
                if (packet.conversation.type != ConversationType.direct) return
                synchronized(lock) {
                    if (_current.value != null) return
                    setCurrent(Call(id = signal.callId, peer = sender, conversation = packet.conversation, direction = Direction.incoming))
                }
                startRinging(signal.callId, sender, packet.conversation)
            }
            CallAction.CALL_ACCEPT -> {
                val call = _current.value ?: return
                if (call.id != signal.callId || !call.peer.nodeID.contentEquals(sender.nodeID) ||
                    call.direction != Direction.outgoing || call.phase != Phase.Ringing
                ) return
                beginMedia(call.id)
            }
            CallAction.CALL_REJECT -> {
                if (!matches(signal, sender)) return
                finish(signal.callId, EndReason.rejected, notifyPeer = false)
            }
            CallAction.CALL_BUSY -> {
                if (!matches(signal, sender)) return
                finish(signal.callId, EndReason.busy, notifyPeer = false)
            }
            CallAction.CALL_HANGUP -> {
                val call = _current.value ?: return
                if (!matches(signal, sender)) return
                // Il chiamante ha rinunciato mentre squillava da noi: per noi e' una chiamata persa.
                val reason = if (call.direction == Direction.incoming && call.phase == Phase.Ringing) EndReason.missed else EndReason.hungUp
                finish(signal.callId, reason, notifyPeer = false)
            }
            else -> return
        }
    }

    private fun matches(signal: CallSignal, sender: PeerRecord): Boolean {
        val call = _current.value ?: return false
        return call.id == signal.callId && call.peer.nodeID.contentEquals(sender.nodeID)
    }

    private suspend fun send(action: CallAction, callID: String, peer: PeerRecord): Boolean {
        val signal = CallSignal.newBuilder().setCallId(callID).setAction(action).build()
        val data = attempt { MeshWire.serialize(signal) } ?: return false
        val payload = InnerPayloadFactory.extra(data, identity = identity, nickname = nickname)
        return attempt { router.sendCallPacket(type = PacketType.CALL_SIGNAL, payload = payload, to = peer) } ?: false
    }

    private fun armRingTimeout(callID: String) {
        addCallTask {
            delay(MeshProtocol.callRingTimeout)
            val call = _current.value ?: return@addCallTask
            if (call.id != callID || call.phase != Phase.Ringing) return@addCallTask
            finish(callID, if (call.direction == Direction.outgoing) EndReason.noAnswer else EndReason.missed, notifyPeer = true)
        }
    }

    private fun startRinging(callID: String, peer: PeerRecord, conversation: ConversationRecord) {
        armRingTimeout(callID)
        if (!isAppInForeground()) {
            postIncomingCallNotification(callID, peer, conversation)
        }
        // Squillo: vibrazione ritmata finche' l'utente non risponde.
        addCallTask {
            while (isActive) {
                val call = _current.value
                if (call?.id != callID || call.phase != Phase.Ringing) break
                vibrate(longArrayOf(0, 400, 200, 400))
                delay(2_000)
            }
        }
    }

    // MARK: - Audio

    private fun beginMedia(callID: String) {
        synchronized(lock) {
            val call = _current.value
            if (call?.id != callID || call.phase != Phase.Ringing || mediaStartingCallID == callID) return
            mediaStartingCallID = callID
        }
        val channel = Channel<List<ByteArray>>(8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        try {
            audio.start { frames -> channel.trySend(frames) }
        } catch (error: Exception) {
            channel.close()
            finish(callID, EndReason.failed, notifyPeer = true)
            return
        }
        val call = synchronized(lock) {
            val call = _current.value
            if (call == null || call.id != callID || call.phase is Phase.Ended) null
            else {
                audioChannel = channel
                lastAudioAt = nowUnixMilliseconds
                call.copy(phase = Phase.Active, connectedAt = nowUnixMilliseconds).also { setCurrent(it) }
            }
        }
        if (call == null) {
            // Chiamata chiusa mentre l'audio partiva.
            channel.close()
            audio.stop()
            return
        }
        cancelCallTasks()
        vibrate(longArrayOf(0, 60))

        // Invio sequenziale (un solo consumatore): i pacchetti partono nell'ordine di cattura.
        val peer = call.peer
        addCallTask {
            var sequence = 0
            for (frames in channel) {
                sequence += 1 // UInt32 `&+=`: l'Int con segno avvolge con gli stessi bit.
                val packet = CallAudio.newBuilder()
                    .setCallId(callID)
                    .setSequence(sequence)
                    .addAllFrames(frames.map { it.toByteString() })
                    .build()
                val data = attempt { MeshWire.serialize(packet) } ?: continue
                val payload = InnerPayloadFactory.extra(data, identity = identity, nickname = nickname)
                attempt { router.sendCallPacket(type = PacketType.CALL_AUDIO, payload = payload, to = peer) }
            }
        }
        // Monitoraggio del link: segnale debole dopo 2 s senza audio, chiusura dopo `callMediaTimeout`.
        addCallTask {
            while (isActive) {
                delay(1_000)
                val current = _current.value
                if (current == null || current.id != callID || current.phase != Phase.Active) break
                val silence = nowUnixMilliseconds - lastAudioAt
                if (silence > MeshProtocol.callMediaTimeout) {
                    finish(callID, EndReason.connectionLost, notifyPeer = true)
                    break
                }
                val weak = silence > 2_000
                synchronized(lock) {
                    val latest = _current.value
                    if (latest != null && latest.id == callID && latest.phase == Phase.Active && latest.isSignalWeak != weak) {
                        setCurrent(latest.copy(isSignalWeak = weak))
                    }
                }
            }
        }
    }

    private fun handleAudio(packet: CallAudioPacket) {
        val call = _current.value ?: return
        if (call.phase != Phase.Active || call.id != packet.audio.callId ||
            !call.peer.nodeID.contentEquals(packet.senderNodeID) || packet.audio.framesCount == 0
        ) return
        lastAudioAt = nowUnixMilliseconds
        audio.receive(packet.audio.sequence, packet.audio.framesList.map { it.toByteArray() })
    }

    // MARK: - Chiusura

    private fun finish(callID: String, reason: EndReason, notifyPeer: Boolean) {
        val ended: Call
        val duration: Long?
        val tasks: List<Job>
        val channel: Channel<List<ByteArray>>?
        synchronized(lock) {
            val call = _current.value ?: return
            if (call.id != callID || call.phase is Phase.Ended) return
            tasks = callTasks.toList()
            callTasks.clear()
            channel = audioChannel
            audioChannel = null
            duration = call.connectedAt?.let { nowUnixMilliseconds - it }
            ended = call.copy(phase = Phase.Ended(reason))
            setCurrent(ended)
        }
        if (notifyPeer) {
            val peer = ended.peer
            scope.launch { send(CallAction.CALL_HANGUP, callID, peer) }
        }
        tasks.forEach { it.cancel() }
        channel?.close()
        // Lo stop dell'audio (join dei thread di cattura/riproduzione, stop/release di MediaCodec) puo'
        // durare centinaia di ms: `hangUp()` arriva dal main thread (tocco in UI), quindi lo facciamo in
        // background. Una nuova chiamata non puo' partire prima che `current` torni null (1,5 s sotto).
        scope.launch { runCatching { audio.stop() } }
        runCatching { NotificationManagerCompat.from(context).cancel(callID, incomingCallNotificationID) }

        scope.launch { logCall(ended, reason, duration) }
        if (reason != EndReason.hungUp) vibrate(longArrayOf(0, 80, 80, 80))

        // Il riepilogo resta a schermo un attimo, poi la schermata di chiamata si chiude.
        scope.launch {
            delay(1_500)
            synchronized(lock) {
                if (_current.value?.id == callID) setCurrent(null)
            }
        }
    }

    private fun setCurrent(call: Call?) {
        _current.value = call
        _isAudioActive.value = call?.phase == Phase.Active
    }

    private fun addCallTask(block: suspend CoroutineScope.() -> Unit) {
        // Registrato prima di partire: un `finish` eseguito dal job stesso lo trova gia' in elenco.
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        synchronized(lock) { callTasks += job }
        job.start()
    }

    private fun cancelCallTasks() {
        val tasks = synchronized(lock) { callTasks.toList().also { callTasks.clear() } }
        tasks.forEach { it.cancel() }
    }

    /** Registro nella chat (solo locale): durata, chiamata persa, rifiutata, senza risposta. `duration` in ms. */
    private suspend fun logCall(call: Call, reason: EndReason, duration: Long?) {
        val text = logText(call.direction, reason, duration)
        val outgoing = call.direction == Direction.outgoing
        val message = MessageRecord(
            msgID = CryptoEngine.randomMessageID(),
            conversationID = call.conversation.id,
            senderNodeID = if (outgoing) identity.nodeID else call.peer.nodeID,
            senderNickname = if (outgoing) nickname else call.peer.nickname,
            contentType = MessageContentType.system,
            text = text,
            isOutgoing = outgoing,
            // Voce solo locale: niente conferme di lettura verso il compagno.
            deliveryStatus = DeliveryStatus.read,
        )
        attempt { database.messages.insert(message) }
    }

    // MARK: - Piattaforma

    private fun isAppInForeground(): Boolean = runCatching {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }.getOrDefault(false)

    private fun postIncomingCallNotification(callID: String, peer: PeerRecord, conversation: ConversationRecord) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(callsChannelID) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(callsChannelID, callsChannelName, NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(LocalNotificationCenter.extraConversationID, conversation.id)
                putExtra(extraCallID, callID)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, callID.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, callsChannelID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(peer.nickname)
                .setContentText(incomingCallText)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setTimeoutAfter(MeshProtocol.callRingTimeout)
                .build()
            NotificationManagerCompat.from(context).notify(callID, incomingCallNotificationID, notification)
        }
    }

    private fun vibrate(pattern: LongArray) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    companion object {
        const val callsChannelID = "calls"
        const val callsChannelName = "Chiamate"
        /** Extra dell'intent della notifica di chiamata in arrivo (iOS `userInfo["call_id"]`). */
        const val extraCallID = "call_id"
        const val incomingCallText = "📞 Chiamata in arrivo su AeroChat"
        private const val incomingCallNotificationID = 2

        /** Swift `MessageBubble.durationLabel(_:)`: "m:ss". */
        fun durationLabel(ms: Int?): String {
            val seconds = (ms ?: 0) / 1_000
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }

        /** Testo del registro chiamate (iOS `logCall`). `duration` in ms, `null` se mai connessa. */
        fun logText(direction: Direction, reason: EndReason, duration: Long?): String {
            if (duration != null) return "📞 Chiamata vocale · ${durationLabel(duration.toInt())}"
            return when (direction) {
                Direction.incoming -> when (reason) {
                    EndReason.missed, EndReason.connectionLost, EndReason.noAnswer -> "📞 Chiamata persa"
                    else -> "📞 Chiamata rifiutata"
                }
                Direction.outgoing -> when (reason) {
                    EndReason.rejected -> "📞 Chiamata rifiutata"
                    EndReason.busy -> "📞 Occupato"
                    EndReason.noAnswer -> "📞 Nessuna risposta"
                    EndReason.hungUp -> "📞 Chiamata annullata"
                    else -> "📞 Chiamata non riuscita"
                }
            }
        }
    }
}

private inline fun <T> attempt(block: () -> T): T? =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
