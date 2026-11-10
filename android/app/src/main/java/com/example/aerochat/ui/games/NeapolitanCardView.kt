@file:Suppress("DEPRECATION") // Path.quadraticBezierTo: stesso disegno su tutte le versioni di Compose

package com.example.aerochat.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.aerochat.R
import com.example.aerochat.core.games.NeapolitanCards
import com.example.aerochat.ui.AeroColors
import com.example.aerochat.ui.hex
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Carta napoletana disegnata in codice (nessuna immagine): fondo avorio, valore negli angoli, simbolo del
 * seme al centro; le figure (F, C, R) hanno un emblema piu' grande dentro una cornice. Stesso disegno di iOS
 * (`NeapolitanCardView.swift`).
 *
 * @param card id 0..39; `null` = dorso.
 */
@Composable
fun NeapolitanCardView(
    card: Int?,
    width: Dp = 60.dp,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    dimmed: Boolean = false,
) {
    val height = width * cardAspectRatio
    val shape = RoundedCornerShape(width * 0.12f)
    val description = cardName(card)
    Box(
        modifier
            .size(width, height)
            .shadow(if (highlighted) 8.dp else 2.dp, shape, ambientColor = if (highlighted) AeroColors.Dark.accentCyan else Color.Black,
                spotColor = if (highlighted) AeroColors.Dark.accentCyan else Color.Black)
            .clip(shape)
            .border(
                width = if (highlighted) max(2f, width.value * 0.05f).dp else 0.8.dp,
                color = if (highlighted) AeroColors.Dark.accentCyan else Color.Black.copy(alpha = 0.35f),
                shape = shape,
            )
            .alpha(if (dimmed) 0.45f else 1f)
            .semantics { contentDescription = description },
    ) {
        if (card != null && NeapolitanCards.isValid(card)) CardFace(card, width) else CardBack(Modifier.fillMaxSize())
    }
}

/** Rapporto altezza / larghezza delle carte. */
const val cardAspectRatio = 1.6f

private val ivory = Color.hex(0xF6F0DC)

/** Colori dei semi: denari oro, coppe rosso/arancio, spade blu, bastoni verde/marrone. */
fun suitColor(suit: Int): Color = when (suit) {
    0 -> Color.hex(0xB8860B)
    1 -> Color.hex(0xC0392B)
    2 -> Color.hex(0x1F4E9C)
    else -> Color.hex(0x2E7D32)
}

@Composable
private fun CardFace(card: Int, width: Dp) {
    val suit = NeapolitanCards.suit(card)
    val color = suitColor(suit)
    val label = NeapolitanCards.cornerLabel(card)
    val density = LocalDensity.current
    val cornerStyle = TextStyle(
        fontSize = with(density) { (width * 0.24f).toSp() },
        fontWeight = FontWeight.Bold,
        color = color,
    )
    Box(Modifier.fillMaxSize().background(ivory)) {
        if (NeapolitanCards.isFigure(card)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(width * 0.16f)
                    .border(max(1f, width.value * 0.03f).dp, color.copy(alpha = 0.75f), RoundedCornerShape(width * 0.06f)),
            )
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                SuitSymbol(suit, Modifier.size(width * 0.5f, width * cardAspectRatio * 0.42f))
                Text(
                    label,
                    style = TextStyle(
                        fontSize = with(density) { (width * 0.3f).toSp() },
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Serif,
                        color = color,
                    ),
                )
            }
        } else {
            SuitSymbol(suit, Modifier.align(Alignment.Center).size(width * 0.48f, width * cardAspectRatio * 0.42f))
        }
        Text(
            label, style = cornerStyle, maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).padding(horizontal = width * 0.07f, vertical = width * 0.03f),
        )
        Text(
            label, style = cornerStyle, maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = width * 0.07f, vertical = width * 0.03f)
                .rotate(180f),
        )
    }
}

/** Simbolo del seme disegnato con `Canvas` nel rettangolo disponibile. */
@Composable
fun SuitSymbol(suit: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = min(size.width, size.height / 1.3f)
        val rect = Rect(
            offset = Offset((size.width - side) / 2, (size.height - side * 1.3f) / 2),
            size = Size(side, side * 1.3f),
        )
        when (suit) {
            0 -> drawCoin(rect)
            1 -> drawCup(rect)
            2 -> drawSword(rect)
            else -> drawClub(rect)
        }
    }
}

