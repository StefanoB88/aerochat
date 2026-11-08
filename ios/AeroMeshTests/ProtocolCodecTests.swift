import Testing
import Foundation
import CryptoKit
@testable import MyApp

@Suite("Protocollo – Protobuf, LZ4, framing, envelope end-to-end")
struct ProtocolCodecTests {

    // MARK: Protobuf wire format

    @Test("MeshEnvelope serializza esattamente i byte proto3 attesi (tag/varint/length-delimited)")
    func meshEnvelopeWireBytesMatchHandComputedProto3() throws {
        var envelope = MeshEnvelope()
        envelope.msgID = Data(1...16)
        envelope.senderNodeID = Data(repeating: 0xAA, count: 8)
        envelope.recipientID = Data(repeating: 0x00, count: 8)
        envelope.ttl = 5
        envelope.hopCount = 0                 // default: non emesso
        envelope.createdAtMs = 300            // varint 0xAC 0x02
        envelope.packetType = .publicLounge   // 4
        envelope.isLz4Compressed = true
        envelope.encryptedPayload = Data([0xDE, 0xAD, 0xBE])
        envelope.ed25519Signature = Data(repeating: 0x7F, count: 64)

        var expected = Data()
        expected.append(contentsOf: [0x0A, 0x10]); expected.append(Data(1...16))
        expected.append(contentsOf: [0x12, 0x08]); expected.append(Data(repeating: 0xAA, count: 8))
        expected.append(contentsOf: [0x1A, 0x08]); expected.append(Data(repeating: 0x00, count: 8))
        expected.append(contentsOf: [0x20, 0x05])
        expected.append(contentsOf: [0x30, 0xAC, 0x02])
        expected.append(contentsOf: [0x38, 0x04])
        expected.append(contentsOf: [0x40, 0x01])
        expected.append(contentsOf: [0x4A, 0x03, 0xDE, 0xAD, 0xBE])
        expected.append(contentsOf: [0x52, 0x40]); expected.append(Data(repeating: 0x7F, count: 64))

        let serialized = try MeshWire.serialize(envelope)
        #expect(serialized == expected)
        #expect(try MeshWire.parseEnvelope(serialized) == envelope)
    }

    @Test("InnerPayload: roundtrip di tutti i 15 campi")
    func innerPayloadRoundTripAllFields() throws {
        var payload = InnerPayload()
        payload.text = "Scambio posto 14C con 22A?"
        payload.senderNickname = "Giulia ✈️"
        payload.senderX25519Pub = Data(repeating: 1, count: 32)
        payload.senderEd25519Pub = Data(repeating: 2, count: 32)
        payload.replyToMsgID = "00112233445566778899aabbccddeeff"
        payload.mediaID = "img-1"
        payload.mimeType = "image/webp"
        payload.totalSizeBytes = 65_000
        payload.totalChunks = 14
        payload.chunkIndex = 3
        payload.chunkData = Data(repeating: 9, count: 100)
        payload.blurhash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"
        payload.audioWaveform = Data(0..<32)
        payload.durationMs = 4_200
        payload.extraData = Data([0xCA, 0xFE])

        let bytes = try MeshWire.serialize(payload)
        let decoded = try MeshWire.parseInnerPayload(bytes)
        #expect(decoded == payload)
        #expect(decoded.audioWaveform.count == 32)
    }

    @Test("PacketType: valori numerici allineati al .proto")
    func packetTypeRawValues() {
        #expect(PacketType.chatText.rawValue == 0)
        #expect(PacketType.deliveryAck.rawValue == 1)
        #expect(PacketType.mediaHeader.rawValue == 2)
        #expect(PacketType.mediaChunk.rawValue == 3)
        #expect(PacketType.publicLounge.rawValue == 4)
        #expect(PacketType.groupKeyGossip.rawValue == 5)
        #expect(PacketType.proPassGrant.rawValue == 6)
        #expect(PacketType.gameMove.rawValue == 7)
        #expect(PacketType(rawValue: 42) == .UNRECOGNIZED(42))
    }

