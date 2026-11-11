package com.stefanobrandi.aerochat.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.calls.CallService
import com.stefanobrandi.aerochat.core.crypto.IdentityKeys
import com.stefanobrandi.aerochat.core.crypto.IdentityStore
import com.stefanobrandi.aerochat.core.crypto.KeychainStore
import com.stefanobrandi.aerochat.core.crypto.PairingPayload
import com.stefanobrandi.aerochat.core.emergency.EmergencyAlert
import com.stefanobrandi.aerochat.core.emergency.EmergencyService
import com.stefanobrandi.aerochat.core.flight.BoardingPass
import com.stefanobrandi.aerochat.core.flight.FlightTrip
import com.stefanobrandi.aerochat.core.flight.TripMemory
import com.stefanobrandi.aerochat.core.flight.TripMemoryStore
import com.stefanobrandi.aerochat.core.games.GameOutcome
import com.stefanobrandi.aerochat.core.games.GameStatus
import com.stefanobrandi.aerochat.core.mesh.IncomingContactRequest
import com.stefanobrandi.aerochat.core.mesh.NearbyPerson
import com.stefanobrandi.aerochat.core.moderation.AppSettings
import com.stefanobrandi.aerochat.core.quiz.QuizQuestionBank
import com.stefanobrandi.aerochat.core.quiz.QuizService
import com.stefanobrandi.aerochat.core.review.DemoPeerService
import com.stefanobrandi.aerochat.core.entitlements.EntitlementManager
import com.stefanobrandi.aerochat.core.entitlements.EntitlementState
import com.stefanobrandi.aerochat.core.entitlements.ProPassService
import com.stefanobrandi.aerochat.core.games.GameService
import com.stefanobrandi.aerochat.core.media.MediaStore
import com.stefanobrandi.aerochat.core.media.MediaTransferService
import com.stefanobrandi.aerochat.core.mesh.LocalNotificationCenter
import com.stefanobrandi.aerochat.core.mesh.LocalProfile
import com.stefanobrandi.aerochat.core.mesh.MeshNotifier
import com.stefanobrandi.aerochat.core.mesh.MeshRouterActor
import com.stefanobrandi.aerochat.core.mesh.Neighbor
import com.stefanobrandi.aerochat.core.mesh.RouterError
import com.stefanobrandi.aerochat.core.mesh.RouterEvent
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.core.transport.BLEPermissions
import com.stefanobrandi.aerochat.core.transport.BLETransportActor
import com.stefanobrandi.aerochat.core.transport.RadioState
import com.stefanobrandi.aerochat.core.transport.TransportProvider
import com.stefanobrandi.aerochat.data.AppDatabase
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.ConversationType
import com.stefanobrandi.aerochat.data.records.IdentityRecord
import com.stefanobrandi.aerochat.data.records.MessageRecord
import com.stefanobrandi.aerochat.data.records.PeerRecord
import com.stefanobrandi.aerochat.data.records.ReportReason
import com.stefanobrandi.aerochat.data.records.ReportRecord
import com.stefanobrandi.aerochat.data.records.canBeEdited
import com.stefanobrandi.aerochat.data.records.canBeRevised
import com.stefanobrandi.aerochat.proto.RevisionAction
import com.stefanobrandi.aerochat.BuildConfig
import com.stefanobrandi.aerochat.ui.AppNavigator
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.onboarding.OnboardingView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Composition root dell'app: apre il database, carica (o crea) l'identita', avvia radio BLE, router
 * mesh e servizi (media, giochi, monetizzazione) e riflette il loro stato verso Compose.
 *
 * Un'istanza per processo, posseduta da [com.stefanobrandi.aerochat.AeroChatApplication] e raggiungibile dalla
 * UI con `LocalAppEnvironment.current`. Ogni proprieta' osservabile di Swift e' uno [StateFlow] con lo
 * stesso nome (in Compose: `environment.phase.collectAsStateWithLifecycle()`); i valori derivati
 * ([hasCompletedOnboarding], [nickname], [isPro], ...) leggono il `.value` corrente: per ricomporre al
 * cambio, la schermata deve raccogliere il flow da cui dipendono (`identityRecord`, `entitlementState`).
 *
 * Tutte le mutazioni di stato avvengono sul main thread ([scope] usa `Dispatchers.Main.immediate`).
 */
