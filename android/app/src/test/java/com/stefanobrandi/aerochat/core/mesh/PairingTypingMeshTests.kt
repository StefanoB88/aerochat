package com.stefanobrandi.aerochat.core.mesh

import com.stefanobrandi.aerochat.core.crypto.CryptoEngine
import com.stefanobrandi.aerochat.core.crypto.IdentityKeys
import com.stefanobrandi.aerochat.core.crypto.PairingPayload
import com.stefanobrandi.aerochat.core.protocol.EnvelopeBuilder
import com.stefanobrandi.aerochat.core.protocol.InnerPayloadFactory
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.MeshWire
import com.stefanobrandi.aerochat.core.protocol.base64URLEncodedString
import com.stefanobrandi.aerochat.core.protocol.toByteString
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.core.transport.LinkID
import com.stefanobrandi.aerochat.core.transport.LinkRole
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.proto.PacketType
import com.stefanobrandi.aerochat.proto.TypingIndicator
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Release 1.2: chat dopo il QR (`PAIR_HELLO`) e "sta scrivendo" (`TYPING`), contratto PAIRING_TYPING_SPEC.md. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingTypingMeshTests : MeshTestCase() {

    // MARK: - QR / URI

    @Test
    fun pairingTokenInURI() {
        val identity = IdentityKeys.generate()
        val token = ByteArray(16) { it.toByte() }

        val plain = PairingPayload(identity, nickname = "Anna")
        assertFalse(plain.uriString.contains("pt="))
        assertNull(PairingPayload(uriString = plain.uriString).pairingToken)

        val withToken = plain.withPairingToken(token)
        assertTrue(withToken.uriString.endsWith("&pt=AAECAwQFBgcICQoLDA0ODw"))
        val parsed = PairingPayload(uriString = withToken.uriString)
        assertTrue(parsed.pairingToken.contentEquals(token))
        assertEquals(withToken, parsed)
        assertEquals(plain.nodeID.toHex(), parsed.nodeID.toHex())

        // Parametri sconosciuti ignorati, ovunque si trovino; `pt` malformato ignorato (pairing come in 1.1).
        val tolerant = PairingPayload(uriString = plain.uriString.replace("?", "?zz=1&") + "&pt=${token.base64URLEncodedString()}&v=9&flag")
        assertTrue(tolerant.pairingToken.contentEquals(token))
        assertEquals("Anna", tolerant.nickname)
        assertNull(PairingPayload(uriString = plain.uriString + "&pt=corto").pairingToken)
        assertNull(PairingPayload(uriString = plain.uriString + "&pt=").pairingToken)

        // Invito di gruppo con token: entrambi i campi sopravvivono al giro.
        val group = PairingPayload(
            identity, nickname = "Anna", groupID = UUID.randomUUID(),
            groupSecretKey = CryptoEngine.randomGroupSecretKey(), groupTitle = "Volo", pairingToken = token,
        )
        val parsedGroup = PairingPayload(uriString = group.uriString)
        assertTrue(parsedGroup.isGroupInvite)
        assertTrue(parsedGroup.pairingToken.contentEquals(token))
    }

    @Test
    fun wireConstantsAndKeyVector() {
        // Numeri del contratto (compatibilita' con iOS).
        assertEquals(15, PacketType.PAIR_HELLO.number)
        assertEquals(16, PacketType.TYPING.number)
        assertTrue(PacketType.PAIR_HELLO.isStoreAndForwardEligible)
        assertFalse(PacketType.TYPING.isStoreAndForwardEligible)
        // TypingIndicator { bool is_typing = 1; }
        val bytes = MeshWire.serialize(TypingIndicator.newBuilder().setIsTyping(true).build())
        assertEquals(listOf(0x08, 0x01), bytes.map { it.toInt() })
        assertFalse(MeshWire.parseTypingIndicator(ByteArray(0)).isTyping)
        // HKDF-SHA256(IKM = 00..0f, salt "AeroMesh-Salt-v1", info "AeroMesh-PairHello-v1", 32 byte).
        val key = CryptoEngine.pairHelloKey(ByteArray(16) { it.toByte() })
        assertEquals("02c24c52d1ebd371f822a1c7bcfa653c40c6728d473b2cc0320762783cb3722c", key.rawBytes.toHex())
    }

    // MARK: - PAIR_HELLO

    @Test
    fun oneWayScanCreatesChatOnBothSides() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        // Una chat precedente (vuota, senza last_message_at_ms): la nuova deve finire sopra.
        alice.database.conversations.createGroup(id = UUID.randomUUID(), title = "Gruppo vecchio", secretKey = CryptoEngine.randomGroupSecretKey())

        // Alice mostra il QR (con token), Bob lo scansiona quando i telefoni non sono ancora collegati.
        val token = alice.router.issuePairingToken()
        val qr = PairingPayload(uriString = alice.pairingPayload.withPairingToken(token).uriString)
        val bobChat = bob.router.pair(with = qr)
        assertNotNull("chat subito in cima anche da chi scansiona", bobChat.lastMessageAtMs)
        assertEquals(bobChat.id, bob.database.conversations.fetchPermanent().first().id)
        assertEquals("PAIR_HELLO in coda fino all'ACK", 1, bob.database.relayQueue.stats().count)

        FakeMeshNetwork.connect(alice.transport, bob.transport)

        assertTrue(eventually { alice.database.peers.fetch(nodeID = bob.nodeID)?.isTrusted == true })
        val alicePeer = requireNotNull(alice.database.peers.fetch(nodeID = bob.nodeID))
        assertEquals("Bob", alicePeer.nickname)
        val aliceChat = requireNotNull(alice.database.conversations.fetch(id = ConversationRecord.direct(with = alicePeer).id))
        assertNotNull(aliceChat.lastMessageAtMs)
        assertEquals("la chat vuota compare in cima", aliceChat.id, alice.database.conversations.fetchPermanent().first().id)
        assertEquals(0, aliceChat.unreadCount)
        assertTrue("nessuna notifica di messaggio", alice.notifier.notified.isEmpty())
        // DELIVERY_ACK: la coda di Bob si svuota; il token resta valido (non consumato).
        assertTrue(eventually { bob.database.relayQueue.stats().count == 0 })
        assertEquals(1, alice.database.pairingTokens.valid().size)

        // Ora si scrivono nei due sensi.
        val fromBob = bob.router.sendText("Ciao, sono Bob dal 14C", conversation = bobChat)
        assertTrue(eventually { alice.database.messages.fetch(msgID = fromBob.msgID) != null })
        val fromAlice = alice.router.sendText("Ciao Bob!", conversation = aliceChat)
        assertTrue(eventually { bob.database.messages.fetch(msgID = fromAlice.msgID) != null })
    }

    @Test
    fun pairHelloKeepsProfileNicknameAndDuplicatesAreReacknowledged() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        // Alice conosceva gia' Bob con un nickname arrivato dal suo PROFILE_UPDATE.
        alice.database.peers.upsert(com.stefanobrandi.aerochat.data.records.PeerRecord(pairing = bob.pairingPayload, isTrusted = false))
        alice.database.peers.applyProfile(bob.nodeID, nickname = "Roberto", avatarColorHex = null, avatarImage = null, updatedAtMs = 10)

        val token = alice.router.issuePairingToken()
        bob.router.pair(with = alice.pairingPayload.withPairingToken(token))
        assertTrue(eventually { alice.database.peers.fetch(nodeID = bob.nodeID)?.isTrusted == true })
        assertEquals("Roberto", alice.database.peers.fetch(nodeID = bob.nodeID)?.nickname)
        assertTrue(eventually { bob.database.relayQueue.stats().count == 0 })
    }

    @Test
    fun unknownOrExpiredTokenIsRejected() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        val carol = node("Carol")
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        FakeMeshNetwork.connect(alice.transport, carol.transport)

        // Token mai emesso da Alice.
        bob.router.pair(with = alice.pairingPayload.withPairingToken(CryptoEngine.randomPairingToken()))
        // Token scaduto (emesso 8 giorni fa).
        val expired = alice.database.pairingTokens.issue(now = System.currentTimeMillis() - 8 * 24 * 3_600_000L)
        carol.router.pair(with = alice.pairingPayload.withPairingToken(expired))

        delay(600)
        assertNull(alice.database.peers.fetch(nodeID = bob.nodeID))
        assertNull(alice.database.peers.fetch(nodeID = carol.nodeID))
        assertTrue(alice.database.conversations.fetchPermanent().isEmpty())
        assertTrue(alice.router.stats.undecryptable >= 2)
        // Nessun ACK: chi ha scansionato continua a tenerlo in coda.
        assertEquals(1, bob.database.relayQueue.stats().count)
    }

    @Test
    fun mismatchedNodeIDIsRejected() = meshTest {
        val alice = node("Alice")
        val eve = IdentityKeys.generate()
        val victim = IdentityKeys.generate()
        val token = alice.router.issuePairingToken()
        val key = CryptoEngine.pairHelloKey(token)
        val link = LinkID(deviceID = UUID.randomUUID().toString(), role = LinkRole.central)

        // Eve firma con le sue chiavi ma dichiara la X25519 di un altro: node_id non corrispondente.
        val forged = InnerPayloadFactory.signed(eve, "Eve").toBuilder()
            .setSenderX25519Pub(victim.x25519PublicKeyRaw.toByteString())
            .build()
        val bad = EnvelopeBuilder(eve).build(PacketType.PAIR_HELLO, recipientID = alice.nodeID, payload = forged, key = key)
        alice.router.receive(MeshWire.serialize(bad), from = link)
        assertNull(alice.database.peers.fetch(nodeID = eve.nodeID))
        assertNull(alice.database.peers.fetch(nodeID = victim.nodeID))

        // Firma con una chiave diversa da quella dichiarata: scartato.
        val other = IdentityKeys.generate()
        val wrongSigner = EnvelopeBuilder(other).build(
            PacketType.PAIR_HELLO, recipientID = alice.nodeID,
            payload = InnerPayloadFactory.signed(eve, "Eve"), key = key,
        ).toBuilder().setSenderNodeId(eve.nodeID.toByteString()).build()
        alice.router.receive(MeshWire.serialize(wrongSigner), from = link)
        assertNull(alice.database.peers.fetch(nodeID = eve.nodeID))

        // Controprova: lo stesso percorso con chiavi coerenti viene accettato.
        val good = EnvelopeBuilder(eve).build(
            PacketType.PAIR_HELLO, recipientID = alice.nodeID, payload = InnerPayloadFactory.signed(eve, "Eve"), key = key,
        )
        alice.router.receive(MeshWire.serialize(good), from = link)
        assertTrue(eventually { alice.database.peers.fetch(nodeID = eve.nodeID)?.isTrusted == true })
        assertEquals(PacketType.PAIR_HELLO, good.packetType)
        assertEquals(MeshProtocol.defaultTTL, good.ttl)
    }

    // MARK: - TYPING

    private suspend fun pairedAndConnected(): Pair<TestNode, TestNode> {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        return alice to bob
    }

    @Test
    fun typingDirectIsDeliveredAndClearedByMessage() = meshTest {
        val (alice, bob) = pairedAndConnected()
        val aliceChat = alice.directConversation(with = bob)
        val bobChat = bob.directConversation(with = alice)

        assertTrue(alice.router.sendTyping(true, conversation = aliceChat))
        assertTrue(eventually { bob.router.typing.typing(bobChat.id).map { it.nickname } == listOf("Alice") })
        assertTrue(alice.router.sendTyping(false, conversation = aliceChat))
        assertTrue(eventually { bob.router.typing.typing(bobChat.id).isEmpty() })

        alice.router.sendTyping(true, conversation = aliceChat)
        assertTrue(eventually { bob.router.typing.typing(bobChat.id).isNotEmpty() })
        val sent = alice.router.sendText("Eccomi", conversation = aliceChat)
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID) != null })
        assertTrue("un messaggio in arrivo azzera il sta scrivendo", bob.router.typing.typing(bobChat.id).isEmpty())

        // Niente coda per il "sta scrivendo" (resta solo il testo finche' non arriva il suo ACK), niente notifiche.
        assertTrue(eventually { alice.database.relayQueue.stats().count == 0 })
        assertEquals(1, bob.notifier.notified.size)
    }

    @Test
    fun typingGroupIsDeliveredButNeverInLounges() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val groupID = UUID.randomUUID()
        val key = CryptoEngine.randomGroupSecretKey()
        val aliceGroup = alice.database.conversations.createGroup(id = groupID, title = "Volo", secretKey = key)
        bob.database.conversations.createGroup(id = groupID, title = "Volo", secretKey = key)

        assertTrue(alice.router.sendTyping(true, conversation = aliceGroup))
        assertTrue(eventually { bob.router.typing.typing(aliceGroup.id).map { it.nickname } == listOf("Alice") })

        // Cabina Pubblica e Cabina del volo: mai.
        val lounge = alice.database.conversations.currentCabinLounge()
        assertFalse(lounge.supportsTypingIndicator)
        assertFalse(alice.router.sendTyping(true, conversation = lounge))
        val flight = aliceGroup.copy(flightCode = "AZ|204|2026-11-10")
        assertFalse(flight.supportsTypingIndicator)
        assertFalse(alice.router.sendTyping(true, conversation = flight))
        delay(300)
        assertTrue(bob.router.typing.typing(bob.database.conversations.currentCabinLounge().id).isEmpty())
        assertEquals(setOf(aliceGroup.id), bob.router.typing.state.value.keys)
    }

    @Test
    fun typingTrackerExpires() = meshTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val tracker = TypingTracker(scope, expiry = 200L)
            tracker.update("c", senderHex = "aa", nickname = "Marco", isTyping = true)
            tracker.update("c", senderHex = "bb", nickname = "Giulia", isTyping = true)
            assertEquals(listOf("Marco", "Giulia"), tracker.typing("c").map { it.nickname })
            tracker.update("c", senderHex = "bb", nickname = "Giulia", isTyping = false)
            assertEquals(listOf("Marco"), tracker.typing("c").map { it.nickname })
            // Scade da solo dopo l'ultimo `true`.
            assertTrue(eventually(timeout = 2_000) { tracker.typing("c").isEmpty() })
            assertTrue(tracker.state.value.isEmpty())
            assertEquals(6_000L, MeshProtocol.typingExpiry)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun typingSenderRules() = meshTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var now = 0L
            var allowed = true
            val sent = Collections.synchronizedList(ArrayList<Boolean>())
            val sender = TypingSender(
                scope = scope, canSend = { allowed }, send = { sent.add(it) },
                refreshInterval = 4_000L, idleTimeout = 150L, clock = { now },
            )
            sender.textChanged("C")
            sender.textChanged("Ci")
            assertEquals("true una volta sola", listOf(true), sent.toList())
            now += 4_000
            sender.textChanged("Cia")
            assertEquals("ripetuto dopo 4 s", listOf(true, true), sent.toList())
            sender.textChanged("")
            assertEquals("testo vuoto -> false", listOf(true, true, false), sent.toList())
            sender.textChanged("x")
            assertTrue("false dopo l'inattivita'", eventually(timeout = 2_000) { sent.toList() == listOf(true, true, false, true, false) })
            sender.textChanged("y")
            sender.stop()
            assertEquals("invio / uscita -> false", listOf(true, true, false, true, false, true, false), sent.toList())
            sender.stop()
            allowed = false
            sender.textChanged("z")
            assertEquals("chat non idonea: niente invio", 7, sent.size)
        } finally {
            scope.cancel()
        }
    }
}
