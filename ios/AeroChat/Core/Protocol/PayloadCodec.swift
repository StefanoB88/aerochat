import Foundation
import CryptoKit

/// Pipeline `InnerPayload -> Protobuf -> (LZ4 se > 128 B) -> ChaCha20-Poly1305` e viceversa.
enum PayloadCodec {

    struct Encoded: Sendable {
        let encryptedPayload: Data
        let isLZ4Compressed: Bool
    }

    static func encode(_ payload: InnerPayload, key: SymmetricKey) throws -> Encoded {
        let plain = try MeshWire.serialize(payload)
        var body = plain
        var compressed = false
        if plain.count > MeshProtocol.lz4CompressionThreshold,
           let candidate = LZ4Codec.compress(plain),
           candidate.count < plain.count {
            body = candidate
            compressed = true
        }
        let sealed = try CryptoEngine.seal(body, key: key)
        return Encoded(encryptedPayload: sealed, isLZ4Compressed: compressed)
    }

    static func decode(encryptedPayload: Data, isLZ4Compressed: Bool, key: SymmetricKey) throws -> InnerPayload {
        var body = try CryptoEngine.open(encryptedPayload, key: key)
        if isLZ4Compressed {
            body = try LZ4Codec.decompress(body)
        }
        return try MeshWire.parseInnerPayload(body)
    }
}

/// Costruisce `MeshEnvelope` completi (cifrati e firmati) a partire dall'identita' locale.
struct EnvelopeBuilder: Sendable {

    let identity: IdentityKeys

    init(identity: IdentityKeys) {
        self.identity = identity
    }

    /// - Parameters:
    ///   - packetType: tipo di pacchetto.
    ///   - recipientID: 8 byte (peer `node_id`, hash del gruppo o 8 zeri per la Lounge).
    ///   - payload: contenuto in chiaro.
    ///   - key: chiave simmetrica della conversazione (derivata ECDH, `group_secret_key` o chiave Lounge).
    ///   - ttl: TTL iniziale (default 5, max 7).
    ///   - anonymousSender: se `true` `sender_node_id` e' tutto a zero.
    func build(packetType: PacketType,
               recipientID: Data,
               payload: InnerPayload,
               key: SymmetricKey,
               ttl: UInt32 = MeshProtocol.defaultTTL,
               msgID: Data = CryptoEngine.randomMessageID(),
               createdAtMs: Int64 = Date.nowUnixMilliseconds,
               anonymousSender: Bool = false) throws -> MeshEnvelope {
        guard recipientID.count == MeshProtocol.nodeIDLength else {
            throw EnvelopeVerifier.Failure.malformed("recipient_id deve essere di 8 byte")
        }
        guard msgID.count == MeshProtocol.msgIDLength else {
            throw EnvelopeVerifier.Failure.malformed("msg_id deve essere di 16 byte")
        }
        let encoded = try PayloadCodec.encode(payload, key: key)
        var envelope = MeshEnvelope()
        envelope.msgID = msgID
        envelope.senderNodeID = anonymousSender ? MeshProtocol.anonymousSenderID : identity.nodeID
        envelope.recipientID = recipientID
        envelope.ttl = min(ttl, MeshProtocol.maxTTL)
        envelope.hopCount = 0
        envelope.createdAtMs = createdAtMs
        envelope.packetType = packetType
        envelope.isLz4Compressed = encoded.isLZ4Compressed
        envelope.encryptedPayload = encoded.encryptedPayload
        envelope.ed25519Signature = try CryptoEngine.sign(
            msgID: msgID,
            recipientID: recipientID,
            createdAtMs: createdAtMs,
            encryptedPayload: encoded.encryptedPayload,
            with: identity.signingKey
        )
        return envelope
    }
}

/// Validazione strutturale e verifica di firma di un `MeshEnvelope` ricevuto dalla radio.
enum EnvelopeVerifier {

    enum Failure: Error, Equatable {
        case malformed(String)
        case badSignature
    }

    /// Controlla lunghezze e limiti prima di qualsiasi operazione costosa (deve costare < 1 ms).
    static func validateShape(_ envelope: MeshEnvelope) throws {
        guard envelope.msgID.count == MeshProtocol.msgIDLength else {
            throw Failure.malformed("msg_id length \(envelope.msgID.count)")
        }
        guard envelope.senderNodeID.count == MeshProtocol.nodeIDLength else {
            throw Failure.malformed("sender_node_id length \(envelope.senderNodeID.count)")
        }
        guard envelope.recipientID.count == MeshProtocol.nodeIDLength else {
            throw Failure.malformed("recipient_id length \(envelope.recipientID.count)")
        }
        guard envelope.ttl <= MeshProtocol.maxTTL else {
            throw Failure.malformed("ttl \(envelope.ttl) > max")
        }
        guard envelope.hopCount <= MeshProtocol.maxTTL else {
            throw Failure.malformed("hop_count \(envelope.hopCount) > max")
        }
        // Timestamp arbitrario dalla rete: fuori da [1970, 2100] e' malformato (evita anche overflow
        // aritmetici nel controllo anti-replay, che in Swift farebbero crashare l'app).
        guard envelope.createdAtMs > 0, envelope.createdAtMs < MeshProtocol.maxCreatedAtMs else {
            throw Failure.malformed("created_at_ms \(envelope.createdAtMs)")
        }
        guard envelope.ed25519Signature.count == MeshProtocol.signatureLength else {
            throw Failure.malformed("signature length \(envelope.ed25519Signature.count)")
        }
        let minimumPayload = MeshProtocol.aeadNonceLength + MeshProtocol.aeadTagLength
        guard envelope.encryptedPayload.count > minimumPayload else {
            throw Failure.malformed("encrypted_payload too short")
        }
        guard envelope.encryptedPayload.count <= MeshProtocol.maxFrameBytes else {
            throw Failure.malformed("encrypted_payload too large")
        }
    }

    /// Verifica la firma Ed25519 con la chiave pubblica del mittente dichiarato.
    static func verifySignature(_ envelope: MeshEnvelope, senderEd25519PublicKeyRaw: Data) -> Bool {
        CryptoEngine.verify(
            signature: envelope.ed25519Signature,
            msgID: envelope.msgID,
            recipientID: envelope.recipientID,
            createdAtMs: envelope.createdAtMs,
            encryptedPayload: envelope.encryptedPayload,
            publicKeyRaw: senderEd25519PublicKeyRaw
        )
    }

    /// Apre un envelope indirizzato a noi: decifra, verifica la firma con la chiave Ed25519
    /// contenuta nel payload (o con quella attesa, se gia' nota) e restituisce il payload.
    static func open(_ envelope: MeshEnvelope,
                     key: SymmetricKey,
                     expectedSenderEd25519PublicKeyRaw: Data? = nil) throws -> InnerPayload {
        try validateShape(envelope)
        let payload = try PayloadCodec.decode(
            encryptedPayload: envelope.encryptedPayload,
            isLZ4Compressed: envelope.isLz4Compressed,
            key: key
        )
        let signer = expectedSenderEd25519PublicKeyRaw ?? payload.senderEd25519Pub
        guard signer.count == MeshProtocol.publicKeyLength,
              verifySignature(envelope, senderEd25519PublicKeyRaw: signer) else {
            throw Failure.badSignature
        }
        return payload
    }
}
