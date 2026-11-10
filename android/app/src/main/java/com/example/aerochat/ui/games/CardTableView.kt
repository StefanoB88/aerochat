package com.example.aerochat.ui.games

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.aerochat.R
import com.example.aerochat.core.games.BriscolaGame
import com.example.aerochat.core.games.GameOutcome
import com.example.aerochat.core.games.GameSessionState
import com.example.aerochat.core.games.GameStatus
import com.example.aerochat.core.games.NeapolitanCards
import com.example.aerochat.core.games.ScopaGame
import com.example.aerochat.core.games.playerCount
import com.example.aerochat.core.games.turnSeat
import com.example.aerochat.proto.GameKind
import com.example.aerochat.ui.AeroForcedDark
import com.example.aerochat.ui.AeroTheme
import com.example.aerochat.ui.components.GameBubbleAction
import com.example.aerochat.ui.components.HapticButton
import com.example.aerochat.ui.components.Haptics
import com.example.aerochat.ui.components.gameTitle
import com.example.aerochat.ui.components.hapticOnTap
import com.example.aerochat.ui.hex
import kotlinx.coroutines.delay
import com.example.aerochat.ui.chat.InfoBanner

/**
 * Tavolo a schermo intero delle partite a carte (Dialog a tutto schermo): panno verde, avversari in alto
 * (e ai lati nello Scopone), carte in tavola al centro, la propria mano in basso. Le mosse passano da
 * `onAction` come nella bolla. Stesso layout di iOS (`CardTableView.swift`).
 */
@Composable
fun CardTableDialog(
    state: GameSessionState,
    onAction: (GameBubbleAction) -> Unit,
    onDismiss: () -> Unit,
    statusMessage: String? = null,
    onStatusExpire: () -> Unit = {},
) {
    // Inset letti dalla finestra dell'Activity, non dal Dialog: nella finestra del Dialog arrivano a zero
    // (o solo dopo il primo layout), e mano e "Gioca" finivano sotto la barra di navigazione, dove i tocchi
    // li prendono i gesti di sistema: il turno sembrava bloccato finche' non si riapriva il tavolo.
    val hostInsets = WindowInsets.safeDrawing.asPaddingValues()
    Dialog(
        onDismissRequest = onDismiss,
        // Edge-to-edge: il feltro disegna sotto le barre di sistema, il contenuto rispetta `hostInsets`.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Il panno verde resta scuro anche con il tema chiaro di sistema.
        AeroForcedDark {
            CardTableView(
                state, onAction, onDismiss, statusMessage = statusMessage, onStatusExpire = onStatusExpire,
                contentPadding = hostInsets,
            )
        }
    }
}

