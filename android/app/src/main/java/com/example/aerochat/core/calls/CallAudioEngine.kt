package com.example.aerochat.core.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import com.example.aerochat.core.media.AudioSession
import com.example.aerochat.core.media.MicrophoneCapture
import com.example.aerochat.core.media.OpusCodec
import com.example.aerochat.core.protocol.MeshProtocol
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Audio full-duplex della chiamata: microfono (`VOICE_COMMUNICATION` + AEC/NS di sistema) -> 16 kHz ->
 * Opus a frame da 20 ms (a gruppi di `MeshProtocol.callFramesPerPacket`) e, in ricezione, Opus -> PCM
 * su un `AudioTrack` (`USAGE_VOICE_COMMUNICATION`) con un piccolo jitter buffer.
 *
 * Tutto lo stato del codec vive sul [codecQueue] seriale; il thread del microfono e quello di
 * riproduzione vi saltano con `execute`.
 */
class CallAudioEngine(context: Context) {

    /** `([Data]) -> Void` su iOS: i frame Opus di un pacchetto `CALL_AUDIO`. */
    fun interface PacketHandler {
        fun invoke(frames: List<ByteArray>)
    }

    sealed class Failure(message: String) : Exception(message) {
        data object formatUnavailable : Failure("formatUnavailable")
        data object microphoneUnavailable : Failure("microphoneUnavailable")
    }

    companion object {
        /** Pacchetti in coda nel player prima di iniziare (o riprendere) la riproduzione: ~120 ms. */
        const val prebufferPackets = 2
        /** Oltre questa coda (~480 ms) i pacchetti in arrivo vengono scartati per non accumulare ritardo. */
        const val maxQueuedPackets = 8
    }

