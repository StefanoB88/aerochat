package com.stefanobrandi.aerochat.screenshots

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.app.AppEnvironment
import com.stefanobrandi.aerochat.core.crypto.CryptoEngine
import com.stefanobrandi.aerochat.core.crypto.IdentityKeys
import com.stefanobrandi.aerochat.core.crypto.IdentityStore
import com.stefanobrandi.aerochat.core.crypto.InMemorySecretStore
import com.stefanobrandi.aerochat.core.flight.BoardingPass
import com.stefanobrandi.aerochat.core.flight.FlightStats
import com.stefanobrandi.aerochat.core.flight.FlightTrip
import com.stefanobrandi.aerochat.core.flight.TripMemoryStore
import com.stefanobrandi.aerochat.core.games.GameSessionState
import com.stefanobrandi.aerochat.core.games.GameStatus
import com.stefanobrandi.aerochat.core.games.NeapolitanCards
import com.stefanobrandi.aerochat.core.games.ScopaGame
import com.stefanobrandi.aerochat.core.mesh.IncomingContactRequest
import com.stefanobrandi.aerochat.core.mesh.MeshRouterActor
import com.stefanobrandi.aerochat.core.mesh.NearbyPerson
import com.stefanobrandi.aerochat.core.mesh.NoopNotifier
import com.stefanobrandi.aerochat.core.media.MediaStore
import com.stefanobrandi.aerochat.core.moderation.AppSettings
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.core.transport.LinkID
import com.stefanobrandi.aerochat.core.transport.LinkRole
import com.stefanobrandi.aerochat.core.transport.PeerLink
import com.stefanobrandi.aerochat.core.transport.RadioState
import com.stefanobrandi.aerochat.core.transport.TransportEvent
import com.stefanobrandi.aerochat.core.transport.TransportProvider
import com.stefanobrandi.aerochat.data.AppDatabase
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.DeliveryStatus
import com.stefanobrandi.aerochat.data.records.GroupMemberRecord
import com.stefanobrandi.aerochat.data.records.IdentityRecord
import com.stefanobrandi.aerochat.data.records.MessageContentType
import com.stefanobrandi.aerochat.data.records.MessageRecord
import com.stefanobrandi.aerochat.data.records.PeerRecord
import com.stefanobrandi.aerochat.proto.GameKind
import com.stefanobrandi.aerochat.ui.AppNavigator
import com.stefanobrandi.aerochat.ui.flight.FlightCardSheet
import com.stefanobrandi.aerochat.ui.games.CardTableDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Calendar
import java.util.UUID
import kotlin.random.Random

/**
 * Screenshot di Google Play (solo build debug: questo file vive in `src/debug`, la build release ha uno
 * stub vuoto in `src/release`). Avvio con
 * `am start -n com.stefanobrandi.aerochat/.MainActivity --ez storeScreenshots true --es storeScreen <nome>`:
 * database e media temporanei, onboarding saltato, profilo "Giulia"/"Julia", chat, gruppo, Cabina del volo
 * AZ 204 FCO -> JFK, Cabina Pubblica, partita a Scopa, persone vicine e richiesta di sfida finte; trasporto
 * radio finto (nessun Bluetooth, nessun permesso) e nessuna notifica. Stessi dati degli screenshot iOS
 * (`ios/AeroChat/Core/Review/StoreScreenshots.swift`). La lingua dei testi finti segue quella dell'app
 * (italiano o inglese, impostata con `cmd locale set-app-locales`).
 */
object StoreScreenshots {

    const val extraEnabled = "storeScreenshots"
    const val extraScreen = "storeScreen"

    enum class Screen { chats, conversation, cabin, lounge, cards, nearby, flightcard }

    /** Schermata richiesta (`null` = modalita' screenshot spenta). */
    @Volatile
    private var screen: Screen? = null

    val isActive: Boolean get() = screen != null

