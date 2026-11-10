package com.example.aerochat.ui.lounge

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aerochat.R
import com.example.aerochat.app.AppEnvironment
import com.example.aerochat.app.LocalAppEnvironment
import com.example.aerochat.core.mesh.MeshRouterActor
import com.example.aerochat.core.mesh.RouterError
import com.example.aerochat.core.moderation.AppSettings
import com.example.aerochat.core.moderation.ContentFilter
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.MessageRecord
import com.example.aerochat.data.records.ReportRecord
import com.example.aerochat.data.records.canBeEdited
import com.example.aerochat.ui.AeroTheme
import com.example.aerochat.ui.chat.InfoBanner
import com.example.aerochat.ui.components.ComposerBar
import com.example.aerochat.ui.components.AeroDropdownMenu
import com.example.aerochat.ui.components.AeroMenuItem
import com.example.aerochat.ui.components.Haptics
import com.example.aerochat.ui.components.MessageBubble
import com.example.aerochat.ui.components.hapticOnTap
import com.example.aerochat.ui.safety.ReportSheet
import com.example.aerochat.core.quiz.QuizService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** Swift `@Observable CabinLoungeViewModel`. */
class CabinLoungeViewModel(context: Context) {

    private val context: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // La tab viene ricreata a ogni cambio: si riparte dall'ultimo stato noto invece che da una Cabina
    // vuota, che per un attimo mostrava il suggerimento "nessun messaggio" prima del caricamento.
    private val _lounge = MutableStateFlow(lastLounge)
    val lounge: StateFlow<ConversationRecord?> = _lounge.asStateFlow()
    private val _messages = MutableStateFlow(lastMessages.orEmpty())
    val messages: StateFlow<List<MessageRecord>> = _messages.asStateFlow()
    /** `true` quando l'elenco riflette il database (o l'ultimo stato noto): solo allora "vuoto" e' reale. */
    private val _loaded = MutableStateFlow(lastMessages != null)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private companion object {
        @Volatile var lastLounge: ConversationRecord? = null
        @Volatile var lastMessages: List<MessageRecord>? = null
    }
    /** `node_id` (hex) dei passeggeri bloccati. */
    private var blocked: Set<String> = emptySet()
    val draft = MutableStateFlow("")
    val infoMessage = MutableStateFlow<String?>(null)
    /** Proprio messaggio in modifica: la barra mostra "Modifica messaggio" e l'invio manda la revisione. */
    private val _editingMessage = MutableStateFlow<MessageRecord?>(null)
    val editingMessage: StateFlow<MessageRecord?> = _editingMessage.asStateFlow()
    private var draftBeforeEditing: String = ""
    private val tasks = mutableListOf<Job>()

    fun start(environment: AppEnvironment) {
        if (tasks.isNotEmpty()) return
        val database = environment.database.value ?: return
        tasks += scope.launch {
            val lounge = runCatching { database.conversations.currentCabinLounge() }.getOrNull() ?: return@launch
            if (lounge.id != lastLounge?.id) {
                // Nuova Lounge (12 ore scadute): l'ultimo stato noto non vale piu'.
                _messages.value = emptyList()
                _loaded.value = false
            }
            _lounge.value = lounge
            lastLounge = lounge
            blocked = runCatching { database.peers.blockedNodeIDs() }.getOrNull() ?: emptySet()
            database.messages.observe(conversationID = lounge.id).collect { list ->
                val visible = list.filter { it.senderNodeID.toHex() !in blocked }
                _messages.value = visible
                lastMessages = visible
                _loaded.value = true
            }
        }
        tasks += scope.launch {
            // Ogni 10 minuti verifica la scadenza delle 12 ore (ricrea la Lounge e svuota i vecchi messaggi).
            while (isActive) {
                delay(600_000)
                val renewed = runCatching { database.conversations.currentCabinLounge() }.getOrNull()
                if (renewed != null && renewed.expiresAtMs != _lounge.value?.expiresAtMs) {
                    stop()
                    start(environment)
                }
            }
        }
    }

