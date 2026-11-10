package com.example.aerochat.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Palette della cabina. Scuro: OLED nero puro, superfici grafite, ciano elettrico e smeraldo.
 * Chiaro: grigio perla, superfici bianche, ciano e smeraldo piu' scuri per il contrasto su bianco.
 */
@Immutable
data class AeroColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    /** Superficie rialzata (menu, campi, chip). */
    val elevatedSurface: Color,
    val accentCyan: Color,
    val accentEmerald: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** Colore "inchiostro" per overlay/bordi sottili: bianco in scuro, nero in chiaro. */
    val onBackground: Color,
) {
    companion object {
        val Dark = AeroColors(
            isDark = true,
            background = Color.hex(0x000000),
            surface = Color.hex(0x121316),
            elevatedSurface = Color.hex(0x1C1D21),
            accentCyan = Color.hex(0x00E5FF),
            accentEmerald = Color.hex(0x10B981),
            textPrimary = Color.White,
            textSecondary = Color.White.copy(alpha = 0.6f),
            onBackground = Color.White,
        )
        val Light = AeroColors(
            isDark = false,
            background = Color.hex(0xF2F3F7),
            surface = Color.hex(0xFFFFFF),
            elevatedSurface = Color.hex(0xE9EBF0),
            accentCyan = Color.hex(0x0091A8),
            accentEmerald = Color.hex(0x059669),
            textPrimary = Color.hex(0x0B0C0F),
            textSecondary = Color.hex(0x5F6573),
            onBackground = Color.Black,
        )
    }
}

/** Palette corrente (segue il tema di sistema, vedi [AeroChatTheme]). */
val LocalAeroColors = staticCompositionLocalOf { AeroColors.Dark }

/** Token di stile dell'app; i colori leggono [LocalAeroColors] e quindi seguono chiaro/scuro. */
object AeroTheme {

    val colors: AeroColors @Composable @ReadOnlyComposable get() = LocalAeroColors.current
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalAeroColors.current.isDark
    val background: Color @Composable @ReadOnlyComposable get() = LocalAeroColors.current.background
    val surface: Color @Composable @ReadOnlyComposable get() = LocalAeroColors.current.surface
    val elevatedSurface: Color @Composable @ReadOnlyComposable get() = LocalAeroColors.current.elevatedSurface
    val accentCyan: Color @Composable @ReadOnlyComposable get() = LocalAeroColors.current.accentCyan
    val accentEmerald: Color @Composable @ReadOnlyComposable get() = LocalAeroColors.current.accentEmerald
    val textPrimary: Color @Composable @ReadOnlyComposable get() = LocalAeroColors.current.textPrimary
    val textSecondary: Color @Composable @ReadOnlyComposable get() = LocalAeroColors.current.textSecondary
    /** Bianco in scuro, nero in chiaro: sostituisce `Color.White.copy(alpha = ...)` per veli e bordi. */
    val onBackground: Color @Composable @ReadOnlyComposable get() = LocalAeroColors.current.onBackground

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

private fun aeroColorScheme(c: AeroColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.accentCyan,
        onPrimary = Color.Black,
        primaryContainer = c.surface,
        onPrimaryContainer = c.textPrimary,
        secondary = c.accentEmerald,
        onSecondary = Color.Black,
        secondaryContainer = c.accentCyan.copy(alpha = 0.18f),
        onSecondaryContainer = c.accentCyan,
        tertiary = c.accentEmerald,
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.background,
        onSurface = c.textPrimary,
        surfaceVariant = c.surface,
        onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.elevatedSurface,
        surfaceContainerLow = c.surface,
        surfaceContainerLowest = c.background,
        surfaceBright = c.elevatedSurface,
        surfaceDim = c.background,
        outline = Color.White.copy(alpha = 0.2f),
        outlineVariant = Color.White.copy(alpha = 0.12f),
        error = Color.hex(0xFF453A),
        onError = Color.Black,
    )
} else {
    // Testo nero sugli accenti anche in chiaro: su #0091A8/#059669 il nero ha contrasto ~5.5:1, il bianco ~3.7:1.
    lightColorScheme(
        primary = c.accentCyan,
        onPrimary = Color.Black,
        primaryContainer = c.surface,
        onPrimaryContainer = c.textPrimary,
        secondary = c.accentEmerald,
        onSecondary = Color.Black,
        secondaryContainer = c.accentCyan.copy(alpha = 0.14f),
        onSecondaryContainer = c.accentCyan,
        tertiary = c.accentEmerald,
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.background,
        onSurface = c.textPrimary,
        surfaceVariant = c.surface,
        onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.elevatedSurface,
        surfaceContainerLow = c.surface,
        surfaceContainerLowest = c.surface,
        surfaceBright = c.surface,
        surfaceDim = c.elevatedSurface,
        outline = Color.Black.copy(alpha = 0.2f),
        outlineVariant = Color.Black.copy(alpha = 0.1f),
        error = Color.hex(0xD70015),
        onError = Color.White,
    )
}

private val AeroShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(AeroTheme.cornerRadius),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Tema Material3 dell'app: segue il tema di sistema (nessun interruttore in-app). */
@Composable
fun AeroChatTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) AeroColors.Dark else AeroColors.Light
    CompositionLocalProvider(LocalAeroColors provides colors) {
        MaterialTheme(
            colorScheme = aeroColorScheme(colors),
            typography = Typography(),
            shapes = AeroShapes,
            content = content,
        )
    }
}

/**
 * Forza la palette scura per le schermate a tutto schermo dal design scuro (visore immagini, chiamata):
 * anche le icone delle barre di sistema diventano chiare finche' la schermata resta visibile.
 */
@Composable
fun AeroForcedDark(content: @Composable () -> Unit) {
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    if (!view.isInEditMode && !systemDark) {
        DisposableEffect(view) {
            val window = (view.context.findActivity())?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            val previousStatus = controller?.isAppearanceLightStatusBars
            val previousNavigation = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            onDispose {
                previousStatus?.let { controller.isAppearanceLightStatusBars = it }
                previousNavigation?.let { controller.isAppearanceLightNavigationBars = it }
            }
        }
    }
    AeroChatTheme(darkTheme = true, content = content)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
