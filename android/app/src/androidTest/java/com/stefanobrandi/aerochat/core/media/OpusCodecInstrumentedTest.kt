package com.stefanobrandi.aerochat.core.media

import android.Manifest
import android.media.MediaRecorder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Codec Opus reale (`MediaCodec`, `c2.android.opus.*`) e cattura dal microfono: i test JVM usano un
 * codec finto, qui si verifica la pipeline vera su dispositivo/emulatore.
 */
@RunWith(AndroidJUnit4::class)
class OpusCodecInstrumentedTest {

    private val tag = "OpusCodecTest"

    @Before
    fun setUp() {
        // Le fabbriche potrebbero essere state sostituite: ripristina quelle reali.
        OpusCodec.encoderFactory = { MediaCodecOpusEncoder(it) }
        OpusCodec.decoderFactory = { MediaCodecOpusDecoder(it) }
    }

    private fun sine(frequency: Double, seconds: Double, amplitude: Double = 0.5): FloatArray =
        FloatArray((OpusCodec.sampleRate * seconds).toInt()) {
            (sin(2 * PI * frequency * it / OpusCodec.sampleRate) * amplitude).toFloat()
        }

    /** Potenza normalizzata della frequenza [frequency] (Goertzel). */
    private fun goertzel(samples: FloatArray, frequency: Double): Double {
        val w = 2 * PI * frequency / OpusCodec.sampleRate
        val coefficient = 2 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (x in samples) {
            val s0 = x + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coefficient * s1 * s2
        return power / (samples.size.toDouble() * samples.size)
    }

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.fold(0.0) { acc, v -> acc + v * v } / samples.size.coerceAtLeast(1))

