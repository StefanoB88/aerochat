package com.stefanobrandi.aerochat.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
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
import kotlin.math.min

/**
 * Riproduce note vocali Ogg Opus: demux -> decodifica Opus -> `AudioTrack` (`USAGE_MEDIA`, parlato).
 * Stato osservabile come `StateFlow`; [duration] in ms, [progress] 0...1.
 */
class VoiceNotePlayer(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    sealed class Failure(message: String) : Exception(message) {
        data object emptyAudio : Failure("emptyAudio")
        data object formatUnavailable : Failure("formatUnavailable")
    }

    private val session = AudioSession(context.applicationContext)
    private val lock = Any()

    private val _isPlaying = MutableStateFlow(false)
    private val _progress = MutableStateFlow(0.0)
    private val _duration = MutableStateFlow(0L)
    private val _currentMediaPath = MutableStateFlow<String?>(null)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    val progress: StateFlow<Double> = _progress.asStateFlow()
    /** ms. */
    val duration: StateFlow<Long> = _duration.asStateFlow()
    val currentMediaPath: StateFlow<String?> = _currentMediaPath.asStateFlow()

    private var buffer: ShortArray? = null
    private var track: AudioTrack? = null
    private var progressJob: Job? = null
    /**
     * Ogni nuova riproduzione incrementa la generazione: il thread di scrittura di una riproduzione
     * fermata termina in ritardo e non deve chiudere la riproduzione successiva.
     */
    @Volatile
    private var generation = 0
    private var isPaused = false

    /** Decodifica (fuori dal main thread) e prepara il buffer PCM. */
    suspend fun load(oggOpus: ByteArray, mediaPath: String? = null) {
        stop()
        val samples = withContext(Dispatchers.Default) {
            val stream = OggOpus.decode(oggOpus)
            OpusCodec.floatToPcm16(OpusCodec.decode(stream.packets))
        }
        if (samples.isEmpty()) throw Failure.emptyAudio
        synchronized(lock) {
            buffer = samples
            _duration.value = samples.size.toLong() * 1_000 / OpusCodec.sampleRate
            _progress.value = 0.0
            _currentMediaPath.value = mediaPath
        }
    }

    fun play() = synchronized(lock) {
        val buffer = buffer ?: throw Failure.emptyAudio
        session.activate(
            usage = AudioAttributes.USAGE_MEDIA,
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            onFocusLoss = { pause() },
        )
        val existing = track
        if (isPaused && existing != null) {
            // Ripresa dal punto di pausa.
            isPaused = false
            existing.play()
            _isPlaying.value = true
            startProgressUpdates()
            return@synchronized
        }
        generation += 1
        val current = generation
        releaseTrack()
        val newTrack = makeTrack() ?: run {
            session.deactivate()
            throw Failure.formatUnavailable
        }
        track = newTrack
        newTrack.play()
        isPaused = false
        _isPlaying.value = true
        _progress.value = 0.0
        startWriter(newTrack, buffer, current)
        startProgressUpdates()
    }

    private fun makeTrack(): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(OpusCodec.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
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
                .setBufferSizeInBytes(maxOf(minBuffer, OpusCodec.sampleRate / 5 * 2))
                .build()
                .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        } catch (_: Exception) {
            null
        }
    }

    /** Scrive il PCM sul track (blocca durante la pausa) e segnala la fine quando e' stato riprodotto tutto. */
    private fun startWriter(track: AudioTrack, pcm: ShortArray, current: Int) {
        Thread({
            // Thread "nudo": un'eccezione non catturata qui ucciderebbe l'intero processo. Il track puo'
            // essere rilasciato da `stop()` (altro thread) in qualsiasi momento: ogni accesso e' protetto.
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val chunk = OpusCodec.sampleRate / 10
                var offset = 0
                while (offset < pcm.size && generation == current) {
                    val written = try {
                        track.write(pcm, offset, min(chunk, pcm.size - offset))
                    } catch (_: Exception) {
                        -1
                    }
                    if (written < 0) {
                        // ERROR_DEAD_OBJECT (audioserver riavviato) ecc.: chiudiamo la riproduzione,
                        // altrimenti `isPlaying` resterebbe true con il focus audio trattenuto.
                        finishPlayback(current)
                        return@Thread
                    }
                    offset += written
                }
                if (generation != current) return@Thread
                // Silenzio di coda: in MODE_STREAM la riproduzione parte solo a buffer pieno.
                // `bufferSizeInFrames` lancia IllegalStateException su un track gia' rilasciato.
                val frames = try { track.bufferSizeInFrames } catch (_: Exception) { return@Thread }
                val padding = ShortArray(frames.coerceAtLeast(chunk))
                if (generation == current) {
                    try { track.write(padding, 0, padding.size) } catch (_: Exception) {}
                }
                while (generation == current) {
                    val head = try { track.playbackHeadPosition } catch (_: Exception) { break }
                    if (head >= pcm.size) break
                    Thread.sleep(20)
                }
                finishPlayback(current)
            } catch (_: InterruptedException) {
            } catch (error: Exception) {
                android.util.Log.w("AeroMesh.VoiceNote", "writer terminato: $error")
                finishPlayback(current)
            }
        }, "AeroMesh.VoiceNote").apply { isDaemon = true }.start()
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(100)
                synchronized(lock) {
                    val track = track
                    val total = buffer?.size ?: 0
                    if (!_isPlaying.value || track == null) return@launch
                    if (total > 0) {
                        val head = try { track.playbackHeadPosition } catch (_: Exception) { 0 }
                        _progress.value = min(1.0, head.toDouble() / total)
                    }
                }
            }
        }
    }

    fun pause() = synchronized(lock) {
        if (!_isPlaying.value) return@synchronized
        track?.pause()
        isPaused = true
        _isPlaying.value = false
        progressJob?.cancel()
    }

    fun stop() = synchronized(lock) {
        generation += 1
        releaseTrack()
        isPaused = false
        _isPlaying.value = false
        _progress.value = 0.0
        progressJob?.cancel()
        progressJob = null
        releaseAudio()
    }

    private fun releaseTrack() {
        val old = track ?: return
        track = null
        try {
            old.pause()
            old.flush()
            old.stop()
        } catch (_: IllegalStateException) {
        }
        // `release()` sblocca anche una `write` bloccata in pausa.
        old.release()
    }

    /** Restituisce l'audio alle altre app (musica, podcast). */
    private fun releaseAudio() {
        session.deactivate()
    }

    private fun finishPlayback(finished: Int) = synchronized(lock) {
        if (finished != generation) return@synchronized
        releaseTrack()
        _isPlaying.value = false
        isPaused = false
        _progress.value = 1.0
        progressJob?.cancel()
        progressJob = null
        releaseAudio()
    }
}