    // MARK: LZ4

    @Test("LZ4: header UInt32 BE con lunghezza originale + raw block, roundtrip")
    func lz4RoundTripAndHeader() throws {
        let original = Data(String(repeating: "AeroMesh cabina 12A, ritardo 40 minuti. ", count: 40).utf8)
        let compressed = try #require(LZ4Codec.compress(original))
        #expect(compressed.count < original.count)
        #expect(compressed.prefix(4) == UInt32(original.count).bigEndianBytes)
        #expect(try LZ4Codec.decompress(compressed) == original)
    }

    @Test("LZ4: input vuoto non comprimibile, header troncato e lunghezze dichiarate assurde vengono rifiutati")
    func lz4RejectsInvalidInput() {
        #expect(LZ4Codec.compress(Data()) == nil)
        #expect(throws: LZ4Codec.Failure.truncatedHeader) { try LZ4Codec.decompress(Data([0, 0, 1])) }
        var huge = UInt32(50_000_000).bigEndianBytes
        huge.append(contentsOf: [0x00, 0x00])
        #expect(throws: LZ4Codec.Failure.declaredSizeTooLarge(50_000_000)) { try LZ4Codec.decompress(huge) }
    }

    @Test("PayloadCodec comprime solo sopra i 128 byte e decifra in entrambi i casi")
    func payloadCodecCompressionThreshold() throws {
        let key = SymmetricKey(size: .bits256)
        let identity = IdentityKeys.generate()

        var short = InnerPayload()
        short.text = "ok"
        let shortEncoded = try PayloadCodec.encode(short, key: key)
        #expect(shortEncoded.isLZ4Compressed == false)
        #expect(try PayloadCodec.decode(encryptedPayload: shortEncoded.encryptedPayload, isLZ4Compressed: false, key: key) == short)

        let long = InnerPayload.text(String(repeating: "taxi condiviso dall'aeroporto? ", count: 30), identity: identity, nickname: "Marco")
        #expect(try MeshWire.serialize(long).count > MeshProtocol.lz4CompressionThreshold)
        let longEncoded = try PayloadCodec.encode(long, key: key)
        #expect(longEncoded.isLZ4Compressed == true)
        #expect(try PayloadCodec.decode(encryptedPayload: longEncoded.encryptedPayload, isLZ4Compressed: true, key: key) == long)
    }

    // MARK: Framing L2CAP

    @Test("FrameCodec: prefisso UInt32 BE e parsing di uno stream frammentato arbitrariamente")
    func frameCodecParsesFragmentedStream() throws {
        let first = Data("primo".utf8)
        let second = Data(repeating: 0x42, count: 700)
        let framed = try FrameCodec.frame(first) + FrameCodec.frame(second)
        #expect(framed.prefix(4) == UInt32(5).bigEndianBytes)

        var parser = FrameCodec.Parser()
        var received: [Data] = []
        var offset = 0
        while offset < framed.count {
            let end = min(offset + 3, framed.count)
            received.append(contentsOf: try parser.append(framed[offset..<end]))
            offset = end
        }
        #expect(received == [first, second])
        #expect(parser.pendingByteCount == 0)
    }

    @Test("FrameCodec: frame vuoti o oltre 1 MB vengono rifiutati")
    func frameCodecRejectsInvalidFrames() {
        #expect(throws: FrameCodec.Failure.emptyFrame) { try FrameCodec.frame(Data()) }
        var parser = FrameCodec.Parser()
        let oversized = UInt32(MeshProtocol.maxFrameBytes + 1).bigEndianBytes
        #expect(throws: FrameCodec.Failure.frameTooLarge(MeshProtocol.maxFrameBytes + 1)) { try parser.append(oversized) }
    }

    // MARK: End-to-end

