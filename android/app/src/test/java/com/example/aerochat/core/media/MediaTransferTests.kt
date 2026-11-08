package com.example.aerochat.core.media

import com.example.aerochat.core.mesh.FakeMeshNetwork
import com.example.aerochat.core.mesh.MeshTestCase
import com.example.aerochat.core.mesh.TestNode
import com.example.aerochat.core.mesh.eventually
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.MessageContentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/** Parti "trasferimento sulla mesh" di `MediaTests.swift` (MediaTransferService su FakeMeshNetwork). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MediaTransferTests : MeshTestCase() {

    private val originalEncoderFactory = OpusCodec.encoderFactory
    private val originalDecoderFactory = OpusCodec.decoderFactory
    private val services = ArrayList<MediaTransferService>()

    @Before
    fun setUp() = FakeOpus.install()

    @After
    fun tearDown() {
        services.forEach { it.stop() }
        OpusCodec.encoderFactory = originalEncoderFactory
        OpusCodec.decoderFactory = originalDecoderFactory
    }

    private fun makeImage(width: Int, height: Int): android.graphics.Bitmap {
        val pixels = ByteArray(width * height * 4) { 255.toByte() }
        for (y in 0 until height) for (x in 0 until width) {
            val index = (y * width + x) * 4
            val fx = x.toDouble() / maxOf(1, width - 1)
            val fy = y.toDouble() / maxOf(1, height - 1)
            pixels[index] = (fx * 255).toInt().toByte()
            pixels[index + 1] = (fy * 255).toInt().toByte()
            pixels[index + 2] = (((sin(fx * 12) * cos(fy * 9) + 1) / 2) * 135).toInt().toByte()
        }
        return RGBAPixels.makeImage(pixels, width, height)!!
    }

    private suspend fun makeMediaService(node: TestNode): MediaTransferService {
        val service = MediaTransferService(
            router = node.router, database = node.database, store = MediaStore.makeTemporary(),
            identity = node.identity, nickname = node.nickname,
        )
        service.start()
        services += service
        return service
    }

    @Test
    fun imageTransferOverMesh() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val aliceMedia = makeMediaService(alice)
        val bobMedia = makeMediaService(bob)
        val bobEvents = bobMedia.events()
        val aliceEvents = aliceMedia.events()

        val image = ImageProcessor.prepareForMesh(makeImage(1_600, 1_200))
        assertTrue("servono piu' chunk", image.webp.size > MediaTransferService.chunkSize)
        val conversation = alice.directConversation(with = bob)
        val sent = aliceMedia.sendImage(image, conversation)
        assertEquals(MessageContentType.image, sent.contentType)
        assertNotNull(sent.mediaLocalPath)
        assertEquals(DeliveryStatus.sentMesh, sent.deliveryStatus)

        // Avanzamento in invio: 0...N chunk, poi completato.
        val totalChunks = (image.webp.size + MediaTransferService.chunkSize - 1) / MediaTransferService.chunkSize
        val firstAliceEvent = withTimeout(5_000) { aliceEvents.first() }
        assertEquals(MediaTransferService.Event.Progress(sent.msgID, 0, totalChunks), firstAliceEvent)

        val completed = withTimeout(10_000) {
            bobEvents.first { it is MediaTransferService.Event.Completed && it.msgID.contentEquals(sent.msgID) }
        }
        assertNotNull(completed)

        val received = bob.database.messages.fetch(msgID = sent.msgID) ?: return@meshTest fail("messaggio mancante")
        assertEquals(MessageContentType.image, received.contentType)
        assertEquals(image.blurhash, received.blurhash)
        val path = received.mediaLocalPath ?: return@meshTest fail("file mancante")
        assertArrayEquals(image.webp, bobMedia.store.read(relativePath = path))
        assertTrue(eventually { alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.deliveredAck })
    }

    @Test
    fun voiceNoteTransferOverMesh() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val aliceMedia = makeMediaService(alice)
        val bobMedia = makeMediaService(bob)

        val samples = FloatArray(24_000) { (sin(2 * Math.PI * 300 * it / 16_000) * 0.4).toFloat() }
        val recording = VoiceNoteRecorder.makeRecording(samples)
        val conversation = alice.directConversation(with = bob)
        val sent = aliceMedia.sendVoiceNote(recording, conversation)

        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID)?.mediaLocalPath != null })
        val received = bob.database.messages.fetch(msgID = sent.msgID)!!
        assertEquals(MessageContentType.audio, received.contentType)
        assertEquals(1_500, received.durationMs)
        assertArrayEquals(recording.waveform, received.audioWaveform)
        val bytes = bobMedia.store.read(relativePath = received.mediaLocalPath!!)
        assertArrayEquals(recording.oggOpus, bytes)
        assertEquals(75, OggOpus.decode(bytes).packets.size)
    }

    @Test
    fun mediaRequiresReachablePeer() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        val aliceMedia = makeMediaService(alice)
        val image = ImageProcessor.prepareForMesh(makeImage(200, 100))
        val conversation = alice.directConversation(with = bob)
        try {
            aliceMedia.sendImage(image, conversation)
            fail("atteso recipientOutOfRange")
        } catch (error: MediaTransferService.Failure.recipientOutOfRange) {
            // atteso
        }
    }

    @Test
    fun oversizedMediaIsRejected() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val aliceMedia = makeMediaService(alice)
        val conversation = alice.directConversation(with = bob)
        val huge = VoiceNoteRecorder.Recording(
            oggOpus = ByteArray(MediaTransferService.maxMediaBytes + 1), waveform = ByteArray(32), durationMs = 1_000, sampleCount = 16_000,
        )
        try {
            aliceMedia.sendVoiceNote(huge, conversation)
            fail("atteso mediaTooLarge")
        } catch (error: MediaTransferService.Failure.mediaTooLarge) {
            assertEquals(MediaTransferService.maxMediaBytes + 1, error.size)
        }
    }

    @Test
    fun wireConstantsMatchIOS() {
        assertEquals(8_192, MediaTransferService.chunkSize)
        assertEquals(1_048_576, MediaTransferService.maxMediaBytes)
        assertEquals(128, MediaTransferService.maxChunks)
        assertEquals("image/webp", MediaStore.Kind.image.mimeType)
        assertEquals("audio/opus", MediaStore.Kind.audio.mimeType)
    }
}