private fun Rect.inset(d: Float): Rect = Rect(left + d, top + d, right - d, bottom - d)

private fun DrawScope.fillOval(rect: Rect, color: Color) = drawOval(color, rect.topLeft, rect.size)

private fun DrawScope.strokeOval(rect: Rect, color: Color, width: Float) =
    drawOval(color, rect.topLeft, rect.size, style = Stroke(width))

/** Denari: moneta d'oro a cerchi concentrici. */
private fun DrawScope.drawCoin(rect: Rect) {
    val side = rect.width
    val circle = Rect(Offset(rect.center.x - side / 2, rect.center.y - side / 2), Size(side, side))
    val gold = Color.hex(0xE0A82E)
    val dark = Color.hex(0x8A5A00)
    fillOval(circle, gold)
    strokeOval(circle.inset(side * 0.03f), dark, side * 0.05f)
    strokeOval(circle.inset(side * 0.2f), dark, side * 0.04f)
    fillOval(circle.inset(side * 0.36f), Color.hex(0xC0392B))
    for (index in 0 until 8) {
        val angle = index * Math.PI / 4
        val radius = side * 0.39f
        val center = Offset(circle.center.x + cos(angle).toFloat() * radius, circle.center.y + sin(angle).toFloat() * radius)
        drawCircle(dark, radius = side * 0.035f, center = center)
    }
}

/** Coppe: calice rosso con bordo arancio, stelo e base. */
private fun DrawScope.drawCup(rect: Rect) {
    val w = rect.width
    val h = rect.height
    val red = Color.hex(0xC0392B)
    val orange = Color.hex(0xF39C12)
    val top = rect.top + h * 0.1f
    val bowlBottom = rect.top + h * 0.55f
    val bowl = Path().apply {
        moveTo(rect.left + w * 0.08f, top)
        lineTo(rect.right - w * 0.08f, top)
        quadraticBezierTo(rect.right - w * 0.05f, bowlBottom, rect.center.x, bowlBottom)
        quadraticBezierTo(rect.left + w * 0.05f, bowlBottom, rect.left + w * 0.08f, top)
        close()
    }
    drawPath(bowl, red)
    fillOval(Rect(Offset(rect.left + w * 0.08f, top - h * 0.04f), Size(w * 0.84f, h * 0.08f)), orange)
    drawRoundRect(
        red, Offset(rect.center.x - w * 0.06f, bowlBottom - h * 0.02f), Size(w * 0.12f, h * 0.25f),
        CornerRadius(w * 0.03f),
    )
    fillOval(Rect(Offset(rect.center.x - w * 0.12f, bowlBottom + h * 0.08f), Size(w * 0.24f, h * 0.07f)), orange)
    val base = Rect(Offset(rect.center.x - w * 0.3f, rect.top + h * 0.78f), Size(w * 0.6f, h * 0.12f))
    fillOval(base, red)
    strokeOval(base, orange, w * 0.03f)
}

/** Spade: spada blu verticale con elsa e pomolo. */
private fun DrawScope.drawSword(rect: Rect) {
    val w = rect.width
    val h = rect.height
    val blue = Color.hex(0x1F4E9C)
    val steel = Color.hex(0x8FB3E8)
    val guardY = rect.top + h * 0.68f
    val blade = Path().apply {
        moveTo(rect.center.x, rect.top + h * 0.02f)
        lineTo(rect.center.x + w * 0.11f, rect.top + h * 0.14f)
        lineTo(rect.center.x + w * 0.09f, guardY)
        lineTo(rect.center.x - w * 0.09f, guardY)
        lineTo(rect.center.x - w * 0.11f, rect.top + h * 0.14f)
        close()
    }
    drawPath(blade, steel)
    drawPath(blade, blue, style = Stroke(w * 0.035f))
    drawLine(blue, Offset(rect.center.x, rect.top + h * 0.08f), Offset(rect.center.x, guardY), strokeWidth = w * 0.025f)
    drawRoundRect(blue, Offset(rect.center.x - w * 0.38f, guardY - h * 0.01f), Size(w * 0.76f, h * 0.06f), CornerRadius(h * 0.03f))
    drawRoundRect(
        Color.hex(0x6D4C41), Offset(rect.center.x - w * 0.06f, guardY + h * 0.05f), Size(w * 0.12f, h * 0.17f),
        CornerRadius(w * 0.03f),
    )
    fillOval(Rect(Offset(rect.center.x - w * 0.1f, guardY + h * 0.21f), Size(w * 0.2f, w * 0.2f)), blue)
}

