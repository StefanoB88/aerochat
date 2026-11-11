package com.stefanobrandi.aerochat.core.protocol

import com.stefanobrandi.aerochat.core.crypto.CryptoEngine
import com.stefanobrandi.aerochat.core.crypto.IdentityKeys
import com.stefanobrandi.aerochat.core.crypto.SymmetricKey
import com.stefanobrandi.aerochat.proto.InnerPayload
import com.stefanobrandi.aerochat.proto.MeshEnvelope
import com.stefanobrandi.aerochat.proto.PacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Protocollo – Protobuf, LZ4, framing, envelope end-to-end (port di `ProtocolCodecTests.swift`). */
class ProtocolCodecTests {

    private fun range(from: Int, toInclusive: Int): ByteArray = ByteArray(toInclusive - from + 1) { (from + it).toByte() }

    // MARK: Protobuf wire format

    @Test
    fun meshEnvelopeWireBytesMatchHandComputedProto3() {
        val envelope = MeshEnvelope.newBuilder()
            .setMsgId(range(1, 16).toByteString())
            .setSenderNodeId(ByteArray(8) { 0xAA.toByte() }.toByteString())
            .setRecipientId(ByteArray(8).toByteString())
            .setTtl(5)
            .setHopCount(0) // default: non emesso
            .setCreatedAtMs(300) // varint 0xAC 0x02
            .setPacketType(PacketType.PUBLIC_LOUNGE) // 4
            .setIsLz4Compressed(true)
            .setEncryptedPayload(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte()).toByteString())
            .setEd25519Signature(ByteArray(64) { 0x7F }.toByteString())
            .build()

