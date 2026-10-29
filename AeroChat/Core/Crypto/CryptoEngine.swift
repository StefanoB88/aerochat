import Foundation
import CryptoKit

/// Primitive crittografiche AeroMesh, byte-compatibili con BouncyCastle su Android.
///
/// - Identita': X25519 (key agreement) + Ed25519 (firma), entrambe raw a 32 byte.
/// - `node_id` = primi 8 byte di `SHA256(x25519_public_key_raw)`.
/// - Chiave chat 1-a-1 = `HKDF-SHA256(ECDH(my_priv, peer_pub), salt: "AeroMesh-Salt-v1", info: "AeroMesh-Msg-v1", 32 byte)`.
/// - AEAD = ChaCha20-Poly1305 (RFC 8439) serializzato come `[12B nonce][ciphertext][16B tag]`.
enum CryptoEngine {

    enum Failure: Error, Equatable {
        case invalidKeyLength(expected: Int, actual: Int)
        case invalidSealedBox
    }

    // MARK: - Identificatori

    /// `node_id` di una chiave pubblica X25519 raw (32 byte).
    static func nodeID(forX25519PublicKey publicKeyRaw: Data) -> Data {
        Data(SHA256.hash(data: publicKeyRaw).prefix(MeshProtocol.nodeIDLength))
    }

    /// `recipient_id` di un gruppo: primi 8 byte di `SHA256(group_uuid_bytes)`.
    static func groupRecipientID(groupID: UUID) -> Data {
        Data(SHA256.hash(data: groupID.rawBytes).prefix(MeshProtocol.nodeIDLength))
    }

    /// 16 byte casuali (UUID v4 raw) per `msg_id`.
    static func randomMessageID() -> Data {
        UUID().rawBytes
    }

    /// `group_secret_key` casuale a 32 byte.
    static func randomGroupSecretKey() -> Data {
        SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
    }

    // MARK: - Derivazione chiavi

    /// Chiave simmetrica della chat 1-a-1. E' simmetrica: A con pub(B) == B con pub(A).
    static func deriveDirectChatKey(privateKey: Curve25519.KeyAgreement.PrivateKey,
                                    peerX25519PublicKeyRaw: Data) throws -> SymmetricKey {
        guard peerX25519PublicKeyRaw.count == MeshProtocol.publicKeyLength else {
            throw Failure.invalidKeyLength(expected: MeshProtocol.publicKeyLength, actual: peerX25519PublicKeyRaw.count)
        }
        let peerPublicKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerX25519PublicKeyRaw)
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: peerPublicKey)
        // HKDF-SHA256 con IKM = shared secret X25519 raw (32 byte), come HKDFBytesGenerator su Android.
        return sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: MeshProtocol.hkdfSalt,
            sharedInfo: MeshProtocol.hkdfInfo,
            outputByteCount: MeshProtocol.symmetricKeyLength
        )
    }

    /// Chiave simmetrica di gruppo da `group_secret_key` (32 byte raw).
    static func groupKey(fromSecret secret: Data) throws -> SymmetricKey {
        guard secret.count == MeshProtocol.symmetricKeyLength else {
            throw Failure.invalidKeyLength(expected: MeshProtocol.symmetricKeyLength, actual: secret.count)
        }
        return SymmetricKey(data: secret)
    }

    /// Chiave nota della Lounge Pubblica di Cabina: `SHA256("AeroMesh-Public-Cabin-v1")`.
    static var publicCabinKey: SymmetricKey {
        SymmetricKey(data: SHA256.hash(data: MeshProtocol.publicCabinLabel))
    }

    // MARK: - AEAD ChaCha20-Poly1305

    /// Cifra con nonce casuale a 12 byte. Output = `SealedBox.combined` = `[nonce][ciphertext][tag]`.
    static func seal(_ plaintext: Data, key: SymmetricKey) throws -> Data {
        try ChaChaPoly.seal(plaintext, using: key).combined
    }

    /// Variante deterministica (nonce esplicito, AAD opzionale) usata nei test con i vettori RFC 8439.
    static func seal(_ plaintext: Data, key: SymmetricKey, nonce: Data, authenticating aad: Data? = nil) throws -> Data {
        let chachaNonce = try ChaChaPoly.Nonce(data: nonce)
        if let aad {
            return try ChaChaPoly.seal(plaintext, using: key, nonce: chachaNonce, authenticating: aad).combined
        }
        return try ChaChaPoly.seal(plaintext, using: key, nonce: chachaNonce).combined
    }

    /// Decifra `[nonce][ciphertext][tag]`. Lancia se il tag non verifica.
    static func open(_ combined: Data, key: SymmetricKey, authenticating aad: Data? = nil) throws -> Data {
        guard combined.count > MeshProtocol.aeadNonceLength + MeshProtocol.aeadTagLength else {
            throw Failure.invalidSealedBox
        }
        let box = try ChaChaPoly.SealedBox(combined: combined)
        if let aad {
            return try ChaChaPoly.open(box, using: key, authenticating: aad)
        }
        return try ChaChaPoly.open(box, using: key)
    }

    // MARK: - Firma Ed25519

    /// Messaggio firmato: `msg_id || recipient_id || created_at_ms (Int64 Big-Endian) || encrypted_payload`.
    static func signatureMessage(msgID: Data, recipientID: Data, createdAtMs: Int64, encryptedPayload: Data) -> Data {
        var message = Data(capacity: msgID.count + recipientID.count + 8 + encryptedPayload.count)
        message.append(msgID)
        message.append(recipientID)
        message.append(createdAtMs.bigEndianBytes)
        message.append(encryptedPayload)
        return message
    }

    static func sign(msgID: Data, recipientID: Data, createdAtMs: Int64, encryptedPayload: Data,
                     with signingKey: Curve25519.Signing.PrivateKey) throws -> Data {
        let message = signatureMessage(msgID: msgID, recipientID: recipientID, createdAtMs: createdAtMs, encryptedPayload: encryptedPayload)
        return try signingKey.signature(for: message)
    }

    static func verify(signature: Data, msgID: Data, recipientID: Data, createdAtMs: Int64, encryptedPayload: Data,
                       publicKeyRaw: Data) -> Bool {
        guard signature.count == MeshProtocol.signatureLength,
              let publicKey = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKeyRaw) else {
            return false
        }
        let message = signatureMessage(msgID: msgID, recipientID: recipientID, createdAtMs: createdAtMs, encryptedPayload: encryptedPayload)
        return publicKey.isValidSignature(signature, for: message)
    }

    /// Firma generica su byte arbitrari (usata per `PRO_PASS_GRANT`).
    static func sign(message: Data, with signingKey: Curve25519.Signing.PrivateKey) throws -> Data {
        try signingKey.signature(for: message)
    }

    static func verify(signature: Data, message: Data, publicKeyRaw: Data) -> Bool {
        guard let publicKey = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKeyRaw) else { return false }
        return publicKey.isValidSignature(signature, for: message)
    }
}

extension SymmetricKey {
    /// Byte raw della chiave (per test e per cifrare `group_secret_key` verso ogni membro).
    var rawBytes: Data {
        withUnsafeBytes { Data($0) }
    }
}
