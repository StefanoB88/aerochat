package com.example.aerochat.ui.safety

import android.content.Context
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.records.MessageContentType
import com.example.aerochat.data.records.MessageRecord
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Attende un `Task` di Google Play services / ML Kit senza dipendere da kotlinx-coroutines-play-services. */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

/**
 * Traduzione offline dei messaggi ricevuti (Swift `MessageTranslation`, framework Translation): ML Kit
 * on-device, nulla lascia il telefono. I modelli delle lingue vanno pero' scaricati una volta (serve rete):
 * lo si fa solo dopo il consenso esplicito dell'utente ([hasDownloadConsent]).
 */
object MessageTranslation {

    private const val preferencesName = "aeromesh.translation"
    private const val consentKey = "translation.downloadConsent"

    /** Lingua dell'utente verso cui tradurre (codice ML Kit), `null` se ML Kit non la supporta. */
    val targetLanguage: String?
        get() = mlKitLanguage(Locale.getDefault().language)

    /** Codice ML Kit da un tag BCP-47 (il norvegese bokmål di Android e' `nb`, per ML Kit `no`). */
    fun mlKitLanguage(tag: String): String? {
        val base = tag.substringBefore('-').lowercase(Locale.ROOT)
        val normalized = if (base == "nb" || base == "nn") "no" else base
        return TranslateLanguage.fromLanguageTag(normalized)
    }

    fun hasDownloadConsent(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).getBoolean(consentKey, false)

    fun setDownloadConsent(context: Context, granted: Boolean) {
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit().putBoolean(consentKey, granted).apply()
    }
}

/** Traduttori per lingua di origine, riusati per tutta la chat; [close] li libera. */
class MessageTranslator : Closeable {

    sealed interface Outcome {
        data class Translated(val text: String) : Outcome
        /** Gia' nella nostra lingua, lingua incerta o non supportata. */
        data object NotNeeded : Outcome
        /** Modello non scaricato (manca la rete) o errore del traduttore. */
        data object Unavailable : Outcome
    }

    private val identifier: LanguageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.6f).build(),
    )
    private val translators = mutableMapOf<String, Translator>()
    /** Lingue il cui download e' gia' fallito in questa sessione: niente nuovi tentativi a ogni messaggio. */
    private val failedSources = mutableSetOf<String>()

    /** Lingua rilevata (codice ML Kit) o `null` se incerta. */
    suspend fun detectLanguage(text: String): String? {
        val tag = runCatching { identifier.identifyLanguage(text).awaitResult() }.getOrNull() ?: return null
        if (tag == "und") return null
        return MessageTranslation.mlKitLanguage(tag)
    }

    suspend fun translate(text: String): Outcome {
        val target = MessageTranslation.targetLanguage ?: return Outcome.NotNeeded
        val source = detectLanguage(text) ?: return Outcome.NotNeeded
        if (source == target) return Outcome.NotNeeded
        if (source in failedSources) return Outcome.Unavailable
        val translator = translators.getOrPut(source) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        }
        return try {
            // Gia' presente: ritorna subito. Altrimenti scarica (consenso gia' dato da chi attiva la traduzione);
            // senza rete il task puo' restare in attesa: limite di 45 s.
            val ready = withTimeoutOrNull(45_000) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
                true
            } ?: false
            if (!ready) {
                failedSources += source
                return Outcome.Unavailable
            }
            val translated = translator.translate(text).awaitResult().trim()
            if (translated.isEmpty() || translated == text.trim()) Outcome.NotNeeded else Outcome.Translated(translated)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failedSources += source
            Outcome.Unavailable
        }
    }

    override fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
        runCatching { identifier.close() }
    }
}

/**
 * Risposte rapide (Swift `CabinAssistant.suggestReplies`) con ML Kit Smart Reply, interamente sul
 * telefono. Il modello supporta solo l'inglese: per le altre lingue non restituisce nulla.
 */
class SmartReplySuggester : Closeable {

    private val generator = SmartReply.getClient()

    /** Fino a 3 suggerimenti per l'ultimo messaggio ricevuto di [history] (in ordine cronologico). */
    suspend fun suggest(history: List<MessageRecord>): List<String> {
        val conversation = history
            .filter { it.contentType == MessageContentType.text && !it.text.isNullOrBlank() }
            .takeLast(10)
            .map { message ->
                val text = message.text.orEmpty()
                if (message.isOutgoing) {
                    TextMessage.createForLocalUser(text, message.createdAtMs)
                } else {
                    TextMessage.createForRemoteUser(text, message.createdAtMs, message.senderNodeID.toHex())
                }
            }
        if (conversation.isEmpty()) return emptyList()
        val result = try {
            generator.suggestReplies(conversation).awaitResult()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        if (result.status != SmartReplySuggestionResult.STATUS_SUCCESS) return emptyList()
        return result.suggestions.map { it.text.trim() }.filter { it.isNotEmpty() }.distinct().take(3)
    }

    override fun close() {
        runCatching { generator.close() }
    }
}
