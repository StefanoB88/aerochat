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

    // Stato osservabile dalla UI.
    private(set) var radioState: RadioState = .unknown
    private(set) var neighbors: [Neighbor] = []
    private(set) var relayQueueCount = 0
    private(set) var lastReceivedMessageID: Data?
    private(set) var entitlementState = EntitlementState.free
    /// Avanzamento (0...1) dei media in trasferimento, per `msg_id` dell'header.
    private(set) var mediaProgress: [Data: Double] = [:]

    private let identityStore: IdentityStore
    private let databaseFactory: @Sendable () throws -> AppDatabase
    private let transportFactory: @Sendable (Data) -> any TransportProvider
    private let mediaStoreFactory: @Sendable () throws -> MediaStore
    private let notifier: any MeshNotifier
    private var backgroundTasks: [Task<Void, Never>] = []

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

    var isPro: Bool { entitlementState.isPro() }

    var isMeshRunning: Bool { router != nil }

    /// Avvio: DB (I/O fuori dal main thread) + chiavi nel Keychain + record identita' + mesh se gia' onboardato.
    func bootstrap() async {
        guard phase == .booting, database == nil else { return }
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

    /// Avvia trasporto BLE, router e servizi (idempotente).
    func startMesh() async {
        guard router == nil, let database, let identityKeys else { return }
        let transport = transportFactory(identityKeys.nodeID)
        let router = MeshRouterActor(identity: identityKeys, database: database, transport: transport,
                                     nickname: nickname, notifier: notifier)
        self.router = router

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
            let media = MediaTransferService(router: router, database: database, store: store,
                                             identity: identityKeys, nickname: nickname)
            self.media = media
            let mediaEvents = await media.events()
            backgroundTasks.append(Task { [weak self] in
                for await event in mediaEvents {
                    guard let self else { break }
                    switch event {
                    case .progress(let msgID, let received, let total):
                        self.mediaProgress[msgID] = total > 0 ? Double(received) / Double(total) : 0
                    case .completed(let msgID):
                        self.mediaProgress.removeValue(forKey: msgID)
                    case .failed:
                        break
                    }
                }
            })
            await media.start()
        }

        let games = GameService(router: router, database: database, identity: identityKeys, nickname: nickname)
        self.games = games
        await games.start()
    }

    func stopMesh() async {
        backgroundTasks.forEach { $0.cancel() }
        backgroundTasks.removeAll()
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

    private func apply(_ event: RouterEvent) async {
        switch event {
        case .radioStateChanged(let state):
            radioState = state
        case .neighborsChanged(let list):
            neighbors = list
        case .messageReceived(_, let msgID):
            lastReceivedMessageID = msgID
        case .relayQueueChanged(let count):
            relayQueueCount = count
        case .groupMembersChanged(let conversationID):
            // Nuovi membri nel gruppo: se siamo Pro, il pass li raggiunge subito.
            guard isPro, let database, let proPass,
                  let group = try? await database.conversations.fetch(id: conversationID) else { return }
            _ = try? await proPass.broadcastGrantIfEntitled(for: group)
        }
    }
}
