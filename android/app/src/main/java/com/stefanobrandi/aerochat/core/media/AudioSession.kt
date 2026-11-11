package com.stefanobrandi.aerochat.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Equivalente Android di `AVAudioSession` (file senza controparte iOS): audio focus, modalita'
 * dell'`AudioManager` e instradamento sull'altoparlante. Un'istanza per componente audio
 * ([VoiceNoteRecorder], [VoiceNotePlayer], `CallAudioEngine`).
 */
class AudioSession(context: Context) {

    val audioManager: AudioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val lock = Any()
    private var focusRequest: AudioFocusRequest? = null
    private var previousMode: Int? = null

    /**
     * `setActive(true)`: richiede l'audio focus. `onFocusLoss` viene chiamato (su un thread di sistema)
     * quando un'altra app prende il focus (telefonata, sveglia...).
     * @return `true` se il focus e' stato concesso.
     */
    fun activate(
        usage: Int,
        contentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH,
        focusGain: Int = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        onFocusLoss: (() -> Unit)? = null,
    ): Boolean = synchronized(lock) {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        val attributes = AudioAttributes.Builder().setUsage(usage).setContentType(contentType).build()
        val request = AudioFocusRequest.Builder(focusGain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    onFocusLoss?.invoke()
                }
            }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** `setActive(false, options: .notifyOthersOnDeactivation)`: restituisce il focus alle altre app. */
    fun deactivate() = synchronized(lock) {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Modalita' chiamata VoIP (`.voiceChat`): `MODE_IN_COMMUNICATION`, ripristinando poi quella precedente. */
    fun setCommunicationMode(enabled: Boolean) = synchronized(lock) {
        if (enabled) {
            if (previousMode == null) previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } else {
            previousMode?.let { audioManager.mode = it }
            previousMode = null
        }
    }

    /** `overrideOutputAudioPort(.speaker / .none)` durante una chiamata. */
    fun setSpeaker(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                val speaker = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) audioManager.setCommunicationDevice(speaker)
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        }
    }
}