private val feltCenter = Color.hex(0x0B5D3B)
private val feltEdge = Color.hex(0x03241A)
private val gold = Color.hex(0xFFD54F)
/** Sollevamento della carta selezionata nella mano. */
private val cardLift = 16.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardTableView(
    state: GameSessionState,
    onAction: (GameBubbleAction) -> Unit,
    onDismiss: () -> Unit,
    statusMessage: String? = null,
    onStatusExpire: () -> Unit = {},
    contentPadding: PaddingValues? = null,
) {
    var selectedCard by remember { mutableStateOf<Int?>(null) }
    var selectedOption by remember { mutableIntStateOf(0) }
    var confirmResign by remember { mutableStateOf(false) }
    var showScoreSheet by remember { mutableStateOf(false) }
    var scopaFlash by remember { mutableStateOf(false) }

    val scopa = state.scopa
    val briscola = state.briscola
    val playerCount = state.playerCount
    val bottomSeat = state.mySeat ?: 0
    val canPlayState = state.status == GameStatus.active && state.myTurn && state.mySeat != null

    val captureOptions = remember(selectedCard, scopa?.table) {
        val card = selectedCard
        if (card == null || scopa == null) emptyList() else scopa.captureOptions(card)
    }
    val highlighted: Set<Int> =
        if (captureOptions.isEmpty()) emptySet() else captureOptions[minOf(selectedOption, captureOptions.size - 1)].toSet()

    // Mani completate: il riepilogo si apre da solo alla fine di ogni mano (non all'apertura del tavolo).
    val completedHands = when {
        scopa != null -> scopa.handNumber - (if (scopa.phase == ScopaGame.Phase.playing) 1 else 0)
        briscola != null -> if (briscola.isFinished) 1 else 0
        else -> 0
    }
    var seenHands by remember { mutableIntStateOf(completedHands) }
    LaunchedEffect(completedHands) {
        if (completedHands > seenHands) showScoreSheet = true
        seenHands = completedHands
    }
    // Vibrazione e "Scopa!" sulle nuove giocate (non su quella gia' presente all'apertura).
    val lastPlay = scopa?.lastPlay
    var seenPlay by remember { mutableStateOf(lastPlay to scopa?.handNumber) }
    LaunchedEffect(lastPlay, scopa?.handNumber) {
        val key = lastPlay to scopa?.handNumber
        if (lastPlay != null && key != seenPlay) {
            if (lastPlay.scopa) {
                Haptics.success()
                scopaFlash = true
            } else {
                Haptics.impact(if (lastPlay.captured.isEmpty()) Haptics.ImpactStyle.light else Haptics.ImpactStyle.medium)
            }
        }
        seenPlay = key
    }
    val lastTrick = briscola?.lastTrick
    var seenTrick by remember { mutableStateOf(lastTrick to briscola?.stock?.size) }
    LaunchedEffect(lastTrick, briscola?.stock?.size) {
        val key = lastTrick to briscola?.stock?.size
        if (lastTrick != null && key != seenTrick) Haptics.impact(Haptics.ImpactStyle.light)
        seenTrick = key
    }
    LaunchedEffect(state.myTurn) { if (!state.myTurn) selectedCard = null }
    // Mossa inviata ma non ancora applicata allo stato: tocchi rapidi (doppio tocco, "Gioca" + tocco)
    // avrebbero spedito una seconda carta, rifiutata come "Mossa non valida". Si sblocca al nuovo stato.
    var playInFlight by remember { mutableStateOf(false) }
    LaunchedEffect(state.sequence, state.myTurn, state.status) { playInFlight = false }
    // Rete di sicurezza: se la mossa fallisce (errore, servizio non pronto) lo stato non cambia e il
    // tavolo restava sordo ai tocchi finche' non veniva chiuso e riaperto.
    LaunchedEffect(playInFlight) {
        if (playInFlight) {
            delay(2_000)
            playInFlight = false
        }
    }
    // Esito della mossa (non e' il tuo turno, mossa non valida...): il banner della chat sta sotto il
    // Dialog a schermo intero, quindi va ripetuto qui.
    LaunchedEffect(statusMessage) { if (statusMessage != null) playInFlight = false }
    LaunchedEffect(scopaFlash) {
        if (scopaFlash) {
            delay(1_400)
            scopaFlash = false
        }
    }

    val canPlay = canPlayState && !playInFlight

    fun playSelected() {
        val card = selectedCard ?: return
        if (!canPlay) return
        playInFlight = true
        val captured = if (captureOptions.isEmpty()) emptyList() else captureOptions[minOf(selectedOption, captureOptions.size - 1)]
        selectedCard = null
        selectedOption = 0
        onAction(GameBubbleAction.PlayCard(card, captured))
    }

    fun tap(card: Int) {
        if (playInFlight) return
        if (!canPlay) {
            Haptics.warning()
            return
        }
        if (selectedCard == card) {
            playSelected()
        } else {
            Haptics.tap()
            selectedCard = card
            selectedOption = 0
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(feltCenter, feltEdge), radius = 1400f)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (contentPadding != null) Modifier.padding(contentPadding) else Modifier.safeDrawingPadding())
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(state, onDismiss, onShowScore = { showScoreSheet = true }, onResign = { confirmResign = true })
            InfoBanner(message = statusMessage, onExpire = onStatusExpire)
            SeatBadge(state, seat = (bottomSeat + (if (playerCount == 4) 2 else 1)) % playerCount)
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (playerCount == 4) SeatBadge(state, seat = (bottomSeat + 3) % 4, compact = true)
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    TableCenter(state, highlighted, dimOthers = selectedCard != null && highlighted.isNotEmpty()) { card ->
                        val index = captureOptions.indexOfFirst { it.contains(card) }
                        if (index >= 0) {
                            Haptics.tap()
                            selectedOption = index
                        }
                    }
                }
                if (playerCount == 4) SeatBadge(state, seat = (bottomSeat + 1) % 4, compact = true)
            }
            InfoLine(state)
            if (captureOptions.size > 1) {
                CaptureChooser(captureOptions, selectedOption) { selectedOption = it }
            }
            BottomArea(state, selectedCard, canPlay, onTap = ::tap, onPlay = ::playSelected)
        }
        AnimatedVisibility(
            visible = scopaFlash,
            enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.4f) + fadeIn(),
            exit = scaleOut(targetScale = 0.4f) + fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                stringResource(R.string.games_scopa_exclaim),
                style = AeroTheme.title(56f, FontWeight.Black),
                color = gold,
            )
        }
    }

    if (confirmResign) {
        AlertDialog(
            onDismissRequest = { confirmResign = false },
            containerColor = AeroTheme.surface,
            titleContentColor = AeroTheme.textPrimary,
            textContentColor = AeroTheme.textSecondary,
            title = { Text(stringResource(R.string.games_resign_title)) },
            text = { Text(stringResource(if (playerCount == 4) R.string.games_resign_team else R.string.games_resign_solo)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmResign = false
                        onAction(GameBubbleAction.Resign)
                    },
                    modifier = Modifier.hapticOnTap(),
                ) { Text(stringResource(R.string.games_resign), color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmResign = false }) {
                    Text(stringResource(R.string.games_cancel), color = AeroTheme.accentCyan)
                }
            },
        )
    }
    if (showScoreSheet) {
        CardScoreSheet(state, onDismiss = { showScoreSheet = false })
    }
}

