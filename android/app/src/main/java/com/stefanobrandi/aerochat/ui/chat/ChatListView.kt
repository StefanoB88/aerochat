package com.stefanobrandi.aerochat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.AppEnvironment
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.mesh.Reachability
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.ConversationType
import com.stefanobrandi.aerochat.data.records.PeerRecord
import com.stefanobrandi.aerochat.ui.AeroRoutes
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.LocalNavController
import com.stefanobrandi.aerochat.ui.components.AvatarView
import com.stefanobrandi.aerochat.ui.components.AeroDropdownMenu
import com.stefanobrandi.aerochat.ui.components.AeroMenuItem
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.RadioStatusView
import com.stefanobrandi.aerochat.ui.components.hapticOnTap
import com.stefanobrandi.aerochat.ui.components.timeLabel
import com.stefanobrandi.aerochat.ui.flight.BoardingPassSheet
import com.stefanobrandi.aerochat.ui.flight.FlightCardSheet
import com.stefanobrandi.aerochat.ui.groups.NewGroupSheet
import com.stefanobrandi.aerochat.ui.onboarding.OnboardingView
import com.stefanobrandi.aerochat.ui.pairing.QRPairingSheet
import com.stefanobrandi.aerochat.ui.pairing.StoreQRSheet
import com.stefanobrandi.aerochat.ui.paywall.PaywallView
import com.stefanobrandi.aerochat.ui.settings.SettingsScreen
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

