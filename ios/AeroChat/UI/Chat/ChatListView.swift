import SwiftUI

@MainActor
@Observable
final class ChatListViewModel {

    var conversations: [ConversationRecord] = []
    var reachability: [String: Reachability] = [:]
    var peers: [Data: PeerRecord] = [:]
    private var tasks: [Task<Void, Never>] = []

    func start(environment: AppEnvironment) {
        guard tasks.isEmpty, let database = environment.database else { return }
        tasks.append(Task { [weak self] in
            for await list in database.conversations.observePermanent() {
                guard let self else { break }
                self.conversations = list
                await self.refreshReachability(environment)
            }
        })
        tasks.append(Task { [weak self] in
            for await list in database.peers.observeAll() {
                guard let self else { break }
                let map = Dictionary(list.map { ($0.nodeID, $0) }, uniquingKeysWith: { first, _ in first })
                if map != self.peers { self.peers = map }
            }
        })
        tasks.append(Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(4))
                guard let self else { break }
                // In background (BLE tiene viva l'app) la lista non e' visibile: niente polling della radio/DB.
                guard UIApplication.shared.applicationState == .active else { continue }
                await self.refreshReachability(environment)
            }
        })
    }

    func stop() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
    }

    private func refreshReachability(_ environment: AppEnvironment) async {
        guard let router = environment.router else { return }
        var map: [String: Reachability] = [:]
        for conversation in conversations {
            map[conversation.id] = await router.reachability(of: conversation)
        }
        // Solo se cambia: ogni assegnazione ridisegna l'intera lista.
        if map != reachability { reachability = map }
    }
}

