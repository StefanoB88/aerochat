package com.example.aerochat.core.media

import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.crypto.IdentityKeys
import com.example.aerochat.core.mesh.InboundPacket
import com.example.aerochat.core.mesh.MeshRouterActor
import com.example.aerochat.core.mesh.Reachability
import com.example.aerochat.core.protocol.InnerPayloadFactory
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.nowUnixMilliseconds
import com.example.aerochat.core.protocol.toByteString
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.MessageContentType
import com.example.aerochat.data.records.MessageRecord
import com.example.aerochat.data.records.PeerRecord
import com.example.aerochat.proto.InnerPayload
import com.example.aerochat.proto.PacketType
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
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

/**
 * Invio e ricezione di foto WebP e note vocali Opus sulla mesh:
 * `MEDIA_HEADER` (metadati + BlurHash/forma d'onda per l'anteprima istantanea) seguito da `MEDIA_CHUNK`.
 *
 * Actor Swift → stato confinato su un dispatcher a parallelismo 1 (rientrante alle sospensioni, come un actor).
 */
class MediaTransferService(
    val router: MeshRouterActor,
    val database: AppDatabase,
    val store: MediaStore,
    private val identity: IdentityKeys,
    nickname: String,
) {

    sealed class Failure(message: String) : Exception(message) {
        data object recipientOutOfRange : Failure("recipientOutOfRange") {
            private fun readResolve(): Any = recipientOutOfRange
        }
        data class mediaTooLarge(val size: Int) : Failure("mediaTooLarge($size)")
        data class unsupportedMime(val mimeType: String) : Failure("unsupportedMime($mimeType)")
    }

    sealed interface Event {
        /** Avanzamento in chunk (in ricezione o in invio) del media legato al messaggio `msgID`. */
        class Progress(val msgID: ByteArray, val received: Int, val total: Int) : Event {
            override fun equals(other: Any?): Boolean =
                other is Progress && msgID.contentEquals(other.msgID) && received == other.received && total == other.total
            override fun hashCode(): Int = (msgID.contentHashCode() * 31 + received) * 31 + total
            override fun toString(): String = "Progress(msgID=${msgID.toHex()}, received=$received, total=$total)"
        }

        class Completed(val msgID: ByteArray) : Event {
            override fun equals(other: Any?): Boolean = other is Completed && msgID.contentEquals(other.msgID)
            override fun hashCode(): Int = msgID.contentHashCode()
            override fun toString(): String = "Completed(msgID=${msgID.toHex()})"
        }

        /** Trasferimento fallito o scaduto: `msgID` permette alla UI di togliere l'anello di avanzamento. */
        class Failed(val mediaID: String, val msgID: ByteArray?) : Event {
            override fun equals(other: Any?): Boolean =
                other is Failed && mediaID == other.mediaID &&
                    (msgID?.contentEquals(other.msgID) ?: (other.msgID == null))
            override fun hashCode(): Int = mediaID.hashCode() * 31 + (msgID?.contentHashCode() ?: 0)
            override fun toString(): String = "Failed(mediaID=$mediaID, msgID=${msgID?.toHex()})"
        }
    }

    /** Chunk arrivati prima dell'header, con il mittente (solo lui puo' completare quel media). */
    private class OrphanChunks(val senderNodeID: ByteArray, val receivedAt: Long) {
        val chunks = HashMap<Int, ByteArray>()
        var bytes = 0
    }

    private class Assembly(
        val mediaID: String,
        val kind: MediaStore.Kind,
        val headerMsgID: ByteArray,
        val conversation: ConversationRecord,
        val sender: PeerRecord,
        val totalChunks: Int,
        val totalBytes: Int,
        val startedAt: Long,
    ) {
        val chunks = HashMap<Int, ByteArray>()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val actorContext = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + actorContext)

    private var nickname: String = nickname
    private val assemblies = HashMap<String, Assembly>()
    /** Chunk arrivati prima del loro header (riordino sulla mesh). */
    private val orphanChunks = HashMap<String, OrphanChunks>()
    private var inboundTask: Job? = null
    private val subscriberIDs = AtomicLong(0)
    private val subscribers = ConcurrentHashMap<Long, Channel<Event>>()

    suspend fun start(): Unit = withContext(actorContext) {
        if (inboundTask != null) return@withContext
        val packets = router.inboundPackets()
        inboundTask = scope.launch {
            packets.collect { packet -> handle(packet) }
        }
    }

    fun stop() {
        inboundTask?.cancel()
        inboundTask = null
    }

    suspend fun setNickname(nickname: String): Unit = withContext(actorContext) {
        this@MediaTransferService.nickname = nickname
    }

    /** Eventi di avanzamento (ultimi 64 bufferizzati per iscritto). L'iscrizione e' registrata alla chiamata. */
    fun events(): Flow<Event> {
        val id = subscriberIDs.incrementAndGet()
        val channel = Channel<Event>(64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        subscribers[id] = channel
        return channel.consumeAsFlow().onCompletion { subscribers.remove(id) }
    }

    // MARK: - Invio

    suspend fun sendImage(image: ImageProcessor.Result, conversation: ConversationRecord): MessageRecord =
        send(MediaStore.Kind.image, image.webp, blurhash = image.blurhash, waveform = null, durationMs = null, conversation = conversation)

    suspend fun sendVoiceNote(recording: VoiceNoteRecorder.Recording, conversation: ConversationRecord): MessageRecord =
        send(
            MediaStore.Kind.audio, recording.oggOpus, blurhash = null, waveform = recording.waveform,
            durationMs = recording.durationMs, conversation = conversation,
        )

    private suspend fun send(
        kind: MediaStore.Kind,
        data: ByteArray,
        blurhash: String?,
        waveform: ByteArray?,
        durationMs: Int?,
        conversation: ConversationRecord,
    ): MessageRecord = withContext(actorContext) {
        if (data.size > maxMediaBytes) throw Failure.mediaTooLarge(data.size)
        // I media non entrano nella coda store-and-forward: serve un vicino a portata adesso.
        if (router.reachability(of = conversation) == Reachability.OutOfRange) throw Failure.recipientOutOfRange

        val mediaID = UUID.randomUUID().toString().lowercase()
        val headerMsgID = CryptoEngine.randomMessageID()
        val localPath = store.write(data, mediaID = mediaID, kind = kind)
        val chunks = (data.indices step chunkSize).map { start -> data.copyOfRange(start, minOf(start + chunkSize, data.size)) }

        val message = MessageRecord(
            msgID = headerMsgID,
            conversationID = conversation.id,
            senderNodeID = identity.nodeID,
            senderNickname = nickname,
            contentType = if (kind == MediaStore.Kind.image) MessageContentType.image else MessageContentType.audio,
            mediaLocalPath = localPath,
            blurhash = blurhash,
            audioWaveform = waveform,
            durationMs = durationMs,
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.queued,
        )
        database.messages.insert(message)
        // La bolla mostra subito l'anello di invio, prima ancora che parta l'header.
        emit(Event.Progress(msgID = headerMsgID, received = 0, total = chunks.size))

        val header = InnerPayloadFactory.signedBuilder(identity, nickname)
            .setMediaId(mediaID)
            .setMimeType(kind.mimeType)
            .setTotalSizeBytes(data.size)
            .setTotalChunks(chunks.size)
        if (blurhash != null) header.blurhash = blurhash
        if (waveform != null) header.audioWaveform = waveform.toByteString()
        if (durationMs != null) header.durationMs = durationMs
        try {
            sendChunks(chunks, header.build(), message, mediaID, kind, conversation)
        } catch (error: Throwable) {
            emit(Event.Failed(mediaID = mediaID, msgID = headerMsgID))
            throw error
        }
    }

    private suspend fun sendChunks(
        chunks: List<ByteArray>,
        header: InnerPayload,
        message: MessageRecord,
        mediaID: String,
        kind: MediaStore.Kind,
        conversation: ConversationRecord,
    ): MessageRecord {
        val headerMsgID = message.msgID
        val headerResult = router.sendPacket(
            type = PacketType.MEDIA_HEADER, payload = header, conversation = conversation,
            msgID = headerMsgID, storeIfUnreachable = false,
        )

        for ((index, chunk) in chunks.withIndex()) {
            val payload = InnerPayloadFactory.signedBuilder(identity, nickname)
                .setMediaId(mediaID)
                .setMimeType(kind.mimeType)
                .setTotalChunks(chunks.size)
                .setChunkIndex(index)
                .setChunkData(chunk.toByteString())
                .build()
            router.sendPacket(type = PacketType.MEDIA_CHUNK, payload = payload, conversation = conversation, storeIfUnreachable = false)
            emit(Event.Progress(msgID = headerMsgID, received = index + 1, total = chunks.size))
        }

        val sent = message.copy(deliveryStatus = if (headerResult.reached) DeliveryStatus.sentMesh else DeliveryStatus.queued)
        // Solo in avanti (come `sendText`): il `DELIVERY_ACK` del destinatario puo' arrivare prima di qui
        // e non va riportato indietro a `sentMesh` (il messaggio nasce gia' `queued`).
        if (headerResult.reached) {
            database.messages.advanceDeliveryStatus(to = DeliveryStatus.sentMesh, msgID = headerMsgID)
        }
        emit(Event.Completed(msgID = headerMsgID))
        return sent
    }

    // MARK: - Ricezione

    private suspend fun handle(packet: InboundPacket) {
        when (packet.envelope.packetType) {
            PacketType.MEDIA_HEADER -> handleHeader(packet)
            PacketType.MEDIA_CHUNK -> handleChunk(packet)
            else -> Unit
        }
        purgeStaleAssemblies()
    }

    private suspend fun handleHeader(packet: InboundPacket) {
        val payload = packet.payload
        val totalBytes = payload.totalSizeBytes.toUInt32()
        val kind = MediaStore.Kind(mimeType = payload.mimeType) ?: return
        if (payload.mediaId.isEmpty() ||
            totalBytes <= 0 || totalBytes > maxMediaBytes ||
            // Numero di chunk coerente con la dimensione: altrimenti il media non si completerebbe mai.
            payload.totalChunks.toUInt32() != (totalBytes + chunkSize - 1) / chunkSize ||
            // Header ripetuto (flooding): non buttiamo i chunk gia' ricevuti.
            assemblies.containsKey(payload.mediaId) ||
            assemblies.size >= maxConcurrentAssemblies
        ) return

        val headerMsgID = packet.envelope.msgId.toByteArray()
        val placeholder = MessageRecord(
            msgID = headerMsgID,
            conversationID = packet.conversation.id,
            senderNodeID = packet.sender.nodeID,
            senderNickname = payload.senderNickname.ifEmpty { packet.sender.nickname },
            contentType = if (kind == MediaStore.Kind.image) MessageContentType.image else MessageContentType.audio,
            blurhash = payload.blurhash.ifEmpty { null },
            audioWaveform = if (payload.audioWaveform.isEmpty) null else payload.audioWaveform.toByteArray(),
            // `uint32` sul filo: oltre 2^31 diventerebbe negativo in Int (etichetta di durata assurda).
            durationMs = if (payload.durationMs == 0) null
            else payload.durationMs.toUInt32().coerceAtMost(maxVoiceNoteDurationMs).toInt(),
            isOutgoing = false,
            deliveryStatus = DeliveryStatus.deliveredAck,
            hopCountReceived = packet.hopCount,
            createdAtMs = packet.envelope.createdAtMs,
        )
        attempt { database.messages.insert(placeholder) }

        val assembly = Assembly(
            mediaID = payload.mediaId,
            kind = kind,
            headerMsgID = headerMsgID,
            conversation = packet.conversation,
            sender = packet.sender,
            totalChunks = payload.totalChunks.toUInt32().toInt(),
            totalBytes = totalBytes.toInt(),
            startedAt = nowUnixMilliseconds,
        )
        val orphans = orphanChunks.remove(payload.mediaId)
        if (orphans != null && orphans.senderNodeID.contentEquals(packet.sender.nodeID)) {
            for ((index, chunk) in orphans.chunks) {
                if (index < assembly.totalChunks) assembly.chunks[index] = chunk
            }
        }
        assemblies[payload.mediaId] = assembly
        completeIfReady(payload.mediaId)
    }

    private suspend fun handleChunk(packet: InboundPacket) {
        val payload = packet.payload
        if (payload.mediaId.isEmpty() || payload.chunkData.isEmpty || payload.chunkData.size() > chunkSize) return
        val index = payload.chunkIndex.toUInt32()
        if (index >= maxChunks) return
        val chunkIndex = index.toInt()
        val assembly = assemblies[payload.mediaId]
        if (assembly != null) {
            if (!assembly.sender.nodeID.contentEquals(packet.sender.nodeID) || chunkIndex >= assembly.totalChunks) return
            assembly.chunks[chunkIndex] = payload.chunkData.toByteArray()
            emit(Event.Progress(msgID = assembly.headerMsgID, received = assembly.chunks.size, total = assembly.totalChunks))
            completeIfReady(payload.mediaId)
        } else {
            val orphans = orphanChunks[payload.mediaId]
                ?: OrphanChunks(senderNodeID = packet.sender.nodeID, receivedAt = nowUnixMilliseconds)
            if (!orphans.senderNodeID.contentEquals(packet.sender.nodeID) || orphans.chunks.containsKey(chunkIndex) ||
                orphanBytes + payload.chunkData.size() > maxOrphanBytes
            ) return
            orphans.chunks[chunkIndex] = payload.chunkData.toByteArray()
            orphans.bytes += payload.chunkData.size()
            orphanChunks[payload.mediaId] = orphans
        }
    }

    private suspend fun completeIfReady(mediaID: String) {
        val assembly = assemblies[mediaID] ?: return
        if (assembly.chunks.size != assembly.totalChunks) return
        assemblies.remove(mediaID)

        val output = ByteArrayOutputStream(assembly.totalBytes)
        for (index in 0 until assembly.totalChunks) {
            val chunk = assembly.chunks[index]
            if (chunk == null) {
                emit(Event.Failed(mediaID = mediaID, msgID = assembly.headerMsgID))
                return
            }
            output.write(chunk)
        }
        val data = output.toByteArray()
        if (data.size != assembly.totalBytes) {
            emit(Event.Failed(mediaID = mediaID, msgID = assembly.headerMsgID))
            return
        }
        // Foto: solo WebP e mai oltre `maxReceivedImageSide` (i mittenti producono al massimo 1280 px):
        // poche centinaia di byte possono dichiarare 16383x16383 e mandare in OOM viewer e salvataggio.
        if (assembly.kind == MediaStore.Kind.image &&
            (!WebPEncoder.isWebP(data) || ImageProcessor.exceedsDimensions(data, maxReceivedImageSide))
        ) {
            emit(Event.Failed(mediaID = mediaID, msgID = assembly.headerMsgID))
            return
        }
        try {
            val path = store.write(data, mediaID = mediaID, kind = assembly.kind)
            database.messages.updateMedia(localPath = path, msgID = assembly.headerMsgID)
            // ACK sul msg_id dell'header: il mittente passa a delivered_ack.
            val ack = InnerPayloadFactory.deliveryAck(assembly.headerMsgID, identity = identity, nickname = nickname)
            attempt {
                router.sendDirectPacket(type = PacketType.DELIVERY_ACK, payload = ack, to = assembly.sender, ttl = MeshProtocol.ackTTL)
            }
            emit(Event.Completed(msgID = assembly.headerMsgID))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emit(Event.Failed(mediaID = mediaID, msgID = assembly.headerMsgID))
        }
    }

    private fun purgeStaleAssemblies(now: Long = nowUnixMilliseconds) {
        val stale = assemblies.filter { now - it.value.startedAt > assemblyTimeout }
        for ((mediaID, assembly) in stale) {
            assemblies.remove(mediaID)
            emit(Event.Failed(mediaID = mediaID, msgID = assembly.headerMsgID))
        }
        // Chunk orfani (header perso o media gia' completato): scadono dopo un minuto.
        orphanChunks.entries.removeAll { now - it.value.receivedAt >= orphanTimeout }
    }

    private val orphanBytes: Int get() = orphanChunks.values.sumOf { it.bytes }

    private fun emit(event: Event) {
        for (channel in subscribers.values) channel.trySend(event)
    }

    companion object {
        const val chunkSize = 8_192
        const val maxMediaBytes = 1_048_576
        /** Lato massimo (px) accettato per una foto ricevuta (i mittenti iOS/Android inviano al massimo 1280 px). */
        const val maxReceivedImageSide = 4_096
        /** ms: durata massima mostrata per una nota vocale ricevuta (il registratore si ferma a 120 s). */
        const val maxVoiceNoteDurationMs = 10 * 60_000L
        /** ms (iOS 300 s). */
        const val assemblyTimeout: Long = 300_000L
        /** Trasferimenti in ricezione contemporanei (limite di memoria contro header a raffica). */
        const val maxConcurrentAssemblies = 16
        const val maxChunks = maxMediaBytes / chunkSize
        const val maxOrphanBytes = 2 * 1_048_576
        /** ms (iOS 60 s). */
        const val orphanTimeout: Long = 60_000L

        /** `UInt32` di protobuf (Int con segno in Java) come valore senza segno. */
        private fun Int.toUInt32(): Long = toLong() and 0xFFFF_FFFFL
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
