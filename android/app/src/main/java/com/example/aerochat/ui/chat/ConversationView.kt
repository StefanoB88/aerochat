package com.example.aerochat.ui.chat

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aerochat.R
import com.example.aerochat.app.AppEnvironment
import com.example.aerochat.app.LocalAppEnvironment
import com.example.aerochat.core.entitlements.FeatureDecision
import com.example.aerochat.core.entitlements.FeatureGate
import com.example.aerochat.core.entitlements.ProFeature
import com.example.aerochat.core.games.GameService
import com.example.aerochat.core.games.GameSessionState
import com.example.aerochat.data.records.GroupMemberRecord
import com.example.aerochat.ui.games.CardTableDialog
import com.example.aerochat.ui.games.ScoponePlayerPicker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.example.aerochat.core.media.ImageProcessor
import com.example.aerochat.core.media.MediaStore
import com.example.aerochat.core.media.MediaTransferService
import com.example.aerochat.core.media.PhotoLibrarySaver
import com.example.aerochat.core.media.VoiceNotePlayer
import com.example.aerochat.core.media.VoiceNoteRecorder
import com.example.aerochat.core.mesh.MeshRouterActor
import com.example.aerochat.core.mesh.NotificationPresence
import com.example.aerochat.core.mesh.Reachability
import com.example.aerochat.core.mesh.RouterError
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.ConversationType
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.MessageRecord
import com.example.aerochat.data.records.PeerRecord
import com.example.aerochat.proto.GameKind
import com.example.aerochat.ui.AeroRoutes
import com.example.aerochat.ui.AeroTheme
import com.example.aerochat.ui.LocalNavController
import com.example.aerochat.ui.components.AvatarView
import com.example.aerochat.ui.components.ComposerBar
import com.example.aerochat.ui.components.GameBubbleAction
import com.example.aerochat.ui.components.Haptics
import com.example.aerochat.ui.components.ImageViewer
import com.example.aerochat.ui.components.MessageBubble
import com.example.aerochat.ui.components.PendingPhotoBubble
import com.example.aerochat.ui.components.RadioStatusView
import com.example.aerochat.ui.components.rememberMicrophonePermission
import com.example.aerochat.ui.groups.GroupInfoSheet
import com.example.aerochat.ui.pairing.QRPairingSheet
import com.example.aerochat.ui.paywall.PaywallView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Stato e azioni della chat aperta (Swift `@Observable ConversationViewModel`): ogni proprieta'
 * osservabile e' uno [StateFlow] con lo stesso nome. Vive finche' la schermata e' composta
 * ([start] / [stop] seguono la sua comparsa e scomparsa).
 */