    /** Da chiamare in `MainActivity.onCreate` prima del primo accesso all'[AppEnvironment]. */
    fun configure(intent: Intent?) {
        if (intent?.getBooleanExtra(extraEnabled, false) != true) return
        val name = intent.getStringExtra(extraScreen)?.lowercase()
        screen = Screen.entries.firstOrNull { it.name == name } ?: Screen.chats
    }

    // MARK: Stato prodotto dal seed

    private enum class OverlayKind { cards, flightcard }

    private val overlay = MutableStateFlow<OverlayKind?>(null)

    @Volatile private var italian = true
    @Volatile private var conversationToShow: String? = null
    @Volatile private var flightLoungeID: String? = null
    @Volatile private var flightCode: String? = null
    @Volatile private var typingConversationID: String? = null
    @Volatile private var scopaState: GameSessionState? = null
    @Volatile private var preselectedCard: Int? = null
    @Volatile private var flightTrip: FlightTrip? = null
    @Volatile private var flightStats: FlightStats? = null

    private fun t(italianText: String, englishText: String): String = if (italian) italianText else englishText

    // MARK: Ambiente

    /**
     * Ambiente con DB in memoria (gia' popolato), media temporanei, chiavi deterministiche, trasporto finto
     * e notifiche mute. `null` se la modalita' screenshot non e' attiva.
     */
    fun makeEnvironment(context: Context, scope: CoroutineScope): AppEnvironment? {
        val screen = screen ?: return null
        val appContext = context.applicationContext
        italian = appContext.resources.configuration.locales[0].language == "it"
        // Nessun ricordo di viaggi precedenti (la classifica parte dai numeri finti).
        appContext.deleteSharedPreferences(TripMemoryStore.preferencesName)

        val me = SeedPeer.me.keys
        val secrets = InMemorySecretStore().apply {
            save(me.x25519PrivateKeyRaw, IdentityStore.Account.x25519)
            save(me.ed25519PrivateKeyRaw, IdentityStore.Account.ed25519)
        }
        val environment = AppEnvironment(
            context = appContext,
            scope = scope,
            identityStore = IdentityStore(secrets),
            databaseFactory = {
                AppDatabase.makeTemporary(appContext).also { db -> runBlocking { seed(db, me) } }
            },
            transportFactory = { StoreScreenshotTransport(it) },
            mediaStoreFactory = { MediaStore.makeTemporary() },
            notifier = NoopNotifier(),
        )
        AppSettings.loungeRulesAcceptedVersion = AppSettings.loungeRulesVersion
        AppSettings.isDemoModeEnabled = false
        AppSettings.smartRepliesEnabled = false
        AppSettings.isNearbyVisible = true
        AppSettings.isOpenToGames = true

        scope.launch {
            val router = environment.router.filterNotNull().first()
            launch { startLiveState(router, environment) }
            delay(1_500)
            when (screen) {
                Screen.chats -> Unit
                Screen.conversation -> conversationToShow?.let { AppNavigator.openConversation(it) }
                Screen.cabin -> flightLoungeID?.let { AppNavigator.openConversation(it) }
                Screen.lounge -> AppNavigator.openConversation(ConversationRecord.cabinLoungeID)
                Screen.cards -> overlay.value = OverlayKind.cards
                Screen.nearby -> AppNavigator.openNearby()
                Screen.flightcard -> overlay.value = OverlayKind.flightcard
            }
        }
        return environment
    }

    /** Tavolo da gioco e cartolina del volo, sopra alle tab (chiamato da `MainTabView`). */
    @Composable
    fun Overlay() {
        val current by overlay.collectAsStateWithLifecycle()
        when (current) {
            OverlayKind.cards -> scopaState?.let { state ->
                CardTableDialog(state = state, onAction = {}, onDismiss = {}, initialSelectedCard = preselectedCard)
            }
            OverlayKind.flightcard -> flightTrip?.let { trip ->
                FlightCardSheet(onDismiss = {}, trip = trip, tripStats = flightStats)
            }
            null -> Unit
        }
    }

    // MARK: Seed del database