// MARK: Intestazione

@Composable
private fun Header(state: GameSessionState, onDismiss: () -> Unit, onShowScore: () -> Unit, onResign: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HapticButton(onClick = onDismiss, tint = AeroTheme.textPrimary, contentDescription = stringResource(R.string.games_close_table)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.games_close_table))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(gameTitle(state.gameKind), style = AeroTheme.title(17f, FontWeight.SemiBold), color = AeroTheme.textPrimary)
            val scores = cardScores(state)
            if (scores != null && scores.size == 2) {
                Text(
                    "${sideName(state, 0)} ${scores[0]} – ${scores[1]} ${sideName(state, 1)}",
                    style = AeroTheme.caption.copy(fontFeatureSettings = "tnum"),
                    color = AeroTheme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.scopa?.lastHandScore != null || state.briscola?.isFinished == true) {
            val label = stringResource(R.string.games_last_hand_score)
            HapticButton(onClick = onShowScore, tint = AeroTheme.textPrimary, contentDescription = label) {
                Icon(Icons.Outlined.FormatListNumbered, contentDescription = label)
            }
        }
        if (state.mySeat != null && state.status != GameStatus.finished) {
            val label = stringResource(R.string.games_resign)
            HapticButton(onClick = onResign, destructive = true, contentDescription = label) {
                Icon(Icons.Filled.Flag, contentDescription = label)
            }
        }
    }
}

// MARK: Posti

private fun handCount(state: GameSessionState, seat: Int): Int =
    state.scopa?.hands?.getOrNull(seat)?.size ?: state.briscola?.hands?.getOrNull(seat)?.size ?: 0

private fun pileCount(state: GameSessionState, seat: Int): Int =
    state.scopa?.captures?.getOrNull(ScopaGame.side(seat))?.size ?: state.briscola?.won?.getOrNull(seat)?.size ?: 0

private fun isDealer(state: GameSessionState, seat: Int): Boolean =
    (state.scopa?.dealer ?: state.briscola?.dealer) == seat

