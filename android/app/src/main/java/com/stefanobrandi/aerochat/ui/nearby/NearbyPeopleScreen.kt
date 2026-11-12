package com.stefanobrandi.aerochat.ui.nearby

import com.stefanobrandi.aerochat.screenshots.StoreScreenshots
import com.stefanobrandi.aerochat.core.transport.BLEPermissions
import com.stefanobrandi.aerochat.ui.components.rememberMeshPermissionsRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.AppEnvironment
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.mesh.IncomingContactRequest
import com.stefanobrandi.aerochat.core.mesh.NearbyPerson
import com.stefanobrandi.aerochat.core.moderation.AppSettings
import com.stefanobrandi.aerochat.proto.GameKind
import com.stefanobrandi.aerochat.ui.AeroRoutes
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.LocalNavController
import com.stefanobrandi.aerochat.ui.chat.InfoBanner
import com.stefanobrandi.aerochat.ui.components.AeroDropdownMenu
import com.stefanobrandi.aerochat.ui.components.AeroMenuHeader
import com.stefanobrandi.aerochat.ui.components.AeroMenuItem
import com.stefanobrandi.aerochat.ui.components.AvatarView
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.pairing.StoreQRSheet
import com.stefanobrandi.aerochat.core.mesh.BoardPostEntry
import com.stefanobrandi.aerochat.core.mesh.RouterError
import com.stefanobrandi.aerochat.proto.BoardKind
import com.stefanobrandi.aerochat.ui.flight.FlightSegmentedPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Segmenti di "Persone vicine": lista, mappa dei posti, bacheca della cabina. */
private enum class NearbySegment { people, map, board }

/** Giochi a 2 giocatori proponibili con "Sfida" (le carte prima). */
internal val challengeGames: List<GameKind> =
    listOf(GameKind.SCOPA, GameKind.BRISCOLA, GameKind.TIC_TAC_TOE, GameKind.BATTLESHIP)

/** Nome del gioco nella lingua dell'app. */
@Composable
internal fun gameName(kind: GameKind): String = stringResource(
    when (kind) {
        GameKind.SCOPA -> R.string.nearby_game_scopa
        GameKind.BRISCOLA -> R.string.nearby_game_briscola
        GameKind.TIC_TAC_TOE -> R.string.nearby_game_tictactoe
        else -> R.string.nearby_game_battleship
    },
)

/** Titolo di una richiesta: "Marco ti saluta" / "Marco ti sfida a Scopa". */
@Composable
internal fun requestTitle(request: IncomingContactRequest): String {
    val kind = GameKind.forNumber(request.gameKind)
    return if (request.isChallenge && kind != null) {
        stringResource(R.string.nearby_notification_challenge, request.nickname, gameName(kind))
    } else {
        stringResource(R.string.nearby_notification_hello, request.nickname)
    }
}

