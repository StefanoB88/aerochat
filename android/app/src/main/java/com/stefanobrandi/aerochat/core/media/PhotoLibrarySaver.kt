package com.stefanobrandi.aerochat.core.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore as SystemMediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Salva una foto della chat (WebP) nella galleria di sistema (`Pictures/AeroChat`) tramite
 * `android.provider.MediaStore`. Da API 29 inserire nella propria cartella non richiede permessi.
 */
object PhotoLibrarySaver {

    sealed class Failure(message: String) : Exception(message) {
        data object permissionDenied : Failure("permissionDenied")
        data object unreadableImage : Failure("unreadableImage")
    }

    const val relativeDirectory = "Pictures/AeroChat"

    /** Lato massimo (px) dell'immagine salvata (le foto della mesh sono al massimo 1280 px). */
    private const val maxSavedSide = 4_096

    /** Il WebP viene convertito in JPEG (qualita' 92), come su iOS: lo gestiscono tutte le app. */
    suspend fun save(context: Context, imageData: ByteArray) = withContext(Dispatchers.IO) {
        // Decodifica con `inSampleSize` (e budget di pixel): mai una bitmap piena da centinaia di MB.
        val image = try {
            ImageProcessor.downscale(imageData, maxSavedSide)
        } catch (_: Exception) {
            throw Failure.unreadableImage
        } catch (_: OutOfMemoryError) {
            throw Failure.unreadableImage
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(SystemMediaStore.Images.Media.DISPLAY_NAME, "AeroChat_${System.currentTimeMillis()}.jpg")
            put(SystemMediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(SystemMediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AeroChat")
            put(SystemMediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = SystemMediaStore.Images.Media.getContentUri(SystemMediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (_: SecurityException) {
            throw Failure.permissionDenied
        } ?: throw IOException("inserimento in MediaStore fallito")
        try {
            val written = resolver.openOutputStream(uri)?.use { stream ->
                image.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            } ?: false
            if (!written) throw IOException("scrittura JPEG fallita")
            values.clear()
            values.put(SystemMediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            if (e is SecurityException) throw Failure.permissionDenied
            throw e
        } finally {
            image.recycle()
        }
        Unit
    }
}
