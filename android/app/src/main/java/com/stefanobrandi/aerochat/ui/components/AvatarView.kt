package com.stefanobrandi.aerochat.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stefanobrandi.aerochat.core.media.ImageProcessor
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Avatar circolare: foto profilo WebP se presente, altrimenti icona sul colore scelto dall'utente.
 * [systemImage] e' l'icona Material equivalente del simbolo SF (default `person.fill`).
 */
@Composable
fun AvatarView(
    imageData: ByteArray?,
    colorHex: String?,
    modifier: Modifier = Modifier,
    systemImage: ImageVector = Icons.Filled.Person,
    size: Dp = 46.dp,
) {
    // Chiave per contenuto, non per riferimento: Room restituisce un nuovo ByteArray a ogni modifica della
    // riga del peer (es. `last_seen` ogni 15 s), e con `LaunchedEffect(imageData)` ogni avatar visibile
    // veniva ridecodificato a ogni aggiornamento della lista.
    val contentKey = imageData?.contentHashCode()
    var image by remember(contentKey) { mutableStateOf(imageData?.let { AvatarView.cached(it) }) }
    LaunchedEffect(contentKey) {
        if (imageData == null || image != null) return@LaunchedEffect
        image = withContext(Dispatchers.Default) { AvatarView.decode(imageData) }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.fromHexString(colorHex) ?: AeroTheme.accentCyan)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Icon(
                systemImage,
                contentDescription = null,
                tint = Color.Black.copy(alpha = 0.8f),
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}

object AvatarView {
    /** Lato massimo (px) della bitmap decodificata: gli avatar sono 128 px e si mostrano al massimo a ~120 dp. */
    private const val maxDecodedSide = 512

    private class Entry(val data: ByteArray, val bitmap: Bitmap)

    /** Cache piccola (4 MB) delle bitmap decodificate, per contenuto (verificato con `contentEquals`). */
    private val cache = object : LruCache<Int, Entry>(4 * 1_048_576) {
        override fun sizeOf(key: Int, value: Entry): Int = value.bitmap.allocationByteCount
    }

    fun cached(data: ByteArray): Bitmap? =
        cache.get(data.contentHashCode())?.takeIf { it.data.contentEquals(data) }?.bitmap

    /**
     * Decodifica la foto profilo (WebP o qualsiasi formato supportato da BitmapFactory) con `inSampleSize`:
     * un avatar ricevuto puo' dichiarare dimensioni enormi in pochi byte.
     */
    fun decode(data: ByteArray): Bitmap? {
        cached(data)?.let { return it }
        val bitmap = try {
            ImageProcessor.downscale(data, maxDecodedSide)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        cache.put(data.contentHashCode(), Entry(data.copyOf(), bitmap))
        return bitmap
    }
}

/** Colore da stringa `RRGGBB` (es. dal profilo di un contatto); `null` se non valida (Swift `Color(hexString:)`). */
fun Color.Companion.fromHexString(hexString: String?): Color? {
    if (hexString == null || hexString.length != 6) return null
    val value = hexString.toLongOrNull(16) ?: return null
    return Color.hex(value)
}
