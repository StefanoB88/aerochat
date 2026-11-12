import SwiftUI
import StoreKit

/// Modello chat ibrido: Tab 1 permanente (Chat & Gruppi), Tab 2 temporanea (Cabina Pubblica 12 h).
struct MainTabView: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.requestReview) private var requestReview
    @State private var showPassport = false

    enum Tab: Hashable {
        case chats
        case cabin
    }

    @State private var selectedTab: Tab = .chats

    var body: some View {
        @Bindable var environment = environment
        TabView(selection: $selectedTab) {
            ChatListView()
                .tabItem { Label("Chat & Gruppi", systemImage: "bubble.left.and.bubble.right.fill") }
                .badge(environment.unreadTotal)
                .tag(Tab.chats)
            CabinLoungeView()
                .tabItem { Label("Cabina", systemImage: "airplane") }
                .tag(Tab.cabin)
        }
        .tint(AeroTheme.accentCyan)
        .toolbarBackground(AeroTheme.background, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(spacing: 0) {
                if AirplaneModeBanner.shouldShow(for: environment.radioState) {
                    AirplaneModeBanner(state: environment.radioState)
                }
                if let banner = environment.contactRequests.banner {
                    ContactRequestBannerView(banner: banner, onOpen: {
                        Haptics.tap()
                        environment.contactRequests.banner = nil
                        switch banner.kind {
                        case .incoming:
                            environment.requestedNearby = .all
                        case .accepted(_, let conversationID):
                            Task {
                                if let conversation = try? await environment.database?.conversations.fetch(id: conversationID) {
                                    environment.requestedConversation = conversation
                                }
                            }
                        case .declined:
                            break
                        }
                    }, onDismiss: {
                        if environment.contactRequests.banner?.id == banner.id { environment.contactRequests.banner = nil }
                    })
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
                if let alert = environment.activeEmergency {
                    EmergencyBanner(alert: alert) {
                        Haptics.tap()
                        environment.activeEmergency = nil
                    }
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .background(AeroTheme.background)
        }
        .animation(.spring(duration: 0.4), value: environment.radioState)
        .animation(.spring(duration: 0.4), value: environment.activeEmergency)
        .animation(.spring(duration: 0.4), value: environment.contactRequests.banner)
        // Chat da aprire (richiesta accettata, "Prova subito"...): la apre la lista delle chat.
        .onChange(of: environment.requestedConversation) { _, conversation in
            if conversation != nil { selectedTab = .chats }
        }
        .sheet(item: $environment.requestedNearby) { filter in
            NearbySheet(initialFilter: filter)
        }
        .sheet(item: $environment.requestedTripCard) { trip in
            FlightCardSheet(trip: trip)
        }
        // Promemoria del giorno del volo: "Invita amici".
        .sheet(isPresented: $environment.requestedInvite) { QRPairingSheet() }
        .sheet(isPresented: $showPassport) { PassportSheet() }
        // Momento felice (cartolina, partita vinta) con le condizioni soddisfatte: recensione di sistema.
        .onChange(of: environment.isReviewRequestPending) { _, pending in
            guard pending else { return }
            environment.isReviewRequestPending = false
            Task {
                // Dopo l'apertura della cartolina / la fine della partita, non sopra l'animazione.
                try? await Task.sleep(for: .seconds(2))
                requestReview()
            }
        }
        // Premio inviti appena sbloccato: 24 h di Pass solo per noi.
        .alert("24 ore di Pass gratis 🎁", isPresented: $environment.isReferralJustUnlocked) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("3 amici si sono aggiunti con il tuo invito: il Pass è attivo per 24 ore su questo telefono. Non si può regalare ai gruppi.")
        }
        // Nuovi badge del passaporto alla fine di un volo.
        .alert("Nuovo badge nel passaporto!",
               isPresented: Binding(get: { !environment.newPassportBadges.isEmpty && !environment.isReferralJustUnlocked },
                                    set: { if !$0 { environment.newPassportBadges = [] } })) {
            Button("Apri il passaporto") {
                environment.newPassportBadges = []
                showPassport = true
            }
            Button("OK", role: .cancel) { environment.newPassportBadges = [] }
        } message: {
            Text(environment.newPassportBadges.map { "🏅 \($0.title)" }.joined(separator: "\n"))
        }
        #if DEBUG
        .modifier(StoreScreenshotPresenter(selectedTab: $selectedTab))
        #endif
        // Tocco su una notifica locale.
        .onChange(of: DeepLinkCenter.shared.pending) { _, link in
            handle(link)
        }
        .onAppear { handle(DeepLinkCenter.shared.pending) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await environment.sceneDidBecomeActive() }
            } else if phase == .background {
                Task { await environment.sceneDidEnterBackground() }
            }
        }
        // Chiamata in entrata o in corso: sopra a tutto, da qualsiasi schermata.
        .fullScreenCover(isPresented: Binding(get: { environment.calls?.current != nil },
                                              set: { if !$0 { environment.calls?.hangUp() } })) {
            CallView()
                .environment(environment)
        }
        .onChange(of: environment.lastReceivedMessageID) { _, newValue in
            if newValue != nil, scenePhase == .active {
                Haptics.impact(.light)
            }
        }
        .alert("AeroChat", isPresented: Binding(get: { environment.incomingLinkMessage != nil },
                                                set: { if !$0 { environment.incomingLinkMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(environment.incomingLinkMessage ?? "")
        }
    }

    private func handle(_ link: DeepLink?) {
        guard let link else { return }
        DeepLinkCenter.shared.pending = nil
        switch link {
        case .nearby:
            environment.requestedNearby = .all
        case .invite:
            environment.requestedInvite = true
        case .tripCard(let flightCode):
            Task {
                if let trip = await environment.trip(flightCode: flightCode) {
                    environment.requestedTripCard = trip
                }
            }
        }
    }
}
