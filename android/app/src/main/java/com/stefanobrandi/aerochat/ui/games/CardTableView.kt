package com.stefanobrandi.aerochat.ui.games

import android.app.Activity
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.games.BriscolaGame
import com.stefanobrandi.aerochat.core.games.GameOutcome
import com.stefanobrandi.aerochat.core.games.GameSessionState
import com.stefanobrandi.aerochat.core.games.GameStatus
import com.stefanobrandi.aerochat.core.games.NeapolitanCards
import com.stefanobrandi.aerochat.core.games.ScopaGame
import com.stefanobrandi.aerochat.core.games.playerCount
import com.stefanobrandi.aerochat.core.games.turnSeat
import com.stefanobrandi.aerochat.proto.GameKind
import com.stefanobrandi.aerochat.ui.AeroForcedDark
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.GameBubbleAction
import com.stefanobrandi.aerochat.ui.components.HapticButton
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.gameTitle
import com.stefanobrandi.aerochat.ui.components.hapticOnTap
import com.stefanobrandi.aerochat.ui.hex
import kotlinx.coroutines.delay
import com.stefanobrandi.aerochat.ui.chat.InfoBanner

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
    initialSelectedCard: Int? = null,
) {
    // Inset del tavolo (Pixel 8a, navigazione a gesti: mano e "Gioca" finivano sulla maniglia di sistema,
    // e a volte succedeva ancora con il minimo di 24 dp). Si prende il massimo, lato per lato, di:
    // 1. inset reali della finestra dell'Activity dalle root insets della DecorView (barre di sistema,
    //    ritaglio del display, gesti obbligatori; in basso anche gesti di sistema ed elementi toccabili);
    // 2. inset Compose della finestra del Dialog (safeDrawing; in basso anche barra di navigazione, gesti
    //    obbligatori e di sistema, elementi toccabili);
    // 3. root insets della finestra del Dialog lette dopo il layout (quando la finestra e' davvero attaccata);
    // 4. in basso almeno [TableMinBottomInset], cosi' mano e "Gioca" stanno sempre sopra la maniglia.
    val hostView = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val hostInsets = remember(hostView, density, configuration) { rootInsets(hostView, density) }
    Dialog(
        onDismissRequest = onDismiss,
        // Edge-to-edge: il feltro disegna sotto le barre di sistema, il contenuto rispetta gli inset sopra.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        FullScreenDialogWindow()
        val layoutDirection = LocalLayoutDirection.current
        val dialogView = LocalView.current
        val dialogDensity = LocalDensity.current
        var dialogRootInsets by remember { mutableStateOf(TableInsets()) }
        val dialogSafe = WindowInsets.safeDrawing.asPaddingValues()
        // Dei gesti del Dialog conta solo il basso (i bordi laterali del "indietro" no).
        val dialogBottom = listOf(
            WindowInsets.navigationBars,
            WindowInsets.mandatorySystemGestures,
            WindowInsets.systemGestures,
            WindowInsets.tappableElement,
        ).maxOf { with(dialogDensity) { it.getBottom(dialogDensity).toDp() } }
        val contentPadding = tableContentPadding(
            hostInsets,
            dialogRootInsets,
            TableInsets(dialogSafe, layoutDirection),
            TableInsets(bottom = dialogBottom),
        )
        // Il panno verde resta scuro anche con il tema chiaro di sistema.
        AeroForcedDark {
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        // Dopo il layout la finestra del Dialog ha i suoi inset reali: si rilegge.
                        val measured = rootInsets(dialogView, dialogDensity, fallbackToActivity = false)
                        if (measured != dialogRootInsets) dialogRootInsets = measured
                    },
            ) {
                CardTableView(
                    state, onAction, onDismiss, statusMessage = statusMessage, onStatusExpire = onStatusExpire,
                    contentPadding = contentPadding, initialSelectedCard = initialSelectedCard,
                )
            }
        }
    }
}

/** Spazio minimo sotto mano e "Gioca": sempre ben sopra la maniglia della navigazione a gesti. */
internal val TableMinBottomInset = 48.dp

/** Inset del tavolo in dp, in direzione logica (start/end). */
internal data class TableInsets(val start: Dp = 0.dp, val top: Dp = 0.dp, val end: Dp = 0.dp, val bottom: Dp = 0.dp) {
    constructor(padding: PaddingValues, layoutDirection: LayoutDirection) : this(
        start = padding.calculateStartPadding(layoutDirection),
        top = padding.calculateTopPadding(),
        end = padding.calculateEndPadding(layoutDirection),
        bottom = padding.calculateBottomPadding(),
    )
}

