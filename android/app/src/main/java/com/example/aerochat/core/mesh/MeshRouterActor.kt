package com.example.aerochat.core.mesh

import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.crypto.IdentityKeys
import com.example.aerochat.core.crypto.PairingPayload
import com.example.aerochat.core.crypto.SymmetricKey
import com.example.aerochat.core.media.ImageProcessor
import com.example.aerochat.core.media.WebPEncoder
import com.example.aerochat.core.protocol.EnvelopeBuilder
import com.example.aerochat.core.protocol.EnvelopeVerifier
import com.example.aerochat.core.protocol.GroupMember
import com.example.aerochat.core.protocol.InnerPayloadFactory
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.MeshWire
import com.example.aerochat.core.protocol.asPeerRecord
import com.example.aerochat.core.protocol.declaredSenderNodeID
import com.example.aerochat.core.protocol.defaultPassengerNickname
import com.example.aerochat.core.protocol.isConsistent
import com.example.aerochat.core.protocol.nowUnixMilliseconds
import com.example.aerochat.core.protocol.rawBytes
import com.example.aerochat.core.protocol.replyToMessageID
import com.example.aerochat.core.protocol.toByteString
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.core.protocol.uuidFromRawBytes
import com.example.aerochat.core.transport.LinkID
import com.example.aerochat.core.transport.LinkRole
import com.example.aerochat.core.transport.PeerLink
import com.example.aerochat.core.transport.RadioState
import com.example.aerochat.core.transport.TransportEvent
import com.example.aerochat.core.transport.TransportProvider
import com.example.aerochat.core.transport.lexicographicallyPrecedes
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.ConversationType
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.MessageContentType
import com.example.aerochat.data.records.MessageRecord
import com.example.aerochat.data.records.PeerRecord
import com.example.aerochat.data.records.RelayQueueRecord
import com.example.aerochat.proto.CallAudio
import com.example.aerochat.proto.GroupKeyGossip
import com.example.aerochat.proto.InnerPayload
import com.example.aerochat.proto.MeshEnvelope
import com.example.aerochat.proto.PacketType
import com.example.aerochat.proto.ProfileUpdate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Motore mesh: managed flooding anti-loop, routing verso le nostre conversazioni, relay con filtro
 * media, Store-and-Forward, `DELIVERY_ACK` e Group Key Gossip.
 *
 * Port dell'`actor` Swift: tutto lo stato e' confinato su un dispatcher seriale ([actorContext],
 * `limitedParallelism(1)`); ogni API pubblica `suspend` vi salta con `withContext`. Come per un actor
 * Swift, durante le sospensioni (DB, trasporto) altri messaggi possono essere elaborati (rientranza),
 * ma mai in parallelo.
 */
