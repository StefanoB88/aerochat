package com.stefanobrandi.aerochat.core.mesh

import com.stefanobrandi.aerochat.core.crypto.IdentityKeys
import com.stefanobrandi.aerochat.core.flight.BoardingPass
import com.stefanobrandi.aerochat.core.games.GameService
import com.stefanobrandi.aerochat.core.games.GameSessionState
import com.stefanobrandi.aerochat.core.games.GameStatus
import com.stefanobrandi.aerochat.core.protocol.EnvelopeBuilder
import com.stefanobrandi.aerochat.core.protocol.InnerPayloadFactory
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.MeshWire
import com.stefanobrandi.aerochat.core.protocol.toByteString
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.core.review.DemoPeerService
import com.stefanobrandi.aerochat.core.transport.LinkID
import com.stefanobrandi.aerochat.core.transport.LinkRole
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.MessageContentType
import com.stefanobrandi.aerochat.data.records.PeerRecord
import com.stefanobrandi.aerochat.proto.ContactAction
import com.stefanobrandi.aerochat.proto.ContactRequest
import com.stefanobrandi.aerochat.proto.GameKind
import com.stefanobrandi.aerochat.proto.PacketType
import com.stefanobrandi.aerochat.proto.Presence
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Release 1.3: persone vicine (`PRESENCE`) e "Saluta"/"Sfida" (`CONTACT_REQUEST`), contratto NEARBY_SPEC.md. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NearbyMeshTests : MeshTestCase() {

    private val services = ArrayList<GameService>()

    @After
    fun stopServices() = services.forEach { it.stop() }

    private suspend fun games(node: TestNode): GameService =
        GameService(router = node.router, database = node.database, identity = node.identity, nickname = node.nickname)
            .also { it.start(); services += it }

    private fun TestNode.sees(other: TestNode): NearbyPerson? =
        router.nearby.people.value.firstOrNull { it.nodeID.contentEquals(other.nodeID) }

    /** Collega i due nodi e attende che si vedano a vicenda (PRESENCE all'apertura del link). */
    private suspend fun meet(a: TestNode, b: TestNode) {
        FakeMeshNetwork.connect(a.transport, b.transport)
        assertTrue("PRESENCE all'apertura del link", eventually { a.sees(b) != null && b.sees(a) != null })
    }

    // MARK: - Contratto wire

    @Test
    fun wireConstantsAndCrossPlatformVectors() {
        assertEquals(17, PacketType.PRESENCE.number)
        assertEquals(18, PacketType.CONTACT_REQUEST.number)
        assertFalse(PacketType.PRESENCE.isStoreAndForwardEligible)
        assertFalse(PacketType.CONTACT_REQUEST.isStoreAndForwardEligible)
        assertEquals(1, ContactAction.CONTACT_ACTION_REQUEST.number)
        assertEquals(2, ContactAction.CONTACT_ACTION_ACCEPT.number)
        assertEquals(3, ContactAction.CONTACT_ACTION_DECLINE.number)
        assertEquals(1, MeshProtocol.presenceTTL)
        assertEquals(2, MeshProtocol.contactRequestTTL)

        // Vettori condivisi con iOS (stessi byte in AeroMeshTests).
        val presence = Presence.newBuilder()
            .setAvatarColorHex("00E5FF")
            .setFlightCode("AZ|204|2026-11-10")
            .setOpenToGames(true)
            .setSentAtMs(1_700_000_000_000L)
            .build()
        assertEquals(
            "0a063030453546461211415a7c3230347c323032362d31312d313018012080d095ffbc31",
            MeshWire.serialize(presence).toHex(),
        )
        val request = ContactRequest.newBuilder()
            .setAction(ContactAction.CONTACT_ACTION_REQUEST)
            .setRequestId(ByteArray(16) { it.toByte() }.toByteString())
            .setGreeting("Ciao")
            .setGameKind(GameKind.SCOPA.number)
            .build()
        assertEquals(
            "08011210000102030405060708090a0b0c0d0e0f1a044369616f2003",
            MeshWire.serialize(request).toHex(),
        )
        assertEquals(presence, MeshWire.parsePresence(MeshWire.serialize(presence)))
        assertEquals(request, MeshWire.parseContactRequest(MeshWire.serialize(request)))
    }

    // MARK: - PRESENCE

    @Test
    fun presenceRoundTripWithFlightAndColor() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        alice.router.setProfile(LocalProfile("Alice", "00e5ff", null, updatedAtMs = 1), announce = false)
        val pass = BoardingPass(carrier = "AZ", flightNumber = "204", origin = "FCO", destination = "LHR", seat = null, departureDay = System.currentTimeMillis())
        alice.router.joinFlightLounge(pass)

        meet(alice, bob)
        val seen = requireNotNull(bob.sees(alice))
        assertEquals("Alice", seen.nickname)
        assertEquals("00E5FF", seen.avatarColorHex)
        assertEquals(pass.canonicalKey, seen.flightCode)
        assertTrue(seen.openToGames)
        assertFalse(seen.isContact)
        assertTrue(seen.x25519Pub.contentEquals(alice.identity.x25519PublicKeyRaw))
        assertNotNull("RSSI dal trasporto", seen.rssi)
        assertNull("Bob non e' in nessun volo", alice.sees(bob)?.flightCode)

        // Mai nel DB: niente peer ne' seen_packets per un PRESENCE.
        assertNull(bob.database.peers.fetch(nodeID = alice.nodeID))
        assertTrue(bob.notifier.notified.isEmpty())

        // "Accetto sfide" spento: il prossimo annuncio lo dice.
        alice.router.setPresenceOptions(visible = true, openToGames = false)
        assertTrue(eventually { bob.sees(alice)?.openToGames == false })
    }

    @Test
    fun presenceCacheExpiresAndIsBounded() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        meet(alice, bob)
        val now = System.currentTimeMillis()
        bob.router.nearby.purge(now + MeshProtocol.presenceLifetime / 2)
        assertNotNull("ancora valido prima di 3 minuti", bob.sees(alice))
        bob.router.nearby.purge(now + MeshProtocol.presenceLifetime + 5_000)
        assertNull("scaduto 3 minuti dopo l'ultimo PRESENCE", bob.sees(alice))
        assertNull(bob.router.nearby.person(alice.nodeID.toHex(), now + MeshProtocol.presenceLifetime + 5_000))

        // Capienza: al massimo 64 voci (esce la meno recente).
        val tracker = NearbyTracker()
        val people = (0 until 70).map { index ->
            val keys = IdentityKeys.generate()
            NearbyPerson(keys.nodeID, keys.x25519PublicKeyRaw, keys.ed25519PublicKeyRaw, "P$index", null, null, true, null, now + index, false)
        }
        people.forEach { tracker.updatePresence(it, now = now + 100) }
        assertEquals(MeshProtocol.presenceCapacity, tracker.people.value.size)
        assertNull(tracker.person(people.first().id, now + 100))
        assertNotNull(tracker.person(people.last().id, now + 100))
    }

    @Test
    fun blockedSenderIsIgnored() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        bob.database.peers.upsert(PeerRecord(pairing = alice.pairingPayload, isTrusted = false))
        bob.database.peers.setBlocked(true, nodeID = alice.nodeID)
        bob.router.refreshBlockedPeers()

        FakeMeshNetwork.connect(alice.transport, bob.transport)
        assertTrue(eventually { alice.sees(bob) != null })
        alice.router.announcePresence()
        delay(300)
        assertNull("PRESENCE di un bloccato ignorato", bob.sees(alice))
    }

    @Test
    fun visibilityOffStopsSending() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        alice.router.setPresenceOptions(visible = false, openToGames = true)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        assertTrue("Alice continua a vedere gli altri", eventually { alice.sees(bob) != null })
        assertFalse(alice.router.announcePresence())
        delay(300)
        assertNull("Alice invisibile", bob.sees(alice))
        val sentTypes = alice.transport.sent.mapNotNull { runCatching { MeshWire.parseEnvelope(it.frame).packetType }.getOrNull() }
        assertFalse(sentTypes.contains(PacketType.PRESENCE))

        // Di nuovo visibile: annuncio immediato.
        alice.router.setPresenceOptions(visible = true, openToGames = true)
        assertTrue(eventually { bob.sees(alice) != null })
    }

    // MARK: - CONTACT_REQUEST

    @Test
    fun requestAcceptCreatesChatOnBothSides() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        meet(alice, bob)
        val events = Collections.synchronizedList(ArrayList<RouterEvent>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aliceEvents = alice.router.events()
        scope.launch { aliceEvents.collect { events.add(it) } }

        assertTrue(alice.router.sendContactRequest(to = requireNotNull(alice.sees(bob)), greeting = "  Ciao dal 14C  "))
        assertTrue(eventually { bob.router.nearby.requests.value.size == 1 })
        val request = bob.router.nearby.requests.value.single()
        assertEquals("Alice", request.nickname)
        assertEquals("Ciao dal 14C", request.greeting)
        assertEquals(0, request.gameKind)
        // Nessun contatto finche' Bob non accetta.
        assertNull(bob.database.peers.fetch(nodeID = alice.nodeID))

        // Il PRESENCE di Bob scade prima della risposta: l'ACCEPT si apre con le chiavi della richiesta.
        alice.router.nearby.purge(System.currentTimeMillis() + MeshProtocol.presenceLifetime + 5_000)
        assertNull(alice.sees(bob))
        val bobChat = bob.router.acceptContactRequest(request.id)
        assertTrue(bob.router.nearby.requests.value.isEmpty())
        assertEquals(bobChat.id, bob.database.conversations.fetchPermanent().first().id)
        assertEquals(true, bob.database.peers.fetch(nodeID = alice.nodeID)?.isTrusted)

        assertTrue(eventually { alice.database.peers.fetch(nodeID = bob.nodeID)?.isTrusted == true })
        val alicePeer = requireNotNull(alice.database.peers.fetch(nodeID = bob.nodeID))
        val aliceChat = alice.database.conversations.fetch(id = ConversationRecord.direct(with = alicePeer).id)
        assertNotNull("chat anche da chi ha chiesto", aliceChat)
        assertTrue(eventually { events.any { it is RouterEvent.ContactAccepted && it.requestedByMe } })
        bob.router.announcePresence()
        assertTrue(eventually { alice.sees(bob)?.isContact == true })

        // Ora si scrivono come dopo il QR.
        val text = alice.router.sendText("Ciao Bob!", conversation = requireNotNull(aliceChat))
        assertTrue(eventually { bob.database.messages.fetch(msgID = text.msgID) != null })
        scope.cancel()
    }

    @Test
    fun challengeAcceptSendsGameInvite() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        games(alice)
        games(bob)
        meet(alice, bob)

        alice.router.sendContactRequest(to = requireNotNull(alice.sees(bob)), gameKind = GameKind.SCOPA.number)
        assertTrue(eventually { bob.router.nearby.requests.value.size == 1 })
        val request = bob.router.nearby.requests.value.single()
        assertEquals(GameKind.SCOPA.number, request.gameKind)
        val bobChat = bob.router.acceptContactRequest(request.id)

        // Chi ha chiesto invia l'invito; dall'altra parte si accetta da solo e parte la mano.
        suspend fun gameState(node: TestNode, conversationID: String): GameSessionState? =
            node.database.messages.fetch(conversationID = conversationID, limit = 20)
                .firstOrNull { it.contentType == MessageContentType.game }
                ?.gameState?.let { GameSessionState.decode(it) }
        assertTrue(
            "invito accettato da solo",
            eventually(timeout = 10_000) { gameState(bob, bobChat.id)?.status == GameStatus.active },
        )
        val bobState = requireNotNull(gameState(bob, bobChat.id))
        assertEquals(GameKind.SCOPA, bobState.gameKind)
        assertFalse(bobState.iAmInviter)
        val aliceChatID = ConversationRecord.direct(with = requireNotNull(alice.database.peers.fetch(nodeID = bob.nodeID))).id
        assertTrue(eventually(timeout = 10_000) { gameState(alice, aliceChatID)?.scopa != null })
        // La sfida vale una volta sola.
        assertFalse(bob.router.consumeAcceptedChallenge(from = alice.nodeID, kind = GameKind.SCOPA.number))
    }

    @Test
    fun declineNotifiesRequesterOnly() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        meet(alice, bob)
        val events = Collections.synchronizedList(ArrayList<RouterEvent>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aliceEvents = alice.router.events()
        scope.launch { aliceEvents.collect { events.add(it) } }

        alice.router.sendContactRequest(to = requireNotNull(alice.sees(bob)))
        assertTrue(eventually { bob.router.nearby.requests.value.size == 1 })
        bob.router.declineContactRequest(bob.router.nearby.requests.value.single().id)
        assertTrue(eventually { events.any { it is RouterEvent.ContactDeclined && it.nickname == "Bob" } })
        assertTrue(bob.router.nearby.requests.value.isEmpty())
        assertNull(alice.database.peers.fetch(nodeID = bob.nodeID))
        assertNull(bob.database.peers.fetch(nodeID = alice.nodeID))
        assertTrue(alice.database.conversations.fetchPermanent().isEmpty())
        scope.cancel()
    }

    @Test
    fun unknownSenderAndForeignAcceptAreDropped() = meshTest {
        val bob = node("Bob")
        val carol = IdentityKeys.generate()
        val link = LinkID(deviceID = UUID.randomUUID().toString(), role = LinkRole.central)
        val request = ContactRequest.newBuilder()
            .setAction(ContactAction.CONTACT_ACTION_REQUEST)
            .setRequestId(ByteArray(16) { 7 }.toByteString())
            .build()
        val payload = InnerPayloadFactory.extra(MeshWire.serialize(request), identity = carol, nickname = "Carol")
        val envelope = EnvelopeBuilder(carol).build(
            PacketType.CONTACT_REQUEST, recipientID = bob.nodeID, payload = payload,
            key = carol.directChatKey(peerX25519PublicKeyRaw = bob.identity.x25519PublicKeyRaw), ttl = 2,
        )
        // Carol non e' ne' nel DB ne' tra i vicini: scartato.
        bob.router.receive(MeshWire.serialize(envelope), from = link)
        assertTrue(bob.router.nearby.requests.value.isEmpty())
        assertTrue(bob.router.stats.undecryptable >= 1)

        // Un ACCEPT con un request_id mai inviato da un vicino noto non crea nulla.
        val alice = node("Alice")
        meet(alice, bob)
        val accept = ContactRequest.newBuilder()
            .setAction(ContactAction.CONTACT_ACTION_ACCEPT)
            .setRequestId(ByteArray(16) { 9 }.toByteString())
            .build()
        val acceptEnvelope = EnvelopeBuilder(alice.identity).build(
            PacketType.CONTACT_REQUEST, recipientID = bob.nodeID,
            payload = InnerPayloadFactory.extra(MeshWire.serialize(accept), identity = alice.identity, nickname = "Alice"),
            key = alice.identity.directChatKey(peerX25519PublicKeyRaw = bob.identity.x25519PublicKeyRaw), ttl = 2,
        )
        bob.router.receive(MeshWire.serialize(acceptEnvelope), from = link)
        delay(200)
        assertNull(bob.database.peers.fetch(nodeID = alice.nodeID))
        assertTrue(bob.database.conversations.fetchPermanent().isEmpty())
    }

    @Test
    fun oneRequestPerSenderEveryTenMinutes() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        meet(alice, bob)
        val person = requireNotNull(alice.sees(bob))
        alice.router.sendContactRequest(to = person, greeting = "Prima")
        assertTrue(eventually { bob.router.nearby.requests.value.size == 1 })
        alice.router.sendContactRequest(to = person, greeting = "Seconda")
        delay(400)
        assertEquals(listOf("Prima"), bob.router.nearby.requests.value.map { it.greeting })
        // Anche dopo aver ignorato la prima, il limite resta per 10 minuti.
        bob.router.dismissContactRequest(bob.router.nearby.requests.value.single().id)
        alice.router.sendContactRequest(to = person, greeting = "Terza")
        delay(400)
        assertTrue(bob.router.nearby.requests.value.isEmpty())

        // Tracker: finestra di 10 minuti e massimo 20 richieste in attesa.
        val tracker = NearbyTracker()
        val now = 1_000_000L
        fun request(sender: IdentityKeys, at: Long, id: Byte) = IncomingContactRequest(
            ByteArray(16) { id }, sender.nodeID, sender.x25519PublicKeyRaw, sender.ed25519PublicKeyRaw, "X", null, "", 0, at,
        )
        val one = IdentityKeys.generate()
        assertTrue(tracker.addIncoming(request(one, now, 1), now))
        assertFalse(tracker.addIncoming(request(one, now + 60_000, 2), now + 60_000))
        tracker.takeIncoming(ByteArray(16) { 1 }.toHex())
        assertTrue(tracker.addIncoming(request(one, now + MeshProtocol.contactRequestRateLimit, 3), now + MeshProtocol.contactRequestRateLimit))
        val crowd = NearbyTracker()
        repeat(MeshProtocol.maxPendingContactRequests) { index ->
            assertTrue(crowd.addIncoming(request(IdentityKeys.generate(), now, (index + 10).toByte()), now))
        }
        assertFalse(crowd.addIncoming(request(IdentityKeys.generate(), now, 99), now))
        // Le richieste scadono dopo 30 minuti.
        crowd.purge(now + MeshProtocol.contactRequestLifetime + 1)
        assertTrue(crowd.requests.value.isEmpty())
    }

    // MARK: - Modalita' prova

    @Test
    fun demoConversationNeverLeavesThePhone() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        delay(200)
        val demoPeer = PeerRecord(
            nodeID = DemoPeerService.nodeID, x25519Pub = ByteArray(32) { 3 }, ed25519Pub = ByteArray(32) { 4 },
            nickname = "Passeggero virtuale", isTrusted = true,
        )
        val stored = alice.database.peers.upsert(demoPeer)
        val chat = alice.database.conversations.ensureDirectConversation(with = stored)
        val before = alice.transport.framesSent()
        alice.router.sendText("Ciao passeggero!", conversation = chat)
        alice.router.sendTyping(true, conversation = chat)
        delay(200)
        assertEquals("niente radio verso il passeggero virtuale", before, alice.transport.framesSent())
        assertEquals("niente coda", 0, alice.database.relayQueue.stats().count)
    }

    @Test
    fun demoPassengerPlaysAFullScopaLocally() = meshTest(timeoutMs = 120_000) {
        val alice = node("Alice")
        val games = games(alice)
        val demo = DemoPeerService(
            database = alice.database, context = context, games = { games },
            readDelay = 0, replyDelay = 0, moveDelay = 0, invitesOnEnable = true,
        )
        val chat = requireNotNull(demo.enable())
        assertTrue(DemoPeerService.isDemoConversation(chat))
        suspend fun state(): GameSessionState? =
            alice.database.messages.fetch(conversationID = chat.id, limit = 50)
                .firstOrNull { it.contentType == MessageContentType.game }
                ?.gameState?.let { GameSessionState.decode(it) }
        // Il passeggero virtuale propone una Scopa.
        assertTrue(eventually { state()?.status == GameStatus.invited })
        val gameID = requireNotNull(state()).gameID
        games.accept(gameID)
        // Il bot distribuisce e gioca; noi giochiamo con lo stesso bot (mosse legali).
        var moves = 0
        while (moves < 500) {
            val current = requireNotNull(state())
            if (current.status == GameStatus.finished) break
            if (current.myTurn) {
                val (card, captured) = requireNotNull(com.stefanobrandi.aerochat.core.review.DemoCardBot.scopaMove(current.scopa!!, current.mySeat!!))
                games.playCard(gameID, card, captured)
                moves += 1
            } else {
                delay(5)
            }
        }
        assertTrue("partita conclusa in locale", eventually(timeout = 20_000) { state()?.status == GameStatus.finished })
        assertTrue("commento finale del passeggero", eventually {
            alice.database.messages.fetch(conversationID = chat.id, limit = 100).any { it.text?.contains("🎉") == true }
        })
        assertEquals("niente radio", 0, alice.transport.framesSent())
        assertEquals("niente coda", 0, alice.database.relayQueue.stats().count)
        demo.close()
    }
}