/** Bastoni: randello verde nodoso con contorno marrone. */
private fun DrawScope.drawClub(rect: Rect) {
    val w = rect.width
    val h = rect.height
    val green = Color.hex(0x2E7D32)
    val brown = Color.hex(0x6D4C41)
    val club = Path().apply {
        moveTo(rect.center.x - w * 0.08f, rect.bottom - h * 0.04f)
        lineTo(rect.center.x - w * 0.2f, rect.top + h * 0.14f)
        quadraticBezierTo(rect.center.x, rect.top - h * 0.04f, rect.center.x + w * 0.2f, rect.top + h * 0.14f)
        lineTo(rect.center.x + w * 0.08f, rect.bottom - h * 0.04f)
        quadraticBezierTo(rect.center.x, rect.bottom + h * 0.02f, rect.center.x - w * 0.08f, rect.bottom - h * 0.04f)
        close()
    }
    drawPath(club, green)
    drawPath(club, brown, style = Stroke(w * 0.04f))
    // Nodi sporgenti alternati.
    val knots = listOf(-0.2f to 0.3f, 0.19f to 0.45f, -0.16f to 0.62f, 0.14f to 0.76f)
    for ((dx, dy) in knots) {
        val knot = Rect(Offset(rect.center.x + w * dx - w * 0.07f, rect.top + h * dy - w * 0.07f), Size(w * 0.14f, w * 0.14f))
        fillOval(knot, green)
        strokeOval(knot, brown, w * 0.03f)
    }
}

/** Dorso: blu notte con reticolo rosso scuro e filetto ciano AeroChat. */
@Composable
fun CardBack(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val navy = Color.hex(0x0F1B3D)
        drawRect(navy)
        val step = max(6f, size.width / 6)
        val lineWidth = max(1f, size.width * 0.025f)
        clipRect {
            var x = -size.height
            while (x < size.width + size.height) {
                drawLine(Color.hex(0x8E1B2E), Offset(x, 0f), Offset(x + size.height, size.height), lineWidth)
                drawLine(Color.hex(0x8E1B2E), Offset(x + size.height, 0f), Offset(x, size.height), lineWidth)
                x += step
            }
        }
        val inset = size.width * 0.09f
        val accentWidth = max(1f, size.width * 0.03f)
        drawRoundRect(
            AeroColors.Dark.accentCyan, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
            CornerRadius(size.width * 0.07f), style = Stroke(accentWidth),
        )
        val emblem = size.width * 0.36f
        drawCircle(navy, radius = emblem / 2, center = center)
        drawCircle(AeroColors.Dark.accentCyan, radius = emblem / 2, center = center, style = Stroke(accentWidth))
    }
}

/** Nome completo per TalkBack: "Asso di Spade", "7 di Denari", "Settebello"; `null` = "Carta coperta". */
@Composable
fun cardName(card: Int?): String {
    if (card == null || !NeapolitanCards.isValid(card)) return stringResource(R.string.games_card_back)
    if (card == NeapolitanCards.settebello) return stringResource(R.string.games_settebello)
    val suit = suitName(NeapolitanCards.suit(card))
    return when (val rank = NeapolitanCards.rank(card)) {
        1 -> stringResource(R.string.games_card_ace, suit)
        8 -> stringResource(R.string.games_card_jack, suit)
        9 -> stringResource(R.string.games_card_knight, suit)
        10 -> stringResource(R.string.games_card_king, suit)
        else -> stringResource(R.string.games_card_number, rank, suit)
    }
}

@Composable
fun suitName(suit: Int): String = when (suit) {
    0 -> stringResource(R.string.games_suit_denari)
    1 -> stringResource(R.string.games_suit_coppe)
    2 -> stringResource(R.string.games_suit_spade)
    else -> stringResource(R.string.games_suit_bastoni)
}

/** Nome breve localizzato per le descrizioni delle mosse: "7 Coppe", "10 Bastoni" (semi napoletani, non francesi). */
@Composable
fun cardShortNamer(): (Int) -> String {
    val names = (0 until NeapolitanCards.suitCount).map { suitName(it) }
    return { card -> "${NeapolitanCards.rank(card)} ${names[NeapolitanCards.suit(card)]}" }
}
