package com.stefanobrandi.aerochat.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.stefanobrandi.aerochat.ui.AeroTheme
import kotlin.math.max

/** Forma d'onda stile WhatsApp dai 32 campioni `UInt8` dell'header del messaggio. */
@Composable
fun WaveformView(
    samples: ByteArray,
    modifier: Modifier = Modifier,
    progress: Double = 0.0,
    tint: Color = AeroTheme.accentCyan,
) {
    val values = remember(samples) {
        val raw = if (samples.isEmpty()) ByteArray(32) { 40 } else samples
        raw.map { max(0.12f, (it.toInt() and 0xFF) / 255f) }
    }
    Canvas(modifier = modifier.clearAndSetSemantics {}) {
        val count = values.size
        val spacing = 2.dp.toPx()
        val barWidth = max(1.5.dp.toPx(), (size.width - spacing * (count - 1)) / count)
        val minHeight = 3.dp.toPx()
        values.forEachIndexed { index, value ->
            val played = index.toDouble() / max(1, count).toDouble() < progress
            val height = max(minHeight, size.height * value)
            drawRoundRect(
                color = if (played) tint else tint.copy(alpha = tint.alpha * 0.35f),
                topLeft = Offset(index * (barWidth + spacing), (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
