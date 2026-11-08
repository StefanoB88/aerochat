package com.example.aerochat.core.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.example.aerochat.core.protocol.littleEndianBytes
import java.io.Closeable
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Codec Opus 16 kHz mono via `MediaCodec` (encoder/decoder di sistema `c2.android.opus.*`, API 29+).
 * 12 kbps VBR => circa 1.5 KB/s: una nota vocale di 10 s pesa ~15 KB.
 *
 * I pacchetti sono Opus "raw" (un frame da 20 ms per pacchetto), identici a quelli prodotti/accettati
 * da `AVAudioConverter` su iOS: note vocali (dentro [OggOpus]) e audio delle chiamate (`CallAudio.frames`).
 *
 * Il codec reale sta dietro [FrameEncoder]/[FrameDecoder]: i test JVM sostituiscono [encoderFactory]
 * e [decoderFactory] con un codec finto, perche' `MediaCodec` non esiste fuori da un dispositivo.
 */
object OpusCodec {

    sealed class Failure(message: String) : Exception(message) {
        data object formatUnavailable : Failure("formatUnavailable")
        data object converterUnavailable : Failure("converterUnavailable")
        data class encodeFailed(val reason: String) : Failure("encodeFailed: $reason")
        data class decodeFailed(val reason: String) : Failure("decodeFailed: $reason")
    }

    /** Hz (`Double` su iOS). */
    const val sampleRate: Int = 16_000
    const val channels: Int = 1
    /** 20 ms a 16 kHz. */
    const val frameSamples: Int = 320
    const val bitRate: Int = 12_000

    /** Encoder con stato: PCM 16 bit mono 16 kHz in ingresso, pacchetti Opus da 20 ms in uscita. */
    interface FrameEncoder : Closeable {
        /** Accoda [pcm] (qualsiasi lunghezza) e restituisce i pacchetti gia' pronti. */
        fun encode(pcm: ShortArray): List<ByteArray>
        /** Fine stream: svuota l'encoder e restituisce i pacchetti rimanenti. */
        fun finish(): List<ByteArray>
    }

    /** Decoder con stato: pacchetti Opus in ingresso (in ordine), PCM 16 bit mono 16 kHz in uscita. */
    interface FrameDecoder : Closeable {
        fun decode(packet: ByteArray): ShortArray
        fun finish(): ShortArray
    }

    /**
     * Fabbriche del codec. `streaming = true` (chiamate) attende brevemente l'uscita di ogni frame
     * cosi' che `encode`/`decode` restituiscano subito il risultato del frame appena accodato.
     */
    @Volatile
    var encoderFactory: (streaming: Boolean) -> FrameEncoder = { MediaCodecOpusEncoder(it) }

    @Volatile
    var decoderFactory: (streaming: Boolean) -> FrameDecoder = { MediaCodecOpusDecoder(it) }

    fun makeEncoder(streaming: Boolean = false): FrameEncoder = try {
        encoderFactory(streaming)
    } catch (e: Failure) {
        throw e
    } catch (e: Exception) {
        throw Failure.converterUnavailable
    }

    fun makeDecoder(streaming: Boolean = false): FrameDecoder = try {
        decoderFactory(streaming)
    } catch (e: Failure) {
        throw e
    } catch (e: Exception) {
        throw Failure.converterUnavailable
    }

    // MARK: - Encode

    /** Codifica campioni PCM Float32 mono 16 kHz in pacchetti Opus da 20 ms. */
    fun encode(samples: FloatArray): List<ByteArray> {
        // Padding all'ultimo frame intero da 20 ms.
        val remainder = samples.size % frameSamples
        val paddedCount = if (remainder == 0) samples.size else samples.size + frameSamples - remainder
        if (paddedCount == 0) return emptyList()
        val pcm = ShortArray(paddedCount)
        for (i in samples.indices) pcm[i] = floatToPcm16(samples[i])

        val encoder = makeEncoder(streaming = false)
        try {
            val packets = ArrayList<ByteArray>(paddedCount / frameSamples + 1)
            // Blocchi da 1 s: l'encoder riempie ogni buffer d'ingresso con piu' frame (un round trip
            // verso il processo media per buffer invece che per frame: 120 s si codificano ~5x prima).
            val block = frameSamples * 50
            var offset = 0
            while (offset < pcm.size) {
                val end = min(pcm.size, offset + block)
                packets += encoder.encode(pcm.copyOfRange(offset, end))
                offset = end
            }
            packets += encoder.finish()
            if (packets.isEmpty()) throw Failure.encodeFailed("nessun pacchetto")
            return packets
        } catch (e: Failure) {
            throw e
        } catch (e: Exception) {
            throw Failure.encodeFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            encoder.close()
        }
    }