/** Il massimo, lato per lato, degli inset noti; in basso almeno [minBottom]. */
internal fun tableContentPadding(vararg insets: TableInsets, minBottom: Dp = TableMinBottomInset): PaddingValues =
    PaddingValues(
        start = insets.maxOfOrNull { it.start } ?: 0.dp,
        top = insets.maxOfOrNull { it.top } ?: 0.dp,
        end = insets.maxOfOrNull { it.end } ?: 0.dp,
        bottom = maxOf(minBottom, insets.maxOfOrNull { it.bottom } ?: 0.dp),
    )

/**
 * Root insets (non consumati da nessuno) della finestra di [view] (con [fallbackToActivity], della
 * DecorView dell'Activity), in dp: barre di sistema, ritaglio del display e gesti obbligatori; in basso
 * anche gesti di sistema ed elementi toccabili (la maniglia della navigazione a gesti).
 */
private fun rootInsets(view: View, density: Density, fallbackToActivity: Boolean = true): TableInsets {
    val target = if (fallbackToActivity) view.context.findActivity()?.window?.decorView ?: view.rootView else view.rootView
    val windowInsets = ViewCompat.getRootWindowInsets(target) ?: return TableInsets()
    val insets = windowInsets.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
            WindowInsetsCompat.Type.mandatorySystemGestures(),
    )
    val bottom = maxOf(
        insets.bottom,
        windowInsets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom,
        windowInsets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom,
    )
    val rtl = target.layoutDirection == View.LAYOUT_DIRECTION_RTL
    return with(density) {
        TableInsets(
            start = (if (rtl) insets.right else insets.left).toDp(),
            top = insets.top.toDp(),
            end = (if (rtl) insets.left else insets.right).toDp(),
            bottom = bottom.toDp(),
        )
    }
}

/**
 * La finestra del Dialog occupa tutto lo schermo (anche sotto barra di stato, ritaglio e barra di
 * navigazione) e non adatta il contenuto alle barre da sola: il feltro arriva ai bordi e gli inset li
 * applica una sola volta il tavolo (niente padding di sistema sommato al nostro, che spingeva giu' la mano).
 */
