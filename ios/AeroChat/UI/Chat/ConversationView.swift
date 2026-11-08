import SwiftUI
import PhotosUI

@MainActor
@Observable
final class ConversationViewModel {

    private(set) var conversation: ConversationRecord
    private(set) var messages: [MessageRecord] = []
    private(set) var reachability: Reachability = .outOfRange
    var draft = ""
    var photoItem: PhotosPickerItem?
    var errorMessage: String?
    var infoMessage: String?
    var showPaywall = false
    var showGroupInvite = false
    /// Scheda partecipanti (tocco sul nome del gruppo).
    var showGroupInfo = false
    /// Chat 1-a-1 scelta con "Scrivi" nella scheda: aperta appena la scheda si chiude.
    var pendingDirectChat: ConversationRecord?
    var openedDirectChat: ConversationRecord?
    var showCamera = false
    /// Foto aperta nel visore a schermo intero.
    var viewedImage: ViewedImage?
    let player = VoiceNotePlayer()
    let recorder = VoiceNoteRecorder()
    private(set) var playingMessageID: Data?
    /// Foto scelte ma ancora in lettura/compressione: mostrate come segnaposto con spinner.
    private(set) var pendingPhotos: [UUID] = []
    /// Compagno della chat 1-a-1 (foto profilo e colore nell'header).
    private(set) var peer: PeerRecord?
    /// Membri del gruppo (noi esclusi): servono per proporre e organizzare lo Scopone.
    private(set) var groupMembers: [GroupMemberRecord] = []
    /// Scelta dei 3 giocatori dello Scopone.
    var showScoponePicker = false
    /// Partita a carte aperta nel tavolo a schermo intero.
    var openedCardTable: CardTableItem?

    private let environment: AppEnvironment
    private var tasks: [Task<Void, Never>] = []

    init(conversation: ConversationRecord, environment: AppEnvironment) {
        self.conversation = conversation
        self.environment = environment
        // Limite dei 120 s: la nota registrata parte comunque, come con il tap su "ferma".
        recorder.onAutoStop = { [weak self] recording in
            self?.sendVoiceNote(recording)
        }
    }

    var isGroup: Bool { conversation.type == .group }
    var store: MediaStore? { environment.mediaStore }
    var showsAttachments: Bool { conversation.type != .cabinLounge }
    /// Avanzamento dei trasferimenti media in corso, per `msg_id` (osservato da `AppEnvironment`).
    var mediaProgress: [Data: Double] { environment.mediaProgress }

