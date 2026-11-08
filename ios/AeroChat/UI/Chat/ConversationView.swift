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
    let player = VoiceNotePlayer()
    let recorder = VoiceNoteRecorder()
    private(set) var playingMessageID: Data?

    private let environment: AppEnvironment
    private var tasks: [Task<Void, Never>] = []

    init(conversation: ConversationRecord, environment: AppEnvironment) {
        self.conversation = conversation
        self.environment = environment
    }

    var isGroup: Bool { conversation.type == .group }
    var store: MediaStore? { environment.mediaStore }
    var showsAttachments: Bool { conversation.type != .cabinLounge }
    /// Avanzamento dei trasferimenti media in corso, per `msg_id` (osservato da `AppEnvironment`).
    var mediaProgress: [Data: Double] { environment.mediaProgress }

    func start() {
        guard tasks.isEmpty, let database = environment.database else { return }
        let id = conversation.id
        tasks.append(Task { [weak self] in
            for await list in database.messages.observe(conversationID: id) {
                guard let self else { break }
                self.messages = list
                try? await database.conversations.markRead(conversationID: id)
            }
        })
        tasks.append(Task { [weak self] in
            for await updated in database.conversations.observe(id: id) {
                guard let self, let updated else { continue }
                self.conversation = updated
            }
        })
        tasks.append(Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { break }
                if let router = self.environment.router {
                    self.reachability = await router.reachability(of: self.conversation)
                }
                try? await Task.sleep(for: .seconds(3))
            }
        })
    }

    func stop() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        player.stop()
        recorder.cancel()
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
        guard allows(.photo), let media = environment.media else { return }
        Task {
            do {
                guard let data = try await item.loadTransferable(type: Data.self) else { return }
                let prepared = try await Task.detached(priority: .userInitiated) { try ImageProcessor.prepareForMesh(imageData: data) }.value
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
            do {
                let recording = try recorder.stop()
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
            } catch VoiceNoteRecorder.Failure.tooShort {
                infoMessage = String(localized: "Nota troppo breve.")
            } catch {
                errorMessage = String(localized: "Registrazione non riuscita: \(error.localizedDescription)")
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

    // MARK: Giochi

    func startGame(_ kind: GameKind) {
        guard allows(.game), let games = environment.games else { return }
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

    func handleGame(_ action: GameBubbleAction, message: MessageRecord) {
        guard let games = environment.games else { return }
        let gameID = message.msgID.hexString
        Task {
            do {
                switch action {
                case .accept: try await games.accept(gameID: gameID)
                case .play(let cell): try await games.play(gameID: gameID, cell: cell)
                case .resign: try await games.resign(gameID: gameID)
                }
                Haptics.impact(.light)
            } catch GameService.Failure.notYourTurn {
                infoMessage = String(localized: "Non è il tuo turno.")
            } catch {
                errorMessage = String(localized: "Mossa non valida: \(error.localizedDescription)")
            }
        }
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
                let model = ConversationViewModel(conversation: conversation, environment: environment)
                viewModel = model
                model.start()
            }
        }
        .onDisappear { viewModel?.stop() }
    }
}

private struct ConversationContent: View {

    @Bindable var viewModel: ConversationViewModel

    var body: some View {
        ZStack {
            AeroTheme.background.ignoresSafeArea()
            VStack(spacing: 0) {
                messageList
                ComposerBar(text: $viewModel.draft,
                            photoItem: $viewModel.photoItem,
                            isRecording: viewModel.recorder.isRecording,
                            recordingLevel: viewModel.recorder.level,
                            recordingElapsed: viewModel.recorder.elapsed,
                            showsAttachments: viewModel.showsAttachments,
                            onSend: viewModel.sendText,
                            onToggleRecording: viewModel.toggleRecording,
                            onStartGame: viewModel.startGame)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 2) {
                    Text(viewModel.conversation.title)
                        .font(AeroTheme.title(17, weight: .semibold))
                        .foregroundStyle(AeroTheme.textPrimary)
                    RadioStatusView(reachability: viewModel.reachability)
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
        .sheet(isPresented: $viewModel.showPaywall) { PaywallView() }
        .sheet(isPresented: $viewModel.showGroupInvite) { QRPairingSheet(group: viewModel.conversation) }
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

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(viewModel.messages, id: \.msgID) { message in
                        MessageBubble(message: message,
                                      showSender: viewModel.isGroup || viewModel.conversation.type == .cabinLounge,
                                      store: viewModel.store,
                                      playbackProgress: viewModel.playingMessageID == message.msgID ? viewModel.player.progress : nil,
                                      isPlaying: viewModel.playingMessageID == message.msgID && viewModel.player.isPlaying,
                                      transferProgress: viewModel.mediaProgress[message.msgID],
                                      onTogglePlayback: { viewModel.togglePlayback(message) },
                                      onGameAction: { viewModel.handleGame($0, message: message) })
                            .id(message.msgID)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 12)
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: viewModel.messages.last?.msgID) { _, last in
                guard let last else { return }
                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last, anchor: .bottom) }
            }
            .onAppear {
                if let last = viewModel.messages.last?.msgID { proxy.scrollTo(last, anchor: .bottom) }
            }
        }
    }
}
