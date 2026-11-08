package com.example.aerochat.core.mesh

import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.PeerRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Conferme di lettura e profilo (port di `ProfileReadReceiptTests.swift`; `avatarPipeline`
 * appartiene a `core.media`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileReadReceiptTests : MeshTestCase() {

    @Test
    fun readReceiptMarksSenderMessageRead() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)

        val conversation = alice.directConversation(with = bob)
        val sent = alice.router.sendText("Hai visto il tramonto?", conversation = conversation)
        assertTrue(eventually { alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.deliveredAck })

        val bobConversation = bob.directConversation(with = alice)
        bob.router.markConversationRead(bobConversation)

        assertEquals(DeliveryStatus.read, bob.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus)
        assertTrue(eventually { alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.read })
        assertEquals(0, bob.database.conversations.fetch(id = bobConversation.id)?.unreadCount)
    }

    @Test
    fun readReceiptFromOtherPeerIsIgnored() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        val mallory = node("Mallory")
        TestNode.pair(alice, bob)
        TestNode.pair(alice, mallory)

        val conversation = alice.directConversation(with = bob)
        val sent = alice.router.sendText("Solo per Bob", conversation = conversation)
        val updated = alice.database.messages.markReadByRecipient(msgIDs = listOf(sent.msgID), readerNodeID = mallory.nodeID)
        assertTrue(updated.isEmpty())
        assertNotEquals(DeliveryStatus.read, alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus)
    }

    @Test
    fun groupReadReceiptRequiresAllMembers() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        val carol = node("Carol")
        TestNode.pair(alice, bob)
        TestNode.pair(alice, carol)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        FakeMeshNetwork.connect(alice.transport, carol.transport)

        val groupID = UUID.randomUUID()
        val key = CryptoEngine.randomGroupSecretKey()
        suspend fun peers(of: TestNode, others: List<TestNode>): List<PeerRecord> =
            others.map { requireValue(of.database.peers.fetch(nodeID = it.nodeID)) }
        val aliceGroup = alice.database.conversations.createGroup(
            id = groupID, title = "Volo", secretKey = key, members = peers(alice, listOf(bob, carol)),
        )
        val bobGroup = bob.database.conversations.createGroup(
            id = groupID, title = "Volo", secretKey = key, members = peers(bob, listOf(alice)),
        )
        val carolGroup = carol.database.conversations.createGroup(
            id = groupID, title = "Volo", secretKey = key, members = peers(carol, listOf(alice)),
        )

        val sent = alice.router.sendText("Tutti a bordo?", conversation = aliceGroup)
        assertTrue(eventually { carol.database.messages.fetch(msgID = sent.msgID) != null })
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID) != null })
        assertTrue(eventually { alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.deliveredAck })

        // Bob legge: Alice lo registra ma la spunta resta grigia (manca Carol).
        bob.router.markConversationRead(bobGroup)
        assertTrue(eventually { alice.database.messages.readerNodeIDs(msgID = sent.msgID).map { it.toHex() } == listOf(bob.nodeID.toHex()) })
        assertEquals(DeliveryStatus.deliveredAck, alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus)

        // Carol legge: ora hanno letto tutti, spunta blu.
        carol.router.markConversationRead(carolGroup)
        assertTrue(eventually { alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.read })
        assertEquals(
            setOf(bob.nodeID.toHex(), carol.nodeID.toHex()),
            alice.database.messages.readerNodeIDs(msgID = sent.msgID).map { it.toHex() }.toSet(),
        )
    }

    @Test
    fun profileUpdatePropagates() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)

        val avatar = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 fake".toByteArray(Charsets.UTF_8)
        val profile = LocalProfile(
            nickname = "Alice in volo", avatarColorHex = "EC4899", avatarImage = avatar,
            updatedAtMs = System.currentTimeMillis(),
        )
        alice.router.setProfile(profile, announce = true)

        assertTrue(eventually { bob.database.peers.fetch(nodeID = alice.nodeID)?.avatarImage?.contentEquals(avatar) == true })
        val peer = requireValue(bob.database.peers.fetch(nodeID = alice.nodeID))
        assertEquals("Alice in volo", peer.nickname)
        assertEquals("EC4899", peer.avatarColorHex)
        assertEquals("Alice in volo", bob.directConversation(with = alice).title)

        // Una versione piu' vecchia (es. ritrasmessa in ritardo) non sovrascrive quella nota.
        val stale = bob.database.peers.applyProfile(
            nodeID = alice.nodeID, nickname = "Vecchio", avatarColorHex = null,
            avatarImage = null, updatedAtMs = profile.updatedAtMs - 1,
        )
        assertFalse(stale)
        // Il nickname riferito da terzi (gossip) non scavalca quello annunciato dal profilo.
        bob.database.peers.upsert(
            PeerRecord(
                nodeID = alice.nodeID, x25519Pub = alice.identity.x25519PublicKeyRaw,
                ed25519Pub = alice.identity.ed25519PublicKeyRaw, nickname = "Gossip",
            ),
        )
        assertEquals("Alice in volo", bob.database.peers.fetch(nodeID = alice.nodeID)?.nickname)
    }
}
