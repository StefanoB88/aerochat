import SwiftUI

/// "Cosa mi sono perso?": riassunto on-device delle ultime voci della Cabina (Apple Intelligence).
struct LoungeSummarySheet: View {

    let messages: [MessageRecord]

    @Environment(\.dismiss) private var dismiss
    @State private var summary: String?
    @State private var points: [String] = []
    @State private var readCount = 0
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
                        if !points.isEmpty {
                            VStack(alignment: .leading, spacing: 10) {
                                ForEach(points, id: \.self) { point in
                                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                                        Text(verbatim: "•").foregroundStyle(AeroTheme.accentCyan)
                                        Text(point)
                                            .font(.body)
                                            .foregroundStyle(AeroTheme.textPrimary)
                                            .textSelection(.enabled)
                                    }
                                }
                            }
                            Text("Basato sugli ultimi \(readCount) messaggi della Cabina.")
                                .font(.caption)
                                .foregroundStyle(AeroTheme.textSecondary)
                        } else if let summary {
                            Text(summary)
                                .font(.body)
                                .foregroundStyle(AeroTheme.textPrimary)
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
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() }.toolbarLabel() }
            }
        }
        .task {
            // Solo i testi scritti dai passeggeri, in ordine cronologico (niente avvisi di sistema ne' quiz).
            let lines = messages
                .filter { $0.contentType == .text }
                .sorted { $0.createdAtMs < $1.createdAtMs }
                .compactMap { message -> CabinAssistant.ChatLine? in
                    guard let text = message.text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
                    let author = message.isOutgoing ? String(localized: "Io") : (message.senderNickname ?? String(localized: "Passeggero"))
                    return CabinAssistant.ChatLine(author: author, text: text)
                }
            guard lines.count >= CabinAssistant.minimumLinesForSummary else {
                summary = lines.isEmpty
                    ? String(localized: "La Cabina è ancora silenziosa: nessun messaggio da riassumere.")
                    : String(localized: "Ci sono ancora pochi messaggi: leggili direttamente in Cabina.")
                return
            }
            readCount = min(lines.count, 50)
            do {
                points = try await CabinAssistant.summarize(lines)
                if points.isEmpty {
                    summary = String(localized: "Non ho trovato argomenti chiari da riassumere: dai un'occhiata ai messaggi in Cabina.")
                }
            } catch {
                errorMessage = String(localized: "Riassunto non disponibile: \(error.localizedDescription)")
            }
        }
        .preferredColorScheme(.dark)
    }
}
