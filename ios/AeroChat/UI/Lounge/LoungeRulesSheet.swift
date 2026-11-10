import SwiftUI

/// Regole della Cabina Pubblica, mostrate alla prima apertura (accettazione obbligatoria) e dal pulsante
/// info. Richiesto dalle linee guida App Store 1.2 per i contenuti generati dagli utenti.
struct LoungeRulesSheet: View {

    let requiresAcceptance: Bool
    var onAccept: () -> Void = {}

    @Environment(\.dismiss) private var dismiss
    @State private var showTerms = false

    private struct Rule: Identifiable {
        let id = UUID()
        let icon: String
        let title: String
        let detail: String
    }

    private var rules: [Rule] {
        [
            Rule(icon: "person.2.wave.2", title: String(localized: "Rispetto prima di tutto"),
                 detail: String(localized: "Niente insulti, molestie, odio o discriminazioni. Le parolacce vengono oscurate.")),
            Rule(icon: "eye.slash", title: String(localized: "Anonimo ma non impunito"),
                 detail: String(localized: "Si vede solo il nickname. Chi viola le regole può essere bloccato e segnalato da chiunque.")),
            Rule(icon: "link.badge.plus", title: String(localized: "Solo testo, niente link"),
                 detail: String(localized: "Foto, vocali e link non sono ammessi nella bacheca pubblica.")),
            Rule(icon: "lock.shield", title: String(localized: "Niente dati personali"),
                 detail: String(localized: "Non condividere numero di telefono, posto, documenti o dettagli di altri passeggeri.")),
            Rule(icon: "timer", title: String(localized: "Sparisce da sola"),
                 detail: String(localized: "La Cabina si svuota ogni 12 ore e resta solo sui telefoni a bordo.")),
        ]
    }

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        Image(systemName: "airplane.circle.fill")
                            .font(.system(size: 48))
                            .foregroundStyle(AeroTheme.accentCyan)
                            .frame(maxWidth: .infinity)
                        Text("Benvenuto in Cabina Pubblica")
                            .font(AeroTheme.title(26))
                            .foregroundStyle(AeroTheme.textPrimary)
                            .frame(maxWidth: .infinity)
                            .multilineTextAlignment(.center)
                        Text("Una bacheca tra i passeggeri a portata di Bluetooth. Per restare piacevole per tutti ha poche regole semplici.")
                            .font(.footnote)
                            .foregroundStyle(AeroTheme.textSecondary)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                        ForEach(rules) { rule in
                            HStack(alignment: .top, spacing: 14) {
                                Image(systemName: rule.icon)
                                    .font(.title3)
                                    .foregroundStyle(AeroTheme.accentCyan)
                                    .frame(width: 28)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(rule.title)
                                        .font(.body.weight(.semibold))
                                        .foregroundStyle(AeroTheme.textPrimary)
                                    Text(rule.detail)
                                        .font(.footnote)
                                        .foregroundStyle(AeroTheme.textSecondary)
                                }
                            }
                        }
                        Button {
                            showTerms = true
                        } label: {
                            Text("Leggi i Termini di utilizzo completi")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(AeroTheme.accentCyan)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .padding(24)
                }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    Haptics.success()
                    onAccept()
                    dismiss()
                } label: {
                    Text(requiresAcceptance ? "Accetto le regole" : "Ho capito")
                        .font(AeroTheme.title(18, weight: .bold))
                        .frame(maxWidth: .infinity, minHeight: 28)
                        .padding(.vertical, 14)
                }
                .buttonStyle(.borderedProminent)
                .tint(AeroTheme.accentCyan)
                .foregroundStyle(.black)
                .padding(.horizontal, 24)
                .padding(.bottom, 12)
                .background(AeroTheme.background)
            }
            .navigationTitle("Regole della Cabina")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !requiresAcceptance {
                    ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() } }
                }
            }
            .sheet(isPresented: $showTerms) { LegalView(document: .terms) }
        }
        .preferredColorScheme(.dark)
    }
}
