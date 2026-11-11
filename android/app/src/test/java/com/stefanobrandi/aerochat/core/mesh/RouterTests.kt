package com.stefanobrandi.aerochat.core.mesh

import com.stefanobrandi.aerochat.core.crypto.CryptoEngine
import com.stefanobrandi.aerochat.core.protocol.EnvelopeBuilder
import com.stefanobrandi.aerochat.core.protocol.InnerPayloadFactory
import com.stefanobrandi.aerochat.core.protocol.MeshWire
import com.stefanobrandi.aerochat.core.protocol.toByteString
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.core.transport.LinkID
import com.stefanobrandi.aerochat.core.transport.LinkRole
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.ConversationType
import com.stefanobrandi.aerochat.data.records.DeliveryStatus
import com.stefanobrandi.aerochat.proto.PacketType
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MeshRouterActor – flooding, relay, store-and-forward, ack, gossip (port di `RouterTests.swift`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouterTests : MeshTestCase() {

    // MARK: - Cache anti-loop

    @Test
    fun seenCacheBehaviour() {
        val cache = SeenPacketCache(capacity = 16, lifetime = 10_000)
        val first = CryptoEngine.randomMessageID()
        assertTrue(cache.insertIfNew(first, nowMs = 0))
        assertFalse(cache.insertIfNew(first, nowMs = 5_000))
        assertTrue("scaduto: torna nuovo", cache.insertIfNew(first, nowMs = 10_001))

        repeat(40) { cache.insertIfNew(CryptoEngine.randomMessageID(), nowMs = 20_000) }
        assertTrue(cache.count <= 16)
        assertFalse("il piu' vecchio e' stato evitto", cache.contains(first, nowMs = 20_000))

        cache.purgeExpired(nowMs = 100_000)
        assertEquals(0, cache.count)
    }

    // MARK: - Consegna diretta

    @Test
    fun directTextDeliveryAndAck() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)

        val conversation = alice.directConversation(with = bob)
        val sent = alice.router.sendText("Ci vediamo al gate B12", conversation = conversation)
        assertEquals(DeliveryStatus.sentMesh, sent.deliveryStatus)

        val bobConversation = bob.directConversation(with = alice)
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID) != null })
        val received = requireValue(bob.database.messages.fetch(msgID = sent.msgID))
        assertEquals("Ci vediamo al gate B12", received.text)
        assertEquals(bobConversation.id, received.conversationID)
        assertEquals(1, received.hopCountReceived)
        assertFalse(received.isOutgoing)
        assertEquals("Alice", received.senderNickname)
        assertEquals(listOf(sent.msgID.toHex()), bob.notifier.notified.map { it.msgID.toHex() })

        assertTrue(eventually { alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.deliveredAck })
        assertEquals(Reachability.Direct(rssi = -60), alice.router.reachability(of = bob.nodeID))
        assertEquals(listOf(bob.nodeID.toHex()), alice.router.neighbors().map { it.nodeID.toHex() })
    }

    // MARK: - Relay

    @Test
    fun relayThroughStrangerBridge() = meshTest {
        val alice = node("Alice")
        val bridge = node("Ponte")
        val carol = node("Carol")
        TestNode.pair(alice, carol)
        FakeMeshNetwork.connect(alice.transport, bridge.transport)
        FakeMeshNetwork.connect(bridge.transport, carol.transport)

        val conversation = alice.directConversation(with = carol)
        val sent = alice.router.sendText("Taxi insieme?", conversation = conversation)

        assertTrue(eventually { carol.database.messages.fetch(msgID = sent.msgID) != null })
        val received = requireValue(carol.database.messages.fetch(msgID = sent.msgID))
        assertEquals(2, received.hopCountReceived)
        assertTrue(eventually { alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.deliveredAck })

        assertNull(bridge.database.messages.fetch(msgID = sent.msgID))
        assertTrue("messaggio + ack", eventually { bridge.router.stats.relayed >= 2 })
        assertEquals(0, bridge.router.stats.delivered)
        assertEquals(Reachability.ViaBridge(hops = 2), carol.router.reachability(of = alice.nodeID))
    }

    @Test
    fun duplicateFramesAreDropped() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        val (_, linkOnBob) = FakeMeshNetwork.connect(alice.transport, bob.transport)

        val conversation = alice.directConversation(with = bob)
        val sent = alice.router.sendText("eco", conversation = conversation)
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID) != null })

        val frame = requireValue(alice.transport.sent.firstOrNull()?.frame)
        bob.router.receive(frame, from = linkOnBob)
        bob.router.receive(frame, from = linkOnBob)
        assertTrue(bob.router.stats.duplicates >= 2)
        assertEquals(1, bob.database.messages.fetch(conversationID = bob.directConversation(with = alice).id).size)
    }

    @Test
    fun ttlExhaustedStopsRelay() = meshTest {
        val alice = node("Alice")
        val bridge = node("Ponte")
        val carol = node("Carol")
        TestNode.pair(alice, carol)
        FakeMeshNetwork.connect(alice.transport, bridge.transport)
        FakeMeshNetwork.connect(bridge.transport, carol.transport)

        val conversation = alice.directConversation(with = carol)
        val payload = InnerPayloadFactory.text("non deve passare", identity = alice.identity, nickname = "Alice")
        val result = alice.router.sendPacket(type = PacketType.CHAT_TEXT, payload = payload, conversation = conversation, ttl = 1)
        assertTrue(result.reached)
        delay(300)
        assertNull(carol.database.messages.fetch(msgID = result.msgID))
        assertEquals(0, bridge.router.stats.relayed)
    }

    @Test
    fun mediaRelayPolicy() = meshTest {
        val alice = node("Alice")
        val bridge = node("Ponte")
        val carol = node("Carol")
        TestNode.pair(alice, carol)
        FakeMeshNetwork.connect(alice.transport, bridge.transport)
        FakeMeshNetwork.connect(bridge.transport, carol.transport)

        val chunk = InnerPayloadFactory.signedBuilder(alice.identity, nickname = "Alice")
            .setMediaId("img-1")
            .setChunkIndex(0)
            .setTotalChunks(1)
            .setChunkData(ByteArray(2_000) { 0xAB.toByte() }.toByteString())
            .build()

        // 1. Diretto verso Carol attraverso un ponte estraneo: bloccato.
        val direct = alice.directConversation(with = carol)
        alice.router.sendPacket(type = PacketType.MEDIA_CHUNK, payload = chunk, conversation = direct, storeIfUnreachable = false)
        delay(300)
        assertEquals(0, bridge.router.stats.relayed)

        // 2. Gruppo di cui il ponte e' membro: un solo salto di relay consentito.
        val groupID = UUID.randomUUID()
        val key = CryptoEngine.randomGroupSecretKey()
        for (n in listOf(alice, bridge, carol)) {
            n.database.conversations.createGroup(id = groupID, title = "Volo", secretKey = key)
        }
        val aliceGroup = requireValue(alice.database.conversations.fetch(id = groupID.toString().lowercase()))
        val inboundOnCarol = carol.router.inboundPackets()
        alice.router.sendPacket(type = PacketType.MEDIA_CHUNK, payload = chunk, conversation = aliceGroup, storeIfUnreachable = false)

        val packet = withTimeout(5_000) { inboundOnCarol.first() }
        assertEquals("img-1", packet.payload.mediaId)
        assertEquals(2, packet.hopCount)
        assertTrue(packet.sender.nodeID.contentEquals(alice.nodeID))
        assertTrue(eventually { bridge.router.stats.relayed == 1 })
    }

    // MARK: - Store-and-forward

    @Test
    fun storeAndForwardFlushesOnNewLink() = meshTest {
        val alice = node("Alice")
        val carol = node("Carol")
        TestNode.pair(alice, carol)

        val conversation = alice.directConversation(with = carol)
        val sent = alice.router.sendText("Ti scrivo appena atterriamo", conversation = conversation)
        assertEquals(DeliveryStatus.queued, sent.deliveryStatus)
        assertEquals(1, alice.database.relayQueue.stats().count)

        FakeMeshNetwork.connect(alice.transport, carol.transport)

        assertTrue(eventually { carol.database.messages.fetch(msgID = sent.msgID) != null })
        assertTrue(eventually { alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.deliveredAck })
        assertTrue(eventually { alice.database.relayQueue.stats().count == 0 })
    }

    @Test
    fun messageIsRetriedUntilAcknowledged() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)

        // Bob sospeso: il link risulta aperto per Alice, ma nessuno elabora i frame.
        val suspendedBob = FakeTransport(localNodeID = bob.nodeID)
        val (staleLink, _) = FakeMeshNetwork.connect(alice.transport, suspendedBob)

        val conversation = alice.directConversation(with = bob)
        val sent = alice.router.sendText("Sei sveglio?", conversation = conversation)
        assertEquals(DeliveryStatus.sentMesh, sent.deliveryStatus)
        assertEquals("senza ACK il messaggio resta in coda", 1, alice.database.relayQueue.stats().count)
        assertNull(bob.database.messages.fetch(msgID = sent.msgID))

        // Bob torna in primo piano e si ricollega: la coda riparte e l'ACK la svuota.
        alice.transport.detach(localLink = staleLink)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID) != null })
        assertTrue(eventually { alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.deliveredAck })
        assertTrue(eventually { alice.database.relayQueue.stats().count == 0 })
    }

    @Test
    fun periodicRetryTargetsOnlyNeighbors() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        val carol = node("Carol")
        TestNode.pair(alice, bob)
        TestNode.pair(alice, carol)
        val suspendedBob = FakeTransport(localNodeID = bob.nodeID)
        FakeMeshNetwork.connect(alice.transport, suspendedBob)
        // Il router deve aver elaborato l'apertura del link (su iOS implicito nell'ordine dell'actor).
        assertTrue(eventually { alice.router.isDirectNeighbor(bob.nodeID) })

        alice.router.sendText("Per Bob", conversation = alice.directConversation(with = bob))
        alice.router.sendText("Per Carol, lontana", conversation = alice.directConversation(with = carol))
        assertEquals(2, alice.database.relayQueue.stats().count)

        val before = alice.transport.framesSent()
        alice.router.retryUnacknowledged()
        assertEquals("ritenta solo il messaggio per il vicino Bob", before + 1, alice.transport.framesSent())
        assertEquals("senza ACK restano entrambi in coda", 2, alice.database.relayQueue.stats().count)
    }

    @Test
    fun ackRetryBackoffDoublesUpToCap() {
        val base = 30_000L
        val max = 600_000L
        var delay = base
        val sequence = (1..7).map { delay = MeshRouterActor.nextAckRetryDelay(delay, sentAny = true, base = base, max = max); delay }
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L, 600_000L), sequence)
        assertEquals("niente da ritrasmettere: torna all'intervallo base", base,
            MeshRouterActor.nextAckRetryDelay(max, sentAny = false, base = base, max = max))
    }

    @Test
    fun ackRetryBacksOffWithoutAckAndResetsWhenPeerComesOnline() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        val base = MeshRouterActor.Configuration().ackRetryInterval
        assertFalse("coda vuota: nessuna ritrasmissione", alice.router.retryUnacknowledged())

        // Bob "sospeso": il link resta aperto ma nessun ACK torna indietro.
        val suspendedBob = FakeTransport(localNodeID = bob.nodeID)
        val (staleLink, _) = FakeMeshNetwork.connect(alice.transport, suspendedBob)
        assertTrue(eventually { alice.router.isDirectNeighbor(bob.nodeID) })
        alice.router.sendText("Ci sei?", conversation = alice.directConversation(with = bob))
        assertEquals(base, alice.router.ackRetryDelayForTesting())

        val before = alice.transport.framesSent()
        alice.router.performAckRetryForTesting()
        assertEquals("il giro ritrasmette il messaggio", before + 1, alice.transport.framesSent())
        assertEquals(base * 2, alice.router.ackRetryDelayForTesting())
        repeat(6) { alice.router.performAckRetryForTesting() }
        assertEquals("tetto del backoff", MeshRouterActor.Configuration().ackRetryMaxInterval, alice.router.ackRetryDelayForTesting())

        // Bob torna davvero raggiungibile: vicino di nuovo online + ACK → intervallo base.
        alice.transport.detach(localLink = staleLink)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        assertTrue(eventually { alice.database.relayQueue.stats().count == 0 })
        assertEquals(base, alice.router.ackRetryDelayForTesting())
        assertFalse("ACK ricevuto: nulla da ritrasmettere", alice.router.retryUnacknowledged())
    }

    @Test
    fun duplicateOfDeliveredMessageIsReacknowledged() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        val conversation = alice.directConversation(with = bob)
        val sent = alice.router.sendText("Hai ricevuto?", conversation = conversation)
        val queued = requireValue(alice.database.relayQueue.pending().firstOrNull())

        // Bob riceve il messaggio ma il suo ACK non arriva mai ad Alice (nessun link verso di lei).
        bob.router.receive(queued.rawEnvelopeBytes, from = LinkID(deviceID = UUID.randomUUID().toString(), role = LinkRole.peripheral))
        requireValue(bob.database.messages.fetch(msgID = sent.msgID))
        val bobQueue = bob.database.relayQueue.pending().map { it.msgID }
        bob.database.relayQueue.remove(msgIDs = bobQueue)

        // Il link si apre e Alice ritrasmette: Bob scarta il duplicato ma, superato il throttle, rimanda l'ACK.
        bob.router.runMaintenance(now = System.currentTimeMillis() + 60_000)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        assertTrue(eventually { alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.deliveredAck })
        assertTrue(eventually { alice.database.relayQueue.stats().count == 0 })
        assertEquals("il duplicato non genera una seconda notifica", 1, bob.notifier.notified.size)
    }

    @Test
    fun bridgeQueuesRelayUntilNextHopAppears() = meshTest {
        val alice = node("Alice")
        val bridge = node("Ponte")
        val carol = node("Carol")
        TestNode.pair(alice, carol)
        FakeMeshNetwork.connect(alice.transport, bridge.transport)

        val conversation = alice.directConversation(with = carol)
        val sent = alice.router.sendText("Messaggio in valigia", conversation = conversation)
        assertEquals("il ponte e' stato raggiunto", DeliveryStatus.sentMesh, sent.deliveryStatus)
        assertTrue(eventually { bridge.database.relayQueue.stats().count == 1 })

        FakeMeshNetwork.connect(bridge.transport, carol.transport)
        assertTrue(eventually { carol.database.messages.fetch(msgID = sent.msgID) != null })
        assertTrue(eventually { bridge.database.relayQueue.stats().count == 0 })
    }

    // MARK: - Lounge e gruppi

    @Test
    fun publicLoungeBetweenStrangers() = meshTest {
        val alice = node("Seat 12A")
        val bob = node("Seat 31F")
        FakeMeshNetwork.connect(alice.transport, bob.transport)

        val lounge = alice.database.conversations.currentCabinLounge()
        val sent = alice.router.sendText("Qualcuno ha un caricatore?", conversation = lounge)
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID) != null })
        val received = requireValue(bob.database.messages.fetch(msgID = sent.msgID))
        assertEquals(ConversationRecord.cabinLoungeID, received.conversationID)
        assertEquals("Seat 12A", received.senderNickname)
        assertTrue("la Lounge non notifica", bob.notifier.notified.isEmpty())

        val spam = alice.router.sendText("Guarda https://example.com", conversation = lounge)
        delay(300)
        assertNull(bob.database.messages.fetch(msgID = spam.msgID))
        assertTrue(MeshRouterActor.containsLink("vai su www.sito.it"))
    }

    @Test
    fun groupKeyGossipPropagatesMembers() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        val carol = node("Carol")
        TestNode.pair(alice, bob)
        TestNode.pair(bob, carol)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        FakeMeshNetwork.connect(bob.transport, carol.transport)

        val group = bob.router.createGroup(title = "Volo FR1234")
        val invite = requireValue(bob.router.groupInvitePayload(group))
        assertTrue(invite.isGroupInvite)

        // A e C scansionano il QR di gruppo di B.
        val aliceGroup = alice.router.pair(with = invite)
        assertEquals(ConversationType.group, aliceGroup.type)
        assertEquals("il nome del gruppo arriva gia' dal QR", "Volo FR1234", aliceGroup.title)
        assertTrue(aliceGroup.groupSecretKey.contentEquals(group.groupSecretKey))
        val carolGroup = carol.router.pair(with = invite)

        // Gossip: B apprende A e C e propaga; A e C si conoscono senza scansionarsi.
        assertTrue(eventually {
            val members = alice.database.conversations.groupMembers(of = aliceGroup.id).map { it.nodeID.toHex() }
            members.contains(carol.nodeID.toHex()) && members.contains(bob.nodeID.toHex())
        })
        assertTrue(eventually {
            val members = carol.database.conversations.groupMembers(of = carolGroup.id).map { it.nodeID.toHex() }
            members.contains(alice.nodeID.toHex())
        })
        assertEquals("Carol", alice.database.peers.fetch(nodeID = carol.nodeID)?.nickname)
        assertEquals(false, alice.database.peers.fetch(nodeID = carol.nodeID)?.isTrusted)
        assertTrue(eventually { bob.database.conversations.groupMembers(of = group.id).size == 2 })
        assertEquals(2, bob.database.conversations.groupMembers(of = group.id).size)

        // Messaggio di gruppo da A: B lo riceve direttamente, C via B.
        val sent = alice.router.sendText("Siamo tutti a bordo?", conversation = aliceGroup)
        assertTrue(eventually { carol.database.messages.fetch(msgID = sent.msgID) != null })
        val onCarol = requireValue(carol.database.messages.fetch(msgID = sent.msgID))
        assertEquals(carolGroup.id, onCarol.conversationID)
        assertEquals(2, onCarol.hopCountReceived)
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID) != null })
        assertEquals(1, bob.database.messages.fetch(msgID = sent.msgID)?.hopCountReceived)
        assertTrue(eventually { alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.deliveredAck })
        assertEquals(Reachability.Direct(rssi = -60), alice.router.reachability(of = aliceGroup))
    }

    @Test
    fun floodingBackoffSuppressesRedundantRelay() = meshTest {
        val alice = node("Alice")
        val fast = node("Veloce", relayBackoff = 0L..0L)
        val slow = node("Lento", relayBackoff = 1_000L..1_000L)
        // Rete completamente magliata: entrambi i vicini ricevono il messaggio direttamente da Alice.
        FakeMeshNetwork.connect(alice.transport, fast.transport)
        FakeMeshNetwork.connect(alice.transport, slow.transport)
        FakeMeshNetwork.connect(fast.transport, slow.transport)

        val lounge = alice.database.conversations.currentCabinLounge()
        val sent = alice.router.sendText("Qualcuno scende a Bergamo?", conversation = lounge)
        assertTrue(eventually { slow.database.messages.fetch(msgID = sent.msgID) != null })

        // "Veloce" ritrasmette subito verso "Lento"; "Lento" sente il duplicato e rinuncia al suo relay.
        assertTrue(eventually { fast.router.stats.relayed == 1 })
        assertTrue(eventually { slow.router.stats.relaySuppressed == 1 })
        delay(1_300)
        assertEquals(0, slow.router.stats.relayed)
        assertEquals(1, slow.database.messages.fetch(conversationID = ConversationRecord.cabinLoungeID).size)
    }

    @Test
    fun replayWindowRejectsStalePackets() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        val (_, linkOnBob) = FakeMeshNetwork.connect(alice.transport, bob.transport)

        val bobPeer = requireValue(alice.database.peers.fetch(nodeID = bob.nodeID))
        val key = alice.identity.directChatKey(peerX25519PublicKeyRaw = bobPeer.x25519Pub)
        val builder = EnvelopeBuilder(alice.identity)
        fun frame(ageMs: Long): Pair<ByteArray, ByteArray> {
            val payload = InnerPayloadFactory.text("vecchio", identity = alice.identity, nickname = "Alice")
            val envelope = builder.build(
                packetType = PacketType.CHAT_TEXT, recipientID = bob.nodeID, payload = payload, key = key,
                createdAtMs = System.currentTimeMillis() - ageMs,
            )
            return MeshWire.serialize(envelope) to envelope.msgId.toByteArray()
        }

        val (stale, staleID) = frame(ageMs = 7 * 3_600_000L)
        bob.router.receive(stale, from = linkOnBob)
        val (future, futureID) = frame(ageMs = -10 * 60_000L)
        bob.router.receive(future, from = linkOnBob)
        val (fresh, freshID) = frame(ageMs = 3_600_000L)
        bob.router.receive(fresh, from = linkOnBob)

        assertTrue(eventually { bob.database.messages.fetch(msgID = freshID) != null })
        assertNull(bob.database.messages.fetch(msgID = staleID))
        assertNull(bob.database.messages.fetch(msgID = futureID))
        val stats = bob.router.stats
        assertEquals(2, stats.replayRejected)
        assertEquals(1, stats.delivered)
        assertFalse("i pacchetti rifiutati non inquinano la cache", bob.database.seenPackets.contains(msgID = staleID))
    }

    @Test
    fun blockedPeerIsIgnored() = meshTest {
        val alice = node("Alice")
        val troll = node("Troll")
        TestNode.pair(alice, troll)
        alice.database.peers.setBlocked(true, nodeID = troll.nodeID)
        alice.router.refreshBlockedPeers()
        FakeMeshNetwork.connect(alice.transport, troll.transport)

        val conversation = troll.directConversation(with = alice)
        val sent = troll.router.sendText("spam", conversation = conversation)
        delay(300)
        assertNull(alice.database.messages.fetch(msgID = sent.msgID))
        assertEquals(DeliveryStatus.sentMesh, troll.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus)
    }
}

/** `#require` di Swift Testing. */
internal fun <T : Any> requireValue(value: T?): T {
    org.junit.Assert.assertNotNull(value)
    return value!!
}
