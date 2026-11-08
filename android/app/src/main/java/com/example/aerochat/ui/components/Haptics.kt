package com.example.aerochat.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Feedback aptico centralizzato (tocchi sui pulsanti, ricezione messaggi, pairing riuscito, errori).
 * Va inizializzato una volta con [install] (lo fa `MainActivity`); prima di allora le chiamate sono no-op.
 */
object Haptics {

    /** Equivalente di `UIImpactFeedbackGenerator.FeedbackStyle`. */
    enum class ImpactStyle { light, medium, heavy, soft, rigid }

    @Volatile
    private var vibrator: Vibrator? = null

    fun install(context: Context) {
        if (vibrator != null) return
        val app = context.applicationContext
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** Vibrazione leggera e morbida per il tocco di un pulsante. */
    fun tap() {
        primitive(primitiveTick, scale = 0.6f) ?: predefined(VibrationEffect.EFFECT_TICK)
    }

    fun impact(style: ImpactStyle = ImpactStyle.light) {
        when (style) {
            ImpactStyle.soft -> primitive(primitiveTick, 0.5f) ?: predefined(VibrationEffect.EFFECT_TICK)
            ImpactStyle.light -> primitive(primitiveClick, 0.5f) ?: predefined(VibrationEffect.EFFECT_TICK)
            ImpactStyle.medium -> primitive(primitiveClick, 0.8f) ?: predefined(VibrationEffect.EFFECT_CLICK)
            ImpactStyle.rigid -> primitive(primitiveClick, 1f) ?: predefined(VibrationEffect.EFFECT_CLICK)
            ImpactStyle.heavy -> predefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        }
    }

    fun success() {
        predefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
    }

    fun warning() {
        predefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    fun error() {
        waveform(longArrayOf(0, 40, 60, 40, 60, 60), intArrayOf(0, 200, 0, 200, 0, 255))
    }

    // Costanti di VibrationEffect.Composition (API 30), replicate per non legare la classe all'API 30.
    private const val primitiveClick = 1
    private const val primitiveTick = 7

    private fun primitive(id: Int, scale: Float): Unit? {
        val vibrator = vibrator ?: return Unit
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!vibrator.areAllPrimitivesSupported(id)) return null
        return runCatching {
            vibrator.vibrate(VibrationEffect.startComposition().addPrimitive(id, scale).compose())
        }.getOrNull()
    }

    private fun predefined(effect: Int) {
        val vibrator = vibrator ?: return
        runCatching { vibrator.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    private fun waveform(timings: LongArray, amplitudes: IntArray) {
        val vibrator = vibrator ?: return
        runCatching {
            if (vibrator.hasAmplitudeControl()) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            }
        }
    }
}

/**
 * Vibrazione morbida al tocco (nel momento in cui il dito tocca) per i pulsanti Material3
 * (`Button`, `IconButton`, `FilledTonalButton`...) e le voci di menu: Swift `.hapticOnTap()`.
 * Non consuma l'evento, quindi il click del pulsante funziona normalmente.
 */
fun Modifier.hapticOnTap(enabled: Boolean = true): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            Haptics.tap()
        }
    }

/**
 * Stile dei pulsanti dell'app (Swift `.buttonStyle(.haptic)` / `.hapticCompact`): aspetto "plain" con
 * tinta d'accento (o rossa se [destructive]), leggera compressione alla pressione e vibrazione morbida
 * quando il dito tocca il pulsante. [minimumTapSize] 44 dp (0 per le celle fitte delle griglie).
 *
 * Il contenuto riceve `LocalContentColor` = tinta, quindi `Text`/`Icon` senza colore esplicito la usano.
 */
@Composable
fun HapticButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    tint: Color = if (destructive) Color.Red else MaterialTheme.colorScheme.primary,
    minimumTapSize: Dp = 44.dp,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) 0.6f else 1f, tween(150), label = "hapticAlpha")
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(150), label = "hapticScale")
    LaunchedEffect(pressed) { if (pressed) Haptics.tap() }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minimumTapSize, minHeight = minimumTapSize)
            .scale(scale)
            .alpha(if (enabled) alpha else 0.4f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) { content() }
    }
}