    // MARK: - Decode

    /** Decodifica pacchetti Opus in campioni PCM Float32 mono 16 kHz. */
    fun decode(
        packets: List<ByteArray>,
        maxPackets: Int = maxVoiceNotePackets,
        maxSamples: Int = maxVoiceNoteSamples,
    ): FloatArray {
        val valid = packets.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return FloatArray(0)
        // Nota vocale ricevuta da un altro telefono: 1 MB di Ogg puo' contenere ~500k pacchetti minimi
        // (ore di audio): senza limiti la decodifica dura minuti e finisce in OutOfMemoryError.
        if (valid.size > maxPackets) throw Failure.decodeFailed("troppi pacchetti: ${valid.size}")
        val decoder = makeDecoder(streaming = false)
        try {
            val chunks = ArrayList<ShortArray>(valid.size + 1)
            var total = 0
            for (packet in valid) {
                val chunk = decoder.decode(packet)
                total += chunk.size
                if (total > maxSamples) throw Failure.decodeFailed("audio troppo lungo")
                chunks += chunk
            }
            chunks += decoder.finish()
            return pcm16ToFloat(chunks)
        } catch (e: Failure) {
            throw e
        } catch (e: Exception) {
            throw Failure.decodeFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            decoder.close()
        }
    }

    // MARK: - Waveform

    /** 32 campioni `UInt8` di ampiezza RMS normalizzata (header del messaggio, forma d'onda stile WhatsApp). */
    fun waveform(samples: FloatArray, buckets: Int = 32): ByteArray {
        if (samples.isEmpty() || buckets <= 0) return ByteArray(max(0, buckets))
        val bucketSize = max(1, samples.size / buckets)
        val rms = FloatArray(buckets)
        for (bucket in 0 until buckets) {
            val start = bucket * bucketSize
            val end = min(samples.size, start + bucketSize)
            if (start >= end) break
            var sum = 0f
            for (index in start until end) sum += samples[index] * samples[index]
            rms[bucket] = sqrt(sum / (end - start).toFloat())
        }
        val peak = rms.max()
        if (!(peak > 0f)) return ByteArray(buckets)
        return ByteArray(buckets) { max(0f, min(255f, (rms[it] / peak) * 255f)).toInt().toByte() }
    }

    // MARK: - Conversioni PCM

    /**
     * Limiti di decodifica delle note vocali: entrambe le piattaforme registrano al massimo
     * [VoiceNoteRecorder.maximumDuration] in frame da 20 ms; tolleriamo frame fino a 10 ms e il 50% di
     * durata in piu', oltre e' un file malformato (o ostile).
     */
    val maxVoiceNotePackets: Int = (VoiceNoteRecorder.maximumDuration / 10).toInt()
    val maxVoiceNoteSamples: Int = (VoiceNoteRecorder.maximumDuration * 3 / 2 * sampleRate / 1_000).toInt()

    fun floatToPcm16(value: Float): Short = (value.coerceIn(-1f, 1f) * 32_767f).toInt().toShort()

    fun pcm16ToFloat(value: Short): Float = value / 32_768f

    fun pcm16ToFloat(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / 32_768f }

    fun floatToPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { floatToPcm16(samples[it]) }

    internal fun pcm16ToFloat(chunks: List<ShortArray>): FloatArray {
        val out = FloatArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            for (s in chunk) out[offset++] = s / 32_768f
        }
        return out
    }
}

// MARK: - Implementazione MediaCodec

private const val TIMEOUT_US = 10_000L
private const val FINISH_DEADLINE_MS = 2_000L
/** ms: attesa massima di un buffer d'ingresso libero per singolo encode/decode. */
private const val DEQUEUE_DEADLINE_MS = 1_000L

/** Encoder Opus di sistema (`MediaFormat.MIMETYPE_AUDIO_OPUS`, API 29+). */
internal class MediaCodecOpusEncoder(private val streaming: Boolean) : OpusCodec.FrameEncoder {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var queuedSamples = 0L
    private var finished = false

