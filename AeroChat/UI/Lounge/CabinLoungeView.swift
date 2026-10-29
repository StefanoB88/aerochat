import SwiftUI

@MainActor
@Observable
final class CabinLoungeViewModel {

    private(set) var lounge: ConversationRecord?
    private(set) var messages: [MessageRecord] = []
    private(set) var blocked: Set<Data> = []
    var draft = ""
    var infoMessage: String?
    private var tasks: [Task<Void, Never>] = []

    func start(environment: AppEnvironment) {
        guard tasks.isEmpty, let database = environment.database else { return }
        tasks.append(Task { [weak self] in
            guard let self else { return }
            guard let lounge = try? await database.conversations.currentCabinLounge() else { return }
            self.lounge = lounge
            self.blocked = (try? await database.peers.blockedNodeIDs()) ?? []
            for await list in database.messages.observe(conversationID: lounge.id) {
                self.messages = list.filter { !self.blocked.contains($0.senderNodeID) }
            }
        })
        tasks.append(Task { [weak self] in
            // Ogni 10 minuti verifica la scadenza delle 12 ore (ricrea la Lounge e svuota i vecchi messaggi).
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(600))
                guard let self else { break }
                if let renewed = try? await database.conversations.currentCabinLounge(), renewed.expiresAtMs != self.lounge?.expiresAtMs {
                    self.stop()
                    self.start(environment: environment)
                }
            }
        })
    }

    func stop() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
    }

    var expiryText: String {
        guard let expires = lounge?.expiresAtMs else { return "" }
        let remaining = max(0, Date(unixMilliseconds: expires).timeIntervalSinceNow)
        let hours = Int(remaining) / 3_600
        let minutes = (Int(remaining) % 3_600) / 60
        return String(localized: "Si autodistrugge tra \(hours)h \(minutes)m")
    }

    func send(environment: AppEnvironment) {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let lounge, let router = environment.router else { return }
        guard !MeshRouterActor.containsLink(text) else {
            infoMessage = String(localized: "Niente link in Cabina Pubblica: solo chiacchiere tra passeggeri.")
            return
        }
        draft = ""
        Task {
            do {
                try await router.sendText(text, in: lounge)
                Haptics.impact(.light)
            } catch {
                infoMessage = String(localized: "Invio non riuscito: \(error.localizedDescription)")
            }
        }
    }

    func block(_ nodeID: Data, environment: AppEnvironment) {
        guard let database = environment.database else { return }
        blocked.insert(nodeID)
        messages.removeAll { $0.senderNodeID == nodeID }
        Task {
            // Il mittente potrebbe non essere ancora tra i peer (Lounge anonima): lo registriamo come bloccato.
            if (try? await database.peers.fetch(nodeID: nodeID)) == nil,
               let sample = messages.first(where: { $0.senderNodeID == nodeID }) {
                _ = try? await database.peers.upsert(PeerRecord(nodeID: nodeID, x25519Pub: Data(repeating: 0, count: 32),
                                                                ed25519Pub: Data(repeating: 0, count: 32),
                                                                nickname: sample.senderNickname ?? String(localized: "Passeggero")))
            }
            try? await database.peers.setBlocked(true, nodeID: nodeID)
            await environment.router?.refreshBlockedPeers()
            Haptics.warning()
        }
    }
}

/// Tab 2 – Cabina Pubblica: bacheca anonima di 12 ore, solo testo, blocco rapido degli utenti.
struct CabinLoungeView: View {

    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel = CabinLoungeViewModel()

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                VStack(spacing: 0) {
                    header
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(spacing: 10) {
                                if viewModel.messages.isEmpty {
                                    emptyHint
                                }
                                ForEach(viewModel.messages, id: \.msgID) { message in
                                    MessageBubble(message: message, showSender: true, store: nil, playbackProgress: nil, isPlaying: false, transferProgress: nil)
                                        .id(message.msgID)
                                        .contextMenu {
                                            if !message.isOutgoing {
                                                Button(role: .destructive) {
                                                    viewModel.block(message.senderNodeID, environment: environment)
                                                } label: {
                                                    Label("Blocca / Silenzia \(message.senderNickname ?? "utente")", systemImage: "hand.raised.fill")
                                                }
                                            }
                                        }
                                }
                            }
                            .padding(12)
                        }
                        .onChange(of: viewModel.messages.last?.msgID) { _, last in
                            guard let last else { return }
                            withAnimation { proxy.scrollTo(last, anchor: .bottom) }
                        }
                    }
                    ComposerBar(text: Binding(get: { viewModel.draft }, set: { viewModel.draft = $0 }),
                                photoItem: .constant(nil),
                                isRecording: false,
                                recordingLevel: 0,
                                recordingElapsed: 0,
                                showsAttachments: false,
                                onSend: { viewModel.send(environment: environment) },
                                onToggleRecording: {},
                                onStartGame: { _ in })
                }
            }
            .navigationTitle("Cabina Pubblica")
            .toolbarBackground(AeroTheme.background, for: .navigationBar)
            .overlay(alignment: .top) {
                if let info = viewModel.infoMessage {
                    Text(info)
                        .font(.footnote)
                        .padding(12)
                        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .padding()
                        .task {
                            try? await Task.sleep(for: .seconds(4))
                            viewModel.infoMessage = nil
                        }
                }
            }
        }
        .onAppear { viewModel.start(environment: environment) }
        .onDisappear { viewModel.stop() }
    }

    private var header: some View {
        HStack {
            Label("\(environment.neighbors.count) passeggeri a portata", systemImage: "dot.radiowaves.left.and.right")
            Spacer()
            Text(viewModel.expiryText)
        }
        .font(.caption)
        .foregroundStyle(AeroTheme.textSecondary)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(AeroTheme.surface.opacity(0.6))
    }

    private var emptyHint: some View {
        VStack(spacing: 10) {
            Image(systemName: "bubble.left.and.text.bubble.right")
                .font(.system(size: 40))
                .foregroundStyle(AeroTheme.accentCyan)
            Text("Scambia il posto, dividi un taxi all'arrivo, commenta il ritardo. Anonimo, solo nickname, sparisce dopo 12 ore.")
                .font(.footnote)
                .foregroundStyle(AeroTheme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(.top, 60)
        .padding(.horizontal, 32)
    }
}
