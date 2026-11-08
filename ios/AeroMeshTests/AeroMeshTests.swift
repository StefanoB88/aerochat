import Testing
import Foundation
import CryptoKit
@testable import AeroChat

/// Vettori di test ufficiali (RFC 8439, 5869, 7748, 8032) + regole AeroMesh.
/// Gli stessi vettori vanno fatti passare dalla suite Android (BouncyCastle) per certificare
/// la compatibilita' byte-for-byte.
@Suite("CryptoEngine – vettori RFC e contratto AeroMesh")
struct CryptoVectorTests {

    private func bytes(_ hex: String) throws -> Data {
        try #require(Data(hexString: hex), "vettore esadecimale non valido")
    }

    // MARK: ChaCha20-Poly1305

    @Test("ChaCha20-Poly1305 riproduce il vettore RFC 8439 §2.8.2 e il layout [nonce][ct][tag]")
    func chachaPolyMatchesRFC8439() throws {
        let key = SymmetricKey(data: try bytes("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"))
        let nonce = try bytes("070000004041424344454647")
        let aad = try bytes("50515253c0c1c2c3c4c5c6c7")
        let plaintext = Data("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".utf8)
        let expectedCiphertext = try bytes(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
            "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
            "3ff4def08e4b7a9de576d26586cec64b6116"
        )
        let expectedTag = try bytes("1ae10b594f09e26a7e902ecbd0600691")

        let combined = try CryptoEngine.seal(plaintext, key: key, nonce: nonce, authenticating: aad)

        #expect(combined.count == 12 + plaintext.count + 16)
        #expect(combined.prefix(12) == nonce)
        #expect(combined.dropFirst(12).prefix(plaintext.count) == expectedCiphertext)
        #expect(combined.suffix(16) == expectedTag)
        #expect(try CryptoEngine.open(combined, key: key, authenticating: aad) == plaintext)
    }

    @Test("Seal casuale: nonce 12 byte, tag 16 byte, roundtrip e rilevamento manomissione")
    func sealOpenRoundTripAndTamperDetection() throws {
        let key = SymmetricKey(size: .bits256)
        let plaintext = Data("Ci vediamo al gate B12".utf8)
        let combined = try CryptoEngine.seal(plaintext, key: key)
        #expect(combined.count == MeshProtocol.aeadNonceLength + plaintext.count + MeshProtocol.aeadTagLength)
        #expect(try CryptoEngine.open(combined, key: key) == plaintext)

        var tampered = combined
        tampered[tampered.index(tampered.startIndex, offsetBy: 14)] ^= 0x01
        #expect(throws: (any Error).self) { try CryptoEngine.open(tampered, key: key) }

        let otherKey = SymmetricKey(size: .bits256)
        #expect(throws: (any Error).self) { try CryptoEngine.open(combined, key: otherKey) }
    }

    // MARK: HKDF / X25519

    @Test("HKDF-SHA256 riproduce RFC 5869 Test Case 1")
    func hkdfMatchesRFC5869() throws {
        let ikm = Data(repeating: 0x0b, count: 22)
        let salt = try bytes("000102030405060708090a0b0c")
        let info = try bytes("f0f1f2f3f4f5f6f7f8f9")
        let okm = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info, outputByteCount: 42)
        #expect(okm.rawBytes.hexString == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    }

    @Test("X25519 riproduce RFC 7748 §6.1 e il node_id e' SHA256(pub)[0..<8]")
    func x25519MatchesRFC7748AndNodeID() throws {
        let alicePrivate = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: try bytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        let bobPublic = try bytes("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")

        #expect(alicePrivate.publicKey.rawRepresentation.hexString == "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        #expect(CryptoEngine.nodeID(forX25519PublicKey: alicePrivate.publicKey.rawRepresentation).hexString == "300c9c9603b92a4b")

        // La chiave di chat deve coincidere con HKDF(shared_secret RFC 7748 = 4a5d9d5b...) calcolato in modo indipendente.
        let sharedSecret = try bytes("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        let expected = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: sharedSecret),
                                              salt: MeshProtocol.hkdfSalt,
                                              info: MeshProtocol.hkdfInfo,
                                              outputByteCount: 32)
        let derived = try CryptoEngine.deriveDirectChatKey(privateKey: alicePrivate, peerX25519PublicKeyRaw: bobPublic)
        #expect(derived.rawBytes == expected.rawBytes)
        #expect(derived.rawBytes.count == 32)
    }

