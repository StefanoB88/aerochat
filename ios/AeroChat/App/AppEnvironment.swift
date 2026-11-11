import Foundation
import Observation
import Network

/// Composition root dell'app: apre il database, carica (o crea) l'identita', avvia radio BLE, router
/// mesh e servizi (media, giochi, monetizzazione) e riflette il loro stato verso SwiftUI.
@MainActor
@Observable
final class AppEnvironment {

    enum Phase: Equatable {
        case booting
        case ready
        case failed(String)
    }

    private(set) var phase: Phase = .booting
    private(set) var database: AppDatabase?
    private(set) var identityKeys: IdentityKeys?
    private(set) var identityRecord: IdentityRecord?

    // Servizi (disponibili dopo `startMesh`).
    private(set) var router: MeshRouterActor?
    private(set) var entitlements: EntitlementManager?
    private(set) var proPass: ProPassService?
    private(set) var media: MediaTransferService?
    private(set) var games: GameService?
    private(set) var mediaStore: MediaStore?
    /// Chiamate vocali in tempo reale (osservato dalla schermata di chiamata).
    private(set) var calls: CallService?
    /// Compagno simulato (App Review / prova con un solo iPhone), attivo solo se abilitato nelle Impostazioni.
    private var demoPeer: DemoPeerService?
    /// Quiz di cabina e avvisi di emergenza (entrambi sulla Lounge pubblica).
    private(set) var quiz: QuizService?
    private(set) var emergency: EmergencyService?
    /// Stato del quiz in corso (nil = nessuno), osservato dal foglio del quiz e dal badge in Cabina.
    private(set) var quizState: QuizService.State?
    /// Ultimo avviso di emergenza ricevuto, mostrato come banner finche' l'utente non lo chiude.
    var activeEmergency: EmergencyAlert?
    /// Live Activity "A bordo" (Lock Screen / Dynamic Island).
    private let liveActivity = LiveActivityController()
    private var liveActivityRefresh: Task<Void, Never>?

    // Stato osservabile dalla UI.
    private(set) var radioState: RadioState = .unknown
    private(set) var neighbors: [Neighbor] = []
    private(set) var relayQueueCount = 0
    private(set) var lastReceivedMessageID: Data?
    private(set) var entitlementState = EntitlementState.free
    /// Avanzamento (0...1) dei media in trasferimento, per `msg_id` dell'header.
    private(set) var mediaProgress: [Data: Double] = [:]
    /// Non letti delle chat permanenti (Cabina Pubblica esclusa): badge del tab "Chat & Gruppi" e dell'icona.
    private(set) var unreadTotal = 0
    /// "Sta scrivendo" ricevuti, per conversazione (intestazione della chat e riga della lista).
    let typing = TypingStore()
    /// "Persone vicine" (PRESENCE) e "Richieste" (CONTACT_REQUEST), solo in memoria.
    let presence = PresenceStore()
    let contactRequests = ContactRequestStore()
    /// Chat da aprire (dopo Accetta, "Saluta" a un contatto, "Prova subito"): la lista chat la apre.
    var requestedConversation: ConversationRecord?
    /// "Persone vicine" da aprire (banner, notifica, Cabina, schermata principale) con il filtro scelto.
    var requestedNearby: NearbyFilter?
    /// Cartolina di un volo finito da mostrare (notifica del ricordo del viaggio).
    var requestedTripCard: FlightTrip?
    /// Card contestuale della schermata principale chiusa per questa sessione.
    var isHomeCardDismissed = false
    /// Cabina del volo attiva (carta d'imbarco), aggiornata ogni minuto e quando si entra in un volo.
    private(set) var activeFlightLounge: ConversationRecord?
    /// Rete cellulare assente su un iPhone che di solito ce l'ha: con ogni probabilita' modalita' aereo.
    private(set) var isCellularMissing = false
    /// Ricordo del viaggio (contatori per volo, notifica una sola volta).
    @ObservationIgnored private var tripMemory: TripMemory
    @ObservationIgnored private let tripStore: TripMemoryStore
    @ObservationIgnored private var presenceTask: Task<Void, Never>?
    @ObservationIgnored private var pathMonitor: NWPathMonitor?