    fun stop() {
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    /** "Si autodistrugge tra Xh Ym" rispetto a [now] (epoch ms). */
    fun expiryText(now: Long = System.currentTimeMillis()): String {
        val expires = _lounge.value?.expiresAtMs ?: return ""
        val remaining = max(0L, expires - now) / 1_000
        val hours = (remaining / 3_600).toInt()
        val minutes = ((remaining % 3_600) / 60).toInt()
        return context.getString(R.string.chat_lounge_expiry, hours, minutes)
    }

    fun send(environment: AppEnvironment) {
        if (_editingMessage.value != null) {
            sendEdit(environment)
            return
        }
        val text = draft.value.trim()
        val lounge = _lounge.value
        val router = environment.router.value
        if (text.isEmpty() || lounge == null || router == null) return
        if (MeshRouterActor.containsLink(text)) {
            infoMessage.value = context.getString(R.string.chat_lounge_links_blocked)
            return
        }
        if (ContentFilter.containsProfanity(text)) {
            infoMessage.value = context.getString(R.string.lounge11_profanity_blocked)
            Haptics.warning()
            return
        }
        draft.value = ""
        scope.launch {
            try {
                router.sendText(text, conversation = lounge)
                Haptics.impact(Haptics.ImpactStyle.light)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val description = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.toString()
                infoMessage.value = context.getString(R.string.chat_send_failed, description)
            }
        }
    }

    // MARK: Modifica e cancellazione per tutti

    fun startEditing(message: MessageRecord) {
        if (!message.canBeEdited()) {
            infoMessage.value = context.getString(R.string.revisions_window_expired)
            return
        }
        if (_editingMessage.value == null) draftBeforeEditing = draft.value
        _editingMessage.value = message
        draft.value = message.text ?: ""
    }

    fun cancelEditing() {
        if (_editingMessage.value == null) return
        _editingMessage.value = null
        draft.value = draftBeforeEditing
        draftBeforeEditing = ""
    }

    /** Stesse regole dell'invio nella Cabina: niente link, niente parolacce (rifiuto in invio). */
    private fun sendEdit(environment: AppEnvironment) {
        val message = _editingMessage.value ?: return
        val lounge = _lounge.value ?: return
        val text = draft.value.trim()
        if (text.isEmpty()) return
        if (MeshRouterActor.containsLink(text)) {
            infoMessage.value = context.getString(R.string.chat_lounge_links_blocked)
            return
        }
        if (ContentFilter.containsProfanity(text)) {
            infoMessage.value = context.getString(R.string.lounge11_profanity_blocked)
            Haptics.warning()
            return
        }
        _editingMessage.value = null
        draft.value = draftBeforeEditing
        draftBeforeEditing = ""
        if (text == message.text?.trim()) return
        scope.launch {
            try {
                environment.editMessage(message, newText = text, conversation = lounge)
                Haptics.impact(Haptics.ImpactStyle.light)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                infoMessage.value = revisionErrorText(e)
            }
        }
    }

    fun deleteForEveryone(message: MessageRecord, environment: AppEnvironment) {
        val lounge = _lounge.value ?: return
        if (_editingMessage.value?.msgID?.contentEquals(message.msgID) == true) cancelEditing()
        scope.launch {
            try {
                environment.deleteMessageForEveryone(message, conversation = lounge)
                Haptics.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                infoMessage.value = revisionErrorText(e)
            }
        }
    }

    private fun revisionErrorText(error: Exception): String =
        if (error is RouterError.RevisionNotAllowed) {
            context.getString(R.string.revisions_window_expired)
        } else {
            val description = error.localizedMessage?.takeIf { it.isNotBlank() } ?: error.toString()
            context.getString(R.string.revisions_failed, description)
        }

    fun block(nodeID: ByteArray, environment: AppEnvironment) {
        val hex = nodeID.toHex()
        blocked = blocked + hex
        // Va letto prima di rimuovere i messaggi, altrimenti il nickname andrebbe perso.
        val nickname = _messages.value.firstOrNull { it.senderNodeID.contentEquals(nodeID) }?.senderNickname
        _messages.value = _messages.value.filterNot { it.senderNodeID.contentEquals(nodeID) }
        lastMessages = _messages.value
        scope.launch {
            // Il mittente potrebbe non essere ancora tra i peer (Lounge anonima): lo registra come bloccato.
            environment.blockUser(nodeID = nodeID, nickname = nickname)
            Haptics.warning()
        }
    }

    /** Dopo una segnalazione con blocco i messaggi del mittente spariscono subito dalla bacheca. */
    fun didReport(report: ReportRecord, didBlock: Boolean) {
        infoMessage.value = context.getString(R.string.lounge11_report_thanks)
        if (!didBlock) return
        val nodeID = report.reportedNodeID
        blocked = blocked + nodeID.toHex()
        _messages.value = _messages.value.filterNot { it.senderNodeID.contentEquals(nodeID) }
        lastMessages = _messages.value
    }
}

/**
 * Ultima posizione di lettura della Cabina (come WhatsApp, e come `CabinScrollMemory` su iOS): primo
 * messaggio visibile in alto + scostamento, oppure "in fondo" (`topMessageID == null`). Legata alla Lounge
 * corrente (ogni 12 ore ne nasce una nuova); sopravvive al cambio di tab e al riavvio dell'app.
 */
internal object CabinScrollMemory {