    func start() {
        guard tasks.isEmpty, let database = environment.database else { return }
        let id = conversation.id
        NotificationPresence.visibleConversationID = id
        tasks.append(Task { [weak self] in
            for await list in database.messages.observe(conversationID: id) {
                guard let self else { break }
                self.messages = list
                await self.markReadIfVisible()
            }
        })
        if let peerID = conversation.peerNodeID {
            tasks.append(Task { [weak self] in
                for await updated in database.peers.observe(nodeID: peerID) {
                    guard let self else { break }
                    if updated != self.peer { self.peer = updated }
                }
            })
        }
        if conversation.type == .group {
            tasks.append(Task { [weak self] in
                for await list in database.conversations.observeGroupMembers(of: id) {
                    guard let self else { break }
                    if list != self.groupMembers { self.groupMembers = list }
                }
            })
        }
        tasks.append(Task { [weak self] in
            for await updated in database.conversations.observe(id: id) {
                guard let self, let updated else { continue }
                self.conversation = updated
            }
        })
        tasks.append(Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { break }
                // In background (BLE tiene viva l'app) nessuno guarda l'header: niente letture DB ogni 3 s.
                if let router = self.environment.router, UIApplication.shared.applicationState == .active {
                    // Assegnare solo se cambia: ogni scrittura ridisegna l'header della chat.
                    let updated = await router.reachability(of: self.conversation)
                    if updated != self.reachability { self.reachability = updated }
                }
                try? await Task.sleep(for: .seconds(3))
            }
        })
    }

    func stop() {
        if NotificationPresence.visibleConversationID == conversation.id {
            NotificationPresence.visibleConversationID = nil
        }
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        player.stop()
        recorder.cancel()
    }

    /// Conferma di lettura solo se l'utente vede davvero la chat: non a schermo bloccato o in background.
    func markReadIfVisible() async {
        guard UIApplication.shared.applicationState == .active, UIApplication.shared.isProtectedDataAvailable else { return }
        guard messages.contains(where: { !$0.isOutgoing && $0.deliveryStatus != .read }) || conversation.unreadCount > 0 else { return }
        await environment.markConversationRead(conversation)
    }

    // MARK: Invio

    func sendText() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let router = environment.router else { return }
        if conversation.type == .cabinLounge, MeshRouterActor.containsLink(text) {
            infoMessage = String(localized: "Nella Cabina Pubblica non si possono condividere link.")
            return
        }
        draft = ""
        Task {
            do {
                try await router.sendText(text, in: conversation)
                Haptics.impact(.light)
            } catch {
                errorMessage = String(localized: "Invio non riuscito: \(error.localizedDescription)")
            }
        }
    }

    func sendPhoto(_ item: PhotosPickerItem) {
        photoItem = nil
        sendPhotoData { try await item.loadTransferable(type: Data.self) }
    }

    /// Apre la fotocamera (stesso controllo della libreria: foto solo con collegamento diretto).
    func takePhoto() {
        guard allows(.photo) else { return }
        if CameraPicker.isAccessDenied {
            infoMessage = String(localized: "Consenti l'accesso alla fotocamera ad AeroChat in Impostazioni › Privacy per scattare foto.")
            return
        }
        showCamera = true
    }

    /// Scatto completato dalla fotocamera (`nil` = annullato).
    func sendCapturedPhoto(_ data: Data?) {
        showCamera = false
        guard let data else { return }
        sendPhotoData { data }
    }

    /// Percorso comune libreria/fotocamera: segnaposto, compressione WebP fuori dal main thread, invio.
    private func sendPhotoData(loadData: @escaping () async throws -> Data?) {
        guard allows(.photo), let media = environment.media else { return }
        let pendingID = UUID()
        pendingPhotos.append(pendingID)
        Task {
            // Il segnaposto sparisce quando compare la bolla vera (o in caso di errore).
            defer { pendingPhotos.removeAll { $0 == pendingID } }
            do {
                guard let data = try await loadData() else { return }
                let prepared = try await Task.detached(priority: .userInitiated) { try ImageProcessor.prepareForMesh(imageData: data) }.value
                pendingPhotos.removeAll { $0 == pendingID }
                try await media.sendImage(prepared, in: conversation)
                Haptics.impact(.medium)
            } catch MediaTransferService.Failure.recipientOutOfRange {
                infoMessage = String(localized: "Compagno fuori portata: le foto partono solo con un collegamento attivo.")
            } catch {
                errorMessage = String(localized: "Foto non inviata: \(error.localizedDescription)")
            }
        }
    }

    func toggleRecording() {
        if recorder.isRecording {
            Task {
                do {
                    sendVoiceNote(try await recorder.stop())
                } catch VoiceNoteRecorder.Failure.tooShort {
                    infoMessage = String(localized: "Nota troppo breve.")
                } catch {
                    errorMessage = String(localized: "Registrazione non riuscita: \(error.localizedDescription)")
                }
            }
            return
        }
        guard allows(.voiceNote) else { return }
        Task {
            guard await recorder.requestPermission() else {
                infoMessage = String(localized: "Consenti il microfono in Impostazioni per registrare note vocali.")
                return
            }
            do {
                try recorder.start()
                Haptics.impact(.rigid)
            } catch {
                errorMessage = String(localized: "Microfono non disponibile: \(error.localizedDescription)")
            }
        }
    }

    func saveImage(_ message: MessageRecord) {
        Task {
            guard let result = await saveImageToLibrary(message) else { return }
            if result.isError { errorMessage = result.text } else { infoMessage = result.text }
        }
    }

    /// Salvataggio nel rullino condiviso da menu della bolla e visore a schermo intero:
    /// restituisce il testo da mostrare (il visore lo presenta sopra di se').
    func saveImageToLibrary(_ message: MessageRecord) async -> (text: String, isError: Bool)? {
        guard let path = message.mediaLocalPath, let store else { return nil }
        do {
            let data = try store.read(relativePath: path)
            try await PhotoLibrarySaver.save(imageData: data)
            Haptics.success()
            return (String(localized: "Foto salvata nel rullino."), false)
        } catch PhotoLibrarySaver.Failure.permissionDenied {
            return (String(localized: "Consenti l'accesso alle Foto in Impostazioni per salvare le immagini."), false)
        } catch {
            return (String(localized: "Foto non salvata: \(error.localizedDescription)"), true)
        }
    }

    private func sendVoiceNote(_ recording: VoiceNoteRecorder.Recording) {
        guard let media = environment.media else { return }
        Task {
            do {
                try await media.sendVoiceNote(recording, in: conversation)
                Haptics.impact(.medium)
            } catch MediaTransferService.Failure.recipientOutOfRange {
                infoMessage = String(localized: "Compagno fuori portata: le note vocali partono solo con un collegamento attivo.")
            } catch {
                errorMessage = String(localized: "Nota vocale non inviata: \(error.localizedDescription)")
            }
        }
    }

    // MARK: Chiamate

    /// Pulsante attivo (aspetto) solo quando una chiamata e' davvero possibile adesso.
    var canCallNow: Bool {
        FeatureGate.decision(for: .call, userIsPro: environment.isPro, conversation: conversation,
                             reachability: reachability) == .allowed
    }

    func startCall() {
        switch FeatureGate.decision(for: .call, userIsPro: environment.isPro, conversation: conversation, reachability: reachability) {
        case .allowed:
            guard let calls = environment.calls else { return }
            Haptics.impact(.medium)
            Task { await calls.placeCall(in: conversation) }
        case .peerOutOfRange:
            infoMessage = String(localized: "Compagno fuori portata: le chiamate richiedono un collegamento diretto.")
        default:
            _ = allows(.call)
        }
    }

    // MARK: Giochi

    /// Giochi del menu: nelle chat 1-a-1 tutti quelli a 2; nei gruppi da almeno 4 (noi compresi) solo lo Scopone.
    var availableGames: [GameKind] {
        switch conversation.type {
        case .direct: return [.ticTacToe, .battleship, .scopa, .briscola]
        case .group: return groupMembers.count + 1 >= 4 ? [.scopone] : [.ticTacToe, .battleship]
        case .cabinLounge: return []
        }
    }

    func startGame(_ kind: GameKind) {
        guard allows(.game), let games = environment.games else { return }
        if kind == .scopone {
            showScoponePicker = true
            return
        }
        Task {
            do {
                try await games.invite(kind: kind, in: conversation)
                Haptics.impact()
            } catch GameService.Failure.directConversationOnly {
                infoMessage = String(localized: "I mini-giochi si giocano nelle chat 1-a-1.")
            } catch {
                errorMessage = String(localized: "Sfida non inviata: \(error.localizedDescription)")
            }
        }
    }

    /// Scopone: invito ai 3 membri scelti (in ordine di posto 1..3).
    func inviteScopone(players: [Data]) {
        guard let games = environment.games else { return }
        Task {
            do {
                try await games.invite(kind: .scopone, in: conversation, players: players)
                Haptics.impact()
            } catch GameService.Failure.invalidPlayers {
                infoMessage = String(localized: "Scegli esattamente 3 membri del gruppo.")
            } catch {
                errorMessage = String(localized: "Sfida non inviata: \(error.localizedDescription)")
            }
        }
    }

    func handleGame(_ action: GameBubbleAction, message: MessageRecord) {
        handleGame(action, gameID: message.msgID.hexString)
    }

    func handleGame(_ action: GameBubbleAction, gameID: String) {
        if action == .openTable {
            Haptics.tap()
            openedCardTable = CardTableItem(id: gameID)
            return
        }
        guard let games = environment.games else { return }
        Task {
            do {
                switch action {
                case .accept: try await games.accept(gameID: gameID)
                case .play(let cell): try await games.play(gameID: gameID, cell: cell)
                case .resign: try await games.resign(gameID: gameID)
                case .playCard(let card, let captured): try await games.playCard(gameID: gameID, card: card, captured: captured)
                case .openTable: return
                }
                Haptics.impact(.light)
            } catch GameService.Failure.notYourTurn {
                infoMessage = String(localized: "Non è il tuo turno.")
            } catch GameService.Failure.invalidMove {
                Haptics.error()
                infoMessage = String(localized: "Mossa non valida.")
            } catch {
                errorMessage = String(localized: "Mossa non valida: \(error.localizedDescription)")
            }
        }
    }

    /// Stato aggiornato di una partita (il tavolo si ridisegna a ogni mossa ricevuta).
    func gameState(gameID: String) -> GameSessionState? {
        guard let message = messages.first(where: { $0.msgID.hexString == gameID }),
              let data = message.gameState else { return nil }
        return try? GameSessionState.decode(data)
    }

    // MARK: Audio

    func togglePlayback(_ message: MessageRecord) {
        guard let path = message.mediaLocalPath, let store else { return }
        if playingMessageID == message.msgID, player.isPlaying {
            player.pause()
            return
        }
        Task {
            do {
                if playingMessageID != message.msgID {
                    let data = try store.read(relativePath: path)
                    try await player.load(oggOpus: data, mediaPath: path)
                    playingMessageID = message.msgID
                }
                try player.play()
            } catch {
                errorMessage = String(localized: "Riproduzione non riuscita: \(error.localizedDescription)")
            }
        }
    }

    // MARK: Gate

    /// Applica il modello Freemium e spiega all'utente cosa manca.
    func allows(_ feature: ProFeature) -> Bool {
        switch FeatureGate.decision(for: feature, userIsPro: environment.isPro, conversation: conversation, reachability: reachability) {
        case .allowed:
            return true
        case .requiresPass:
            showPaywall = true
        case .photosNeedDirectLink:
            infoMessage = String(localized: "Sei connesso tramite un ponte: le foto richiedono una connessione diretta. Testo e mini-vocali restano attivi.")
        case .peerOutOfRange:
            infoMessage = String(localized: "Compagno fuori portata: il testo viene messo in coda, i media partono appena rientrate a portata.")
        case .loungeTextOnly:
            infoMessage = String(localized: "La Cabina Pubblica è solo testo.")
        case .callNeedsDirectLink:
            infoMessage = String(localized: "Le chiamate funzionano solo con il compagno a portata diretta, senza ponti.")
        case .callSignalTooWeak:
            infoMessage = String(localized: "Segnale troppo debole per una chiamata: avvicinatevi di qualche fila.")
        }
        return false
    }
}

