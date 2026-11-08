package com.example.aerochat.core.media

import android.graphics.Bitmap
import com.example.aerochat.core.protocol.hexToByteArray
import com.example.aerochat.core.protocol.toHex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Media – BlurHash, WebP, Ogg Opus, MediaStore (parti codec/storage di `MediaTests.swift`).
 * I vettori di riferimento "iOS" sono stati generati compilando i sorgenti Swift originali.
 * Robolectric con grafica nativa: `Bitmap.compress(WEBP)` produce WebP reali.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaTests {

    private val originalEncoderFactory = OpusCodec.encoderFactory
    private val originalDecoderFactory = OpusCodec.decoderFactory

    @Before
    fun setUp() = FakeOpus.install()

    @After
    fun tearDown() {
        OpusCodec.encoderFactory = originalEncoderFactory
        OpusCodec.decoderFactory = originalDecoderFactory
    }

    /**
     * Immagine sintetica "fotografica": gradienti morbidi + onde, senza bordi netti.
     * Con `noisy = true` aggiunge una scacchiera a bordi netti (caso patologico per la compressione lossy).
     */
    private fun makeImage(width: Int, height: Int, solid: Triple<Int, Int, Int>? = null, noisy: Boolean = false): Bitmap {
        val pixels = ByteArray(width * height * 4) { 255.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = (y * width + x) * 4
                if (solid != null) {
                    pixels[index] = solid.first.toByte(); pixels[index + 1] = solid.second.toByte(); pixels[index + 2] = solid.third.toByte()
                } else {
                    val fx = x.toDouble() / maxOf(1, width - 1)
                    val fy = y.toDouble() / maxOf(1, height - 1)
                    pixels[index] = (fx * 255).toInt().toByte()
                    pixels[index + 1] = (fy * 255).toInt().toByte()
                    val wave = (sin(fx * 12) * cos(fy * 9) + 1) / 2
                    val checker = if (noisy) (((x / 8 + y / 8) % 2) * 120).toDouble() else 0.0
                    pixels[index + 2] = min(255.0, wave * 135 + checker).toInt().toByte()
                }
            }
        }
        return RGBAPixels.makeImage(pixels, width, height)!!
    }

    /** Stesso buffer usato per generare i vettori con il codice Swift. */
    private fun referencePixels(w: Int, h: Int): ByteArray {
        val p = ByteArray(w * h * 4) { 255.toByte() }
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            p[i] = ((x * 255) / maxOf(1, w - 1)).toByte()
            p[i + 1] = ((y * 255) / maxOf(1, h - 1)).toByte()
            p[i + 2] = (((x * 7 + y * 13) * 3) % 256).toByte()
        }
        return p
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

    // MARK: BlurHash

    @Test
    fun blurHashEncodeDecode() {
        val red = makeImage(48, 32, solid = Triple(220, 30, 30))
        val hash = BlurHash.encode(red)
        assertEquals(28, hash.length)
        assertTrue("4x3 componenti => flag (3-1)*9+(4-1)=21 => 'L'", hash.startsWith("L"))
        assertTrue(BlurHash.isValid(hash))

        val average = BlurHash.averageColor(hash)
        assertTrue(average.red > 0.75 && average.green < 0.25 && average.blue < 0.25)

        val pixels = BlurHash.decode(hash, 4, 4)
        assertEquals(4 * 4 * 4, pixels.size)
        assertTrue((pixels[0].toInt() and 0xFF) > 180 && (pixels[1].toInt() and 0xFF) < 70)
        assertNotNull(BlurHash.image(hash, 8, 8))
        assertThrows(BlurHash.Failure.invalidHash::class.java) { BlurHash.decode("LEHV6nWB2yk8", 2, 2) }
    }

    @Test
    fun blurHashMatchesIOSByteForByte() {
        assertEquals("L\$Hewc2swwX8l}WDjse:gIfifPfi", BlurHash.encode(referencePixels(32, 24), 32, 24, 4, 3))
        assertEquals("V\$Hxvk2@wwkWSNl}WCjse=fQgHfifOfhfRnmWpjsfPfP", BlurHash.encode(referencePixels(17, 29), 17, 29, 5, 4))
        assertEquals("000000", BlurHash.encode(referencePixels(1, 1), 1, 1, 1, 1))
        assertEquals(
            "87a4b1ffa1adb1ffb5b4abffa0acaeff7c9aa9ff94949affa49186ff92989bff7c909aff908684ffa38268ff948c86ff",
            BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 4, 3).toHex(),
        )
        assertThrows(BlurHash.Failure.invalidComponents::class.java) { BlurHash.encode(ByteArray(4), 1, 1, 0, 3) }
        assertThrows(BlurHash.Failure.invalidImage::class.java) { BlurHash.encode(ByteArray(3), 1, 1, 4, 3) }
        assertFalse(BlurHash.isValid("L\$Hewc2swwX8l}WDjse:gIfifPf"))
    }

    // MARK: WebP

    @Test
    fun imageProcessorProducesMeshReadyWebP() {
        val result = ImageProcessor.prepareForMesh(makeImage(2_400, 1_800))
        assertEquals(1_280, result.width)
        assertEquals(960, result.height)
        assertTrue(WebPEncoder.isWebP(result.webp))
        assertTrue(result.webp.size > 1_000)
        assertTrue(result.webp.size <= ImageProcessor.targetMaxBytes)
        assertTrue(result.quality <= ImageProcessor.initialQuality)
        assertEquals(28, result.blurhash.length)

        val decoded = WebPEncoder.decode(result.webp)
        assertEquals(1_280, decoded.width)
        assertEquals(960, decoded.height)
        assertEquals(1_280, WebPEncoder.dimensions(of = result.webp)?.width)
    }

    @Test
    fun imageProcessorCapsPathologicalImages() {
        val result = ImageProcessor.prepareForMesh(makeImage(2_000, 1_500, noisy = true))
        assertTrue(result.webp.size <= ImageProcessor.targetMaxBytes)
        assertTrue(result.width <= 1_280)
        assertTrue(result.width >= ImageProcessor.minimumLongSide)
    }

    @Test
    fun imageProcessorFromEncodedData() {
        val small = makeImage(400, 300)
        val png = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val result = ImageProcessor.prepareForMesh(png)
        assertEquals(400, result.width)
        assertEquals(300, result.height)
        assertTrue(WebPEncoder.isWebP(result.webp))
        assertThrows(ImageProcessor.Failure.unreadableImage::class.java) { ImageProcessor.prepareForMesh(ByteArray(16)) }
    }

    @Test
    fun avatarIsSquareWebPWithinBudget() {
        val source = makeImage(900, 600)
        val png = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val avatar = ImageProcessor.prepareAvatar(png)
        assertTrue(WebPEncoder.isWebP(avatar))
        assertTrue(avatar.size <= com.example.aerochat.core.protocol.MeshProtocol.maxAvatarBytes)
        assertEquals(WebPEncoder.Dimensions(128, 128), WebPEncoder.dimensions(of = avatar))
    }

    @Test
    fun webpDetection() {
        assertFalse(WebPEncoder.isWebP(ByteArray(64)))
        assertFalse(WebPEncoder.isWebP("RIFF0000WEBP".toByteArray()))
        assertTrue(WebPEncoder.isWebP("RIFF0000WEBPVP8 ".toByteArray()))
        assertNull(WebPEncoder.dimensions(of = ByteArray(32)))
    }

    // MARK: Ogg Opus

    private fun testPackets(): List<ByteArray> = (0 until 120).map { index ->
        val length = if (index % 7 == 0) 300 else 20 + index % 50
        ByteArray(length) { offset -> ((offset + index) and 0xFF).toByte() }
    }

    @Test
    fun oggOpusRoundTrip() {
        val packets = testPackets()
        val header = OggOpus.Header(preSkip = 312)
        val ogg = OggOpus.encode(packets, header, serial = 0x1234_5678)
        assertEquals("OggS", String(ogg, 0, 4, Charsets.US_ASCII))
        val text = String(ogg, Charsets.ISO_8859_1)
        assertTrue(text.contains("OpusHead"))
        assertTrue(text.contains("OpusTags"))

        val decoded = OggOpus.decode(ogg)
        assertEquals(packets.size, decoded.packets.size)
        packets.indices.forEach { assertArrayEquals(packets[it], decoded.packets[it]) }
        assertEquals(header, decoded.header)

        val corrupted = ogg.copyOf()
        corrupted[200] = (corrupted[200].toInt() xor 0xFF).toByte()
        assertThrows(OggOpus.Failure.badChecksum::class.java) { OggOpus.decode(corrupted) }
        assertThrows(OggOpus.Failure.notOgg::class.java) { OggOpus.decode(ByteArray(64)) }
        assertThrows(OggOpus.Failure.truncated::class.java) { OggOpus.decode(ogg.copyOf(ogg.size - 10)) }
    }

    @Test
    fun oggOpusMatchesIOSByteForByte() {
        // Stesso input di `OggOpus.encode` su iOS (vendor "AeroMesh iOS"): file identico.
        val ogg = OggOpus.encode(testPackets(), OggOpus.Header(), 0x1234_5678, "AeroMesh iOS")
        assertEquals(9_981, ogg.size)
        assertEquals("c378d185a563e4a3c2563b71a34456ccb5714d11ad18ffe5d3e4a325bae066c3", sha256(ogg))

        val iosEmpty = (
            "4f67675300020000000000000000bebafeca00000000b7bf9a1f01134f7075734865616401013801803e0000000000" +
                "4f67675300000000000000000000bebafeca010000002c7aaa23011c4f707573546167730c0000004165726f4d65" +
                "736820694f53000000004f67675300043801000000000000bebafeca0200000095c2e96b00"
            ).hexToByteArray()!!
        assertArrayEquals(iosEmpty, OggOpus.encode(emptyList(), OggOpus.Header(), 0xCAFE_BABE.toInt(), "AeroMesh iOS"))
        val stream = OggOpus.decode(iosEmpty)
        assertTrue(stream.packets.isEmpty())
        assertEquals(OggOpus.Header(), stream.header)

        assertEquals(0xe3f15e48.toInt(), OggOpus.crc32("hello ogg".toByteArray()))
    }

    @Test
    fun oggOpusAndroidVendorStillDecodes() {
        val ogg = OggOpus.encode(testPackets())
        assertEquals(120, OggOpus.decode(ogg).packets.size)
    }

    @Test
    fun opusPipelineWaveformAndRecording() {
        val sampleCount = 16_000
        val samples = FloatArray(sampleCount) { (sin(2 * Math.PI * 440 * it / 16_000) * 0.5).toFloat() }
        val packets = OpusCodec.encode(samples)
        assertEquals(50, packets.size)

        val decoded = OpusCodec.decode(packets)
        assertTrue(decoded.size in (sampleCount - 1_000)..(sampleCount + 1_000))
        val energy = decoded.takeLast(8_000).fold(0f) { acc, v -> acc + v * v } / 8_000
        assertTrue("il segnale decodificato non e' silenzio", energy > 0.01f)

        val waveform = OpusCodec.waveform(samples)
        assertEquals(32, waveform.size)
        assertEquals(255, waveform.maxOf { it.toInt() and 0xFF })

        val recording = VoiceNoteRecorder.makeRecording(samples)
        assertEquals(1_000, recording.durationMs)
        assertEquals(32, recording.waveform.size)
        assertEquals(50, OggOpus.decode(recording.oggOpus).packets.size)

        // Padding all'ultimo frame intero.
        assertEquals(2, OpusCodec.encode(FloatArray(321)).size)
        assertTrue(OpusCodec.encode(FloatArray(0)).isEmpty())
        assertEquals(0, OpusCodec.decode(listOf(ByteArray(0))).size)
    }

    @Test
    fun waveformMatchesIOS() {
        val samples = FloatArray(5_000) { (sin(it * 0.01).toFloat()) * (it % 700).toFloat() / 700f }
        assertEquals("2a4e9cc861347db0e2195f96c9e6437baaff295e8ce5364070c8b42356abafe1", OpusCodec.waveform(samples).toHex())
        assertArrayEquals(ByteArray(32), OpusCodec.waveform(FloatArray(0)))
        assertArrayEquals(ByteArray(32), OpusCodec.waveform(FloatArray(100)))
    }

    @Test
    fun resamplerProducesExpectedRates() {
        val decimator = PcmResampler(48_000, 16_000)
        val a = decimator.process(FloatArray(1_000) { 0.5f })
        val b = decimator.process(FloatArray(500) { 0.5f })
        assertEquals(500, a.size + b.size)
        assertTrue(a.all { it == 0.5f })
        val linear = PcmResampler(44_100, 16_000)
        var total = 0
        repeat(10) { total += linear.process(FloatArray(4_410) { 0.25f }).size }
        assertTrue(total in 15_990..16_010)
    }

    // MARK: MediaStore

    @Test
    fun mediaStoreWritesReadsAndRemoves() {
        val store = MediaStore.makeTemporary()
        val data = ByteArray(1_000) { it.toByte() }
        val path = store.write(data, "abc-123_X", MediaStore.Kind.image)
        assertEquals("abc-123_X.webp", path)
        assertTrue(store.exists(path))
        assertArrayEquals(data, store.read(path))
        assertEquals(store.url("abc-123_X", MediaStore.Kind.image), store.absoluteURL(path))

        // Sovrascrittura atomica.
        val newer = ByteArray(10) { 7 }
        store.write(newer, "abc-123_X", MediaStore.Kind.image)
        assertArrayEquals(newer, store.read(path))
        assertEquals(1, store.directory.listFiles()!!.size)

        assertEquals("a_b_c__.opus", store.write(ByteArray(1), "a/b.c/.", MediaStore.Kind.audio))
        assertEquals("caffè.opus", store.write(ByteArray(1), "caffè", MediaStore.Kind.audio))

        store.remove(path)
        assertFalse(store.exists(path))
        store.remove(path) // gia' assente: nessun errore
    }

    @Test
    fun mediaKindMimeTypes() {
        assertEquals(MediaStore.Kind.image, MediaStore.Kind("image/webp"))
        assertEquals(MediaStore.Kind.audio, MediaStore.Kind("audio/opus"))
        assertEquals(MediaStore.Kind.audio, MediaStore.Kind("Audio/Ogg"))
        assertEquals(MediaStore.Kind.audio, MediaStore.Kind("audio/ogg; codecs=opus"))
        assertNull(MediaStore.Kind("image/jpeg"))
        assertEquals("image/webp", MediaStore.Kind.image.mimeType)
        assertEquals("opus", MediaStore.Kind.audio.fileExtension)
    }
}