    private let identityStore: IdentityStore
    private let databaseFactory: @Sendable () throws -> AppDatabase
    private let transportFactory: @Sendable (Data) -> any TransportProvider
    private let mediaStoreFactory: @Sendable () throws -> MediaStore
    private let notifier: any MeshNotifier
    private var backgroundTasks: [Task<Void, Never>] = []
    /// `bootstrap()` puo' partire sia dall'AppDelegate (rilancio in background via State Restoration)
    /// sia dal `.task` della UI: senza questo flag due chiamate sovrapposte avvierebbero due mesh.
    private var isBootstrapping = false
    /// Radar di prossimita' a schermo e app in primo piano: solo allora la radio legge l'RSSI ogni 4 s.
    @ObservationIgnored private var isRadarVisible = false
    @ObservationIgnored private var isSceneActive = true

    init(identityStore: IdentityStore = IdentityStore(),
         databaseFactory: @escaping @Sendable () throws -> AppDatabase = { try AppDatabase.makeShared() },
         transportFactory: @escaping @Sendable (Data) -> any TransportProvider = { BLETransportActor(localNodeID: $0) },
         mediaStoreFactory: @escaping @Sendable () throws -> MediaStore = { try MediaStore.makeShared() },
         notifier: any MeshNotifier = LocalNotificationCenter(),
         tripStore: TripMemoryStore = TripMemoryStore()) {
        self.identityStore = identityStore
        self.databaseFactory = databaseFactory
        self.transportFactory = transportFactory
        self.mediaStoreFactory = mediaStoreFactory
        self.notifier = notifier
        self.tripStore = tripStore
        self.tripMemory = tripStore.load()
        presence.onPersonSeen = { [weak self] person in
            self?.tripMemory.recordMet(person.nodeID)
        }
    }

    /// `true` dopo che l'utente ha completato l'onboarding (nickname salvato).
    var hasCompletedOnboarding: Bool { identityRecord != nil }

    var nickname: String { identityRecord?.nickname ?? String(localized: "Passeggero") }
    var avatarColorHex: String { identityRecord?.avatarColorHex ?? OnboardingView.palette[0] }
    var avatarImage: Data? { identityRecord?.avatarImage }

    /// Profilo pubblico annunciato ai contatti con `PROFILE_UPDATE`.
    private var localProfile: LocalProfile? {
        guard let identityRecord else { return nil }
        return LocalProfile(nickname: identityRecord.nickname,
                            avatarColorHex: identityRecord.avatarColorHex,
                            avatarImage: identityRecord.avatarImage,
                            updatedAtMs: identityRecord.profileUpdatedAtMs ?? identityRecord.createdAtMs)
    }

    /// In DEBUG tutte le funzioni Pro sono sbloccate per lo sviluppo; il Flight Group Pass verso gli
    /// altri membri resta legato all'acquisto reale (`ProPassService` usa `entitlementState`).
    var isPro: Bool {
        #if DEBUG
        return true
        #else
        return entitlementState.isPro()
        #endif
    }

    var isMeshRunning: Bool { router != nil }

    /// Avvio: DB (I/O fuori dal main thread) + chiavi nel Keychain + record identita' + mesh se gia' onboardato.
    func bootstrap() async {
        guard phase == .booting, database == nil, !isBootstrapping else { return }
        isBootstrapping = true
        defer { isBootstrapping = false }
        do {
            let factory = databaseFactory
            let db = try await Task.detached(priority: .userInitiated) { try factory() }.value
            let keys = try await identityStore.loadOrCreateKeys()
            database = db
            identityKeys = keys
            identityRecord = try await db.identities.fetch()
            phase = .ready
            if identityRecord != nil {
                await startMesh()
            }
        } catch {
            phase = .failed(String(describing: error))
        }
    }

    /// Completa l'onboarding zero-account: salva nickname e colore avatar, poi accende la radio.
    func completeOnboarding(nickname: String, avatarColorHex: String) async throws {
        guard let database, let identityKeys else { return }
        let trimmed = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        let record = IdentityRecord(identity: identityKeys, nickname: trimmed, avatarColorHex: avatarColorHex)
        try await database.identities.save(record)
        identityRecord = record
        await startMesh()
    }

    func updateNickname(_ nickname: String) async throws {
        guard let database else { return }
        let trimmed = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        try await database.identities.updateNickname(trimmed)
        identityRecord?.nickname = trimmed
        await router?.setNickname(trimmed)
        await media?.setNickname(trimmed)
        await games?.setNickname(trimmed)
        await proPass?.setNickname(trimmed)
    }