    data class Position(val topMessageID: String?, val offset: Int)

    private const val prefs = "cabin_scroll"

    fun load(context: Context, loungeID: String): Position? {
        val store = context.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        if (store.getString("lounge_id", null) != loungeID) return null
        return Position(store.getString("top_msg_id", null), store.getInt("offset", 0))
    }

    fun save(context: Context, loungeID: String, topMessageID: String?, offset: Int) {
        context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit()
            .putString("lounge_id", loungeID)
            .putString("top_msg_id", topMessageID)
            .putInt("offset", if (topMessageID == null) 0 else offset)
            .apply()
    }
}

/** Tab 2 – Cabina Pubblica: bacheca anonima di 12 ore, solo testo, blocco rapido degli utenti. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CabinLoungeView() {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val viewModel = remember { CabinLoungeViewModel(context) }
    val database by environment.database.collectAsStateWithLifecycle()
    val neighbors by environment.neighbors.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val lounge by viewModel.lounge.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val editingMessage by viewModel.editingMessage.collectAsStateWithLifecycle()
    val infoMessage by viewModel.infoMessage.collectAsStateWithLifecycle()
    val quizState by environment.quizState.collectAsStateWithLifecycle()
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    // Regole della Cabina: finche' non sono accettate, il foglio si apre a ogni visita della bacheca.
    var showRules by remember { mutableStateOf(!AppSettings.hasAcceptedLoungeRules) }
    var showQuiz by remember { mutableStateOf(false) }
    var showRadar by remember { mutableStateOf(false) }
    var showEmergency by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    /** Messaggio in corso di segnalazione. */
    var reportTarget by remember { mutableStateOf<MessageRecord?>(null) }

