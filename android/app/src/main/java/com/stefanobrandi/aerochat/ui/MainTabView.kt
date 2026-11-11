package com.stefanobrandi.aerochat.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.transport.RadioState
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.ui.chat.CallView
import com.stefanobrandi.aerochat.ui.chat.ChatListView
import com.stefanobrandi.aerochat.ui.chat.ConversationView
import com.stefanobrandi.aerochat.ui.components.AirplaneModeBanner
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.MeshPermissionsGate
import com.stefanobrandi.aerochat.ui.components.hapticOnTap
import com.stefanobrandi.aerochat.ui.components.rememberMeshPermissionsRequester
import com.stefanobrandi.aerochat.ui.lounge.CabinLoungeView
import com.stefanobrandi.aerochat.ui.lounge.EmergencyBanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/*
 * ## Navigazione (API per le schermate chat)
 *
 * Il `NavHost` radice vive in [MainTabView] ed e' raggiungibile da qualsiasi composable con
 * [LocalNavController]`.current`. Rotte ([AeroRoutes]):
 *
 * - [AeroRoutes.tabs] — la schermata con le due tab (`ChatListView()` / `CabinLoungeView()`) e la
 *   bottom bar. E' la rotta iniziale.
 * - [AeroRoutes.conversationPattern] = `conversation/{conversationID}` — una chat a schermo intero
 *   (senza bottom bar). Aprirla con
 *   `LocalNavController.current.navigate(AeroRoutes.conversation(conversation.id))`
 *   (equivalente del `NavigationLink(value: conversation)` di Swift). La rotta osserva il record con
 *   `database.conversations.observe(id)` e chiama **`ConversationView(conversation: ConversationRecord)`**
 *   (stessa firma Swift); se la chat viene eliminata la rotta torna indietro da sola.
 *   Tornare indietro: `LocalNavController.current.popBackStack()` (o il tasto Indietro di sistema).
 *
 * Fuori dal NavHost: [AppNavigator.openConversation] (usato da `MainActivity` per i tap sulle
 * notifiche) accoda l'apertura di una chat, eseguita appena [MainTabView] e' composta.
 *
 * I fogli modali della shell si presentano da soli (ModalBottomSheet) e si chiudono con `onDismiss`:
 * `QRPairingSheet(onDismiss, group = null, initialMode = QRPairingSheet.Mode.show)`, `StoreQRSheet(onDismiss)`,
 * `PaywallView(onDismiss)`, `ProfileEditSheet(onDismiss)`.
 *
 * La chiamata in corso (`calls.current != null`) e' mostrata da [MainTabView] a tutto schermo sopra
 * ogni rotta con `CallView()`; le schermate chat devono solo chiamare `calls.placeCall(conversation)`.
 */

/** Rotte del NavHost radice. */
object AeroRoutes {
    const val tabs = "tabs"
    const val conversationArg = "conversationID"
    const val conversationPattern = "conversation/{$conversationArg}"

    fun conversation(conversationID: String): String = "conversation/${Uri.encode(conversationID)}"
}

/** NavController radice (fornito da [MainTabView]). */
val LocalNavController = staticCompositionLocalOf<NavHostController> {
    error("LocalNavController disponibile solo dentro MainTabView")
}

/** Richieste di navigazione provenienti da fuori della UI (tap su notifica, deep link). */
object AppNavigator {
    private val _pendingConversationID = MutableStateFlow<String?>(null)
    val pendingConversationID: StateFlow<String?> = _pendingConversationID.asStateFlow()

    fun openConversation(conversationID: String) {
        _pendingConversationID.value = conversationID
    }

    internal fun consume() {
        _pendingConversationID.value = null
    }
}

/** Modello chat ibrido: Tab 1 permanente (Chat & Gruppi), Tab 2 temporanea (Cabina Pubblica 12 h). */
@Composable
fun MainTabView() {
    val environment = LocalAppEnvironment.current
    val navController = rememberNavController()
    val radioState by environment.radioState.collectAsStateWithLifecycle()
    val calls by environment.calls.collectAsStateWithLifecycle()
    val currentCall by (calls?.current ?: flowOf(null)).collectAsStateWithLifecycle(initialValue = null)
    val pending by AppNavigator.pendingConversationID.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Tocco su una notifica: apre la chat (sopra alle tab).
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        AppNavigator.consume()
        navController.navigate(AeroRoutes.conversation(id)) {
            popUpTo(AeroRoutes.tabs)
            launchSingleTop = true
        }
    }

    // Nuovo messaggio con l'app attiva: tocco leggero.
    LaunchedEffect(environment) {
        environment.lastReceivedMessageID.collect { msgID ->
            if (msgID != null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                Haptics.impact(Haptics.ImpactStyle.light)
            }
        }
    }

    // Primo avvio: permessi BLE (+ notifiche su Android 13+).
    MeshPermissionsGate()

    CompositionLocalProvider(LocalNavController provides navController) {
        Box(Modifier.fillMaxSize().background(AeroTheme.background)) {
            NavHost(
                navController = navController,
                startDestination = AeroRoutes.tabs,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { slideInHorizontally { it } },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { slideOutHorizontally { it } },
            ) {
                composable(AeroRoutes.tabs) { TabsScreen(radioState) }
                composable(
                    AeroRoutes.conversationPattern,
                    arguments = listOf(navArgument(AeroRoutes.conversationArg) { type = NavType.StringType }),
                ) { entry ->
                    val id = entry.arguments?.getString(AeroRoutes.conversationArg).orEmpty()
                    ConversationRoute(id)
                }
            }

            // Chiamata in entrata o in corso: sopra a tutto, da qualsiasi schermata.
            AnimatedVisibility(
                visible = currentCall != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.zIndex(10f),
            ) {
                BackHandler { scope.launch { calls?.hangUp() } }
                Box(Modifier.fillMaxSize().background(AeroTheme.background)) { CallView() }
            }
        }
    }
}