    private val session = AudioSession(context.applicationContext)
    private val codecQueue: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AeroMesh.CallCodec").apply { priority = Thread.MAX_PRIORITY; isDaemon = true }
    }

    // Stato confinato alla codecQueue.
    private var encoder: OpusStreamEncoder? = null
    private var decoder: OpusStreamDecoder? = null
    private var pendingSamples = FloatArray(0)
    private val pendingFrames = ArrayList<ByteArray>()
    /** `UInt32` su iOS: confronto senza segno. */
    private var lastReceivedSequence = 0
    private var onPacket: PacketHandler? = null
    private var isMuted = false

    // Jitter buffer: condiviso tra codecQueue e thread di riproduzione, protetto da `playLock`.
    private val playLock = Object()
    private val playbackQueue = ArrayDeque<ShortArray>()
    internal var queuedPackets = 0
        private set
    internal var isPrebuffering = true
        private set

    // Scritto solo da start/stop, letto dal thread di riproduzione.
    @Volatile
    private var isRunning = false
    private var capture: MicrophoneCapture? = null
    private var track: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val effects = ArrayList<AudioEffect>()

    // MARK: - Ciclo di vita

    @Synchronized
    fun start(onPacket: PacketHandler) {
        if (isRunning) return
        startCodec(onPacket)

        // `.playAndRecord` + `.voiceChat` + `.duckOthers`.
        session.activate(
            usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )
        session.setCommunicationMode(true)

        val track = makeTrack()
        if (track == null) {
            stopCodec()
            releaseSession()
            throw Failure.formatUnavailable
        }
        val capture = try {
            MicrophoneCapture.open(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                chunkSamples = OpusCodec.frameSamples,
                configure = { record -> attachEffects(record.audioSessionId) },
            ) { samples -> receiveCaptured(samples) }
        } catch (_: Exception) {
            track.release()
            releaseEffects()
            stopCodec()
            releaseSession()
            throw Failure.microphoneUnavailable
        }
        try {
            track.play()
        } catch (_: IllegalStateException) {
            // Track invalidato (audioserver riavviato / route cambiata): rilascio completo, niente leak del microfono.
            capture.stop()
            track.release()
            releaseEffects()
            stopCodec()
            releaseSession()
            throw Failure.formatUnavailable
        }
        this.track = track
        this.capture = capture
        val thread = Thread({ playbackLoop(track) }, "AeroMesh.CallPlayback")
        playbackThread = thread
        isRunning = true
        thread.start()
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        // Prima si spegne il codec (nessun nuovo pacchetto), poi l'audio.
        stopCodec()
        capture?.stop()
        capture = null
        releaseEffects()
        synchronized(playLock) { playLock.notifyAll() }
        track?.let { track ->
            try {
                track.pause()
                track.flush()
                track.stop()
            } catch (_: IllegalStateException) {
            }
            track.release()
        }
        track = null
        playbackThread?.join(500)
        playbackThread = null
        session.setSpeaker(false)
        releaseSession()
    }

    fun setMuted(muted: Boolean) {
        codecQueue.execute { isMuted = muted }
    }

    fun setSpeaker(enabled: Boolean) {
        session.setSpeaker(enabled)
    }

    private fun releaseSession() {
        session.setCommunicationMode(false)
        session.deactivate()
    }

    /** Prepara encoder/decoder e azzera lo stato (separato dall'hardware audio per i test). */
    internal fun startCodec(onPacket: PacketHandler) {
        val encoder = OpusStreamEncoder()
        val decoder = OpusStreamDecoder()
        if (encoder == null || decoder == null) {
            encoder?.close()
            decoder?.close()
            throw Failure.formatUnavailable
        }
        codecQueue.submit {
            this.encoder = encoder
            this.decoder = decoder
            this.onPacket = onPacket
            pendingSamples = FloatArray(0)
            pendingFrames.clear()
            lastReceivedSequence = 0
            isMuted = false
            synchronized(playLock) {
                playbackQueue.clear()
                queuedPackets = 0
                isPrebuffering = true
            }
        }.get()
    }

    internal fun stopCodec() {
        codecQueue.submit {
            onPacket = null
            encoder?.close()
            decoder?.close()
            encoder = null
            decoder = null
            pendingSamples = FloatArray(0)
            pendingFrames.clear()
            synchronized(playLock) {
                playbackQueue.clear()
                queuedPackets = 0
                playLock.notifyAll()
            }
        }.get()
    }

    /** Attende che la codecQueue abbia processato tutto il lavoro accodato (test). */
    internal fun drainCodecQueue() {
        codecQueue.submit {}.get()
    }

    private fun makeTrack(): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(OpusCodec.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(OpusCodec.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer, OpusCodec.frameSamples * 2 * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
                .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        } catch (_: Exception) {
            null
        }
    }

    /** Cancellazione dell'eco e riduzione del rumore di sistema (fondamentale in vivavoce). */
    private fun attachEffects(audioSessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(audioSessionId)?.let { effects += it; runCatching { it.enabled = true } }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(audioSessionId)?.let { effects += it; runCatching { it.enabled = true } }
        }
    }

    private fun releaseEffects() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
    }

    // MARK: - Cattura (thread microfono -> codecQueue)

    /**
     * Campioni 16 kHz mono catturati dal microfono (qualsiasi thread): saltano sulla codecQueue come il
     * tap di iOS (`engine.codecQueue.async { engine.encode(samples) }`).
     */
    internal fun receiveCaptured(samples: FloatArray) {
        codecQueue.execute { encode(samples) }
    }

    /** Solo sulla codecQueue. */
    internal fun encode(samples: FloatArray) {
        val encoder = encoder ?: return
        val onPacket = onPacket ?: return
        // In muto si inviano frame di silenzio: il compagno continua a sapere che il link e' vivo.
        val incoming = if (isMuted) FloatArray(samples.size) else samples
        val merged = FloatArray(pendingSamples.size + incoming.size)
        pendingSamples.copyInto(merged)
        incoming.copyInto(merged, pendingSamples.size)
        val frameSize = OpusCodec.frameSamples
        var offset = 0
        while (merged.size - offset >= frameSize) {
            val frame = merged.copyOfRange(offset, offset + frameSize)
            offset += frameSize
            for (packet in encoder.encodeAll(frame)) {
                pendingFrames += packet
                if (pendingFrames.size >= MeshProtocol.callFramesPerPacket) {
                    onPacket.invoke(pendingFrames.toList())
                    pendingFrames.clear()
                }
            }
        }
        pendingSamples = merged.copyOfRange(offset, merged.size)
    }

    // MARK: - Riproduzione

    /** Audio ricevuto dal compagno (qualsiasi thread). `sequence` e' un `UInt32` (bit pattern). */
    fun receive(sequence: Int, frames: List<ByteArray>) {
        codecQueue.execute { play(sequence, frames) }
    }

    /** Solo sulla codecQueue. */
    internal fun play(sequence: Int, frames: List<ByteArray>) {
        val decoder = decoder ?: return
        if (Integer.compareUnsigned(sequence, lastReceivedSequence) <= 0) return
        lastReceivedSequence = sequence
        // Coda troppo lunga (raffica dopo un buco radio): meglio perdere 60 ms che restare indietro.
        if (synchronized(playLock) { queuedPackets } >= maxQueuedPackets) return
        val decoded = frames.take(MeshProtocol.callFramesPerPacket * 2).map { decoder.decode(it) }
        val total = decoded.sumOf { it.size }
        if (total == 0) return
        val pcm = ShortArray(total)
        var offset = 0
        for (chunk in decoded) {
            for (sample in chunk) pcm[offset++] = OpusCodec.floatToPcm16(sample)
        }
        synchronized(playLock) {
            playbackQueue.addLast(pcm)
            queuedPackets += 1
            if (isPrebuffering && queuedPackets >= prebufferPackets) {
                isPrebuffering = false
            }
            playLock.notifyAll()
        }
    }

    /** Thread di riproduzione: scrive i buffer in coda sul track (scrittura bloccante, ~tempo reale). */
    private fun playbackLoop(track: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (true) {
            val buffer: ShortArray = synchronized(playLock) {
                while (isRunning && (isPrebuffering || playbackQueue.isEmpty())) {
                    try {
                        playLock.wait(100)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                if (!isRunning) return
                playbackQueue.removeFirst()
            }
            val written = try {
                track.write(buffer, 0, buffer.size)
            } catch (_: Exception) {
                -1
            }
            bufferFinished()
            if (written < 0) return
        }
    }

    private fun bufferFinished() = synchronized(playLock) {
        queuedPackets = maxOf(0, queuedPackets - 1)
        if (queuedPackets == 0) {
            // Coda vuota: nuovo prebuffer, cosi' il jitter della radio non diventa audio a scatti.
            isPrebuffering = true
        }
    }

    /** Test: simula il consumo di un buffer da parte del thread di riproduzione. */
    internal fun consumeQueuedBufferForTesting(): ShortArray? {
        val buffer = synchronized(playLock) { playbackQueue.removeFirstOrNull() } ?: return null
        bufferFinished()
        return buffer
    }
}

/**
 * Encoder Opus con stato (un frame da 20 ms alla volta). `OpusStreamEncoder()` restituisce `null`
 * se il codec non e' disponibile (`init?()` su iOS).
 */
class OpusStreamEncoder private constructor(private val encoder: OpusCodec.FrameEncoder) : Closeable {

    private val ready = ArrayDeque<ByteArray>()

    /** Un pacchetto per frame da 20 ms (320 campioni Float), come su iOS. */
    fun encode(frame: FloatArray): ByteArray? {
        ready.addAll(encodeAll(frame))
        return ready.removeFirstOrNull()
    }

    /**
     * Tutti i pacchetti pronti dopo aver accodato [frame]: `MediaCodec` puo' restituirli con un frame di
     * ritardo, e la chiamata non deve accumulare latenza.
     */
    fun encodeAll(frame: FloatArray): List<ByteArray> {
        if (frame.size != OpusCodec.frameSamples) return emptyList()
        return try {
            encoder.encode(OpusCodec.floatToPcm16(frame)).filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun close() = encoder.close()

    companion object {
        operator fun invoke(): OpusStreamEncoder? = try {
            OpusStreamEncoder(OpusCodec.makeEncoder(streaming = true))
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Decoder Opus con stato (un pacchetto alla volta, nell'ordine di arrivo). `OpusStreamDecoder()`
 * restituisce `null` se il codec non e' disponibile.
 */
class OpusStreamDecoder private constructor(private val decoder: OpusCodec.FrameDecoder) : Closeable {

    fun decode(packet: ByteArray): FloatArray {
        if (packet.isEmpty() || packet.size > 1_500) return FloatArray(0)
        return try {
            OpusCodec.pcm16ToFloat(decoder.decode(packet))
        } catch (_: Exception) {
            FloatArray(0)
        }
    }

    override fun close() = decoder.close()

    companion object {
        operator fun invoke(): OpusStreamDecoder? = try {
            OpusStreamDecoder(OpusCodec.makeDecoder(streaming = true))
        } catch (_: Exception) {
            null
        }
    }
}