class AppEnvironment(
    context: Context,
    private val scope: CoroutineScope,
    private val identityStore: IdentityStore = IdentityStore(KeychainStore(context.applicationContext)),
    private val databaseFactory: () -> AppDatabase = { AppDatabase.makeShared(context.applicationContext) },
    private val transportFactory: (ByteArray) -> TransportProvider =
        { BLETransportActor(context.applicationContext, localNodeID = it) },
    private val mediaStoreFactory: () -> MediaStore = { MediaStore.makeShared(context.applicationContext) },
    private val notifier: MeshNotifier = LocalNotificationCenter(context.applicationContext),
) {

    sealed interface Phase {
        data object Booting : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

    private val context: Context = context.applicationContext

    init {
        // Preferenze lette anche dal router (filtro linguaggio) fuori dal main thread: caricate subito.
        AppSettings.initialize(this.context)
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Booting)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _database = MutableStateFlow<AppDatabase?>(null)
    val database: StateFlow<AppDatabase?> = _database.asStateFlow()

    private val _identityKeys = MutableStateFlow<IdentityKeys?>(null)
    val identityKeys: StateFlow<IdentityKeys?> = _identityKeys.asStateFlow()

    private val _identityRecord = MutableStateFlow<IdentityRecord?>(null)
    val identityRecord: StateFlow<IdentityRecord?> = _identityRecord.asStateFlow()

    // Servizi (disponibili dopo `startMesh`).
    private val _router = MutableStateFlow<MeshRouterActor?>(null)
    val router: StateFlow<MeshRouterActor?> = _router.asStateFlow()

    private val _entitlements = MutableStateFlow<EntitlementManager?>(null)
    val entitlements: StateFlow<EntitlementManager?> = _entitlements.asStateFlow()

    private val _proPass = MutableStateFlow<ProPassService?>(null)
    val proPass: StateFlow<ProPassService?> = _proPass.asStateFlow()

    private val _media = MutableStateFlow<MediaTransferService?>(null)
    val media: StateFlow<MediaTransferService?> = _media.asStateFlow()

    private val _games = MutableStateFlow<GameService?>(null)
    val games: StateFlow<GameService?> = _games.asStateFlow()

    private val _mediaStore = MutableStateFlow<MediaStore?>(null)
    val mediaStore: StateFlow<MediaStore?> = _mediaStore.asStateFlow()

    /** Chiamate vocali in tempo reale (osservato dalla schermata di chiamata). */
    private val _calls = MutableStateFlow<CallService?>(null)
    val calls: StateFlow<CallService?> = _calls.asStateFlow()

    /** Compagno simulato (revisione dello store / prova con un solo telefono), attivo solo se abilitato. */
    private var demoPeer: DemoPeerService? = null

    /** Quiz di cabina e avvisi di emergenza (entrambi sulla Lounge pubblica). */
    private val _quiz = MutableStateFlow<QuizService?>(null)
    val quiz: StateFlow<QuizService?> = _quiz.asStateFlow()

    private val _emergency = MutableStateFlow<EmergencyService?>(null)
    val emergency: StateFlow<EmergencyService?> = _emergency.asStateFlow()

    /** Stato del quiz in corso (null = nessuno), osservato dal foglio del quiz e dal badge in Cabina. */
    private val _quizState = MutableStateFlow<QuizService.State?>(null)
    val quizState: StateFlow<QuizService.State?> = _quizState.asStateFlow()

    /** Ultimo avviso di emergenza ricevuto, mostrato come banner finche' l'utente non lo chiude ([setActiveEmergency] `null`). */
    private val _activeEmergency = MutableStateFlow<EmergencyAlert?>(null)
    val activeEmergency: StateFlow<EmergencyAlert?> = _activeEmergency.asStateFlow()

    /** Swift `activeEmergency = ...`: la UI lo azzera quando l'utente chiude il banner. */
    fun setActiveEmergency(alert: EmergencyAlert?) {
        _activeEmergency.value = alert
    }

    // Stato osservabile dalla UI.
    private val _radioState = MutableStateFlow(RadioState.unknown)
    val radioState: StateFlow<RadioState> = _radioState.asStateFlow()

    private val _neighbors = MutableStateFlow<List<Neighbor>>(emptyList())
    val neighbors: StateFlow<List<Neighbor>> = _neighbors.asStateFlow()

    // MARK: Persone vicine (NEARBY_SPEC.md)

    /** Persone vicine annunciate da `PRESENCE` (cache del router, scade dopo 3 minuti). */
    private val _nearbyPeople = MutableStateFlow<List<NearbyPerson>>(emptyList())
    val nearbyPeople: StateFlow<List<NearbyPerson>> = _nearbyPeople.asStateFlow()

    /** Richieste "Saluta"/"Sfida" ricevute in attesa di risposta. */
    private val _contactRequests = MutableStateFlow<List<IncomingContactRequest>>(emptyList())
    val contactRequests: StateFlow<List<IncomingContactRequest>> = _contactRequests.asStateFlow()

    /** Avviso in app sulle persone vicine (richiesta arrivata, accettata, rifiutata); la UI lo chiude. */
    sealed interface NearbyBanner {
        data class Request(val request: IncomingContactRequest) : NearbyBanner
        data class Accepted(val conversationID: String, val nickname: String) : NearbyBanner
        data class Declined(val nickname: String) : NearbyBanner
    }

    private val _nearbyBanner = MutableStateFlow<NearbyBanner?>(null)
    val nearbyBanner: StateFlow<NearbyBanner?> = _nearbyBanner.asStateFlow()

    fun dismissNearbyBanner() {
        _nearbyBanner.value = null
    }

    /** Modalita' aereo del telefono (scheda "Sei in volo" e fine del viaggio). */
    private val _airplaneMode = MutableStateFlow(false)
    val airplaneMode: StateFlow<Boolean> = _airplaneMode.asStateFlow()
    private var airplaneReceiver: BroadcastReceiver? = null

    /** Scheda contestuale in cima alla lista chat chiusa dall'utente (solo per questa sessione). */
    private val _homeCardDismissed = MutableStateFlow(false)
    val homeCardDismissed: StateFlow<Boolean> = _homeCardDismissed.asStateFlow()

    fun dismissHomeCard() {
        _homeCardDismissed.value = true
    }

    // MARK: Ricordo del viaggio

    private val tripMemory: TripMemory by lazy { TripMemory(TripMemoryStore(this.context)) }

    /** Volo in corso (contatori della classifica del volo), `null` a terra. */
    private val _activeTrip = MutableStateFlow<FlightTrip?>(null)
    val activeTrip: StateFlow<FlightTrip?> = _activeTrip.asStateFlow()

    /** Viaggio da mostrare nella cartolina (tocco sulla notifica "cartolina pronta"); la UI lo azzera. */
    private val _pendingTripCard = MutableStateFlow<FlightTrip?>(null)
    val pendingTripCard: StateFlow<FlightTrip?> = _pendingTripCard.asStateFlow()

    fun openTripCard(key: String) {
        _pendingTripCard.value = tripMemory.trip(key) ?: tripMemory.lastEndedTrip()
    }

    fun dismissTripCard() {
        _pendingTripCard.value = null
    }

    private val _relayQueueCount = MutableStateFlow(0)
    val relayQueueCount: StateFlow<Int> = _relayQueueCount.asStateFlow()

    /** Non letti totali (badge della scheda "Chat & Gruppi"), osservati dal database. */
    private val _totalUnread = MutableStateFlow(0)
    val totalUnread: StateFlow<Int> = _totalUnread.asStateFlow()
    private var totalUnreadTask: Job? = null

    /** `msg_id` dell'ultimo messaggio ricevuto (ogni ricezione e' un nuovo array → nuova emissione). */
    private val _lastReceivedMessageID = MutableStateFlow<ByteArray?>(null)
    val lastReceivedMessageID: StateFlow<ByteArray?> = _lastReceivedMessageID.asStateFlow()

    private val _entitlementState = MutableStateFlow(EntitlementState.free)
    val entitlementState: StateFlow<EntitlementState> = _entitlementState.asStateFlow()

    /** Avanzamento (0...1) dei media in trasferimento, per `msg_id` dell'header (chiave: hex). */
    private val _mediaProgress = MutableStateFlow<Map<String, Double>>(emptyMap())
    val mediaProgress: StateFlow<Map<String, Double>> = _mediaProgress.asStateFlow()

    /**
     * Esito di un link di invito `aeromesh://` aperto da fuori (Messaggi, browser, Nearby Share), mostrato
     * come avviso dalla UI; la UI lo azzera con [dismissIncomingLinkMessage].
     */
    private val _incomingLinkMessage = MutableStateFlow<String?>(null)
    val incomingLinkMessage: StateFlow<String?> = _incomingLinkMessage.asStateFlow()

    fun dismissIncomingLinkMessage() {
        _incomingLinkMessage.value = null
    }

    /** "Prova subito" dall'onboarding: aprire la chat demo appena parte la mesh. */
    private var openDemoAfterStart = false

    /** Completa l'onboarding e apre la chat con il passeggero virtuale ("Prova subito"). */
    suspend fun completeOnboardingAndTryDemo(nickname: String, avatarColorHex: String) {
        openDemoAfterStart = true
        completeOnboarding(nickname = nickname, avatarColorHex = avatarColorHex)
    }

    /** Link arrivato prima che la mesh fosse accesa (avvio a freddo, onboarding): si abbina a `startMesh`. */
    private var pendingPairingURI: String? = null

    /** Aggiornamento coalescente della notifica di stato "a bordo" (equivalente della Live Activity). */
    private var liveStatusRefresh: Job? = null
    private var lastLiveStatusAtMs = 0L

    private val backgroundTasks = mutableListOf<Job>()

    /**
     * Processo in primo piano (almeno un'Activity visibile), da `ProcessLifecycleOwner`: decide la modalita'
     * della radio. Un unico StateFlow raccolto in sequenza evita le corse tra eventi su scope diversi.
     */
    private val appInForeground = MutableStateFlow(false)
    /** Osservatore del ciclo di vita del processo, registrato da [startMesh] e rimosso da [stopMesh]. */
    private var processLifecycleObserver: LifecycleEventObserver? = null
    /** Radar di prossimita' a schermo: solo allora serve il polling RSSI dei link. */
    private var isRadarVisible = false
    private var isBootstrapping = false

    /** `true` dopo che l'utente ha completato l'onboarding (nickname salvato). */
    val hasCompletedOnboarding: Boolean get() = _identityRecord.value != null

    val nickname: String
        get() = _identityRecord.value?.nickname ?: context.getString(R.string.shell_default_nickname)
    val avatarColorHex: String get() = _identityRecord.value?.avatarColorHex ?: OnboardingView.palette[0]
    val avatarImage: ByteArray? get() = _identityRecord.value?.avatarImage

    /** Profilo pubblico annunciato ai contatti con `PROFILE_UPDATE`. */
    private val localProfile: LocalProfile?
        get() {
            val record = _identityRecord.value ?: return null
            return LocalProfile(
                nickname = record.nickname,
                avatarColorHex = record.avatarColorHex,
                avatarImage = record.avatarImage,
                updatedAtMs = record.profileUpdatedAtMs ?: record.createdAtMs,
            )
        }

    /**
     * In DEBUG tutte le funzioni Pro sono sbloccate per lo sviluppo; il Flight Group Pass verso gli
     * altri membri resta legato all'acquisto reale (`ProPassService` usa `entitlementState`).
     */
    val isPro: Boolean
        get() = if (BuildConfig.DEBUG) true else _entitlementState.value.isPro()

    val isMeshRunning: Boolean get() = _router.value != null

    /** Trasporto BLE corrente (per permessi / primo piano), `null` se la mesh e' spenta o in test. */
    val bleTransport: BLETransportActor? get() = _router.value?.transport as? BLETransportActor

    /** Avvio: DB (I/O fuori dal main thread) + chiavi nel Keystore + record identita' + mesh se gia' onboardato. */
    suspend fun bootstrap() {
        if (_phase.value != Phase.Booting || _database.value != null || isBootstrapping) return
        isBootstrapping = true
        try {
            val factory = databaseFactory
            val db = withContext(Dispatchers.IO) { factory() }
            val keys = identityStore.loadOrCreateKeys()
            val record = db.identities.fetch()
            _database.value = db
            _identityKeys.value = keys
            _identityRecord.value = record
            observeTotalUnread(db)
            _phase.value = Phase.Ready
            if (record != null) {
                startMesh()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _phase.value = Phase.Failed(e.toString())
        } finally {
            isBootstrapping = false
        }
    }

    /** Completa l'onboarding zero-account: salva nickname e colore avatar, poi accende la radio. */
    suspend fun completeOnboarding(nickname: String, avatarColorHex: String) {
        val database = _database.value ?: return
        val identityKeys = _identityKeys.value ?: return
        val trimmed = nickname.trim()
        val record = IdentityRecord(identity = identityKeys, nickname = trimmed, avatarColorHex = avatarColorHex)
        database.identities.save(record)
        _identityRecord.value = record
        startMesh()
    }

    suspend fun updateNickname(nickname: String) {
        val database = _database.value ?: return
        val trimmed = nickname.trim()
        if (trimmed.isEmpty()) return
        database.identities.updateNickname(trimmed)
        _identityRecord.update { it?.copy(nickname = trimmed) }
        _router.value?.setNickname(trimmed)
        _media.value?.setNickname(trimmed)
        _games.value?.setNickname(trimmed)
        _proPass.value?.setNickname(trimmed)
        _quiz.value?.setNickname(trimmed)
        _emergency.value?.setNickname(trimmed)
    }

    /** Modifica il profilo (nickname, colore, foto) e lo annuncia a tutti i contatti. */
    suspend fun updateProfile(nickname: String, avatarColorHex: String, avatarImage: ByteArray?) {
        val database = _database.value ?: return
        val trimmed = nickname.trim().take(MeshProtocol.maxNicknameLength)
        if (trimmed.isEmpty()) return
        val record = database.identities.updateProfile(
            nickname = trimmed, avatarColorHex = avatarColorHex, avatarImage = avatarImage,
        ) ?: return
        _identityRecord.value = record
        _media.value?.setNickname(trimmed)
        _games.value?.setNickname(trimmed)
        _proPass.value?.setNickname(trimmed)
        _quiz.value?.setNickname(trimmed)
        _emergency.value?.setNickname(trimmed)
        _calls.value?.setNickname(trimmed)
        val router = _router.value
        val profile = localProfile
        if (router != null && profile != null) {
            router.setProfile(profile, announce = true)
        }
    }

    /**
     * "Sta scrivendo" (`TYPING`) verso la chat, senza attendere la radio. Avviato senza dispatch: l'ordine
     * delle chiamate (true / false) arriva intatto al router, anche se la schermata della chat sta chiudendo.
     */
    fun sendTyping(isTyping: Boolean, conversation: ConversationRecord) {
        val router = _router.value ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                router.sendTyping(isTyping, conversation = conversation)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Indicatore effimero: un errore radio non interessa a nessuno.
            }
        }
    }

    /** La chat e' visibile: messaggi letti + conferme di lettura (doppia spunta colorata) ai mittenti. */
    suspend fun markConversationRead(conversation: ConversationRecord) {
        val router = _router.value
        if (router != null) {
            router.markConversationRead(conversation)
        } else {
            runCatching { _database.value?.messages?.markIncomingRead(conversationID = conversation.id) }
        }
        // Le notifiche della chat letta spariscono: il badge sull'icona scende.
        runCatching { notifier.cancelConversation(conversationID = conversation.id) }
        refreshLiveStatus()
    }

    /**
     * Badge in-app e sull'icona: la somma dei non letti viene osservata dal database; il numero delle
     * notifiche dei messaggi (`setNumber`) la legge al momento della pubblicazione.
     */
    private fun observeTotalUnread(database: AppDatabase) {
        (notifier as? LocalNotificationCenter)?.unreadCountProvider = { database.conversations.totalUnread() }
        totalUnreadTask?.cancel()
        totalUnreadTask = scope.launch {
            runCatching { database.conversations.observeTotalUnread().collect { _totalUnread.value = it } }
        }
    }

    // MARK: - Modifica e cancellazione per tutti

    /**
     * Modifica un nostro messaggio di testo entro 15 minuti: il nuovo testo sostituisce il vecchio anche sui
     * telefoni dei destinatari (`MESSAGE_REVISION` EDIT). Lancia [RouterError.RevisionNotAllowed] fuori finestra.
     */
    suspend fun editMessage(message: MessageRecord, newText: String, conversation: ConversationRecord) {
        val router = _router.value ?: throw RouterError.NotRunning
        if (!message.canBeEdited()) throw RouterError.RevisionNotAllowed
        router.sendRevision(message, action = RevisionAction.EDIT, newText = newText, conversation = conversation)
    }

    /**
     * Cancella per tutti un nostro messaggio (testo, foto, nota vocale) entro 15 minuti e rimuove il suo
     * file media dal disco. Lancia [RouterError.RevisionNotAllowed] fuori finestra.
     */
    suspend fun deleteMessageForEveryone(message: MessageRecord, conversation: ConversationRecord) {
        val router = _router.value ?: throw RouterError.NotRunning
        if (!message.canBeRevised()) throw RouterError.RevisionNotAllowed
        val deletion = router.sendRevision(message, action = RevisionAction.DELETE, conversation = conversation)
        removeMedia(deletion?.mediaPath)
    }

    private suspend fun removeMedia(relativePath: String?) {
        val path = relativePath ?: return
        val store = _mediaStore.value ?: return
        withContext(Dispatchers.IO) { runCatching { store.remove(relativePath = path) } }
    }

    /** Avvia trasporto BLE, router e servizi (idempotente). */
    suspend fun startMesh() {
        if (_router.value != null) return
        val database = _database.value ?: return
        val identityKeys = _identityKeys.value ?: return
        val transport = transportFactory(identityKeys.nodeID)
        val router = MeshRouterActor(
            identity = identityKeys, database = database, transport = transport,
            nickname = nickname, notifier = notifier,
        )
        _router.value = router
        localProfile?.let { router.setProfile(it, announce = false) }

        val routerEvents = router.events()
        backgroundTasks += scope.launch {
            routerEvents.collect { apply(it) }
        }
        // Servizio media iscritto a `inboundPackets` PRIMA di accendere la radio: header e chunk di una
        // foto arrivati appena aperto il link non vanno persi (i media non sono in store-and-forward).
        val store = withContext(Dispatchers.IO) { runCatching { mediaStoreFactory() }.getOrNull() }
        if (store != null) {
            _mediaStore.value = store
            val media = MediaTransferService(
                router = router, database = database, store = store,
                identity = identityKeys, nickname = nickname,
            )
            _media.value = media
            val mediaEvents = media.events()
            backgroundTasks += scope.launch {
                mediaEvents.collect { event -> applyMedia(event) }
            }
            media.start()
        }

        // Anche i giochi si iscrivono PRIMA di accendere la radio: le mosse ritrasmesse da un compagno
        // appena il link si apre venivano segnate come viste senza che nessuno le raccogliesse.
        val games = GameService(router = router, database = database, identity = identityKeys, nickname = nickname)
        _games.value = games
        games.start()

        // Quiz ed emergenze: stessi vincoli (iscrizione a `inboundPackets` prima della radio). Gli avvisi
        // rimasti in coda presso un vicino arrivano appena il link si apre.
        val quiz = QuizService(
            router = router, database = database, identity = identityKeys, nickname = nickname,
            questions = { QuizQuestionBank.questions(context) },
        )
        _quiz.value = quiz
        val quizEvents = quiz.events()
        backgroundTasks += scope.launch {
            quizEvents.collect { event ->
                if (event is QuizService.Event.Updated && event.state != _quizState.value) _quizState.value = event.state
            }
        }
        quiz.start()

        val emergency = EmergencyService(
            router = router, database = database, identity = identityKeys,
            nickname = nickname, notifier = notifier, context = context,
        )
        _emergency.value = emergency
        val emergencyEvents = emergency.events()
        backgroundTasks += scope.launch {
            emergencyEvents.collect { event ->
                if (event is EmergencyService.Event.Received) _activeEmergency.value = event.alert
            }
        }
        emergency.start()

        router.setPresenceOptions(visible = AppSettings.isNearbyVisible, openToGames = AppSettings.isOpenToGames)
        // `PRESENCE` solo in primo piano: un riavvio del servizio senza UI non si annuncia ai vicini.
        val startsInForeground = withContext(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        router.setPresenceForeground(startsInForeground)
        router.start()
        _radioState.value = router.radioState
        _neighbors.value = router.neighbors()
        observeProcessLifecycle()
        observeNearby(router)
        observeTrips(database)

        val entitlements = EntitlementManager(context, database)
        _entitlements.value = entitlements
        val entitlementEvents = entitlements.events()
        backgroundTasks += scope.launch {
            entitlementEvents.collect { _entitlementState.value = it }
        }
        // Non bloccante: Play Billing puo' impiegare decine di secondi (connect con timeout 15 s, poi
        // queryPurchases e queryProductDetails senza rete in volo). Attenderlo qui ritardava la nascita di
        // MediaTransferService / GameService / CallService: nel frattempo foto e note vocali in arrivo
        // venivano scartate (nessun iscritto a `inboundPackets`) e il pulsante foto non faceva nulla.
        backgroundTasks += scope.launch { entitlements.start() }

        val proPass = ProPassService(
            router = router, database = database, entitlements = entitlements,
            identity = identityKeys, nickname = nickname,
        )
        _proPass.value = proPass
        proPass.start()

        val calls = CallService(
            context = context, router = router, database = database,
            identity = identityKeys, nickname = nickname,
        )
        _calls.value = calls
        calls.start()
        // Android 14+: il servizio in primo piano aggiunge il tipo `microphone` solo durante una chiamata.
        backgroundTasks += scope.launch {
            calls.isAudioActive.collect { active -> MeshForegroundService.update(context, callActive = active) }
        }

        // Radio viva a schermo spento (equivalente dei background mode BLE di iOS).
        MeshForegroundService.start(context, callActive = false)

        // Notifiche gratuite per tutti: anche chi ha completato l'onboarding senza rispondere al
        // prompt deve poter ricevere i banner dei messaggi arrivati a schermo spento.
        (notifier as? LocalNotificationCenter)?.requestAuthorizationIfNeeded()

        if (AppSettings.isDemoModeEnabled) {
            setDemoMode(true)
        }
        // "Prova subito" scelto alla fine dell'onboarding: la chat demo si apre appena la mesh e' pronta.
        if (openDemoAfterStart) {
            openDemoAfterStart = false
            scope.launch { startDemo()?.let { AppNavigator.openConversation(it.id) } }
        }

        // Stato "a bordo" nella notifica del servizio: ogni cambio di conversazioni (non letti, Cabine del
        // volo) o di vicini la riaggiorna, con coalescenza.
        backgroundTasks += scope.launch {
            database.conversations.observePermanent().collect { refreshLiveStatus() }
        }
        backgroundTasks += scope.launch {
            database.conversations.observeActiveFlightLounges().collect { refreshLiveStatus() }
        }
        refreshLiveStatus()

        pendingPairingURI?.let { pending ->
            pendingPairingURI = null
            pairFromLink(pending)
        }
    }

    // MARK: - Link di invito aeromesh://

    /**
     * Link `aeromesh://v1?...` aperto da un'altra app (Swift `handleIncomingURL`). Se la mesh non e' ancora
     * accesa il link resta in attesa e si abbina appena parte il router.
     */
    fun handleIncomingURL(uri: String) {
        if (!uri.substringBefore(':').equals(MeshProtocol.qrScheme, ignoreCase = true)) return
        if (_router.value == null) {
            pendingPairingURI = uri
            return
        }
        scope.launch { pairFromLink(uri) }
    }

    private suspend fun pairFromLink(uri: String) {
        try {
            val conversation = pair(uri)
            Haptics.success()
            _incomingLinkMessage.value = if (conversation.type == ConversationType.group) {
                context.getString(R.string.shell_pairing_joined_group, conversation.title)
            } else {
                context.getString(R.string.shell11_link_added, conversation.title)
            }
            AppNavigator.openConversation(conversation.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Haptics.error()
            _incomingLinkMessage.value = context.getString(R.string.shell11_link_invalid)
        }
    }

    // MARK: - Notifica di stato "a bordo"

    /**
     * Aggiorna la notifica del servizio con vicini, non letti e Cabina del volo attiva (Swift
     * `refreshLiveActivity`). Coalescenza: al massimo un aggiornamento ogni [liveStatusIntervalMs]; le
     * richieste nel frattempo confluiscono nel prossimo, che legge i valori piu' recenti.
     */
    private fun refreshLiveStatus() {
        if (liveStatusRefresh?.isActive == true) return
        liveStatusRefresh = scope.launch {
            val sinceLast = System.currentTimeMillis() - lastLiveStatusAtMs
            delay(maxOf(liveStatusMinDelayMs, liveStatusIntervalMs - sinceLast))
            val database = _database.value ?: return@launch
            if (_router.value == null) return@launch
            lastLiveStatusAtMs = System.currentTimeMillis()
            val unread = runCatching { database.conversations.totalUnread() }.getOrDefault(0)
            val flight = runCatching { database.conversations.activeFlightLounges() }.getOrNull()?.firstOrNull()?.title
            MeshForegroundService.updateStatus(
                context,
                MeshForegroundService.LiveStatus(
                    neighborCount = _neighbors.value.size, unreadCount = unread, flightTitle = flight,
                ),
            )
        }
    }

    /**
     * Attiva/disattiva il compagno di volo simulato (Impostazioni › Informazioni) e salva la scelta in
     * [AppSettings.isDemoModeEnabled].
     */
    suspend fun setDemoMode(enabled: Boolean) {
        AppSettings.isDemoModeEnabled = enabled
        val database = _database.value ?: return
        if (enabled) {
            val demo = demoPeer ?: DemoPeerService(database = database, context = context, games = { _games.value })
                .also { demoPeer = it }
            demo.enable()
        } else {
            demoPeer?.disable()
            demoPeer?.close()
            demoPeer = null
        }
    }

    /**
     * "Prova subito": accende il passeggero virtuale e ritorna la sua chat (solo locale, nulla esce dalla
     * radio). `null` se il database non e' pronto.
     */
    suspend fun startDemo(): ConversationRecord? {
        setDemoMode(true)
        return demoPeer?.enable()
    }

    // MARK: - Persone vicine

    /** "Visibile alle persone vicine": spenta, smettiamo di inviare `PRESENCE` (gli altri restano visibili). */
    suspend fun setNearbyVisible(visible: Boolean) {
        AppSettings.isNearbyVisible = visible
        _router.value?.setPresenceOptions(visible = visible, openToGames = AppSettings.isOpenToGames)
    }

    /** "Accetto sfide a carte" (`Presence.open_to_games`). */
    suspend fun setOpenToGames(open: Boolean) {
        AppSettings.isOpenToGames = open
        _router.value?.setPresenceOptions(visible = AppSettings.isNearbyVisible, openToGames = open)
    }

    /**
     * "Saluta": a un contatto apre direttamente la chat (ritornata); a uno sconosciuto invia la richiesta
     * (ritorna `null`). Lancia [RouterError.NotRunning] se la mesh e' spenta.
     */
    suspend fun sayHello(person: NearbyPerson, greeting: String = ""): ConversationRecord? {
        val router = _router.value ?: throw RouterError.NotRunning
        contactConversation(person)?.let { return it }
        router.sendContactRequest(to = person, greeting = greeting, gameKind = 0)
        return null
    }

    /**
     * "Sfida": a un contatto invia il normale invito di gioco nella chat (ritornata); a uno sconosciuto la
     * richiesta con `game_kind` (ritorna `null`): se accetta, l'invito parte da solo.
     */
    suspend fun challenge(person: NearbyPerson, kind: com.stefanobrandi.aerochat.proto.GameKind): ConversationRecord? {
        val router = _router.value ?: throw RouterError.NotRunning
        contactConversation(person)?.let { conversation ->
            _games.value?.invite(kind, conversation)
            return conversation
        }
        router.sendContactRequest(to = person, gameKind = kind.number)
        return null
    }

    /** Chat 1-a-1 con una persona vicina gia' nei contatti (stesse chiavi), creata se manca. */
    private suspend fun contactConversation(person: NearbyPerson): ConversationRecord? {
        val database = _database.value ?: return null
        val peer = runCatching { database.peers.fetch(nodeID = person.nodeID) }.getOrNull() ?: return null
        if (!peer.isTrusted || peer.isBlocked || !peer.x25519Pub.contentEquals(person.x25519Pub)) return null
        return database.conversations.ensureDirectConversation(with = peer)
    }

    /** Accetta una richiesta: il mittente diventa un contatto e la chat (ritornata) compare in cima. */
    suspend fun acceptContactRequest(request: IncomingContactRequest): ConversationRecord {
        val router = _router.value ?: throw RouterError.NotRunning
        (notifier as? LocalNotificationCenter)?.cancelContactRequest(request.id)
        if ((_nearbyBanner.value as? NearbyBanner.Request)?.request?.id == request.id) _nearbyBanner.value = null
        return router.acceptContactRequest(request.id)
    }

    suspend fun declineContactRequest(request: IncomingContactRequest) {
        (notifier as? LocalNotificationCenter)?.cancelContactRequest(request.id)
        if ((_nearbyBanner.value as? NearbyBanner.Request)?.request?.id == request.id) _nearbyBanner.value = null
        _router.value?.declineContactRequest(request.id)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeNearby(router: MeshRouterActor) {
        backgroundTasks += scope.launch {
            router.nearby.people.collect { people ->
                _nearbyPeople.value = people
                // Ricordo del viaggio: ogni persona vicina durante il volo conta una volta.
                if (_activeTrip.value != null) {
                    people.forEach { tripMemory.recordPerson(it.id) }
                    _activeTrip.value = tripMemory.activeTrip
                }
            }
        }
        backgroundTasks += scope.launch {
            router.nearby.requests.collect { _contactRequests.value = it }
        }
        backgroundTasks += scope.launch {
            _games.flatMapLatest { it?.events() ?: flowOf() }.collect { event ->
                if (_activeTrip.value == null || event !is GameService.Event.Updated) return@collect
                val state = runCatching { _games.value?.session(event.gameID) }.getOrNull() ?: return@collect
                if (state.status != GameStatus.finished) return@collect
                tripMemory.recordGameFinished(state.gameID, won = state.outcome == GameOutcome.iWon)
                _activeTrip.value = tripMemory.activeTrip
            }
        }
    }

    // MARK: - Ricordo del viaggio

    /**
     * Inizio e fine dei voli: Cabine del volo attive (osservate e ricontrollate ogni minuto, perche' scadono
     * col tempo) e modalita' aereo. A fine volo una sola notifica "cartolina pronta".
     */
    private fun observeTrips(database: AppDatabase) {
        _airplaneMode.value = isAirplaneModeOn()
        if (airplaneReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    _airplaneMode.value = isAirplaneModeOn()
                }
            }
            airplaneReceiver = receiver
            runCatching {
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        }
        backgroundTasks += scope.launch {
            database.conversations.observeActiveFlightLounges().collect { evaluateTrips(database) }
        }
        backgroundTasks += scope.launch {
            _airplaneMode.collect { evaluateTrips(database) }
        }
        backgroundTasks += scope.launch {
            while (true) {
                delay(tripCheckIntervalMs)
                evaluateTrips(database)
            }
        }
    }

    private suspend fun evaluateTrips(database: AppDatabase) {
        val lounges = runCatching { database.conversations.activeFlightLounges() }.getOrNull() ?: return
        val active = lounges.mapNotNull { lounge -> lounge.flightCode?.let { it to lounge.title } }.toMap()
        val ended = tripMemory.update(active, airplaneMode = _airplaneMode.value, now = System.currentTimeMillis())
        _activeTrip.value = tripMemory.activeTrip
        for (trip in ended) {
            (notifier as? LocalNotificationCenter)?.notifyTripMemory(trip.key, trip.flightCode)
        }
    }

    private fun isAirplaneModeOn(): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 }
            .getOrDefault(false)

    /** Ritorno in primo piano: riprova subito i messaggi in attesa di conferma di consegna. */
    suspend fun sceneDidBecomeActive() {
        _router.value?.flushRelayQueue()
    }

    /**
     * Primo piano / background del processo (non della singola Activity: dialoghi e tendina delle notifiche
     * non contano). Lo stato iniziale viene letto subito, poi `ON_START` / `ON_STOP` aggiornano [appInForeground];
     * un riavvio "sticky" del servizio senza UI resta quindi in modalita' a basso consumo.
     */
    private suspend fun observeProcessLifecycle() = withContext(Dispatchers.Main.immediate) {
        if (processLifecycleObserver != null) return@withContext
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        appInForeground.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appInForeground.value = true
                Lifecycle.Event.ON_STOP -> appInForeground.value = false
                else -> Unit
            }
        }
        processLifecycleObserver = observer
        lifecycle.addObserver(observer)
        // Raccolta sequenziale (e conflata): l'ultimo stato vince sempre.
        backgroundTasks += scope.launch {
            var wasForeground: Boolean? = null
            appInForeground.collect { foreground ->
                setForeground(foreground)
                val returned = foreground && wasForeground == false
                wasForeground = foreground
                if (returned) {
                    // Ritorno in primo piano: i permessi possono essere stati concessi dalle Impostazioni.
                    bleTransport?.refreshAuthorization()
                    if (!MeshForegroundService.isRunning) {
                        MeshForegroundService.start(context, callActive = _calls.value?.isAudioActive?.value == true)
                    }
                }
            }
        }
    }

    private suspend fun stopObservingProcessLifecycle() = withContext(Dispatchers.Main.immediate) {
        val observer = processLifecycleObserver ?: return@withContext
        processLifecycleObserver = null
        ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
    }

    /** Il processo passa in primo piano / in background: il trasporto adatta scansione, advertising e link. */
    private suspend fun setForeground(isForeground: Boolean) {
        bleTransport?.setForeground(isForeground)
        // Persone vicine: `PRESENCE` solo con l'app in primo piano.
        _router.value?.setPresenceForeground(isForeground)
        updateRSSIPolling()
    }

    /**
     * Il radar di prossimita' compare / scompare: il polling RSSI dei link resta acceso solo mentre e'
     * visibile con l'app in primo piano (in background si spegne comunque).
     */
    suspend fun setRadarVisible(visible: Boolean) {
        isRadarVisible = visible
        updateRSSIPolling()
    }

    private suspend fun updateRSSIPolling() {
        bleTransport?.setRSSIPolling(isRadarVisible && appInForeground.value)
    }

    /**
     * Dopo la concessione dei permessi BLE a runtime: il trasporto riprova subito ad accendere radio
     * e advertising, e parte il servizio in primo piano.
     */
    suspend fun refreshAuthorization() {
        bleTransport?.refreshAuthorization()
        if (isMeshRunning) {
            MeshForegroundService.start(context, callActive = _calls.value?.isAudioActive?.value == true)
        }
    }

    suspend fun stopMesh() {
        stopObservingProcessLifecycle()
        backgroundTasks.forEach { it.cancel() }
        backgroundTasks.clear()
        liveStatusRefresh?.cancel()
        liveStatusRefresh = null
        _calls.value?.stop()
        _calls.value = null
        _quiz.value?.stop()
        _emergency.value?.stop()
        _quiz.value = null
        _emergency.value = null
        _quizState.value = null
        demoPeer?.close()
        demoPeer = null
        _games.value?.stop()
        _media.value?.stop()
        _proPass.value?.stop()
        _entitlements.value?.stop()
        _router.value?.stop()
        _games.value = null
        _media.value = null
        _proPass.value = null
        _entitlements.value = null
        _router.value = null
        _neighbors.value = emptyList()
        _nearbyPeople.value = emptyList()
        _contactRequests.value = emptyList()
        airplaneReceiver?.let { receiver -> runCatching { context.unregisterReceiver(receiver) } }
        airplaneReceiver = null
        MeshForegroundService.stop(context)
    }

    // MARK: - Azioni di alto livello

    /** Acquisto in-app (Play Billing richiede l'Activity corrente); dopo il successo propaga il pass ai gruppi. */
    suspend fun purchase(productID: String, activity: Activity) {
        val entitlements = _entitlements.value ?: return
        entitlements.purchase(productID, activity)
        _proPass.value?.broadcastGrantsForAllGroups()
    }

    suspend fun restorePurchases() {
        _entitlements.value?.restorePurchases()
        _proPass.value?.broadcastGrantsForAllGroups()
    }

    /** Pairing da QR scansionato (persona o gruppo). Lancia [RouterError.NotRunning] / [PairingPayload.Failure]. */
    suspend fun pair(uriString: String): ConversationRecord {
        val router = _router.value ?: throw RouterError.NotRunning
        val payload = PairingPayload(uriString)
        val conversation = router.pair(with = payload)
        if (conversation.type == ConversationType.group) {
            runCatching { _proPass.value?.broadcastGrantIfEntitled(conversation) }
        }
        return conversation
    }

    /** Entra nella Cabina del volo della carta d'imbarco (gruppo derivato, nessun QR). */
    suspend fun joinFlightLounge(pass: BoardingPass): ConversationRecord {
        val router = _router.value ?: throw RouterError.NotRunning
        val lounge = router.joinFlightLounge(pass)
        runCatching { _proPass.value?.broadcastGrantIfEntitled(lounge) }
        return lounge
    }

    /** Crea un gruppo con i compagni selezionati e, se Pro, lo sblocca per tutti. */
    suspend fun createGroup(title: String, members: List<PeerRecord>): ConversationRecord {
        val router = _router.value ?: throw RouterError.NotRunning
        val group = router.createGroup(title = title)
        for (member in members) {
            router.invite(member, to = group)
        }
        runCatching { _proPass.value?.broadcastGrantIfEntitled(group) }
        return group
    }

    // MARK: - Sicurezza (blocco e segnalazioni)

    /**
     * Blocca un utente: i suoi pacchetti vengono ignorati e non piu' ritrasmessi. Il mittente della
     * Cabina Pubblica puo' non essere ancora tra i peer: viene registrato con una chiave segnaposto.
     */
    suspend fun blockUser(nodeID: ByteArray, nickname: String?) {
        val database = _database.value ?: return
        val known = runCatching { database.peers.fetch(nodeID = nodeID) }.getOrNull()
        if (known == null) {
            // Chiave segnaposto unica per nodo (la colonna x25519_pub e' UNIQUE).
            val placeholderKey = nodeID + ByteArray(MeshProtocol.publicKeyLength - nodeID.size)
            runCatching {
                database.peers.upsert(
                    PeerRecord(
                        nodeID = nodeID, x25519Pub = placeholderKey,
                        ed25519Pub = ByteArray(MeshProtocol.publicKeyLength),
                        nickname = nickname ?: context.getString(R.string.shell_default_nickname),
                    ),
                )
            }
        }
        runCatching { database.peers.setBlocked(true, nodeID = nodeID) }
        _router.value?.refreshBlockedPeers()
    }

    suspend fun unblockUser(nodeID: ByteArray) {
        val database = _database.value ?: return
        runCatching { database.peers.setBlocked(false, nodeID = nodeID) }
        _router.value?.refreshBlockedPeers()
    }

    /** Segnala un messaggio: salva il rapporto in locale e, se richiesto, blocca subito il mittente. */
    suspend fun report(message: MessageRecord, reason: ReportReason, details: String?, blockSender: Boolean): ReportRecord {
        val database = _database.value ?: throw RouterError.NotRunning
        val report = database.reports.insert(ReportRecord(message = message, reason = reason, details = details))
        if (blockSender) {
            blockUser(nodeID = message.senderNodeID, nickname = message.senderNickname)
        }
        return report
    }

    suspend fun setPinned(pinned: Boolean, conversation: ConversationRecord) {
        _database.value?.conversations?.setPinned(pinned, conversationID = conversation.id)
    }

    /** Elimina la chat (solo su questo dispositivo): messaggi in cascata e file media su disco. */
    suspend fun deleteConversation(conversation: ConversationRecord) {
        val database = _database.value ?: return
        // Passeggero virtuale: eliminare la sua chat spegne la modalita' prova (e la sua chat).
        if (DemoPeerService.isDemoConversation(conversation)) {
            setDemoMode(false)
            return
        }
        val paths = runCatching { database.messages.mediaPaths(conversationID = conversation.id) }.getOrNull()
            ?: emptyList()
        database.conversations.delete(conversationID = conversation.id)
        runCatching { notifier.cancelConversation(conversationID = conversation.id) }
        val store = _mediaStore.value ?: return
        withContext(Dispatchers.IO) { paths.forEach { store.remove(relativePath = it) } }
    }

    private fun applyMedia(event: MediaTransferService.Event) {
        when (event) {
            is MediaTransferService.Event.Progress -> {
                val value = if (event.total > 0) event.received.toDouble() / event.total.toDouble() else 0.0
                val key = event.msgID.toHex()
                // Ogni scrittura invalida la chat aperta: aggiorniamo solo a passi del 5%.
                val current = _mediaProgress.value[key]
                if (current != null && value < 1 && value - current < 0.05) return
                _mediaProgress.update { it + (key to value) }
            }
            is MediaTransferService.Event.Completed ->
                _mediaProgress.update { it - event.msgID.toHex() }
            is MediaTransferService.Event.Failed -> {
                val msgID = event.msgID ?: return
                _mediaProgress.update { it - msgID.toHex() }
            }
        }
    }

    private suspend fun apply(event: RouterEvent) {
        when (event) {
            is RouterEvent.RadioStateChanged -> _radioState.value = event.state
            is RouterEvent.NeighborsChanged -> {
                // I vicini si aggiornano ogni ~4 s per l'RSSI: la notifica cambia solo se cambia il numero.
                val countChanged = event.neighbors.size != _neighbors.value.size
                _neighbors.value = event.neighbors
                if (countChanged) refreshLiveStatus()
            }
            is RouterEvent.MessageReceived -> {
                _lastReceivedMessageID.value = event.msgID
                refreshLiveStatus()
                recordTripMessage(event)
            }
            is RouterEvent.MessageSent -> if (_activeTrip.value != null) {
                tripMemory.recordMessage(outgoing = true)
                _activeTrip.value = tripMemory.activeTrip
            }
            is RouterEvent.ContactRequestReceived -> _nearbyBanner.value = NearbyBanner.Request(event.request)
            is RouterEvent.ContactAccepted -> if (event.requestedByMe) {
                _nearbyBanner.value = NearbyBanner.Accepted(event.conversationID, event.nickname)
                Haptics.success()
            }
            is RouterEvent.ContactDeclined -> _nearbyBanner.value = NearbyBanner.Declined(event.nickname)
            is RouterEvent.RelayQueueChanged -> _relayQueueCount.value = event.count
            // Qualcuno ha scansionato il nostro QR: la chat compare da sola nella lista (DB osservato).
            is RouterEvent.PeerPaired -> Unit
            // Cancellato per tutti dal mittente: via il file della foto / nota vocale.
            is RouterEvent.MessageRevised -> {
                removeMedia(event.deletedMediaPath)
                if (event.isDeleted) refreshLiveStatus()
            }
            is RouterEvent.GroupMembersChanged -> {
                // Nuovi membri nel gruppo: se siamo Pro, il pass li raggiunge subito.
                if (!isPro) return
                val database = _database.value ?: return
                val proPass = _proPass.value ?: return
                val group = runCatching { database.conversations.fetch(id = event.conversationID) }.getOrNull() ?: return
                runCatching { proPass.broadcastGrantIfEntitled(group) }
            }
        }
    }

    /** Messaggio ricevuto durante il volo: contatore e, in Cabina, la persona incontrata. */
    private suspend fun recordTripMessage(event: RouterEvent.MessageReceived) {
        if (_activeTrip.value == null) return
        tripMemory.recordMessage(outgoing = false)
        val database = _database.value
        if (database != null) {
            val message = runCatching { database.messages.fetch(msgID = event.msgID) }.getOrNull()
            val conversation = runCatching { database.conversations.fetch(id = event.conversationID) }.getOrNull()
            if (message != null && conversation != null &&
                (conversation.type == ConversationType.cabinLounge || conversation.isFlightLounge)
            ) {
                tripMemory.recordPerson(message.senderNodeID.toHex())
            }
        }
        _activeTrip.value = tripMemory.activeTrip
    }

    private companion object {
        const val tripCheckIntervalMs = 60_000L
        const val liveStatusIntervalMs = 2_000L
        const val liveStatusMinDelayMs = 250L
    }

    /** `true` se i permessi BLE runtime sono concessi (altrimenti la radio resta `unauthorized`). */
    fun hasBluetoothPermissions(): Boolean = BLEPermissions.hasAllPermissions(context)
}

/**
 * Accesso Compose all'unica [AppEnvironment] del processo (Swift `@Environment(AppEnvironment.self)`):
 * `val environment = LocalAppEnvironment.current`. Fornito da `MainActivity`.
 */
val LocalAppEnvironment = androidx.compose.runtime.staticCompositionLocalOf<AppEnvironment> {
    error("LocalAppEnvironment non fornito: avvolgi la UI in CompositionLocalProvider(LocalAppEnvironment provides ...)")
}