    init {
        try {
            try {
                configure(vbr = true)
            } catch (_: Exception) {
                // Alcuni encoder rifiutano la modalita' esplicita: si riprova con quella di default.
                codec.reset()
                configure(vbr = false)
            }
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    private fun configure(vbr: Boolean) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OpusCodec.sampleRate, OpusCodec.channels)
        format.setInteger(MediaFormat.KEY_BIT_RATE, OpusCodec.bitRate)
        // Chiamate: un frame per buffer. Note vocali: fino a 1 s per buffer.
        val maxFrames = if (streaming) 8 else 50
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, OpusCodec.frameSamples * 2 * maxFrames)
        if (vbr) format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    override fun encode(pcm: ShortArray): List<ByteArray> {
        check(!finished) { "encoder chiuso" }
        val out = ArrayList<ByteArray>(2)
        var offset = 0
        val deadline = System.currentTimeMillis() + DEQUEUE_DEADLINE_MS
        while (offset < pcm.size) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                // Codec bloccato (stallo del codec di sistema): meglio un errore che un loop infinito
                // sul thread audio, che bloccherebbe anche la chiusura della chiamata.
                if (System.currentTimeMillis() > deadline) throw OpusCodec.Failure.encodeFailed("timeout input buffer")
                // Nessun buffer libero: svuota l'uscita per sbloccare il codec.
                drain(out, 0)
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: throw OpusCodec.Failure.encodeFailed("input buffer")
            buffer.clear()
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            // Frame interi da 20 ms per buffer quando c'e' spazio (il resto va nel buffer successivo).
            val capacity = buffer.remaining() / 2
            val wholeFrames = capacity / OpusCodec.frameSamples * OpusCodec.frameSamples
            val count = min(pcm.size - offset, if (wholeFrames > 0) wholeFrames else capacity)
            if (count <= 0) throw OpusCodec.Failure.encodeFailed("input buffer vuoto")
            buffer.asShortBuffer().put(pcm, offset, count)
            val ptsUs = queuedSamples * 1_000_000L / OpusCodec.sampleRate
            codec.queueInputBuffer(index, 0, count * 2, ptsUs, 0)
            queuedSamples += count
            offset += count
        }
        drain(out, if (streaming) TIMEOUT_US else 0)
        return out
    }

    override fun finish(): List<ByteArray> {
        if (finished) return emptyList()
        val out = ArrayList<ByteArray>()
        val deadline = System.currentTimeMillis() + FINISH_DEADLINE_MS
        var index: Int
        do {
            index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) drain(out, 0)
        } while (index < 0 && System.currentTimeMillis() < deadline)
        if (index >= 0) {
            val ptsUs = queuedSamples * 1_000_000L / OpusCodec.sampleRate
            codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        while (System.currentTimeMillis() < deadline) {
            if (drain(out, TIMEOUT_US)) break
        }
        finished = true
        return out
    }

    /** @return `true` se e' arrivato l'end-of-stream. */
    private fun drain(out: MutableList<ByteArray>, firstTimeoutUs: Long): Boolean {
        var timeout = firstTimeoutUs
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeout)
            timeout = 0
            when {
                index >= 0 -> {
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && info.size > 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val packet = ByteArray(info.size)
                            buffer.get(packet)
                            out += packet
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                else -> return false
            }
        }
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }
}

/**
 * Decoder Opus di sistema. `csd-0` = OpusHead, `csd-1` = pre-skip e `csd-2` = seek pre-roll in
 * nanosecondi (8 byte little-endian). Il decoder di sistema esce a 48 kHz: si ricampiona a 16 kHz.
 * Pre-skip 0: come `AVAudioConverter` su iOS, nessun campione viene scartato all'inizio.
 */
