package com.example.aerochat.core.media

import com.example.aerochat.core.crypto.IdentityKeys
import com.example.aerochat.core.crypto.PairingPayload
import com.example.aerochat.core.mesh.MeshRouterActor
import com.example.aerochat.core.mesh.MeshTestCase
import com.example.aerochat.core.mesh.RecordingNotifier
import com.example.aerochat.core.mesh.eventually
import com.example.aerochat.core.transport.BLETransportActor
import com.example.aerochat.core.transport.L2CAPConnection
import com.example.aerochat.core.transport.LinkID
import com.example.aerochat.core.transport.LinkRole
import com.example.aerochat.core.transport.TransportProvider
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.IdentityRecord
import com.example.aerochat.data.records.MessageContentType
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressione "le foto non partono / non arrivano": trasferimento di foto sull'intero stack reale
 * (MediaTransferService -> MeshRouterActor -> BLETransportActor -> L2CAPConnection -> stream) con il
 * solo socket Bluetooth sostituito da pipe lente. Raffiche di decine di chunk da ~8.5 KB, in entrambe
 * le direzioni e mescolate a messaggi di testo, devono arrivare complete e byte-identiche.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MediaBurstTransferTests : MeshTestCase() {

    /** `BLETransportActor` vero ma senza radio: `start`/`stop` non accendono i controller BLE. */
    private class RadiolessTransport(val actor: BLETransportActor) : TransportProvider by actor {
        override suspend fun start() {}
        override suspend fun stop() {}
    }

    private class StackNode(
        val nickname: String,
        val identity: IdentityKeys,
        val database: AppDatabase,
        val actor: BLETransportActor,
        val router: MeshRouterActor,
        val media: MediaTransferService,
    ) {
        val nodeID: ByteArray get() = identity.nodeID
        val pairing: PairingPayload get() = PairingPayload(identity = identity, nickname = nickname)
    }

    private val stackNodes = ArrayList<StackNode>()
    private val connections = ArrayList<L2CAPConnection>()

    @After
    fun tearDownStack() = runBlocking {
        connections.forEach { it.close() }
        for (node in stackNodes) {
            node.media.stop()
            node.router.stop()
        }
        delay(50)
        stackNodes.forEach { it.database.close() }
    }

    private suspend fun stackNode(nickname: String): StackNode {
        val identity = IdentityKeys.generate()
        val database = AppDatabase.makeTemporary(context)
        database.identities.save(IdentityRecord(identity = identity, nickname = nickname, avatarColorHex = "00E5FF"))
        val actor = BLETransportActor(context, identity.nodeID)
        val router = MeshRouterActor(
            identity = identity, database = database, transport = RadiolessTransport(actor),
            nickname = nickname, notifier = RecordingNotifier(),
            configuration = MeshRouterActor.Configuration(relayBackoff = 0L..0L, maintenanceInterval = 3_600_000L),
        )
        router.start()
        val media = MediaTransferService(
            router = router, database = database, store = MediaStore.makeTemporary(),
            identity = identity, nickname = nickname,
        )
        media.start()
        return StackNode(nickname, identity, database, actor, router, media).also { stackNodes += it }
    }

    /** Canale L2CAP simulato: due pipe da [pipeBuffer] byte (radio lenta: lo scrittore si blocca spesso). */
    private suspend fun link(a: StackNode, b: StackNode, pipeBuffer: Int = 2_048) {
        val aToB = PipedInputStream(pipeBuffer)
        val aOut = PipedOutputStream(aToB)
        val bToA = PipedInputStream(pipeBuffer)
        val bOut = PipedOutputStream(bToA)
        val idOnA = LinkID(UUID.randomUUID().toString(), LinkRole.central)
        val idOnB = LinkID(UUID.randomUUID().toString(), LinkRole.peripheral)
        val connA = L2CAPConnection(
            id = idOnA, inputStream = bToA, outputStream = aOut,
            onFrame = { frame -> runBlocking { a.actor.received(frame, from = idOnA) } },
            onClose = {},
        )
        val connB = L2CAPConnection(
            id = idOnB, inputStream = aToB, outputStream = bOut,
            onFrame = { frame -> runBlocking { b.actor.received(frame, from = idOnB) } },
            onClose = {},
        )
        connections += connA
        connections += connB
        // Come dopo lo scambio del node_id via GATT.
        a.actor.identifyForTesting(idOnA.deviceID, b.nodeID, LinkRole.central)
        b.actor.identifyForTesting(idOnB.deviceID, a.nodeID, LinkRole.peripheral)
        a.actor.register(connA, idOnA)
        b.actor.register(connB, idOnB)
        connA.open()
        connB.open()
        assertTrue(eventually { a.router.isDirectNeighbor(b.nodeID) && b.router.isDirectNeighbor(a.nodeID) })
    }

    private suspend fun pairAndLink(a: StackNode, b: StackNode) {
        a.router.pair(with = b.pairing)
        b.router.pair(with = a.pairing)
        link(a, b)
    }

    private suspend fun StackNode.conversation(with: StackNode): ConversationRecord {
        val peer = database.peers.fetch(nodeID = with.nodeID) ?: error("peer mancante")
        return database.conversations.ensureDirectConversation(with = peer)
    }

    /** Container RIFF/WEBP con contenuto casuale (incomprimibile come un vero WebP): `size / 8192` chunk. */
    private fun syntheticWebP(size: Int, seed: Int): ImageProcessor.Result {
        val data = Random(seed).nextBytes(size)
        "RIFF".toByteArray().copyInto(data, 0)
        "WEBP".toByteArray().copyInto(data, 8)
        return ImageProcessor.Result(data, blurhash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj", width = 1_280, height = 960, quality = 65f)
    }

    private fun photo(width: Int, height: Int): android.graphics.Bitmap {
        val pixels = ByteArray(width * height * 4) { 255.toByte() }
        val random = java.util.Random(3)
        for (y in 0 until height) for (x in 0 until width) {
            val index = (y * width + x) * 4
            val fx = x.toDouble() / maxOf(1, width - 1)
            val fy = y.toDouble() / maxOf(1, height - 1)
            pixels[index] = ((fx * 200) + random.nextInt(55)).toInt().toByte()
            pixels[index + 1] = ((fy * 200) + random.nextInt(55)).toInt().toByte()
            pixels[index + 2] = ((((sin(fx * 40) * cos(fy * 31) + 1) / 2) * 200) + random.nextInt(55)).toInt().toByte()
        }
        return RGBAPixels.makeImage(pixels, width, height)!!
    }

    private suspend fun assertReceived(receiver: StackNode, msgID: ByteArray, expected: ByteArray) {
        assertTrue(
            "media non completato",
            eventually(timeout = 30_000) { receiver.database.messages.fetch(msgID = msgID)?.mediaLocalPath != null },
        )
        val message = receiver.database.messages.fetch(msgID = msgID)!!
        assertEquals(MessageContentType.image, message.contentType)
        assertArrayEquals(expected, receiver.media.store.read(relativePath = message.mediaLocalPath!!))
    }

    @Test
    fun realPhotoCrossesFullStack() = meshTest {
        val alice = stackNode("Alice")
        val bob = stackNode("Bob")
        pairAndLink(alice, bob)

        val image = ImageProcessor.prepareForMesh(photo(1_600, 1_200))
        assertTrue("servono piu' chunk", image.webp.size > 4 * MediaTransferService.chunkSize)
        val sent = alice.media.sendImage(image, alice.conversation(with = bob))
        assertEquals(DeliveryStatus.sentMesh, sent.deliveryStatus)

        assertReceived(bob, sent.msgID, image.webp)
        // La foto ricevuta si decodifica (stessa strada della bolla in chat).
        val decoded = WebPEncoder.decode(bob.media.store.read(bob.database.messages.fetch(sent.msgID)!!.mediaLocalPath!!))
        assertEquals(image.width, decoded.width)
        assertEquals(image.height, decoded.height)
        assertTrue(
            "ACK del destinatario atteso",
            eventually(timeout = 10_000) { alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.deliveredAck },
        )
    }

    @Test
    fun simultaneousBurstsBothWaysWithTextInterleaved() = meshTest(timeoutMs = 120_000) {
        val alice = stackNode("Alice")
        val bob = stackNode("Bob")
        pairAndLink(alice, bob)
        val aliceToBob = syntheticWebP(600_000, seed = 1) // 74 chunk
        val bobToAlice = syntheticWebP(400_000, seed = 2) // 49 chunk
        val aliceConversation = alice.conversation(with = bob)
        val bobConversation = bob.conversation(with = alice)

        val first = async { alice.media.sendImage(aliceToBob, aliceConversation) }
        val second = async { bob.media.sendImage(bobToAlice, bobConversation) }
        val texts = (1..5).map { alice.router.sendText("ciao $it", conversation = aliceConversation) }
        val sentByAlice = first.await()
        val sentByBob = second.await()

        assertReceived(bob, sentByAlice.msgID, aliceToBob.webp)
        assertReceived(alice, sentByBob.msgID, bobToAlice.webp)
        for (text in texts) {
            assertTrue(eventually { bob.database.messages.fetch(msgID = text.msgID) != null })
        }
    }

    @Test
    fun consecutivePhotosAllArrive() = meshTest(timeoutMs = 120_000) {
        val alice = stackNode("Alice")
        val bob = stackNode("Bob")
        pairAndLink(alice, bob)
        val conversation = alice.conversation(with = bob)
        val images = (1..6).map { syntheticWebP(80_000 + it * 1_000, seed = 10 + it) }
        val sent = images.map { alice.media.sendImage(it, conversation) }
        sent.zip(images).forEach { (message, image) -> assertReceived(bob, message.msgID, image.webp) }
    }
}