@Composable
private fun TabsScreen(radioState: RadioState) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val requestPermissions = rememberMeshPermissionsRequester()
    val environment = LocalAppEnvironment.current
    val activeEmergency by environment.activeEmergency.collectAsStateWithLifecycle()
    // Ultimo avviso mostrato, per l'animazione di uscita dopo la chiusura.
    var shownEmergency by remember { mutableStateOf(activeEmergency) }
    if (activeEmergency != null) shownEmergency = activeEmergency
    Column(Modifier.fillMaxSize().background(AeroTheme.background)) {
        AnimatedVisibility(
            visible = AirplaneModeBanner.shouldShow(radioState),
            enter = slideInVertically(spring()) { -it } + fadeIn(),
            exit = slideOutVertically(spring()) { -it } + fadeOut(),
        ) {
            AirplaneModeBanner(
                state = radioState,
                modifier = Modifier
                    .statusBarsPadding()
                    .background(AeroTheme.background)
                    .let {
                        // Su Android il permesso si puo' richiedere (o aprire le Impostazioni) da qui.
                        if (radioState == RadioState.unauthorized) it.clickable { requestPermissions() } else it
                    },
            )
        }
        val airplaneBannerVisible = AirplaneModeBanner.shouldShow(radioState)
        // Avviso di emergenza ricevuto: sotto il banner radio, finche' l'utente non lo chiude.
        AnimatedVisibility(
            visible = activeEmergency != null,
            enter = slideInVertically(spring()) { -it } + fadeIn(),
            exit = slideOutVertically(spring()) { -it } + fadeOut(),
        ) {
            shownEmergency?.let { alert ->
                EmergencyBanner(
                    alert = alert,
                    onDismiss = { environment.setActiveEmergency(null) },
                    modifier = Modifier
                        .background(AeroTheme.background)
                        .let { if (airplaneBannerVisible) it else it.statusBarsPadding() },
                )
            }
        }
        val bannerVisible = airplaneBannerVisible || activeEmergency != null
        // Con il banner in alto la status bar e' gia' gestita: le top bar delle tab non la ripetono.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .let { if (bannerVisible) it.consumeWindowInsets(WindowInsets.statusBars) else it },
        ) {
            when (selectedTab) {
                0 -> ChatListView()
                else -> CabinLoungeView()
            }
        }
        val totalUnread by environment.totalUnread.collectAsStateWithLifecycle()
        NavigationBar(containerColor = AeroTheme.background, contentColor = AeroTheme.accentCyan) {
            val colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AeroTheme.accentCyan,
                selectedTextColor = AeroTheme.accentCyan,
                indicatorColor = AeroTheme.accentCyan.copy(alpha = 0.15f),
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
            )
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                icon = {
                    // Badge dei non letti di chat e gruppi (la Cabina Pubblica non conta).
                    BadgedBox(
                        badge = {
                            if (totalUnread > 0) {
                                val description = pluralStringResource(R.plurals.revisions_unread_badge, totalUnread, totalUnread)
                                Badge(
                                    containerColor = Color.Red,
                                    contentColor = Color.White,
                                    modifier = Modifier.semantics { contentDescription = description },
                                ) {
                                    Text(if (totalUnread > 99) "99+" else totalUnread.toString())
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Forum, contentDescription = null)
                    }
                },
                label = { Text(stringResource(R.string.shell_tab_chats)) },
                colors = colors,
                modifier = Modifier.hapticOnTap(),
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                icon = { Icon(Icons.Filled.Flight, contentDescription = null) },
                label = { Text(stringResource(R.string.shell_tab_cabin)) },
                colors = colors,
                modifier = Modifier.hapticOnTap(),
            )
        }
    }
}

/** Rotta `conversation/{conversationID}`: osserva il record e mostra `ConversationView(conversation)`. */
@Composable
private fun ConversationRoute(conversationID: String) {
    val environment = LocalAppEnvironment.current
    val navController = LocalNavController.current
    val database by environment.database.collectAsStateWithLifecycle()
    val state by produceState<Pair<Boolean, ConversationRecord?>>(false to null, database, conversationID) {
        val db = database ?: return@produceState
        db.conversations.observe(conversationID).collect { value = true to it }
    }
    val (loaded, conversation) = state
    if (conversation != null) {
        ConversationView(conversation = conversation)
    } else {
        Box(Modifier.fillMaxSize().background(AeroTheme.background))
        if (loaded) {
            // Chat eliminata o inesistente: torna alla lista.
            LaunchedEffect(Unit) { navController.popBackStack() }
        }
    }
}
