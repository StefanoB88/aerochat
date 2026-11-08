package com.example.aerochat.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.aerochat.R
import com.example.aerochat.core.media.ImageProcessor
import com.example.aerochat.core.media.MediaStore
import com.example.aerochat.core.media.WebPEncoder
import com.example.aerochat.ui.AeroTheme
import com.example.aerochat.ui.chat.InfoBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val maxScale = 4f
private const val doubleTapScale = 2.5f
/** Lato lungo massimo della decodifica a piena risoluzione (le foto della mesh sono comunque piu' piccole). */
private const val fullSizeMaxPixels = 4096

/**
 * Visore foto a schermo intero (Swift `ImageViewer` in `fullScreenCover`): sfondo nero, immagine
 * adattata, pinch fino a 4x, trascinamento quando ingrandita, doppio tocco per ingrandire/ripristinare,
 * trascinamento verso il basso per chiudere, pulsanti chiudi e salva in galleria.
 *
 * @param onSave salvataggio in galleria (stessa logica del menu della bolla); restituisce il testo da mostrare.
 */
@Composable
fun ImageViewer(
    relativePath: String,
    store: MediaStore?,
    onSave: suspend () -> String?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ImageViewerContent(relativePath, store, onSave, onDismiss)
    }
}

@Composable
private fun ImageViewerContent(
    relativePath: String,
    store: MediaStore?,
    onSave: suspend () -> String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Subito la miniatura della bolla (gia' in cache), poi la versione a piena risoluzione fuori dal main thread.
    var image by remember(relativePath) { mutableStateOf(ChatImageCache.cached(relativePath)) }
    LaunchedEffect(relativePath, store) {
        if (store == null) return@LaunchedEffect
        val full = withContext(Dispatchers.IO) { decodeFullSize(store, relativePath) }
        if (full != null) image = full
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dismissOffset by remember { mutableFloatStateOf(0f) }
    var isSaving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    val dismissThreshold = with(density) { 120.dp.toPx() }
    val dismissFade = with(density) { 500.dp.toPx() }
    val controlsFade = with(density) { 150.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = max(0.2f, 1f - abs(dismissOffset) / dismissFade))),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val containerWidth = constraints.maxWidth.toFloat()
            val containerHeight = constraints.maxHeight.toFloat()
            val bitmap = image
            if (bitmap == null) {
                CircularProgressIndicator(color = AeroTheme.accentCyan)
            } else {
                // Proporzioni costanti fra miniatura e originale: i limiti dello spostamento non cambiano.
                val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                fun clamp(proposed: Offset, atScale: Float): Offset {
                    if (containerWidth <= 0f || containerHeight <= 0f || aspect <= 0f) return Offset.Zero
                    val fitWidth = min(containerWidth, containerHeight * aspect)
                    val fitHeight = fitWidth / aspect
                    val maxX = max(0f, (fitWidth * atScale - containerWidth) / 2f)
                    val maxY = max(0f, (fitHeight * atScale - containerHeight) / 2f)
                    return Offset(proposed.x.coerceIn(-maxX, maxX), proposed.y.coerceIn(-maxY, maxY))
                }
                fun animateTo(targetScale: Float, targetOffset: Offset) {
                    val startScale = scale
                    val startOffset = offset
                    scope.launch {
                        animate(0f, 1f, animationSpec = spring(stiffness = 500f)) { t, _ ->
                            scale = startScale + (targetScale - startScale) * t
                            offset = startOffset + (targetOffset - startOffset) * t
                        }
                    }
                }
                val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = stringResource(R.string.chat_photo),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(aspect, containerWidth, containerHeight) {
                            detectTapGestures(
                                onDoubleTap = { tap ->
                                    Haptics.impact(Haptics.ImpactStyle.light)
                                    if (scale > 1f) {
                                        animateTo(1f, Offset.Zero)
                                    } else {
                                        // Ingrandisce attorno al punto toccato.
                                        val center = Offset(containerWidth / 2f, containerHeight / 2f)
                                        animateTo(doubleTapScale, clamp((center - tap) * (doubleTapScale - 1f), doubleTapScale))
                                    }
                                },
                            )
                        }
                        .pointerInput(aspect, containerWidth, containerHeight) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val velocity = VelocityTracker()
                                var totalPan = Offset.Zero
                                var pastSlop = false
                                var dismissing = false
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { it.isConsumed }) break
                                    val pressed = event.changes.count { it.pressed }
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    event.changes.firstOrNull()?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                                    if (!pastSlop) {
                                        totalPan += pan
                                        pastSlop = pressed > 1 || totalPan.getDistance() > viewConfiguration.touchSlop
                                        if (!pastSlop) continue
                                        // Non ingrandita, un dito, verso il basso: trascinamento per chiudere.
                                        dismissing = pressed == 1 && scale <= 1f && totalPan.y > abs(totalPan.x)
                                    }
                                    if (dismissing) {
                                        dismissOffset = max(0f, dismissOffset + pan.y)
                                    } else if (pressed > 1 || scale > 1f) {
                                        val newScale = (scale * zoom).coerceIn(1f, maxScale)
                                        scale = newScale
                                        offset = clamp(offset + pan, newScale)
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } while (event.changes.any { it.pressed })

                                if (dismissing) {
                                    val speed = velocity.calculateVelocity().y
                                    if (dismissOffset > dismissThreshold || speed > 2_000f) {
                                        Haptics.impact(Haptics.ImpactStyle.light)
                                        onDismiss()
                                    } else {
                                        val start = dismissOffset
                                        scope.launch {
                                            animate(start, 0f, animationSpec = spring(stiffness = 500f)) { v, _ -> dismissOffset = v }
                                        }
                                    }
                                } else if (scale <= 1.01f && (scale != 1f || offset != Offset.Zero)) {
                                    animateTo(1f, Offset.Zero)
                                }
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y + dismissOffset
                        },
                )
            }
        }

        val controlsAlpha = if (dismissOffset == 0f) 1f else max(0f, 1f - abs(dismissOffset) / controlsFade)
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .alpha(controlsAlpha),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    Haptics.tap()
                    onDismiss()
                },
                modifier = Modifier.background(Color.White.copy(alpha = 0.15f), CircleShape),
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chat_call_close), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    if (isSaving) return@IconButton
                    Haptics.tap()
                    isSaving = true
                    scope.launch {
                        try {
                            onSave()?.let { feedback = it }
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.background(Color.White.copy(alpha = 0.15f), CircleShape),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.chat_save_to_photos), tint = Color.White)
                }
            }
        }

        InfoBanner(
            message = feedback,
            onExpire = { feedback = null },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

/** Decodifica bloccante a piena risoluzione (da chiamare su `Dispatchers.IO`); niente cache, solo miniature in `ChatImageCache`. */
private fun decodeFullSize(store: MediaStore, relativePath: String): Bitmap? {
    val data = try {
        store.read(relativePath = relativePath)
    } catch (_: Exception) {
        return null
    }
    return try {
        ImageProcessor.downscale(data, fullSizeMaxPixels)
    } catch (_: OutOfMemoryError) {
        // Non e' una Exception: senza questo catch farebbe crashare la coroutine (e l'app).
        null
    } catch (_: Exception) {
        try {
            ImageProcessor.downscale(WebPEncoder.decode(data), fullSizeMaxPixels)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}