    /** Durata di un pacchetto Opus dal TOC byte (RFC 6716 par. 3.1), in campioni a 48 kHz. */
    private fun packetDuration48k(packet: ByteArray): Int {
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameSize = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
            config < 16 -> intArrayOf(480, 960)[config and 1]
            else -> intArrayOf(120, 240, 480, 960)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> packet[1].toInt() and 0x3F
        }
        return frameSize * frames
    }

    private fun assertValidPackets(packets: List<ByteArray>) {
        for ((index, packet) in packets.withIndex()) {
            assertTrue("pacchetto $index vuoto", packet.isNotEmpty())
            assertTrue("pacchetto $index troppo grande (${packet.size})", packet.size < 400)
            // Ogni pacchetto dura 20 ms: e' il framing che si aspettano OggOpus (granule) e iOS.
            assertEquals("durata pacchetto $index", 960, packetDuration48k(packet))
        }
    }

    @Test
    fun realEncoderProducesTwentyMillisecondPackets() {
        val samples = sine(440.0, 1.0)
        val packets = OpusCodec.encode(samples)
        Log.i(tag, "1 s -> ${packets.size} pacchetti, ${packets.sumOf { it.size }} byte, TOC ${"%02x".format(packets.first()[0])}")
        // L'encoder di sistema svuota il lookahead (6.5 ms) in un pacchetto in piu'.
        assertTrue("pacchetti: ${packets.size}", packets.size in 50..51)
        assertValidPackets(packets)
        // 12 kbps VBR: circa 1.5 KB/s (margine ampio).
        assertTrue("bitrate fuori scala: ${packets.sumOf { it.size }} B/s", packets.sumOf { it.size } in 500..4_000)
    }

    @Test
    fun realCodecRoundTripThroughOgg() {
        val samples = sine(440.0, 2.0)
        val recording = VoiceNoteRecorder.makeRecording(samples)
        assertEquals(2_000, recording.durationMs)

        val stream = OggOpus.decode(recording.oggOpus)
        assertTrue("pacchetti: ${stream.packets.size}", stream.packets.size in 100..101)
        assertValidPackets(stream.packets)

        val decoded = OpusCodec.decode(stream.packets)
        Log.i(tag, "round trip: ${samples.size} -> ${decoded.size} campioni, rms ${rms(decoded)}")
        assertTrue("lunghezza decodificata ${decoded.size}", abs(decoded.size - samples.size) <= 1_600)

        // Parte centrale (salta il transitorio iniziale del codec).
        val middle = decoded.copyOfRange(4_000, minOf(decoded.size, 28_000))
        val level = rms(middle)
        assertTrue("livello decodificato $level (atteso ~0.35)", level in 0.15..0.6)
        val at440 = goertzel(middle, 440.0)
        val at1000 = goertzel(middle, 1_000.0)
        Log.i(tag, "goertzel 440=$at440 1000=$at1000")
        assertTrue("440 Hz non dominante: $at440 vs $at1000", at440 > at1000 * 50)

        // Per la verifica inversa (decoder iOS / AVAudioConverter sul Mac): file in base64 nel logcat.
        val base64 = android.util.Base64.encodeToString(recording.oggOpus, android.util.Base64.NO_WRAP)
        base64.chunked(3_000).forEachIndexed { index, chunk -> Log.i(tag, "ANDROID_OGG[$index] $chunk") }
    }

    /**
     * Nota prodotta dal codice iOS (`OpusCodec.swift` + `OggOpus.swift`, `AVAudioConverter` di Apple):
     * seno 440 Hz, 1.5 s, ampiezza 0.5. Il decoder Android deve riprodurla.
     */
    @Test
    fun decodesIOSProducedVoiceNote() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val ogg = context.assets.open("ios_sine440.opus").use { it.readBytes() }
        val stream = OggOpus.decode(ogg)
        assertEquals(75, stream.packets.size)
        assertValidPackets(stream.packets)
        val decoded = OpusCodec.decode(stream.packets)
        val middle = decoded.copyOfRange(4_000, minOf(decoded.size, 20_000))
        Log.i(tag, "iOS: ${decoded.size} campioni, rms ${rms(middle)}")
        assertTrue("lunghezza ${decoded.size}", abs(decoded.size - 24_000) <= 1_600)
        assertTrue("livello ${rms(middle)}", rms(middle) in 0.15..0.6)
        assertTrue(goertzel(middle, 440.0) > goertzel(middle, 1_000.0) * 50)

        val player = VoiceNotePlayer(InstrumentationRegistry.getInstrumentation().targetContext)
        player.load(ogg, mediaPath = "ios.opus")
        assertTrue("durata ${player.duration.value}", player.duration.value in 1_400L..1_600L)
        player.stop()
    }

    @Test
    fun realCodecHandlesPartialFrameAndSilence() {
        assertTrue(OpusCodec.encode(FloatArray(321)).size in 2..3)
        assertTrue(OpusCodec.encode(FloatArray(0)).isEmpty())
        val silence = OpusCodec.encode(FloatArray(16_000))
        assertTrue(silence.size in 50..51)
        assertValidPackets(silence)
        val decoded = OpusCodec.decode(silence)
        assertTrue(decoded.size > 14_000)
        assertTrue(rms(decoded) < 0.01)
    }

    @Test
    fun realStreamingCodecReturnsFramesImmediately() {
        // Chiamate: ogni frame da 20 ms accodato deve uscire subito (o quasi) come pacchetto.
        val samples = OpusCodec.floatToPcm16(sine(300.0, 1.0))
        OpusCodec.makeEncoder(streaming = true).use { encoder ->
            OpusCodec.makeDecoder(streaming = true).use { decoder ->
                var packets = 0
                var decodedSamples = 0
                var offset = 0
                while (offset < samples.size) {
                    val out = encoder.encode(samples.copyOfRange(offset, offset + OpusCodec.frameSamples))
                    packets += out.size
                    for (packet in out) decodedSamples += decoder.decode(packet).size
                    offset += OpusCodec.frameSamples
                }
                Log.i(tag, "streaming: $packets pacchetti, $decodedSamples campioni")
                assertTrue("streaming encoder: solo $packets pacchetti su 50", packets >= 45)
                assertTrue("streaming decoder: solo $decodedSamples campioni", decodedSamples >= 12_000)
            }
        }
    }

    @Test
    fun longRecordingEncodesQuickly() {
        // 120 s (limite massimo) devono codificarsi in tempi ragionevoli.
        val samples = sine(220.0, 120.0, amplitude = 0.3)
        val started = System.currentTimeMillis()
        val recording = VoiceNoteRecorder.makeRecording(samples)
        val elapsed = System.currentTimeMillis() - started
        Log.i(tag, "120 s codificati in $elapsed ms, ${recording.oggOpus.size} byte")
        assertTrue(OggOpus.decode(recording.oggOpus).packets.size in 6_000..6_001)
        assertTrue("codifica troppo lenta: $elapsed ms", elapsed < 15_000)
    }

    @Test
    fun voiceNotePlayerLoadsAndroidNote() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recording = VoiceNoteRecorder.makeRecording(sine(440.0, 1.5))
        val player = VoiceNotePlayer(context)
        player.load(recording.oggOpus, mediaPath = "test.opus")
        val duration = player.duration.value
        assertTrue("durata $duration", duration in 1_300L..1_700L)
        player.stop()
    }

    // MARK: Microfono

    private fun grantMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO,
        )
    }

    @Test
    fun microphoneCaptureOpensAndDeliversSamples() {
        grantMicrophone()
        var received = 0
        val lock = Any()
        val capture = MicrophoneCapture.open(MediaRecorder.AudioSource.MIC, chunkSamples = 1_600) { chunk ->
            synchronized(lock) { received += chunk.size }
        }
        Thread.sleep(1_000)
        capture.stop()
        Log.i(tag, "microfono: $received campioni in 1 s")
        assertTrue("il microfono non consegna campioni ($received)", received >= 8_000)
    }

    @Test
    fun voiceNoteRecorderRecordsAndStops() = runBlocking {
        grantMicrophone()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = VoiceNoteRecorder(context)
        withContext(Dispatchers.Main) { recorder.start() }
        assertTrue(recorder.isRecording.value)
        Thread.sleep(1_500)
        val elapsed = recorder.elapsed.value
        // Come la UI: stop dal main thread, codifica su Dispatchers.Default.
        val recording = withContext(Dispatchers.Main) { recorder.stopAndEncode() }
        Log.i(tag, "registrazione: ${recording.durationMs} ms, ${recording.oggOpus.size} byte, elapsed $elapsed")
        assertTrue("elapsed non aggiornato: $elapsed", elapsed >= 1_000)
        assertTrue("durata ${recording.durationMs}", recording.durationMs in 1_000..2_000)
        val stream = OggOpus.decode(recording.oggOpus)
        assertTrue(stream.packets.size >= 50)
        assertValidPackets(stream.packets)
        assertEquals(32, recording.waveform.size)
        assertTrue(!recorder.isRecording.value)
        // La riproduzione della nota appena registrata funziona.
        val player = VoiceNotePlayer(context)
        player.load(recording.oggOpus)
        assertTrue(player.duration.value in 1_000L..2_100L)
        player.stop()
    }

    @Test
    fun voiceNoteRecorderTooShortThenRestarts() = runBlocking {
        grantMicrophone()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = VoiceNoteRecorder(context)
        withContext(Dispatchers.Main) { recorder.start() }
        val failure = runCatching { withContext(Dispatchers.Main) { recorder.stopAndEncode() } }.exceptionOrNull()
        assertEquals(VoiceNoteRecorder.Failure.tooShort, failure)
        assertTrue(!recorder.isRecording.value)
        // Microfono e audio focus rilasciati: una nuova registrazione parte.
        withContext(Dispatchers.Main) { recorder.start() }
        assertTrue(recorder.isRecording.value)
        withContext(Dispatchers.Main) { recorder.cancel() }
        assertTrue(!recorder.isRecording.value)
    }
}
