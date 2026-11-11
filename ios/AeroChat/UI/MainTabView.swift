import SwiftUI

/// Modello chat ibrido: Tab 1 permanente (Chat & Gruppi), Tab 2 temporanea (Cabina Pubblica 12 h).
struct MainTabView: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.scenePhase) private var scenePhase

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
        case .tripCard(let flightCode):
            Task {
                if let trip = await environment.trip(flightCode: flightCode) {
                    environment.requestedTripCard = trip
                }
            }
        }
    }
}
