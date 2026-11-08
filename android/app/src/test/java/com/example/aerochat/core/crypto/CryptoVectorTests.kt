package com.example.aerochat.core.crypto

import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.bigEndianBytes
import com.example.aerochat.core.protocol.hexToByteArray
import com.example.aerochat.core.protocol.rawBytes
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.core.protocol.uuidFromRawBytes
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * Vettori di test ufficiali (RFC 8439, 5869, 7748, 8032) + regole AeroMesh.
 * Gli stessi vettori passano dalla suite iOS (CryptoKit): compatibilita' byte-for-byte.
 * Port di `CryptoVectorTests` in `AeroMeshTests.swift`.
 */
class CryptoVectorTests {

    private fun bytes(hex: String): ByteArray = requireNotNull(hex.hexToByteArray()) { "vettore esadecimale non valido" }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    // MARK: ChaCha20-Poly1305

    @Test
    fun chachaPolyMatchesRFC8439() {
        val key = SymmetricKey(bytes("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"))
        val nonce = bytes("070000004041424344454647")
        val aad = bytes("50515253c0c1c2c3c4c5c6c7")
        val plaintext = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
            .toByteArray(Charsets.UTF_8)
        val expectedCiphertext = bytes(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
                "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116",
        )
        val expectedTag = bytes("1ae10b594f09e26a7e902ecbd0600691")

        val combined = CryptoEngine.seal(plaintext, key, nonce, authenticating = aad)

        assertEquals(12 + plaintext.size + 16, combined.size)
        assertArrayEquals(nonce, combined.copyOfRange(0, 12))
        assertArrayEquals(expectedCiphertext, combined.copyOfRange(12, 12 + plaintext.size))
        assertArrayEquals(expectedTag, combined.copyOfRange(combined.size - 16, combined.size))
        assertArrayEquals(plaintext, CryptoEngine.open(combined, key, authenticating = aad))
    }

    @Test
    fun sealOpenRoundTripAndTamperDetection() {
        val key = SymmetricKey.random256()
        val plaintext = "Ci vediamo al gate B12".toByteArray(Charsets.UTF_8)
        val combined = CryptoEngine.seal(plaintext, key)
        assertEquals(MeshProtocol.aeadNonceLength + plaintext.size + MeshProtocol.aeadTagLength, combined.size)
        assertArrayEquals(plaintext, CryptoEngine.open(combined, key))

        val tampered = combined.copyOf()
        tampered[14] = (tampered[14].toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) { CryptoEngine.open(tampered, key) }

        val otherKey = SymmetricKey.random256()
        assertThrows(Exception::class.java) { CryptoEngine.open(combined, otherKey) }

        // Box troppo corto (<= nonce + tag) rifiutato prima di decifrare.
        assertThrows(CryptoEngine.Failure.InvalidSealedBox::class.java) { CryptoEngine.open(ByteArray(28), key) }
    }

    // MARK: HKDF / X25519

    @Test
    fun hkdfMatchesRFC5869() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = bytes("000102030405060708090a0b0c")
        val info = bytes("f0f1f2f3f4f5f6f7f8f9")
        val okm = CryptoEngine.hkdfSHA256(ikm, salt, info, 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
    }

