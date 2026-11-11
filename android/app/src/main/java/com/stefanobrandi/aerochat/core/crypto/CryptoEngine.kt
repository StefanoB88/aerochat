package com.stefanobrandi.aerochat.core.crypto

import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.bigEndianBytes
import com.stefanobrandi.aerochat.core.protocol.rawBytes
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Chiave simmetrica (equivalente di `CryptoKit.SymmetricKey`). Uguaglianza per contenuto.
 * [rawBytes] e' una copia difensiva dei byte passati.
 */
class SymmetricKey(rawBytes: ByteArray) {
    private val bytes: ByteArray = rawBytes.copyOf()

    /** Byte raw della chiave (per test e per cifrare `group_secret_key` verso ogni membro). */
    val rawBytes: ByteArray get() = bytes.copyOf()

    /** Dimensione della chiave in byte. */
    val byteCount: Int get() = bytes.size

    internal fun unsafeBytes(): ByteArray = bytes

    override fun equals(other: Any?): Boolean = other is SymmetricKey && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "SymmetricKey(${bytes.size * 8} bit)"

    companion object {
        /** `SymmetricKey(size: .bits256)`: 32 byte casuali. */
        fun random256(): SymmetricKey = SymmetricKey(CryptoEngine.randomBytes(32))
    }
}

/**
 * Primitive crittografiche AeroMesh, byte-compatibili con CryptoKit su iOS.
 *
 * - Identita': X25519 (key agreement) + Ed25519 (firma), entrambe raw a 32 byte.
 * - `node_id` = primi 8 byte di `SHA256(x25519_public_key_raw)`.
 * - Chiave chat 1-a-1 = `HKDF-SHA256(ECDH(my_priv, peer_pub), salt: "AeroMesh-Salt-v1", info: "AeroMesh-Msg-v1", 32 byte)`.
 * - AEAD = ChaCha20-Poly1305 (RFC 8439) serializzato come `[12B nonce][ciphertext][16B tag]`.
 */
object CryptoEngine {

    sealed class Failure(message: String) : Exception(message) {
        data class InvalidKeyLength(val expected: Int, val actual: Int) :
            Failure("invalid key length: expected $expected, actual $actual")
        data object InvalidSealedBox : Failure("invalid sealed box") {
            private fun readResolve(): Any = InvalidSealedBox
        }
        /** Equivalente di `CryptoKitError.authenticationFailure` (tag Poly1305 non valido). */
        data object AuthenticationFailure : Failure("authentication failure") {
            private fun readResolve(): Any = AuthenticationFailure
        }
        /** Equivalente di `CryptoKitError.incorrectParameterSize` (es. nonce non da 12 byte). */
        data class IncorrectParameterSize(val parameter: String) : Failure("incorrect parameter size: $parameter")
        /** Key agreement fallito (punto di ordine basso: shared secret tutto a zero), come CryptoKit. */
        data object KeyAgreementFailed : Failure("key agreement failed") {
            private fun readResolve(): Any = KeyAgreementFailed
        }
    }

    private val secureRandom = SecureRandom()

    internal fun randomBytes(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }

    internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    // MARK: - Identificatori

    /** `node_id` di una chiave pubblica X25519 raw (32 byte). */
    fun nodeID(forX25519PublicKey: ByteArray): ByteArray =
        sha256(forX25519PublicKey).copyOf(MeshProtocol.nodeIDLength)

    /** `recipient_id` di un gruppo: primi 8 byte di `SHA256(group_uuid_bytes)`. */
    fun groupRecipientID(groupID: UUID): ByteArray =
        sha256(groupID.rawBytes()).copyOf(MeshProtocol.nodeIDLength)

    /** 16 byte casuali (UUID v4 raw) per `msg_id`. */
    fun randomMessageID(): ByteArray = UUID.randomUUID().rawBytes()

    /** `group_secret_key` casuale a 32 byte. */
    fun randomGroupSecretKey(): ByteArray = randomBytes(MeshProtocol.symmetricKeyLength)

    // MARK: - Derivazione chiavi

    /** Chiave simmetrica della chat 1-a-1. E' simmetrica: A con pub(B) == B con pub(A). */
    @Throws(Failure::class)
    fun deriveDirectChatKey(privateKey: X25519PrivateKeyParameters, peerX25519PublicKeyRaw: ByteArray): SymmetricKey {
        if (peerX25519PublicKeyRaw.size != MeshProtocol.publicKeyLength) {
            throw Failure.InvalidKeyLength(MeshProtocol.publicKeyLength, peerX25519PublicKeyRaw.size)
        }
        val peerPublicKey = X25519PublicKeyParameters(peerX25519PublicKeyRaw, 0)
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val sharedSecret = ByteArray(agreement.agreementSize)
        try {
            // BouncyCastle lancia se lo shared secret e' tutto a zero (come CryptoKit).
            agreement.calculateAgreement(peerPublicKey, sharedSecret, 0)
        } catch (_: IllegalStateException) {
            throw Failure.KeyAgreementFailed
        }
        // HKDF-SHA256 con IKM = shared secret X25519 raw (32 byte).
        return SymmetricKey(hkdfSHA256(sharedSecret, MeshProtocol.hkdfSalt, MeshProtocol.hkdfInfo, MeshProtocol.symmetricKeyLength))
    }

