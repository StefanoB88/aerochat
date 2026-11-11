package com.stefanobrandi.aerochat.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pipeline foto per la mesh: ridimensiona (lato lungo max 1280 px), codifica WebP a qualita' 65
 * puntando a 50-80 KB e calcola il BlurHash 4x3 per l'anteprima istantanea.
 */
object ImageProcessor {

    class Result(
        val webp: ByteArray,
        val blurhash: String,
        val width: Int,
        val height: Int,
        val quality: Float,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Result && webp.contentEquals(other.webp) && blurhash == other.blurhash &&
                width == other.width && height == other.height && quality == other.quality

        override fun hashCode(): Int =
            ((webp.contentHashCode() * 31 + blurhash.hashCode()) * 31 + width) * 31 + height

        override fun toString(): String =
            "Result(webp=${webp.size} B, blurhash=$blurhash, width=$width, height=$height, quality=$quality)"
    }

    sealed class Failure(message: String) : Exception(message) {
        data object unreadableImage : Failure("unreadableImage")
    }

    const val maxLongSide = 1_280
    const val minimumLongSide = 640
    const val targetMinBytes = 50_000
    const val targetMaxBytes = 80_000
    const val initialQuality: Float = 65f
    const val minimumQuality: Float = 35f

    /** Da dati compressi (JPEG/HEIC/PNG/WebP dalla fotocamera o dalla galleria), applicando l'orientamento EXIF. */
    fun prepareForMesh(imageData: ByteArray): Result {
        val image = downscale(imageData, maxLongSide)
        return prepareForMesh(image)
    }

    fun prepareForMesh(image: Bitmap): Result {
        var working = downscale(image, maxLongSide)
        var quality = initialQuality
        var webp = WebPEncoder.encode(working, quality)

        // Scala la qualita' finche' non rientriamo negli 80 KB (foto molto dettagliate).
        var attempts = 0
        while (webp.size > targetMaxBytes && quality > minimumQuality && attempts < 6) {
            quality = max(minimumQuality, quality - 8)
            webp = WebPEncoder.encode(working, quality)
            attempts += 1
        }
        // Ultima risorsa (immagini ad altissimo dettaglio): riduci il lato lungo del 20% alla volta,
        // mai sotto i 640 px, finche' il file non rientra nel budget della banda 2.4 GHz.
        while (webp.size > targetMaxBytes && max(working.width, working.height) > minimumLongSide) {
            val nextLongSide = max(minimumLongSide, (max(working.width, working.height).toDouble() * 0.8).toInt())
            working = downscale(working, nextLongSide)
            webp = WebPEncoder.encode(working, quality)
        }

        val blurhash = BlurHash.encode(working)
        return Result(webp, blurhash, working.width, working.height, quality)
    }

    /** Foto profilo: ritaglio quadrato centrale 128x128 in WebP entro `MeshProtocol.maxAvatarBytes`. */
    fun prepareAvatar(imageData: ByteArray): ByteArray {
        val side = MeshProtocol.avatarPixelSize
        // Il lato corto deve arrivare almeno a `side` dopo il ritaglio: prima un downscale generoso.
        val image = downscale(imageData, side * 4)
        val crop = min(image.width, image.height)
        val square = try {
            Bitmap.createBitmap(image, (image.width - crop) / 2, (image.height - crop) / 2, crop, crop)
        } catch (_: IllegalArgumentException) {
            throw Failure.unreadableImage
        }
        val avatar = downscale(square, side)
        var quality = 75f
        var webp = WebPEncoder.encode(avatar, quality)
        while (webp.size > MeshProtocol.maxAvatarBytes && quality > 20) {
            quality -= 15
            webp = WebPEncoder.encode(avatar, quality)
        }
        if (webp.size > MeshProtocol.maxAvatarBytes) throw Failure.unreadableImage
        return webp
    }

    /**
     * Decodifica + thumbnail (equivalente di `CGImageSourceCreateThumbnailAtIndex`): `inSampleSize` evita di
     * caricare l'immagine piena in memoria, poi riduzione esatta a `maxLongSide` (mai ingrandita) e
     * applicazione dell'orientamento EXIF.
     */
    /** Pixel massimi di una decodifica intermedia (~96 MB in ARGB_8888). */
    const val maxDecodePixels: Long = 24_000_000L

    fun downscale(imageData: ByteArray, maxLongSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageData, 0, imageData.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw Failure.unreadableImage

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxLongSide) sampleSize *= 2
        // Tetto assoluto della decodifica intermedia: il criterio sopra la limita a < 4 * maxLongSide^2 pixel,
        // che per i lati grandi del viewer (4096) vuol dire fino a 268 MB (es. WebP 16383x16383 di pochi KB
        // ricevuto da un altro telefono) -> OutOfMemoryError. Per maxLongSide <= 2449 non cambia nulla.
        while ((bounds.outWidth.toLong() / sampleSize) * (bounds.outHeight.toLong() / sampleSize) > maxDecodePixels) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
            ?: throw Failure.unreadableImage
        val scaled = downscale(decoded, maxLongSide)
        if (scaled !== decoded) decoded.recycle()
        return applyExifOrientation(scaled, imageData)
    }

    /**
     * `true` se [data] dichiara dimensioni oltre [maxSide] (lettura del solo header). Dati non decodificabili
     * ritornano `false`: non possono comunque produrre una bitmap.
     */
    fun exceedsDimensions(data: ByteArray, maxSide: Int): Boolean {
        val dimensions = WebPEncoder.dimensions(of = data) ?: return false
        return max(dimensions.width, dimensions.height) > maxSide
    }

    fun downscale(image: Bitmap, maxLongSide: Int): Bitmap {
        val longSide = max(image.width, image.height)
        if (longSide <= maxLongSide) return image
        val scale = maxLongSide.toDouble() / longSide.toDouble()
        val width = max(1, (image.width.toDouble() * scale).roundToInt())
        val height = max(1, (image.height.toDouble() * scale).roundToInt())
        return Bitmap.createScaledBitmap(image, width, height, true)
    }

    private fun applyExifOrientation(image: Bitmap, imageData: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(imageData))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return image
        }
        val rotated = Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
        if (rotated !== image) image.recycle()
        return rotated
    }
}