    private suspend fun seed(database: AppDatabase, keys: IdentityKeys) {
        val now = System.currentTimeMillis()
        // Orari coerenti con la barra di stato (09:41): l'ultimo messaggio e' delle 09:40.
        val nineForty = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 40); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val clock = minOf(now, nineForty)
        fun ago(minutes: Double): Long = clock - (minutes * 60_000).toLong()
        val day = 86_400_000L
        val myName = t("Giulia", "Julia")
        val myNodeID = keys.nodeID

        val identity = IdentityRecord(identity = keys, nickname = myName, avatarColorHex = "EC4899", createdAt = now - 30 * day)
            .copy(avatarImage = avatarImage(myName.take(1), "EC4899"))

        val pass = BoardingPass(carrier = "AZ", flightNumber = "204", origin = "FCO", destination = "JFK", seat = "14A", departureDay = now)
        val marco = SeedPeer.marco.record(now)
        val sofia = SeedPeer.sofia.record(now)
        val luca = SeedPeer.luca.record(now)
        val chiara = SeedPeer.chiara.record(now)
        val mamma = SeedPeer.mamma.record(now)

        var lounge = ConversationRecord.flightLounge(pass, createdAt = now - 3 * 3_600_000L).copy(isPinned = true)
        var marcoChat = ConversationRecord.direct(marco, createdAt = now - 20 * day)
        var sofiaChat = ConversationRecord.direct(sofia, createdAt = now - 9 * day)
        var lucaChat = ConversationRecord.direct(luca, createdAt = now - 4 * day)
        var chiaraChat = ConversationRecord.direct(chiara, createdAt = now - 30 * day)
        var mammaChat = ConversationRecord.direct(mamma, createdAt = now - 60 * day)
        var tripGroup = ConversationRecord.group(
            id = UUID.randomUUID(), title = t("Vacanza a New York 🗽", "New York trip 🗽"),
            secretKey = CryptoEngine.randomGroupSecretKey(), createdAt = now - 12 * day,
        )
        var cabin = ConversationRecord.cabinLounge(createdAt = now - 2 * 3_600_000L)

        val messages = ArrayList<MessageRecord>()
        fun text(
            conversation: ConversationRecord, peer: PeerRecord?, body: String, minutes: Double,
            name: String? = null, status: DeliveryStatus = DeliveryStatus.read, hops: Int = 1,
        ) {
            val outgoing = peer == null && name == null
            messages += MessageRecord(
                msgID = CryptoEngine.randomMessageID(),
                conversationID = conversation.id,
                senderNodeID = if (outgoing) myNodeID else peer?.nodeID ?: SeedPeer.nodeID(name.orEmpty()),
                senderNickname = if (outgoing) myName else peer?.nickname ?: name,
                contentType = MessageContentType.text,
                text = body,
                isOutgoing = outgoing,
                deliveryStatus = if (outgoing) status else DeliveryStatus.read,
                hopCountReceived = if (outgoing) null else hops,
                createdAtMs = ago(minutes),
            )
        }

        // Marco: raggiungibile solo tramite altri passeggeri (schermata 2).
        text(marcoChat, marco, t("Sono al gate B12, tu dove sei?", "I'm at gate B12, where are you?"), 58.0, hops = 2)
        text(marcoChat, null, t("Già a bordo! Fila 14, lato finestrino ✈️", "Already on board! Row 14, window seat ✈️"), 56.0)
        text(marcoChat, marco, t("Io sono in fila 27, tutto in fondo 😅", "I'm in row 27, all the way at the back 😅"), 41.0, hops = 2)
        text(marcoChat, null, t("Ti scrivo senza Wi-Fi, che magia", "Texting you with no Wi-Fi, this is magic"), 39.0)
        text(marcoChat, marco, t("Il tuo messaggio è passato dai telefoni di due passeggeri!", "Your message just hopped through two passengers' phones!"), 37.0, hops = 3)
        text(marcoChat, null, t("All'arrivo dividiamo il taxi per Manhattan?", "Split a cab to Manhattan when we land?"), 12.0)
        text(marcoChat, marco, t("Affare fatto 🚕 Ci vediamo al nastro bagagli", "Deal 🚕 See you at baggage claim"), 9.0, hops = 2)
        text(marcoChat, null, t("Perfetto, a dopo!", "Perfect, see you later!"), 7.0, status = DeliveryStatus.deliveredAck)