    /** HKDF-SHA256 (RFC 5869), come `hkdfDerivedSymmetricKey(using: SHA256.self, ...)`. */
    fun hkdfSHA256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputByteCount: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(outputByteCount)
        generator.generateBytes(out, 0, outputByteCount)
        return out
    }

    /** Chiave simmetrica di gruppo da `group_secret_key` (32 byte raw). */
    @Throws(Failure::class)
    fun groupKey(fromSecret: ByteArray): SymmetricKey {
        if (fromSecret.size != MeshProtocol.symmetricKeyLength) {
            throw Failure.InvalidKeyLength(MeshProtocol.symmetricKeyLength, fromSecret.size)
        }
        return SymmetricKey(fromSecret)
    }

    /** Chiave nota della Lounge Pubblica di Cabina: `SHA256("AeroMesh-Public-Cabin-v1")`. */
    val publicCabinKey: SymmetricKey get() = SymmetricKey(sha256(MeshProtocol.publicCabinLabel))

    // MARK: - AEAD ChaCha20-Poly1305

    /** Cifra con nonce casuale a 12 byte. Output = `[nonce][ciphertext][tag]`. */
    @Throws(Failure::class)
    fun seal(plaintext: ByteArray, key: SymmetricKey): ByteArray =
        seal(plaintext, key, randomBytes(MeshProtocol.aeadNonceLength), null)

    /** Variante deterministica (nonce esplicito, AAD opzionale) usata nei test con i vettori RFC 8439. */
    @Throws(Failure::class)
    fun seal(plaintext: ByteArray, key: SymmetricKey, nonce: ByteArray, authenticating: ByteArray? = null): ByteArray {
        if (nonce.size != MeshProtocol.aeadNonceLength) throw Failure.IncorrectParameterSize("nonce")
        val cipher = chacha(forEncryption = true, key = key, nonce = nonce, aad = authenticating)
        val out = ByteArray(nonce.size + cipher.getOutputSize(plaintext.size))
        nonce.copyInto(out, 0)
        val written = cipher.processBytes(plaintext, 0, plaintext.size, out, nonce.size)
        cipher.doFinal(out, nonce.size + written)
        return out
    }

    /** Decifra `[nonce][ciphertext][tag]`. Lancia se il tag non verifica. */
    @Throws(Failure::class)
    fun open(combined: ByteArray, key: SymmetricKey, authenticating: ByteArray? = null): ByteArray {
        if (combined.size <= MeshProtocol.aeadNonceLength + MeshProtocol.aeadTagLength) throw Failure.InvalidSealedBox
        val nonce = combined.copyOfRange(0, MeshProtocol.aeadNonceLength)
        val cipher = chacha(forEncryption = false, key = key, nonce = nonce, aad = authenticating)
        val bodyLength = combined.size - MeshProtocol.aeadNonceLength
        val out = ByteArray(cipher.getOutputSize(bodyLength))
        return try {
            val written = cipher.processBytes(combined, MeshProtocol.aeadNonceLength, bodyLength, out, 0)
            val total = written + cipher.doFinal(out, written)
            if (total == out.size) out else out.copyOf(total)
        } catch (_: InvalidCipherTextException) {
            throw Failure.AuthenticationFailure
        }
    }

    private fun chacha(forEncryption: Boolean, key: SymmetricKey, nonce: ByteArray, aad: ByteArray?): ChaCha20Poly1305 {
        if (key.byteCount != MeshProtocol.symmetricKeyLength) {
            throw Failure.IncorrectParameterSize("key")
        }
        val cipher = ChaCha20Poly1305()
        cipher.init(forEncryption, AEADParameters(KeyParameter(key.unsafeBytes()), MeshProtocol.aeadTagLength * 8, nonce, aad))
        return cipher
    }

    // MARK: - Firma Ed25519

    /** Messaggio firmato: `msg_id || recipient_id || created_at_ms (Int64 Big-Endian) || encrypted_payload`. */
    fun signatureMessage(msgID: ByteArray, recipientID: ByteArray, createdAtMs: Long, encryptedPayload: ByteArray): ByteArray =
        msgID + recipientID + createdAtMs.bigEndianBytes + encryptedPayload

    fun sign(
        msgID: ByteArray,
        recipientID: ByteArray,
        createdAtMs: Long,
        encryptedPayload: ByteArray,
        with: Ed25519PrivateKeyParameters,
    ): ByteArray = sign(signatureMessage(msgID, recipientID, createdAtMs, encryptedPayload), with)

    fun verify(
        signature: ByteArray,
        msgID: ByteArray,
        recipientID: ByteArray,
        createdAtMs: Long,
        encryptedPayload: ByteArray,
        publicKeyRaw: ByteArray,
    ): Boolean {
        if (signature.size != MeshProtocol.signatureLength) return false
        return verify(signature, signatureMessage(msgID, recipientID, createdAtMs, encryptedPayload), publicKeyRaw)
    }

    /** Firma generica su byte arbitrari (usata per `PRO_PASS_GRANT`). Ed25519 deterministica (RFC 8032). */
    fun sign(message: ByteArray, with: Ed25519PrivateKeyParameters): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, with)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** Non lancia mai: chiave o firma malformate restituiscono `false`. */
    fun verify(signature: ByteArray, message: ByteArray, publicKeyRaw: ByteArray): Boolean {
        if (publicKeyRaw.size != MeshProtocol.publicKeyLength) return false
        if (signature.size != MeshProtocol.signatureLength) return false
        return try {
            val publicKey = Ed25519PublicKeyParameters(publicKeyRaw, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: RuntimeException) {
            false
        }
    }
}