    /// Modifica il profilo (nickname, colore, foto) e lo annuncia a tutti i contatti.
    func updateProfile(nickname: String, avatarColorHex: String, avatarImage: Data?) async throws {
        guard let database else { return }
        let trimmed = String(nickname.trimmingCharacters(in: .whitespacesAndNewlines).prefix(MeshProtocol.maxNicknameLength))
        guard !trimmed.isEmpty else { return }
        guard let record = try await database.identities.updateProfile(nickname: trimmed, avatarColorHex: avatarColorHex,
                                                                        avatarImage: avatarImage) else { return }
        identityRecord = record
        await media?.setNickname(trimmed)
        await games?.setNickname(trimmed)
        await proPass?.setNickname(trimmed)
        await quiz?.setNickname(trimmed)
        await emergency?.setNickname(trimmed)
        calls?.setNickname(trimmed)
        if let router, let localProfile {
            await router.setProfile(localProfile, announce: true)
        }
    }

    /// La chat e' visibile: messaggi letti + conferme di lettura (doppia spunta colorata) ai mittenti.
    func markConversationRead(_ conversation: ConversationRecord) async {
        if let router {
            await router.markConversationRead(conversation)
        } else {
            _ = try? await database?.messages.markIncomingRead(conversationID: conversation.id)
        }
        refreshLiveActivity()
    }

    /// Avvia trasporto BLE, router e servizi (idempotente).
    func startMesh() async {
        guard router == nil, let database, let identityKeys else { return }
        let transport = transportFactory(identityKeys.nodeID)
        let router = MeshRouterActor(identity: identityKeys, database: database, transport: transport,
                                     nickname: nickname, notifier: notifier)
        self.router = router
        if let localProfile {
            await router.setProfile(localProfile, announce: false)
        }

        // Non letti osservati dal DB: ogni messaggio ricevuto, chat letta, chat o messaggio eliminato
        // aggiorna il badge del tab e quello dell'icona (anche con l'app tenuta viva in background dal BLE).
        backgroundTasks.append(Task { [weak self] in
            var isFirstValue = true
            for await conversations in database.conversations.observePermanent() {
                guard let self else { break }
                let total = conversations.reduce(0) { $0 + max(0, $1.unreadCount) }
                // Il primo valore riallinea sempre l'icona (badge rimasto da una sessione precedente).
                guard total != self.unreadTotal || isFirstValue else { continue }
                isFirstValue = false
                self.unreadTotal = total
                self.refreshLiveActivity()
                await LocalNotificationCenter.setAppBadge(total)
            }
        })

        let routerEvents = await router.events()
        backgroundTasks.append(Task { [weak self] in
            for await event in routerEvents {
                guard let self else { break }
                await self.apply(event)
            }
        })
        activeFlightLounge = try? await database.conversations.activeFlightLounges().first
        await router.setPresenceSettings(currentPresenceSettings)
        await router.start()
        await router.setProximityRanging(isRadarVisible && isSceneActive)
        radioState = await router.radioState
        neighbors = await router.neighbors()

        let entitlements = EntitlementManager(database: database)
        self.entitlements = entitlements
        let entitlementEvents = await entitlements.events()
        backgroundTasks.append(Task { [weak self] in
            for await state in entitlementEvents {
                guard let self else { break }
                self.entitlementState = state
            }
        })
        await entitlements.start()

        let proPass = ProPassService(router: router, database: database, entitlements: entitlements,
                                     identity: identityKeys, nickname: nickname)
        self.proPass = proPass
        await proPass.start()

        if let store = try? mediaStoreFactory() {
            mediaStore = store
            await router.setMediaStore(store)
            let media = MediaTransferService(router: router, database: database, store: store,
                                             identity: identityKeys, nickname: nickname)
            self.media = media
            let mediaEvents = await media.events()
            backgroundTasks.append(Task { [weak self] in
                for await event in mediaEvents {
                    guard let self else { break }
                    switch event {
                    case .progress(let msgID, let received, let total):
                        let value = total > 0 ? Double(received) / Double(total) : 0
                        // Ogni scrittura invalida la chat aperta: aggiorniamo solo a passi del 5%.
                        if let current = self.mediaProgress[msgID], value < 1, value - current < 0.05 { continue }
                        self.mediaProgress[msgID] = value
                    case .completed(let msgID):
                        self.mediaProgress.removeValue(forKey: msgID)
                    case .failed(_, let msgID):
                        if let msgID { self.mediaProgress.removeValue(forKey: msgID) }
                    }
                }
            })
            await media.start()
        }

        let games = GameService(router: router, database: database, identity: identityKeys, nickname: nickname)
        self.games = games
        await games.start()

        let calls = CallService(router: router, database: database, identity: identityKeys, nickname: nickname)
        self.calls = calls
        await calls.start()

        let quiz = QuizService(router: router, database: database, identity: identityKeys, nickname: nickname)
        self.quiz = quiz
        let quizEvents = await quiz.events()
        backgroundTasks.append(Task { [weak self] in
            for await event in quizEvents {
                guard let self else { break }
                if case .updated(let state) = event, state != self.quizState { self.quizState = state }
            }
        })
        await quiz.start()

        let emergency = EmergencyService(router: router, database: database, identity: identityKeys,
                                         nickname: nickname, notifier: notifier)
        self.emergency = emergency
        let emergencyEvents = await emergency.events()
        backgroundTasks.append(Task { [weak self] in
            for await event in emergencyEvents {
                guard let self else { break }
                if case .received(let alert) = event {
                    self.activeEmergency = alert
                    Haptics.warning()
                }
            }
        })
        await emergency.start()

        // Notifiche gratuite per tutti: anche chi ha completato l'onboarding senza rispondere al
        // prompt deve poter ricevere i banner dei messaggi arrivati a schermo spento.
        if let localNotifier = notifier as? LocalNotificationCenter {
            await localNotifier.requestAuthorizationIfNeeded()
        }

        if AppSettings.isDemoModeEnabled {
            await setDemoMode(true)
        }
        startConnectivityMonitor()
        startPresenceLoop()

        if let pending = pendingPairingURI {
            pendingPairingURI = nil
            await pair(fromLink: pending)
        }

        await liveActivity.cleanUpStale()
        refreshLiveActivity()
    }

