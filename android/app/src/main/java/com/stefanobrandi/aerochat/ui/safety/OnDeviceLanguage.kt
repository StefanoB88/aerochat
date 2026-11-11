package com.stefanobrandi.aerochat.ui.safety

import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.data.records.MessageContentType
import com.stefanobrandi.aerochat.data.records.MessageRecord
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Attende un `Task` di Google Play services / ML Kit senza dipendere da kotlinx-coroutines-play-services. */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
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
