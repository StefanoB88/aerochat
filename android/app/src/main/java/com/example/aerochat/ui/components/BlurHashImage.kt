package com.example.aerochat.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aerochat.R
import com.example.aerochat.core.media.BlurHash
import com.example.aerochat.core.media.ImageProcessor
import com.example.aerochat.core.media.MediaStore
import com.example.aerochat.core.media.WebPEncoder
import com.example.aerochat.ui.AeroTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Anteprima istantanea da BlurHash (decodifica 32x32 fuori dal main thread, poi upscaling bilineare). */
@Composable
fun BlurHashImage(hash: String, modifier: Modifier = Modifier, punch: Double = 1.0) {
    val image by produceState<Bitmap?>(initialValue = null, hash, punch) {
        value = withContext(Dispatchers.Default) { BlurHash.image(hash, width = 32, height = 32, punch = punch) }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.High,
        )
    } else {
        Box(modifier.fillMaxSize().background(AeroTheme.surface))
    }
}

/** Anello di avanzamento del trasferimento (chunk ricevuti / totali) sopra l'anteprima BlurHash. */
@Composable
fun TransferProgressRing(progress: Double, modifier: Modifier = Modifier, lineWidth: Dp = 4.dp, size: Dp = 44.dp) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0.02, 1.0).toFloat(),
        animationSpec = tween(250),
        label = "transferProgress",
    )
    val percent = (progress * 100).roundToInt()
    val description = stringResource(R.string.chat_transfer_percent, percent)
    Box(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = description }
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .padding(6.dp)
            .size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = lineWidth.toPx()
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(
                color = Color.White.copy(alpha = 0.25f), startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = stroke),
            )
            drawArc(
                color = AeroTheme.accentCyan, startAngle = -90f, sweepAngle = 360f * animated, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = "$percent%",
            color = Color.White,
            fontSize = (size.value * 0.26f).sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** Foto ricevuta/inviata: BlurHash finche' il file WebP non e' pronto, poi l'immagine vera. */
@Composable
fun MediaImageView(
    blurhash: String?,
    relativePath: String?,
    store: MediaStore?,
    modifier: Modifier = Modifier,
    transferProgress: Double? = null,
) {
    // Gia' decodificata (riapparsa scorrendo): niente BlurHash ne' nuova decodifica.
    var image by remember(relativePath) { mutableStateOf(relativePath?.let { ChatImageCache.cached(it) }) }
    LaunchedEffect(relativePath, store) {
        if (relativePath == null || store == null) return@LaunchedEffect
        ChatImageCache.cached(relativePath)?.let {
            image = it
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) { ChatImageCache.load(store, relativePath) }
        image = loaded
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val current = image
        if (blurhash != null && current == null) {
            BlurHashImage(hash = blurhash)
        }
        AnimatedVisibility(visible = current != null, enter = fadeIn(tween(250))) {
            val shown = current ?: return@AnimatedVisibility
            Image(
                bitmap = remember(shown) { shown.asImageBitmap() },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (transferProgress != null) {
            // In ricezione sopra il BlurHash, in invio sopra la foto gia' visibile.
            TransferProgressRing(progress = transferProgress)
        } else if (current == null && relativePath == null) {
            CircularProgressIndicator(color = AeroTheme.accentCyan, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * Foto della chat ridotte alla dimensione della bolla (660 px) e decodificate fuori dal main thread;
 * restano in cache cosi' lo scroll avanti/indietro non le ridecodifica.
 */
object ChatImageCache {

    const val maxPixelSize = 660

    /** 48 MB, ma mai oltre 1/8 dell'heap (su dispositivi con heap da 128 MB sarebbe piu' di un terzo). */
    private val cacheBytes: Int = minOf(48L * 1_048_576, Runtime.getRuntime().maxMemory() / 8).toInt()

    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun cached(relativePath: String): Bitmap? = cache.get(relativePath)

    /** Decodifica bloccante (da chiamare su `Dispatchers.IO`). */
    fun load(store: MediaStore, relativePath: String): Bitmap? {
        val data = try {
            store.read(relativePath = relativePath)
        } catch (_: Exception) {
            return null
        }
        val bitmap = try {
            ImageProcessor.downscale(data, maxPixelSize)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            try {
                ImageProcessor.downscale(WebPEncoder.decode(data), maxPixelSize)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        } ?: return null
        cache.put(relativePath, bitmap)
        return bitmap
    }
}
