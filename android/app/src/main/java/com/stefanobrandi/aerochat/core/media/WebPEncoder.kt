package com.stefanobrandi.aerochat.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Codifica WebP lossy con l'encoder di sistema (`Bitmap.compress`): `WEBP_LOSSY` da API 30,
 * `WEBP` (lossy per qualita' < 100) su API 29. iOS usa libwebp; i file sono WebP standard (RIFF/WEBP)
 * e si decodificano nativamente su entrambe le piattaforme.
 */
object WebPEncoder {

    sealed class Failure(message: String) : Exception(message) {
        data object encodeFailed : Failure("encodeFailed")
        data object decodeFailed : Failure("decodeFailed")
    }

    /** Dimensioni in pixel (tupla `(width, height)` su iOS). */
    data class Dimensions(val width: Int, val height: Int)

    /** Codifica `image` in WebP lossy con la qualita' indicata (0...100). */
    fun encode(image: Bitmap, quality: Float): ByteArray {
        // Qualita' 100 su API 29 (`WEBP`) significherebbe lossless: restiamo sempre in lossy.
        val q = quality.toInt().coerceIn(0, 99)
        val output = ByteArrayOutputStream()
        val ok = try {
            image.compress(webpFormat(), q, output)
        } catch (_: RuntimeException) {
            false
        }
        val bytes = output.toByteArray()
        if (!ok || bytes.isEmpty()) throw Failure.encodeFailed
        return bytes
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    /** Decodifica nativa con `BitmapFactory`. */
    fun decode(data: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw Failure.decodeFailed

    /** `true` se i byte iniziano con il container RIFF/WEBP. */
    fun isWebP(data: ByteArray): Boolean =
        data.size > 12 &&
            data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() &&
            data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte()

    /** Dimensioni senza decodificare i pixel. */
    fun dimensions(of: ByteArray): Dimensions? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(of, 0, of.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return Dimensions(options.outWidth, options.outHeight)
    }
}