class ConversationViewModel(
    conversation: ConversationRecord,
    private val environment: AppEnvironment,
    context: Context,
) {
    private val context: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _conversation = MutableStateFlow(conversation)
    val conversation: StateFlow<ConversationRecord> = _conversation.asStateFlow()
    private val _messages = MutableStateFlow<List<MessageRecord>>(emptyList())
    val messages: StateFlow<List<MessageRecord>> = _messages.asStateFlow()
    private val _reachability = MutableStateFlow<Reachability>(Reachability.OutOfRange)
    val reachability: StateFlow<Reachability> = _reachability.asStateFlow()
    val draft = MutableStateFlow("")
    val errorMessage = MutableStateFlow<String?>(null)
    val infoMessage = MutableStateFlow<String?>(null)
    val showPaywall = MutableStateFlow(false)
    val showGroupInvite = MutableStateFlow(false)
    /** Foto aperta nel visore a schermo intero. */
    val viewedImage = MutableStateFlow<MessageRecord?>(null)
    val player = VoiceNotePlayer(this.context)
    val recorder = VoiceNoteRecorder(this.context)
    private val _playingMessageID = MutableStateFlow<String?>(null)
    /** `msg_id` (hex) della nota vocale caricata nel player. */
    val playingMessageID: StateFlow<String?> = _playingMessageID.asStateFlow()
    /** Foto scelte ma ancora in lettura/compressione: mostrate come segnaposto con spinner. */
    private val _pendingPhotos = MutableStateFlow<List<UUID>>(emptyList())
    val pendingPhotos: StateFlow<List<UUID>> = _pendingPhotos.asStateFlow()
    /** Compagno della chat 1-a-1 (foto profilo e colore nell'header). */
    private val _peer = MutableStateFlow<PeerRecord?>(null)
    val peer: StateFlow<PeerRecord?> = _peer.asStateFlow()
    /** Membri del gruppo (noi esclusi): servono per proporre e organizzare lo Scopone. */
    private val _groupMembers = MutableStateFlow<List<GroupMemberRecord>>(emptyList())
    val groupMembers: StateFlow<List<GroupMemberRecord>> = _groupMembers.asStateFlow()
    /** Scelta dei 3 giocatori dello Scopone. */
    val showScoponePicker = MutableStateFlow(false)
    /** Partita a carte aperta a schermo intero (`gameID`). */
    val openedCardTable = MutableStateFlow<String?>(null)
    /**
     * Stato della partita aperta al tavolo, decodificato fuori dal main thread a ogni aggiornamento del
     * messaggio di gioco.
     */
    val openedCardTableState: StateFlow<GameSessionState?> = combine(openedCardTable, _messages) { id, list ->
        id?.let { gameID -> list.firstOrNull { it.msgID.toHex() == gameID }?.gameState }
    }
        .distinctUntilChanged { old, new -> old.contentEqualsNullable(new) }
        .map { data -> data?.let { runCatching { GameSessionState.decode(it) }.getOrNull() } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Richiesta del permesso microfono (impostata dalla UI: su Android il dialogo richiede l'Activity).
     * Restituisce `true` se concesso.
     */
    var requestMicrophonePermission: suspend () -> Boolean = { recorder.hasPermission() }

    private val tasks = mutableListOf<Job>()

    init {
        // Limite dei 120 s: la nota registrata parte comunque, come con il tap su "ferma".
        recorder.onAutoStop = { recording -> sendVoiceNote(recording) }
    }

    val isGroup: Boolean get() = _conversation.value.type == ConversationType.group
    val store: MediaStore? get() = environment.mediaStore.value
    val showsAttachments: Boolean get() = _conversation.value.type != ConversationType.cabinLounge

    fun start() {
        if (tasks.isNotEmpty()) return
        val database = environment.database.value ?: return
        val id = _conversation.value.id
        NotificationPresence.visibleConversationID = id
        tasks += scope.launch {
            database.messages.observe(conversationID = id).collect { list ->
                _messages.value = list
                // Fuori dal collector: la conferma di lettura scrive sul DB e manda READ_RECEIPT via BLE,
                // e teneva fermo il flusso dei messaggi (e l'apertura della chat) finche' non finiva.
                scheduleMarkRead()
            }
        }
        _conversation.value.peerNodeID?.let { peerID ->
            tasks += scope.launch {
                database.peers.observe(nodeID = peerID).collect { updated ->
                    if (updated != _peer.value) _peer.value = updated
                }
            }
        }
        if (_conversation.value.type == ConversationType.group) {
            tasks += scope.launch {
                database.conversations.observeGroupMembers(of = id).collect { list ->
                    val me = environment.identityKeys.value?.nodeID
                    val others = list.filter { me == null || !it.nodeID.contentEquals(me) }
                    if (others != _groupMembers.value) _groupMembers.value = others
                }
            }
        }
        tasks += scope.launch {
            database.conversations.observe(id = id).collect { updated ->
                if (updated != null) _conversation.value = updated
            }
        }
        tasks += scope.launch {
            while (isActive) {
                val router = environment.router.value
                if (router != null) {
                    // Assegnare solo se cambia: ogni scrittura ricompone l'header della chat.
                    val updated = router.reachability(of = _conversation.value)
                    if (updated != _reachability.value) _reachability.value = updated
                }
                delay(3_000)
            }
        }
    }

    /**
     * App in background (ON_STOP): ferma osservatori Room e polling della raggiungibilita' (ogni 3 s sul
     * router), ma non la nota vocale in riproduzione. Riprendono con [start] al ritorno in primo piano.
     */
    fun stopObserving() {
        if (NotificationPresence.visibleConversationID == _conversation.value.id) {
            NotificationPresence.visibleConversationID = null
        }
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    fun stop() {
        stopObserving()
        player.stop()
        recorder.cancel()
    }

    /** Da chiamare quando la schermata esce definitivamente dalla composizione. */
    fun dispose() {
        stop()
        scope.cancel()
    }

    private var markReadJob: Job? = null

    /** Una sola conferma di lettura in volo, con un breve ritardo che accorpa le emissioni a raffica. */
    fun scheduleMarkRead() {
        if (markReadJob?.isActive == true) return
        markReadJob = scope.launch {
            delay(250)
            markReadIfVisible()
        }
    }

    /** Conferma di lettura solo se l'utente vede davvero la chat: non a schermo bloccato o in background. */
    suspend fun markReadIfVisible() {
        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (!inForeground || keyguard?.isKeyguardLocked == true) return
        val conversation = _conversation.value
        val hasUnread = _messages.value.any { !it.isOutgoing && it.deliveryStatus != DeliveryStatus.read }
        if (!hasUnread && conversation.unreadCount <= 0) return
        environment.markConversationRead(conversation)
    }

    // MARK: Invio

    fun sendText() {
        val text = draft.value.trim()
        val router = environment.router.value
        if (text.isEmpty() || router == null) return
        val conversation = _conversation.value
        if (conversation.type == ConversationType.cabinLounge && MeshRouterActor.containsLink(text)) {
            infoMessage.value = context.getString(R.string.chat_lounge_no_links)
            return
        }
        draft.value = ""
        scope.launch {
            try {
                router.sendText(text, conversation = conversation)
                Haptics.impact(Haptics.ImpactStyle.light)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = context.getString(R.string.chat_send_failed, e.describe())
            }
        }
    }

    fun sendPhoto(uri: Uri) {
        if (!allows(ProFeature.photo)) return
        val conversation = _conversation.value
        val pendingID = UUID.randomUUID()
        _pendingPhotos.update { it + pendingID }
        scope.launch {
            // Il segnaposto sparisce quando compare la bolla vera (o in caso di errore).
            try {
                // NonCancellable: uscire dalla chat (scope del view model cancellato) mentre la foto e' in
                // compressione o in invio la troncava (header senza chunk, o foto mai partita). Su iOS il
                // `Task {}` non strutturato sopravvive alla vista: la foto parte comunque.
                withContext(NonCancellable) {
                    // Il servizio media nasce poco dopo l'avvio della mesh: attendiamolo invece di ignorare
                    // in silenzio la foto scelta (prima: `?: return`, nessun feedback all'utente).
                    val media = environment.media.value
                        ?: withTimeoutOrNull(10_000) { environment.media.filterNotNull().first() }
                        ?: throw RouterError.NotRunning
                    val data = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } ?: throw ImageProcessor.Failure.unreadableImage
                    val prepared = withContext(Dispatchers.Default) {
                        try {
                            ImageProcessor.prepareForMesh(imageData = data)
                        } catch (_: OutOfMemoryError) {
                            // Foto enorme su un telefono con poca memoria: errore mostrato, niente crash.
                            throw ImageProcessor.Failure.unreadableImage
                        }
                    }
                    _pendingPhotos.update { list -> list - pendingID }
                    media.sendImage(prepared, conversation = conversation)
                }
                Haptics.impact(Haptics.ImpactStyle.medium)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaTransferService.Failure.recipientOutOfRange) {
                infoMessage.value = context.getString(R.string.chat_photo_out_of_range)
            } catch (e: Exception) {
                errorMessage.value = context.getString(R.string.chat_photo_not_sent, e.describe())
            } finally {
                _pendingPhotos.update { list -> list - pendingID }
            }
        }
    }

    fun toggleRecording() {
        if (recorder.isRecording.value) {
            scope.launch {
                try {
                    // Il microfono si ferma subito (main thread, scope `immediate`); la codifica Opus gira
                    // su Dispatchers.Default: sul main thread una nota lunga bloccava la UI per secondi (ANR).
                    val recording = withContext(NonCancellable) { recorder.stopAndEncode() }
                    sendVoiceNote(recording)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VoiceNoteRecorder.Failure.tooShort) {
                    infoMessage.value = context.getString(R.string.chat_note_too_short)
                } catch (e: Exception) {
                    errorMessage.value = context.getString(R.string.chat_recording_failed, e.describe())
                }
            }
            return
        }
        if (!allows(ProFeature.voiceNote)) return
        scope.launch {
            if (!requestMicrophonePermission()) {
                infoMessage.value = context.getString(R.string.chat_allow_microphone_notes)
                return@launch
            }
            try {
                recorder.start()
                Haptics.impact(Haptics.ImpactStyle.rigid)
            } catch (e: Exception) {
                errorMessage.value = context.getString(R.string.chat_microphone_unavailable, e.describe())
            }
        }
    }

    fun saveImage(message: MessageRecord) {
        scope.launch {
            val (text, isError) = saveImageToLibrary(message) ?: return@launch
            if (isError) errorMessage.value = text else infoMessage.value = text
        }
    }

    /**
     * Salvataggio in galleria condiviso da menu della bolla e visore a schermo intero: restituisce
     * il testo da mostrare e se e' un errore (il visore lo presenta sopra di se').
     */
    suspend fun saveImageToLibrary(message: MessageRecord): Pair<String, Boolean>? {
        val path = message.mediaLocalPath ?: return null
        val store = store ?: return null
        return try {
            val data = withContext(Dispatchers.IO) { store.read(relativePath = path) }
            PhotoLibrarySaver.save(context, imageData = data)
            Haptics.success()
            context.getString(R.string.chat_photo_saved) to false
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhotoLibrarySaver.Failure.permissionDenied) {
            context.getString(R.string.chat_allow_photos_save) to false
        } catch (e: Exception) {
            context.getString(R.string.chat_photo_not_saved, e.describe()) to true
        }
    }

    private fun sendVoiceNote(recording: VoiceNoteRecorder.Recording) {
        val conversation = _conversation.value
        scope.launch {
            try {
                // Come per le foto: la nota registrata non va persa in silenzio (prima `?: return`) se il
                // servizio media non e' ancora nato, ne' troncata se si esce dalla chat durante l'invio.
                withContext(NonCancellable) {
                    val media = environment.media.value
                        ?: withTimeoutOrNull(10_000) { environment.media.filterNotNull().first() }
                    if (media == null) {
                        errorMessage.value = context.getString(
                            R.string.chat_voice_not_sent, context.getString(R.string.chat_media_not_ready),
                        )
                        return@withContext
                    }
                    media.sendVoiceNote(recording, conversation = conversation)
                    Haptics.impact(Haptics.ImpactStyle.medium)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaTransferService.Failure.recipientOutOfRange) {
                infoMessage.value = context.getString(R.string.chat_voice_out_of_range)
            } catch (e: Exception) {
                errorMessage.value = context.getString(R.string.chat_voice_not_sent, e.describe())
            }
        }
    }

    // MARK: Chiamate

    /** Pulsante attivo (aspetto) solo quando una chiamata e' davvero possibile adesso. */
    fun canCallNow(reachability: Reachability = _reachability.value): Boolean =
        FeatureGate.decision(
            feature = ProFeature.call, userIsPro = environment.isPro,
            conversation = _conversation.value, reachability = reachability,
        ) == FeatureDecision.allowed

    fun startCall() {
        val decision = FeatureGate.decision(
            feature = ProFeature.call, userIsPro = environment.isPro,
            conversation = _conversation.value, reachability = _reachability.value,
        )
        when (decision) {
            FeatureDecision.allowed -> {
                val calls = environment.calls.value ?: return
                Haptics.impact(Haptics.ImpactStyle.medium)
                val conversation = _conversation.value
                scope.launch {
                    // Il servizio chiude da solo con `microphoneDenied` se il permesso resta negato.
                    requestMicrophonePermission()
                    calls.placeCall(conversation)
                }
            }
            FeatureDecision.peerOutOfRange ->
                infoMessage.value = context.getString(R.string.chat_call_out_of_range)
            else -> allows(ProFeature.call)
        }
    }

    // MARK: Giochi

    /** Giochi del menu: nelle chat 1-a-1 tutti quelli a 2; nei gruppi da almeno 4 (noi compresi) solo lo Scopone. */
    fun availableGames(conversation: ConversationRecord, members: List<GroupMemberRecord>): List<GameKind> =
        when (conversation.type) {
            ConversationType.direct -> listOf(GameKind.TIC_TAC_TOE, GameKind.BATTLESHIP, GameKind.SCOPA, GameKind.BRISCOLA)
            ConversationType.group ->
                if (members.size + 1 >= 4) listOf(GameKind.SCOPONE) else listOf(GameKind.TIC_TAC_TOE, GameKind.BATTLESHIP)
            else -> emptyList()
        }

    fun startGame(kind: GameKind) {
        if (!allows(ProFeature.game)) return
        val games = environment.games.value ?: return
        if (kind == GameKind.SCOPONE) {
            showScoponePicker.value = true
            return
        }
        val conversation = _conversation.value
        scope.launch {
            try {
                games.invite(kind = kind, conversation = conversation)
                Haptics.impact()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GameService.Failure.directConversationOnly) {
                infoMessage.value = context.getString(R.string.chat_games_direct_only)
            } catch (e: Exception) {
                errorMessage.value = context.getString(R.string.chat_challenge_not_sent, e.describe())
            }
        }
    }

    /** Scopone: invito ai 3 membri scelti (in ordine di posto 1..3). */
    fun inviteScopone(players: List<ByteArray>) {
        val games = environment.games.value ?: return
        val conversation = _conversation.value
        scope.launch {
            try {
                games.invite(kind = GameKind.SCOPONE, conversation = conversation, players = players)
                Haptics.impact()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GameService.Failure.invalidPlayers) {
                infoMessage.value = context.getString(R.string.games_pick_exactly_three)
            } catch (e: Exception) {
                errorMessage.value = context.getString(R.string.chat_challenge_not_sent, e.describe())
            }
        }
    }

    fun handleGame(action: GameBubbleAction, message: MessageRecord) = handleGame(action, gameID = message.msgID.toHex())

    fun handleGame(action: GameBubbleAction, gameID: String) {
        if (action == GameBubbleAction.OpenTable) {
            Haptics.tap()
            openedCardTable.value = gameID
            // Se il DEAL che toccava a noi non e' mai partito, l'apertura del tavolo lo ritenta.
            environment.games.value?.let { games -> scope.launch { runCatching { games.resume(gameID) } } }
            return
        }
        val games = environment.games.value
        if (games == null) {
            infoMessage.value = context.getString(R.string.chat_invalid_move, "mesh")
            return
        }
        scope.launch {
            try {
                when (action) {
                    GameBubbleAction.Accept -> games.accept(gameID = gameID)
                    is GameBubbleAction.Play -> games.play(gameID = gameID, cell = action.cell)
                    GameBubbleAction.Resign -> games.resign(gameID = gameID)
                    is GameBubbleAction.PlayCard -> games.playCard(gameID = gameID, card = action.card, captured = action.captured)
                    GameBubbleAction.OpenTable -> return@launch
                }
                Haptics.impact(Haptics.ImpactStyle.light)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GameService.Failure.notYourTurn) {
                infoMessage.value = context.getString(R.string.chat_not_your_turn)
            } catch (e: GameService.Failure.invalidMove) {
                Haptics.error()
                infoMessage.value = context.getString(R.string.games_invalid_move)
            } catch (e: Exception) {
                errorMessage.value = context.getString(R.string.chat_invalid_move, e.describe())
            }
        }
    }

    // MARK: Audio

    fun togglePlayback(message: MessageRecord) {
        val path = message.mediaLocalPath ?: return
        val store = store ?: return
        val id = message.msgID.toHex()
        if (_playingMessageID.value == id && player.isPlaying.value) {
            player.pause()
            return
        }
        scope.launch {
            try {
                if (_playingMessageID.value != id) {
                    val data = withContext(Dispatchers.IO) { store.read(relativePath = path) }
                    player.load(oggOpus = data, mediaPath = path)
                    _playingMessageID.value = id
                }
                player.play()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage.value = context.getString(R.string.chat_playback_failed, e.describe())
            }
        }
    }

    // MARK: Gate

    /** Applica il modello Freemium e spiega all'utente cosa manca. */
    fun allows(feature: ProFeature): Boolean {
        val decision = FeatureGate.decision(
            feature = feature, userIsPro = environment.isPro,
            conversation = _conversation.value, reachability = _reachability.value,
        )
        val info = when (decision) {
            FeatureDecision.allowed -> return true
            FeatureDecision.requiresPass -> {
                showPaywall.value = true
                null
            }
            FeatureDecision.photosNeedDirectLink -> R.string.chat_gate_photos_need_direct
            FeatureDecision.peerOutOfRange -> R.string.chat_gate_peer_out_of_range
            FeatureDecision.loungeTextOnly -> R.string.chat_gate_lounge_text_only
            FeatureDecision.callNeedsDirectLink -> R.string.chat_gate_call_needs_direct
            FeatureDecision.callSignalTooWeak -> R.string.chat_gate_call_signal_weak
        }
        if (info != null) infoMessage.value = context.getString(info)
        return false
    }
}

/** Swift `error.localizedDescription`. */
internal fun Throwable.describe(): String = localizedMessage?.takeIf { it.isNotBlank() } ?: toString()

/** Schermata chat con bolle BlurHash / forma d'onda / giochi e indicatore "Stato Radio" nell'header. */
@Composable
fun ConversationView(conversation: ConversationRecord) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val database by environment.database.collectAsStateWithLifecycle()
    val viewModel = remember(conversation.id) { ConversationViewModel(conversation, environment, context) }
    // Legato al ciclo di vita (STARTED), non alla sola composizione: con lo schermo spento o l'app in
    // background la composizione resta viva (il servizio in primo piano tiene vivo il processo) e il
    // polling della raggiungibilita' continuava all'infinito. Anche al ritorno: start e' idempotente.
    LifecycleStartEffect(viewModel, database) {
        viewModel.start()
        onStopOrDispose { viewModel.stopObserving() }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.dispose() } }
    ConversationContent(viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationContent(viewModel: ConversationViewModel) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val reachability by viewModel.reachability.collectAsStateWithLifecycle()
    val peer by viewModel.peer.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val infoMessage by viewModel.infoMessage.collectAsStateWithLifecycle()
    val showPaywall by viewModel.showPaywall.collectAsStateWithLifecycle()
    val showGroupInvite by viewModel.showGroupInvite.collectAsStateWithLifecycle()
    /** Scheda partecipanti (tocco sul nome del gruppo) e chat 1-a-1 scelta con "Scrivi". */
    var showGroupInfo by remember { mutableStateOf(false) }
    var pendingDirectChatID by remember { mutableStateOf<String?>(null) }
    val viewedImage by viewModel.viewedImage.collectAsStateWithLifecycle()
    val groupMembers by viewModel.groupMembers.collectAsStateWithLifecycle()
    val showScoponePicker by viewModel.showScoponePicker.collectAsStateWithLifecycle()
    val openedCardTable by viewModel.openedCardTable.collectAsStateWithLifecycle()
    val openedCardTableState by viewModel.openedCardTableState.collectAsStateWithLifecycle()
    // Ricomposizione quando cambia lo stato Pro (abilita il pulsante di chiamata).
    LocalAppEnvironment.current.entitlementState.collectAsStateWithLifecycle()

    // Permesso microfono chiesto al momento del bisogno (note vocali, chiamate).
    val microphone = rememberMicrophonePermission()
    SideEffect {
        viewModel.requestMicrophonePermission = {
            val result = CompletableDeferred<Boolean>()
            microphone.runWithPermission(onDenied = { result.complete(false) }) { result.complete(true) }
            result.await()
        }
    }

    // Sblocco / ritorno in primo piano con la chat aperta: ora i messaggi sono davvero letti.
    val currentViewModel by rememberUpdatedState(viewModel)
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentViewModel.scheduleMarkRead()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = AeroTheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = AeroTheme.background,
                    navigationIconContentColor = AeroTheme.accentCyan,
                    actionIconContentColor = AeroTheme.accentCyan,
                ),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = {
                    val isGroup = conversation.type == ConversationType.group
                    val showParticipantsLabel = stringResource(R.string.chat_group_show_participants)
                    Row(
                        // Gruppo: il nome apre i partecipanti.
                        modifier = if (isGroup) {
                            Modifier.clickable(onClickLabel = showParticipantsLabel, role = Role.Button) {
                                Haptics.tap()
                                showGroupInfo = true
                            }
                        } else Modifier,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (conversation.type == ConversationType.direct) {
                            AvatarView(imageData = peer?.avatarImage, colorHex = peer?.avatarColorHex, size = 32.dp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    conversation.title,
                                    style = AeroTheme.title(17f, FontWeight.SemiBold),
                                    color = AeroTheme.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (isGroup) {
                                    Icon(
                                        Icons.Filled.ChevronRight,
                                        contentDescription = null,
                                        tint = AeroTheme.textSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            RadioStatusView(reachability = reachability)
                        }
                    }
                },
                actions = {
                    if (conversation.type == ConversationType.direct) {
                        val canCall = viewModel.canCallNow(reachability)
                        val label = stringResource(R.string.chat_voice_call)
                        val hint = if (canCall) "" else stringResource(R.string.chat_call_hint)
                        IconButton(
                            onClick = { viewModel.startCall() },
                            modifier = Modifier.semantics {
                                contentDescription = label
                                if (hint.isNotEmpty()) stateDescription = hint
                            },
                        ) {
                            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.alpha(if (canCall) 1f else 0.35f))
                        }
                    }
                    if (conversation.type == ConversationType.group) {
                        IconButton(onClick = { viewModel.showGroupInvite.value = true }) {
                            Icon(Icons.Filled.QrCode, contentDescription = stringResource(R.string.chat_group_qr))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(AeroTheme.background),
        ) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                // Viste separate: i tick da 100 ms di registrazione/riproduzione ricompongono solo
                // l'indicatore e la bolla in riproduzione, non l'intera lista dei messaggi.
                MessageListView(viewModel, Modifier.weight(1f))
                ComposerBar(
                    text = draft,
                    onTextChange = { viewModel.draft.value = it },
                    recorder = viewModel.recorder,
                    showsAttachments = viewModel.showsAttachments,
                    onSend = viewModel::sendText,
                    onToggleRecording = viewModel::toggleRecording,
                    onStartGame = viewModel::startGame,
                    games = viewModel.availableGames(conversation, groupMembers),
                    onPhotoPicked = viewModel::sendPhoto,
                    canTakePhoto = { viewModel.allows(ProFeature.photo) },
                    onCameraDenied = {
                        viewModel.infoMessage.value = context.getString(R.string.chat_camera_denied)
                    },
                )
            }
            InfoBanner(
                message = infoMessage,
                onExpire = { viewModel.infoMessage.value = null },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    if (showPaywall) {
        PaywallView(onDismiss = { viewModel.showPaywall.value = false })
    }
    if (showGroupInvite) {
        QRPairingSheet(onDismiss = { viewModel.showGroupInvite.value = false }, group = conversation)
    }
    if (showGroupInfo) {
        GroupInfoSheet(
            group = conversation,
            onDismiss = {
                showGroupInfo = false
                // Push solo a scheda chiusa (come iOS): la chat 1-a-1 si apre sopra al gruppo.
                pendingDirectChatID?.let { id ->
                    pendingDirectChatID = null
                    navController.navigate(AeroRoutes.conversation(id))
                }
            },
            onOpenDirectChat = { pendingDirectChatID = it.id },
        )
    }
    viewedImage?.let { viewed ->
        val path = viewed.mediaLocalPath
        if (path != null) {
            ImageViewer(
                relativePath = path,
                store = viewModel.store,
                onSave = { viewModel.saveImageToLibrary(viewed)?.first },
                onDismiss = { viewModel.viewedImage.value = null },
            )
        }
    }
    if (openedCardTable != null) {
        val tableState = openedCardTableState
        if (tableState != null) {
            val gameID = openedCardTable.orEmpty()
            CardTableDialog(
                state = tableState,
                onAction = { viewModel.handleGame(it, gameID = gameID) },
                onDismiss = { viewModel.openedCardTable.value = null },
                statusMessage = infoMessage,
                onStatusExpire = { viewModel.infoMessage.value = null },
            )
        }
    }
    if (showScoponePicker) {
        ScoponePlayerPicker(
            members = groupMembers,
            onInvite = viewModel::inviteScopone,
            onDismiss = { viewModel.showScoponePicker.value = false },
        )
    }
    errorMessage?.let { message ->
        OopsAlert(message = message, onDismiss = { viewModel.errorMessage.value = null })
    }
}

/** Avviso "Ops" con il messaggio d'errore (Swift `.alert("Ops", ...)`). */
@Composable
internal fun OopsAlert(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AeroTheme.surface,
        titleContentColor = AeroTheme.textPrimary,
        textContentColor = AeroTheme.textSecondary,
        title = { Text(stringResource(R.string.chat_oops)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_ok), color = AeroTheme.accentCyan) }
        },
    )
}

/** Messaggio informativo in alto che sparisce da solo dopo 4 s (Swift `.overlay(alignment: .top)`). */
@Composable
internal fun InfoBanner(message: String?, onExpire: () -> Unit, modifier: Modifier = Modifier) {
    // Ultimo testo mostrato, per l'animazione di uscita.
    var shown by remember { mutableStateOf(message) }
    if (message != null) shown = message
    LaunchedEffect(message) {
        if (message != null) {
            delay(4_000)
            onExpire()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Text(
            shown.orEmpty(),
            style = AeroTheme.footnote,
            color = AeroTheme.textPrimary,
            modifier = Modifier
                .padding(16.dp)
                .background(AeroTheme.surface, RoundedCornerShape(14.dp))
                .padding(12.dp),
        )
    }
}

@Composable
private fun MessageListView(viewModel: ConversationViewModel, modifier: Modifier = Modifier) {
    val environment = LocalAppEnvironment.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val pendingPhotos by viewModel.pendingPhotos.collectAsStateWithLifecycle()
    val playingMessageID by viewModel.playingMessageID.collectAsStateWithLifecycle()
    val isPlaying by viewModel.player.isPlaying.collectAsStateWithLifecycle()
    // Letto per riga con `derivedStateOf`: ogni avanzamento di un trasferimento invalida solo la bolla
    // interessata, non tutte quelle visibili (MessageRecord non e' stabile, le bolle non si saltano).
    val mediaProgressState = environment.mediaProgress.collectAsStateWithLifecycle()
    val store by environment.mediaStore.collectAsStateWithLifecycle()
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val showSender = conversation.type == ConversationType.group || conversation.type == ConversationType.cabinLounge
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    var didInitialScroll by remember { mutableStateOf(false) }

    // Lo scroll a trascinamento chiude la tastiera (Swift `.scrollDismissesKeyboard(.interactively)`).
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) { if (isDragged) focusManager.clearFocus() }

    val lastID = messages.lastOrNull()?.msgID?.toHex()
    LaunchedEffect(lastID) {
        if (lastID == null) return@LaunchedEffect
        val index = messages.lastIndex
        // Primo caricamento senza animazione: niente scorrimento animato attraverso tutta la storia.
        if (didInitialScroll) {
            listState.animateScrollToItem(index)
        } else {
            didInitialScroll = true
            listState.scrollToItem(index)
        }
    }
    val lastPending = pendingPhotos.lastOrNull()
    LaunchedEffect(lastPending) {
        if (lastPending == null) return@LaunchedEffect
        listState.animateScrollToItem(messages.size + pendingPhotos.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.msgID.toHex() }) { message ->
            val id = message.msgID.toHex()
            val isCurrent = playingMessageID == id
            val transferProgress by remember(id) { derivedStateOf { mediaProgressState.value[id] } }
            MessageBubble(
                message = message,
                showSender = showSender,
                store = store,
                player = if (isCurrent) viewModel.player else null,
                isPlaying = isCurrent && isPlaying,
                transferProgress = transferProgress,
                onTogglePlayback = { viewModel.togglePlayback(message) },
                onGameAction = { viewModel.handleGame(it, message) },
                onSaveImage = { viewModel.saveImage(message) },
                onOpenImage = { viewModel.viewedImage.value = message },
            )
        }
        items(pendingPhotos, key = { it.toString() }) {
            PendingPhotoBubble()
        }
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this === other else contentEquals(other)
