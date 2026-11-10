import SwiftUI

/// "Cosa mi sono perso?": riassunto on-device delle ultime voci della Cabina (Apple Intelligence).
struct LoungeSummarySheet: View {

    let messages: [MessageRecord]

    @Environment(\.dismiss) private var dismiss
    @State private var summary: String?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        HStack(spacing: 10) {
                            Image(systemName: "sparkles")
                                .font(.title2)
                                .foregroundStyle(AeroTheme.accentCyan)
                            Text("Cosa mi sono perso")
                                .font(AeroTheme.title(24))
                                .foregroundStyle(AeroTheme.textPrimary)
                        }
                        if let summary {
                            Text(summary)
                                .font(.body)
                                .foregroundStyle(AeroTheme.textPrimary)
                                .textSelection(.enabled)
                        } else if let errorMessage {
                            Text(errorMessage)
                                .font(.footnote)
                                .foregroundStyle(.red)
                        } else {
                            HStack(spacing: 10) {
                                ProgressView().tint(AeroTheme.accentCyan)
                                Text("Il modello sul tuo iPhone sta leggendo \(messages.count) messaggi…")
                                    .font(.footnote)
                                    .foregroundStyle(AeroTheme.textSecondary)
                            }
                        }
                        Text("Generato interamente sul dispositivo con Apple Intelligence: nessun messaggio lascia il telefono. Può contenere imprecisioni.")
                            .font(.caption)
                            .foregroundStyle(AeroTheme.textSecondary)
                    }
                    .padding(24)
                }
            }
            .navigationTitle("Riassunto")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() } }
            }
        }
        .task {
            let lines = messages.compactMap { message -> String? in
                guard message.contentType == .text, let text = message.text, !text.isEmpty else { return nil }
                return "\(message.senderNickname ?? String(localized: "Passeggero")): \(text)"
            }
            guard !lines.isEmpty else {
                summary = String(localized: "La Cabina è ancora silenziosa: nessun messaggio da riassumere.")
                return
            }
            do {
                summary = try await CabinAssistant.summarize(lines)
            } catch {
                errorMessage = String(localized: "Riassunto non disponibile: \(error.localizedDescription)")
            }
        }
        .preferredColorScheme(.dark)
    }
}
