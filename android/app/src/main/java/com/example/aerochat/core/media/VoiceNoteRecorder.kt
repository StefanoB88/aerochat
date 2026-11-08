package com.example.aerochat.core.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Registratore note vocali: cattura mono dal microfono (`AudioRecord`, sorgente `MIC`) a 16 kHz
 * (ricampionando se il dispositivo non lo supporta), codifica Opus 12 kbps VBR in container Ogg e
 * produce i 32 campioni di forma d'onda per la bolla.
 *
 * Stato osservabile come `StateFlow` (proprieta' `@Observable` su iOS). Le durate sono in ms.
 * Richiede il permesso runtime `RECORD_AUDIO` (va chiesto dalla UI con un `ActivityResultLauncher`).
 *
 * @param scope dove girano il meter e la callback [onAutoStop] (main thread di default).
 */
class VoiceNoteRecorder(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    class Recording(
        val oggOpus: ByteArray,
        val waveform: ByteArray,
        val durationMs: Int,
        val sampleCount: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Recording && oggOpus.contentEquals(other.oggOpus) && waveform.contentEquals(other.waveform) &&
                durationMs == other.durationMs && sampleCount == other.sampleCount

        override fun hashCode(): Int =
            ((oggOpus.contentHashCode() * 31 + waveform.contentHashCode()) * 31 + durationMs) * 31 + sampleCount
    }

    sealed class Failure(message: String) : Exception(message) {
        data object permissionDenied : Failure("permissionDenied")
        data object alreadyRecording : Failure("alreadyRecording")
        data object notRecording : Failure("notRecording")
        data object formatUnavailable : Failure("formatUnavailable")
        data object tooShort : Failure("tooShort")
    }

    private val appContext = context.applicationContext
    private val session = AudioSession(appContext)
    private val lock = Any()

    private val _isRecording = MutableStateFlow(false)
    private val _level = MutableStateFlow(0f)
    private val _elapsed = MutableStateFlow(0L)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    /** Livello 0...1 per l'animazione del microfono. */
    val level: StateFlow<Float> = _level.asStateFlow()
    /** ms dall'inizio della registrazione. */
    val elapsed: StateFlow<Long> = _elapsed.asStateFlow()

    private var capture: MicrophoneCapture? = null
    private var sink: SampleSink? = null
    private var meterJob: Job? = null
    private var startedAt = 0L

    /** Chiamato quando la registrazione si ferma da sola al limite dei 120 s (la nota va inviata, non persa). */
    var onAutoStop: ((Recording) -> Unit)? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Su Android il dialogo di sistema richiede un'Activity: la UI chiede `RECORD_AUDIO` con
     * `ActivityResultContracts.RequestPermission`; qui si verifica solo l'esito.
     */
    suspend fun requestPermission(): Boolean = hasPermission()

    fun start() = synchronized(lock) {
        if (_isRecording.value) throw Failure.alreadyRecording
        if (!hasPermission()) throw Failure.permissionDenied

        // `.playAndRecord` interrompe musica e podcast delle altre app.
        session.activate(
            usage = AudioAttributes.USAGE_MEDIA,
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
        )
        val sink = SampleSink()
        val capture = try {
            MicrophoneCapture.open(MediaRecorder.AudioSource.MIC, chunkSamples = 1_600) { samples -> sink.append(samples) }
        } catch (e: Exception) {
            // Microfono occupato (chiamata, assistente): pulizia completa, il prossimo tentativo deve poter ripartire.
            session.deactivate()
            throw e
        }
        this.sink = sink
        this.capture = capture
        _isRecording.value = true
        startedAt = System.currentTimeMillis()
        _elapsed.value = 0
        meterJob = scope.launch {
            while (isActive) {
                delay(100)
                _level.value = sink.currentLevel()
                _elapsed.value = System.currentTimeMillis() - startedAt
                if (_elapsed.value >= maximumDuration) {
                    // `stopCapture` cancella questo job: codifica e callback girano in una coroutine a parte.
                    val samples = try { stopCapture() } catch (_: Exception) { null }
                    if (samples != null) {
                        scope.launch {
                            val recording = try {
                                withContext(Dispatchers.Default) { makeRecording(samples) }
                            } catch (_: Exception) {
                                null
                            }
                            if (recording != null) onAutoStop?.invoke(recording)
                        }
                    }
                    break
                }
            }
        }
    }

    /**
     * Ferma la registrazione e restituisce il file Ogg Opus pronto per la mesh.
     * Codifica in modo sincrono: dal main thread usare [stopAndEncode].
     */
    fun stop(): Recording = makeRecording(stopCapture())

    /**
     * Ferma subito la cattura (thread chiamante) e codifica fuori dal main thread: 120 s di Opus
     * richiedono qualche secondo, abbastanza per un ANR se fatti sul main thread.
     */
    suspend fun stopAndEncode(): Recording {
        val samples = stopCapture()
        return withContext(Dispatchers.Default) { makeRecording(samples) }
    }

    /**
     * Ferma microfono, meter e audio focus e restituisce i campioni PCM a 16 kHz.
     * @throws Failure.notRecording / [Failure.tooShort]
     */
    fun stopCapture(): FloatArray {
        val samples: FloatArray
        synchronized(lock) {
            val sink = this.sink
            if (!_isRecording.value || sink == null) throw Failure.notRecording
            meterJob?.cancel()
            meterJob = null
            capture?.stop()
            capture = null
            _isRecording.value = false
            _level.value = 0f
            this.sink = null
            session.deactivate()
            samples = sink.drain()
        }
        if (samples.size.toDouble() / OpusCodec.sampleRate * 1_000 < minimumDuration) throw Failure.tooShort
        return samples
    }

    fun cancel() = synchronized(lock) {
        meterJob?.cancel()
        meterJob = null
        capture?.stop()
        capture = null
        _isRecording.value = false
        _level.value = 0f
        sink = null
        session.deactivate()
    }

    companion object {
        /** ms. */
        const val maximumDuration: Long = 120_000L
        /** ms. */
        const val minimumDuration: Long = 500L

        /** Pipeline pura (testabile): PCM 16 kHz -> Opus -> Ogg + forma d'onda. */
        fun makeRecording(samples: FloatArray): Recording {
            val packets = OpusCodec.encode(samples)
            val ogg = OggOpus.encode(packets)
            val waveform = OpusCodec.waveform(samples)
            val durationMs = (samples.size.toDouble() / OpusCodec.sampleRate * 1_000).roundToInt()
            return Recording(ogg, waveform, durationMs, samples.size)
        }
    }
}

/**
 * Accumula i campioni a 16 kHz. Usato dal thread di cattura e dal chiamante di `stop()` (drain):
 * l'accesso e' serializzato dal lock.
 */
class SampleSink {

    private val lock = Any()
    private var samples = FloatArray(16_000 * 4)
    private var count = 0
    private var lastLevel = 0f

    fun append(chunk: FloatArray) {
        if (chunk.isEmpty()) return
        var sum = 0f
        for (value in chunk) sum += value * value
        val rms = sqrt(sum / chunk.size.toFloat())
        synchronized(lock) {
            if (count + chunk.size > samples.size) {
                samples = samples.copyOf(maxOf(samples.size * 2, count + chunk.size))
            }
            chunk.copyInto(samples, count)
            count += chunk.size
            lastLevel = min(1f, rms * 4)
        }
    }

    fun currentLevel(): Float = synchronized(lock) { lastLevel }

    fun drain(): FloatArray = synchronized(lock) {
        val all = samples.copyOf(count)
        count = 0
        all
    }
}

/**
 * Cattura dal microfono su un thread dedicato, consegnando blocchi Float mono a 16 kHz.
 * Prova prima 16 kHz nativi, poi 48 / 44.1 kHz con ricampionamento.
 */
internal class MicrophoneCapture private constructor(
    val record: AudioRecord,
    private val inputRate: Int,
    private val chunkSamples: Int,
    private val onSamples: (FloatArray) -> Unit,
) {
    @Volatile
    private var running = true
    private val thread = Thread({ loop() }, "AeroMesh.Microphone")

    val audioSessionId: Int get() = record.audioSessionId

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val resampler = PcmResampler(inputRate, OpusCodec.sampleRate)
        val inputChunk = (chunkSamples.toLong() * inputRate / OpusCodec.sampleRate).toInt().coerceAtLeast(1)
        val buffer = ShortArray(inputChunk)
        while (running) {
            var filled = 0
            while (running && filled < inputChunk) {
                val read = record.read(buffer, filled, inputChunk - filled)
                if (read < 0) {
                    running = false
                    break
                }
                filled += read
            }
            if (filled == 0) continue
            val floats = FloatArray(filled) { buffer[it] / 32_768f }
            val samples = resampler.process(floats)
            if (samples.isNotEmpty()) onSamples(samples)
        }
    }

    fun stop() {
        running = false
        try { record.stop() } catch (_: IllegalStateException) {}
        if (Thread.currentThread() !== thread) thread.join(500)
        record.release()
    }

    companion object {
        private val candidateRates = intArrayOf(OpusCodec.sampleRate, 48_000, 44_100)

        /** @throws IOException se nessuna configurazione funziona o il microfono e' occupato. */
        @SuppressLint("MissingPermission")
        fun open(source: Int, chunkSamples: Int, configure: (AudioRecord) -> Unit = {}, onSamples: (FloatArray) -> Unit): MicrophoneCapture {
            for (rate in candidateRates) {
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) continue
                val record = try {
                    AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate / 5 * 2))
                } catch (_: Exception) {
                    continue
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    continue
                }
                try {
                    // Effetti AEC/NS delle chiamate: se lanciano, l'AudioRecord (e il microfono) va rilasciato subito.
                    configure(record)
                    record.startRecording()
                } catch (_: IllegalStateException) {
                } catch (e: Exception) {
                    record.release()
                    throw IOException("configurazione microfono fallita: ${e.message}", e)
                }
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    record.release()
                    throw IOException("microfono non disponibile")
                }
                val capture = MicrophoneCapture(record, rate, chunkSamples, onSamples)
                capture.thread.start()
                return capture
            }
            throw IOException("nessun formato di cattura disponibile")
        }
    }
}
