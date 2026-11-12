import SwiftUI

@MainActor
@Observable
final class ChatListViewModel {

    var conversations: [ConversationRecord] = []
    var reachability: [String: Reachability] = [:]
    var peers: [Data: PeerRecord] = [:]
    private var tasks: [Task<Void, Never>] = []
    /// Lista "congelata" mentre e' aperta la conferma di eliminazione: il popover e' agganciato alla riga e
    /// iOS lo chiude se la riga viene ridisegnata (stato radio ogni 4 s, nuovi messaggi, "sta scrivendo").
    /// Gli aggiornamenti arrivati nel frattempo si applicano alla chiusura.
    private(set) var isFrozen = false
    private var pendingConversations: [ConversationRecord]?
    private var pendingReachability: [String: Reachability]?

    func setFrozen(_ frozen: Bool) {
        guard frozen != isFrozen else { return }
        isFrozen = frozen
        guard !frozen else { return }
        if let pending = pendingConversations { conversations = pending }
        if let pending = pendingReachability { reachability = pending }
        pendingConversations = nil
        pendingReachability = nil
    }

    func start(environment: AppEnvironment) {
        guard tasks.isEmpty, let database = environment.database else { return }
        tasks.append(Task { [weak self] in
            for await list in database.conversations.observePermanent() {
                guard let self else { break }
                if self.isFrozen {
                    self.pendingConversations = list
                } else {
                    self.conversations = list
                }
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
        if isFrozen {
            pendingReachability = map
        } else if map != reachability {
            reachability = map
        }
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
    @AppStorage(AppSettings.Key.inviteCardDismissed) private var isInviteCardDismissed = false
    @AppStorage(AppSettings.Key.travelMode) private var travelMode: TravelMode = .flight
    @State private var showRadar = false
    @State private var showDiagnosis = false
    @State private var showTranslationHelp = false
    /// "Invita chi viaggia con te" dal foglio della carta d'imbarco: "Invita amici" dopo la sua chiusura.
    @State private var inviteAfterBoarding = false
    /// Cabina del volo appena creata: aperta subito dopo la chiusura del foglio.
    @State private var joinedFlightLounge: ConversationRecord?
    @State private var pendingFlightLounge: ConversationRecord?
    @State private var conversationToDelete: ConversationRecord?
    @State private var actionError: String?
    /// Chat aperta su richiesta (Accetta, "Saluta" a un contatto, "Prova subito", Cabina del volo).
    @State private var openedConversation: ConversationRecord?

    /// Avatar (profilo) e Pass/Pro affiancati nella barra in alto a sinistra.
    private var profileAndPassButtons: some View {
        HStack(spacing: 10) {
            Button {
                Haptics.tap()
                showProfile = true
            } label: {
                AvatarView(imageData: environment.avatarImage, colorHex: environment.avatarColorHex, size: 34)
            }
            .accessibilityLabel("Impostazioni e profilo")
            Button {
                showPaywall = true
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: environment.isPro ? "ticket.fill" : "ticket")
                    Text(environment.isPro ? "Pro" : "Pass")
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                }
                .foregroundStyle(environment.isPro ? AeroTheme.accentEmerald : AeroTheme.accentCyan)
                .padding(.vertical, 6)
            }
            .accessibilityLabel(environment.isPro ? "Pro" : "Pass")
        }
        .buttonStyle(.haptic)
    }

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
            .modifier(PeopleSubtitle(text: peopleStatusText))
            .toolbar {
                // Avatar + Pass/Pro in un'unica capsula di vetro disegnata da noi: la capsula condivisa
                // della barra veniva dimensionata sulle sole icone e tagliava "Pro"/"Pass".
                if #available(iOS 26.0, *) {
                    ToolbarItem(placement: .topBarLeading) {
                        profileAndPassButtons
                            .padding(.leading, 5)
                            .padding(.trailing, 14)
                            .padding(.vertical, 5)
                            .fixedSize()
                            .glassEffect(.regular.interactive(), in: .capsule)
                    }
                    .sharedBackgroundVisibility(.hidden)
                } else {
                    ToolbarItem(placement: .topBarLeading) {
                        profileAndPassButtons.fixedSize()
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Haptics.tap()
                        environment.requestedNearby = .all
                    } label: {
                        Image(systemName: "person.wave.2")
                            .foregroundStyle(AeroTheme.accentCyan)
                    }
                    .accessibilityLabel("Persone vicine")
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
                        Button { Haptics.tap(); showNewGroup = true } label: { Label("Nuovo gruppo di viaggio", systemImage: "person.3") }
                        Button { Haptics.tap(); showBoardingPass = true } label: { Label("Cabina del volo (carta d'imbarco)", systemImage: "airplane.departure") }
                        Divider()
                        // Voci usate di rado: in fondo.
                        Button { Haptics.tap(); showStoreQR = true } label: { Label("Fai scaricare l'app al gate", systemImage: "arrow.down.app") }
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
            .navigationDestination(item: $openedConversation) { conversation in
                ConversationView(conversation: conversation)
            }
        }
        .onChange(of: environment.requestedConversation) { _, conversation in
            openRequested(conversation)
        }
        .sheet(isPresented: $showPairing) { QRPairingSheet() }
        .sheet(isPresented: $showBoardingPass, onDismiss: {
            if inviteAfterBoarding {
                // La Cabina resta pronta (si apre dalla card); prima "Invita amici", finche' c'e' rete.
                inviteAfterBoarding = false
                pendingFlightLounge = nil
                showPairing = true
            } else if let lounge = pendingFlightLounge {
                pendingFlightLounge = nil
                joinedFlightLounge = lounge
            }
        }) {
            BoardingPassSheet(onJoined: { pendingFlightLounge = $0 }, onInvite: { inviteAfterBoarding = true })
        }
        .sheet(isPresented: $showRadar) { RadarSheet() }
        .sheet(isPresented: $showDiagnosis) { NobodyNearbySheet() }
        .alert("Traduzione offline", isPresented: $showTranslationHelp) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Tieni premuto un messaggio ricevuto e scegli «Traduci». Per usarlo senza internet scarica prima le lingue nell'app Traduci di Apple, finché c'è rete.")
        }
        .sheet(isPresented: $showFlightCard) { FlightCardSheet() }
        .sheet(isPresented: $showStoreQR) { StoreQRSheet() }
        .sheet(isPresented: $showNewGroup) { NewGroupSheet() }
        .sheet(isPresented: $showPaywall) { PaywallView() }
        .sheet(isPresented: $showProfile) { SettingsView() }
        .alert("Ops", isPresented: Binding(get: { actionError != nil }, set: { if !$0 { actionError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(actionError ?? "")
        }
        .onAppear {
            viewModel.setFrozen(false)
            viewModel.start(environment: environment)
            openRequested(environment.requestedConversation)
        }
        .onDisappear { viewModel.stop() }
    }

    private func openRequested(_ conversation: ConversationRecord?) {
        guard let conversation else { return }
        environment.requestedConversation = nil
        openedConversation = conversation
    }

    /// "3 persone vicine" sotto il titolo (iOS 26) al posto degli stati radio tecnici.
    private var peopleStatusText: String {
        PeopleStatus.text(neighbors: environment.neighbors.count, nearby: environment.presence.count,
                          radioState: environment.radioState)
    }

    private var list: some View {
        List {
            if showsHomeCard {
                homeCard
                    .listRowBackground(AeroTheme.background)
                    .listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            }
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
                // Conferma agganciata alla riga: su iOS 26 il popover parte dalla chat da eliminare.
                .confirmationDialog("Eliminare la chat?",
                                    isPresented: deleteConfirmationBinding(for: conversation),
                                    titleVisibility: .visible) {
                    Button("Elimina chat", role: .destructive) { delete(conversation) }
                    Button("Annulla", role: .cancel) {}
                } message: {
                    Text("Messaggi e media verranno cancellati da questo iPhone.")
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

    private func deleteConfirmationBinding(for conversation: ConversationRecord) -> Binding<Bool> {
        Binding(get: { conversationToDelete?.id == conversation.id },
                set: { presented in
                    if !presented, conversationToDelete?.id == conversation.id {
                        conversationToDelete = nil
                        viewModel.setFrozen(false)
                    }
                })
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
            viewModel.setFrozen(true)
            // Dallo swipe o dal menu contestuale la riga si sta ancora richiudendo: aprire la conferma
            // subito la faceva sparire insieme all'animazione. Si apre a riga ferma.
            Task {
                try? await Task.sleep(for: .milliseconds(400))
                conversationToDelete = conversation
            }
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
                    // Lista congelata (conferma di eliminazione aperta): niente "sta scrivendo" che ridisegna la riga.
                    if !viewModel.isFrozen, environment.typing.isAnyoneTyping(in: conversation.id) {
                        Text("sta scrivendo…")
                            .font(.subheadline.italic())
                            .foregroundStyle(AeroTheme.accentCyan)
                            .lineLimit(1)
                    } else {
                        RadioStatusView(reachability: viewModel.reachability[conversation.id] ?? .outOfRange, compact: true)
                    }
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
        ScrollView {
            VStack(spacing: 18) {
                Text("Pronti al decollo, \(environment.nickname)")
                    .font(AeroTheme.title(26))
                    .foregroundStyle(AeroTheme.textPrimary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 12)
                if showsHomeCard {
                    homeCard
                }
                // Prova con il passeggero virtuale: tutto da questo iPhone, nulla passa dalla radio.
                Button {
                    Haptics.tap()
                    Task { await environment.startDemo() }
                } label: {
                    VStack(spacing: 4) {
                        Label("Prova subito", systemImage: "sparkles")
                            .font(AeroTheme.title(18, weight: .bold))
                        Text("Chatta e gioca con un passeggero virtuale, senza un secondo telefono")
                            .font(.footnote)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                }
                .buttonStyle(.bordered)
                .tint(AeroTheme.accentEmerald)
                Button {
                    Haptics.tap()
                    environment.requestedNearby = .all
                } label: {
                    Label("Guarda chi è vicino", systemImage: "person.wave.2")
                        .font(.body.weight(.semibold))
                }
                .tint(AeroTheme.accentCyan)
                Text("A bordo vi troverete da soli via Bluetooth: niente Wi-Fi, niente account.")
                    .font(.footnote)
                    .foregroundStyle(AeroTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
    }

    // MARK: Card contestuale

    /// In volo la card si chiude solo per questa sessione; quella a terra ("Porta i tuoi amici a bordo")
    /// una volta chiusa non ricompare piu'.
    private var showsHomeCard: Bool {
        environment.isLikelyInFlight ? !environment.isHomeCardDismissed : !isInviteCardDismissed
    }

    /// Card grande in cima: si adatta a "in volo" / "a terra".
    private var homeCard: some View {
        HomeContextCard(
            isInFlight: environment.isLikelyInFlight,
            travelMode: $travelMode,
            flightNumber: environment.activeFlightNumber,
            hasFlightCabin: environment.activeFlightLounge != nil,
            nearbyCount: environment.presence.count,
            onOpenFlightCabin: {
                if let lounge = environment.activeFlightLounge { openedConversation = lounge }
            },
            onPlayOnBoard: { environment.requestedNearby = .flight },
            onAddBoardingPass: { showBoardingPass = true },
            onInvite: { showPairing = true },
            onNewGroup: { showNewGroup = true },
            onNearby: { environment.requestedNearby = .all },
            onRadar: { showRadar = true },
            onShareLocation: shareLocationFromHome,
            onTranslationHelp: { showTranslationHelp = true },
            onDiagnosis: { showDiagnosis = true },
            onDismiss: {
                withAnimation {
                    if environment.isLikelyInFlight {
                        environment.isHomeCardDismissed = true
                    } else {
                        isInviteCardDismissed = true
                    }
                }
            }
        )
    }

    /// Montagna: la chat (o il gruppo) piu' recente, dove allegare la posizione dal menu allegati; altrimenti un
    /// gruppo nuovo.
    private func shareLocationFromHome() {
        let chat = viewModel.conversations.first { conversation in
            (conversation.type == .direct || (conversation.type == .group && !conversation.isFlightLounge))
                && !DemoPeerIdentity.isDemoPeer(conversation.peerNodeID)
        }
        if let chat {
            openedConversation = chat
        } else {
            showNewGroup = true
        }
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