    @Test("Envelope end-to-end: A cifra e firma per B, B decifra con la propria chiave e verifica la firma")
    func endToEndEnvelopeBetweenTwoIdentities() throws {
        let alice = IdentityKeys.generate()
        let bob = IdentityKeys.generate()
        let aliceKey = try alice.directChatKey(peerX25519PublicKeyRaw: bob.x25519PublicKeyRaw)
        let bobKey = try bob.directChatKey(peerX25519PublicKeyRaw: alice.x25519PublicKeyRaw)

        let payload = InnerPayload.text("Atterrati! Taxi insieme?", identity: alice, nickname: "Alice")
        let envelope = try EnvelopeBuilder(identity: alice).build(packetType: .chatText, recipientID: bob.nodeID, payload: payload, key: aliceKey)

        #expect(envelope.senderNodeID == alice.nodeID)
        #expect(envelope.ttl == MeshProtocol.defaultTTL)
        #expect(envelope.hopCount == 0)
        try EnvelopeVerifier.validateShape(envelope)

        // Passaggio sul filo.
        let wire = try FrameCodec.frame(try MeshWire.serialize(envelope))
        var parser = FrameCodec.Parser()
        let frames = try parser.append(wire)
        let received = try MeshWire.parseEnvelope(try #require(frames.first))
        #expect(received == envelope)

        let opened = try EnvelopeVerifier.open(received, key: bobKey)
        #expect(opened.text == "Atterrati! Taxi insieme?")
        #expect(opened.senderEd25519Pub == alice.ed25519PublicKeyRaw)
        #expect(EnvelopeVerifier.verifySignature(received, senderEd25519PublicKeyRaw: alice.ed25519PublicKeyRaw))

        // Chiave sbagliata => il tag Poly1305 fallisce.
        let mallory = IdentityKeys.generate()
        let wrongKey = try mallory.directChatKey(peerX25519PublicKeyRaw: alice.x25519PublicKeyRaw)
        #expect(throws: (any Error).self) { try EnvelopeVerifier.open(received, key: wrongKey) }

        // Firma di un altro => badSignature.
        var forged = received
        forged.ed25519Signature = try CryptoEngine.sign(msgID: received.msgID, recipientID: received.recipientID,
                                                         createdAtMs: received.createdAtMs, encryptedPayload: received.encryptedPayload,
                                                         with: mallory.signingKey)
        #expect(throws: EnvelopeVerifier.Failure.badSignature) { try EnvelopeVerifier.open(forged, key: bobKey) }

        // TTL oltre il massimo viene limitato; recipient_id non a 8 byte rifiutato.
        let capped = try EnvelopeBuilder(identity: alice).build(packetType: .chatText, recipientID: bob.nodeID, payload: payload, key: aliceKey, ttl: 99)
        #expect(capped.ttl == MeshProtocol.maxTTL)
        #expect(throws: EnvelopeVerifier.Failure.malformed("recipient_id deve essere di 8 byte")) {
            try EnvelopeBuilder(identity: alice).build(packetType: .chatText, recipientID: Data([1, 2]), payload: payload, key: aliceKey)
        }
    }

    @Test("Lounge: envelope anonimo cifrato con la chiave di canale e leggibile da chiunque")
    func publicLoungeEnvelope() throws {
        let sender = IdentityKeys.generate()
        let payload = InnerPayload.text("Qualcuno ha un caricatore USB-C?", identity: sender, nickname: "Seat 31F")
        let envelope = try EnvelopeBuilder(identity: sender).build(packetType: .publicLounge,
                                                                   recipientID: MeshProtocol.loungeRecipientID,
                                                                   payload: payload,
                                                                   key: CryptoEngine.publicCabinKey,
                                                                   anonymousSender: true)
        #expect(envelope.senderNodeID == Data(repeating: 0, count: 8))
        let opened = try EnvelopeVerifier.open(envelope, key: CryptoEngine.publicCabinKey)
        #expect(opened.senderNickname == "Seat 31F")
    }
}