/** Swift `@Observable ChatListViewModel`: elenco chat permanenti, raggiungibilita' e profili dei compagni. */
class ChatListViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /**
     * `null` finche' il database non ha risposto: la vista non deve mostrare lo stato vuoto (le 2 CTA
     * giganti) mentre carica. Si parte dall'ultimo elenco noto, cosi' tornando sulla tab (che ricrea la
     * vista) le chat compaiono subito invece di lampeggiare la schermata vuota.
     */
    private val _conversations = MutableStateFlow(lastConversations)
    val conversations: StateFlow<List<ConversationRecord>?> = _conversations.asStateFlow()
    /** Per `ConversationRecord.id`. */
    private val _reachability = MutableStateFlow(lastReachability)
    val reachability: StateFlow<Map<String, Reachability>> = _reachability.asStateFlow()
    /** Per `node_id` (hex). */
    private val _peers = MutableStateFlow(lastPeers)
    val peers: StateFlow<Map<String, PeerRecord>> = _peers.asStateFlow()

    private companion object {
        // Ultimo stato visto (processo): seme della prossima istanza quando la tab viene ricreata.
        @Volatile var lastConversations: List<ConversationRecord>? = null
        @Volatile var lastReachability: Map<String, Reachability> = emptyMap()
        @Volatile var lastPeers: Map<String, PeerRecord> = emptyMap()
    }
    private val tasks = mutableListOf<Job>()

    fun start(environment: AppEnvironment) {
        if (tasks.isNotEmpty()) return
        val database = environment.database.value ?: return
        tasks += scope.launch {
            var knownIDs: Set<String>? = null
            database.conversations.observePermanent().collect { list ->
                _conversations.value = list
                lastConversations = list
                // Stato radio ricalcolato solo quando cambia l'elenco delle chat: ogni unread/ultimo messaggio
                // (anche l'apertura di una chat, durante la sua animazione) rifaceva il giro di tutti i gruppi.
                val ids = list.mapTo(HashSet()) { it.id }
                if (ids != knownIDs) {
                    knownIDs = ids
                    refreshReachability(environment)
                }
            }
        }
        tasks += scope.launch {
            database.peers.observeAll().collect { list ->
                val map = LinkedHashMap<String, PeerRecord>()
                for (peer in list) map.putIfAbsent(peer.nodeID.toHex(), peer)
                if (map != _peers.value) _peers.value = map
                lastPeers = map
            }
        }
        tasks += scope.launch {
            while (isActive) {
                delay(4_000)
                refreshReachability(environment)
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

    private suspend fun refreshReachability(environment: AppEnvironment) {
        val router = environment.router.value ?: return
        val map = LinkedHashMap<String, Reachability>()
        for (conversation in _conversations.value.orEmpty()) {
            map[conversation.id] = router.reachability(of = conversation)
        }
        // Solo se cambia: ogni assegnazione ricompone l'intera lista.
        if (map != _reachability.value) _reachability.value = map
        lastReachability = map
    }
}

/** Tab 1 – Chat & Gruppi permanenti (stile WhatsApp) con le 2 CTA giganti quando la lista e' vuota. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListView() {
    val environment = LocalAppEnvironment.current
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = remember { ChatListViewModel() }
    val database by environment.database.collectAsStateWithLifecycle()
    val identity by environment.identityRecord.collectAsStateWithLifecycle()
    val entitlementState by environment.entitlementState.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val reachability by viewModel.reachability.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()

    var showPairing by remember { mutableStateOf(false) }
    var showStoreQR by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showBoardingPass by remember { mutableStateOf(false) }
    var showFlightCard by remember { mutableStateOf(false) }
    /** Cabina del volo appena creata: aperta subito dopo la chiusura del foglio. */
    var pendingFlightLounge by remember { mutableStateOf<ConversationRecord?>(null) }
    var conversationToDelete by remember { mutableStateOf<ConversationRecord?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }

    // Ciclo di vita, non composizione: in background il polling ogni 4 s (query DB per ogni chat) e gli
    // osservatori Room restavano attivi finche' il processo viveva.
    LifecycleStartEffect(viewModel, database) {
        viewModel.start(environment)
        onStopOrDispose { viewModel.stop() }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.dispose() } }

    // Valori derivati di AppEnvironment: si ricalcolano quando cambiano i flow raccolti sopra.
    val isPro = entitlementState.let { environment.isPro }
    val nickname = identity?.nickname ?: environment.nickname
    val avatarImage = identity?.avatarImage
    val avatarColorHex = identity?.avatarColorHex ?: environment.avatarColorHex

    fun togglePin(conversation: ConversationRecord) {
        scope.launch {
            try {
                environment.setPinned(!conversation.isPinned, conversation = conversation)
                Haptics.impact(Haptics.ImpactStyle.light)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError = context.getString(R.string.chat_operation_failed, e.describe())
            }
        }
    }

    fun delete(conversation: ConversationRecord) {
        scope.launch {
            try {
                environment.deleteConversation(conversation)
                Haptics.impact(Haptics.ImpactStyle.medium)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError = context.getString(R.string.chat_operation_failed, e.describe())
            }
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
                    actionIconContentColor = AeroTheme.accentCyan,
                ),
                title = { Text(stringResource(R.string.chat_app_title), style = AeroTheme.title(28f)) },
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val profileLabel = stringResource(R.string.chat_edit_profile)
                        IconButton(
                            onClick = {
                                Haptics.tap()
                                showProfile = true
                            },
                            modifier = Modifier.semantics { contentDescription = profileLabel },
                        ) {
                            AvatarView(imageData = avatarImage, colorHex = avatarColorHex, size = 34.dp)
                        }
                        val passColor = if (isPro) AeroTheme.accentEmerald else AeroTheme.accentCyan
                        TextButton(onClick = { showPaywall = true }, modifier = Modifier.hapticOnTap()) {
                            Icon(
                                if (isPro) Icons.Filled.ConfirmationNumber else Icons.Outlined.ConfirmationNumber,
                                contentDescription = null,
                                tint = passColor,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(stringResource(if (isPro) R.string.chat_pro else R.string.chat_pass), color = passColor)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        Haptics.tap()
                        showPairing = true
                    }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.chat_add_buddy_qr))
                    }
                    Box {
                        IconButton(onClick = { showAddMenu = true }, modifier = Modifier.hapticOnTap()) {
                            Icon(Icons.Filled.AddCircle, contentDescription = null)
                        }
                        AeroDropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false },
                            items = listOf(
                                AeroMenuItem(stringResource(R.string.flight_menu_boarding), Icons.Outlined.FlightTakeoff) {
                                    Haptics.tap()
                                    showAddMenu = false
                                    showBoardingPass = true
                                },
                                AeroMenuItem(stringResource(R.string.chat_new_travel_group), Icons.Outlined.Groups) {
                                    Haptics.tap()
                                    showAddMenu = false
                                    showNewGroup = true
                                },
                                AeroMenuItem(stringResource(R.string.chat_share_app_gate), Icons.Outlined.GetApp) {
                                    Haptics.tap()
                                    showAddMenu = false
                                    showStoreQR = true
                                },
                                AeroMenuItem(
                                    stringResource(R.string.flight_card_title),
                                    Icons.Outlined.AutoAwesome,
                                    dividerBefore = true,
                                ) {
                                    Haptics.tap()
                                    showAddMenu = false
                                    showFlightCard = true
                                },
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(AeroTheme.background)) {
            val loaded = conversations
            if (loaded == null) {
                // Database non ancora pronto: niente stato vuoto provvisorio (evita il lampo delle CTA).
            } else if (loaded.isEmpty()) {
                EmptyState(
                    nickname = nickname,
                    onBoardingPass = { showBoardingPass = true },
                    onPairing = { showPairing = true },
                    onStoreQR = { showStoreQR = true },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(loaded, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            reachability = reachability[conversation.id] ?: Reachability.OutOfRange,
                            peer = conversation.peerNodeID?.let { peers[it.toHex()] },
                            onOpen = { navController.navigate(AeroRoutes.conversation(conversation.id)) },
                            onTogglePin = { togglePin(conversation) },
                            onDelete = {
                                Haptics.tap()
                                conversationToDelete = conversation
                            },
                        )
                        HorizontalDivider(color = AeroTheme.surface, modifier = Modifier.padding(start = 74.dp))
                    }
                }
            }
        }
    }

    if (showPairing) QRPairingSheet(onDismiss = { showPairing = false })
    if (showStoreQR) StoreQRSheet(onDismiss = { showStoreQR = false })
    if (showNewGroup) NewGroupSheet(onDismiss = { showNewGroup = false })
    if (showPaywall) PaywallView(onDismiss = { showPaywall = false })
    if (showBoardingPass) {
        BoardingPassSheet(
            onDismiss = {
                showBoardingPass = false
                pendingFlightLounge?.let { lounge ->
                    pendingFlightLounge = null
                    navController.navigate(AeroRoutes.conversation(lounge.id))
                }
            },
            onJoined = { pendingFlightLounge = it },
        )
    }
    if (showFlightCard) FlightCardSheet(onDismiss = { showFlightCard = false })
    var showRadar by remember { mutableStateOf(false) }
    var showEmergency by remember { mutableStateOf(false) }
    if (showRadar) com.stefanobrandi.aerochat.ui.lounge.RadarSheet(onDismiss = { showRadar = false })
    if (showEmergency) com.stefanobrandi.aerochat.ui.lounge.EmergencySheet(onDismiss = { showEmergency = false })
    if (showProfile) {
        SettingsScreen(
            onDismiss = { showProfile = false },
            onOpenFlightCard = {
                showProfile = false
                showFlightCard = true
            },
            onOpenRadar = {
                showProfile = false
                showRadar = true
            },
            onOpenEmergency = {
                showProfile = false
                showEmergency = true
            },
        )
    }

    conversationToDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { conversationToDelete = null },
            containerColor = AeroTheme.surface,
            titleContentColor = AeroTheme.textPrimary,
            textContentColor = AeroTheme.textSecondary,
            title = { Text(stringResource(R.string.chat_delete_confirm_title)) },
            text = { Text(stringResource(R.string.chat_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    conversationToDelete = null
                    delete(conversation)
                }) { Text(stringResource(R.string.chat_delete_chat), color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { conversationToDelete = null }) {
                    Text(stringResource(R.string.chat_cancel), color = AeroTheme.accentCyan)
                }
            },
        )
    }
    actionError?.let { OopsAlert(message = it, onDismiss = { actionError = null }) }
}

