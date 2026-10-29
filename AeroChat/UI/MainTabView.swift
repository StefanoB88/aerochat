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
        .onChange(of: environment.lastReceivedMessageID) { _, newValue in
            if newValue != nil, scenePhase == .active {
                Haptics.impact(.light)
            }
        }
    }
}