    // Ciclo di vita (STARTED): in background l'osservatore Room ri-interrogava 500 messaggi a ogni
    // scrittura sulla tabella `messages` (anche di altre chat), con lo schermo spento.
    LifecycleStartEffect(viewModel, database) {
        viewModel.start(environment)
        onStopOrDispose { viewModel.stop() }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.dispose() } }

    // Il conto alla rovescia si aggiorna ogni minuto.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) { if (isDragged) focusManager.clearFocus() }

    // Posizione di lettura come WhatsApp: la tab viene ricreata a ogni cambio, quindi al primo
    // caricamento si torna all'ultima posizione nota (o in fondo) prima di mostrare la lista.
    var restoredPosition by remember { mutableStateOf(false) }
    /** L'utente vede la fine della chat (ultimo o penultimo messaggio visibile). */
    fun isNearBottom(lastIndex: Int): Boolean {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
        return lastVisible.index >= lastIndex
    }
    val lastID = messages.lastOrNull()?.msgID?.toHex()
    // Anche sull'id della Lounge: con i messaggi gia' noti, la Lounge puo' arrivare dopo l'ultimo messaggio.
    LaunchedEffect(lastID, lounge?.id) {
        val loungeID = lounge?.id
        if (lastID == null || loungeID == null) return@LaunchedEffect
        if (!restoredPosition) {
            val saved = CabinScrollMemory.load(context, loungeID)
            val index = saved?.topMessageID?.let { id -> messages.indexOfFirst { it.msgID.toHex() == id } } ?: -1
            if (saved != null && index >= 0) {
                listState.scrollToItem(index, saved.offset)
            } else {
                listState.scrollToItem(messages.lastIndex)
            }
            restoredPosition = true
            return@LaunchedEffect
        }
        // Nuovo messaggio: si scende solo se l'utente era in fondo (prima dell'arrivo: penultimo visibile)
        // o se l'ha scritto lui; chi sta rileggendo i vecchi messaggi resta dov'e'.
        if (isNearBottom(messages.lastIndex - 1) || messages.last().isOutgoing) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    // Salva la posizione mentre si scorre (dopo il ripristino, altrimenti salverebbe quella provvisoria).
    LaunchedEffect(restoredPosition, lounge?.id) {
        val loungeID = lounge?.id
        if (!restoredPosition || loungeID == null) return@LaunchedEffect
        // Solo a scorrimento fermo: l'offset cambia a ogni frame e ogni `apply()` di SharedPreferences
        // accoda una scrittura su disco (attesa poi in onPause/onStop → jank o ANR dopo un fling).
        snapshotFlow {
            if (listState.isScrollInProgress) null
            else Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.layoutInfo.totalItemsCount)
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (index, offset, _) ->
                val current = viewModel.messages.value
                val atBottom = current.isEmpty() || isNearBottom(current.lastIndex)
                val top = current.getOrNull(index)?.msgID?.toHex()
                CabinScrollMemory.save(context, loungeID, if (atBottom) null else top, offset)
            }
    }

    Scaffold(
        containerColor = AeroTheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AeroTheme.background,
                    titleContentColor = AeroTheme.textPrimary,
                ),
                title = { Text(stringResource(R.string.chat_lounge_title), style = AeroTheme.title(28f)) },
                actions = {
                    // Radar sempre a portata di tocco: e' il modo piu' rapido per ritrovare un compagno in cabina.
                    IconButton(onClick = { Haptics.tap(); showRadar = true }) {
                        Icon(
                            Icons.Filled.Radar,
                            contentDescription = stringResource(R.string.lounge11_radar_menu),
                            tint = AeroTheme.accentCyan,
                        )
                    }
                    QuizToolbarButton(state = quizState, onClick = {
                        Haptics.tap()
                        showQuiz = true
                    })
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.hapticOnTap()) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.lounge11_more_actions),
                                tint = AeroTheme.accentCyan,
                            )
                        }
                        AeroDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            items = listOf(
                                AeroMenuItem(
                                    stringResource(R.string.lounge11_emergency_title),
                                    Icons.Outlined.WarningAmber,
                                    hapticOnTap = true,
                                ) { showMenu = false; showEmergency = true },
                                AeroMenuItem(
                                    stringResource(R.string.lounge11_rules_menu),
                                    Icons.Outlined.Info,
                                    dividerBefore = true,
                                    hapticOnTap = true,
                                ) { showMenu = false; showRules = true },
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(AeroTheme.background)) {
            // La tab bar resta sotto la Lounge: la tastiera solleva il composer solo della parte che la supera.
            val density = LocalDensity.current
            var bottomGap by remember { mutableStateOf(0.dp) }
            Column(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        val rootHeight = coordinates.findRootCoordinates().size.height
                        val gap = with(density) { (rootHeight - coordinates.boundsInRoot().bottom).coerceAtLeast(0f).toDp() }
                        if (gap != bottomGap) bottomGap = gap
                    }
                    .consumeWindowInsets(PaddingValues(bottom = bottomGap))
                    .imePadding(),
            ) {
                LoungeHeader(
                    neighborCount = neighbors.size,
                    expiryText = lounge?.let { viewModel.expiryText(now) } ?: "",
                    onOpenRadar = {
                        Haptics.tap()
                        showRadar = true
                    },
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Invisibile finche' non e' alla posizione giusta: niente lampo dall'alto della chat.
                        .alpha(if (restoredPosition || (loaded && messages.isEmpty())) 1f else 0f)
                        .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Solo a caricamento avvenuto: prima "vuoto" vuol dire solo "non ancora letto".
                    if (loaded && messages.isEmpty()) {
                        item(key = "empty") { EmptyHint() }
                    }
                    items(messages, key = { it.msgID.toHex() }) { message ->
                        LoungeMessage(
                            message,
                            onReport = {
                                Haptics.tap()
                                reportTarget = message
                            },
                            onBlock = {
                                Haptics.tap()
                                viewModel.block(message.senderNodeID, environment)
                            },
                            onEdit = { viewModel.startEditing(message) },
                            onDeleteForEveryone = { viewModel.deleteForEveryone(message, environment) },
                        )
                    }
                }
                ComposerBar(
                    text = draft,
                    onTextChange = { viewModel.draft.value = it },
                    recorder = null,
                    showsAttachments = false,
                    onSend = { viewModel.send(environment) },
                    onToggleRecording = {},
                    onStartGame = {},
                    isEditing = editingMessage != null,
                    onCancelEditing = viewModel::cancelEditing,
                )
            }
            InfoBanner(
                message = infoMessage,
                onExpire = { viewModel.infoMessage.value = null },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    if (showQuiz) CabinQuizSheet(onDismiss = { showQuiz = false })
    if (showRadar) RadarSheet(onDismiss = { showRadar = false })
    if (showEmergency) EmergencySheet(onDismiss = { showEmergency = false })
    reportTarget?.let { message ->
        ReportSheet(
            message = message,
            onDismiss = { reportTarget = null },
            onReported = { report, didBlock -> viewModel.didReport(report, didBlock) },
        )
    }
    // Prima visita: regole della bacheca da accettare prima di leggere o scrivere.
    if (showRules) {
        LoungeRulesSheet(
            requiresAcceptance = !settings.hasAcceptedLoungeRules,
            onAccept = { AppSettings.acceptLoungeRules() },
            onDismiss = { showRules = false },
        )
    }
}

