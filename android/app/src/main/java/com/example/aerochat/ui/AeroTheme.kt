package com.example.aerochat.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette OLED dark-first della cabina: nero puro, superfici grafite, ciano elettrico e smeraldo. */
object AeroTheme {

    val background = Color.hex(0x000000)
    val surface = Color.hex(0x121316)
    val accentCyan = Color.hex(0x00E5FF)
    val accentEmerald = Color.hex(0x10B981)
    val textPrimary = Color.White
    val textSecondary = Color.White.copy(alpha = 0.6f)

    /**
     * Titoli "rounded" (SF Pro Rounded su iOS). Android non ha un equivalente di sistema: si usa la
     * famiglia di default con lo stesso peso/dimensione. [size] in sp.
     */
    fun title(size: Float = 28f, weight: FontWeight = FontWeight.Bold): TextStyle =
        TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, fontWeight = weight)

    fun monospaced(size: Float = 13f): TextStyle =
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp, fontWeight = FontWeight.Normal)

    val cornerRadius: Dp = 20.dp

    /** Forma continua standard delle card. */
    val cardShape = RoundedCornerShape(cornerRadius)

    // Equivalenti dei text style di sistema SwiftUI usati nelle schermate.
    val body = TextStyle(fontSize = 17.sp)
    val footnote = TextStyle(fontSize = 13.sp)
    val caption = TextStyle(fontSize = 12.sp)
    val captionSemibold = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val title2: TextUnit = 22.sp
}

/** Colore da valore esadecimale `0xRRGGBB` (Swift `Color(hex:opacity:)`). */
fun Color.Companion.hex(hex: Long, opacity: Float = 1f): Color = Color(
    red = ((hex shr 16) and 0xFF).toInt() / 255f,
    green = ((hex shr 8) and 0xFF).toInt() / 255f,
    blue = (hex and 0xFF).toInt() / 255f,
    alpha = opacity,
)

private val AeroColorScheme = darkColorScheme(
    primary = AeroTheme.accentCyan,
    onPrimary = Color.Black,
    primaryContainer = AeroTheme.surface,
    onPrimaryContainer = AeroTheme.textPrimary,
    secondary = AeroTheme.accentEmerald,
    onSecondary = Color.Black,
    secondaryContainer = AeroTheme.accentCyan.copy(alpha = 0.18f),
    onSecondaryContainer = AeroTheme.accentCyan,
    tertiary = AeroTheme.accentEmerald,
    background = AeroTheme.background,
    onBackground = AeroTheme.textPrimary,
    surface = AeroTheme.background,
    onSurface = AeroTheme.textPrimary,
    surfaceVariant = AeroTheme.surface,
    onSurfaceVariant = AeroTheme.textSecondary,
    surfaceContainer = AeroTheme.surface,
    surfaceContainerHigh = AeroTheme.surface,
    surfaceContainerHighest = Color.hex(0x1C1D21),
    surfaceContainerLow = AeroTheme.surface,
    surfaceContainerLowest = AeroTheme.background,
    surfaceBright = Color.hex(0x1C1D21),
    surfaceDim = AeroTheme.background,
    outline = Color.White.copy(alpha = 0.2f),
    outlineVariant = Color.White.copy(alpha = 0.12f),
    error = Color.hex(0xFF453A),
    onError = Color.Black,
)

private val AeroShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(AeroTheme.cornerRadius),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Tema Material3 scuro dell'app (`.preferredColorScheme(.dark)` + `.tint(AeroTheme.accentCyan)`). */
@Composable
fun AeroChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AeroColorScheme,
        typography = Typography(),
        shapes = AeroShapes,
        content = content,
    )
}