    @Test("La chiave 1-a-1 e' simmetrica tra A e B")
    func directChatKeyIsSymmetric() throws {
        let alice = IdentityKeys.generate()
        let bob = IdentityKeys.generate()
        let aliceSide = try alice.directChatKey(peerX25519PublicKeyRaw: bob.x25519PublicKeyRaw)
        let bobSide = try bob.directChatKey(peerX25519PublicKeyRaw: alice.x25519PublicKeyRaw)
        #expect(aliceSide.rawBytes == bobSide.rawBytes)
        #expect(throws: CryptoEngine.Failure.invalidKeyLength(expected: 32, actual: 5)) {
            try alice.directChatKey(peerX25519PublicKeyRaw: Data([1, 2, 3, 4, 5]))
        }
    }

    // MARK: Ed25519

    @Test("Ed25519 RFC 8032 §7.1 Test 1: chiave pubblica derivata e firma deterministica (BouncyCastle) verificabile")
    func ed25519MatchesRFC8032() throws {
        let key = try Curve25519.Signing.PrivateKey(rawRepresentation: try bytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))
        #expect(key.publicKey.rawRepresentation.hexString == "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")

        // Firma deterministica RFC 8032 (quella che produce BouncyCastle su Android): CryptoKit deve verificarla.
        let rfcSignature = try bytes("ad71e81055d931934a578b3d03816a354ec48d439d838942e5d4cc69c95eda7863547216deef2633139b7884850fd6d105d429da3cecc3ec83c69b9760f0ca0b")
        #expect(CryptoEngine.verify(signature: rfcSignature, message: Data(), publicKeyRaw: key.publicKey.rawRepresentation))
        #expect(!CryptoEngine.verify(signature: rfcSignature, message: Data([0x01]), publicKeyRaw: key.publicKey.rawRepresentation))

        // CryptoKit su iOS randomizza il nonce della firma (contromisura side-channel): la firma non e'
        // byte-identica a quella RFC ma e' sempre valida sotto la stessa chiave pubblica, quindi Android la verifica.
        let signature = try CryptoEngine.sign(message: Data(), with: key)
        #expect(signature.count == 64)
        #expect(CryptoEngine.verify(signature: signature, message: Data(), publicKeyRaw: key.publicKey.rawRepresentation))
    }

    @Test("Firma envelope su msg_id||recipient_id||created_at_ms(BE)||payload e verifica manomissioni")
    func envelopeSignatureCoversAllFields() throws {
        let identity = IdentityKeys.generate()
        let msgID = CryptoEngine.randomMessageID()
        let recipient = Data(repeating: 0xAB, count: 8)
        let payload = Data("cipher".utf8)
        let createdAt: Int64 = 1_700_000_000_000

        let message = CryptoEngine.signatureMessage(msgID: msgID, recipientID: recipient, createdAtMs: createdAt, encryptedPayload: payload)
        #expect(message.count == 16 + 8 + 8 + payload.count)
        #expect(message.dropFirst(24).prefix(8) == createdAt.bigEndianBytes)
        #expect(createdAt.bigEndianBytes.hexString == "0000018bcfe56800")

        let signature = try CryptoEngine.sign(msgID: msgID, recipientID: recipient, createdAtMs: createdAt, encryptedPayload: payload, with: identity.signingKey)
        #expect(signature.count == 64)
        #expect(CryptoEngine.verify(signature: signature, msgID: msgID, recipientID: recipient, createdAtMs: createdAt, encryptedPayload: payload, publicKeyRaw: identity.ed25519PublicKeyRaw))
        #expect(!CryptoEngine.verify(signature: signature, msgID: msgID, recipientID: recipient, createdAtMs: createdAt + 1, encryptedPayload: payload, publicKeyRaw: identity.ed25519PublicKeyRaw))
        #expect(!CryptoEngine.verify(signature: signature, msgID: msgID, recipientID: recipient, createdAtMs: createdAt, encryptedPayload: payload, publicKeyRaw: IdentityKeys.generate().ed25519PublicKeyRaw))
    }

    // MARK: Identificatori di canale

    @Test("Chiave Lounge = SHA256(\"AeroMesh-Public-Cabin-v1\") e recipient_id = 8 zeri")
    func publicCabinConstants() {
        let expected = Data(SHA256.hash(data: Data("AeroMesh-Public-Cabin-v1".utf8)))
        #expect(CryptoEngine.publicCabinKey.rawBytes == expected)
        #expect(MeshProtocol.loungeRecipientID == Data(repeating: 0, count: 8))
    }

    @Test("recipient_id di gruppo = SHA256(uuid RFC 4122 bytes)[0..<8]")
    func groupRecipientIDUsesRFC4122Bytes() throws {
        let groupID = try #require(UUID(uuidString: "12345678-1234-5678-1234-567812345678"))
        #expect(groupID.rawBytes.hexString == "12345678123456781234567812345678")
        #expect(UUID(rawBytes: groupID.rawBytes) == groupID)
        let expected = Data(SHA256.hash(data: groupID.rawBytes).prefix(8))
        #expect(CryptoEngine.groupRecipientID(groupID: groupID) == expected)
        #expect(CryptoEngine.randomGroupSecretKey().count == 32)
    }
}
