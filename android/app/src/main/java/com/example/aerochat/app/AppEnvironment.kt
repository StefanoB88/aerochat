package com.example.aerochat.app

import android.app.Activity
import android.content.Context
import com.example.aerochat.R
import com.example.aerochat.core.calls.CallService
import com.example.aerochat.core.crypto.IdentityKeys
import com.example.aerochat.core.crypto.IdentityStore
import com.example.aerochat.core.crypto.KeychainStore
import com.example.aerochat.core.crypto.PairingPayload
import com.example.aerochat.core.emergency.EmergencyAlert
import com.example.aerochat.core.emergency.EmergencyService
import com.example.aerochat.core.flight.BoardingPass
import com.example.aerochat.core.moderation.AppSettings
import com.example.aerochat.core.quiz.QuizQuestionBank
import com.example.aerochat.core.quiz.QuizService
import com.example.aerochat.core.review.DemoPeerService
import com.example.aerochat.core.entitlements.EntitlementManager
import com.example.aerochat.core.entitlements.EntitlementState
import com.example.aerochat.core.entitlements.ProPassService
import com.example.aerochat.core.games.GameService
import com.example.aerochat.core.media.MediaStore
import com.example.aerochat.core.media.MediaTransferService
import com.example.aerochat.core.mesh.LocalNotificationCenter
import com.example.aerochat.core.mesh.LocalProfile
import com.example.aerochat.core.mesh.MeshNotifier
import com.example.aerochat.core.mesh.MeshRouterActor
import com.example.aerochat.core.mesh.Neighbor
import com.example.aerochat.core.mesh.RouterError
import com.example.aerochat.core.mesh.RouterEvent
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.core.transport.BLEPermissions
import com.example.aerochat.core.transport.BLETransportActor
import com.example.aerochat.core.transport.RadioState
import com.example.aerochat.core.transport.TransportProvider
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.ConversationType
import com.example.aerochat.data.records.IdentityRecord
import com.example.aerochat.data.records.MessageRecord
import com.example.aerochat.data.records.PeerRecord
import com.example.aerochat.data.records.ReportReason
import com.example.aerochat.data.records.ReportRecord
import com.example.aerochat.BuildConfig
import com.example.aerochat.ui.onboarding.OnboardingView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Composition root dell'app: apre il database, carica (o crea) l'identita', avvia radio BLE, router
 * mesh e servizi (media, giochi, monetizzazione) e riflette il loro stato verso Compose.
 *
 * Un'istanza per processo, posseduta da [com.example.aerochat.AeroChatApplication] e raggiungibile dalla
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

    private val _relayQueueCount = MutableStateFlow(0)
    val relayQueueCount: StateFlow<Int> = _relayQueueCount.asStateFlow()

    /** `msg_id` dell'ultimo messaggio ricevuto (ogni ricezione e' un nuovo array → nuova emissione). */
    private val _lastReceivedMessageID = MutableStateFlow<ByteArray?>(null)
    val lastReceivedMessageID: StateFlow<ByteArray?> = _lastReceivedMessageID.asStateFlow()

    private val _entitlementState = MutableStateFlow(EntitlementState.free)
    val entitlementState: StateFlow<EntitlementState> = _entitlementState.asStateFlow()

    /** Avanzamento (0...1) dei media in trasferimento, per `msg_id` dell'header (chiave: hex). */
    private val _mediaProgress = MutableStateFlow<Map<String, Double>>(emptyMap())
    val mediaProgress: StateFlow<Map<String, Double>> = _mediaProgress.asStateFlow()

    private val backgroundTasks = mutableListOf<Job>()
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

    /** La chat e' visibile: messaggi letti + conferme di lettura (doppia spunta colorata) ai mittenti. */
    suspend fun markConversationRead(conversation: ConversationRecord) {
        val router = _router.value
        if (router != null) {
            router.markConversationRead(conversation)
        } else {
            runCatching { _database.value?.messages?.markIncomingRead(conversationID = conversation.id) }
        }
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

        router.start()
        _radioState.value = router.radioState
        _neighbors.value = router.neighbors()

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
    }

    /**
     * Attiva/disattiva il compagno di volo simulato (Impostazioni › Informazioni) e salva la scelta in
     * [AppSettings.isDemoModeEnabled].
     */
    suspend fun setDemoMode(enabled: Boolean) {
        AppSettings.isDemoModeEnabled = enabled
        val database = _database.value ?: return
        if (enabled) {
            val demo = demoPeer ?: DemoPeerService(database = database, context = context).also { demoPeer = it }
            demo.enable()
        } else {
            demoPeer?.disable()
            demoPeer?.close()
            demoPeer = null
        }
    }

    /** Ritorno in primo piano: riprova subito i messaggi in attesa di conferma di consegna. */
    suspend fun sceneDidBecomeActive() {
        _router.value?.flushRelayQueue()
    }

    /** L'app passa in primo piano / in background: il trasporto adatta scansione e advertising. */
    suspend fun setForeground(isForeground: Boolean) {
        bleTransport?.setForeground(isForeground)
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
        backgroundTasks.forEach { it.cancel() }
        backgroundTasks.clear()
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
        val paths = runCatching { database.messages.mediaPaths(conversationID = conversation.id) }.getOrNull()
            ?: emptyList()
        database.conversations.delete(conversationID = conversation.id)
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
            is RouterEvent.NeighborsChanged -> _neighbors.value = event.neighbors
            is RouterEvent.MessageReceived -> _lastReceivedMessageID.value = event.msgID
            is RouterEvent.RelayQueueChanged -> _relayQueueCount.value = event.count
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