/// Schermata chat con bolle BlurHash / forma d'onda / giochi e indicatore "Stato Radio" nell'header.
struct ConversationView: View {

    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel: ConversationViewModel?
    let conversation: ConversationRecord

    var body: some View {
        Group {
            if let viewModel {
                ConversationContent(viewModel: viewModel)
            } else {
                AeroTheme.background.ignoresSafeArea()
            }
        }
        .task {
            if viewModel == nil {
                viewModel = ConversationViewModel(conversation: conversation, environment: environment)
            }
            // Anche al ritorno da un altro tab: `onDisappear` ha fermato le osservazioni (start e' idempotente).
            viewModel?.start()
        }
        .onDisappear { viewModel?.stop() }
    }
}

private struct ConversationContent: View {

    @Bindable var viewModel: ConversationViewModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            AeroTheme.background.ignoresSafeArea()
            VStack(spacing: 0) {
                // Viste separate: i timer da 100 ms di registrazione/riproduzione invalidano solo
                // l'indicatore e la bolla in riproduzione, non l'intera lista dei messaggi.
                MessageListView(viewModel: viewModel)
                ComposerBar(text: $viewModel.draft,
                            photoItem: $viewModel.photoItem,
                            recorder: viewModel.recorder,
                            showsAttachments: viewModel.showsAttachments,
                            onSend: viewModel.sendText,
                            onToggleRecording: viewModel.toggleRecording,
                            onStartGame: viewModel.startGame,
                            availableGames: viewModel.availableGames,
                            onTakePhoto: viewModel.takePhoto)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: 8) {
                    if viewModel.conversation.type == .direct {
                        AvatarView(imageData: viewModel.peer?.avatarImage, colorHex: viewModel.peer?.avatarColorHex, size: 32)
                    }
                    VStack(spacing: 2) {
                        HStack(spacing: 4) {
                            Text(viewModel.conversation.title)
                                .font(AeroTheme.title(17, weight: .semibold))
                                .foregroundStyle(AeroTheme.textPrimary)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                            if viewModel.isGroup {
                                Image(systemName: "chevron.right")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(AeroTheme.textSecondary)
                            }
                        }
                        RadioStatusView(reachability: viewModel.reachability)
                    }
                }
                // Gruppo: il nome apre i partecipanti (gesto, non Button: niente capsula di vetro sul titolo).
                .contentShape(Rectangle())
                .onTapGesture {
                    guard viewModel.isGroup else { return }
                    Haptics.tap()
                    viewModel.showGroupInfo = true
                }
                .accessibilityAddTraits(viewModel.isGroup ? .isButton : [])
                .accessibilityHint(viewModel.isGroup ? Text("Mostra i partecipanti del gruppo") : Text(""))
            }
            if viewModel.conversation.type == .direct {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { viewModel.startCall() } label: {
                        Image(systemName: "phone.fill")
                            .foregroundStyle(AeroTheme.accentCyan)
                            .opacity(viewModel.canCallNow ? 1 : 0.35)
                    }
                    .accessibilityLabel("Chiamata vocale")
                    .accessibilityHint(viewModel.canCallNow ? Text("") : Text("Disponibile con il compagno a portata diretta"))
                }
            }
            if viewModel.isGroup {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { viewModel.showGroupInvite = true } label: {
                        Image(systemName: "qrcode").foregroundStyle(AeroTheme.accentCyan)
                    }
                    .accessibilityLabel("QR del gruppo")
                }
            }
        }
        .toolbarBackground(AeroTheme.background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .onChange(of: viewModel.photoItem) { _, item in
            if let item { viewModel.sendPhoto(item) }
        }
        .onChange(of: scenePhase) { _, phase in
            // Sblocco / ritorno in primo piano con la chat aperta: ora i messaggi sono davvero letti.
            if phase == .active { Task { await viewModel.markReadIfVisible() } }
        }
        .fullScreenCover(isPresented: $viewModel.showCamera) {
            CameraPicker { data in viewModel.sendCapturedPhoto(data) }
                .ignoresSafeArea()
        }
        .fullScreenCover(item: $viewModel.viewedImage) { viewed in
            if let path = viewed.message.mediaLocalPath {
                ImageViewer(relativePath: path, store: viewModel.store) {
                    await viewModel.saveImageToLibrary(viewed.message)
                }
            }
        }
        .fullScreenCover(item: $viewModel.openedCardTable) { table in
            if let state = viewModel.gameState(gameID: table.id) {
                CardTableView(state: state) { viewModel.handleGame($0, gameID: table.id) }
            } else {
                AeroTheme.background.ignoresSafeArea()
                    .onTapGesture { viewModel.openedCardTable = nil }
            }
        }
        .sheet(isPresented: $viewModel.showScoponePicker) {
            ScoponePlayerPicker(members: viewModel.groupMembers) { viewModel.inviteScopone(players: $0) }
        }
        .sheet(isPresented: $viewModel.showPaywall) { PaywallView() }
        .sheet(isPresented: $viewModel.showGroupInvite) { QRPairingSheet(group: viewModel.conversation) }
        .sheet(isPresented: $viewModel.showGroupInfo, onDismiss: {
            // Push solo a scheda chiusa: insieme alla chiusura la navigazione puo' perdersi.
            if let direct = viewModel.pendingDirectChat {
                viewModel.pendingDirectChat = nil
                viewModel.openedDirectChat = direct
            }
        }) {
            GroupInfoSheet(group: viewModel.conversation) { viewModel.pendingDirectChat = $0 }
        }
        .navigationDestination(item: $viewModel.openedDirectChat) { direct in
            ConversationView(conversation: direct)
        }
        .alert("Ops", isPresented: Binding(get: { viewModel.errorMessage != nil }, set: { if !$0 { viewModel.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
        .overlay(alignment: .top) {
            if let info = viewModel.infoMessage {
                Text(info)
                    .font(.footnote)
                    .foregroundStyle(AeroTheme.textPrimary)
                    .padding(12)
                    .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .padding()
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task {
                        try? await Task.sleep(for: .seconds(4))
                        viewModel.infoMessage = nil
                    }
            }
        }
        .animation(.easeInOut, value: viewModel.infoMessage)
    }
}

private struct MessageListView: View {

    let viewModel: ConversationViewModel
    @State private var didInitialScroll = false

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(viewModel.messages, id: \.msgID) { message in
                        let isCurrent = viewModel.playingMessageID == message.msgID
                        MessageBubble(message: message,
                                      showSender: viewModel.isGroup || viewModel.conversation.type == .cabinLounge,
                                      store: viewModel.store,
                                      player: isCurrent ? viewModel.player : nil,
                                      isPlaying: isCurrent && viewModel.player.isPlaying,
                                      transferProgress: viewModel.mediaProgress[message.msgID],
                                      onTogglePlayback: { viewModel.togglePlayback(message) },
                                      onGameAction: { viewModel.handleGame($0, message: message) },
                                      onSaveImage: { viewModel.saveImage(message) },
                                      onOpenImage: { viewModel.viewedImage = ViewedImage(message: message) })
                            .id(message.msgID)
                    }
                    ForEach(viewModel.pendingPhotos, id: \.self) { id in
                        PendingPhotoBubble().id(id)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 12)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .dismissesKeyboardOnTap()
            .onChange(of: viewModel.messages.last?.msgID) { _, last in
                guard let last else { return }
                // Primo caricamento senza animazione: niente scorrimento animato attraverso tutta la storia.
                if didInitialScroll {
                    withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last, anchor: .bottom) }
                } else {
                    didInitialScroll = true
                    proxy.scrollTo(last, anchor: .bottom)
                }
            }
            .onChange(of: viewModel.pendingPhotos.last) { _, pending in
                guard let pending else { return }
                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(pending, anchor: .bottom) }
            }
            .onAppear {
                if let last = viewModel.messages.last?.msgID {
                    didInitialScroll = true
                    proxy.scrollTo(last, anchor: .bottom)
                }
            }
        }
    }
}

/// Partita a carte presentata nel tavolo a schermo intero (`gameID` = hex del msg_id dell'invito).
struct CardTableItem: Identifiable, Hashable {
    let id: String
}