@Composable
private fun FullScreenDialogWindow() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.decorView.setPadding(0, 0, 0, 0)
        val attributes = window.attributes
        if (attributes.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attributes
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val feltCenter = Color.hex(0x0B5D3B)
private val feltEdge = Color.hex(0x03241A)
private val gold = Color.hex(0xFFD54F)
/** Sollevamento della carta selezionata nella mano. */
private val cardLift = 16.dp

// Slot fissi del tavolo (dp): l'altezza di ogni regione non dipende mai dal turno o dallo stato.
private val TableTopPadding = 8.dp
private val TableBottomPadding = 12.dp
private val TableSpacing = 10.dp
internal val HeaderHeight = 48.dp
private val SeatSlotHeight = 108.dp
private val SeatNameRowHeight = 18.dp
private val SeatCardsHeight = 52.dp
private val CompactSeatWidth = 72.dp
private val PileRowHeight = 16.dp
private val InfoLineHeight = 40.dp
private val CaptureSlotHeight = 56.dp
private val BottomRowHeight = 48.dp
private val HandMaxHeight = 136.dp
private val HandMinHeight = 96.dp
/** Spazio minimo lasciato al centro del tavolo prima di rimpicciolire la mano. */
private val TableCenterMinHeight = 180.dp
internal const val CaptureSlotTag = "cardTable.captureSlot"
internal const val HandTag = "cardTable.hand"
internal const val PlayButtonTag = "cardTable.play"

/** Altezza della mano: la massima che lascia al centro del tavolo almeno [TableCenterMinHeight]. */
private fun handHeightFor(available: Dp, hasCaptureSlot: Boolean): Dp {
    val slots = 5 + (if (hasCaptureSlot) 1 else 0)
    val fixed = HeaderHeight + SeatSlotHeight + InfoLineHeight + BottomRowHeight +
        (if (hasCaptureSlot) CaptureSlotHeight else 0.dp) + TableSpacing * (slots - 1)
    return (available - fixed - TableCenterMinHeight).coerceIn(HandMinHeight, HandMaxHeight)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardTableView(
    state: GameSessionState,
    onAction: (GameBubbleAction) -> Unit,
    onDismiss: () -> Unit,
    statusMessage: String? = null,
    onStatusExpire: () -> Unit = {},
    contentPadding: PaddingValues? = null,
    /** Carta della mano gia' selezionata all'apertura (screenshot degli store). */
    initialSelectedCard: Int? = null,
) {
    var selectedCard by remember { mutableStateOf(initialSelectedCard) }
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

    // Gestori dei tocchi letti sempre dallo stato corrente. Prima erano funzioni locali passate come
    // `::tap` / `::playSelected`: con lo strong skipping di Compose il riferimento restava quello della
    // prima composizione, con `canPlay` di allora. Aprendo il tavolo durante il turno dell'avversario,
    // al proprio turno i tocchi venivano ignorati finche' non si chiudeva e riapriva il tavolo.
    val currentState by rememberUpdatedState(state)
    val currentOnAction by rememberUpdatedState(onAction)
    val playSelected: () -> Unit = remember {
        {
            val current = currentState
            val card = selectedCard
            val playable = current.status == GameStatus.active && current.myTurn && current.mySeat != null && !playInFlight
            if (card != null && playable) {
                val options = current.scopa?.captureOptions(card).orEmpty()
                val captured = if (options.isEmpty()) emptyList() else options[minOf(selectedOption, options.size - 1)]
                playInFlight = true
                selectedCard = null
                selectedOption = 0
                currentOnAction(GameBubbleAction.PlayCard(card, captured))
            }
        }
    }
    val tap: (Int) -> Unit = remember {
        { card ->
            val current = currentState
            val playable = current.status == GameStatus.active && current.myTurn && current.mySeat != null
            when {
                playInFlight -> Unit
                !playable -> Haptics.warning()
                selectedCard == card -> playSelected()
                else -> {
                    Haptics.tap()
                    selectedCard = card
                    selectedOption = 0
                }
            }
        }
    }

    // Layout a slot fissi: nessuna regione cambia altezza con il turno o lo stato (prima il banner di
    // stato, la scelta della presa e il pulsante "Gioca" comparivano e sparivano nella colonna, e il
    // centro del tavolo, a peso 1, si allargava e stringeva a ogni cambio: tutto "saltava").
    val insetsModifier = if (contentPadding != null) Modifier.padding(contentPadding) else Modifier.safeDrawingPadding()
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(feltCenter, feltEdge), radius = 1400f)),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .then(insetsModifier)
                .padding(horizontal = 12.dp)
                .padding(top = TableTopPadding, bottom = TableBottomPadding),
        ) {
            // Altezza della mano dallo spazio disponibile (dipende solo dallo schermo, non dal turno): su
            // schermi bassi carte e mano si rimpiccioliscono invece di finire sotto il bordo.
            val hasCaptureSlot = scopa != null && state.mySeat != null
            val handHeight = handHeightFor(maxHeight, hasCaptureSlot)
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(TableSpacing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(state, onDismiss, onShowScore = { showScoreSheet = true }, onResign = { confirmResign = true })
                Box(Modifier.fillMaxWidth().height(SeatSlotHeight), contentAlignment = Alignment.Center) {
                    SeatBadge(state, seat = (bottomSeat + (if (playerCount == 4) 2 else 1)) % playerCount)
                }
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
                if (hasCaptureSlot) {
                    // Spazio sempre riservato: il contenuto c'e' solo con piu' prese possibili.
                    Box(
                        Modifier.fillMaxWidth().height(CaptureSlotHeight).testTag(CaptureSlotTag),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (captureOptions.size > 1) {
                            CaptureChooser(captureOptions, selectedOption) { selectedOption = it }
                        }
                    }
                }
                BottomArea(state, selectedCard, canPlay, handHeight, onTap = tap, onPlay = playSelected)
            }
        }
        // Banner di stato in sovrimpressione sul feltro (sotto l'intestazione): non sposta niente.
        InfoBanner(
            message = statusMessage, onExpire = onStatusExpire,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(insetsModifier)
                .padding(top = TableTopPadding + HeaderHeight),
        )
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
    Row(Modifier.fillMaxWidth().height(HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
        HapticButton(onClick = onDismiss, tint = AeroTheme.textPrimary, contentDescription = stringResource(R.string.games_close_table)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.games_close_table))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                gameTitle(state.gameKind), style = AeroTheme.title(17f, FontWeight.SemiBold), color = AeroTheme.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
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
            .then(if (compact) Modifier.width(CompactSeatWidth) else Modifier)
            .background(Color.Black.copy(alpha = if (isTurn) 0.35f else 0.18f), shape)
            .border(1.5.dp, if (isTurn) AeroTheme.accentCyan.copy(alpha = 0.8f) else Color.Transparent, shape)
            .padding(6.dp)
            .semantics(mergeDescendants = true) { stateDescription = handDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.height(SeatNameRowHeight),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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
        // Spazio delle carte sempre riservato (anche a mano vuota) e largo quanto la mano piena.
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
            val fullHand = maxOf(count, if (state.scopa?.isScopone == true) 10 else 3)
            Box(Modifier.size(32.dp + step * (fullHand - 1), SeatCardsHeight), contentAlignment = Alignment.CenterStart) {
                val start = step * (fullHand - count) / 2
                for (index in 0 until count) {
                    NeapolitanCardView(card = null, width = 32.dp, modifier = Modifier.offset(x = start + step * index))
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
    Row(
        Modifier.height(PileRowHeight),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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

/** Ultima giocata e stato del turno: due righe singole in uno slot ad altezza fissa (testi lunghi troncati). */
@Composable
private fun InfoLine(state: GameSessionState) {
    Column(
        Modifier.fillMaxWidth().height(InfoLineHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(
            lastMoveText(state).orEmpty(), style = AeroTheme.footnote.copy(fontWeight = FontWeight.Medium),
            color = AeroTheme.textPrimary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(cardStatusText(state), resignText(state)).joinToString(" · "),
            style = AeroTheme.captionSemibold,
            color = if (state.myTurn) AeroTheme.accentCyan else AeroTheme.textSecondary,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
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

/**
 * Mano e riga del giocatore in uno slot ad altezza fissa ancorato in basso: il pulsante "Gioca" c'e'
 * sempre nello stesso punto (disattivato e attenuato fuori turno o senza carta scelta).
 */
@Composable
private fun BottomArea(
    state: GameSessionState,
    selectedCard: Int?,
    canPlay: Boolean,
    handHeight: Dp,
    onTap: (Int) -> Unit,
    onPlay: () -> Unit,
) {
    val mySeat = state.mySeat
    if (mySeat == null) {
        Column(
            Modifier.fillMaxWidth().height(handHeight + BottomRowHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
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
    Column(Modifier.fillMaxWidth().height(handHeight + BottomRowHeight)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(handHeight).testTag(HandTag),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val count = maxOf(hand.size, 1)
            val visible = if (count > 5) 0.62f else 1.08f
            // Larghezza della carta limitata anche dall'altezza della mano (carta + sollevamento).
            val maxCardWidth = minOf(72.dp, (handHeight - cardLift) / cardAspectRatio)
            val width: Dp = (maxWidth / (1 + (count - 1) * visible)).coerceIn(minOf(36.dp, maxCardWidth), maxCardWidth)
            val step = width * visible
            val total = width + step * (count - 1)
            Box(Modifier.width(total).height(handHeight)) {
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
        Row(Modifier.fillMaxWidth().height(BottomRowHeight), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    seatName(state, mySeat), style = AeroTheme.captionSemibold,
                    color = if (state.turnSeat == mySeat) AeroTheme.accentCyan else AeroTheme.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                PileLabel(state, mySeat)
                if (isDealer(state, mySeat)) {
                    Text(
                        stringResource(R.string.games_dealer), style = AeroTheme.caption.copy(fontSize = 11.sp),
                        color = AeroTheme.textSecondary, maxLines = 1,
                    )
                }
            }
            val playEnabled = canPlay && selectedCard != null
            Button(
                onClick = onPlay,
                enabled = playEnabled,
                modifier = Modifier
                    .testTag(PlayButtonTag)
                    .hapticOnTap(playEnabled)
                    .alpha(if (canPlay) 1f else 0.5f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AeroTheme.accentEmerald, contentColor = Color.White,
                    disabledContainerColor = AeroTheme.accentEmerald.copy(alpha = 0.35f),
                    disabledContentColor = Color.White.copy(alpha = 0.6f),
                ),
            ) {
                Text(
                    stringResource(R.string.games_play), style = AeroTheme.body, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.defaultMinSize(minWidth = 72.dp),
                    textAlign = TextAlign.Center, maxLines = 1,
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
