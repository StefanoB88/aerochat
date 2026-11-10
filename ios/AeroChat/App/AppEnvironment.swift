import Foundation
import Observation

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

    private let identityStore: IdentityStore
    private let databaseFactory: @Sendable () throws -> AppDatabase
    private let transportFactory: @Sendable (Data) -> any TransportProvider
    private let mediaStoreFactory: @Sendable () throws -> MediaStore
    private let notifier: any MeshNotifier
    private var backgroundTasks: [Task<Void, Never>] = []
    /// `bootstrap()` puo' partire sia dall'AppDelegate (rilancio in background via State Restoration)
    /// sia dal `.task` della UI: senza questo flag due chiamate sovrapposte avvierebbero due mesh.
    private var isBootstrapping = false

    init(identityStore: IdentityStore = IdentityStore(),
         databaseFactory: @escaping @Sendable () throws -> AppDatabase = { try AppDatabase.makeShared() },
         transportFactory: @escaping @Sendable (Data) -> any TransportProvider = { BLETransportActor(localNodeID: $0) },
         mediaStoreFactory: @escaping @Sendable () throws -> MediaStore = { try MediaStore.makeShared() },
         notifier: any MeshNotifier = LocalNotificationCenter()) {
        self.identityStore = identityStore
        self.databaseFactory = databaseFactory
        self.transportFactory = transportFactory
        self.mediaStoreFactory = mediaStoreFactory
        self.notifier = notifier
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
        await router.start()
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
        } else {
            await demoPeer?.disable()
            demoPeer = nil
        }
    }

    /// Ritorno in primo piano: riprova subito i messaggi in attesa di conferma di consegna.
    func sceneDidBecomeActive() async {
        await router?.flushRelayQueue()
    }

    /// Passaggio in background: il badge dell'icona riparte dal totale reale dei non letti.
    func sceneDidEnterBackground() async {
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

    func setPinned(_ pinned: Bool, conversation: ConversationRecord) async throws {
        try await database?.conversations.setPinned(pinned, conversationID: conversation.id)
    }

    /// Elimina la chat (solo su questo iPhone): messaggi in cascata e file media su disco.
    func deleteConversation(_ conversation: ConversationRecord) async throws {
        guard let database else { return }
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
            if state != radioState { radioState = state }
        case .neighborsChanged(let list):
            if list != neighbors {
                let countChanged = list.count != neighbors.count
                neighbors = list
                if countChanged { refreshLiveActivity() }
            }
        case .messageReceived(_, let msgID):
            lastReceivedMessageID = msgID
            refreshLiveActivity()
        case .relayQueueChanged(let count):
            if count != relayQueueCount { relayQueueCount = count }
        case .groupMembersChanged(let conversationID):
            // Nuovi membri nel gruppo: se siamo Pro, il pass li raggiunge subito.
            guard isPro, let database, let proPass,
                  let group = try? await database.conversations.fetch(id: conversationID) else { return }
            _ = try? await proPass.broadcastGrantIfEntitled(for: group)
        }
    }
}