        // Sofia: partita a Scopa in corso.
        text(sofiaChat, sofia, t("Rivincita a Scopa? Stavolta il settebello è mio 😏", "Rematch at Scopa? The settebello is mine this time 😏"), 30.0)
        text(sofiaChat, null, t("Accetto la sfida!", "Challenge accepted!"), 29.0)
        val gameMsgID = CryptoEngine.randomMessageID()
        makeScopaState(gameMsgID.toHex(), sofiaChat.id, myNodeID, myName, sofia)?.let { (state, card) ->
            scopaState = state
            preselectedCard = card
            messages += MessageRecord(
                msgID = gameMsgID, conversationID = sofiaChat.id, senderNodeID = myNodeID, senderNickname = myName,
                contentType = MessageContentType.game, gameState = state.encoded(), isOutgoing = true,
                deliveryStatus = DeliveryStatus.read, createdAtMs = ago(28.0),
            )
        }
        text(sofiaChat, sofia, t("Tocca a te! 🃏", "Your turn! 🃏"), 3.0)

        // Luca: sta scrivendo.
        text(lucaChat, luca, t("Hai visto che film ci sono a bordo?", "Have you seen the movies on board?"), 22.0)
        text(lucaChat, null, t("Sto guardando Dune, tu?", "Watching Dune, you?"), 20.0)

        // Gruppo di viaggio.
        text(tripGroup, chiara, t("Chi prenota il brunch domenica? 🥞", "Who's booking Sunday brunch? 🥞"), 95.0)
        text(tripGroup, marco, t("Io! Tavolo per 4 a Brooklyn", "Me! Table for 4 in Brooklyn"), 90.0, hops = 2)
        text(tripGroup, sofia, t("Allora sabato si va al MoMA", "Then MoMA on Saturday"), 18.0)

        // Chiara e Mamma: fuori portata, il messaggio aspetta.
        text(chiaraChat, chiara, t("Buon viaggio! Mandami una foto di New York 🗽", "Have a great trip! Send me a pic of New York 🗽"), 60.0 * 20)
        text(chiaraChat, null, t("Promesso!", "Promise!"), 60.0 * 19.8)
        text(mammaChat, null, t("Decollati! Ti scrivo appena atterro ❤️", "We took off! I'll text you when I land ❤️"), 150.0, status = DeliveryStatus.queued)

        // Cabina del volo AZ 204.
        val elena = SeedPeer.elena.name
        val davide = SeedPeer.davide.name
        val kenji = SeedPeer.kenji.name
        text(lounge, null, t("Qualcuno sa se da JFK c'è un treno per Manhattan? 🚆", "Anyone know if there's a train from JFK to Manhattan? 🚆"), 34.0, name = elena)
        text(lounge, null, t("AirTrain fino a Jamaica, poi la metro E. Circa 45 minuti.", "AirTrain to Jamaica, then the E subway. About 45 minutes."), 32.0, name = davide, hops = 2)
        text(lounge, null, t("Fila 31: si vede la Groenlandia dal finestrino! 🏔️", "Row 31: you can see Greenland out the window! 🏔️"), 21.0, name = kenji, hops = 3)
        text(lounge, null, t("Qualcuno ha un caricatore USB-C da prestare? Fila 14 🔌", "Anyone have a USB-C charger to lend? Row 14 🔌"), 15.0)
        text(lounge, sofia, t("Ce l'ho io, te lo passo dopo il carrello ☕️", "I do, I'll pass it over after the drinks cart ☕️"), 14.0)
        text(lounge, null, t("Chi fa una partita a Scopa per ingannare il tempo? 🃏", "Who's up for a card game to pass the time? 🃏"), 6.0, name = elena)
        text(lounge, luca, t("Io ci sto! Sfidami da Persone vicine", "I'm in! Challenge me from Nearby"), 4.0)