/// Tab 1 – Chat & Gruppi permanenti (stile WhatsApp) con le 2 CTA giganti quando la lista e' vuota.
struct ChatListView: View {

    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel = ChatListViewModel()
    @State private var showPairing = false
    @State private var showStoreQR = false
    @State private var showNewGroup = false
    @State private var showPaywall = false
    @State private var showProfile = false
    @State private var showBoardingPass = false
    @State private var showFlightCard = false
    /// Cabina del volo appena creata: aperta subito dopo la chiusura del foglio.
    @State private var joinedFlightLounge: ConversationRecord?
    @State private var pendingFlightLounge: ConversationRecord?
    @State private var conversationToDelete: ConversationRecord?
    @State private var actionError: String?

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                if viewModel.conversations.isEmpty {
                    emptyState
                } else {
                    list
                }
            }
            .navigationTitle("AeroChat")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        Haptics.tap()
                        showProfile = true
                    } label: {
                        AvatarView(imageData: environment.avatarImage, colorHex: environment.avatarColorHex, size: 34)
                    }
                    .accessibilityLabel("Impostazioni e profilo")
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showPaywall = true
                    } label: {
                        Label(environment.isPro ? String(localized: "Pro") : String(localized: "Pass"), systemImage: environment.isPro ? "ticket.fill" : "ticket")
                            .foregroundStyle(environment.isPro ? AeroTheme.accentEmerald : AeroTheme.accentCyan)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Haptics.tap()
                        showPairing = true
                    } label: {
                        Image(systemName: "qrcode.viewfinder")
                            .foregroundStyle(AeroTheme.accentCyan)
                    }
                    .accessibilityLabel("Aggiungi compagno (QR)")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { Haptics.tap(); showBoardingPass = true } label: { Label("Cabina del volo (carta d'imbarco)", systemImage: "airplane.departure") }
                        Button { Haptics.tap(); showNewGroup = true } label: { Label("Nuovo gruppo di viaggio", systemImage: "person.3") }
                        Button { Haptics.tap(); showStoreQR = true } label: { Label("Fai scaricare l'app al gate", systemImage: "arrow.down.app") }
                        Divider()
                        Button { Haptics.tap(); showFlightCard = true } label: { Label("Cartolina di volo", systemImage: "sparkles.rectangle.stack") }
                    } label: {
                        Image(systemName: "plus.circle.fill")
                            .foregroundStyle(AeroTheme.accentCyan)
                    }
                }
            }
            .navigationDestination(for: ConversationRecord.self) { conversation in
                ConversationView(conversation: conversation)
            }
            .navigationDestination(item: $joinedFlightLounge) { lounge in
                ConversationView(conversation: lounge)
            }
        }
        .sheet(isPresented: $showPairing) { QRPairingSheet() }
        .sheet(isPresented: $showBoardingPass, onDismiss: {
            if let lounge = pendingFlightLounge {
                pendingFlightLounge = nil
                joinedFlightLounge = lounge
            }
        }) {
            BoardingPassSheet { pendingFlightLounge = $0 }
        }
        .sheet(isPresented: $showFlightCard) { FlightCardSheet() }
        .sheet(isPresented: $showStoreQR) { StoreQRSheet() }
        .sheet(isPresented: $showNewGroup) { NewGroupSheet() }
        .sheet(isPresented: $showPaywall) { PaywallView() }
        .sheet(isPresented: $showProfile) { SettingsView() }
        .confirmationDialog("Eliminare la chat?",
                            isPresented: Binding(get: { conversationToDelete != nil }, set: { if !$0 { conversationToDelete = nil } }),
                            titleVisibility: .visible,
                            presenting: conversationToDelete) { conversation in
            Button("Elimina chat", role: .destructive) { delete(conversation) }
            Button("Annulla", role: .cancel) {}
        } message: { _ in
            Text("Messaggi e media verranno cancellati da questo iPhone.")
        }
        .alert("Ops", isPresented: Binding(get: { actionError != nil }, set: { if !$0 { actionError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(actionError ?? "")
        }
        .onAppear { viewModel.start(environment: environment) }
        .onDisappear { viewModel.stop() }
    }

    private var list: some View {
        List {
            ForEach(viewModel.conversations, id: \.id) { conversation in
                NavigationLink(value: conversation) {
                    row(conversation)
                }
                .listRowBackground(AeroTheme.background)
                .listRowSeparatorTint(AeroTheme.surface)
                .swipeActions(edge: .leading, allowsFullSwipe: true) {
                    pinButton(conversation)
                }
                // Come Mail: scorrimento completo verso sinistra = elimina (con conferma).
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    deleteButton(conversation)
                }
                .contextMenu {
                    pinButton(conversation)
                    deleteButton(conversation)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        // Lo stile `.haptic` impostato su tutta l'app (MyApp) trasformava ogni NavigationLink in un
        // pulsante personalizzato che si prendeva il tocco: la List non riconosceva piu' lo swipe delle
        // celle. Qui torna lo stile di sistema (celle native, swipe come Mail, evidenziazione al tocco).
        .buttonStyle(.automatic)
    }

    private func pinButton(_ conversation: ConversationRecord) -> some View {
        Button {
            togglePin(conversation)
        } label: {
            if conversation.isPinned {
                Label("Rimuovi fissata", systemImage: "pin.slash.fill")
            } else {
                Label("Fissa in alto", systemImage: "pin.fill")
            }
        }
        .tint(AeroTheme.accentCyan)
    }

    private func deleteButton(_ conversation: ConversationRecord) -> some View {
        Button(role: .destructive) {
            Haptics.tap()
            conversationToDelete = conversation
        } label: {
            Label("Elimina", systemImage: "trash.fill")
        }
        // Il tint ciano globale (ContentView) coloriva anche l'azione di swipe: rosso come Mail.
        .tint(.red)
    }

    private func togglePin(_ conversation: ConversationRecord) {
        Task {
            do {
                try await environment.setPinned(!conversation.isPinned, conversation: conversation)
                Haptics.impact(.light)
            } catch {
                actionError = String(localized: "Operazione non riuscita: \(error.localizedDescription)")
            }
        }
    }

    private func delete(_ conversation: ConversationRecord) {
        Task {
            do {
                try await environment.deleteConversation(conversation)
                Haptics.impact(.medium)
            } catch {
                actionError = String(localized: "Operazione non riuscita: \(error.localizedDescription)")
            }
        }
    }

    private func row(_ conversation: ConversationRecord) -> some View {
        HStack(spacing: 12) {
            avatar(conversation)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(conversation.title)
                        .font(AeroTheme.title(17, weight: .semibold))
                        .foregroundStyle(AeroTheme.textPrimary)
                    if conversation.isProUnlocked() {
                        Image(systemName: "ticket.fill").font(.caption2).foregroundStyle(AeroTheme.accentEmerald)
                    }
                    Spacer()
                    if let last = conversation.lastMessageAtMs {
                        Text(Date(unixMilliseconds: last), style: .time)
                            .font(.caption)
                            .foregroundStyle(AeroTheme.textSecondary)
                    }
                }
                HStack {
                    RadioStatusView(reachability: viewModel.reachability[conversation.id] ?? .outOfRange, compact: true)
                    Spacer()
                    if conversation.isPinned {
                        Image(systemName: "pin.fill")
                            .font(.caption2)
                            .foregroundStyle(AeroTheme.textSecondary)
                            .accessibilityLabel("Fissata")
                    }
                    if conversation.unreadCount > 0 {
                        Text("\(conversation.unreadCount)")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(AeroTheme.accentCyan, in: Capsule())
                            .foregroundStyle(.black)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func avatar(_ conversation: ConversationRecord) -> some View {
        if conversation.isFlightLounge {
            AvatarView(imageData: nil, colorHex: "00E5FF", systemImage: "airplane")
        } else if conversation.type == .group {
            AvatarView(imageData: nil, colorHex: "10B981", systemImage: "person.3.fill")
        } else {
            let peer = conversation.peerNodeID.flatMap { viewModel.peers[$0] }
            AvatarView(imageData: peer?.avatarImage, colorHex: peer?.avatarColorHex ?? fallbackColorHex(conversation))
        }
    }

    /// Colore deterministico per i contatti che non hanno ancora annunciato il loro profilo.
    private func fallbackColorHex(_ conversation: ConversationRecord) -> String {
        let seed = conversation.recipientID.first ?? 0
        return OnboardingView.palette[Int(seed) % OnboardingView.palette.count]
    }

    private var emptyState: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "airplane.circle")
                .font(.system(size: 64))
                .foregroundStyle(AeroTheme.accentCyan)
            Text("Pronti al decollo, \(environment.nickname)")
                .font(AeroTheme.title(26))
                .foregroundStyle(AeroTheme.textPrimary)
            Text("Inquadra la carta d'imbarco per entrare nella chat del tuo volo, o aggiungi i compagni al gate: a bordo vi troverete da soli via Bluetooth.")
                .font(.footnote)
                .foregroundStyle(AeroTheme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
            ctaButton("Inquadra la carta d'imbarco", systemImage: "airplane.departure", tint: AeroTheme.accentCyan) { showBoardingPass = true }
            ctaButton("Aggiungi compagni di volo (QR)", systemImage: "qrcode.viewfinder", tint: AeroTheme.accentCyan) { showPairing = true }
            ctaButton("Fai scaricare l'app al Gate", systemImage: "arrow.down.app.fill", tint: AeroTheme.accentEmerald) { showStoreQR = true }
            Spacer(minLength: 24)
        }
        .padding(.horizontal, 20)
    }

    private func ctaButton(_ title: String, systemImage: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(AeroTheme.title(18, weight: .bold))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: 28)
                .padding(.vertical, 20)
        }
        .buttonStyle(.borderedProminent)
        .tint(tint)
        .hapticOnTap()
        .foregroundStyle(.black)
    }
}