    /// Aggiorna la Live Activity con vicini, non letti e Cabina del volo (coalescenza di 1 s).
    private func refreshLiveActivity() {
        liveActivityRefresh?.cancel()
        liveActivityRefresh = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled, let self, let database = self.database, self.router != nil else { return }
            let unread = (try? await database.conversations.totalUnread()) ?? 0
            let flight = (try? await database.conversations.activeFlightLounges())?.first?.title
            await self.liveActivity.update(nickname: self.nickname, neighborCount: self.neighbors.count,
                                           unreadCount: unread, flightTitle: flight, yourTurnIn: nil)
        }
    }

    /// Attiva/disattiva il compagno di volo simulato (Impostazioni › Informazioni, dopo 5 tocchi sulla versione).
    func setDemoMode(_ enabled: Bool) async {
        guard let database else { return }
        if enabled {
            if demoPeer == nil { demoPeer = DemoPeerService(database: database) }
            await demoPeer?.enable()
            await games?.setLocalBot(DemoGameBot())
        } else {
            await games?.setLocalBot(nil)
            await demoPeer?.disable()
            demoPeer = nil
        }
    }

    /// "Prova subito": accende il passeggero virtuale e apre la sua chat (nulla passa dalla radio).
    @discardableResult
    func startDemo() async -> ConversationRecord? {
        UserDefaults.standard.set(true, forKey: AppSettings.Key.demoMode)
        await setDemoMode(true)
        let conversation = await demoPeer?.conversation()
        if let conversation { requestedConversation = conversation }
        return conversation
    }

    // MARK: - Persone vicine

    private var currentPresenceSettings: PresenceSettings {
        PresenceSettings(isVisible: AppSettings.isNearbyVisible, openToGames: AppSettings.isOpenToGames,
                         flightCode: activeFlightLounge?.flightCode)
    }

    /// Impostazioni "Visibile alle persone vicine" / "Accetto sfide" cambiate: il PRESENCE si adegua subito.
    func nearbySettingsChanged() {
        Task {
            await router?.setPresenceSettings(currentPresenceSettings)
            if AppSettings.isNearbyVisible, isSceneActive { await router?.sendPresence() }
        }
    }

    /// PRESENCE ogni 60 s, solo con l'app in primo piano; nello stesso giro si aggiorna il volo attivo.
    private func startPresenceLoop() {
        presenceTask?.cancel()
        guard isSceneActive, router != nil else { return }
        presenceTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, let router = self.router else { break }
                await self.refreshFlightState()
                await router.setPresenceSettings(self.currentPresenceSettings)
                await router.sendPresence()
                try? await Task.sleep(for: .seconds(NearbyProtocol.presenceInterval))
            }
        }
    }

    /// "Saluta" / "Sfida" un vicino. Contatto gia' noto: "Saluta" apre la chat, "Sfida" manda l'invito di
    /// gioco normale; altrimenti parte un CONTACT_REQUEST.
    enum NearbyActionResult: Equatable {
        case openedChat
        case requestSent
    }

    @discardableResult
    func greet(_ person: NearbyPerson, game: GameKind? = nil) async throws -> NearbyActionResult {
        guard let router, let database else { throw RouterError.notRunning }
        if let peer = try await database.peers.fetch(nodeID: person.nodeID), peer.isTrusted, !peer.isBlocked {
            let conversation = try await database.conversations.ensureDirectConversation(with: peer, showAtTop: true)
            if let game, let games {
                try await games.invite(kind: game, in: conversation)
            }
            requestedConversation = conversation
            return .openedChat
        }
        let requestID = try await router.sendContactRequest(to: person.asPeerRecord, gameKind: game)
        contactRequests.sent(requestID, to: person.nodeID)
        return .requestSent
    }

    /// Accetta (apre la nuova chat) o rifiuta una richiesta ricevuta.
    @discardableResult
    func respond(to request: IncomingContactRequest, accept: Bool) async -> ConversationRecord? {
        contactRequests.remove(request.requestID)
        guard let router else { return nil }
        do {
            if let conversation = try await router.respondToContactRequest(request.requestID, accept: accept) {
                Haptics.success()
                requestedConversation = conversation
                return conversation
            }
        } catch {
            Haptics.error()
        }
        return nil
    }

    /// `true` se il nodo e' gia' tra i contatti fidati (QR o richiesta accettata).
    func isContact(_ nodeID: Data, peers: [Data: PeerRecord]) -> Bool {
        peers[nodeID].map { $0.isTrusted && !$0.isBlocked } ?? false
    }

    // MARK: - Volo e ricordo del viaggio

    /// In volo: Cabina del volo attiva, Bluetooth spento (logica del banner modalita' aereo) o rete
    /// cellulare sparita su un iPhone che di solito ce l'ha.
    var isLikelyInFlight: Bool {
        activeFlightLounge != nil || isAirplaneModeLikely
    }

    var isAirplaneModeLikely: Bool {
        radioState == .poweredOff || isCellularMissing
    }

    /// "AZ204" della Cabina del volo attiva.
    var activeFlightNumber: String? {
        NearbyProtocol.displayFlightNumber(fromCanonical: activeFlightLounge?.flightCode)
    }

    /// Rilegge la Cabina del volo attiva e fa avanzare il ricordo del viaggio.
    func refreshFlightState() async {
        guard let database else { return }
        let lounge = try? await database.conversations.activeFlightLounges().first
        if lounge?.id != activeFlightLounge?.id || lounge?.expiresAtMs != activeFlightLounge?.expiresAtMs {
            activeFlightLounge = lounge
            await router?.setPresenceSettings(currentPresenceSettings)
        }
        await advanceTripMemory()
    }

    private func advanceTripMemory() async {
        let active = activeFlightLounge.flatMap { lounge in
            lounge.flightCode.map { TripMemory.ActiveFlight(flightCode: $0, title: lounge.title) }
        }
        let ended = tripMemory.observe(activeFlight: active, airplaneMode: isAirplaneModeLikely)
        tripStore.save(tripMemory)
        guard let ended, let database, let identityKeys else { return }
        let computed = (try? await ended.withComputedCounters(database: database, myNodeID: identityKeys.nodeID, myName: nickname)) ?? ended
        tripMemory.updateFinished(computed)
        tripStore.save(tripMemory)
        if let localNotifier = notifier as? LocalNotificationCenter {
            await localNotifier.notifyTripCard(computed)
        }
    }

    /// Volo (in corso o finito) con i numeri aggiornati, per la cartolina.
    func trip(flightCode: String) async -> FlightTrip? {
        guard var trip = tripMemory.trip(flightCode: flightCode) else { return nil }
        if let database, let identityKeys {
            trip = (try? await trip.withComputedCounters(database: database, myNodeID: identityKeys.nodeID, myName: nickname)) ?? trip
        }
        return trip
    }

    /// Volo in corso (per la classifica del volo nella cartolina).
    var currentTrip: FlightTrip? { tripMemory.current }

    /// Rete cellulare: sparita dopo essere stata vista = modalita' aereo; tornata = possibile atterraggio.
    private func startConnectivityMonitor() {
        guard pathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        pathMonitor = monitor
        monitor.pathUpdateHandler = { [weak self] path in
            let hasCellular = path.availableInterfaces.contains { $0.type == .cellular }
            Task { @MainActor [weak self] in
                guard let self else { return }
                if hasCellular { UserDefaults.standard.set(true, forKey: AppSettings.Key.hasSeenCellular) }
                let missing = !hasCellular && UserDefaults.standard.bool(forKey: AppSettings.Key.hasSeenCellular)
                guard missing != self.isCellularMissing else { return }
                self.isCellularMissing = missing
                await self.advanceTripMemory()
            }
        }
        monitor.start(queue: DispatchQueue(label: "aerochat.connectivity"))
    }

    /// Ritorno in primo piano: riprova subito i messaggi in attesa di conferma di consegna.
    func sceneDidBecomeActive() async {
        isSceneActive = true
        await updateProximityRanging()
        startPresenceLoop()
        await router?.flushRelayQueue()
    }

    /// Il radar e' comparso/scomparso: il polling RSSI (batteria) segue la sua visibilita'.
    func setRadarVisible(_ visible: Bool) {
        isRadarVisible = visible
        // Lo stato viene riletto quando il Task parte: apri/chiudi rapidi convergono sull'ultimo valore.
        Task { await updateProximityRanging() }
    }

    private func updateProximityRanging() async {
        await router?.setProximityRanging(isRadarVisible && isSceneActive)
    }

    /// Passaggio in background: il badge dell'icona riparte dal totale reale dei non letti.
    func sceneDidEnterBackground() async {
        // Il foglio del radar resta "aperto" in background, ma nessuno lo guarda: niente polling RSSI.
        isSceneActive = false
        // PRESENCE periodico solo in primo piano.
        presenceTask?.cancel()
        presenceTask = nil
        await updateProximityRanging()
        guard let database else { return }
        let total = (try? await database.conversations.totalUnread()) ?? unreadTotal
        unreadTotal = total
        await LocalNotificationCenter.setAppBadge(total)
    }

    func stopMesh() async {
        backgroundTasks.forEach { $0.cancel() }
        backgroundTasks.removeAll()
        liveActivityRefresh?.cancel()
        await liveActivity.end()
        calls?.stop()
        calls = nil
        await quiz?.stop()
        await emergency?.stop()
        quiz = nil
        emergency = nil
        quizState = nil
        await games?.stop()
        await media?.stop()
        await proPass?.stop()
        await entitlements?.stop()
        await router?.stop()
        games = nil
        media = nil
        proPass = nil
        entitlements = nil
        router = nil
        neighbors = []
        typing.removeAll()
        presenceTask?.cancel()
        presenceTask = nil
        pathMonitor?.cancel()
        pathMonitor = nil
        presence.removeAll()
        contactRequests.removeAll()
    }

    // MARK: - Azioni di alto livello

    /// Acquisto in-app; dopo il successo propaga il Flight Group Pass a tutti i gruppi.
    func purchase(productID: String) async throws {
        guard let entitlements else { return }
        try await entitlements.purchase(productID)
        await proPass?.broadcastGrantsForAllGroups()
    }

    func restorePurchases() async {
        await entitlements?.restorePurchases()
        await proPass?.broadcastGrantsForAllGroups()
    }

    /// Esito dell'ultimo link `aeromesh://` aperto (da Messaggi, AirDrop…), mostrato come avviso.
    var incomingLinkMessage: String?
    private var pendingPairingURI: String?

    /// Link `aeromesh://v1?...` aperto dall'esterno: pairing immediato, o rinviato a mesh avviata.
    func handleIncomingURL(_ url: URL) {
        let uri = url.absoluteString
        guard url.scheme?.lowercased() == MeshProtocol.qrScheme else { return }
        guard router != nil else {
            pendingPairingURI = uri
            return
        }
        Task { await pair(fromLink: uri) }
    }

    private func pair(fromLink uri: String) async {
        do {
            let conversation = try await pair(uriString: uri)
            Haptics.success()
            incomingLinkMessage = conversation.type == .group
                ? String(localized: "Sei nel gruppo \(conversation.title)!")
                : String(localized: "Aggiunto \(conversation.title)! Vi troverete via Bluetooth.")
        } catch {
            Haptics.error()
            incomingLinkMessage = String(localized: "Link di invito non valido.")
        }
    }

    /// Pairing da QR scansionato (persona o gruppo).
    @discardableResult
    func pair(uriString: String) async throws -> ConversationRecord {
        guard let router else { throw RouterError.notRunning }
        let payload = try PairingPayload(uriString: uriString)
        let conversation = try await router.pair(with: payload)
        if conversation.type == .group {
            _ = try? await proPass?.broadcastGrantIfEntitled(for: conversation)
        }
        return conversation
    }

    /// Entra nella Cabina del volo della carta d'imbarco (gruppo derivato, nessun QR).
    @discardableResult
    func joinFlightLounge(_ pass: BoardingPass) async throws -> ConversationRecord {
        guard let router else { throw RouterError.notRunning }
        let lounge = try await router.joinFlightLounge(pass)
        _ = try? await proPass?.broadcastGrantIfEntitled(for: lounge)
        await refreshFlightState()
        // I vicini scoprono subito che siamo sullo stesso volo.
        await router.sendPresence()
        return lounge
    }

    /// Crea un gruppo con i compagni selezionati e, se Pro, lo sblocca per tutti.
    func createGroup(title: String, members: [PeerRecord]) async throws -> ConversationRecord {
        guard let router else { throw RouterError.notRunning }
        let group = try await router.createGroup(title: title)
        for member in members {
            try await router.invite(member, to: group)
        }
        _ = try? await proPass?.broadcastGrantIfEntitled(for: group)
        return group
    }

    // MARK: - Sicurezza (blocco e segnalazioni)

    /// Blocca un utente: i suoi pacchetti vengono ignorati e non piu' ritrasmessi. Il mittente della
    /// Cabina Pubblica puo' non essere ancora tra i peer: viene registrato con una chiave segnaposto.
    func blockUser(nodeID: Data, nickname: String?) async {
        guard let database else { return }
        if (try? await database.peers.fetch(nodeID: nodeID)) == nil {
            // Chiave segnaposto unica per nodo (la colonna x25519_pub e' UNIQUE).
            let placeholderKey = nodeID + Data(repeating: 0, count: MeshProtocol.publicKeyLength - nodeID.count)
            _ = try? await database.peers.upsert(PeerRecord(nodeID: nodeID, x25519Pub: placeholderKey,
                                                            ed25519Pub: Data(repeating: 0, count: MeshProtocol.publicKeyLength),
                                                            nickname: nickname ?? String(localized: "Passeggero")))
        }
        try? await database.peers.setBlocked(true, nodeID: nodeID)
        await router?.refreshBlockedPeers()
    }

    func unblockUser(nodeID: Data) async {
        guard let database else { return }
        try? await database.peers.setBlocked(false, nodeID: nodeID)
        await router?.refreshBlockedPeers()
    }

    /// Segnala un messaggio: salva il rapporto in locale e, se richiesto, blocca subito il mittente.
    @discardableResult
    func report(message: MessageRecord, reason: ReportReason, details: String?, blockSender: Bool) async throws -> ReportRecord {
        guard let database else { throw RouterError.notRunning }
        let report = try await database.reports.insert(ReportRecord(message: message, reason: reason, details: details))
        if blockSender {
            await blockUser(nodeID: message.senderNodeID, nickname: message.senderNickname)
        }
        return report
    }

    // MARK: - Modifica e cancellazione per tutti

    /// Modifica il testo di un nostro messaggio entro 15 minuti dall'invio (revisione inviata a tutti).
    func editMessage(_ message: MessageRecord, newText: String) async throws {
        guard let router else { throw RouterError.notRunning }
        guard message.canBeEdited() else { throw RouterError.revisionWindowExpired }
        try await router.reviseMessage(message, action: .edit, newText: newText)
    }

    /// Cancella per tutti un nostro messaggio (testo, foto, nota vocale) entro 15 minuti dall'invio;
    /// il file media locale viene rimosso subito.
    func deleteMessageForEveryone(_ message: MessageRecord) async throws {
        guard let router else { throw RouterError.notRunning }
        guard message.canBeDeletedForEveryone() else { throw RouterError.revisionWindowExpired }
        if let path = try await router.reviseMessage(message, action: .delete), let mediaStore {
            mediaStore.remove(relativePath: path)
        }
    }

    /// Testo da mostrare quando una modifica / cancellazione per tutti non va a buon fine.
    static func revisionFailureText(_ error: Error, isDelete: Bool) -> String {
        switch error as? RouterError {
        case .revisionWindowExpired:
            return String(localized: "Sono passati più di 15 minuti: il messaggio non si può più modificare né eliminare.")
        case .linkNotAllowedInLounge:
            return String(localized: "Niente link in Cabina Pubblica: solo chiacchiere tra passeggeri.")
        case .languageNotAllowedInLounge:
            return String(localized: "Linguaggio non ammesso in Cabina Pubblica: qui si chiacchiera con rispetto.")
        default:
            return isDelete
                ? String(localized: "Eliminazione non riuscita: \(error.localizedDescription)")
                : String(localized: "Modifica non riuscita: \(error.localizedDescription)")
        }
    }

    // MARK: - Token di abbinamento

    /// Nuovo token `pt` per il QR / link d'invito mostrato (7 giorni, max 16); nil se il DB non e' pronto.
    func issuePairingToken() async -> Data? {
        try? await database?.pairingTokens.issue()
    }

    // MARK: - Sta scrivendo

    /// Invia il "sta scrivendo" (mai in Cabina Pubblica / Cabina del volo, ne' in chat scadute o bloccate).
    func sendTyping(_ isTyping: Bool, in conversation: ConversationRecord) async {
        guard let router, MeshRouterActor.allowsTyping(in: conversation) else { return }
        await router.sendTyping(isTyping, in: conversation)
    }

    func setPinned(_ pinned: Bool, conversation: ConversationRecord) async throws {
        try await database?.conversations.setPinned(pinned, conversationID: conversation.id)
    }

    /// Elimina la chat (solo su questo iPhone): messaggi in cascata e file media su disco.
    func deleteConversation(_ conversation: ConversationRecord) async throws {
        guard let database else { return }
        // La chat del passeggero virtuale si elimina spegnendo la prova (non ricompare al riavvio).
        if DemoPeerIdentity.isDemoPeer(conversation.peerNodeID) {
            UserDefaults.standard.set(false, forKey: AppSettings.Key.demoMode)
            await setDemoMode(false)
            return
        }
        let paths = (try? await database.messages.mediaPaths(conversationID: conversation.id)) ?? []
        try await database.conversations.delete(conversationID: conversation.id)
        if let mediaStore {
            paths.forEach(mediaStore.remove(relativePath:))
        }
    }

    private func apply(_ event: RouterEvent) async {
        switch event {
        // Assegnare solo se cambia: `@Observable` invalida chi legge la proprieta' anche con lo stesso
        // valore (banner modalita' aereo, contatore della Cabina) e i due manager BLE ripetono lo stato.
        case .radioStateChanged(let state):
            if state != radioState {
                radioState = state
                await advanceTripMemory()
            }
        case .neighborsChanged(let list):
            if list != neighbors {
                let countChanged = list.count != neighbors.count
                neighbors = list
                if countChanged { refreshLiveActivity() }
            }
        case .messageReceived(let conversationID, let msgID):
            lastReceivedMessageID = msgID
            refreshLiveActivity()
            // Ricordo del viaggio: chi scrive in Cabina durante il volo e' una persona incontrata.
            if tripMemory.current != nil, conversationID == ConversationRecord.cabinLoungeID || conversationID == activeFlightLounge?.id,
               let message = try? await database?.messages.fetch(msgID: msgID) {
                tripMemory.recordMet(message.senderNodeID)
            }
        case .relayQueueChanged(let count):
            if count != relayQueueCount { relayQueueCount = count }
        case .typingChanged(let conversationID, let senderNodeID, let nickname, let isTyping):
            typing.update(conversationID: conversationID, senderNodeID: senderNodeID, nickname: nickname, isTyping: isTyping)
        case .pairedByHello:
            // La nuova chat compare da sola in cima alla lista (osservazione del DB): niente notifica.
            Haptics.success()
        case .presenceUpdated(let person):
            presence.update(person)
        case .contactRequestReceived(let request):
            contactRequests.received(request)
            Haptics.warning()
        case .contactRequestAnswered(_, let peerNodeID, let nickname, let outcome, _):
            contactRequests.answered(peerNodeID: peerNodeID, nickname: nickname, outcome: outcome)
            if case .accepted = outcome { Haptics.success() }
        case .groupMembersChanged(let conversationID):
            // Nuovi membri nel gruppo: se siamo Pro, il pass li raggiunge subito.
            guard isPro, let database, let proPass,
                  let group = try? await database.conversations.fetch(id: conversationID) else { return }
            _ = try? await proPass.broadcastGrantIfEntitled(for: group)
        }
    }
}
