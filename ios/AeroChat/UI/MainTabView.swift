import SwiftUI

/// Modello chat ibrido: Tab 1 permanente (Chat & Gruppi), Tab 2 temporanea (Cabina Pubblica 12 h).
struct MainTabView: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabView {
            ChatListView()
                .tabItem { Label("Chat & Gruppi", systemImage: "bubble.left.and.bubble.right.fill") }
            CabinLoungeView()
                .tabItem { Label("Cabina", systemImage: "airplane") }
        }
        .tint(AeroTheme.accentCyan)
        .toolbarBackground(AeroTheme.background, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
        .safeAreaInset(edge: .top, spacing: 0) {
            if AirplaneModeBanner.shouldShow(for: environment.radioState) {
                AirplaneModeBanner(state: environment.radioState)
                    .background(AeroTheme.background)
            }
        }
        .animation(.spring(duration: 0.4), value: environment.radioState)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await environment.sceneDidBecomeActive() }
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
    }
}