@Composable
private fun SeatBadge(state: GameSessionState, seat: Int, compact: Boolean = false) {
    val isTurn = state.turnSeat == seat
    val count = handCount(state, seat)
    val handDescription = pluralStringResource(R.plurals.games_cards_in_hand, count, count)
    val dealerDescription = stringResource(R.string.games_dealer)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .then(if (compact) Modifier.widthIn(max = 72.dp) else Modifier)
            .background(Color.Black.copy(alpha = if (isTurn) 0.35f else 0.18f), shape)
            .border(1.5.dp, if (isTurn) AeroTheme.accentCyan.copy(alpha = 0.8f) else Color.Transparent, shape)
            .padding(6.dp)
            .semantics(mergeDescendants = true) { stateDescription = handDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isDealer(state, seat)) {
                Text(
                    stringResource(R.string.games_dealer_short),
                    style = AeroTheme.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Black),
                    color = AeroTheme.textPrimary,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .semantics { contentDescription = dealerDescription },
                )
            }
            Text(
                seatName(state, seat),
                style = AeroTheme.captionSemibold,
                color = if (isTurn) AeroTheme.accentCyan else AeroTheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (count > 0) {
            if (compact) {
                Box(Modifier.size(50.dp, 70.dp)) {
                    for (index in 0 until minOf(count, 5)) {
                        NeapolitanCardView(
                            card = null, width = 28.dp,
                            modifier = Modifier.align(Alignment.TopCenter).offset(y = (index * 6).dp).rotate(90f),
                        )
                    }
                }
            } else {
                val step = 10.dp
                Box(Modifier.size(32.dp + step * (count - 1), 52.dp)) {
                    for (index in 0 until count) {
                        NeapolitanCardView(card = null, width = 32.dp, modifier = Modifier.offset(x = step * index))
                    }
                }
            }
        }
        PileLabel(state, seat)
    }
}

@Composable
private fun PileLabel(state: GameSessionState, seat: Int) {
    val scope = state.scopa?.scope?.getOrNull(ScopaGame.side(seat)) ?: 0
    val scopeDescription = pluralStringResource(R.plurals.games_scope_count, scope, scope)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Outlined.Layers, contentDescription = null, tint = AeroTheme.textSecondary, modifier = Modifier.size(12.dp))
        Text(
            pileCount(state, seat).toString(),
            style = AeroTheme.caption.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"),
            color = AeroTheme.textSecondary,
        )
        if (scope > 0) {
            Text(
                "★".repeat(minOf(scope, 6)),
                style = AeroTheme.caption.copy(fontSize = 11.sp),
                color = gold,
                modifier = Modifier.semantics { contentDescription = scopeDescription },
            )
        }
    }
}

// MARK: Centro

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TableCenter(state: GameSessionState, highlighted: Set<Int>, dimOthers: Boolean, onTapTableCard: (Int) -> Unit) {
    val scopa = state.scopa
    val briscola = state.briscola
    when {
        scopa != null -> Column(
            Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (scopa.table.isEmpty()) {
                if (scopa.phase == ScopaGame.Phase.playing) {
                    Text(stringResource(R.string.games_empty_table), style = AeroTheme.footnote, color = AeroTheme.textSecondary)
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (card in scopa.table) {
                        NeapolitanCardView(
                            card = card, width = 48.dp,
                            highlighted = card in highlighted,
                            dimmed = dimOthers && card !in highlighted,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                role = Role.Button,
                            ) { onTapTableCard(card) },
                        )
                    }
                }
            }
            if (!scopa.isScopone && scopa.phase == ScopaGame.Phase.playing) {
                Text(
                    stringResource(R.string.games_deck_count, scopa.remainingInDeck),
                    style = AeroTheme.caption.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"),
                    color = AeroTheme.textSecondary,
                )
            }
        }
        briscola != null -> BriscolaCenter(briscola)
        else -> Text(cardStatusText(state), style = AeroTheme.footnote, color = AeroTheme.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BriscolaCenter(briscola: BriscolaGame) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(100.dp, 80.dp), contentAlignment = Alignment.CenterStart) {
                if (briscola.stock.isNotEmpty()) {
                    NeapolitanCardView(
                        card = briscola.briscolaCard, width = 48.dp,
                        modifier = Modifier.offset(x = 30.dp).rotate(90f),
                    )
                }
                if (briscola.stock.size > 1) NeapolitanCardView(card = null, width = 48.dp)
            }
            Text(
                stringResource(R.string.games_stock_count, briscola.stock.size),
                style = AeroTheme.caption.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"),
                color = AeroTheme.textPrimary,
            )
            Text(
                stringResource(R.string.games_briscola_suit, suitName(briscola.trumpSuit)),
                style = AeroTheme.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = gold,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (card in briscola.trick) NeapolitanCardView(card = card, width = 56.dp)
            if (briscola.trick.isEmpty()) {
                Text(stringResource(R.string.games_no_card_in_play), style = AeroTheme.footnote, color = AeroTheme.textSecondary)
            }
        }
    }
}

