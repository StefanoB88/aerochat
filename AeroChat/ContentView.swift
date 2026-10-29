import SwiftUI

/// Root: avvio → onboarding zero-account → tab principali.
struct ContentView: View {

    @Environment(AppEnvironment.self) private var environment

    var body: some View {
        Group {
            switch environment.phase {
            case .booting:
                bootView
            case .failed(let message):
                failureView(message)
            case .ready:
                if environment.hasCompletedOnboarding {
                    MainTabView()
                        .transition(.opacity)
                } else {
                    OnboardingView()
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        }
        .animation(.easeInOut(duration: 0.35), value: environment.hasCompletedOnboarding)
        .preferredColorScheme(.dark)
        .tint(AeroTheme.accentCyan)
    }

    private var bootView: some View {
        ZStack {
            AeroTheme.background.ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "airplane.departure")
                    .font(.system(size: 44, weight: .semibold))
                    .foregroundStyle(AeroTheme.accentCyan)
                Text("AeroChat")
                    .font(AeroTheme.title(32))
                    .foregroundStyle(AeroTheme.textPrimary)
                ProgressView().tint(AeroTheme.accentCyan)
            }
        }
    }

    private func failureView(_ message: String) -> some View {
        ZStack {
            AeroTheme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 12) {
                Label("Avvio fallito", systemImage: "exclamationmark.triangle.fill")
                    .font(AeroTheme.title(20, weight: .semibold))
                    .foregroundStyle(.red)
                Text(message)
                    .font(AeroTheme.monospaced(12))
                    .foregroundStyle(AeroTheme.textSecondary)
                    .textSelection(.enabled)
            }
            .padding(24)
        }
    }
}

#Preview {
    ContentView()
        .environment(AppEnvironment(databaseFactory: { try AppDatabase.makeTemporary() },
                                    mediaStoreFactory: { try MediaStore.makeTemporary() }))
}