/**
 * Riga della lista con le azioni a scorrimento (verso destra: fissa/rimuovi, anche a scorrimento pieno;
 * verso sinistra: elimina, con conferma) e il menu a pressione prolungata (Swift `.contextMenu`).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ConversationRecord,
    reachability: Reachability,
    peer: PeerRecord?,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    val currentTogglePin by rememberUpdatedState(onTogglePin)
    val currentDelete by rememberUpdatedState(onDelete)
    var showMenu by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentTogglePin()
                SwipeToDismissBoxValue.EndToStart -> currentDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // La riga torna sempre al suo posto: l'azione la applica il database (o la conferma).
            false
        },
        positionalThreshold = { distance -> distance * 0.4f },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val isPin = direction == SwipeToDismissBoxValue.StartToEnd
            val color = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> AeroTheme.accentCyan
                SwipeToDismissBoxValue.EndToStart -> Color.Red
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            Box(
                Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                contentAlignment = if (isPin) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    val (icon, label) = if (isPin) pinAction(conversation) else Icons.Filled.Delete to R.string.chat_delete
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, contentDescription = null, tint = if (isPin) Color.Black else Color.White)
                        Text(stringResource(label), fontSize = 12.sp, color = if (isPin) Color.Black else Color.White)
                    }
                }
            }
        },
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AeroTheme.background)
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = {
                            Haptics.impact(Haptics.ImpactStyle.medium)
                            showMenu = true
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConversationAvatar(conversation, peer)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Titolo + biglietto occupano tutto lo spazio libero: l'orario resta sempre
                        // allineato a destra (con titolo e Spacer entrambi pesati, la parte non usata dal
                        // titolo `fill = false` non passava allo Spacer e l'orario finiva a meta' riga).
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                conversation.title,
                                style = AeroTheme.title(17f, FontWeight.SemiBold),
                                color = AeroTheme.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (conversation.isProUnlocked()) {
                                Icon(
                                    Icons.Filled.ConfirmationNumber,
                                    contentDescription = null,
                                    tint = AeroTheme.accentEmerald,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                        conversation.lastMessageAtMs?.let { last ->
                            Text(timeLabel(last), style = AeroTheme.caption, color = AeroTheme.textSecondary)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RadioStatusView(reachability = reachability, compact = true)
                        Spacer(Modifier.weight(1f))
                        if (conversation.isPinned) {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = stringResource(R.string.chat_pinned),
                                tint = AeroTheme.textSecondary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        if (conversation.unreadCount > 0) {
                            Text(
                                "${conversation.unreadCount}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                modifier = Modifier
                                    .background(AeroTheme.accentCyan, CircleShape)
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            val (pinIcon, pinLabel) = pinAction(conversation)
            AeroDropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                items = listOf(
                    AeroMenuItem(stringResource(pinLabel), pinIcon, iconTint = AeroTheme.accentCyan) {
                        showMenu = false
                        onTogglePin()
                    },
                    AeroMenuItem(stringResource(R.string.chat_delete), Icons.Filled.Delete, destructive = true) {
                        showMenu = false
                        onDelete()
                    },
                ),
            )
        }
    }
}

private fun pinAction(conversation: ConversationRecord): Pair<ImageVector, Int> =
    if (conversation.isPinned) Icons.Outlined.PushPin to R.string.chat_unpin else Icons.Filled.PushPin to R.string.chat_pin

@Composable
private fun ConversationAvatar(conversation: ConversationRecord, peer: PeerRecord?) {
    if (conversation.isFlightLounge) {
        AvatarView(imageData = null, colorHex = "00E5FF", systemImage = Icons.Filled.Flight)
    } else if (conversation.type == ConversationType.group) {
        AvatarView(imageData = null, colorHex = "10B981", systemImage = Icons.Filled.Groups)
    } else {
        AvatarView(imageData = peer?.avatarImage, colorHex = peer?.avatarColorHex ?: fallbackColorHex(conversation))
    }
}

/** Colore deterministico per i contatti che non hanno ancora annunciato il loro profilo. */
private fun fallbackColorHex(conversation: ConversationRecord): String {
    val seed = conversation.recipientID.firstOrNull()?.toInt()?.and(0xFF) ?: 0
    val palette = OnboardingView.palette
    return palette[seed % palette.size]
}

