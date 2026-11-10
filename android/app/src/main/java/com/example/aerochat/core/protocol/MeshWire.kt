package com.example.aerochat.core.protocol

import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.crypto.IdentityKeys
import com.example.aerochat.data.records.PeerRecord
import com.example.aerochat.proto.CallAudio
import com.example.aerochat.proto.CallSignal
import com.example.aerochat.proto.EmergencyBroadcast
import com.example.aerochat.proto.GameMove
import com.example.aerochat.proto.GroupKeyGossip
import com.example.aerochat.proto.GroupMember
import com.example.aerochat.proto.InnerPayload
import com.example.aerochat.proto.MeshEnvelope
import com.example.aerochat.proto.ProPassGrant
import com.example.aerochat.proto.ProfileUpdate
import com.example.aerochat.proto.QuizPacket
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException

/**
 * Punto unico di (de)serializzazione Protobuf, cosi' il resto dell'app (e i test)
 * non dipendono direttamente dalle API generate. I `parse*` lanciano [InvalidProtocolBufferException].
 */
object MeshWire {

    fun serialize(envelope: MeshEnvelope): ByteArray = envelope.toByteArray()
    fun serialize(payload: InnerPayload): ByteArray = payload.toByteArray()
    fun serialize(gossip: GroupKeyGossip): ByteArray = gossip.toByteArray()
    fun serialize(grant: ProPassGrant): ByteArray = grant.toByteArray()
    fun serialize(move: GameMove): ByteArray = move.toByteArray()
    fun serialize(profile: ProfileUpdate): ByteArray = profile.toByteArray()
    fun serialize(signal: CallSignal): ByteArray = signal.toByteArray()
    fun serialize(audio: CallAudio): ByteArray = audio.toByteArray()
    fun serialize(quiz: QuizPacket): ByteArray = quiz.toByteArray()
    fun serialize(emergency: EmergencyBroadcast): ByteArray = emergency.toByteArray()

    @Throws(InvalidProtocolBufferException::class)
    fun parseQuizPacket(data: ByteArray): QuizPacket = QuizPacket.parseFrom(data)

    @Throws(InvalidProtocolBufferException::class)
    fun parseEmergencyBroadcast(data: ByteArray): EmergencyBroadcast = EmergencyBroadcast.parseFrom(data)

    @Throws(InvalidProtocolBufferException::class)
    fun parseCallSignal(data: ByteArray): CallSignal = CallSignal.parseFrom(data)

    @Throws(InvalidProtocolBufferException::class)
    fun parseCallAudio(data: ByteArray): CallAudio = CallAudio.parseFrom(data)

    @Throws(InvalidProtocolBufferException::class)
    fun parseEnvelope(data: ByteArray): MeshEnvelope = MeshEnvelope.parseFrom(data)

    @Throws(InvalidProtocolBufferException::class)
    fun parseInnerPayload(data: ByteArray): InnerPayload = InnerPayload.parseFrom(data)

    @Throws(InvalidProtocolBufferException::class)
    fun parseGroupKeyGossip(data: ByteArray): GroupKeyGossip = GroupKeyGossip.parseFrom(data)

    @Throws(InvalidProtocolBufferException::class)
    fun parseProPassGrant(data: ByteArray): ProPassGrant = ProPassGrant.parseFrom(data)

    @Throws(InvalidProtocolBufferException::class)
    fun parseGameMove(data: ByteArray): GameMove = GameMove.parseFrom(data)

    @Throws(InvalidProtocolBufferException::class)
    fun parseProfileUpdate(data: ByteArray): ProfileUpdate = ProfileUpdate.parseFrom(data)
}

/** `ByteArray` -> `ByteString` (campi `bytes` dei messaggi generati). */
fun ByteArray.toByteString(): ByteString = ByteString.copyFrom(this)

// MARK: - InnerPayload (static func di `extension InnerPayload` su iOS)

/**
 * Factory di [InnerPayload] (su iOS `static func` in `extension InnerPayload`; le classi Java generate
 * non hanno un companion estendibile). Restituiscono il messaggio gia' costruito; per aggiungere campi
 * usare `.toBuilder()`.
 */
object InnerPayloadFactory {

