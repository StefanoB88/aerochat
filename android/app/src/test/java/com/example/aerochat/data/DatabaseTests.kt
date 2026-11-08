package com.example.aerochat.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.crypto.IdentityKeys
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.records.ConversationType
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.IdentityRecord
import com.example.aerochat.data.records.MessageContentType
import com.example.aerochat.data.records.MessageRecord
import com.example.aerochat.data.records.PeerRecord
import com.example.aerochat.data.records.ProTier
import com.example.aerochat.data.records.RelayQueueRecord
import com.example.aerochat.data.repositories.MessageRepository
import com.example.aerochat.data.repositories.RelayQueueStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room – schema, FTS, osservazioni, code (port di `DatabaseTests.swift`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseTests {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.makeTemporary(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun makePeer(nickname: String, trusted: Boolean = false): PeerRecord {
        val identity = IdentityKeys.generate()
        return PeerRecord(
            nodeID = identity.nodeID,
            x25519Pub = identity.x25519PublicKeyRaw,
            ed25519Pub = identity.ed25519PublicKeyRaw,
            nickname = nickname,
            isTrusted = trusted,
        )
    }

    @Test
    fun migrationsCreateSchema() = runBlocking {
        val tables = db.tableNames()
        for (expected in listOf("identity", "peers", "conversations", "group_members", "messages", "messages_fts", "seen_packets", "relay_queue", "message_reads")) {
            assertTrue("manca la tabella $expected", tables.contains(expected))
        }
    }

    @Test
    fun fileDatabaseRunsInWAL() = runBlocking {
        val name = "wal-test-${UUID.randomUUID()}.sqlite"
        val fileDB = AppDatabase.makeFile(context, name)
        try {
            assertTrue(fileDB.tableNames().contains("messages"))
            assertTrue(fileDB.isWALEnabled())
        } finally {
            fileDB.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun identitySingleton() = runBlocking {
        val keys = IdentityKeys.generate()
        assertNull(db.identities.fetch())

        db.identities.save(IdentityRecord(identity = keys, nickname = "Stefano", avatarColorHex = "00E5FF"))
        val stored = db.identities.fetch()!!
        assertTrue(stored.nodeID.contentEquals(keys.nodeID))
        assertEquals(IdentityRecord.singletonID, stored.id)
        assertFalse(stored.isPro())

        val until = System.currentTimeMillis() + 72 * 3_600_000L
        db.identities.updateProStatus(tier = ProTier.pass72h, validUntilMs = until, receiptJWS = "jws")
        val upgraded = db.identities.fetch()!!
        assertTrue(upgraded.isPro())
        assertFalse(upgraded.isPro(now = System.currentTimeMillis() + 73 * 3_600_000L))
    }

    @Test
    fun identityProfileVersionAlwaysIncreases() = runBlocking {
        db.identities.save(IdentityRecord(identity = IdentityKeys.generate(), nickname = "A", avatarColorHex = "FFFFFF", createdAt = 10_000))
        val first = db.identities.updateProfile(nickname = "B", avatarColorHex = "000000", avatarImage = byteArrayOf(1, 2), at = 5_000)!!
        assertEquals(10_001L, first.profileUpdatedAtMs)
        val stored = db.identities.fetch()!!
        assertEquals("B", stored.nickname)
        assertTrue(stored.avatarImage.contentEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun peerUpsertPreservesTrust() = runBlocking {
        val peer = makePeer("Anna", trusted = true)
        db.peers.upsert(peer)

        val gossiped = peer.copy(isTrusted = false, nickname = "Anna R.")
        db.peers.upsert(gossiped)

        val stored = db.peers.fetch(nodeID = peer.nodeID)!!
        assertTrue(stored.isTrusted)
        assertEquals("Anna R.", stored.nickname)

        db.peers.markSeen(nodeID = peer.nodeID, hopCount = 2, rssi = -71)
        val seen = db.peers.fetch(nodeID = peer.nodeID)!!
        assertEquals(2, seen.lastHopCount)
        assertEquals(-71, seen.lastRSSI)
        assertNotNull(seen.lastSeenAtMs)
        db.peers.markSeen(nodeID = peer.nodeID, hopCount = null, rssi = null)
        assertEquals(-71, db.peers.fetch(nodeID = peer.nodeID)!!.lastRSSI)
        assertEquals(1, db.peers.trustedPeers().size)

        db.peers.setBlocked(true, nodeID = peer.nodeID)
        assertEquals(setOf(peer.nodeID.toHex()), db.peers.blockedNodeIDs())
        assertTrue(db.peers.trustedPeers().isEmpty())
    }

    @Test
    fun profileUpdateWinsOverGossipNickname() = runBlocking {
        val peer = makePeer("Luca", trusted = true)
        db.peers.upsert(peer)
        val chat = db.conversations.ensureDirectConversation(with = peer)

        assertTrue(db.peers.applyProfile(peer.nodeID, "Luca B.", "FF0000", byteArrayOf(9), updatedAtMs = 100))
        assertFalse(db.peers.applyProfile(peer.nodeID, "Vecchio", null, null, updatedAtMs = 50))
        assertEquals("Luca B.", db.conversations.fetch(id = chat.id)!!.title)

        // Un nickname da gossip non sovrascrive quello firmato dal peer.
        db.peers.upsert(peer.copy(nickname = "Gossip"))
        assertEquals("Luca B.", db.peers.fetch(peer.nodeID)!!.nickname)
        db.peers.upsert(peer.copy(nickname = "Firmato"), authoritativeNickname = true)
        assertEquals("Firmato", db.peers.fetch(peer.nodeID)!!.nickname)
        assertEquals("Firmato", db.conversations.fetch(id = chat.id)!!.title)

        db.peers.setBLEIdentifier("AA:BB:CC:DD:EE:FF", nodeID = peer.nodeID)
        assertEquals(setOf("AA:BB:CC:DD:EE:FF"), db.peers.trustedPeripheralIdentifiers())
    }

    @Test
    fun peersSortedByNicknameCaseInsensitive() = runBlocking {
        db.peers.upsert(makePeer("bruno"))
        db.peers.upsert(makePeer("Anna"))
        db.peers.upsert(makePeer("carla"))
        assertEquals(listOf("Anna", "bruno", "carla"), db.peers.fetchAll().map { it.nickname })
    }

    @Test
    fun directConversationMessagesAndFTS() = runBlocking {
        val me = IdentityKeys.generate()
        val peer = makePeer("Luca")
        db.peers.upsert(peer)
        val conversation = db.conversations.ensureDirectConversation(with = peer)
        assertTrue(conversation.recipientID.contentEquals(peer.nodeID))
        assertEquals("direct:${peer.nodeID.toHex()}", conversation.id)
        assertEquals(conversation.id, db.conversations.ensureDirectConversation(with = peer).id)
        assertEquals(conversation.id, db.conversations.fetch(recipientID = peer.nodeID)?.id)

        val now = System.currentTimeMillis()
        assertTrue(db.messages.insert(MessageRecord.outgoingText("Prendiamo un taxi insieme all'arrivo?", conversationID = conversation.id, sender = me)))
        val incoming = MessageRecord(
            msgID = CryptoEngine.randomMessageID(), conversationID = conversation.id,
            senderNodeID = peer.nodeID, contentType = MessageContentType.text, text = "Volentieri, io sono al 22A",
            isOutgoing = false, deliveryStatus = DeliveryStatus.deliveredAck, hopCountReceived = 1,
            createdAtMs = now + 1,
        )
        assertTrue(db.messages.insert(incoming))
        assertFalse("msg_id duplicato ignorato", db.messages.insert(incoming))
        db.messages.insert(
            MessageRecord(
                msgID = CryptoEngine.randomMessageID(), conversationID = conversation.id,
                senderNodeID = peer.nodeID, contentType = MessageContentType.text, text = "Il gate è cambiato: B7",
                isOutgoing = false, deliveryStatus = DeliveryStatus.deliveredAck,
                createdAtMs = now + 2,
            ),
        )

        val messages = db.messages.fetch(conversationID = conversation.id)
        assertEquals(3, messages.size)
        assertTrue(messages.first().isOutgoing)

        val updated = db.conversations.fetch(id = conversation.id)!!
        assertEquals(2, updated.unreadCount)
        assertNotNull(updated.lastMessageAtMs)

        assertEquals(1, db.messages.search("taxi").size)
        assertEquals(1, db.messages.search("tax").size)
        assertEquals("Il gate è cambiato: B7", db.messages.search("gate b7").firstOrNull()?.text)
        // Parole chiave FTS (OR/NOT/NEAR) trattate come testo, niente errori di sintassi.
        assertTrue(db.messages.search("cambiato OR NOT").isEmpty())
        assertEquals(1, db.messages.search("CAMBIATO").size)
        assertTrue(db.messages.search("inesistente").isEmpty())
        assertTrue(db.messages.search("  ,. ").isEmpty())

        db.conversations.markRead(conversationID = conversation.id)
        assertEquals(0, db.conversations.fetch(id = conversation.id)?.unreadCount)
        assertTrue(db.messages.fetch(conversationID = conversation.id).filter { !it.isOutgoing }.all { it.deliveryStatus == DeliveryStatus.read })
    }

    @Test
    fun ftsFollowsUpdatesAndDeletes() = runBlocking {
        val peer = makePeer("Eva")
        db.peers.upsert(peer)
        val chat = db.conversations.ensureDirectConversation(with = peer)
        val message = MessageRecord.outgoingText("bagaglio smarrito", conversationID = chat.id, sender = IdentityKeys.generate())
        db.messages.insert(message)
        assertEquals(1, db.messages.search("bagag").size)
        db.messages.delete(msgID = message.msgID)
        assertTrue(db.messages.search("bagaglio").isEmpty())
    }

    @Test
    fun prefixPatternMatchesGRDBSemantics() {
        assertNull(MessageRepository.prefixPattern("  !? "))
        assertEquals("\"gate*\" \"b7*\"", MessageRepository.prefixPattern("Gate, B7"))
    }

    @Test
    fun markIncomingReadAndReadReceipts() = runBlocking {
        val me = IdentityKeys.generate()
        val peer = makePeer("Marta")
        val other = makePeer("Ugo")
        db.peers.upsert(peer)
        val chat = db.conversations.ensureDirectConversation(with = peer)
        val incoming = MessageRecord(
            msgID = CryptoEngine.randomMessageID(), conversationID = chat.id, senderNodeID = peer.nodeID,
            contentType = MessageContentType.text, text = "ciao", isOutgoing = false, deliveryStatus = DeliveryStatus.deliveredAck,
        )
        db.messages.insert(incoming)
        val unread = db.messages.markIncomingRead(conversationID = chat.id)
        assertEquals(listOf(incoming.msgID.toHex()), unread.map { it.msgID.toHex() })
        assertTrue(db.messages.markIncomingRead(conversationID = chat.id).isEmpty())
        assertEquals(0, db.conversations.fetch(id = chat.id)!!.unreadCount)

        val outgoing = MessageRecord.outgoingText("tutto ok?", conversationID = chat.id, sender = me)
        db.messages.insert(outgoing)
        assertEquals(setOf(outgoing.msgID.toHex()), db.messages.awaitingAckIDs(among = listOf(outgoing.msgID, incoming.msgID)))
        db.messages.markSentMesh(msgIDs = listOf(outgoing.msgID))
        assertEquals(DeliveryStatus.sentMesh, db.messages.fetch(msgID = outgoing.msgID)!!.deliveryStatus)

        // Un lettore diverso dal compagno della chat 1-a-1 non conta.
        assertTrue(db.messages.markReadByRecipient(msgIDs = listOf(outgoing.msgID), readerNodeID = other.nodeID).isEmpty())
        val updated = db.messages.markReadByRecipient(msgIDs = listOf(outgoing.msgID), readerNodeID = peer.nodeID)
        assertEquals(listOf(outgoing.msgID.toHex()), updated.map { it.toHex() })
        assertTrue(db.messages.awaitingAckIDs(among = listOf(outgoing.msgID)).isEmpty())
    }

    @Test
    fun mediaPathsAndGameState() = runBlocking {
        val peer = makePeer("Pia")
        db.peers.upsert(peer)
        val chat = db.conversations.ensureDirectConversation(with = peer)
        val image = MessageRecord(
            msgID = CryptoEngine.randomMessageID(), conversationID = chat.id, senderNodeID = peer.nodeID,
            contentType = MessageContentType.image, blurhash = "LEHV6nWB2yk8", isOutgoing = false,
            deliveryStatus = DeliveryStatus.deliveredAck,
        )
        db.messages.insert(image)
        assertTrue(db.messages.mediaPaths(conversationID = chat.id).isEmpty())
        db.messages.updateMedia(localPath = "media/a.webp", msgID = image.msgID)
        assertEquals(listOf("media/a.webp"), db.messages.mediaPaths(conversationID = chat.id))
        db.messages.updateGameState(byteArrayOf(1, 2, 3), msgID = image.msgID)
        assertTrue(db.messages.fetch(msgID = image.msgID)!!.gameState.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun deliveryStatusOnlyAdvances() = runBlocking {
        val me = IdentityKeys.generate()
        val peer = makePeer("Sara")
        db.peers.upsert(peer)
        val conversation = db.conversations.ensureDirectConversation(with = peer)
        val message = MessageRecord.outgoingText("ping", conversationID = conversation.id, sender = me)
        db.messages.insert(message)
        assertEquals(1, db.messages.queuedOutgoing().size)

        db.messages.advanceDeliveryStatus(to = DeliveryStatus.deliveredAck, msgID = message.msgID)
        db.messages.advanceDeliveryStatus(to = DeliveryStatus.sentMesh, msgID = message.msgID)
        assertEquals(DeliveryStatus.deliveredAck, db.messages.fetch(msgID = message.msgID)?.deliveryStatus)
        assertTrue(db.messages.queuedOutgoing().isEmpty())
    }

    @Test
    fun observeMessagesStream() = runBlocking {
        val me = IdentityKeys.generate()
        val peer = makePeer("Paolo")
        db.peers.upsert(peer)
        val conversation = db.conversations.ensureDirectConversation(with = peer)
        db.messages.insert(MessageRecord.outgoingText("uno", conversationID = conversation.id, sender = me))

        val values = Channel<List<MessageRecord>>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) {
            db.messages.observe(conversationID = conversation.id).collect { values.send(it) }
        }
        try {
            val initial = withTimeout(5_000) { values.receive() }
            assertEquals(1, initial.size)

            db.messages.insert(MessageRecord.outgoingText("due", conversationID = conversation.id, sender = me))
            val updated = withTimeout(5_000) { values.receive() }
            assertEquals(2, updated.size)
            assertEquals("due", updated.last().text)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun observeConversationAndIdentity() = runBlocking {
        val peer = makePeer("Gino")
        db.peers.upsert(peer)
        val chat = db.conversations.ensureDirectConversation(with = peer)
        val values = Channel<Boolean?>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) {
            db.conversations.observe(id = chat.id).collect { values.send(it?.isPinned) }
        }
        try {
            assertEquals(false, withTimeout(5_000) { values.receive() })
            db.conversations.setPinned(true, conversationID = chat.id)
            assertEquals(true, withTimeout(5_000) { values.receive() })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun relayQueueEnforcesCountLimit() = runBlocking {
        val queue = RelayQueueStore(database = db, maxCount = 5, maxTotalBytes = MeshProtocol.relayQueueMaxBytes)
        val ids = mutableListOf<ByteArray>()
        val base = System.currentTimeMillis()
        for (index in 0 until 7) {
            val id = CryptoEngine.randomMessageID()
            ids += id
            queue.enqueue(
                RelayQueueRecord(
                    msgID = id, recipientID = ByteArray(8) { 1 },
                    rawEnvelopeBytes = ByteArray(50), enqueuedAt = base + index * 1_000L,
                ),
                now = base + index * 1_000L,
            )
        }
        val stats = queue.stats()
        assertEquals(5, stats.count)
        assertEquals(250, stats.totalBytes)
        val pending = queue.pending(now = base + 10_000)
        assertEquals(ids.drop(2).map { it.toHex() }, pending.map { it.msgID.toHex() })
        assertEquals(2, queue.pending(now = base + 10_000, limit = 2).size)
        assertEquals(5, queue.pending(recipientID = ByteArray(8) { 1 }, now = base + 10_000).size)
        queue.remove(msgIDs = ids.drop(2).take(2))
        queue.remove(msgID = ids.last())
        assertEquals(2, queue.stats().count)
    }

    @Test
    fun relayQueueEnforcesByteLimitAndExpiry() = runBlocking {
        val queue = RelayQueueStore(database = db, maxCount = 200, maxTotalBytes = 1_000)
        val base = System.currentTimeMillis()
        val first = RelayQueueRecord(
            msgID = CryptoEngine.randomMessageID(), recipientID = ByteArray(8) { 2 },
            rawEnvelopeBytes = ByteArray(400), enqueuedAt = base,
        )
        queue.enqueue(first, now = base)
        queue.enqueue(first, now = base) // duplicato ignorato
        queue.enqueue(
            RelayQueueRecord(CryptoEngine.randomMessageID(), ByteArray(8) { 2 }, ByteArray(400), enqueuedAt = base + 1_000),
            now = base + 1_000,
        )
        queue.enqueue(
            RelayQueueRecord(CryptoEngine.randomMessageID(), ByteArray(8) { 2 }, ByteArray(400), enqueuedAt = base + 2_000),
            now = base + 2_000,
        )
        var stats = queue.stats()
        assertEquals(2, stats.count)
        assertEquals(800, stats.totalBytes)
        assertFalse(queue.pending(now = base).any { it.msgID.contentEquals(first.msgID) })

        try {
            queue.enqueue(RelayQueueRecord(CryptoEngine.randomMessageID(), ByteArray(8) { 2 }, ByteArray(5_000)))
            fail("atteso PacketTooLarge")
        } catch (failure: RelayQueueStore.Failure) {
            assertEquals(RelayQueueStore.Failure.PacketTooLarge(5_000), failure)
        }

        // Dopo 6 ore + 10 s tutto e' scaduto.
        val later = base + MeshProtocol.relayQueueLifetime + 10_000
        assertTrue(queue.pending(now = later).isEmpty())
        assertEquals(2, queue.purgeExpired(now = later))
        stats = queue.stats()
        assertEquals(0, stats.count)
    }

    @Test
    fun seenPacketsDeduplicate() = runBlocking {
        val id = CryptoEngine.randomMessageID()
        val now = System.currentTimeMillis()
        assertTrue(db.seenPackets.markSeenIfNew(msgID = id, now = now))
        assertFalse(db.seenPackets.markSeenIfNew(msgID = id, now = now))
        assertTrue(db.seenPackets.contains(msgID = id))
        assertEquals(listOf(id.toHex()), db.seenPackets.activeMessageIDs(now = now).map { it.toHex() })

        val later = now + MeshProtocol.seenPacketLifetime + 1_000
        assertEquals(1, db.seenPackets.purgeExpired(now = later))
        assertEquals(0, db.seenPackets.count())
    }

    @Test
    fun cabinLoungeExpiresAfter12Hours() = runBlocking {
        val start = System.currentTimeMillis()
        val lounge = db.conversations.currentCabinLounge(now = start)
        assertEquals(ConversationType.cabinLounge, lounge.type)
        assertTrue(lounge.recipientID.contentEquals(MeshProtocol.loungeRecipientID))
        assertEquals(start + MeshProtocol.loungeLifetime, lounge.expiresAtMs)

        db.messages.insert(
            MessageRecord(
                msgID = CryptoEngine.randomMessageID(), conversationID = lounge.id,
                senderNodeID = ByteArray(8) { 3 }, senderNickname = "31F",
                contentType = MessageContentType.text, text = "Caricatore?", isOutgoing = false,
                deliveryStatus = DeliveryStatus.deliveredAck, createdAtMs = start,
            ),
        )
        assertEquals(1, db.messages.fetch(conversationID = lounge.id).size)

        val sameLounge = db.conversations.currentCabinLounge(now = start + 3_600_000)
        assertEquals(lounge.expiresAtMs, sameLounge.expiresAtMs)

        val renewed = db.conversations.currentCabinLounge(now = start + 13 * 3_600_000L)
        assertNotEquals(lounge.expiresAtMs, renewed.expiresAtMs)
        assertTrue(db.messages.fetch(conversationID = lounge.id).isEmpty())
    }

    @Test
    fun maintenancePurgesExpiredData() = runBlocking {
        val start = System.currentTimeMillis()
        val lounge = db.conversations.currentCabinLounge(now = start)
        db.messages.insert(
            MessageRecord(
                msgID = CryptoEngine.randomMessageID(), conversationID = lounge.id, senderNodeID = ByteArray(8),
                contentType = MessageContentType.text, text = "x", isOutgoing = false,
                deliveryStatus = DeliveryStatus.deliveredAck, createdAtMs = start,
            ),
        )
        db.seenPackets.markSeenIfNew(CryptoEngine.randomMessageID(), now = start)
        db.relayQueue.enqueue(RelayQueueRecord(CryptoEngine.randomMessageID(), ByteArray(8), ByteArray(10), enqueuedAt = start), now = start)

        db.runMaintenance(now = start + 13 * 3_600_000L)
        assertNull(db.conversations.fetch(id = lounge.id))
        assertEquals(0, db.seenPackets.count())
        assertEquals(0, db.relayQueue.stats().count)
    }

    @Test
    fun pinnedConversationsComeFirst() = runBlocking {
        val a = makePeer("A", trusted = true)
        val b = makePeer("B", trusted = true)
        db.peers.upsert(a)
        db.peers.upsert(b)
        val chatA = db.conversations.ensureDirectConversation(with = a)
        val chatB = db.conversations.ensureDirectConversation(with = b)
        val base = MessageRecord.outgoingText("vecchio", conversationID = chatA.id, sender = IdentityKeys.generate())
        val older = base.copy(createdAtMs = base.createdAtMs - 60_000)
        db.messages.insert(older)
        db.messages.insert(MessageRecord.outgoingText("nuovo", conversationID = chatB.id, sender = IdentityKeys.generate()))
        assertEquals(listOf(chatB.id, chatA.id), db.conversations.fetchPermanent().map { it.id })

        db.conversations.setPinned(true, conversationID = chatA.id)
        val pinned = db.conversations.fetchPermanent()
        assertEquals(listOf(chatA.id, chatB.id), pinned.map { it.id })
        assertTrue(pinned.first().isPinned)

        db.conversations.setPinned(false, conversationID = chatA.id)
        assertEquals(listOf(chatB.id, chatA.id), db.conversations.fetchPermanent().map { it.id })

        db.conversations.delete(conversationID = chatA.id)
        assertEquals(listOf(chatB.id), db.conversations.fetchPermanent().map { it.id })
        assertNull("i messaggi seguono la chat eliminata", db.messages.fetch(msgID = older.msgID))
    }

    @Test
    fun groupConversation() = runBlocking {
        val a = makePeer("A", trusted = true)
        val b = makePeer("B", trusted = true)
        db.peers.upsert(a)
        db.peers.upsert(b)

        val groupID = UUID.randomUUID()
        val group = db.conversations.createGroup(id = groupID, title = "Volo FR1234", members = listOf(a))
        db.conversations.addGroupMember(b, to = group.id)
        assertEquals(32, group.groupSecretKey?.size)
        assertEquals(groupID, group.groupID)
        assertTrue(group.recipientID.contentEquals(CryptoEngine.groupRecipientID(groupID = groupID)))
        assertEquals(group.id, db.conversations.fetch(recipientID = group.recipientID)?.id)
        assertEquals(2, db.conversations.groupMembers(of = group.id).size)
        assertEquals(setOf(group.id), db.conversations.groupIDs(containing = b.nodeID))

        val until = System.currentTimeMillis() + MeshProtocol.proPassDuration
        db.conversations.setProUnlocked(until = until, conversationID = group.id)
        assertEquals(true, db.conversations.fetch(id = group.id)?.isProUnlocked())
        assertEquals(listOf(group.id), db.conversations.fetchPermanent().map { it.id })

        db.conversations.updateTitle("Volo FR9999", conversationID = group.id)
        assertEquals("Volo FR9999", db.conversations.fetch(id = group.id)?.title)
        db.conversations.delete(conversationID = group.id)
        assertTrue("i membri seguono il gruppo eliminato", db.conversations.groupMembers(of = group.id).isEmpty())
    }

    @Test
    fun groupReadRequiresAllMembers() = runBlocking {
        val me = IdentityKeys.generate()
        val a = makePeer("A", trusted = true)
        val b = makePeer("B", trusted = true)
        val outsider = makePeer("Estraneo")
        for (peer in listOf(a, b, outsider)) db.peers.upsert(peer)
        val group = db.conversations.createGroup(title = "Volo FR1234", members = listOf(a, b))
        val message = MessageRecord.outgoingText("Imbarco al gate 12", conversationID = group.id, sender = me)
        db.messages.insert(message)
        db.messages.markSentMesh(listOf(message.msgID))

        // Primo lettore: registrato, il messaggio diventa solo "consegnato".
        assertEquals(listOf(message.msgID.toHex()), db.messages.markReadByRecipient(listOf(message.msgID), a.nodeID).map { it.toHex() })
        assertEquals(DeliveryStatus.deliveredAck, db.messages.fetch(message.msgID)?.deliveryStatus)
        // Conferma duplicata e lettore non membro: nessun avanzamento.
        assertTrue(db.messages.markReadByRecipient(listOf(message.msgID), a.nodeID).isEmpty())
        assertTrue(db.messages.markReadByRecipient(listOf(message.msgID), outsider.nodeID).isEmpty())
        assertEquals(DeliveryStatus.deliveredAck, db.messages.fetch(message.msgID)?.deliveryStatus)
        assertEquals(
            setOf(a.nodeID.toHex(), outsider.nodeID.toHex()),
            db.messages.readerNodeIDs(message.msgID).map { it.toHex() }.toSet(),
        )

        // Ultimo membro: spunta blu.
        assertEquals(listOf(message.msgID.toHex()), db.messages.markReadByRecipient(listOf(message.msgID), b.nodeID).map { it.toHex() })
        assertEquals(DeliveryStatus.read, db.messages.fetch(message.msgID)?.deliveryStatus)

        // Un membro che entra dopo non riporta indietro un messaggio gia' letto da tutti.
        db.conversations.addGroupMember(makePeer("C"), to = group.id)
        assertTrue(db.messages.markReadByRecipient(listOf(message.msgID), a.nodeID).isEmpty())
        assertEquals(DeliveryStatus.read, db.messages.fetch(message.msgID)?.deliveryStatus)

        // I lettori seguono il messaggio eliminato.
        db.messages.delete(message.msgID)
        assertTrue(db.messages.readerNodeIDs(message.msgID).isEmpty())
    }

    @Test
    fun groupReadWaitsForMembersKnownNow() = runBlocking {
        val me = IdentityKeys.generate()
        val a = makePeer("A", trusted = true)
        val c = makePeer("C", trusted = true)
        db.peers.upsert(a)
        db.peers.upsert(c)
        val group = db.conversations.createGroup(title = "Volo AZ610", members = listOf(a))
        val message = MessageRecord.outgoingText("Ciao a tutti", conversationID = group.id, sender = me)
        db.messages.insert(message)
        db.conversations.addGroupMember(c, to = group.id)

        db.messages.markReadByRecipient(listOf(message.msgID), a.nodeID)
        assertEquals(DeliveryStatus.deliveredAck, db.messages.fetch(message.msgID)?.deliveryStatus)
        db.messages.markReadByRecipient(listOf(message.msgID), c.nodeID)
        assertEquals(DeliveryStatus.read, db.messages.fetch(message.msgID)?.deliveryStatus)
    }
}