@Composable
private fun EmptyState(nickname: String, onBoardingPass: () -> Unit, onPairing: () -> Unit, onStoreQR: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(64.dp).border(3.dp, AeroTheme.accentCyan, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Flight, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(36.dp))
        }
        Text(
            stringResource(R.string.chat_ready_takeoff, nickname),
            style = AeroTheme.title(26f),
            color = AeroTheme.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.flight_empty_hint),
            style = AeroTheme.footnote,
            color = AeroTheme.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.weight(1f))
        CtaButton(stringResource(R.string.flight_cta_boarding), Icons.Filled.FlightTakeoff, AeroTheme.accentCyan, onBoardingPass)
        CtaButton(stringResource(R.string.chat_cta_add_buddies), Icons.Filled.QrCodeScanner, AeroTheme.accentCyan, onPairing)
        CtaButton(stringResource(R.string.chat_cta_share_app), Icons.Filled.GetApp, AeroTheme.accentEmerald, onStoreQR)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CtaButton(title: String, icon: ImageVector, tint: Color, action: () -> Unit) {
    Button(
        onClick = action,
        modifier = Modifier.fillMaxWidth().hapticOnTap(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = Color.Black),
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minHeight = 28.dp).padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null)
            Text(title, style = AeroTheme.title(18f, FontWeight.Bold), textAlign = TextAlign.Center)
        }
    }
}