@Composable
private fun InfoLine(state: GameSessionState) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lastMoveText(state)?.let {
            Text(
                it, style = AeroTheme.footnote.copy(fontWeight = FontWeight.Medium), color = AeroTheme.textPrimary,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            cardStatusText(state),
            style = AeroTheme.captionSemibold,
            color = if (state.myTurn) AeroTheme.accentCyan else AeroTheme.textSecondary,
        )
        resignText(state)?.let { Text(it, style = AeroTheme.caption.copy(fontSize = 11.sp), color = AeroTheme.textSecondary) }
    }
}

// MARK: Scelta della presa

@Composable
private fun CaptureChooser(options: List<List<Int>>, selected: Int, onSelect: (Int) -> Unit) {
    val shortName = cardShortNamer()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.games_choose_capture), style = AeroTheme.caption.copy(fontSize = 11.sp), color = AeroTheme.textSecondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selected
                val names = option.map { cardName(it) }.joinToString(", ")
                Text(
                    option.joinToString(" + ") { shortName(it) },
                    style = AeroTheme.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    color = AeroTheme.textPrimary,
                    modifier = Modifier
                        .background(if (isSelected) AeroTheme.accentCyan.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f), CircleShape)
                        .border(1.dp, if (isSelected) AeroTheme.accentCyan else Color.Transparent, CircleShape)
                        .clickable(role = Role.Button) {
                            Haptics.tap()
                            onSelect(index)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics {
                            contentDescription = names
                            this.selected = isSelected
                        },
                )
            }
        }
    }
}

// MARK: Mano

