package com.example.aerochat.core.mesh

import com.example.aerochat.core.protocol.EnvelopeBuilder
import com.example.aerochat.core.protocol.InnerPayloadFactory
import com.example.aerochat.core.protocol.MeshWire
import com.example.aerochat.proto.PacketType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robustezza contro input malevoli (parte router di `StabilityTests.swift`; i casi Opus e
 * L2CAPConnection appartengono ai package `core.media` / `core.transport`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StabilityTests : MeshTestCase() {

    @Test
    fun extremeTimestampIsRejected() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        val (_, linkB) = FakeMeshNetwork.connect(alice.transport, bob.transport)

        val key = alice.identity.directChatKey(peerX25519PublicKeyRaw = bob.identity.x25519PublicKeyRaw)
        val payload = InnerPayloadFactory.text("boom", identity = alice.identity, nickname = "Alice")
        for (timestamp in listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L)) {
            val envelope = EnvelopeBuilder(alice.identity)
                .build(packetType = PacketType.CHAT_TEXT, recipientID = bob.nodeID, payload = payload, key = key)
                .toBuilder()
                .setCreatedAtMs(timestamp)
                .build()
            bob.router.receive(MeshWire.serialize(envelope), from = linkB)
        }
        assertEquals(4, bob.router.stats.malformed)
        assertEquals(0, bob.router.stats.delivered)
    }
}
