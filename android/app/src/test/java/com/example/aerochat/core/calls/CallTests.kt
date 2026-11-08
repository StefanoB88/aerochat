package com.example.aerochat.core.calls

import androidx.test.core.app.ApplicationProvider
import com.example.aerochat.core.media.FakeOpus
import com.example.aerochat.core.media.OpusCodec
import com.example.aerochat.core.protocol.MeshProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import kotlin.math.sin

/** Parti "audio engine" di `CallTests.swift` (codec in streaming, packetizzazione, jitter buffer). */
@RunWith(RobolectricTestRunner::class)
class CallTests {

    private val originalEncoderFactory = OpusCodec.encoderFactory
    private val originalDecoderFactory = OpusCodec.decoderFactory

    @Before
    fun setUp() = FakeOpus.install()

    @After
    fun tearDown() {
        OpusCodec.encoderFactory = originalEncoderFactory
        OpusCodec.decoderFactory = originalDecoderFactory
    }

    @Test
    fun opusStreamingRoundTrip() {
        val encoder = assertNotNull(OpusStreamEncoder())
        val decoder = assertNotNull(OpusStreamDecoder())
        val frameSize = OpusCodec.frameSamples
        val packets = ArrayList<ByteArray>()
        for (index in 0 until 50) {
            val frame = FloatArray(frameSize) { (sin((index * frameSize + it) * 0.06) * 0.5).toFloat() }
            encoder.encode(frame)?.let { packets += it }
        }
        assertTrue(packets.size >= 45)
        assertTrue(packets.all { it.size < 200 })
        val decoded = packets.flatMap { decoder.decode(it).toList() }
        assertTrue(decoded.size >= 40 * frameSize)
        // Frame di dimensione sbagliata e pacchetti vuoti/enormi vengono ignorati.
        assertNull(encoder.encode(FloatArray(100)))
        assertEquals(0, decoder.decode(ByteArray(0)).size)
        assertEquals(0, decoder.decode(ByteArray(1_501)).size)
    }

    @Test
    fun streamCodecIsNullWhenUnavailable() {
        OpusCodec.encoderFactory = { throw IllegalStateException("nessun codec") }
        OpusCodec.decoderFactory = { throw IllegalStateException("nessun codec") }
        assertNull(OpusStreamEncoder())
        assertNull(OpusStreamDecoder())
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    @Test
    fun captureIsGroupedInCallFramesPerPacket() {
        val engine = CallAudioEngine(ApplicationProvider.getApplicationContext())
        val sent = Collections.synchronizedList(ArrayList<List<ByteArray>>())
        engine.startCodec { frames -> sent += frames }

        // 1 s di audio a blocchi irregolari: 50 frame => 16 pacchetti da 3 frame (2 frame in attesa).
        val samples = FloatArray(16_000) { (sin(it * 0.05) * 0.4).toFloat() }
        var offset = 0
        val sizes = intArrayOf(1_024, 333, 2_000, 77)
        var i = 0
        while (offset < samples.size) {
            val end = minOf(samples.size, offset + sizes[i++ % sizes.size])
            engine.encodeOnQueue(samples.copyOfRange(offset, end))
            offset = end
        }
        engine.drainCodecQueue()
        assertEquals(16, sent.size)
        assertTrue(sent.all { it.size == MeshProtocol.callFramesPerPacket })

        // In muto partono frame di silenzio (il link resta vivo).
        sent.clear()
        engine.setMuted(true)
        engine.encodeOnQueue(FloatArray(OpusCodec.frameSamples * 4) { 0.9f })
        engine.drainCodecQueue()
        // 2 frame non muti erano gia' in attesa (50 = 16*3 + 2): 2 + 4 = 6 frame => 2 pacchetti, e i 4
        // frame catturati in muto sono silenzio.
        assertEquals(2, sent.size)
        val muted = sent.flatten().takeLast(4)
        assertTrue(muted.all { packet -> packet.drop(1).all { it == 0.toByte() } })

        engine.stopCodec()
        engine.encodeOnQueue(FloatArray(OpusCodec.frameSamples * 3))
        engine.drainCodecQueue()
        assertEquals(2, sent.size)
    }

    @Test
    fun jitterBufferPrebuffersDropsOldAndCapsQueue() {
        val engine = CallAudioEngine(ApplicationProvider.getApplicationContext())
        engine.startCodec { }
        val encoder = assertNotNull(OpusStreamEncoder())
        val frames = (0 until 3).map { encoder.encode(FloatArray(OpusCodec.frameSamples) { 0.3f })!! }

        engine.receive(1, frames)
        engine.drainCodecQueue()
        assertEquals(1, engine.queuedPackets)
        assertTrue("prebuffer: serve un secondo pacchetto", engine.isPrebuffering)

        // Sequenza vecchia o duplicata: scartata.
        engine.receive(1, frames)
        engine.drainCodecQueue()
        assertEquals(1, engine.queuedPackets)

        engine.receive(2, frames)
        engine.drainCodecQueue()
        assertEquals(2, engine.queuedPackets)
        assertFalse(engine.isPrebuffering)

        // La coda non supera maxQueuedPackets.
        for (seq in 3..20) engine.receive(seq, frames)
        engine.drainCodecQueue()
        assertEquals(CallAudioEngine.maxQueuedPackets, engine.queuedPackets)

        // Ogni buffer e' 3 frame da 20 ms; svuotando la coda si torna in prebuffer.
        val first = engine.consumeQueuedBufferForTesting()!!
        assertEquals(3 * OpusCodec.frameSamples, first.size)
        while (engine.consumeQueuedBufferForTesting() != null) Unit
        assertEquals(0, engine.queuedPackets)
        assertTrue(engine.isPrebuffering)

        // Confronto senza segno (UInt32): 0x80000000 e' maggiore di 20.
        engine.receive(0x8000_0000.toInt(), frames)
        engine.drainCodecQueue()
        assertEquals(1, engine.queuedPackets)
        engine.stopCodec()
    }
}

/** Accoda sulla codecQueue come il thread del microfono. */
private fun CallAudioEngine.encodeOnQueue(samples: FloatArray) = receiveCaptured(samples)