internal class MediaCodecOpusDecoder(private val streaming: Boolean) : OpusCodec.FrameDecoder {

    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var outputRate = 48_000
    private var outputChannels = 1
    private var resampler = PcmResampler(outputRate, OpusCodec.sampleRate)
    private var queuedSamples = 0L
    private var finished = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, OpusCodec.channels)
        val head = OggOpus.opusHead(OggOpus.Header(channels = 1, preSkip = 0, inputSampleRate = OpusCodec.sampleRate.toLong()))
        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(head))
        format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(0L.littleEndianBytes))
        format.setByteBuffer("csd-2", java.nio.ByteBuffer.wrap(80_000_000L.littleEndianBytes))
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    override fun decode(packet: ByteArray): ShortArray {
        check(!finished) { "decoder chiuso" }
        val out = ArrayList<ShortArray>(2)
        val deadline = System.currentTimeMillis() + DEQUEUE_DEADLINE_MS
        var index = codec.dequeueInputBuffer(TIMEOUT_US)
        while (index < 0) {
            // Vedi encode: niente attese infinite su un codec in stallo.
            if (System.currentTimeMillis() > deadline) throw OpusCodec.Failure.decodeFailed("timeout input buffer")
            drain(out, 0)
            index = codec.dequeueInputBuffer(TIMEOUT_US)
        }
        val buffer = codec.getInputBuffer(index) ?: throw OpusCodec.Failure.decodeFailed("input buffer")
        buffer.clear()
        if (packet.size > buffer.remaining()) {
            codec.queueInputBuffer(index, 0, 0, ptsUs(), 0)
            throw OpusCodec.Failure.decodeFailed("pacchetto troppo grande")
        }
        buffer.put(packet)
        codec.queueInputBuffer(index, 0, packet.size, ptsUs(), 0)
        queuedSamples += OpusCodec.frameSamples
        drain(out, if (streaming) TIMEOUT_US else 0)
        return concat(out)
    }

    override fun finish(): ShortArray {
        if (finished) return ShortArray(0)
        val out = ArrayList<ShortArray>()
        val deadline = System.currentTimeMillis() + FINISH_DEADLINE_MS
        var index: Int
        do {
            index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) drain(out, 0)
        } while (index < 0 && System.currentTimeMillis() < deadline)
        if (index >= 0) codec.queueInputBuffer(index, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        while (System.currentTimeMillis() < deadline) {
            if (drain(out, TIMEOUT_US)) break
        }
        finished = true
        return concat(out)
    }

    private fun ptsUs(): Long = queuedSamples * 1_000_000L / OpusCodec.sampleRate

    private fun drain(out: MutableList<ShortArray>, firstTimeoutUs: Long): Boolean {
        var timeout = firstTimeoutUs
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeout)
            timeout = 0
            when {
                index >= 0 -> {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val raw = ShortArray(shorts.remaining())
                            shorts.get(raw)
                            out += toMono16k(raw)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    val rate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: outputRate
                    outputChannels = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannels
                    if (rate != outputRate) {
                        outputRate = rate
                        resampler = PcmResampler(rate, OpusCodec.sampleRate)
                    }
                }
                else -> return false
            }
        }
    }

    private fun toMono16k(raw: ShortArray): ShortArray {
        val mono = if (outputChannels <= 1) {
            FloatArray(raw.size) { raw[it] / 32_768f }
        } else {
            val frames = raw.size / outputChannels
            FloatArray(frames) { frame ->
                var sum = 0f
                for (c in 0 until outputChannels) sum += raw[frame * outputChannels + c]
                sum / outputChannels / 32_768f
            }
        }
        return OpusCodec.floatToPcm16(resampler.process(mono))
    }

    private fun concat(chunks: List<ShortArray>): ShortArray = when (chunks.size) {
        0 -> ShortArray(0)
        1 -> chunks[0]
        else -> {
            val out = ShortArray(chunks.sumOf { it.size })
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(out, offset)
                offset += chunk.size
            }
            out
        }
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }
}

private fun MediaFormat.getIntegerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

/**
 * Ricampionatore mono con stato (per stream a blocchi). Rapporto intero (48 -> 16 kHz): media di
 * `factor` campioni (filtro anti-alias minimo, sufficiente per la voce in banda larga Opus);
 * altrimenti interpolazione lineare.
 */
internal class PcmResampler(val inputRate: Int, val outputRate: Int) {

    private val step = inputRate.toDouble() / outputRate.toDouble()
    private val integerFactor = if (inputRate >= outputRate && inputRate % outputRate == 0) inputRate / outputRate else 0

    // Decimazione intera: gruppo parziale in sospeso.
    private var carrySum = 0f
    private var carryCount = 0

    // Interpolazione lineare: posizione del prossimo campione relativa al blocco corrente (-1 = ultimo campione precedente).
    private var position = 0.0
    private var last = 0f

    fun process(input: FloatArray): FloatArray {
        if (inputRate == outputRate) return input
        if (integerFactor > 0) {
            val out = FloatArray((carryCount + input.size) / integerFactor)
            var produced = 0
            for (sample in input) {
                carrySum += sample
                carryCount += 1
                if (carryCount == integerFactor) {
                    out[produced++] = carrySum / integerFactor
                    carrySum = 0f
                    carryCount = 0
                }
            }
            return out
        }
        if (input.isEmpty()) return input
        val n = input.size
        val out = ArrayList<Float>((n / step).toInt() + 2)
        while (position < n - 1) {
            val i = kotlin.math.floor(position).toInt()
            val frac = (position - i).toFloat()
            val s0 = if (i < 0) last else input[i]
            val s1 = input[i + 1]
            out += s0 + (s1 - s0) * frac
            position += step
        }
        position -= n
        last = input[n - 1]
        return out.toFloatArray()
    }
}
