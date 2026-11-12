import SwiftUI

/// Card non bloccante mostrata quando il Bluetooth non e' acceso (tipicamente in Modalita' Aereo).
struct AirplaneModeBanner: View {

    let state: RadioState

    static func shouldShow(for state: RadioState) -> Bool {
        switch state {
        case .poweredOff, .unauthorized, .unsupported: return true
        case .poweredOn, .unknown, .resetting: return false
        }
    }

    private var title: String {
        switch state {
        case .unauthorized: return String(localized: "Bluetooth non autorizzato")
        case .unsupported: return String(localized: "Bluetooth non disponibile")
        default: return String(localized: "Sei in Modalità Aereo?")
        }
    }

    private var message: String {
        switch state {
        case .unauthorized:
            return String(localized: "Consenti il Bluetooth ad AeroChat in Impostazioni › Privacy per parlare con i compagni di volo.")
        case .unsupported:
            return String(localized: "Questo dispositivo non supporta il Bluetooth LE necessario alla mesh.")
        default:
            return String(localized: "Puoi riattivare il Bluetooth in totale sicurezza (come per le cuffie wireless) mantenendo spento il segnale cellulare.")
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "airplane.circle.fill")
                .font(.title2)
                .foregroundStyle(AeroTheme.accentCyan)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(AeroTheme.title(16, weight: .semibold))
                    .foregroundStyle(AeroTheme.textPrimary)
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(AeroTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if state == .unauthorized {
                    Button("Apri Impostazioni") {
                        Haptics.tap()
                        PermissionPrompt.openSettings(for: .bluetooth)
                    }
                    .font(.footnote.weight(.semibold))
                    .tint(AeroTheme.accentCyan)
                    .padding(.top, 2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(AeroTheme.accentCyan.opacity(0.35)))
        .padding(.horizontal, 16)
        .padding(.top, 4)
        .transition(.move(edge: .top).combined(with: .opacity))
        .accessibilityElement(children: .combine)
    }
}