/**
 * "Persone vicine": chi ha AeroChat aperto a portata radio (`PRESENCE`), con **Saluta** e **Sfida**, e le
 * "Richieste" ricevute con **Accetta** / **Rifiuta**. Con [gamesOnly] mostra solo chi accetta sfide.
 * I passeggeri dello stesso volo compaiono per primi.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyPeopleScreen(gamesOnly: Boolean) {
    val environment = LocalAppEnvironment.current
    // Senza Bluetooth non si vede nessuno: lo chiediamo di nuovo (o spieghiamo come riattivarlo).
    val requestMeshPermissions = rememberMeshPermissionsRequester()
    val permissionContext = LocalContext.current
    LaunchedEffect(Unit) {
        if (!StoreScreenshots.isActive && !BLEPermissions.hasAllPermissions(permissionContext)) requestMeshPermissions()
    }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val people by environment.nearbyPeople.collectAsStateWithLifecycle()
    val requests by environment.contactRequests.collectAsStateWithLifecycle()
    val banner by environment.nearbyBanner.collectAsStateWithLifecycle()
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val database by environment.database.collectAsStateWithLifecycle()
    var onlyGames by rememberSaveable { mutableStateOf(gamesOnly) }
    var info by remember { mutableStateOf<String?>(null) }
    /** Persone a cui abbiamo appena scritto (in attesa di risposta), per `node_id` hex. */
    var pending by remember { mutableStateOf(emptySet<String>()) }
    var showInvite by remember { mutableStateOf(false) }
    var showDiagnosis by remember { mutableStateOf(false) }
    var segment by rememberSaveable { mutableStateOf(NearbySegment.people) }
    val boardPosts by environment.boardPosts.collectAsStateWithLifecycle()
    var showSeatEdit by remember { mutableStateOf(false) }
    /** Annuncio da scrivere (tipo e testo iniziali), `null` = foglio chiuso. */
    var composing by remember { mutableStateOf<Pair<BoardKind, String>?>(null) }

    // Volo della Cabina attiva: chi e' sullo stesso volo compare per primo.
    val myFlight by produceState<String?>(null, database) {
        val db = database ?: return@produceState
        db.conversations.observeActiveFlightLounges().collect { value = it.firstOrNull()?.flightCode }
    }

    // La nostra richiesta e' stata accettata: si apre subito la nuova chat.
    LaunchedEffect(banner) {
        val accepted = banner as? AppEnvironment.NearbyBanner.Accepted ?: return@LaunchedEffect
        environment.dismissNearbyBanner()
        navController.navigate(AeroRoutes.conversation(accepted.conversationID)) {
            popUpTo(AeroRoutes.tabs)
        }
    }

    val mySeat = settings.seat(myFlight)
    val swapGreeting = seatSwapGreeting(mySeat)
    val quickSwapText = mySeat?.let { stringResource(R.string.growth14_board_quick_swap, it) }

    val shown = people
        .filter { !onlyGames || it.openToGames }
        .sortedWith(compareByDescending<NearbyPerson> { it.isOnFlight(myFlight) })

    fun openChat(conversationID: String) {
        navController.navigate(AeroRoutes.conversation(conversationID)) { popUpTo(AeroRoutes.tabs) }
    }

    fun failure(e: Exception) {
        Haptics.error()
        info = context.getString(R.string.nearby_send_failed)
        android.util.Log.w("AeroMesh.Nearby", "invio non riuscito: $e")
    }

    fun hello(person: NearbyPerson) {
        scope.launch {
            try {
                val chat = environment.sayHello(person)
                if (chat != null) {
                    openChat(chat.id)
                } else {
                    pending = pending + person.id
                    Haptics.success()
                    info = context.getString(R.string.nearby_hello_sent, person.nickname)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(e)
            }
        }
    }

    fun challenge(person: NearbyPerson, kind: GameKind) {
        scope.launch {
            try {
                val chat = environment.challenge(person, kind)
                if (chat != null) {
                    openChat(chat.id)
                } else {
                    pending = pending + person.id
                    Haptics.success()
                    info = context.getString(R.string.nearby_challenge_sent, person.nickname)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(e)
            }
        }
    }

    fun proposeSwap(person: NearbyPerson) {
        scope.launch {
            try {
                val chat = environment.proposeSeatSwap(person, swapGreeting)
                if (chat != null) {
                    openChat(chat.id)
                } else {
                    pending = pending + person.id
                    Haptics.success()
                    info = context.getString(R.string.nearby_hello_sent, person.nickname)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(e)
            }
        }
    }

    fun post(kind: BoardKind, text: String) {
        scope.launch {
            try {
                environment.postToBoard(kind, text)
                Haptics.success()
                info = context.getString(R.string.growth14_board_posted)
            } catch (e: CancellationException) {
                throw e
            } catch (_: RouterError.BoardLimitReached) {
                Haptics.error()
                info = context.getString(R.string.growth14_board_limit)
            } catch (_: RouterError.InvalidBoardPost) {
                Haptics.error()
                info = context.getString(R.string.growth14_board_invalid)
            } catch (e: Exception) {
                failure(e)
            }
        }
    }

    fun reply(post: BoardPostEntry) {
        scope.launch {
            try {
                val chat = environment.replyToBoardPost(post)
                if (chat != null) {
                    openChat(chat.id)
                } else {
                    Haptics.success()
                    info = context.getString(R.string.nearby_hello_sent, post.nickname)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(e)
            }
        }
    }

    fun accept(request: IncomingContactRequest) {
        scope.launch {
            try {
                val chat = environment.acceptContactRequest(request)
                Haptics.success()
                openChat(chat.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure(e)
            }
        }
    }

    fun decline(request: IncomingContactRequest) {
        scope.launch {
            runCatching { environment.declineContactRequest(request) }
            Haptics.tap()
        }
    }

    Scaffold(
        containerColor = AeroTheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AeroTheme.background,
                    titleContentColor = AeroTheme.textPrimary,
                    navigationIconContentColor = AeroTheme.accentCyan,
                ),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.safety_back))
                    }
                },
                title = {
                    Text(
                        stringResource(if (onlyGames) R.string.nearby_challenge_title else R.string.nearby_title),
                        style = AeroTheme.title(22f),
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!onlyGames) {
                    item(key = "segments") {
                        FlightSegmentedPicker(
                            options = NearbySegment.entries,
                            selected = segment,
                            label = {
                                stringResource(
                                    when (it) {
                                        NearbySegment.people -> R.string.growth14_segment_people
                                        NearbySegment.map -> R.string.growth14_segment_map
                                        NearbySegment.board -> R.string.growth14_segment_board
                                    },
                                )
                            },
                            onSelect = { segment = it },
                        )
                    }
                }
                if (!onlyGames && segment == NearbySegment.map) {
                    item(key = "seat") {
                        SeatCard(
                            flightCode = myFlight,
                            seat = mySeat,
                            shared = settings.isSeatShared,
                            onEditSeat = { showSeatEdit = true },
                            onSharedChange = { scope.launch { environment.setSeatShared(it) } },
                        )
                    }
                    item(key = "map") {
                        SeatMap(
                            flightCode = myFlight,
                            mySeat = mySeat,
                            people = people,
                            onHello = ::hello,
                            onSwap = ::proposeSwap,
                        )
                    }
                }
                if (!onlyGames && segment == NearbySegment.board) {
                    item(key = "board-header") {
                        BoardHeader(
                            onCompose = { composing = BoardKind.BOARD_KIND_LOOKING_FOR to "" },
                            onQuickSeatSwap = {
                                // Con il posto noto l'annuncio parte subito, altrimenti si scrive.
                                if (quickSwapText != null) post(BoardKind.BOARD_KIND_SEAT_SWAP, quickSwapText)
                                else composing = BoardKind.BOARD_KIND_SEAT_SWAP to ""
                            },
                        )
                    }
                    if (boardPosts.isEmpty()) item(key = "board-empty") { BoardEmpty() }
                    items(boardPosts, key = { "post-" + it.id }) { entry ->
                        BoardPostRow(
                            post = entry,
                            onSameFlight = myFlight != null && entry.flightCode == myFlight,
                            onReply = { reply(entry) },
                            onClose = { scope.launch { environment.closeBoardPost(entry) } },
                        )
                    }
                }
                if (onlyGames || segment == NearbySegment.people) {
                item(key = "visibility") {
                    VisibilityCard(
                        visible = settings.isNearbyVisible,
                        onlyGames = onlyGames,
                        onVisibleChange = { scope.launch { environment.setNearbyVisible(it) } },
                        onOnlyGamesChange = { onlyGames = it },
                    )
                }
                if (requests.isNotEmpty()) {
                    item(key = "requests-header") { SectionTitle(stringResource(R.string.nearby_requests)) }
                    items(requests, key = { "request-" + it.id }) { request ->
                        RequestCard(request, onAccept = { accept(request) }, onDecline = { decline(request) })
                    }
                }
                item(key = "people-header") {
                    SectionTitle(
                        if (shown.isEmpty()) stringResource(R.string.nearby_people_section)
                        else pluralStringResource(R.plurals.nearby_people_count, shown.size, shown.size),
                    )
                }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        EmptyNearby(
                            onInvite = { showInvite = true },
                            onDiagnose = { showDiagnosis = true },
                            onTryDemo = {
                                scope.launch {
                                    environment.startDemo()?.let { openChat(it.id) }
                                }
                            },
                        )
                    }
                }
                items(shown, key = { "person-" + it.id }) { person ->
                    PersonRow(
                        person = person,
                        onSameFlight = person.isOnFlight(myFlight),
                        seat = person.seatOnFlight(myFlight),
                        isPending = pending.contains(person.id),
                        onHello = { hello(person) },
                        onChallenge = { kind -> challenge(person, kind) },
                    )
                }
                }
            }
            InfoBanner(message = info, onExpire = { info = null }, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
    if (showInvite) StoreQRSheet(onDismiss = { showInvite = false })
    if (showDiagnosis) NobodyNearbySheet(onDismiss = { showDiagnosis = false })
    if (showSeatEdit) {
        SeatEditSheet(current = mySeat, onDismiss = { showSeatEdit = false }) { value ->
            // Il volo attivo e' noto (la riga e' toccabile solo con la Cabina del volo).
            val flight = myFlight ?: return@SeatEditSheet false
            val seat = value?.let { com.stefanobrandi.aerochat.core.mesh.SeatCode.normalized(it) ?: return@SeatEditSheet false }
            AppSettings.setSeat(seat, flightCode = flight)
            if (settings.isSeatShared) scope.launch { environment.router.value?.announcePresence() }
            true
        }
    }
    composing?.let { (kind, text) ->
        BoardComposeSheet(
            initialKind = kind,
            initialText = text,
            onDismiss = { composing = null },
            onPost = { postKind, postText -> post(postKind, postText) },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = AeroTheme.captionSemibold,
        color = AeroTheme.textSecondary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun VisibilityCard(
    visible: Boolean,
    onlyGames: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onOnlyGamesChange: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        ToggleLine(Icons.Filled.Visibility, stringResource(R.string.nearby_visible_toggle), visible, onVisibleChange)
        Text(
            stringResource(if (visible) R.string.nearby_visible_on_hint else R.string.nearby_visible_off_hint),
            style = AeroTheme.caption,
            color = AeroTheme.textSecondary,
            modifier = Modifier.padding(start = 36.dp, bottom = 6.dp),
        )
        ToggleLine(Icons.Filled.SportsEsports, stringResource(R.string.nearby_only_games), onlyGames, onOnlyGamesChange)
    }
}

@Composable
private fun ToggleLine(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { Haptics.tap(); onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(24.dp))
        Text(title, style = AeroTheme.body, color = AeroTheme.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { Haptics.tap(); onChange(it) },
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AeroTheme.accentCyan),
        )
    }
}