        // Cabina Pubblica (12 ore).
        text(cabin, null, t("Il comandante dice 20 minuti di anticipo 🎉", "Captain says we're 20 minutes early 🎉"), 50.0, name = t("Roberta", "Rachel"), hops = 2)
        text(cabin, null, t("Qualcuno va a Brooklyn? Dividiamo un taxi", "Anyone heading to Brooklyn? Let's share a cab"), 44.0, name = "Tom")
        text(cabin, null, t("Consiglio: il tiramisù del menu è ottimo 😋", "Tip: the tiramisu on the menu is great 😋"), 31.0, name = t("Lucia", "Lucy"), hops = 3)
        text(cabin, null, t("Io! Scrivimi dopo l'atterraggio", "Me! Message me after landing"), 29.0)
        text(cabin, null, t("Bimbo in fila 9 che non dorme: chi ha delle carte da prestare? 🃏", "Kid in row 9 can't sleep: anyone have cards to lend? 🃏"), 11.0, name = "Ahmed", hops = 2)

        fun last(conversation: ConversationRecord): Long? =
            messages.filter { it.conversationID == conversation.id }.maxOfOrNull { it.createdAtMs }
        marcoChat = marcoChat.copy(lastMessageAtMs = last(marcoChat))
        sofiaChat = sofiaChat.copy(lastMessageAtMs = last(sofiaChat), unreadCount = 1)
        lucaChat = lucaChat.copy(lastMessageAtMs = last(lucaChat))
        chiaraChat = chiaraChat.copy(lastMessageAtMs = last(chiaraChat))
        mammaChat = mammaChat.copy(lastMessageAtMs = last(mammaChat))
        tripGroup = tripGroup.copy(lastMessageAtMs = last(tripGroup), unreadCount = 3)
        lounge = lounge.copy(lastMessageAtMs = last(lounge), unreadCount = 5)
        cabin = cabin.copy(lastMessageAtMs = last(cabin))

        // Passeggeri non ancora contatti: solo il profilo (foto nelle Persone vicine).
        val peers = listOf(marco, sofia, luca, chiara, mamma) +
            listOf(SeedPeer.elena, SeedPeer.davide, SeedPeer.kenji, SeedPeer.paolo, SeedPeer.anna).map { it.record(now, trusted = false) }
        val conversations = listOf(marcoChat, sofiaChat, lucaChat, chiaraChat, mammaChat, tripGroup, lounge, cabin)
        val members = listOf(chiara, marco, sofia).map { GroupMemberRecord(tripGroup.id, it, joinedAt = now - 12 * day) } +
            listOf(SeedPeer.sofia, SeedPeer.luca, SeedPeer.elena, SeedPeer.davide, SeedPeer.kenji)
                .map { GroupMemberRecord(lounge.id, it.record(now, trusted = false), joinedAt = now - 3_600_000L) }

        database.identityDao().upsert(identity)
        peers.forEach { database.peerDao().insert(it) }
        conversations.forEach { database.conversationDao().insert(it) }
        members.forEach { database.groupMemberDao().upsert(it) }
        messages.forEach { database.messageDao().insert(it) }

        conversationToShow = marcoChat.id
        flightLoungeID = lounge.id
        flightCode = lounge.flightCode
        typingConversationID = lucaChat.id

