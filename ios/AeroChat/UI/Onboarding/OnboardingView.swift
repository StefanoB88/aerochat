import SwiftUI

/// Onboarding zero-account in 5 secondi: nickname → chiavi nel Keychain → Home.
struct OnboardingView: View {

    @Environment(AppEnvironment.self) private var environment
    @State private var nickname = ""
    @State private var colorHex = OnboardingView.palette[0]
    @State private var isSaving = false
    @State private var errorMessage: String?
    @FocusState private var focused: Bool

    static let palette = ["00E5FF", "10B981", "F59E0B", "EC4899", "8B5CF6", "F97316"]

    private var trimmed: String { nickname.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
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
                        .onSubmit { Task { await save() } }

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
                    Task { await save() }
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

                Text("Le chiavi crittografiche vengono generate sul tuo iPhone e non lasciano mai il dispositivo. Niente email, niente numero di telefono.")
                    .font(.caption)
                    .foregroundStyle(AeroTheme.textSecondary)
                Spacer()
            }
            .padding(24)
        }
        .onAppear { focused = true }
    }

    private func save() async {
        guard trimmed.count >= 2, !isSaving else { return }
        isSaving = true
        defer { isSaving = false }
        do {
            try await environment.completeOnboarding(nickname: trimmed, avatarColorHex: colorHex)
            Haptics.success()
            _ = await LocalNotificationCenter().requestAuthorization()
        } catch {
            errorMessage = String(localized: "Impossibile salvare il profilo: \(error.localizedDescription)")
            Haptics.error()
        }
    }
}