@Composable
private fun RequestCard(request: IncomingContactRequest, onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, AeroTheme.accentCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AvatarView(imageData = null, colorHex = request.avatarColorHex, size = 42.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    requestTitle(request),
                    style = AeroTheme.title(17f, FontWeight.SemiBold),
                    color = AeroTheme.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (request.greeting.isNotEmpty()) {
                    Text("“${request.greeting}”", style = AeroTheme.footnote, color = AeroTheme.textSecondary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AeroTheme.accentCyan, contentColor = Color.Black),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.nearby_accept), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = AeroTheme.textSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.nearby_decline), color = AeroTheme.textPrimary)
            }
        }
    }
}

@Composable
private fun PersonRow(
    person: NearbyPerson,
    onSameFlight: Boolean,
    seat: String?,
    isPending: Boolean,
    onHello: () -> Unit,
    onChallenge: (GameKind) -> Unit,
) {
    var showGames by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AvatarView(imageData = null, colorHex = person.avatarColorHex, size = 46.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    person.nickname,
                    style = AeroTheme.title(17f, FontWeight.SemiBold),
                    color = AeroTheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onSameFlight) Tag(stringResource(R.string.nearby_same_flight), AeroTheme.accentCyan, Icons.Filled.Flight)
                    if (seat != null) Tag(stringResource(R.string.flight_seat, seat), AeroTheme.accentEmerald, null)
                    if (person.isContact) Tag(stringResource(R.string.nearby_already_contact), AeroTheme.accentEmerald, null)
                    Text(signalLabel(person.rssi), style = AeroTheme.caption, color = AeroTheme.textSecondary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { Haptics.tap(); onHello() },
                enabled = !isPending,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AeroTheme.accentCyan,
                    contentColor = Color.Black,
                    disabledContainerColor = AeroTheme.accentCyan.copy(alpha = 0.25f),
                ),
            ) {
                Icon(Icons.Filled.WavingHand, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(
                        when {
                            isPending -> R.string.nearby_waiting
                            person.isContact -> R.string.nearby_open_chat
                            else -> R.string.nearby_say_hello
                        },
                    ),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            if (person.openToGames) {
                Box(Modifier.weight(1f)) {
                    Button(
                        onClick = { Haptics.tap(); showGames = true },
                        enabled = !isPending,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AeroTheme.accentEmerald,
                            contentColor = Color.Black,
                            disabledContainerColor = AeroTheme.accentEmerald.copy(alpha = 0.25f),
                        ),
                    ) {
                        Icon(Icons.Filled.SportsEsports, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.nearby_challenge), fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    AeroDropdownMenu(
                        expanded = showGames,
                        onDismissRequest = { showGames = false },
                        header = AeroMenuHeader(stringResource(R.string.nearby_pick_game, person.nickname)),
                        items = challengeGames.map { kind ->
                            AeroMenuItem(gameName(kind), Icons.Filled.SportsEsports, iconTint = AeroTheme.accentEmerald) {
                                showGames = false
                                onChallenge(kind)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color, icon: ImageVector?) {
    Row(
        Modifier.background(color.copy(alpha = 0.18f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(text, style = AeroTheme.captionSemibold, color = color, maxLines = 1)
    }
}

@Composable
private fun signalLabel(rssi: Int?): String = stringResource(
    when {
        rssi == null -> R.string.nearby_signal_unknown
        rssi >= -65 -> R.string.nearby_signal_very_close
        rssi >= -80 -> R.string.nearby_signal_close
        else -> R.string.nearby_signal_far
    },
)

@Composable
private fun EmptyNearby(onInvite: () -> Unit, onDiagnose: () -> Unit, onTryDemo: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(16.dp))
            .padding(20.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.WavingHand, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(40.dp))
        Text(
            stringResource(R.string.nearby_empty_title),
            style = AeroTheme.title(18f, FontWeight.SemiBold),
            color = AeroTheme.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.nearby_empty_hint),
            style = AeroTheme.footnote,
            color = AeroTheme.textSecondary,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onInvite,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AeroTheme.accentCyan, contentColor = Color.Black),
        ) {
            Icon(Icons.Outlined.GetApp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.nearby_invite_friends), fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { Haptics.tap(); onDiagnose() }) {
            Text(stringResource(R.string.growth14_diag_title), color = AeroTheme.accentCyan, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onTryDemo) {
            Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = AeroTheme.accentEmerald, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.nearby_try_demo), color = AeroTheme.accentEmerald)
        }
    }
}