@Composable
private fun BottomArea(
    state: GameSessionState,
    selectedCard: Int?,
    canPlay: Boolean,
    onTap: (Int) -> Unit,
    onPlay: () -> Unit,
) {
    val mySeat = state.mySeat
    if (mySeat == null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Visibility, contentDescription = null, tint = AeroTheme.textSecondary, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.games_watching), style = AeroTheme.caption, color = AeroTheme.textSecondary)
            }
            SeatBadge(state, seat = 0)
        }
        return
    }
    // Mano ordinata per seme e valore (l'ordine interno resta quello di distribuzione).
    val hand = (state.scopa?.hands?.getOrNull(mySeat) ?: state.briscola?.hands?.getOrNull(mySeat) ?: emptyList()).sorted()
    BoxWithConstraints(Modifier.fillMaxWidth().height(136.dp), contentAlignment = Alignment.BottomCenter) {
        val count = maxOf(hand.size, 1)
        val visible = if (count > 5) 0.62f else 1.08f
        val width: Dp = (maxWidth / (1 + (count - 1) * visible)).coerceIn(36.dp, 72.dp)
        val step = width * visible
        val total = width + step * (count - 1)
        Box(Modifier.width(total).height(136.dp)) {
            hand.forEachIndexed { index, card ->
                key(card) {
                    val lift by animateDpAsState(if (card == selectedCard) cardLift else 0.dp, label = "lift")
                    // L'area di tocco resta ferma (alta quanto carta + sollevamento): prima si spostava con la
                    // carta sollevata e il secondo tocco ("doppio tocco per giocare") cadeva nel vuoto o sulla
                    // carta accanto. Solo il disegno si solleva (graphicsLayer, niente nuovo layout).
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = step * index)
                            .size(width, width * cardAspectRatio + cardLift)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                role = Role.Button,
                            ) { onTap(card) }
                            .semantics { selected = card == selectedCard },
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        NeapolitanCardView(
                            card = card, width = width, highlighted = card == selectedCard,
                            modifier = Modifier.graphicsLayer { translationY = -lift.toPx() },
                        )
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                seatName(state, mySeat), style = AeroTheme.captionSemibold,
                color = if (state.turnSeat == mySeat) AeroTheme.accentCyan else AeroTheme.textPrimary,
            )
            PileLabel(state, mySeat)
            if (isDealer(state, mySeat)) {
                Text(stringResource(R.string.games_dealer), style = AeroTheme.caption.copy(fontSize = 11.sp), color = AeroTheme.textSecondary)
            }
        }
        if (canPlay) {
            Button(
                onClick = onPlay,
                enabled = selectedCard != null,
                modifier = Modifier.hapticOnTap(),
                colors = ButtonDefaults.buttonColors(containerColor = AeroTheme.accentEmerald, contentColor = Color.White),
            ) {
                Text(
                    stringResource(R.string.games_play), style = AeroTheme.body, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.defaultMinSize(minWidth = 72.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// MARK: Riepilogo

/** Riepilogo di fine mano: carte, denari, settebello, primiera, scope e totali (Briscola: punti carta). */
@Composable
fun CardScoreSheet(state: GameSessionState, onDismiss: () -> Unit) {
    val scopa = state.scopa
    val briscola = state.briscola
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AeroTheme.surface,
        titleContentColor = AeroTheme.textPrimary,
        textContentColor = AeroTheme.textPrimary,
        title = {
            Text(stringResource(if (state.gameKind == GameKind.BRISCOLA) R.string.games_score_title else R.string.games_score_title_hand))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScoreRow("", sideName(state, 0), sideName(state, 1), header = true)
                HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                val score = scopa?.lastHandScore
                if (scopa != null && score != null) {
                    ScoreRow(stringResource(R.string.games_row_cards), score.cards[0].toString(), score.cards[1].toString(), score.cardsPoint)
                    ScoreRow(stringResource(R.string.games_row_denari), score.denari[0].toString(), score.denari[1].toString(), score.denariPoint)
                    ScoreRow(
                        stringResource(R.string.games_row_settebello),
                        if (score.settebello[0] > 0) "7♦" else "–", if (score.settebello[1] > 0) "7♦" else "–", score.settebello,
                    )
                    ScoreRow(
                        stringResource(R.string.games_row_primiera),
                        score.primieraValue[0].takeIf { it > 0 }?.toString() ?: "–",
                        score.primieraValue[1].takeIf { it > 0 }?.toString() ?: "–", score.primiera,
                    )
                    ScoreRow(stringResource(R.string.games_row_scope), score.scope[0].toString(), score.scope[1].toString(), score.scope)
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                    ScoreRow(stringResource(R.string.games_row_hand_total), score.total[0].toString(), score.total[1].toString(), bold = true)
                    ScoreRow(
                        stringResource(R.string.games_row_match), scopa.scores[0].toString(), scopa.scores[1].toString(),
                        bold = true, valueColor = AeroTheme.accentCyan,
                    )
                } else if (briscola != null) {
                    ScoreRow(stringResource(R.string.games_row_points), briscola.points[0].toString(), briscola.points[1].toString())
                    ScoreRow(stringResource(R.string.games_row_won_cards), briscola.won[0].size.toString(), briscola.won[1].size.toString())
                }
                Text(
                    cardStatusText(state),
                    style = AeroTheme.headline,
                    color = when (state.outcome) {
                        GameOutcome.iWon -> AeroTheme.accentEmerald
                        GameOutcome.iLost -> Color.Red
                        else -> AeroTheme.textPrimary
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.games_close), color = AeroTheme.accentCyan) }
        },
    )
}

@Composable
private fun ScoreRow(
    title: String,
    first: String,
    second: String,
    points: List<Int>? = null,
    header: Boolean = false,
    bold: Boolean = false,
    valueColor: Color = AeroTheme.textPrimary,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, modifier = Modifier.weight(1.2f),
            style = if (bold) AeroTheme.body.copy(fontWeight = FontWeight.SemiBold) else AeroTheme.body.copy(fontSize = 15.sp),
            color = AeroTheme.textPrimary,
        )
        for ((index, value) in listOf(first, second).withIndex()) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    value,
                    style = if (header) AeroTheme.captionSemibold else AeroTheme.body.copy(fontSize = 15.sp, fontFeatureSettings = "tnum"),
                    color = if (header) AeroTheme.textPrimary else valueColor,
                    maxLines = 2,
                )
                val point = points?.getOrNull(index) ?: 0
                if (point > 0) {
                    Text("+$point", style = AeroTheme.caption.copy(fontWeight = FontWeight.Bold), color = AeroTheme.accentEmerald)
                }
            }
        }
    }
}
