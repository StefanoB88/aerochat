import SwiftUI

/// Segnalazione di un messaggio: motivo, dettagli facoltativi, blocco del mittente e inoltro via email
/// al team di sicurezza (la mesh non ha un server a cui inviare il rapporto).
struct ReportSheet: View {

    let message: MessageRecord
    /// Chiamato a segnalazione salvata: rapporto e se il mittente e' stato bloccato.
    var onReported: (ReportRecord, Bool) -> Void = { _, _ in }

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    @State private var reason: ReportReason = .harassment
    @State private var details = ""
    @State private var blockSender = true
    @State private var isSaving = false
    @State private var saved: ReportRecord?
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        if let sender = message.senderNickname {
                            Text(sender)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(AeroTheme.accentCyan)
                        }
                        Text(message.text ?? String(localized: "Contenuto multimediale"))
                            .font(.body)
                            .foregroundStyle(AeroTheme.textPrimary)
                            .lineLimit(4)
                    }
                } header: {
                    Text("Messaggio segnalato")
                }
                .listRowBackground(AeroTheme.surface)

                Section("Motivo") {
                    Picker("Motivo", selection: $reason) {
                        ForEach(ReportReason.allCases) { reason in
                            Text(reason.title).tag(reason)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
                .listRowBackground(AeroTheme.surface)

                Section("Dettagli (facoltativi)") {
                    TextField("Cosa è successo?", text: $details, axis: .vertical)
                        .lineLimit(2...5)
                }
                .listRowBackground(AeroTheme.surface)

                Section {
                    Toggle("Blocca anche \(message.senderNickname ?? String(localized: "questo utente"))", isOn: $blockSender)
                        .tint(AeroTheme.accentCyan)
                } footer: {
                    Text("Un utente bloccato non può più scriverti e i suoi messaggi non vengono ritrasmessi dal tuo telefono. Puoi sbloccarlo da Impostazioni › Utenti bloccati.")
                }
                .listRowBackground(AeroTheme.surface)

                if let saved {
                    Section {
                        Button {
                            sendEmail(saved)
                        } label: {
                            Label("Invia il rapporto via email", systemImage: "envelope")
                        }
                    } footer: {
                        Text("La segnalazione è salvata sul tuo iPhone. Se hai rete puoi inoltrarla al team di sicurezza: esaminiamo ogni rapporto entro 24 ore.")
                    }
                    .listRowBackground(AeroTheme.surface)
                }

                if let errorMessage {
                    Text(errorMessage).font(.footnote).foregroundStyle(.red)
                        .listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AeroTheme.background)
            .navigationTitle("Segnala")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(saved == nil ? "Annulla" : "Chiudi") { dismiss() }.toolbarLabel()
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saved == nil {
                        if isSaving {
                            ProgressView().tint(AeroTheme.accentCyan)
                        } else {
                            Button("Invia") { Task { await submit() } }.toolbarLabel()
                        }
                    }
                }
            }
        }
    }

    private func submit() async {
        guard !isSaving else { return }
        isSaving = true
        defer { isSaving = false }
        do {
            let report = try await environment.report(message: message, reason: reason, details: details, blockSender: blockSender)
            saved = report
            Haptics.success()
            onReported(report, blockSender)
        } catch {
            Haptics.error()
            errorMessage = String(localized: "Segnalazione non salvata: \(error.localizedDescription)")
        }
    }

    private func sendEmail(_ report: ReportRecord) {
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = AppLinks.safetyEmail
        components.queryItems = [
            URLQueryItem(name: "subject", value: String(localized: "Segnalazione AeroChat – \(report.reason.title)")),
            URLQueryItem(name: "body", value: report.emailBody),
        ]
        if let url = components.url { openURL(url) }
    }
}
