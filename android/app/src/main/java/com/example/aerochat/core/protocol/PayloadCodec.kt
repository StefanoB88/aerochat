package com.example.aerochat.core.protocol

import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.crypto.IdentityKeys
import com.example.aerochat.core.crypto.SymmetricKey
import com.example.aerochat.proto.InnerPayload
import com.example.aerochat.proto.MeshEnvelope
import com.example.aerochat.proto.PacketType

/** Pipeline `InnerPayload -> Protobuf -> (LZ4 se > 128 B) -> ChaCha20-Poly1305` e viceversa. */
object PayloadCodec {

    class Encoded(val encryptedPayload: ByteArray, val isLZ4Compressed: Boolean)

    @Throws(CryptoEngine.Failure::class)
    fun encode(payload: InnerPayload, key: SymmetricKey): Encoded {
        val plain = MeshWire.serialize(payload)
        var body = plain
        var compressed = false
        if (plain.size > MeshProtocol.lz4CompressionThreshold) {
            val candidate = LZ4Codec.compress(plain)
            if (candidate != null && candidate.size < plain.size) {
                body = candidate
                compressed = true
            }
        }
        val sealed = CryptoEngine.seal(body, key)
        return Encoded(encryptedPayload = sealed, isLZ4Compressed = compressed)
    }

    /** Lancia [CryptoEngine.Failure], [LZ4Codec.Failure] o `InvalidProtocolBufferException`. */
    @Throws(Exception::class)
    fun decode(encryptedPayload: ByteArray, isLZ4Compressed: Boolean, key: SymmetricKey): InnerPayload {
        var body = CryptoEngine.open(encryptedPayload, key)
        if (isLZ4Compressed) {
            body = LZ4Codec.decompress(body)
        }
        return MeshWire.parseInnerPayload(body)
    }
}

/** Costruisce `MeshEnvelope` completi (cifrati e firmati) a partire dall'identita' locale. */
class EnvelopeBuilder(val identity: IdentityKeys) {

    /**
     * @param packetType tipo di pacchetto.
     * @param recipientID 8 byte (peer `node_id`, hash del gruppo o 8 zeri per la Lounge).
     * @param payload contenuto in chiaro.
     * @param key chiave simmetrica della conversazione (derivata ECDH, `group_secret_key` o chiave Lounge).
     * @param ttl TTL iniziale (default 5, max 7; `UInt32` su iOS).
     * @param anonymousSender se `true` `sender_node_id` e' tutto a zero.
     */
    @Throws(EnvelopeVerifier.Failure::class, CryptoEngine.Failure::class)
    fun build(
        packetType: PacketType,
        recipientID: ByteArray,
        payload: InnerPayload,
        key: SymmetricKey,
        ttl: Int = MeshProtocol.defaultTTL,
        msgID: ByteArray = CryptoEngine.randomMessageID(),
        createdAtMs: Long = nowUnixMilliseconds,
        anonymousSender: Boolean = false,
    ): MeshEnvelope {
        if (recipientID.size != MeshProtocol.nodeIDLength) {
            throw EnvelopeVerifier.Failure.Malformed("recipient_id deve essere di 8 byte")
        }
        if (msgID.size != MeshProtocol.msgIDLength) {
            throw EnvelopeVerifier.Failure.Malformed("msg_id deve essere di 16 byte")
        }
        val encoded = PayloadCodec.encode(payload, key)
        // `min` su UInt32: confronto senza segno.
        val clampedTTL = if (Integer.compareUnsigned(ttl, MeshProtocol.maxTTL) > 0) MeshProtocol.maxTTL else ttl
        val signature = CryptoEngine.sign(
            msgID = msgID,
            recipientID = recipientID,
            createdAtMs = createdAtMs,
            encryptedPayload = encoded.encryptedPayload,
            with = identity.signingKey,
        )
        return MeshEnvelope.newBuilder()
            .setMsgId(msgID.toByteString())
            .setSenderNodeId((if (anonymousSender) MeshProtocol.anonymousSenderID else identity.nodeID).toByteString())
            .setRecipientId(recipientID.toByteString())
            .setTtl(clampedTTL)
            .setHopCount(0)
            .setCreatedAtMs(createdAtMs)
            .setPacketType(packetType)
            .setIsLz4Compressed(encoded.isLZ4Compressed)
            .setEncryptedPayload(encoded.encryptedPayload.toByteString())
            .setEd25519Signature(signature.toByteString())
            .build()
    }
}

