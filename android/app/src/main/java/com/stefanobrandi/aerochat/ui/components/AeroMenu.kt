package com.stefanobrandi.aerochat.ui.components

import android.graphics.Rect as AndroidRect
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.stefanobrandi.aerochat.ui.AeroTheme

/**
 * Voce di [AeroDropdownMenu]. [dividerBefore] traccia un separatore tra questa voce e la precedente
 * (nell'ordine di dichiarazione): resta tra la stessa coppia anche quando il menu si apre verso l'alto.
 * [hapticOnTap] aggiunge la vibrazione al tocco ([Modifier.hapticOnTap]); [iconTint] colora la sola icona.
 */
data class AeroMenuItem(
    val text: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val dividerBefore: Boolean = false,
    val hapticOnTap: Boolean = false,
    val iconTint: Color? = null,
    val onClick: () -> Unit,
)

/**
 * Intestazione facoltativa di [AeroDropdownMenu] (titolo in grassetto + testo esplicativo), per le conferme
 * ancorate all'elemento interessato (come `confirmationDialog` su iOS 26, che parte dalla riga / dalla bolla).
 */
data class AeroMenuHeader(val title: String, val message: String? = null)

/** Distanza tra il bordo del pulsante e il menu. */
private val MenuGap = 4.dp

/** Margine minimo dai bordi dell'area visibile (barre di sistema e tastiera escluse). */
private val EdgeMargin = 8.dp

private val MenuShape = RoundedCornerShape(14.dp)

/** Spazio trasparente attorno alla superficie perche' l'ombra non venga tagliata dal bordo della finestra popup. */
private val ShadowPad = 4.dp

/** Altezza di una riga (come `DropdownMenuItem`) e padding verticale del contenitore: servono a stimare l'altezza. */
private val RowHeight = 48.dp
private val ContainerPadding = 8.dp
/** Stima dell'altezza dell'intestazione (titolo + 2 righe di testo + separatore). */
private val HeaderHeight = 88.dp
private val HeaderMaxWidth = 280.dp

/** Stato non osservato: legge posizione del pulsante e direzione senza ricomporre a ogni scroll. */
private class AeroMenuAnchor {
    var coordinates: LayoutCoordinates? = null
    var opensUp: Boolean = false
    var alignEnd: Boolean = false
}

/**
 * Menu a comparsa ancorato al contenitore in cui e' dichiarato (va messo nello stesso `Box` del pulsante,
 * al posto del vecchio `DropdownMenu`). Come `UIMenu` su iOS la prima voce sta sempre vicino al pulsante:
 * aprendosi verso il basso l'ordine e' quello di dichiarazione, verso l'alto (pulsante in basso, es. il
 * composer) e' invertito.
 *
 * Il `DropdownMenu` Material3 confrontava la posizione del pulsante (coordinate di finestra, che in
 * edge-to-edge partono dal bordo dello schermo) con l'area visibile senza barre di sistema, e il menu
 * risultava spostato verso l'alto. Qui tutto e' calcolato nelle coordinate della finestra.
 */
@Composable
fun BoxScope.AeroDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<AeroMenuItem>,
    header: AeroMenuHeader? = null,
) {
    val anchor = remember { AeroMenuAnchor() }
    // Legge i limiti del contenitore (= il pulsante) senza stato osservato: onPlaced non scatta a ogni frame di scroll.
    Spacer(Modifier.matchParentSize().onPlaced { anchor.coordinates = it })

    val transition = remember { MutableTransitionState(false) }
    transition.targetState = expanded
    if (!transition.currentState && !transition.targetState) return

    val view = LocalView.current
    val density = LocalDensity.current
    val gapPx = with(density) { MenuGap.roundToPx() }
    val marginPx = with(density) { EdgeMargin.roundToPx() }
    val padPx = with(density) { ShadowPad.roundToPx() }
    val frame = visibleFrameInWindow(view)
    val bounds = anchor.coordinates?.takeIf { it.isAttached }?.boundsInWindow()

    // Direzione decisa prima di comporre le voci (e congelata durante l'animazione di chiusura).
    if (expanded && bounds != null) {
        val dividers = items.count { it.dividerBefore }
        val estimated = with(density) {
            (
                RowHeight * items.size + ContainerPadding * 2 + (ContainerPadding * 2 + 1.dp) * dividers +
                    (if (header != null) HeaderHeight else 0.dp)
                ).toPx()
        }
        val spaceBelow = frame.bottom - bounds.bottom - gapPx - marginPx
        val spaceAbove = bounds.top - frame.top - gapPx - marginPx
        anchor.opensUp = spaceBelow < estimated && spaceAbove > spaceBelow
        anchor.alignEnd = bounds.center.x > (frame.left + frame.right) / 2f
    }
    val opensUp = anchor.opensUp
    val alignEnd = anchor.alignEnd
    // Lo spazio disponibile dalla parte in cui si apre: oltre, le voci scorrono.
    val maxHeightPx: Float = when {
        bounds == null -> (frame.height() - 2 * marginPx).toFloat()
        opensUp -> bounds.top - frame.top - gapPx - marginPx
        else -> frame.bottom - bounds.bottom - gapPx - marginPx
    }
    val maxHeight = with(density) { maxHeightPx.coerceAtLeast(RowHeight.toPx()).toDp() }

    // Verso l'alto la prima voce finisce in fondo, vicino al pulsante; il separatore resta tra la stessa coppia.
    // Con un'intestazione (conferma) l'ordine resta quello di lettura: titolo, conferma, "Annulla".
    val rows: List<Pair<AeroMenuItem, Boolean>> = if (opensUp && header == null) {
        items.indices.reversed().map { i -> items[i] to (items.getOrNull(i + 1)?.dividerBefore == true) }
    } else {
        items.mapIndexed { i, item -> item to (i > 0 && item.dividerBefore) }
    }

    val positionProvider = remember(view, opensUp, alignEnd, gapPx, marginPx, padPx) {
        AeroMenuPositionProvider(view, opensUp, alignEnd, gapPx, marginPx, padPx)
    }
    val origin = TransformOrigin(if (alignEnd) 1f else 0f, if (opensUp) 1f else 0f)

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        // Niente clipping del window manager: la posizione e' gia' contenuta nell'area visibile.
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        AnimatedVisibility(
            visibleState = transition,
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.85f, transformOrigin = origin),
            exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.92f, transformOrigin = origin),
        ) {
            Surface(
                shape = MenuShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.padding(ShadowPad),
            ) {
                Column(
                    Modifier
                        .widthIn(min = 200.dp)
                        .width(IntrinsicSize.Max)
                        .heightIn(max = maxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = ContainerPadding),
                ) {
                    // L'intestazione resta sempre in cima, qualunque sia la direzione di apertura.
                    if (header != null) AeroMenuHeaderView(header)
                    for ((item, divider) in rows) {
                        if (divider) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = ContainerPadding),
                                color = AeroTheme.onBackground.copy(alpha = 0.1f),
                            )
                        }
                        AeroMenuRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun AeroMenuHeaderView(header: AeroMenuHeader) {
    Column(
        Modifier
            .widthIn(max = HeaderMaxWidth)
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { heading() },
    ) {
        Text(header.title, style = AeroTheme.headline, color = AeroTheme.textPrimary)
        header.message?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = AeroTheme.footnote, color = AeroTheme.textSecondary)
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(top = ContainerPadding, bottom = 4.dp),
        color = AeroTheme.onBackground.copy(alpha = 0.1f),
    )
}

