package com.example.aerochat.core.media

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regressioni sui media ricevuti da altri telefoni (input non fidato): dimensioni dichiarate enormi,
 * note vocali con troppi pacchetti / troppo lunghe.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MediaHardeningTests {

    private val originalEncoderFactory = OpusCodec.encoderFactory
    private val originalDecoderFactory = OpusCodec.decoderFactory

    @Before
    fun setUp() = FakeOpus.install()

    @After
    fun tearDown() {
        OpusCodec.encoderFactory = originalEncoderFactory
        OpusCodec.decoderFactory = originalDecoderFactory
    }

    private fun webp(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 120, 200))
        return WebPEncoder.encode(bitmap, quality = 0.5f).also { bitmap.recycle() }
    }

    @Test
    fun exceedsDimensionsReadsHeaderOnly() {
        // Striscia 5000x1: pochi byte, ma lato lungo oltre il limite delle foto ricevute.
        val wide = webp(5_000, 1)
        assertTrue(wide.size < 64 * 1024)
        assertTrue(ImageProcessor.exceedsDimensions(wide, MediaTransferService.maxReceivedImageSide))
        assertFalse(ImageProcessor.exceedsDimensions(webp(1_280, 960), MediaTransferService.maxReceivedImageSide))
        // Dati non decodificabili: nessuna bitmap possibile, quindi non "oltre il limite".
        assertFalse(ImageProcessor.exceedsDimensions(ByteArray(32), 16))
    }

    @Test
    fun downscaleCapsIntermediateDecode() {
        // 6000x4000 = 24M pixel con lato massimo 4096: senza tetto decodificherebbe a piena risoluzione.
        val big = webp(6_000, 4_000)
        val image = ImageProcessor.downscale(big, 4_096)
        assertTrue(image.width.toLong() * image.height <= ImageProcessor.maxDecodePixels)
        assertTrue(maxOf(image.width, image.height) <= 4_096)
        // Lati piccoli: comportamento invariato (riduzione esatta al lato richiesto).
        val small = ImageProcessor.downscale(webp(2_000, 1_500), 660)
        assertEquals(660, maxOf(small.width, small.height))
    }

    @Test
    fun voiceNoteDecodeRejectsTooManyPackets() {
        val packet = ByteArray(81).also { it[0] = 0x08 }
        val tooMany = List(OpusCodec.maxVoiceNotePackets + 1) { packet }
        try {
            OpusCodec.decode(tooMany)
            fail("attesa decodeFailed")
        } catch (_: OpusCodec.Failure) {
        }
        // Al limite di durata (in campioni) la decodifica si ferma.
        try {
            OpusCodec.decode(List(10) { packet }, maxSamples = 5 * OpusCodec.frameSamples)
            fail("attesa decodeFailed")
        } catch (_: OpusCodec.Failure) {
        }
        // Una nota normale (6 s) passa.
        assertEquals(300 * OpusCodec.frameSamples, OpusCodec.decode(List(300) { packet }).size)
    }
}