    @Test
    fun x25519MatchesRFC7748AndNodeID() {
        val alicePrivate = X25519PrivateKeyParameters(bytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"), 0)
        val bobPublic = bytes("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")

        val alicePublic = alicePrivate.generatePublicKey().encoded
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alicePublic.toHex())
        assertEquals("300c9c9603b92a4b", CryptoEngine.nodeID(forX25519PublicKey = alicePublic).toHex())

        // La chiave di chat deve coincidere con HKDF(shared_secret RFC 7748 = 4a5d9d5b...) calcolato in modo indipendente.
        val sharedSecret = bytes("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        val expected = CryptoEngine.hkdfSHA256(sharedSecret, MeshProtocol.hkdfSalt, MeshProtocol.hkdfInfo, 32)
        val derived = CryptoEngine.deriveDirectChatKey(alicePrivate, bobPublic)
        assertArrayEquals(expected, derived.rawBytes)
        assertEquals(32, derived.rawBytes.size)
    }

    @Test
    fun directChatKeyIsSymmetric() {
        val alice = IdentityKeys.generate()
        val bob = IdentityKeys.generate()
        val aliceSide = alice.directChatKey(bob.x25519PublicKeyRaw)
        val bobSide = bob.directChatKey(alice.x25519PublicKeyRaw)
        assertArrayEquals(aliceSide.rawBytes, bobSide.rawBytes)
        val error = assertThrows(CryptoEngine.Failure.InvalidKeyLength::class.java) {
            alice.directChatKey(byteArrayOf(1, 2, 3, 4, 5))
        }
        assertEquals(CryptoEngine.Failure.InvalidKeyLength(expected = 32, actual = 5), error)
    }

    @Test
    fun x25519RejectsLowOrderPointLikeCryptoKit() {
        // Chiave pubblica tutta a zero: shared secret nullo, rifiutato.
        val alice = IdentityKeys.generate()
        assertThrows(CryptoEngine.Failure.KeyAgreementFailed::class.java) {
            alice.directChatKey(ByteArray(32))
        }
    }

    // MARK: Ed25519

    @Test
    fun ed25519MatchesRFC8032() {
        val key = Ed25519PrivateKeyParameters(bytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"), 0)
        val publicKey = key.generatePublicKey().encoded
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", publicKey.toHex())

        val rfcSignature = bytes(
            "ad71e81055d931934a578b3d03816a354ec48d439d838942e5d4cc69c95eda7863547216deef2633139b7884850fd6d105d429da3cecc3ec83c69b9760f0ca0b",
        )
        assertTrue(CryptoEngine.verify(signature = rfcSignature, message = ByteArray(0), publicKeyRaw = publicKey))
        assertFalse(CryptoEngine.verify(signature = rfcSignature, message = byteArrayOf(0x01), publicKeyRaw = publicKey))

        // Nota: `rfcSignature` (dalla suite iOS) e' una firma valida ma con nonce randomizzato (CryptoKit).
        // BouncyCastle firma in modo deterministico: la firma coincide byte per byte con RFC 8032 §7.1 Test 1.
        val deterministic = bytes(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        val signature = CryptoEngine.sign(message = ByteArray(0), with = key)
        assertEquals(64, signature.size)
        assertArrayEquals(deterministic, signature)
        assertTrue(CryptoEngine.verify(signature = signature, message = ByteArray(0), publicKeyRaw = publicKey))
    }

    @Test
    fun ed25519VerifyNeverThrowsOnMalformedInput() {
        val message = byteArrayOf(1, 2, 3)
        assertFalse(CryptoEngine.verify(signature = ByteArray(64), message = message, publicKeyRaw = ByteArray(5)))
        assertFalse(CryptoEngine.verify(signature = ByteArray(10), message = message, publicKeyRaw = ByteArray(32)))
        assertFalse(CryptoEngine.verify(signature = ByteArray(64) { 0xFF.toByte() }, message = message, publicKeyRaw = ByteArray(32) { 0xFF.toByte() }))
    }

    @Test
    fun envelopeSignatureCoversAllFields() {
        val identity = IdentityKeys.generate()
        val msgID = CryptoEngine.randomMessageID()
        val recipient = ByteArray(8) { 0xAB.toByte() }
        val payload = "cipher".toByteArray(Charsets.UTF_8)
        val createdAt = 1_700_000_000_000L

        val message = CryptoEngine.signatureMessage(msgID, recipient, createdAt, payload)
        assertEquals(16 + 8 + 8 + payload.size, message.size)
        assertArrayEquals(createdAt.bigEndianBytes, message.copyOfRange(24, 32))
        assertEquals("0000018bcfe56800", createdAt.bigEndianBytes.toHex())

        val signature = CryptoEngine.sign(msgID, recipient, createdAt, payload, with = identity.signingKey)
        assertEquals(64, signature.size)
        assertTrue(CryptoEngine.verify(signature, msgID, recipient, createdAt, payload, identity.ed25519PublicKeyRaw))
        assertFalse(CryptoEngine.verify(signature, msgID, recipient, createdAt + 1, payload, identity.ed25519PublicKeyRaw))
        assertFalse(CryptoEngine.verify(signature, msgID, recipient, createdAt, payload, IdentityKeys.generate().ed25519PublicKeyRaw))
    }

    // MARK: Identificatori di canale

    @Test
    fun publicCabinConstants() {
        val expected = sha256("AeroMesh-Public-Cabin-v1".toByteArray(Charsets.UTF_8))
        assertArrayEquals(expected, CryptoEngine.publicCabinKey.rawBytes)
        assertArrayEquals(ByteArray(8), MeshProtocol.loungeRecipientID)
    }

    @Test
    fun groupRecipientIDUsesRFC4122Bytes() {
        val groupID = UUID.fromString("12345678-1234-5678-1234-567812345678")
        assertEquals("12345678123456781234567812345678", groupID.rawBytes().toHex())
        assertEquals(groupID, uuidFromRawBytes(groupID.rawBytes()))
        val expected = sha256(groupID.rawBytes()).copyOf(8)
        assertArrayEquals(expected, CryptoEngine.groupRecipientID(groupID))
        assertEquals(32, CryptoEngine.randomGroupSecretKey().size)
    }
}