class MeshRouterActor(
    val identity: IdentityKeys,
    val database: AppDatabase,
    val transport: TransportProvider,
    nickname: String,
    private val notifier: MeshNotifier = NoopNotifier(),
    private val configuration: Configuration = Configuration(),
) {

    /** Tutte le durate sono in **millisecondi**. */
    data class Configuration(
        /** 16 byte per id: 65k id ~ 1 MB, copre la finestra anti-replay anche con molti chunk media. */
        val seenCacheCapacity: Int = 65_536,
        val maintenanceInterval: Long = 600_000L,
        val relayEnabled: Boolean = true,
        /** Finestra entro cui un `last_seen` via ponte conta come "connesso tramite ponte". */
        val bridgeFreshness: Long = 120_000L,
        /** Anti-replay: pacchetti piu' vecchi di cosi' vengono scartati (coda store-and-forward 6 h + tolleranza). */
        val replayWindow: Long = MeshProtocol.relayQueueLifetime + 15 * 60_000L,
        /** Anti-replay: tolleranza per `created_at_ms` nel futuro (orologi non sincronizzati). */
        val futureSkewTolerance: Long = 5 * 60_000L,
        /**
         * Backoff casuale (ms) prima di ritrasmettere: se nel frattempo un vicino ritrasmette
         * lo stesso pacchetto, il nostro relay viene annullato (meno traffico in cabina piena).
         */
        val relayBackoff: LongRange = 50L..300L,
        /**
         * Ogni quanto ritrasmettere ai vicini i nostri messaggi ancora senza `DELIVERY_ACK`
         * (il link puo' sembrare aperto mentre l'app del destinatario e' sospesa).
         */
        val ackRetryInterval: Long = 30_000L,
        /** Intervallo minimo tra due ACK per lo stesso `msg_id` (ritrasmissioni duplicate). */
        val reackThrottle: Long = 15_000L,
        /**
         * Intervallo minimo tra due scritture di `last_seen` per lo stesso peer (a meno che cambi l'hop):
         * evita una transazione e un refresh della UI per ogni chunk ricevuto.
         */
        val lastSeenWriteInterval: Long = 15_000L,
    )

    /** Risultato di [sendPacket] (tupla `(msgID, reached)` su iOS). */
    class SendResult(val msgID: ByteArray, val reached: Boolean) {
        operator fun component1(): ByteArray = msgID
        operator fun component2(): Boolean = reached
        override fun equals(other: Any?): Boolean =
            other is SendResult && msgID.contentEquals(other.msgID) && reached == other.reached
        override fun hashCode(): Int = 31 * msgID.contentHashCode() + reached.hashCode()
    }

    private sealed interface Target {
        data object Me : Target
        data class Group(val conversation: ConversationRecord) : Target
        data class Lounge(val conversation: ConversationRecord) : Target
        data object Other : Target
    }

    private class LastSeenWrite(val date: Long, val hops: Int?)
    private class CallPeer(val peer: PeerRecord, val key: SymmetricKey)

    // MARK: - Stato (confinato su actorContext)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val actorContext = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + actorContext)

    private val myNodeID: ByteArray = identity.nodeID
    private val myNodeHex: String = myNodeID.toHex()
    private val loungeRecipientHex: String = MeshProtocol.loungeRecipientID.toHex()

    private var nickname: String = nickname
    private val seen = SeenPacketCache(capacity = configuration.seenCacheCapacity)
    private val links = LinkedHashMap<LinkID, PeerLink>()
    /** `node_id` bloccati (hex). */
    private var blocked: Set<String> = emptySet()
    private var isRunning = false
    private var eventTask: Job? = null
    private var maintenanceTask: Job? = null
    private var ackRetryTask: Job? = null
    /** Ultimo `DELIVERY_ACK` inviato per `msg_id` (hex), per non rispondere a ogni duplicato del flooding. */
    private val lastAckSentAt = HashMap<String, Long>()
    private val subscriberIDs = AtomicLong(0)
    private val subscribers = ConcurrentHashMap<Long, Channel<RouterEvent>>()
    private val inboundSubscribers = ConcurrentHashMap<Long, Channel<InboundPacket>>()
    private val callAudioSubscribers = ConcurrentHashMap<Long, Channel<CallAudioPacket>>()
    /** Peer e chiave 1-a-1 per l'audio delle chiamate: ~17 pacchetti/s non devono leggere il DB. */
    private val callPeerCache = HashMap<String, CallPeer>()
    /** Relay in attesa del backoff, per `msg_id` (hex). */
    private val pendingRelays = HashMap<String, Job>()
    /** Ultima scrittura di `last_seen` per peer (throttle di `markSeen`). */
    private val lastSeenWrites = HashMap<String, LastSeenWrite>()
    /** Coalescenza dello svuotamento della relay queue: niente flush sovrapposti (rientranza). */
    private var isFlushingRelayQueue = false
    private val pendingFlushTargets = ArrayList<ByteArray?>()
    /** Profilo da annunciare e nodi (hex) a cui e' gia' stato inviato da quando il link e' aperto. */
    private var profile: LocalProfile? = null
    private val profileSentTo = HashSet<String>()
    /** `recipient_id` (hex) dei gruppi noti. */
    private val knownGroupRecipientIDs = HashSet<String>()

    @Volatile
    private var currentStats = RouterStats()

    /** Statistiche correnti (snapshot immutabile, leggibile da qualsiasi thread). */
    val stats: RouterStats get() = currentStats

    @Volatile
    var radioState: RadioState = RadioState.unknown
        private set

    private inline fun updateStats(transform: (RouterStats) -> RouterStats) {
        currentStats = transform(currentStats)
    }

    // MARK: - Ciclo di vita

    suspend fun start(): Unit = withContext(actorContext) {
        if (isRunning) return@withContext
        isRunning = true

        val now = nowUnixMilliseconds
        attempt { database.seenPackets.activeMessageIDs(now = now, limit = configuration.seenCacheCapacity) }?.let {
            seen.preload(it, expiresAtMs = now + MeshProtocol.seenPacketLifetime)
        }
        blocked = attempt { database.peers.blockedNodeIDs() } ?: emptySet()
        attempt { database.conversations.fetchPermanent() }?.let { conversations ->
            knownGroupRecipientIDs.clear()
            conversations.filter { it.type == ConversationType.group }.mapTo(knownGroupRecipientIDs) { it.recipientID.toHex() }
        }

        // Iscrizione registrata subito: nessun evento perso tra `events()` e la raccolta.
        val stream = transport.events()
        eventTask = scope.launch {
            stream.collect { event ->
                // Un evento difettoso non deve spegnere la ricezione (ne' far crashare il processo).
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateStats { it.copy(malformed = it.malformed + 1) }
                }
            }
        }
        transport.start()
        refreshTrustedPeers()
        radioState = transport.radioState()

        val interval = configuration.maintenanceInterval
        maintenanceTask = scope.launch {
            while (isActive) {
                delay(interval)
                if (!isActive) break
                runMaintenance()
            }
        }

        val retryInterval = configuration.ackRetryInterval
        ackRetryTask = scope.launch {
            while (isActive) {
                delay(retryInterval)
                if (!isActive) break
                retryUnacknowledged()
            }
        }
    }

    suspend fun stop(): Unit = withContext(actorContext) {
        if (!isRunning) return@withContext
        isRunning = false
        eventTask?.cancel()
        maintenanceTask?.cancel()
        ackRetryTask?.cancel()
        eventTask = null
        maintenanceTask = null
        ackRetryTask = null
        pendingRelays.values.forEach { it.cancel() }
        pendingRelays.clear()
        transport.stop()
        links.clear()
    }

    suspend fun setNickname(nickname: String): Unit = withContext(actorContext) {
        this@MeshRouterActor.nickname = nickname
    }

    /** Imposta il profilo pubblico; con `announce` lo invia subito a tutti i contatti non bloccati. */
    suspend fun setProfile(profile: LocalProfile, announce: Boolean): Unit = withContext(actorContext) {
        this@MeshRouterActor.profile = profile
        nickname = profile.nickname
        profileSentTo.clear()
        if (!announce || !isRunning) return@withContext
        val peers = attempt { database.peers.unblockedPeers() } ?: return@withContext
        for (peer in peers) {
            sendProfile(peer)
        }
    }

    /** Eventi verso la UI (ultimi 128 bufferizzati). L'iscrizione e' registrata alla chiamata. */
    fun events(): Flow<RouterEvent> = subscribe(subscribers, capacity = 128)

    /**
     * Pacchetti media / giochi / Pro Pass gia' decifrati e verificati (ultimi 256 bufferizzati).
     * Con [types] la coda riceve solo quei tipi: un servizio che vuole le mosse di gioco non si fa
     * svuotare il buffer dai chunk di una foto.
     */
    fun inboundPackets(vararg types: PacketType): Flow<InboundPacket> {
        val id = subscriberIDs.incrementAndGet()
        val channel = Channel<InboundPacket>(256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        if (types.isNotEmpty()) inboundFilters[id] = types.toSet()
        inboundSubscribers[id] = channel
        return channel.consumeAsFlow().onCompletion {
            inboundSubscribers.remove(id)
            inboundFilters.remove(id)
        }
    }

    /** Tipi accettati per iscritto a `inboundPackets` (assente = tutti). */
    private val inboundFilters = ConcurrentHashMap<Long, Set<PacketType>>()

    /** Audio delle chiamate in arrivo (solo vicini diretti, gia' verificato; ultimi 64 bufferizzati). */
    fun callAudioPackets(): Flow<CallAudioPacket> = subscribe(callAudioSubscribers, capacity = 64)

    private fun <T> subscribe(registry: ConcurrentHashMap<Long, Channel<T>>, capacity: Int): Flow<T> {
        val id = subscriberIDs.incrementAndGet()
        val channel = Channel<T>(capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        registry[id] = channel
        return channel.consumeAsFlow().onCompletion { registry.remove(id) }
    }

    /** `true` se esiste un link radio diretto (1 hop) verso il nodo. */
    suspend fun isDirectNeighbor(nodeID: ByteArray): Boolean = withContext(actorContext) {
        links.values.any { it.nodeID?.contentEquals(nodeID) == true }
    }

    suspend fun neighbors(): List<Neighbor> = withContext(actorContext) { currentNeighbors() }

    private fun currentNeighbors(): List<Neighbor> {
        val byNode = LinkedHashMap<String, Neighbor>()
        for (link in links.values) {
            val nodeID = link.nodeID ?: continue
            val key = nodeID.toHex()
            val existing = byNode[key]
            if (existing != null) {
                val best = maxOf(existing.rssi ?: Int.MIN_VALUE, link.rssi ?: Int.MIN_VALUE)
                byNode[key] = existing.copy(
                    linkCount = existing.linkCount + 1,
                    rssi = if (best == Int.MIN_VALUE) null else best,
                )
            } else {
                byNode[key] = Neighbor(nodeID = nodeID, rssi = link.rssi, linkCount = 1)
            }
        }
        return byNode.values.sortedWith { a, b ->
            when {
                a.nodeID.lexicographicallyPrecedes(b.nodeID) -> -1
                b.nodeID.lexicographicallyPrecedes(a.nodeID) -> 1
                else -> 0
            }
        }
    }

    /** Stato radio semantico verso un nodo (header chat). `now` in epoch ms. */
    suspend fun reachability(of: ByteArray, now: Long = nowUnixMilliseconds): Reachability = withContext(actorContext) {
        currentNeighbors().firstOrNull { it.nodeID.contentEquals(of) }?.let {
            return@withContext Reachability.Direct(rssi = it.rssi)
        }
        val peer = attempt { database.peers.fetch(nodeID = of) }
        val seenAt = peer?.lastSeenAtMs
        val hops = peer?.lastHopCount
        if (seenAt == null || hops == null || hops <= 1 || seenAt + configuration.bridgeFreshness <= now) {
            return@withContext Reachability.OutOfRange
        }
        Reachability.ViaBridge(hops = hops)
    }

    /** Stato radio di una conversazione: diretta = quella del peer; gruppo = il migliore tra i membri. */
    suspend fun reachability(of: ConversationRecord): Reachability = withContext(actorContext) {
        when (of.type) {
            ConversationType.direct -> {
                val peerID = of.peerNodeID ?: return@withContext Reachability.OutOfRange
                reachability(of = peerID)
            }
            ConversationType.group -> {
                val members = attempt { database.conversations.groupMembers(of = of.id) } ?: emptyList()
                var best: Reachability = Reachability.OutOfRange
                for (member in members) {
                    val candidate = reachability(of = member.nodeID)
                    val current = best
                    when {
                        candidate is Reachability.Direct -> return@withContext candidate
                        candidate is Reachability.ViaBridge && current is Reachability.OutOfRange -> best = candidate
                        candidate is Reachability.ViaBridge && current is Reachability.ViaBridge &&
                            candidate.hops < current.hops -> best = candidate
                        else -> Unit
                    }
                }
                best
            }
            ConversationType.cabinLounge -> {
                val neighbors = currentNeighbors()
                if (neighbors.isEmpty()) Reachability.OutOfRange
                else Reachability.Direct(rssi = neighbors.mapNotNull { it.rssi }.maxOrNull())
            }
        }
    }

    suspend fun refreshTrustedPeers(): Unit = withContext(actorContext) {
        attempt { database.peers.trustedPeripheralIdentifiers() }?.let {
            transport.setTrustedPeripheralIdentifiers(it)
        }
    }

    suspend fun refreshBlockedPeers(): Unit = withContext(actorContext) {
        blocked = attempt { database.peers.blockedNodeIDs() } ?: emptySet()
    }

    // MARK: - Pairing e gruppi

    /** Pairing da QR: salva il compagno come fidato, crea la chat 1-a-1 e, se il QR porta un gruppo, vi entra. */
    suspend fun pair(with: PairingPayload): ConversationRecord = withContext(actorContext) {
        val peer = database.peers.upsert(PeerRecord(pairing = with, isTrusted = true))
        val direct = database.conversations.ensureDirectConversation(with = peer)
        refreshTrustedPeers()
        sendProfile(peer)
        if (with.isGroupInvite) {
            return@withContext joinGroup(from = with, inviter = peer)
        }
        direct
    }

    /** Crea un gruppo di viaggio locale (noi siamo il primo membro). */
    suspend fun createGroup(title: String): ConversationRecord = withContext(actorContext) {
        val group = database.conversations.createGroup(title = title)
        knownGroupRecipientIDs.add(group.recipientID.toHex())
        group
    }

    /** Entra nel gruppo descritto dal QR e annuncia la propria adesione all'invitante (Group Key Gossip). */
    suspend fun joinGroup(from: PairingPayload, inviter: PeerRecord): ConversationRecord = withContext(actorContext) {
        val groupID = from.groupID
        val key = from.groupSecretKey
        if (groupID == null || key == null) throw RouterError.InvalidGossip("QR senza gruppo")
        var group = database.conversations.createGroup(
            id = groupID, title = from.groupTitle ?: placeholderGroupTitle,
            secretKey = key, members = listOf(inviter),
        )
        from.groupTitle?.let { title -> group = adoptGroupTitle(title, group) }
        database.conversations.addGroupMember(inviter, to = group.id)
        knownGroupRecipientIDs.add(group.recipientID.toHex())
        emit(RouterEvent.GroupMembersChanged(conversationID = group.id))
        sendGossip(group, inviter)
        group
    }

    /** Aggiunge un compagno gia' noto al gruppo e propaga il gossip a tutti i membri. */
    suspend fun invite(peer: PeerRecord, to: ConversationRecord): Unit = withContext(actorContext) {
        database.conversations.addGroupMember(peer, to = to.id)
        emit(RouterEvent.GroupMembersChanged(conversationID = to.id))
        propagateGossip(to, excluding = null)
    }

    /** QR del gruppo da mostrare agli altri: identita' personale + chiave del gruppo (Swift `groupInvitePayload(for:)`). */
    suspend fun groupInvitePayload(group: ConversationRecord): PairingPayload? = withContext(actorContext) {
        val groupID = group.groupID ?: return@withContext null
        val key = group.groupSecretKey ?: return@withContext null
        PairingPayload(identity = identity, nickname = nickname, groupID = groupID, groupSecretKey = key, groupTitle = group.title)
    }

    /** Sostituisce il titolo provvisorio con quello vero (dal QR o dal gossip); un titolo gia' noto non cambia. */
    private suspend fun adoptGroupTitle(title: String, group: ConversationRecord): ConversationRecord {
        val trimmed = PairingPayload.trimWhitespacesAndNewlines(title)
        if (trimmed.isEmpty() || trimmed == group.title || group.title != placeholderGroupTitle) return group
        database.conversations.updateTitle(trimmed, conversationID = group.id)
        return group.copy(title = trimmed)
    }

    // MARK: - Invio

    /**
     * Accetta subito il messaggio in chat (stato `queued`) e lo immette nella mesh
     * (Swift `sendText(_:in:replyTo:)`: `in` e' una keyword Kotlin, il parametro si chiama `conversation`).
     */
    suspend fun sendText(text: String, conversation: ConversationRecord, replyTo: ByteArray? = null): MessageRecord =
        withContext(actorContext) {
            if (!isRunning) throw RouterError.NotRunning
            var message = MessageRecord.outgoingText(
                text, conversationID = conversation.id, sender = identity,
                senderNickname = nickname, replyTo = replyTo,
            )
            database.messages.insert(message)

            val payload = InnerPayloadFactory.text(text, identity = identity, nickname = nickname, replyTo = replyTo)
            val type = if (conversation.type == ConversationType.cabinLounge) PacketType.PUBLIC_LOUNGE else PacketType.CHAT_TEXT
            val envelope = makeEnvelope(type, payload, conversation, msgID = message.msgID)
            // Chat e gruppi: il messaggio resta in coda finche' non arriva il `DELIVERY_ACK`, anche se un
            // vicino e' stato raggiunto (l'app del destinatario puo' essere sospesa con il link "aperto").
            val reached = dispatch(
                envelope, storeIfUnreachable = true,
                retainUntilAck = conversation.type != ConversationType.cabinLounge,
            )
            message = message.copy(deliveryStatus = if (reached) DeliveryStatus.sentMesh else DeliveryStatus.queued)
            if (reached) {
                // Solo in avanti: l'ACK puo' essere gia' arrivato mentre `dispatch` era in corso.
                database.messages.advanceDeliveryStatus(to = DeliveryStatus.sentMesh, msgID = message.msgID)
            }
            message
        }

    /** Invio generico (media, giochi, Pro Pass). Ritorna il `msg_id` e se almeno un vicino e' stato raggiunto. */
    suspend fun sendPacket(
        type: PacketType,
        payload: InnerPayload,
        conversation: ConversationRecord,
        msgID: ByteArray = CryptoEngine.randomMessageID(),
        ttl: Int = MeshProtocol.defaultTTL,
        storeIfUnreachable: Boolean = true,
        retainUntilAck: Boolean = false,
    ): SendResult = withContext(actorContext) {
        if (!isRunning) throw RouterError.NotRunning
        val envelope = makeEnvelope(type, payload, conversation, msgID = msgID, ttl = ttl)
        // Mosse di gioco: come i testi, restano in coda finche' il destinatario non conferma (una mossa
        // persa bloccava la partita per sempre).
        val reached = dispatch(envelope, storeIfUnreachable = storeIfUnreachable, retainUntilAck = retainUntilAck)
        SendResult(msgID, reached)
    }

    /** Chiamate: solo sul link diretto, ttl 1, mai flooding ne' coda. Ritorna `false` se il vicino non c'e'. */
    suspend fun sendCallPacket(type: PacketType, payload: InnerPayload, to: PeerRecord): Boolean = withContext(actorContext) {
        if (!isRunning) return@withContext false
        val key = attempt { callKey(to) } ?: return@withContext false
        val envelope = attempt {
            EnvelopeBuilder(identity).build(packetType = type, recipientID = to.nodeID, payload = payload, key = key, ttl = 1)
        } ?: return@withContext false
        val bytes = MeshWire.serialize(envelope)
        if (type == PacketType.CALL_SIGNAL) seen.insertIfNew(envelope.msgId.toByteArray())
        attempt { transport.send(bytes, toNode = to.nodeID) } ?: false
    }

    private fun callKey(peer: PeerRecord): SymmetricKey {
        val cacheKey = peer.nodeID.toHex()
        callPeerCache[cacheKey]?.let { cached ->
            if (cached.peer.x25519Pub.contentEquals(peer.x25519Pub)) return cached.key
        }
        val key = identity.directChatKey(peerX25519PublicKeyRaw = peer.x25519Pub)
        if (callPeerCache.size > 32) callPeerCache.clear()
        callPeerCache[cacheKey] = CallPeer(peer, key)
        return key
    }

    /**
     * Fast path dell'audio: niente cache anti-loop ne' DB (la sequenza della chiamata scarta i replay),
     * solo pacchetti diretti (hop 0) indirizzati a noi.
     */
    private suspend fun receiveCallAudio(envelope: MeshEnvelope) {
        val senderID = envelope.senderNodeId.toByteArray()
        val senderHex = senderID.toHex()
        if (!envelope.recipientId.toByteArray().contentEquals(myNodeID) || envelope.hopCount != 0 ||
            blocked.contains(senderHex) || callAudioSubscribers.isEmpty()
        ) return
        val peer: PeerRecord
        val key: SymmetricKey
        val cached = callPeerCache[senderHex]
        if (cached != null) {
            peer = cached.peer
            key = cached.key
        } else {
            val fetched = attempt { database.peers.fetch(nodeID = senderID) } ?: return
            val derived = attempt { callKey(fetched) } ?: return
            peer = fetched
            key = derived
        }
        val audio: CallAudio? = attempt {
            val payload = EnvelopeVerifier.open(envelope, key, expectedSenderEd25519PublicKeyRaw = peer.ed25519Pub)
            MeshWire.parseCallAudio(payload.extraData.toByteArray())
        }
        if (audio == null) {
            updateStats { it.copy(undecryptable = it.undecryptable + 1) }
            return
        }
        val packet = CallAudioPacket(senderNodeID = peer.nodeID, audio = audio)
        for (channel in callAudioSubscribers.values) channel.trySend(packet)
    }

    /** Invio diretto a un peer noto, senza passare da una conversazione (gossip, ack, pro pass). */
    suspend fun sendDirectPacket(
        type: PacketType,
        payload: InnerPayload,
        to: PeerRecord,
        ttl: Int = MeshProtocol.defaultTTL,
        storeIfUnreachable: Boolean = true,
    ): Boolean = withContext(actorContext) {
        val key = identity.directChatKey(peerX25519PublicKeyRaw = to.x25519Pub)
        val envelope = EnvelopeBuilder(identity).build(packetType = type, recipientID = to.nodeID, payload = payload, key = key, ttl = ttl)
        dispatch(envelope, storeIfUnreachable = storeIfUnreachable)
    }

    private suspend fun makeEnvelope(
        type: PacketType,
        payload: InnerPayload,
        conversation: ConversationRecord,
        msgID: ByteArray,
        ttl: Int = MeshProtocol.defaultTTL,
    ): MeshEnvelope {
        val key = conversationKey(conversation)
        return EnvelopeBuilder(identity).build(
            packetType = type,
            recipientID = conversation.recipientID,
            payload = payload,
            key = key,
            ttl = ttl,
            msgID = msgID,
        )
    }

    private suspend fun conversationKey(conversation: ConversationRecord): SymmetricKey = when (conversation.type) {
        ConversationType.direct -> {
            val peerID = conversation.peerNodeID ?: throw RouterError.InvalidConversation
            val peer = database.peers.fetch(nodeID = peerID) ?: throw RouterError.InvalidConversation
            identity.directChatKey(peerX25519PublicKeyRaw = peer.x25519Pub)
        }
        ConversationType.group -> {
            val secret = conversation.groupSecretKey ?: throw RouterError.MissingGroupKey
            CryptoEngine.groupKey(fromSecret = secret)
        }
        ConversationType.cabinLounge -> CryptoEngine.publicCabinKey
    }

    /**
     * Immette l'envelope nella mesh: link diretto se esiste, altrimenti flooding; se nessun vicino,
     * Store-and-Forward per i pacchetti idonei. Ritorna `true` se almeno un vicino e' stato raggiunto.
     * Con `retainUntilAck` il pacchetto viene accodato comunque e rimosso solo dal `DELIVERY_ACK`.
     */
    private suspend fun dispatch(
        envelope: MeshEnvelope,
        storeIfUnreachable: Boolean,
        retainUntilAck: Boolean = false,
        excluding: Set<LinkID> = emptySet(),
    ): Boolean {
        val msgID = envelope.msgId.toByteArray()
        val recipientID = envelope.recipientId.toByteArray()
        seen.insertIfNew(msgID)
        val bytes = attempt { MeshWire.serialize(envelope) } ?: return false

        val eligible = envelope.packetType.isStoreAndForwardEligible
        if (retainUntilAck && eligible) {
            // Accodato prima dell'invio: un ACK rapidissimo deve trovare il record da rimuovere.
            val record = RelayQueueRecord(msgID = msgID, recipientID = recipientID, rawEnvelopeBytes = bytes)
            if (attempt { database.relayQueue.enqueue(record) } != null) {
                emitRelayQueueCount()
            }
        }

        var reached: Int
        val recipientHex = recipientID.toHex()
        val isMulticast = recipientHex == loungeRecipientHex || isGroupRecipient(recipientHex)
        reached = if (!isMulticast && attempt { transport.send(bytes, toNode = recipientID) } == true) {
            1
        } else {
            transport.broadcast(bytes, excluding = excluding)
        }

        if (reached == 0 && retainUntilAck && eligible) {
            updateStats { it.copy(queued = it.queued + 1) }
        } else if (reached == 0 && storeIfUnreachable && eligible) {
            val record = RelayQueueRecord(msgID = msgID, recipientID = recipientID, rawEnvelopeBytes = bytes)
            if (attempt { database.relayQueue.enqueue(record) } != null) {
                updateStats { it.copy(queued = it.queued + 1) }
                emitRelayQueueCount()
            }
        }
        return reached > 0
    }

    private fun isGroupRecipient(recipientHex: String): Boolean = knownGroupRecipientIDs.contains(recipientHex)

    // MARK: - Eventi trasporto

    private suspend fun handle(event: TransportEvent) {
        when (event) {
            is TransportEvent.RadioStateChanged -> {
                radioState = event.state
                emit(RouterEvent.RadioStateChanged(event.state))
            }
            is TransportEvent.AdvertisingChanged -> Unit
            is TransportEvent.PeerDiscovered -> {
                for ((id, link) in links.entries.toList()) {
                    if (id.deviceID == event.deviceID) links[id] = link.copy(rssi = event.rssi)
                }
            }
            is TransportEvent.LinkOpened -> {
                val link = event.link
                links[link.id] = link
                link.nodeID?.let { peerCameOnline(it, link) }
                emit(RouterEvent.NeighborsChanged(currentNeighbors()))
            }
            is TransportEvent.LinkIdentified -> {
                val existing = links[event.id] ?: return
                val link = existing.copy(nodeID = event.nodeID)
                links[event.id] = link
                peerCameOnline(event.nodeID, link)
                emit(RouterEvent.NeighborsChanged(currentNeighbors()))
            }
            is TransportEvent.LinkClosed -> {
                val closed = links.remove(event.id)
                // Alla prossima riconnessione il profilo viene ripetuto (il precedente puo' essere andato perso).
                val nodeID = closed?.nodeID
                if (nodeID != null && links.values.none { it.nodeID?.contentEquals(nodeID) == true }) {
                    profileSentTo.remove(nodeID.toHex())
                }
                emit(RouterEvent.NeighborsChanged(currentNeighbors()))
            }
            is TransportEvent.FrameReceived -> receive(event.frame, from = event.from)
        }
    }

    private suspend fun peerCameOnline(nodeID: ByteArray, link: PeerLink) {
        markSeenThrottled(nodeID, hopCount = 1, rssi = link.rssi)
        if (link.id.role == LinkRole.central) {
            attempt { database.peers.setBLEIdentifier(link.id.deviceID, nodeID = nodeID) }
        }
        // Secondo link (dual-role) verso un vicino gia' online: coda e profilo sono gia' partiti.
        if (links.values.any { it.nodeID?.contentEquals(nodeID) == true && it.id != link.id }) return
        flushRelayQueue(toward = nodeID)
        attempt { database.peers.fetch(nodeID = nodeID) }?.let { sendProfile(it) }
    }

    /**
     * Svuota la coda Store-and-Forward verso il nuovo vicino (diretto se destinatario, altrimenti flooding).
     * Le richieste arrivate durante un flush (raffica di link aperti, manutenzione, foreground) vengono
     * unite nel giro successivo invece di ritrasmettere tutta la coda piu' volte in parallelo.
     */
    suspend fun flushRelayQueue(toward: ByteArray? = null): Unit = withContext(actorContext) {
        pendingFlushTargets.add(toward)
        if (isFlushingRelayQueue) return@withContext
        isFlushingRelayQueue = true
        try {
            while (pendingFlushTargets.isNotEmpty()) {
                val targets = pendingFlushTargets.filterNotNull().mapTo(HashSet()) { it.toHex() }
                pendingFlushTargets.clear()
                performFlush(targets)
            }
        } finally {
            isFlushingRelayQueue = false
        }
    }

    /** @param targets `node_id` (hex) appena raggiungibili direttamente. */
    private suspend fun performFlush(targets: Set<String>) {
        val pending = attempt { database.relayQueue.pending() }
        if (pending.isNullOrEmpty()) return
        val sentIDs = ArrayList<ByteArray>()
        for (record in pending) {
            var sent = false
            if (targets.contains(record.recipientID.toHex())) {
                sent = attempt { transport.send(record.rawEnvelopeBytes, toNode = record.recipientID) } ?: false
            }
            if (!sent) {
                sent = transport.broadcast(record.rawEnvelopeBytes, excluding = emptySet()) > 0
            }
            if (sent) sentIDs.add(record.msgID)
        }
        if (sentIDs.isEmpty()) return
        attempt { database.messages.markSentMesh(msgIDs = sentIDs) }
        // I nostri messaggi restano in coda finche' il destinatario non conferma con l'ACK.
        val awaiting = attempt { database.messages.awaitingAckIDs(among = sentIDs) }
            ?: sentIDs.mapTo(HashSet()) { it.toHex() }
        val flushed = sentIDs.filter { !awaiting.contains(it.toHex()) }
        if (flushed.isNotEmpty()) {
            attempt { database.relayQueue.remove(msgIDs = flushed) }
            emitRelayQueueCount()
        }
    }

    /**
     * Ritrasmissione periodica dei nostri messaggi senza ACK: solo verso destinatari che sono vicini
     * diretti (o gruppi), cosi' la coda non viene ri-inondata a tutta la cabina ogni 30 secondi.
     * I destinatari lontani vengono ritentati all'apertura di ogni nuovo link (`flushRelayQueue`).
     */
    suspend fun retryUnacknowledged(): Unit = withContext(actorContext) {
        if (links.isEmpty()) return@withContext
        val pending = attempt { database.relayQueue.pending() }
        if (pending.isNullOrEmpty()) return@withContext
        val neighborIDs = currentNeighbors().mapTo(HashSet()) { it.nodeID.toHex() }
        val candidates = pending.filter {
            val hex = it.recipientID.toHex()
            neighborIDs.contains(hex) || isGroupRecipient(hex)
        }
        if (candidates.isEmpty()) return@withContext
        val awaiting = attempt { database.messages.awaitingAckIDs(among = candidates.map { it.msgID }) } ?: return@withContext
        for (record in candidates) {
            if (!awaiting.contains(record.msgID.toHex())) continue
            if (isGroupRecipient(record.recipientID.toHex())) {
                transport.broadcast(record.rawEnvelopeBytes, excluding = emptySet())
            } else {
                attempt { transport.send(record.rawEnvelopeBytes, toNode = record.recipientID) }
            }
        }
    }

    // MARK: - Ricezione

    /** Entry point di ogni frame ricevuto dalla radio (pubblico per i test). */
    suspend fun receive(data: ByteArray, from: LinkID): Unit = withContext(actorContext) {
        val envelope = attempt { MeshWire.parseEnvelope(data).also { EnvelopeVerifier.validateShape(it) } }
        if (envelope == null) {
            updateStats { it.copy(malformed = it.malformed + 1) }
            return@withContext
        }
        // 1. Anti-replay: finestra temporale (ne' troppo vecchio, ne' nel futuro).
        val ageMs = try {
            Math.subtractExact(nowUnixMilliseconds, envelope.createdAtMs)
        } catch (_: ArithmeticException) {
            null
        }
        if (ageMs == null || ageMs > configuration.replayWindow || ageMs < -configuration.futureSkewTolerance) {
            updateStats { it.copy(replayRejected = it.replayRejected + 1) }
            return@withContext
        }
        // Audio delle chiamate: fast path senza deduplicazione persistente (17 pacchetti/s).
        if (envelope.packetType == PacketType.CALL_AUDIO) {
            receiveCallAudio(envelope)
            return@withContext
        }
        val msgID = envelope.msgId.toByteArray()
        // 2. Managed flooding anti-loop: scarto in memoria, prima di qualsiasi I/O.
        if (!seen.insertIfNew(msgID)) {
            updateStats { it.copy(duplicates = it.duplicates + 1) }
            // Un vicino ha gia' ritrasmesso questo pacchetto: il nostro relay in attesa e' superfluo.
            cancelPendingRelay(msgID)
            // Il mittente ritrasmette finche' non riceve l'ACK: se il nostro e' andato perso, lo rimandiamo.
            reacknowledgeDuplicate(envelope)
            return@withContext
        }
        // La cache in memoria puo' aver dimenticato l'id (riavvio, eviction): il DB e' la verita'.
        if (attempt { database.seenPackets.markSeenIfNew(msgID = msgID) } == false) {
            updateStats { it.copy(duplicates = it.duplicates + 1) }
            return@withContext
        }
        updateStats { it.copy(received = it.received + 1) }
        val hops = envelope.hopCount + 1

        // 3. Routing.
        when (val target = resolveTarget(envelope.recipientId.toByteArray())) {
            Target.Me -> deliver(envelope, hops, from, target)
            is Target.Group, is Target.Lounge -> {
                deliver(envelope, hops, from, target)
                relayIfAllowed(envelope, from, target)
            }
            Target.Other -> relayIfAllowed(envelope, from, target)
        }
    }

    private suspend fun resolveTarget(recipientID: ByteArray): Target {
        if (recipientID.contentEquals(myNodeID)) return Target.Me
        val recipientHex = recipientID.toHex()
        if (recipientHex == loungeRecipientHex) {
            attempt { database.conversations.currentCabinLounge() }?.let { return Target.Lounge(it) }
            return Target.Other
        }
        val conversation = attempt { database.conversations.fetch(recipientID = recipientID) }
        if (conversation != null && conversation.type == ConversationType.group) {
            knownGroupRecipientIDs.add(recipientHex)
            return Target.Group(conversation)
        }
        return Target.Other
    }

    /**
     * Relay con TTL/hop e filtro media:
     * - mai oltre 16 KB di payload;
     * - `MEDIA_*` solo come primo e unico ponte (`hop_count == 0`) e solo se siamo membri del gruppo destinatario.
     */
    private fun relayIfAllowed(envelope: MeshEnvelope, from: LinkID, target: Target) {
        if (!configuration.relayEnabled || envelope.ttl <= 1) return
        if (envelope.encryptedPayload.size() > MeshProtocol.maxRelayPayloadBytes) return
        if (envelope.packetType.isMedia) {
            if (envelope.hopCount != 0 || target !is Target.Group) return
        }
        val forwarded = envelope.toBuilder()
            .setTtl(envelope.ttl - 1)
            .setHopCount(envelope.hopCount + 1)
            .build()
        val bytes = attempt { MeshWire.serialize(forwarded) } ?: return

        // Backoff casuale: in una cabina piena molti vicini ricevono lo stesso pacchetto nello stesso
        // istante; chi estrae il ritardo piu' corto ritrasmette, gli altri sentono il duplicato e rinunciano.
        val msgID = forwarded.msgId.toByteArray()
        val key = msgID.toHex()
        val recipientID = forwarded.recipientId.toByteArray()
        val type = forwarded.packetType
        val range = configuration.relayBackoff
        val delayMs = if (range.last <= range.first) range.first else Random.nextLong(range.first, range.last + 1)
        pendingRelays[key]?.cancel()
        pendingRelays[key] = scope.launch {
            if (delayMs > 0) delay(delayMs)
            ensureActive()
            performRelay(bytes, msgID, recipientID, type, excluding = from)
        }
    }

    private suspend fun performRelay(bytes: ByteArray, msgID: ByteArray, recipientID: ByteArray, type: PacketType, excluding: LinkID) {
        if (pendingRelays.remove(msgID.toHex()) == null) return
        val reached = transport.broadcast(bytes, excluding = setOf(excluding))
        if (reached > 0) {
            updateStats { it.copy(relayed = it.relayed + 1) }
        } else if (type.isStoreAndForwardEligible) {
            val record = RelayQueueRecord(msgID = msgID, recipientID = recipientID, rawEnvelopeBytes = bytes)
            if (attempt { database.relayQueue.enqueue(record) } != null) {
                updateStats { it.copy(queued = it.queued + 1) }
                emitRelayQueueCount()
            }
        }
    }

    private fun cancelPendingRelay(msgID: ByteArray) {
        val task = pendingRelays.remove(msgID.toHex()) ?: return
        task.cancel()
        updateStats { it.copy(relaySuppressed = it.relaySuppressed + 1) }
    }

    private suspend fun deliver(envelope: MeshEnvelope, hops: Int, via: LinkID, target: Target) {
        if (blocked.contains(envelope.senderNodeId.toByteArray().toHex())) return
        try {
            val type = envelope.packetType
            when {
                type == PacketType.CHAT_TEXT && target is Target.Me -> deliverDirectText(envelope, hops, via)
                type == PacketType.CHAT_TEXT && target is Target.Group -> deliverGroupText(envelope, target.conversation, hops, via)
                type == PacketType.PUBLIC_LOUNGE && target is Target.Lounge -> deliverLoungeText(envelope, target.conversation, hops)
                type == PacketType.DELIVERY_ACK && target is Target.Me -> handleAck(envelope)
                type == PacketType.GROUP_KEY_GOSSIP && target is Target.Me -> handleGossip(envelope, hops)
                type == PacketType.READ_RECEIPT && target is Target.Me -> handleReadReceipt(envelope)
                type == PacketType.PROFILE_UPDATE && target is Target.Me -> handleProfileUpdate(envelope)
                type == PacketType.CALL_SIGNAL && target is Target.Me -> {
                    // Solo dal vicino diretto: con un ponte di mezzo la chiamata non reggerebbe.
                    if (envelope.hopCount != 0) return
                    forwardToSubsystems(envelope, hops, target)
                }
                type == PacketType.MEDIA_HEADER || type == PacketType.MEDIA_CHUNK ||
                    type == PacketType.PRO_PASS_GRANT || type == PacketType.GAME_MOVE ->
                    forwardToSubsystems(envelope, hops, target)
                else -> return
            }
            updateStats { it.copy(delivered = it.delivered + 1) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateStats { it.copy(undecryptable = it.undecryptable + 1) }
        }
    }

    // MARK: Decifratura

    private suspend fun openDirect(envelope: MeshEnvelope): Pair<InnerPayload, PeerRecord> {
        val senderID = envelope.senderNodeId.toByteArray()
        val peer = database.peers.fetch(nodeID = senderID) ?: throw RouterError.UnknownPeer(senderID)
        val key = identity.directChatKey(peerX25519PublicKeyRaw = peer.x25519Pub)
        val payload = EnvelopeVerifier.open(envelope, key, expectedSenderEd25519PublicKeyRaw = peer.ed25519Pub)
        return payload to peer
    }

    /**
     * Apre un pacchetto cifrato con chiave condivisa (gruppo/Lounge) e autentica il mittente tramite
     * le chiavi dichiarate nel payload, che devono corrispondere al `sender_node_id` dell'envelope.
     */
    private suspend fun openShared(envelope: MeshEnvelope, key: SymmetricKey): Pair<InnerPayload, PeerRecord> {
        val payload = EnvelopeVerifier.open(envelope, key)
        val senderID = envelope.senderNodeId.toByteArray()
        if (payload.declaredSenderNodeID?.contentEquals(senderID) != true) throw RouterError.SenderMismatch
        val known = database.peers.fetch(nodeID = senderID)
        if (known != null) {
            if (!known.ed25519Pub.contentEquals(payload.senderEd25519Pub.toByteArray())) throw RouterError.SenderMismatch
            return payload to known
        }
        val nickname = payload.senderNickname.ifEmpty { defaultPassengerNickname }
        val peer = PeerRecord(
            nodeID = senderID,
            x25519Pub = payload.senderX25519Pub.toByteArray(),
            ed25519Pub = payload.senderEd25519Pub.toByteArray(),
            nickname = nickname,
        )
        return payload to peer
    }

    // MARK: Consegna

    private suspend fun deliverDirectText(envelope: MeshEnvelope, hops: Int, via: LinkID) {
        val (payload, peer) = openDirect(envelope)
        val conversation = database.conversations.ensureDirectConversation(with = peer)
        storeIncomingText(payload, envelope, conversation, peer, hops, via, notify = true)
        sendAck(envelope, peer)
    }

    private suspend fun deliverGroupText(envelope: MeshEnvelope, group: ConversationRecord, hops: Int, via: LinkID) {
        val secret = group.groupSecretKey ?: throw RouterError.MissingGroupKey
        val (payload, sender) = openShared(envelope, CryptoEngine.groupKey(fromSecret = secret))
        val peer = database.peers.upsert(sender)
        database.conversations.addGroupMember(peer, to = group.id)
        storeIncomingText(payload, envelope, group, peer, hops, via, notify = true)
        sendAck(envelope, peer)
    }

    private suspend fun deliverLoungeText(envelope: MeshEnvelope, lounge: ConversationRecord, hops: Int) {
        val (payload, sender) = openShared(envelope, CryptoEngine.publicCabinKey)
        // Cabina Pubblica: solo testo, niente link.
        if (containsLink(payload.text) || payload.text.isEmpty()) throw RouterError.LinkNotAllowedInLounge
        val message = MessageRecord(
            msgID = envelope.msgId.toByteArray(),
            conversationID = lounge.id,
            senderNodeID = sender.nodeID,
            senderNickname = sender.nickname,
            contentType = MessageContentType.text,
            text = payload.text,
            isOutgoing = false,
            deliveryStatus = DeliveryStatus.deliveredAck,
            hopCountReceived = hops,
            createdAtMs = envelope.createdAtMs,
        )
        if (database.messages.insert(message)) {
            emit(RouterEvent.MessageReceived(conversationID = lounge.id, msgID = message.msgID))
        }
    }

    private suspend fun storeIncomingText(
        payload: InnerPayload,
        envelope: MeshEnvelope,
        conversation: ConversationRecord,
        sender: PeerRecord,
        hops: Int,
        via: LinkID,
        notify: Boolean,
    ) {
        val nickname = payload.senderNickname.ifEmpty { sender.nickname }
        val message = MessageRecord(
            msgID = envelope.msgId.toByteArray(),
            conversationID = conversation.id,
            senderNodeID = sender.nodeID,
            senderNickname = nickname,
            contentType = MessageContentType.text,
            text = payload.text,
            replyToMsgID = payload.replyToMessageID,
            isOutgoing = false,
            deliveryStatus = DeliveryStatus.deliveredAck,
            hopCountReceived = hops,
            createdAtMs = envelope.createdAtMs,
        )
        markSeenThrottled(sender.nodeID, hopCount = hops, rssi = links[via]?.rssi)
        if (nickname != sender.nickname) {
            attempt { database.peers.upsert(sender.copy(nickname = nickname), authoritativeNickname = true) }
        }
        if (database.messages.insert(message)) {
            emit(RouterEvent.MessageReceived(conversationID = conversation.id, msgID = message.msgID))
            if (notify) {
                // Un errore della notifica non deve impedire l'ACK (il mittente ritrasmetterebbe all'infinito).
                attempt { notifier.notify(message = message, conversation = conversation) }
            }
        }
    }

    private suspend fun sendAck(envelope: MeshEnvelope, peer: PeerRecord) {
        val msgID = envelope.msgId.toByteArray()
        lastAckSentAt[msgID.toHex()] = nowUnixMilliseconds
        val payload = InnerPayloadFactory.deliveryAck(msgID, identity = identity, nickname = nickname)
        sendDirectPacket(PacketType.DELIVERY_ACK, payload, to = peer, ttl = MeshProtocol.ackTTL)
    }

    /**
     * Rimanda l'ACK per un testo gia' consegnato e salvato (mai per pacchetti non verificati),
     * al massimo una volta ogni `reackThrottle` per non rispondere a ogni copia del flooding.
     */
    private suspend fun reacknowledgeDuplicate(envelope: MeshEnvelope) {
        val recipientID = envelope.recipientId.toByteArray()
        val type = envelope.packetType
        if ((type != PacketType.CHAT_TEXT && type != PacketType.GAME_MOVE) ||
            !(recipientID.contentEquals(myNodeID) || isGroupRecipient(recipientID.toHex()))
        ) return
        val msgID = envelope.msgId.toByteArray()
        val key = msgID.toHex()
        val now = nowUnixMilliseconds
        val last = lastAckSentAt[key]
        if (last != null && now - last < configuration.reackThrottle) return
        // Anche un esito negativo viene ricordato: le copie successive non rileggono il DB.
        lastAckSentAt[key] = now
        val senderID = envelope.senderNodeId.toByteArray()
        val peer = attempt { database.peers.fetch(nodeID = senderID) } ?: return
        if (type == PacketType.CHAT_TEXT) {
            // Basta il messaggio salvato: esiste solo se era per noi (diretto o gruppo) e gia' verificato.
            val stored = attempt { database.messages.fetch(msgID = msgID) } ?: return
            if (stored.isOutgoing || !stored.senderNodeID.contentEquals(senderID)) return
        }
        // Mosse di gioco: non hanno un messaggio proprio (lo stato vive nella bolla dell'invito); il
        // duplicato di un msg_id gia' visto da un peer noto viene riconfermato cosi' smette di ritrasmettere.
        attempt { sendAck(envelope, peer) }
    }

    private suspend fun handleAck(envelope: MeshEnvelope) {
        val (payload, _) = openDirect(envelope)
        val msgID = payload.replyToMessageID ?: return
        database.messages.advanceDeliveryStatus(to = DeliveryStatus.deliveredAck, msgID = msgID)
        attempt { database.relayQueue.remove(msgID = msgID) }
    }

    // MARK: - Conferme di lettura

    /** L'utente ha aperto la chat: segna letti i messaggi ricevuti e invia un `READ_RECEIPT` a ogni mittente. */
    suspend fun markConversationRead(conversation: ConversationRecord): Unit = withContext(actorContext) {
        val unread = attempt { database.messages.markIncomingRead(conversationID = conversation.id) } ?: return@withContext
        if (unread.isEmpty() || conversation.type == ConversationType.cabinLounge || !isRunning) return@withContext
        // Le conferme partono in un task separato: le scritture BLE possono richiedere secondi e il
        // chiamante (apertura della chat) non deve aspettarle, ne' l'actor restare occupato.
        scope.launch { sendReadReceipts(unread) }
    }

    private suspend fun sendReadReceipts(unread: List<MessageRecord>) {
        val bySender = unread.groupBy { it.senderNodeID.toHex() }
        for ((senderHex, messages) in bySender) {
            if (senderHex == myNodeHex) continue
            val peer = attempt { database.peers.fetch(nodeID = messages.first().senderNodeID) } ?: continue
            val ids = messages.map { it.msgID }
            for (batch in ids.chunked(readReceiptBatch)) {
                val joined = ByteArray(batch.size * MeshProtocol.msgIDLength)
                batch.forEachIndexed { index, id -> id.copyInto(joined, index * MeshProtocol.msgIDLength) }
                val payload = InnerPayloadFactory.extra(joined, identity = identity, nickname = nickname)
                attempt { sendDirectPacket(PacketType.READ_RECEIPT, payload, to = peer, ttl = MeshProtocol.ackTTL) }
            }
        }
    }

    private suspend fun handleReadReceipt(envelope: MeshEnvelope) {
        val (payload, peer) = openDirect(envelope)
        val data = payload.extraData.toByteArray()
        val length = MeshProtocol.msgIDLength
        if (data.isEmpty() || data.size % length != 0 || data.size > length * readReceiptBatch) return
        val ids = (0 until data.size step length).map { offset -> data.copyOfRange(offset, offset + length) }
        // Nei gruppi il lettore viene registrato e la spunta blu arriva solo quando hanno letto tutti
        // i membri; nel frattempo il messaggio avanza almeno a consegnato.
        val updated = database.messages.markReadByRecipient(msgIDs = ids, readerNodeID = peer.nodeID)
        // Letto implica consegnato: se l'ACK si e' perso, il messaggio non va piu' ritrasmesso.
        if (updated.isNotEmpty() && attempt { database.relayQueue.remove(msgIDs = updated) } != null) {
            emitRelayQueueCount()
        }
    }

    // MARK: - Profilo

    private suspend fun sendProfile(peer: PeerRecord) {
        val profile = profile ?: return
        if (!isRunning) return
        val peerHex = peer.nodeID.toHex()
        if (peerHex == myNodeHex || blocked.contains(peerHex) || !profileSentTo.add(peerHex)) return
        val update = ProfileUpdate.newBuilder()
            .setNickname(profile.nickname)
            .setAvatarColorHex(profile.avatarColorHex)
            .setAvatarWebp((profile.avatarImage ?: ByteArray(0)).toByteString())
            .setUpdatedAtMs(profile.updatedAtMs)
            .build()
        val data = attempt { MeshWire.serialize(update) } ?: return
        val payload = InnerPayloadFactory.extra(data, identity = identity, nickname = nickname)
        attempt { sendDirectPacket(PacketType.PROFILE_UPDATE, payload, to = peer, storeIfUnreachable = false) }
    }

    private suspend fun handleProfileUpdate(envelope: MeshEnvelope) {
        val (payload, peer) = openDirect(envelope)
        val update = MeshWire.parseProfileUpdate(payload.extraData.toByteArray())
        if (update.updatedAtMs <= 0) return
        val nickname = PairingPayload.prefixCharacters(
            PairingPayload.trimWhitespacesAndNewlines(update.nickname), MeshProtocol.maxNicknameLength,
        )
        val color = if (isValidColorHex(update.avatarColorHex)) update.avatarColorHex.uppercase() else null
        val avatar = update.avatarWebp.toByteArray()
        // Anche le dimensioni dichiarate: 12 KB di WebP possono descrivere 16383x16383 px, e l'avatar viene
        // decodificato in ogni riga della lista chat (OOM / "Canvas: trying to draw too large bitmap" a ogni avvio).
        val validAvatar = if (avatar.isNotEmpty() && avatar.size <= MeshProtocol.maxAvatarBytes && WebPEncoder.isWebP(avatar) &&
            !ImageProcessor.exceedsDimensions(avatar, maxAvatarSide)
        ) avatar else null
        database.peers.applyProfile(
            nodeID = peer.nodeID, nickname = nickname, avatarColorHex = color,
            avatarImage = validAvatar, updatedAtMs = update.updatedAtMs,
        )
    }

    private suspend fun forwardToSubsystems(envelope: MeshEnvelope, hops: Int, target: Target) {
        val packet: InboundPacket = when (target) {
            Target.Me -> {
                val (payload, peer) = openDirect(envelope)
                val conversation = database.conversations.ensureDirectConversation(with = peer)
                InboundPacket(envelope, payload, conversation, peer, hopCount = hops)
            }
            is Target.Group -> {
                val group = target.conversation
                val secret = group.groupSecretKey ?: throw RouterError.MissingGroupKey
                val (payload, sender) = openShared(envelope, CryptoEngine.groupKey(fromSecret = secret))
                val peer = database.peers.upsert(sender)
                InboundPacket(envelope, payload, group, peer, hopCount = hops)
            }
            is Target.Lounge, Target.Other -> return
        }
        markSeenThrottled(packet.sender.nodeID, hopCount = hops, rssi = null)
        val type = packet.envelope.packetType
        for ((id, channel) in inboundSubscribers) {
            // I chunk media non devono intasare (e far perdere, DROP_OLDEST) la coda di chi vuole solo le mosse.
            val filter = inboundFilters[id]
            if (filter == null || type in filter) channel.trySend(packet)
        }
        // Le mosse di gioco vengono confermate come i testi: il mittente le ritrasmette finche' non arriva l'ACK.
        if (type == PacketType.GAME_MOVE) attempt { sendAck(envelope, packet.sender) }
    }

    // MARK: - Group Key Gossip

    private suspend fun handleGossip(envelope: MeshEnvelope, hops: Int) {
        val (payload, sender) = openDirect(envelope)
        val gossip = MeshWire.parseGroupKeyGossip(payload.extraData.toByteArray())
        val groupID = uuidFromRawBytes(gossip.groupUuid.toByteArray()) ?: throw RouterError.InvalidGossip("group_uuid")
        val secretKey = gossip.groupSecretKey.toByteArray()
        if (secretKey.size != MeshProtocol.symmetricKeyLength) throw RouterError.InvalidGossip("group_secret_key")
        if (gossip.membersCount > MeshProtocol.maxGossipMembers) throw RouterError.InvalidGossip("members")

        val title = gossip.groupTitle.ifEmpty { placeholderGroupTitle }
        var group = database.conversations.createGroup(id = groupID, title = title, secretKey = secretKey)
        if (group.groupSecretKey?.contentEquals(secretKey) != true) throw RouterError.InvalidGossip("chiave diversa da quella nota")
        group = adoptGroupTitle(gossip.groupTitle, group)
        knownGroupRecipientIDs.add(group.recipientID.toHex())

        val known = database.conversations.groupMembers(of = group.id).mapTo(HashSet()) { it.nodeID.toHex() }
        var learnedSomething = false
        val senderHex = sender.nodeID.toHex()

        if (!known.contains(senderHex)) {
            database.conversations.addGroupMember(sender, to = group.id)
            learnedSomething = true
        }
        for (member in gossip.membersList) {
            if (!member.isConsistent || member.nodeId.toByteArray().contentEquals(myNodeID)) continue
            val peer = database.peers.upsert(member.asPeerRecord)
            if (!known.contains(peer.nodeID.toHex())) {
                database.conversations.addGroupMember(peer, to = group.id)
                learnedSomething = true
                sendProfile(peer)
            }
        }
        markSeenThrottled(sender.nodeID, hopCount = hops, rssi = null)

        // Se il mittente conosce meno membri di noi (es. ha appena scansionato il QR), gli rispondiamo
        // con la lista completa: cosi' A e C si riconoscono avendo scansionato solo B.
        val theirView = gossip.membersList.mapTo(HashSet()) { it.nodeId.toByteArray().toHex() }.apply { add(senderHex) }
        val ourView = database.conversations.groupMembers(of = group.id).mapTo(HashSet()) { it.nodeID.toHex() }.apply { add(myNodeHex) }
        if (!theirView.containsAll(ourView)) {
            sendGossip(group, sender)
        }

        if (learnedSomething) {
            emit(RouterEvent.GroupMembersChanged(conversationID = group.id))
            // Convergenza: ri-propaghiamo agli altri solo quando abbiamo imparato membri nuovi.
            propagateGossip(group, excluding = sender.nodeID)
        }
    }

    private suspend fun gossipPayload(group: ConversationRecord): InnerPayload {
        val groupID = group.groupID ?: throw RouterError.MissingGroupKey
        val key = group.groupSecretKey ?: throw RouterError.MissingGroupKey
        val builder = GroupKeyGossip.newBuilder()
            .setGroupUuid(groupID.rawBytes().toByteString())
            .setGroupTitle(group.title)
            .setGroupSecretKey(key.toByteString())
            .setIssuedAtMs(nowUnixMilliseconds)
            .addMembers(GroupMember(identity = identity, nickname = nickname))
        for (member in database.conversations.groupMembers(of = group.id)) {
            builder.addMembers(
                com.example.aerochat.proto.GroupMember.newBuilder()
                    .setNodeId(member.nodeID.toByteString())
                    .setX25519Pub(member.x25519Pub.toByteString())
                    .setEd25519Pub(member.ed25519Pub.toByteString())
                    .setNickname(member.nickname)
                    .build(),
            )
        }
        return InnerPayloadFactory.extra(MeshWire.serialize(builder.build()), identity = identity, nickname = nickname)
    }

    private suspend fun sendGossip(group: ConversationRecord, peer: PeerRecord) {
        val payload = gossipPayload(group)
        sendDirectPacket(PacketType.GROUP_KEY_GOSSIP, payload, to = peer)
    }

    private suspend fun propagateGossip(group: ConversationRecord, excluding: ByteArray?) {
        val payload = gossipPayload(group)
        for (member in database.conversations.groupMembers(of = group.id)) {
            if (member.nodeID.contentEquals(myNodeID) || (excluding != null && member.nodeID.contentEquals(excluding))) continue
            val peer = database.peers.fetch(nodeID = member.nodeID) ?: continue
            sendDirectPacket(PacketType.GROUP_KEY_GOSSIP, payload, to = peer)
        }
    }

    // MARK: - Manutenzione

    /** Job periodico (anche pubblico per i test). `now` in epoch ms. */
    suspend fun runMaintenance(now: Long = nowUnixMilliseconds): Unit = withContext(actorContext) {
        seen.purgeExpired(nowMs = now)
        val throttle = configuration.reackThrottle
        lastAckSentAt.entries.removeAll { now - it.value >= throttle }
        val seenInterval = configuration.lastSeenWriteInterval
        lastSeenWrites.entries.removeAll { now - it.value.date >= seenInterval }
        attempt { database.runMaintenance(now = now) }
        if (links.isNotEmpty()) {
            flushRelayQueue()
        }
        emitRelayQueueCount()
    }

    // MARK: - Utility

    /**
     * `markSeen` al massimo ogni `lastSeenWriteInterval` per peer, salvo cambio di hop o RSSI nuovo
     * da un link diretto (servono all'indicatore "Stato Radio").
     */
    private suspend fun markSeenThrottled(nodeID: ByteArray, hopCount: Int?, rssi: Int?) {
        val now = nowUnixMilliseconds
        val key = nodeID.toHex()
        val last = lastSeenWrites[key]
        if (last != null && last.hops == hopCount && now - last.date < configuration.lastSeenWriteInterval) return
        lastSeenWrites[key] = LastSeenWrite(now, hopCount)
        attempt { database.peers.markSeen(nodeID = nodeID, hopCount = hopCount, rssi = rssi) }
    }

    private fun emit(event: RouterEvent) {
        for (channel in subscribers.values) channel.trySend(event)
    }

    private suspend fun emitRelayQueueCount() {
        val count = attempt { database.relayQueue.stats().count } ?: 0
        emit(RouterEvent.RelayQueueChanged(count = count))
    }

    companion object {
        /** Titolo provvisorio di un gruppo di cui non conosciamo ancora il nome. */
        const val placeholderGroupTitle = "Gruppo di viaggio"

        const val readReceiptBatch = 64

        /** Lato massimo (px) di un avatar ricevuto: iOS e Android inviano 128 px, il resto e' malformato. */
        const val maxAvatarSide = 4 * MeshProtocol.avatarPixelSize

        fun isValidColorHex(hex: String): Boolean =
            hex.length == 6 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        fun containsLink(text: String): Boolean {
            val lowered = text.lowercase()
            return lowered.contains("http://") || lowered.contains("https://") || lowered.contains("www.")
        }
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
