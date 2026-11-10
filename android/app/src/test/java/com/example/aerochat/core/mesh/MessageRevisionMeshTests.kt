package com.example.aerochat.core.mesh

import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.moderation.AppSettings
import com.example.aerochat.core.protocol.InnerPayloadFactory
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.MeshWire
import com.example.aerochat.core.protocol.toByteString
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.MessageContentType
import com.example.aerochat.data.records.MessageRecord
import com.example.aerochat.data.records.canBeEdited
import com.example.aerochat.data.records.canBeRevised
import com.example.aerochat.proto.MessageRevision
import com.example.aerochat.proto.PacketType
import com.example.aerochat.proto.RevisionAction
import java.util.UUID
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Release 1.2: modifica e cancellazione per tutti (`MESSAGE_REVISION`), contratto MESSAGE_REVISIONS_SPEC.md. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageRevisionMeshTests : MeshTestCase() {

    @Before
    fun resetSettings() {
        AppSettings.resetForTesting()
    }

    @After
    fun restoreSettings() {
        AppSettings.resetForTesting()
    }

    private suspend fun pairedAndConnected(): Pair<TestNode, TestNode> {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        return alice to bob
    }

    /** Gruppo con la stessa chiave su tutti i nodi (come un invito gia' accettato). */
    private suspend fun group(vararg nodes: TestNode): String {
        val groupID = UUID.randomUUID()
        val key = CryptoEngine.randomGroupSecretKey()
        for (n in nodes) n.database.conversations.createGroup(id = groupID, title = "Volo", secretKey = key)
        return groupID.toString().lowercase()
    }

    private suspend fun TestNode.conversation(id: String): ConversationRecord =
        requireNotNull(database.conversations.fetch(id = id))

    // MARK: - Wire

    @Test
    fun revisionProtoRoundTrip() {
        val target = CryptoEngine.randomMessageID()
        val revision = MessageRevision.newBuilder()
            .setTargetMsgId(target.toByteString())
            .setAction(RevisionAction.EDIT)
            .setNewText("Gate B12, non B21")
            .setRevisedAtMs(1_762_000_000_123L)
            .build()
        val decoded = MeshWire.parseMessageRevision(MeshWire.serialize(revision))
        assertTrue(decoded.targetMsgId.toByteArray().contentEquals(target))
        assertEquals(RevisionAction.EDIT, decoded.action)
        assertEquals("Gate B12, non B21", decoded.newText)
        assertEquals(1_762_000_000_123L, decoded.revisedAtMs)
        // Numeri di campo/enum del contratto (compatibilita' con iOS).
        assertEquals(14, PacketType.MESSAGE_REVISION.number)
        assertEquals(1, RevisionAction.EDIT.number)
        assertEquals(2, RevisionAction.DELETE.number)
        assertTrue(PacketType.MESSAGE_REVISION.isStoreAndForwardEligible)
        // Byte attesi: campo 1 (bytes, 16), campo 2 (varint 2), campo 4 (varint).
        val delete = MessageRevision.newBuilder()
            .setTargetMsgId(ByteArray(16) { 1 }.toByteString())
            .setAction(RevisionAction.DELETE)
            .setRevisedAtMs(1)
            .build()
        val bytes = MeshWire.serialize(delete)
        assertEquals(0x0A, bytes[0].toInt())
        assertEquals(16, bytes[1].toInt())
        assertEquals(0x10, bytes[18].toInt())
        assertEquals(2, bytes[19].toInt())
        assertEquals(0x20, bytes[20].toInt())
        assertEquals(1, bytes[21].toInt())
    }

    // MARK: - 1-a-1

    @Test
    fun editDirectMessage() = meshTest {
        val (alice, bob) = pairedAndConnected()
        val conversation = alice.directConversation(with = bob)
        val sent = alice.router.sendText("Ci vediamo al gate B21", conversation = conversation)
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID) != null })

        alice.router.sendRevision(sent, action = RevisionAction.EDIT, newText = "  Ci vediamo al gate B12 ", conversation = conversation)
        val local = requireNotNull(alice.database.messages.fetch(msgID = sent.msgID))
        assertEquals("Ci vediamo al gate B12", local.text)
        assertNotNull(local.editedAtMs)

        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID)?.text == "Ci vediamo al gate B12" })
        assertEquals(local.editedAtMs, bob.database.messages.fetch(msgID = sent.msgID)?.editedAtMs)
        // DELIVERY_ACK della revisione: la coda del mittente si svuota.
        assertTrue(eventually { alice.database.relayQueue.stats().count == 0 })
        // Nessuna notifica per la revisione (solo quella del messaggio originale).
        assertEquals(1, bob.notifier.notified.size)
    }

    @Test
    fun deleteDirectMessageForEveryone() = meshTest {
        val (alice, bob) = pairedAndConnected()
        val conversation = alice.directConversation(with = bob)
        val sent = alice.router.sendText("Messaggio sbagliato", conversation = conversation)
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID) != null })
        val bobConversation = bob.directConversation(with = alice)
        assertEquals(1, bob.database.conversations.fetch(id = bobConversation.id)?.unreadCount)
        assertEquals(1, bob.database.conversations.totalUnread())

        val deletion = alice.router.sendRevision(sent, action = RevisionAction.DELETE, conversation = conversation)
        assertNotNull(deletion)
        assertTrue(alice.database.messages.fetch(msgID = sent.msgID)?.isDeleted == true)

        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID)?.isDeleted == true })
        val tombstone = requireNotNull(bob.database.messages.fetch(msgID = sent.msgID))
        assertNull(tombstone.text)
        // Un messaggio cancellato non conta piu' come non letto.
        assertEquals(0, bob.database.conversations.fetch(id = bobConversation.id)?.unreadCount)
        assertEquals(0, bob.database.conversations.totalUnread())
        assertTrue(eventually { alice.database.relayQueue.stats().count == 0 })

        // DELETE e' definitivo: un EDIT successivo viene rifiutato gia' in invio.
        try {
            alice.router.sendRevision(sent, action = RevisionAction.EDIT, newText = "Ripensamento", conversation = conversation)
            fail("EDIT dopo DELETE deve essere rifiutato")
        } catch (_: RouterError.RevisionNotAllowed) {
        }
    }

    // MARK: - Gruppo

    @Test
    fun editAndDeleteGroupMessage() = meshTest {
        val (alice, bob) = pairedAndConnected()
        val groupID = group(alice, bob)
        val aliceGroup = alice.conversation(groupID)

        val first = alice.router.sendText("Taxi alle 9", conversation = aliceGroup)
        val second = alice.router.sendText("Foto dopo", conversation = aliceGroup)
        assertTrue(eventually { bob.database.messages.fetch(msgID = second.msgID) != null })
        assertEquals(2, bob.database.conversations.fetch(id = groupID)?.unreadCount)

        alice.router.sendRevision(first, action = RevisionAction.EDIT, newText = "Taxi alle 10", conversation = aliceGroup)
        alice.router.sendRevision(second, action = RevisionAction.DELETE, conversation = aliceGroup)

        assertTrue(eventually { bob.database.messages.fetch(msgID = first.msgID)?.text == "Taxi alle 10" })
        assertTrue(eventually { bob.database.messages.fetch(msgID = second.msgID)?.isDeleted == true })
        assertEquals(1, bob.database.conversations.fetch(id = groupID)?.unreadCount)
        assertTrue(eventually { alice.database.relayQueue.stats().count == 0 })
    }

    // MARK: - Validazioni

    @Test
    fun revisionOutsideWindowIsRejected() = meshTest {
        val (alice, bob) = pairedAndConnected()
        val conversation = alice.directConversation(with = bob)
        val now = System.currentTimeMillis()

        // Invio: un nostro messaggio di 16 minuti fa non si modifica piu'.
        val old = MessageRecord.outgoingText("Vecchio", conversationID = conversation.id, sender = alice.identity)
            .copy(createdAtMs = now - 16 * 60_000L)
        alice.database.messages.insert(old)
        assertFalse(old.canBeRevised(now))
        assertFalse(old.canBeEdited(now))
        try {
            alice.router.sendRevision(old, action = RevisionAction.DELETE, conversation = conversation)
            fail("fuori finestra")
        } catch (_: RouterError.RevisionNotAllowed) {
        }

        // Ricezione: la copia del destinatario e' di 20 minuti fa (oltre 15 + 2 di tolleranza): scartata.
        val msgID = CryptoEngine.randomMessageID()
        alice.database.messages.insert(
            MessageRecord.outgoingText("Testo", conversationID = conversation.id, sender = alice.identity, msgID = msgID),
        )
        val bobConversation = bob.directConversation(with = alice)
        bob.database.messages.insert(
            MessageRecord(
                msgID = msgID, conversationID = bobConversation.id, senderNodeID = alice.nodeID,
                contentType = MessageContentType.text, text = "Testo", isOutgoing = false,
                deliveryStatus = DeliveryStatus.deliveredAck, createdAtMs = now - 20 * 60_000L,
            ),
        )
        val outgoing = requireNotNull(alice.database.messages.fetch(msgID = msgID))
        alice.router.sendRevision(outgoing, action = RevisionAction.EDIT, newText = "Modificato", conversation = conversation)
        // L'ACK arriva comunque (il mittente smette di ritrasmettere), ma il testo non cambia.
        assertTrue(eventually { alice.database.relayQueue.stats().count == 0 })
        assertEquals("Testo", bob.database.messages.fetch(msgID = msgID)?.text)
        assertNull(bob.database.messages.fetch(msgID = msgID)?.editedAtMs)
    }

    @Test
    fun revisionFromWrongAuthorIsRejected() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        // Senza relay: la coda di Mallory contiene solo la sua revisione (rimossa dall'ACK di Bob).
        val mallory = node("Mallory", relayEnabled = false)
        TestNode.pair(alice, bob)
        TestNode.pair(mallory, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val groupID = group(alice, bob, mallory)

        val sent = alice.router.sendText("Ritrovo al nastro 4", conversation = alice.conversation(groupID))
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID) != null })

        // Mallory finge che il messaggio di Alice sia suo e prova a cancellarlo per tutti.
        mallory.database.messages.insert(sent.copy(conversationID = groupID, senderNodeID = mallory.nodeID))
        val forged = requireNotNull(mallory.database.messages.fetch(msgID = sent.msgID))
        FakeMeshNetwork.connect(mallory.transport, bob.transport)
        mallory.router.sendRevision(forged, action = RevisionAction.DELETE, conversation = mallory.conversation(groupID))
        assertTrue(eventually { mallory.database.relayQueue.stats().count == 0 })
        delay(200)
        val stored = requireNotNull(bob.database.messages.fetch(msgID = sent.msgID))
        assertFalse(stored.isDeleted)
        assertEquals("Ritrovo al nastro 4", stored.text)
    }

    // MARK: - Revisione prima dell'originale

    @Test
    fun revisionBeforeOriginalIsAppliedLater() = meshTest {
        val (alice, bob) = pairedAndConnected()
        val conversation = alice.directConversation(with = bob)

        // Il messaggio esiste solo da Alice: la revisione parte (e arriva) prima dell'originale.
        val original = MessageRecord.outgoingText("Imbarco alle 7", conversationID = conversation.id, sender = alice.identity)
        alice.database.messages.insert(original)
        alice.router.sendRevision(original, action = RevisionAction.EDIT, newText = "Imbarco alle 7:30", conversation = conversation)
        assertTrue(eventually { alice.database.relayQueue.stats().count == 0 })
        assertNull(bob.database.messages.fetch(msgID = original.msgID))

        val payload = InnerPayloadFactory.text("Imbarco alle 7", identity = alice.identity, nickname = "Alice")
        alice.router.sendPacket(
            type = PacketType.CHAT_TEXT, payload = payload, conversation = conversation,
            msgID = original.msgID, retainUntilAck = true,
        )
        assertTrue(eventually { bob.database.messages.fetch(msgID = original.msgID)?.text == "Imbarco alle 7:30" })
        assertNotNull(bob.database.messages.fetch(msgID = original.msgID)?.editedAtMs)
        // La notifica mostra gia' il testo modificato.
        assertTrue(eventually { bob.notifier.notified.any { it.text == "Imbarco alle 7:30" } })

        // Cancellazione arrivata prima: nessuna notifica e nessun non letto.
        val deleted = MessageRecord.outgoingText("Ops", conversationID = conversation.id, sender = alice.identity)
        alice.database.messages.insert(deleted)
        alice.router.sendRevision(deleted, action = RevisionAction.DELETE, conversation = conversation)
        assertTrue(eventually { alice.database.relayQueue.stats().count == 0 })
        alice.router.sendPacket(
            type = PacketType.CHAT_TEXT,
            payload = InnerPayloadFactory.text("Ops", identity = alice.identity, nickname = "Alice"),
            conversation = conversation, msgID = deleted.msgID, retainUntilAck = true,
        )
        assertTrue(eventually { bob.database.messages.fetch(msgID = deleted.msgID)?.isDeleted == true })
        assertFalse(bob.notifier.notified.any { it.msgID.contentEquals(deleted.msgID) })
        val bobConversation = bob.directConversation(with = alice)
        assertEquals(1, bob.database.conversations.fetch(id = bobConversation.id)?.unreadCount)
    }

    // MARK: - Cabina Pubblica

    @Test
    fun loungeEditIsMaskedAndLinksRejected() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val lounge = alice.database.conversations.currentCabinLounge()
        val sent = alice.router.sendText("Ritardo di un'ora", conversation = lounge)
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID) != null })

        try {
            alice.router.sendRevision(sent, action = RevisionAction.EDIT, newText = "vedi www.esempio.it", conversation = lounge)
            fail("niente link nella Lounge")
        } catch (_: RouterError.LinkNotAllowedInLounge) {
        }

        alice.router.sendRevision(sent, action = RevisionAction.EDIT, newText = "Che cazzo di ritardo", conversation = lounge)
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID)?.text == "Che c**** di ritardo" })
    }

    // MARK: - Database

    @Test
    fun deleteRemovesMediaAndLatestEditWins() = meshTest {
        val database = node("Io").database
        val lounge = database.conversations.currentCabinLounge()
        val group = database.conversations.createGroup(id = UUID.randomUUID(), title = "Volo", secretKey = CryptoEngine.randomGroupSecretKey())
        val photo = MessageRecord(
            msgID = CryptoEngine.randomMessageID(), conversationID = group.id, senderNodeID = ByteArray(8) { 3 },
            contentType = MessageContentType.image, mediaLocalPath = "media/a.webp", blurhash = "LEHV6nWB2yk8",
            isOutgoing = false, deliveryStatus = DeliveryStatus.deliveredAck,
        )
        database.messages.insert(photo)
        assertEquals(1, database.conversations.fetch(id = group.id)?.unreadCount)
        val deletion = requireNotNull(database.messages.applyDelete(photo.msgID))
        assertEquals("media/a.webp", deletion.mediaPath)
        assertTrue(deletion.wasUnread)
        val tombstone = requireNotNull(database.messages.fetch(photo.msgID))
        assertTrue(tombstone.isDeleted)
        assertNull(tombstone.mediaLocalPath)
        assertNull(tombstone.blurhash)
        assertEquals(0, database.conversations.fetch(id = group.id)?.unreadCount)
        assertNull("DELETE e' definitivo", database.messages.applyDelete(photo.msgID))
        // Il file arrivato dopo la cancellazione non viene ricollegato.
        assertFalse(database.messages.updateMedia(localPath = "media/a.webp", msgID = photo.msgID))

        val text = MessageRecord(
            msgID = CryptoEngine.randomMessageID(), conversationID = lounge.id, senderNodeID = ByteArray(8) { 4 },
            contentType = MessageContentType.text, text = "v1", isOutgoing = false, deliveryStatus = DeliveryStatus.deliveredAck,
        )
        database.messages.insert(text)
        assertTrue(database.messages.applyEdit(text.msgID, newText = "v3", editedAtMs = 300))
        assertFalse("vince l'EDIT piu' recente", database.messages.applyEdit(text.msgID, newText = "v2", editedAtMs = 200))
        assertEquals("v3", database.messages.fetch(text.msgID)?.text)
        // La Cabina Pubblica non conta nei badge.
        assertEquals(0, database.conversations.totalUnread())
        assertEquals(MeshProtocol.revisionWindow, 900_000L)
    }
}