        val expected = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x0A, 0x10)); write(range(1, 16))
            write(byteArrayOf(0x12, 0x08)); write(ByteArray(8) { 0xAA.toByte() })
            write(byteArrayOf(0x1A, 0x08)); write(ByteArray(8))
            write(byteArrayOf(0x20, 0x05))
            write(byteArrayOf(0x30, 0xAC.toByte(), 0x02))
            write(byteArrayOf(0x38, 0x04))
            write(byteArrayOf(0x40, 0x01))
            write(byteArrayOf(0x4A, 0x03, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte()))
            write(byteArrayOf(0x52, 0x40)); write(ByteArray(64) { 0x7F })
        }.toByteArray()

        val serialized = MeshWire.serialize(envelope)
        assertArrayEquals(expected, serialized)
        assertEquals(envelope, MeshWire.parseEnvelope(serialized))
    }

    @Test
    fun innerPayloadRoundTripAllFields() {
        val payload = InnerPayload.newBuilder()
            .setText("Scambio posto 14C con 22A?")
            .setSenderNickname("Giulia ✈️")
            .setSenderX25519Pub(ByteArray(32) { 1 }.toByteString())
            .setSenderEd25519Pub(ByteArray(32) { 2 }.toByteString())
            .setReplyToMsgId("00112233445566778899aabbccddeeff")
            .setMediaId("img-1")
            .setMimeType("image/webp")
            .setTotalSizeBytes(65_000)
            .setTotalChunks(14)
            .setChunkIndex(3)
            .setChunkData(ByteArray(100) { 9 }.toByteString())
            .setBlurhash("LEHV6nWB2yk8pyo0adR*.7kCMdnj")
            .setAudioWaveform(ByteArray(32) { it.toByte() }.toByteString())
            .setDurationMs(4_200)
            .setExtraData(byteArrayOf(0xCA.toByte(), 0xFE.toByte()).toByteString())
            .build()

        val bytes = MeshWire.serialize(payload)
        val decoded = MeshWire.parseInnerPayload(bytes)
        assertEquals(payload, decoded)
        assertEquals(32, decoded.audioWaveform.size())
        assertArrayEquals(
            "00112233445566778899aabbccddeeff".hexToByteArray(),
            decoded.replyToMessageID,
        )
    }

    @Test
    fun packetTypeRawValues() {
        assertEquals(0, PacketType.CHAT_TEXT.number)
        assertEquals(1, PacketType.DELIVERY_ACK.number)
        assertEquals(2, PacketType.MEDIA_HEADER.number)
        assertEquals(3, PacketType.MEDIA_CHUNK.number)
        assertEquals(4, PacketType.PUBLIC_LOUNGE.number)
        assertEquals(5, PacketType.GROUP_KEY_GOSSIP.number)
        assertEquals(6, PacketType.PRO_PASS_GRANT.number)
        assertEquals(7, PacketType.GAME_MOVE.number)
        assertNull(PacketType.forNumber(42))
        // Valore sconosciuto sul filo (packet_type = 42) => UNRECOGNIZED, come `.UNRECOGNIZED(42)` su iOS.
        val parsed = MeshWire.parseEnvelope(byteArrayOf(0x38, 42))
        assertEquals(PacketType.UNRECOGNIZED, parsed.packetType)
        assertEquals(42, parsed.packetTypeValue)
    }

    // MARK: LZ4

    @Test
    fun lz4RoundTripAndHeader() {
        val original = "AeroMesh cabina 12A, ritardo 40 minuti. ".repeat(40).toByteArray(Charsets.UTF_8)
        val compressed = LZ4Codec.compress(original)
        assertNotNull(compressed)
        compressed!!
        assertTrue(compressed.size < original.size)
        assertArrayEquals(original.size.bigEndianBytes, compressed.copyOf(4))
        assertArrayEquals(original, LZ4Codec.decompress(compressed))
    }

    @Test
    fun lz4RejectsInvalidInput() {
        assertNull(LZ4Codec.compress(ByteArray(0)))
        assertThrows(LZ4Codec.Failure.TruncatedHeader::class.java) { LZ4Codec.decompress(byteArrayOf(0, 0, 1)) }
        val huge = 50_000_000.bigEndianBytes + byteArrayOf(0x00, 0x00)
        val error = assertThrows(LZ4Codec.Failure.DeclaredSizeTooLarge::class.java) { LZ4Codec.decompress(huge) }
        assertEquals(LZ4Codec.Failure.DeclaredSizeTooLarge(50_000_000), error)
    }

    @Test
    fun lz4DecodesIOSRawBlockAndRejectsMaliciousBlocks() {
        // Raw block LZ4 scritto a mano (formato COMPRESSION_LZ4_RAW di iOS) con header AeroMesh:
        // token 0x16 = 1 literal "a" + match offset 1 lungo 4+6; token 0x50 = 5 literal finali.
        val handWritten = 16.bigEndianBytes + byteArrayOf(0x16, 0x61, 0x01, 0x00, 0x50, 0x61, 0x61, 0x61, 0x61, 0x61)
        assertArrayEquals(ByteArray(16) { 0x61 }, LZ4Codec.decompress(handWritten))
        val literalsOnly = 5.bigEndianBytes + byteArrayOf(0x50) + "hello".toByteArray(Charsets.UTF_8)
        assertArrayEquals("hello".toByteArray(Charsets.UTF_8), LZ4Codec.decompress(literalsOnly))

        val original = ByteArray(1000) { 'a'.code.toByte() }
        val blob = LZ4Codec.compress(original)!!
        assertArrayEquals(original, LZ4Codec.decompress(blob))

        // Lunghezza dichiarata maggiore di quella reale => corrupted (niente letture fuori dai limiti).
        val lying = 2_000.bigEndianBytes + blob.copyOfRange(4, blob.size)
        assertThrows(LZ4Codec.Failure.CorruptedBlock::class.java) { LZ4Codec.decompress(lying) }
        // Lunghezza dichiarata minore => il blocco non entra nel buffer => corrupted.
        val shortDeclared = 10.bigEndianBytes + blob.copyOfRange(4, blob.size)
        assertThrows(LZ4Codec.Failure.CorruptedBlock::class.java) { LZ4Codec.decompress(shortDeclared) }
        // Byte casuali / match con offset oltre l'inizio del buffer.
        val garbage = 100.bigEndianBytes + byteArrayOf(0x0F, 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        assertThrows(LZ4Codec.Failure.CorruptedBlock::class.java) { LZ4Codec.decompress(garbage) }
        val badOffset = 100.bigEndianBytes + byteArrayOf(0x10, 'a'.code.toByte(), 0xFF.toByte(), 0x00)
        assertThrows(LZ4Codec.Failure.CorruptedBlock::class.java) { LZ4Codec.decompress(badOffset) }
        // Dimensione dichiarata zero o UInt32 > Int.MAX.
        assertThrows(LZ4Codec.Failure.DeclaredSizeTooLarge::class.java) { LZ4Codec.decompress(0.bigEndianBytes + byteArrayOf(0)) }
        assertThrows(LZ4Codec.Failure.DeclaredSizeTooLarge::class.java) { LZ4Codec.decompress((-1).bigEndianBytes + byteArrayOf(0)) }
    }

    @Test
    fun payloadCodecCompressionThreshold() {
        val key = SymmetricKey.random256()
        val identity = IdentityKeys.generate()

        val short = InnerPayload.newBuilder().setText("ok").build()
        val shortEncoded = PayloadCodec.encode(short, key)
        assertFalse(shortEncoded.isLZ4Compressed)
        assertEquals(short, PayloadCodec.decode(shortEncoded.encryptedPayload, isLZ4Compressed = false, key = key))

        val long = InnerPayloadFactory.text("taxi condiviso dall'aeroporto? ".repeat(30), identity, nickname = "Marco")
        assertTrue(MeshWire.serialize(long).size > MeshProtocol.lz4CompressionThreshold)
        val longEncoded = PayloadCodec.encode(long, key)
        assertTrue(longEncoded.isLZ4Compressed)
        assertEquals(long, PayloadCodec.decode(longEncoded.encryptedPayload, isLZ4Compressed = true, key = key))
    }

    // MARK: Framing L2CAP

    @Test
    fun frameCodecParsesFragmentedStream() {
        val first = "primo".toByteArray(Charsets.UTF_8)
        val second = ByteArray(700) { 0x42 }
        val framed = FrameCodec.frame(first) + FrameCodec.frame(second)
        assertArrayEquals(5.bigEndianBytes, framed.copyOf(4))

        val parser = FrameCodec.Parser()
        val received = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < framed.size) {
            val end = minOf(offset + 3, framed.size)
            received.addAll(parser.append(framed.copyOfRange(offset, end)))
            offset = end
        }
        assertEquals(2, received.size)
        assertArrayEquals(first, received[0])
        assertArrayEquals(second, received[1])
        assertEquals(0, parser.pendingByteCount)
    }

    @Test
    fun frameCodecRejectsInvalidFrames() {
        assertThrows(FrameCodec.Failure.EmptyFrame::class.java) { FrameCodec.frame(ByteArray(0)) }
        val parser = FrameCodec.Parser()
        val oversized = (MeshProtocol.maxFrameBytes + 1).bigEndianBytes
        val error = assertThrows(FrameCodec.Failure.FrameTooLarge::class.java) { parser.append(oversized) }
        assertEquals(FrameCodec.Failure.FrameTooLarge((MeshProtocol.maxFrameBytes + 1).toLong()), error)
        assertEquals(0, parser.pendingByteCount)
        // Lunghezza UInt32 enorme (> Int.MAX) trattata senza segno.
        val error2 = assertThrows(FrameCodec.Failure.FrameTooLarge::class.java) { FrameCodec.Parser().append((-1).bigEndianBytes) }
        assertEquals(FrameCodec.Failure.FrameTooLarge(0xFFFF_FFFFL), error2)
    }

    // MARK: End-to-end

    @Test
    fun endToEndEnvelopeBetweenTwoIdentities() {
        val alice = IdentityKeys.generate()
        val bob = IdentityKeys.generate()
        val aliceKey = alice.directChatKey(bob.x25519PublicKeyRaw)
        val bobKey = bob.directChatKey(alice.x25519PublicKeyRaw)

        val payload = InnerPayloadFactory.text("Atterrati! Taxi insieme?", alice, nickname = "Alice")
        val envelope = EnvelopeBuilder(alice).build(PacketType.CHAT_TEXT, recipientID = bob.nodeID, payload = payload, key = aliceKey)

        assertArrayEquals(alice.nodeID, envelope.senderNodeId.toByteArray())
        assertEquals(MeshProtocol.defaultTTL, envelope.ttl)
        assertEquals(0, envelope.hopCount)
        EnvelopeVerifier.validateShape(envelope)

        // Passaggio sul filo.
        val wire = FrameCodec.frame(MeshWire.serialize(envelope))
        val frames = FrameCodec.Parser().append(wire)
        val received = MeshWire.parseEnvelope(frames.first())
        assertEquals(envelope, received)

        val opened = EnvelopeVerifier.open(received, bobKey)
        assertEquals("Atterrati! Taxi insieme?", opened.text)
        assertArrayEquals(alice.ed25519PublicKeyRaw, opened.senderEd25519Pub.toByteArray())
        assertArrayEquals(alice.nodeID, opened.declaredSenderNodeID)
        assertTrue(EnvelopeVerifier.verifySignature(received, alice.ed25519PublicKeyRaw))

        // Chiave sbagliata => il tag Poly1305 fallisce.
        val mallory = IdentityKeys.generate()
        val wrongKey = mallory.directChatKey(alice.x25519PublicKeyRaw)
        assertThrows(Exception::class.java) { EnvelopeVerifier.open(received, wrongKey) }

        // Firma di un altro => badSignature.
        val forged = received.toBuilder()
            .setEd25519Signature(
                CryptoEngine.sign(
                    msgID = received.msgId.toByteArray(),
                    recipientID = received.recipientId.toByteArray(),
                    createdAtMs = received.createdAtMs,
                    encryptedPayload = received.encryptedPayload.toByteArray(),
                    with = mallory.signingKey,
                ).toByteString(),
            )
            .build()
        assertThrows(EnvelopeVerifier.Failure.BadSignature::class.java) { EnvelopeVerifier.open(forged, bobKey) }

        // TTL oltre il massimo viene limitato; recipient_id non a 8 byte rifiutato.
        val capped = EnvelopeBuilder(alice).build(PacketType.CHAT_TEXT, bob.nodeID, payload, aliceKey, ttl = 99)
        assertEquals(MeshProtocol.maxTTL, capped.ttl)
        val error = assertThrows(EnvelopeVerifier.Failure.Malformed::class.java) {
            EnvelopeBuilder(alice).build(PacketType.CHAT_TEXT, byteArrayOf(1, 2), payload, aliceKey)
        }
        assertEquals(EnvelopeVerifier.Failure.Malformed("recipient_id deve essere di 8 byte"), error)

        // ttl uint32 enorme dalla rete (negativo come Int Java) => malformato.
        val hugeTTL = received.toBuilder().setTtl(-1).build()
        assertThrows(EnvelopeVerifier.Failure.Malformed::class.java) { EnvelopeVerifier.validateShape(hugeTTL) }
    }

    @Test
    fun publicLoungeEnvelope() {
        val sender = IdentityKeys.generate()
        val payload = InnerPayloadFactory.text("Qualcuno ha un caricatore USB-C?", sender, nickname = "Seat 31F")
        val envelope = EnvelopeBuilder(sender).build(
            packetType = PacketType.PUBLIC_LOUNGE,
            recipientID = MeshProtocol.loungeRecipientID,
            payload = payload,
            key = CryptoEngine.publicCabinKey,
            anonymousSender = true,
        )
        assertArrayEquals(ByteArray(8), envelope.senderNodeId.toByteArray())
        val opened = EnvelopeVerifier.open(envelope, CryptoEngine.publicCabinKey)
        assertEquals("Seat 31F", opened.senderNickname)
    }

    @Test
    fun groupMemberConsistency() {
        val identity = IdentityKeys.generate()
        val member = GroupMember(identity, nickname = "Anna")
        assertTrue(member.isConsistent)
        val tampered = member.toBuilder().setNodeId(ByteArray(8).toByteString()).build()
        assertFalse(tampered.isConsistent)
        val peer = member.asPeerRecord
        assertArrayEquals(identity.nodeID, peer.nodeID)
        assertEquals("Anna", peer.nickname)
        assertEquals("Passeggero", member.toBuilder().setNickname("").build().asPeerRecord.nickname)
    }
}