/**
 * Conferma di un'azione distruttiva ancorata all'elemento (riga della lista, bolla): titolo, testo, voce
 * rossa di conferma e "Annulla". Va dichiarata nel `Box` dell'elemento, come [AeroDropdownMenu].
 */
@Composable
fun BoxScope.AeroConfirmMenu(
    expanded: Boolean,
    title: String,
    message: String?,
    confirmText: String,
    cancelText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    confirmIcon: ImageVector? = null,
) {
    AeroDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        header = AeroMenuHeader(title, message),
        items = listOf(
            AeroMenuItem(confirmText, confirmIcon, destructive = true, onClick = onConfirm),
            AeroMenuItem(cancelText, onClick = onDismissRequest),
        ),
    )
}

@Composable
private fun AeroMenuRow(item: AeroMenuItem) {
    val tint = if (item.destructive) Color.Red else null
    DropdownMenuItem(
        text = {
            if (tint != null) Text(item.text, color = tint) else Text(item.text)
        },
        leadingIcon = item.icon?.let { icon ->
            {
                val iconTint = tint ?: item.iconTint
                if (iconTint != null) Icon(icon, contentDescription = null, tint = iconTint)
                else Icon(icon, contentDescription = null)
            }
        },
        enabled = item.enabled,
        modifier = if (item.hapticOnTap) Modifier.hapticOnTap(item.enabled) else Modifier,
        onClick = item.onClick,
    )
}

/** Area visibile della finestra (senza barre di sistema e tastiera) nelle coordinate della finestra. */
private fun visibleFrameInWindow(view: View): AndroidRect {
    val frame = AndroidRect()
    view.getWindowVisibleDisplayFrame(frame)
    val origin = IntArray(2)
    view.rootView.getLocationOnScreen(origin)
    frame.offset(-origin[0], -origin[1])
    if (frame.isEmpty) frame.set(0, 0, view.rootView.width, view.rootView.height)
    return frame
}

/**
 * Posiziona il menu a filo del pulsante: sotto o sopra secondo [opensUp] (stessa decisione dell'ordine
 * delle voci), allineato al bordo sinistro o destro del pulsante, contenuto nell'area visibile.
 * `anchorBounds` arriva in coordinate di finestra; l'area visibile viene ricalcolata nelle stesse coordinate
 * (il `windowSize` del provider perde lo scostamento della barra di stato).
 */
private class AeroMenuPositionProvider(
    private val view: View,
    private val opensUp: Boolean,
    private val alignEnd: Boolean,
    private val gapPx: Int,
    private val marginPx: Int,
    private val padPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val frame = visibleFrameInWindow(view)
        // Il contenuto ha [padPx] di bordo trasparente per l'ombra: si compensa perche' la superficie
        // visibile resti a filo del pulsante.
        val x = if (alignEnd) anchorBounds.right - popupContentSize.width + padPx else anchorBounds.left - padPx
        val y = if (opensUp) {
            anchorBounds.top - gapPx - popupContentSize.height + padPx
        } else {
            anchorBounds.bottom + gapPx - padPx
        }
        val inset = marginPx - padPx
        return IntOffset(
            x.clampIn(frame.left + inset, frame.right - inset - popupContentSize.width),
            y.clampIn(frame.top + inset, frame.bottom - inset - popupContentSize.height),
        )
    }

    private fun Int.clampIn(min: Int, max: Int): Int = if (max < min) min else coerceIn(min, max)
}
