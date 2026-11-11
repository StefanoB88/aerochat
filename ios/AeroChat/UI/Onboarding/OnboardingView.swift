import SwiftUI

/// Onboarding zero-account: 3 schermate chiare (come funziona), poi nickname e colore → Home.
struct OnboardingView: View {

    /// Le tre idee chiave, una frase ciascuna.
    struct IntroPage: Identifiable {
        let id: Int
        let systemImage: String
        let title: LocalizedStringKey
        let message: LocalizedStringKey
    }

    static let introPages: [IntroPage] = [
        IntroPage(id: 0, systemImage: "wifi.slash", title: "Funziona senza internet",
                  message: "Niente Wi-Fi, niente rete, niente account: basta il Bluetooth, anche in modalità aereo."),
        IntroPage(id: 1, systemImage: "iphone.radiowaves.left.and.right", title: "I messaggi passano da telefono a telefono",
                  message: "Ogni messaggio salta da un telefono vicino all'altro, cifrato: nessun server lo legge."),
        IntroPage(id: 2, systemImage: "person.3.sequence.fill", title: "Più siete, più arriva lontano",
                  message: "Ogni passeggero con AeroChat allunga la catena: invita chi viaggia con te."),
    ]

    @Environment(AppEnvironment.self) private var environment
    /// 0...2 = schermate introduttive, 3 = profilo.
    @State private var page = 0
    @State private var nickname = ""
    @State private var colorHex = OnboardingView.palette[0]
    @State private var isSaving = false
    @State private var errorMessage: String?
    @FocusState private var focused: Bool

    static let palette = ["00E5FF", "10B981", "F59E0B", "EC4899", "8B5CF6", "F97316"]

    private var trimmed: String { nickname.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        if page < Self.introPages.count {
            introView
        } else {
            profileView
        }
    }

    private var introView: some View {
        ZStack {
            AeroTheme.background.ignoresSafeArea()
            VStack(spacing: 24) {
                HStack {
                    Spacer()
                    Button("Salta") {
                        Haptics.tap()
                        withAnimation { page = Self.introPages.count }
                    }
                    .foregroundStyle(AeroTheme.textSecondary)
                }
                TabView(selection: $page) {
                    ForEach(Self.introPages) { intro in
                        VStack(spacing: 22) {
                            Spacer()
                            Image(systemName: intro.systemImage)
                                .font(.system(size: 76, weight: .semibold))
                                .foregroundStyle(AeroTheme.accentCyan)
                                .symbolRenderingMode(.hierarchical)
                                .frame(height: 110)
                            Text(intro.title)
                                .font(AeroTheme.title(30))
                                .foregroundStyle(AeroTheme.textPrimary)
                                .multilineTextAlignment(.center)
                            Text(intro.message)
                                .font(.body)
                                .foregroundStyle(AeroTheme.textSecondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 12)
                            Spacer()
                        }
                        .tag(intro.id)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .always))
                .indexViewStyle(.page(backgroundDisplayMode: .always))
                Button {
                    Haptics.tap()
                    withAnimation { page += 1 }
                } label: {
                    Text(page == Self.introPages.count - 1 ? "Iniziamo" : "Avanti")
                        .font(AeroTheme.title(18, weight: .bold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                }
                .buttonStyle(.borderedProminent)
                .tint(AeroTheme.accentCyan)
                .foregroundStyle(.black)
            }
            .padding(24)
        }
    }

    private var profileView: some View {
        ZStack {
            AeroTheme.background.ignoresSafeArea()
                .onTapGesture { focused = false }
            VStack(alignment: .leading, spacing: 28) {
                Spacer(minLength: 24)
                VStack(alignment: .leading, spacing: 8) {
                    Image(systemName: "airplane.departure")
                        .font(.system(size: 44, weight: .semibold))
                        .foregroundStyle(AeroTheme.accentCyan)
                    Text("AeroChat")
                        .font(AeroTheme.title(40))
                        .foregroundStyle(AeroTheme.textPrimary)
                    Text("Messaggi tra sedili, senza Wi-Fi e senza account. Solo un nickname.")
                        .font(.body)
                        .foregroundStyle(AeroTheme.textSecondary)
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text("COME TI CHIAMANO A BORDO?")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AeroTheme.textSecondary)
                    TextField("Nickname", text: $nickname)
                        .textInputAutocapitalization(.words)
                        .focused($focused)
                        .padding(16)
                        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .foregroundStyle(AeroTheme.textPrimary)
                        .font(AeroTheme.title(20, weight: .semibold))
                        .submitLabel(.go)
                        .onSubmit { Task { await save(startDemo: false) } }

                    HStack(spacing: 12) {
                        ForEach(Self.palette, id: \.self) { hex in
                            Circle()
                                .fill(Color(hex: UInt32(hex, radix: 16) ?? 0))
                                .frame(width: 34, height: 34)
                                .overlay(Circle().strokeBorder(AeroTheme.textPrimary, lineWidth: colorHex == hex ? 3 : 0))
                                .onTapGesture { colorHex = hex; Haptics.impact() }
                                .accessibilityLabel("Colore avatar \(hex)")
                                .accessibilityAddTraits(colorHex == hex ? .isSelected : [])
                        }
                    }
                }

                if let errorMessage {
                    Text(errorMessage).font(.footnote).foregroundStyle(.red)
                }

                Button {
                    Task { await save(startDemo: false) }
                } label: {
                    HStack {
                        if isSaving { ProgressView().tint(.black) }
                        Text("Sali a bordo")
                            .font(AeroTheme.title(18, weight: .bold))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                }
                .buttonStyle(.borderedProminent)
                .tint(AeroTheme.accentCyan)
                .hapticOnTap()
                .foregroundStyle(.black)
                .disabled(trimmed.count < 2 || isSaving)

                // Prova subito con il passeggero virtuale: niente secondo telefono necessario.
                Button {
                    Task { await save(startDemo: true) }
                } label: {
                    Label("Sali a bordo e prova subito", systemImage: "sparkles")
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                }
                .tint(AeroTheme.accentEmerald)
                .disabled(trimmed.count < 2 || isSaving)

                Text("Le chiavi crittografiche vengono generate sul tuo iPhone e non lasciano mai il dispositivo. Niente email, niente numero di telefono.")
                    .font(.caption)
                    .foregroundStyle(AeroTheme.textSecondary)
                Spacer()
            }
            .padding(24)
        }
        .onAppear { focused = true }
    }

    private func save(startDemo: Bool) async {
        guard trimmed.count >= 2, !isSaving else { return }
        isSaving = true
        defer { isSaving = false }
        do {
            try await environment.completeOnboarding(nickname: trimmed, avatarColorHex: colorHex)
            Haptics.success()
            if startDemo {
                await environment.startDemo()
            }
            _ = await LocalNotificationCenter().requestAuthorization()
        } catch {
            errorMessage = String(localized: "Impossibile salvare il profilo: \(error.localizedDescription)")
            Haptics.error()
        }
    }
}