/** Validazione strutturale e verifica di firma di un `MeshEnvelope` ricevuto dalla radio. */
object EnvelopeVerifier {

    sealed class Failure(message: String) : Exception(message) {
        data class Malformed(val reason: String) : Failure("malformed: $reason")
        data object BadSignature : Failure("bad signature") {
            private fun readResolve(): Any = BadSignature
        }
    }

    /** Controlla lunghezze e limiti prima di qualsiasi operazione costosa (deve costare < 1 ms). */
    @Throws(Failure::class)
    fun validateShape(envelope: MeshEnvelope) {
        if (envelope.msgId.size() != MeshProtocol.msgIDLength) {
            throw Failure.Malformed("msg_id length ${envelope.msgId.size()}")
        }
        if (envelope.senderNodeId.size() != MeshProtocol.nodeIDLength) {
            throw Failure.Malformed("sender_node_id length ${envelope.senderNodeId.size()}")
        }
        if (envelope.recipientId.size() != MeshProtocol.nodeIDLength) {
            throw Failure.Malformed("recipient_id length ${envelope.recipientId.size()}")
        }
        // ttl / hop_count sono uint32: confronto senza segno.
        if (Integer.compareUnsigned(envelope.ttl, MeshProtocol.maxTTL) > 0) {
            throw Failure.Malformed("ttl ${Integer.toUnsignedString(envelope.ttl)} > max")
        }
        if (Integer.compareUnsigned(envelope.hopCount, MeshProtocol.maxTTL) > 0) {
            throw Failure.Malformed("hop_count ${Integer.toUnsignedString(envelope.hopCount)} > max")
        }
        // Timestamp arbitrario dalla rete: fuori da [1970, 2100] e' malformato.
        if (envelope.createdAtMs <= 0 || envelope.createdAtMs >= MeshProtocol.maxCreatedAtMs) {
            throw Failure.Malformed("created_at_ms ${envelope.createdAtMs}")
        }
        if (envelope.ed25519Signature.size() != MeshProtocol.signatureLength) {
            throw Failure.Malformed("signature length ${envelope.ed25519Signature.size()}")
        }
        val minimumPayload = MeshProtocol.aeadNonceLength + MeshProtocol.aeadTagLength
        if (envelope.encryptedPayload.size() <= minimumPayload) {
            throw Failure.Malformed("encrypted_payload too short")
        }
        if (envelope.encryptedPayload.size() > MeshProtocol.maxFrameBytes) {
            throw Failure.Malformed("encrypted_payload too large")
        }
    }

    /** Verifica la firma Ed25519 con la chiave pubblica del mittente dichiarato. */
    fun verifySignature(envelope: MeshEnvelope, senderEd25519PublicKeyRaw: ByteArray): Boolean =
        CryptoEngine.verify(
            signature = envelope.ed25519Signature.toByteArray(),
            msgID = envelope.msgId.toByteArray(),
            recipientID = envelope.recipientId.toByteArray(),
            createdAtMs = envelope.createdAtMs,
            encryptedPayload = envelope.encryptedPayload.toByteArray(),
            publicKeyRaw = senderEd25519PublicKeyRaw,
        )

    /**
     * Apre un envelope indirizzato a noi: decifra, verifica la firma con la chiave Ed25519
     * contenuta nel payload (o con quella attesa, se gia' nota) e restituisce il payload.
     * Lancia [Failure], [CryptoEngine.Failure], [LZ4Codec.Failure] o `InvalidProtocolBufferException`.
     */
    @Throws(Exception::class)
    fun open(
        envelope: MeshEnvelope,
        key: SymmetricKey,
        expectedSenderEd25519PublicKeyRaw: ByteArray? = null,
    ): InnerPayload {
        validateShape(envelope)
        val payload = PayloadCodec.decode(
            encryptedPayload = envelope.encryptedPayload.toByteArray(),
            isLZ4Compressed = envelope.isLz4Compressed,
            key = key,
        )
        val signer = expectedSenderEd25519PublicKeyRaw ?: payload.senderEd25519Pub.toByteArray()
        if (signer.size != MeshProtocol.publicKeyLength || !verifySignature(envelope, signer)) {
            throw Failure.BadSignature
        }
        return payload
    }
}