    /**
     * Builder base con le chiavi pubbliche del mittente (necessarie al destinatario per verificare
     * la firma e, se non ci conosce ancora, per rispondere).
     */
    fun signedBuilder(identity: IdentityKeys, nickname: String): InnerPayload.Builder =
        InnerPayload.newBuilder()
            .setSenderNickname(nickname)
            .setSenderX25519Pub(identity.x25519PublicKeyRaw.toByteString())
            .setSenderEd25519Pub(identity.ed25519PublicKeyRaw.toByteString())

    /** Swift `InnerPayload.signed(by:nickname:)`. */
    fun signed(identity: IdentityKeys, nickname: String): InnerPayload = signedBuilder(identity, nickname).build()

    /** Messaggio testuale 1-a-1, di gruppo o di Lounge. */
    fun text(text: String, identity: IdentityKeys, nickname: String, replyTo: ByteArray? = null): InnerPayload {
        val builder = signedBuilder(identity, nickname).setText(text)
        if (replyTo != null) builder.setReplyToMsgId(replyTo.toHex())
        return builder.build()
    }

    /** `DELIVERY_ACK`: `reply_to_msg_id` contiene il `msg_id` (hex) del messaggio consegnato. */
    fun deliveryAck(msgID: ByteArray, identity: IdentityKeys, nickname: String): InnerPayload =
        signedBuilder(identity, nickname).setReplyToMsgId(msgID.toHex()).build()

    /** Payload con `extra_data` (gossip, pro pass, mosse di gioco). */
    fun extra(data: ByteArray, identity: IdentityKeys, nickname: String): InnerPayload =
        signedBuilder(identity, nickname).setExtraData(data.toByteString()).build()
}

/** `msg_id` referenziato da `reply_to_msg_id`, se valido. */
val InnerPayload.replyToMessageID: ByteArray?
    get() {
        if (replyToMsgId.isEmpty()) return null
        val data = replyToMsgId.hexToByteArray() ?: return null
        return if (data.size == MeshProtocol.msgIDLength) data else null
    }

/** `node_id` derivato dalla chiave X25519 dichiarata nel payload. */
val InnerPayload.declaredSenderNodeID: ByteArray?
    get() {
        if (senderX25519Pub.size() != MeshProtocol.publicKeyLength) return null
        return CryptoEngine.nodeID(forX25519PublicKey = senderX25519Pub.toByteArray())
    }

// MARK: - GroupMember

/** Swift `GroupMember(peer:)`. */
fun GroupMember(peer: PeerRecord): GroupMember =
    GroupMember.newBuilder()
        .setNodeId(peer.nodeID.toByteString())
        .setX25519Pub(peer.x25519Pub.toByteString())
        .setEd25519Pub(peer.ed25519Pub.toByteString())
        .setNickname(peer.nickname)
        .build()

/** Swift `GroupMember(identity:nickname:)`. */
fun GroupMember(identity: IdentityKeys, nickname: String): GroupMember =
    GroupMember.newBuilder()
        .setNodeId(identity.nodeID.toByteString())
        .setX25519Pub(identity.x25519PublicKeyRaw.toByteString())
        .setEd25519Pub(identity.ed25519PublicKeyRaw.toByteString())
        .setNickname(nickname)
        .build()

/** Un membro e' valido solo se il suo `node_id` corrisponde alla chiave X25519 dichiarata. */
val GroupMember.isConsistent: Boolean
    get() = nodeId.size() == MeshProtocol.nodeIDLength &&
        x25519Pub.size() == MeshProtocol.publicKeyLength &&
        ed25519Pub.size() == MeshProtocol.publicKeyLength &&
        CryptoEngine.nodeID(forX25519PublicKey = x25519Pub.toByteArray()).contentEquals(nodeId.toByteArray())

/** Nickname di default per un membro senza nome (`String(localized: "Passeggero")`). */
const val defaultPassengerNickname = "Passeggero"

val GroupMember.asPeerRecord: PeerRecord
    get() = PeerRecord(
        nodeID = nodeId.toByteArray(),
        x25519Pub = x25519Pub.toByteArray(),
        ed25519Pub = ed25519Pub.toByteArray(),
        nickname = nickname.ifEmpty { defaultPassengerNickname },
    )
