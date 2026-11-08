package com.example.aerochat.core.entitlements

import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.crypto.IdentityKeys
import com.example.aerochat.core.mesh.InboundPacket
import com.example.aerochat.core.mesh.MeshRouterActor
import com.example.aerochat.core.mesh.Reachability
import com.example.aerochat.core.protocol.InnerPayloadFactory
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.MeshWire
import com.example.aerochat.core.protocol.bigEndianBytes
import com.example.aerochat.core.protocol.rawBytes
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.ConversationType
import com.example.aerochat.data.records.ProTier
import com.example.aerochat.proto.PacketType
import com.example.aerochat.proto.ProPassGrant
import com.google.protobuf.ByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * Flight Group Pass "1 paga, il gruppo usa": l'utente Pro emette un `PRO_PASS_GRANT` firmato Ed25519
 * per ogni gruppo di viaggio; tutti i membri (anche iOS, anche gratuiti) sbloccano la conversazione.
 */
class ProPassService(
    private val router: MeshRouterActor,
    private val database: AppDatabase,
    private val entitlements: EntitlementManager,
    private val identity: IdentityKeys,
    nickname: String,
) {

    sealed interface Event {
        class Unlocked(val conversationID: String, val validUntilMs: Long, val issuerNodeID: ByteArray) : Event {
            override fun equals(other: Any?): Boolean =
                other is Unlocked && conversationID == other.conversationID && validUntilMs == other.validUntilMs &&
                    issuerNodeID.contentEquals(other.issuerNodeID)

            override fun hashCode(): Int =
                (conversationID.hashCode() * 31 + validUntilMs.hashCode()) * 31 + issuerNodeID.contentHashCode()

            override fun toString(): String =
                "Unlocked(conversationID=$conversationID, validUntilMs=$validUntilMs, issuerNodeID=${issuerNodeID.toHex()})"
        }
    }

    @Volatile
    private var nickname: String = nickname
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inboundJob: Job? = null
    private val subscriberIDs = AtomicLong(0)
    private val subscribers = ConcurrentHashMap<Long, Channel<Event>>()

    suspend fun start() {
        mutex.withLock {
            if (inboundJob != null) return
            // Registrazione immediata: nessun pacchetto perso tra start() e l'avvio della coroutine.
            val packets = router.inboundPackets()
            inboundJob = scope.launch {
                packets.collect { packet ->
                    if (packet.envelope.packetType == PacketType.PRO_PASS_GRANT) handle(packet)
                }
            }
        }
    }

    fun stop() {
        inboundJob?.cancel()
        inboundJob = null
    }

    fun setNickname(nickname: String) {
        this.nickname = nickname
    }

    /** Eventi di sblocco (registrazione immediata alla chiamata, ultimi 16 bufferizzati). */
    fun events(): Flow<Event> {
        val id = subscriberIDs.incrementAndGet()
        val channel = Channel<Event>(16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        subscribers[id] = channel
        return channel.consumeAsFlow().onCompletion { subscribers.remove(id) }
    }

    // MARK: - Emissione

    /**
     * Se siamo Pro, sblocca il gruppo localmente e trasmette il grant ai membri. Ritorna `true` se emesso.
     * `now` in epoch ms.
     */
    suspend fun broadcastGrantIfEntitled(group: ConversationRecord, now: Long = System.currentTimeMillis()): Boolean {
        if (group.type != ConversationType.group) return false
        val groupID = group.groupID ?: return false
        val state = entitlements.state
        if (!state.isPro(now)) return false

        var validUntil = now + MeshProtocol.proPassDuration
        val ownLimit = state.validUntilMs
        if (state.tier == ProTier.pass72h && ownLimit != null) {
            validUntil = minOf(validUntil, ownLimit)
        }
        val productID = if (state.tier == ProTier.lifetime) MeshProtocol.ProductID.lifetime else MeshProtocol.ProductID.pass72h
        val grant = makeGrant(groupID = groupID, validUntilMs = validUntil, productID = productID, identity = identity)

        database.conversations.setProUnlocked(until = validUntil, conversationID = group.id)
        emit(Event.Unlocked(conversationID = group.id, validUntilMs = validUntil, issuerNodeID = identity.nodeID))

        val payload = InnerPayloadFactory.extra(MeshWire.serialize(grant), identity = identity, nickname = nickname)
        router.sendPacket(type = PacketType.PRO_PASS_GRANT, payload = payload, conversation = group)
        return true
    }

    /** Ri-emette il grant per tutti i gruppi (dopo un acquisto). */
    suspend fun broadcastGrantsForAllGroups() {
        val groups = try {
            database.conversations.fetchPermanent()
        } catch (_: Exception) {
            return
        }
        for (group in groups) {
            if (group.type != ConversationType.group) continue
            try {
                broadcastGrantIfEntitled(group)
            } catch (_: Exception) {
            }
        }
    }

    // MARK: - Ricezione

    private suspend fun handle(packet: InboundPacket, now: Long = System.currentTimeMillis()) {
        if (packet.conversation.type != ConversationType.group) return
        val grant = try {
            MeshWire.parseProPassGrant(packet.payload.extraData.toByteArray())
        } catch (_: Exception) {
            return
        }
        if (!verify(grant, now)) return
        if (!grant.issuerEd25519Pub.toByteArray().contentEquals(packet.sender.ed25519Pub)) return
        val groupID = packet.conversation.groupID ?: return
        if (!grant.groupUuid.toByteArray().contentEquals(groupID.rawBytes())) return

        val current = packet.conversation.proUnlockedUntilMs ?: 0L
        if (grant.validUntilMs <= current) return
        try {
            database.conversations.setProUnlocked(until = grant.validUntilMs, conversationID = packet.conversation.id)
        } catch (_: Exception) {
        }
        emit(Event.Unlocked(conversationID = packet.conversation.id, validUntilMs = grant.validUntilMs, issuerNodeID = packet.sender.nodeID))
    }

    private fun emit(event: Event) {
        for (channel in subscribers.values) channel.trySend(event)
    }

    companion object {
        /** Tolleranza sulla durata massima del grant (orologi non sincronizzati), in ms. */
        const val clockSkew: Long = 15 * 60_000L

        /** Messaggio firmato: `group_uuid (16) || valid_until_ms (Int64 Big-Endian)`. */
        fun signedMessage(groupUUID: ByteArray, validUntilMs: Long): ByteArray = groupUUID + validUntilMs.bigEndianBytes

        fun makeGrant(groupID: UUID, validUntilMs: Long, productID: String, identity: IdentityKeys): ProPassGrant {
            val groupUUID = groupID.rawBytes()
            return ProPassGrant.newBuilder()
                .setGroupUuid(ByteString.copyFrom(groupUUID))
                .setValidUntilMs(validUntilMs)
                .setIssuerEd25519Pub(ByteString.copyFrom(identity.ed25519PublicKeyRaw))
                .setProductId(productID)
                .setSignature(
                    ByteString.copyFrom(
                        CryptoEngine.sign(message = signedMessage(groupUUID, validUntilMs), with = identity.signingKey),
                    ),
                )
                .build()
        }

        /** Verifica firma, scadenza e durata massima (72 h + tolleranza) del grant. `now` in epoch ms. */
        fun verify(grant: ProPassGrant, now: Long = System.currentTimeMillis()): Boolean {
            if (grant.groupUuid.size() != 16 ||
                grant.issuerEd25519Pub.size() != MeshProtocol.publicKeyLength ||
                grant.signature.size() != MeshProtocol.signatureLength
            ) return false
            val maximum = now + MeshProtocol.proPassDuration + clockSkew
            if (grant.validUntilMs <= now || grant.validUntilMs > maximum) return false
            return CryptoEngine.verify(
                signature = grant.signature.toByteArray(),
                message = signedMessage(grant.groupUuid.toByteArray(), grant.validUntilMs),
                publicKeyRaw = grant.issuerEd25519Pub.toByteArray(),
            )
        }
    }
}

// MARK: - Feature gate

enum class ProFeature {
    groupChat,
    voiceNote,
    photo,
    game,
    call,
}

enum class FeatureDecision {
    allowed,
    requiresPass,
    photosNeedDirectLink,
    peerOutOfRange,
    loungeTextOnly,
    /** Chiamata possibile solo con link diretto (niente ponti) verso un compagno 1-a-1. */
    callNeedsDirectLink,
    /** Link diretto presente ma segnale troppo debole per l'audio in tempo reale. */
    callSignalTooWeak;

    val isAllowed: Boolean get() = this == allowed
}

/**
 * Regole del modello Freemium: testo 1-a-1, Lounge e store-and-forward sempre gratis;
 * gruppi 3+, vocali, foto e giochi con Pro o Flight Group Pass attivo sulla conversazione.
 */
object FeatureGate {

    /** `decision(for:userIsPro:conversation:reachability:now:)`; `now` in epoch ms. */
    fun decision(
        feature: ProFeature,
        userIsPro: Boolean,
        conversation: ConversationRecord,
        reachability: Reachability,
        now: Long = System.currentTimeMillis(),
    ): FeatureDecision {
        if (conversation.type == ConversationType.cabinLounge) return FeatureDecision.loungeTextOnly
        val unlocked = userIsPro || conversation.isProUnlocked(now)
        if (!unlocked) return FeatureDecision.requiresPass
        return when (feature) {
            ProFeature.groupChat, ProFeature.game -> FeatureDecision.allowed
            ProFeature.voiceNote ->
                if (reachability.allowsRealtimeDelivery) FeatureDecision.allowed else FeatureDecision.peerOutOfRange
            ProFeature.photo -> when {
                !reachability.allowsRealtimeDelivery -> FeatureDecision.peerOutOfRange
                reachability.allowsPhotos -> FeatureDecision.allowed
                else -> FeatureDecision.photosNeedDirectLink
            }
            ProFeature.call -> {
                if (conversation.type != ConversationType.direct) return FeatureDecision.callNeedsDirectLink
                when (reachability) {
                    Reachability.OutOfRange -> FeatureDecision.peerOutOfRange
                    is Reachability.ViaBridge -> FeatureDecision.callNeedsDirectLink
                    is Reachability.Direct -> {
                        val rssi = reachability.rssi
                        if (rssi != null && rssi < MeshProtocol.minimumCallRSSI) FeatureDecision.callSignalTooWeak
                        else FeatureDecision.allowed
                    }
                }
            }
        }
    }
}