/** Pulsante del quiz nella barra: pieno e verde con un quiz in corso, pulsa durante una domanda. */
@Composable
private fun QuizToolbarButton(state: QuizService.State?, onClick: () -> Unit) {
    val pulsing = state?.phase == QuizService.Phase.question
    val transition = rememberInfiniteTransition(label = "quizPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "quizPulseAlpha",
    )
    IconButton(onClick = onClick) {
        Icon(
            if (state == null) Icons.Outlined.Quiz else Icons.Filled.Quiz,
            contentDescription = stringResource(R.string.lounge11_quiz_title),
            tint = if (state == null) AeroTheme.accentCyan else AeroTheme.accentEmerald,
            modifier = Modifier.alpha(if (pulsing) pulse else 1f),
        )
    }
}

/** Bolla della Lounge con il menu a pressione prolungata "Blocca / Silenzia" (Swift `.contextMenu`). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LoungeMessage(
    message: MessageRecord,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    onEdit: () -> Unit,
    onDeleteForEveryone: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    // Messaggio cancellato dal mittente: niente segnala / blocca su un segnaposto.
    val hasSafetyMenu = !message.isOutgoing && !message.isDeleted
    Box {
        MessageBubble(
            message = message,
            showSender = true,
            store = null,
            player = null,
            isPlaying = false,
            transferProgress = null,
            selectableText = message.isOutgoing,
            // Propri messaggi: "Modifica" ed "Elimina per tutti" entro 15 minuti (menu della bolla).
            onEdit = if (message.isOutgoing) onEdit else null,
            onDeleteForEveryone = if (message.isOutgoing) onDeleteForEveryone else null,
            modifier = if (!hasSafetyMenu) Modifier else Modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = {
                    Haptics.impact(Haptics.ImpactStyle.medium)
                    showMenu = true
                },
            ),
        )
        if (hasSafetyMenu) {
            AeroDropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                items = listOf(
                    AeroMenuItem(stringResource(R.string.lounge11_report_message), Icons.Outlined.Flag) {
                        showMenu = false
                        onReport()
                    },
                    AeroMenuItem(
                        stringResource(R.string.chat_block_mute, message.senderNickname ?: "utente"),
                        Icons.Filled.PanTool,
                        destructive = true,
                    ) {
                        showMenu = false
                        onBlock()
                    },
                ),
            )
        }
    }
}

@Composable
private fun LoungeHeader(neighborCount: Int, expiryText: String, onOpenRadar: () -> Unit) {
    val radarHint = stringResource(R.string.lounge11_open_radar_hint)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Numero dei vicini: un tocco apre il radar di prossimita'.
        Row(
            modifier = Modifier.clickable(onClickLabel = radarHint, onClick = onOpenRadar),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Filled.SettingsInputAntenna,
                contentDescription = null,
                tint = AeroTheme.textSecondary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                // Android ignora quantity="zero" (it/en/...): lo zero ha una stringa propria, come la variante "zero" di iOS.
                if (neighborCount == 0) stringResource(R.string.chat_passengers_in_range_zero)
                else pluralStringResource(R.plurals.chat_passengers_in_range, neighborCount, neighborCount),
                style = AeroTheme.caption,
                color = AeroTheme.textSecondary,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(expiryText, style = AeroTheme.caption, color = AeroTheme.textSecondary)
    }
}

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Forum, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(40.dp))
        Text(
            stringResource(R.string.chat_lounge_empty_hint),
            style = AeroTheme.footnote,
            color = AeroTheme.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