        // Cartolina del volo (ricordo del viaggio) con classifica.
        val trip = FlightTrip(
            key = pass.canonicalKey, title = pass.loungeTitle, startedAtMs = ago(6 * 60.0), endedAtMs = ago(0.0),
            people = (1..14).map { "seed-person-$it" }.toSet(),
            messagesSent = 48, messagesReceived = 79, gamesPlayed = 4, gamesWon = 3, notified = true,
        )
        flightTrip = trip
        flightStats = FlightStats(
            period = FlightStats.Period.trip, messagesSent = 48, messagesReceived = 79, peopleMet = 14,
            photos = 6, voiceNotes = 4, gamesPlayed = 4, gamesWon = 3, longestHop = 3,
            flightTitles = listOf(pass.loungeTitle),
        )
    }

    // MARK: Stato vivo (in memoria)

    /** Persone vicine, richiesta "Sfida" in arrivo, "sta scrivendo" e telemetria del ponte, tenuti vivi. */
    private suspend fun startLiveState(router: MeshRouterActor, environment: AppEnvironment) {
        val now = System.currentTimeMillis()
        val challenger = SeedPeer.riccardo.keys
        router.nearby.addIncoming(
            IncomingContactRequest(
                requestID = Random.nextBytes(16),
                senderNodeID = challenger.nodeID, x25519Pub = challenger.x25519PublicKeyRaw,
                ed25519Pub = challenger.ed25519PublicKeyRaw, nickname = SeedPeer.riccardo.name,
                avatarColorHex = SeedPeer.riccardo.colorHex,
                greeting = t("Partita veloce prima dell'atterraggio?", "Quick game before we land?"),
                gameKind = GameKind.SCOPA.number, receivedAtMs = now,
            ),
            now,
        )
        var tick = 0
        while (true) {
            val database = environment.database.value ?: break
            typingConversationID?.let { id ->
                router.typing.update(id, SeedPeer.luca.keys.nodeID.toHex(), SeedPeer.luca.name, isTyping = true)
            }
            if (tick % 10 == 0) {
                val time = System.currentTimeMillis()
                for (person in nearbyPeople(time)) router.nearby.updatePresence(person, time)
                // Marco resta "tramite 1 persona" (la freschezza del ponte e' di 2 minuti).
                runCatching { database.peers.markSeen(nodeID = SeedPeer.marco.keys.nodeID, hopCount = 2, rssi = null) }
            }
            tick += 1
            delay(2_000)
        }
    }

    /** Persone vicine (PRESENCE finti): quattro sul nostro volo, due no. */
    private fun nearbyPeople(now: Long): List<NearbyPerson> = listOf(
        NearbyEntry(SeedPeer.sofia, onFlight = true, games = false, rssi = -52, contact = true),
        NearbyEntry(SeedPeer.elena, onFlight = true, games = true, rssi = -60),
        NearbyEntry(SeedPeer.davide, onFlight = true, games = false, rssi = -66),
        NearbyEntry(SeedPeer.kenji, onFlight = true, games = true, rssi = -74),
        NearbyEntry(SeedPeer.paolo, onFlight = false, games = true, rssi = -79),
        NearbyEntry(SeedPeer.anna, onFlight = false, games = false, rssi = -86),
    ).map { entry ->
        val keys = entry.peer.keys
        NearbyPerson(
            nodeID = keys.nodeID, x25519Pub = keys.x25519PublicKeyRaw, ed25519Pub = keys.ed25519PublicKeyRaw,
            nickname = entry.peer.name, avatarColorHex = entry.peer.colorHex,
            flightCode = if (entry.onFlight) flightCode else null, openToGames = entry.games,
            rssi = entry.rssi, lastSeenAtMs = now, isContact = entry.contact,
        )
    }

    private class NearbyEntry(val peer: SeedPeer, val onFlight: Boolean, val games: Boolean, val rssi: Int, val contact: Boolean = false)

    // MARK: Partita a Scopa

    /**
     * Seconda mano di una partita (prima mano giocata davvero dal motore), ferma al nostro turno con una
     * presa disponibile. Restituisce lo stato e la carta da selezionare.
     */
    private fun makeScopaState(
        gameID: String, conversationID: String, myNodeID: ByteArray, myName: String, opponent: PeerRecord,
    ): Pair<GameSessionState, Int>? {
        val rng = Random(204)
        repeat(2_000) {
            val game = runCatching { ScopaGame(playerCount = 2, dealer = 1, deck = (0 until 40).shuffled(rng)) }.getOrNull()
                ?: return@repeat
            var moves = 0
            // Tavolo troppo pieno: la ricerca delle prese diventa esponenziale, si scarta la partita.
            while (game.phase == ScopaGame.Phase.playing && game.table.size <= 8 && botPlay(game)) moves += 1
            if (game.phase != ScopaGame.Phase.handOver || game.scores[0] <= game.scores[1] || game.scores[1] <= 0) return@repeat
            if (runCatching { game.deal(deck = (0 until 40).shuffled(rng), dealer = game.nextDealer) }.isFailure) return@repeat
            val target = 5 + rng.nextInt(6)
            var played = 0
            // Si gioca almeno `target` carte, poi ci si ferma al primo nostro turno con la mano piena.
            while (game.phase == ScopaGame.Phase.playing && game.table.size <= 8 &&
                !(played >= target && game.turn == 0 && game.hands[0].size == 3) && botPlay(game)
            ) { played += 1; moves += 1 }
            if (game.phase != ScopaGame.Phase.playing || game.turn != 0 || game.hands[0].size != 3 ||
                game.table.size !in 3..5 || game.remainingInDeck <= 0 || game.scope[0] + game.scope[1] <= 0
            ) return@repeat
            // Carta con una sola presa di almeno due carte: l'evidenziazione si vede bene.
            val card = game.hands[0].firstOrNull { card ->
                val options = game.captureOptions(card)
                options.size == 1 && options[0].size >= 2
            } ?: return@repeat
            val state = GameSessionState(
                gameID = gameID, kind = GameKind.SCOPA.number, conversationID = conversationID,
                opponentNodeID = opponent.nodeID, iAmInviter = true,
                status = GameStatus.active, sequence = (moves + 2).toLong(), myTurn = true,
                seats = listOf(myNodeID, opponent.nodeID), seatNames = listOf(myName, opponent.nickname), mySeat = 0,
                scopa = game,
            )
            return state to card
        }
        return null
    }

    /** Giocatore automatico semplice: la presa piu' ricca, altrimenti la carta piu' bassa. */
    private fun botPlay(game: ScopaGame): Boolean {
        val seat = game.turn
        val hand = game.hands[seat]
        var best: Pair<Int, List<Int>>? = null
        for (card in hand) {
            for (option in game.captureOptions(card)) {
                if (option.size >= (best?.second?.size ?: 0)) best = card to option
            }
        }
        best?.let { (card, capture) -> return runCatching { game.play(seat, card, capture) }.isSuccess }
        val card = hand.minByOrNull { NeapolitanCards.rank(it) } ?: return false
        return runCatching { game.play(seat, card, emptyList()) }.isSuccess
    }

    // MARK: Avatar

    /** Avatar con l'iniziale su sfumatura del colore scelto (PNG, come una foto profilo). */
    internal fun avatarImage(initial: String, hex: String): ByteArray {
        val size = 160
        val base = android.graphics.Color.parseColor("#$hex")
        val dark = android.graphics.Color.rgb(
            (android.graphics.Color.red(base) * 0.65f).toInt(),
            (android.graphics.Color.green(base) * 0.65f).toInt(),
            (android.graphics.Color.blue(base) * 0.65f).toInt(),
        )
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), base, dark, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 76f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val y = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(initial.uppercase(), size / 2f, y, text)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    // MARK: Persone finte

    /** Compagni di viaggio finti con chiavi deterministiche (stessi `node_id` a ogni avvio). */
    private class SeedPeer(val tag: String, val italianName: String, val englishName: String, val colorHex: String, val rssi: Int?) {

        val name: String get() = if (italian) italianName else englishName

        val keys: IdentityKeys by lazy {
            IdentityKeys(
                x25519PrivateRaw = sha256("AeroChat-StoreScreenshots-x25519-$tag"),
                ed25519PrivateRaw = sha256("AeroChat-StoreScreenshots-ed25519-$tag"),
            )
        }

        fun record(now: Long, trusted: Boolean = true): PeerRecord {
            val keys = keys
            val isMarco = tag == marco.tag
            return PeerRecord(
                nodeID = keys.nodeID, x25519Pub = keys.x25519PublicKeyRaw, ed25519Pub = keys.ed25519PublicKeyRaw,
                nickname = name, isTrusted = trusted, createdAtMs = now - 30 * 86_400_000L,
                lastSeenAtMs = if (isMarco) now else null, lastHopCount = if (isMarco) 2 else null,
                avatarColorHex = colorHex, avatarImage = avatarImage(name.take(1), colorHex), profileUpdatedAtMs = now,
            )
        }

        companion object {
            val me = SeedPeer("me", "Giulia", "Julia", "EC4899", null)
            val marco = SeedPeer("marco", "Marco", "Mark", "F59E0B", null)
            val sofia = SeedPeer("sofia", "Sofia", "Sophie", "8B5CF6", -52)
            val luca = SeedPeer("luca", "Luca", "Luke", "10B981", -63)
            val chiara = SeedPeer("chiara", "Chiara", "Claire", "F97316", null)
            val mamma = SeedPeer("mamma", "Mamma", "Mom", "00E5FF", null)
            val elena = SeedPeer("elena", "Elena", "Emily", "00E5FF", -60)
            val davide = SeedPeer("davide", "Davide", "David", "F59E0B", -68)
            val kenji = SeedPeer("kenji", "Kenji", "Kenji", "10B981", -74)
            val paolo = SeedPeer("paolo", "Paolo", "Paul", "F97316", null)
            val anna = SeedPeer("anna", "Anna", "Hannah", "EC4899", null)
            val riccardo = SeedPeer("riccardo", "Riccardo", "Ryan", "8B5CF6", null)

            /** Vicini radio del trasporto finto. */
            val neighbors: List<SeedPeer> get() = listOf(sofia, luca, elena, davide, kenji)

            fun nodeID(name: String): ByteArray =
                listOf(elena, davide, kenji, paolo, anna, riccardo, marco, sofia, luca, chiara).firstOrNull { it.name == name }?.keys?.nodeID
                    ?: sha256(name).copyOf(8)

            private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        }
    }

    // MARK: Trasporto finto

    /** Trasporto senza radio: Bluetooth acceso e qualche vicino collegato; i frame in uscita vengono scartati. */
    private class StoreScreenshotTransport(override val localNodeID: ByteArray) : TransportProvider {

        private val links = SeedPeer.neighbors.mapIndexed { index, peer ->
            PeerLink(
                id = LinkID(deviceID = "02:00:00:00:00:%02X".format(index + 1), role = LinkRole.central),
                nodeID = peer.keys.nodeID, rssi = peer.rssi, connectedAt = System.currentTimeMillis(),
            )
        }
        private val channel = Channel<TransportEvent>(Channel.UNLIMITED)

        override suspend fun start() {
            channel.send(TransportEvent.RadioStateChanged(RadioState.poweredOn))
            for (link in links) channel.send(TransportEvent.LinkOpened(link))
        }

        override suspend fun stop() { channel.close() }
        override fun events(): Flow<TransportEvent> = channel.receiveAsFlow()
        override suspend fun radioState(): RadioState = RadioState.poweredOn
        override suspend fun activeLinks(): List<PeerLink> = links
        override suspend fun send(frame: ByteArray, to: LinkID) {}
        override suspend fun send(frame: ByteArray, toNode: ByteArray): Boolean = links.any { it.nodeID.contentEquals(toNode) }
        override suspend fun broadcast(frame: ByteArray, excluding: Set<LinkID>): Int = links.count { it.id !in excluding }
        override suspend fun setTrustedPeripheralIdentifiers(identifiers: Set<String>) {}
    }
}
