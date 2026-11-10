import SwiftUI
import AVFoundation

/// Entra nella Cabina del volo: inquadra il codice della carta d'imbarco (PDF417/Aztec/QR, formato IATA)
/// oppure inserisci numero di volo e data. Nulla lascia il telefono: dal volo si deriva solo la chiave del gruppo.
struct BoardingPassSheet: View {

    enum Mode: String, CaseIterable, Identifiable {
        case scan
        case manual
        var id: String { rawValue }

        var title: String {
            switch self {
            case .scan: return String(localized: "Inquadra")
            case .manual: return String(localized: "Inserisci")
            }
        }
    }

    /// Chiamato con la Cabina del volo appena creata o ritrovata.
    var onJoined: (ConversationRecord) -> Void = { _ in }

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var mode: Mode = .scan
    @State private var flightText = ""
    @State private var departureDay = Date()
    @State private var status: String?
    @State private var isError = false
    @State private var isBusy = false
    @State private var detected: BoardingPass?

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                VStack(spacing: 20) {
                    Picker("Modalità", selection: $mode) {
                        ForEach(Mode.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .controlSize(.large)
                    .padding(.horizontal)

                    switch mode {
                    case .scan: scanSection
                    case .manual: manualSection
                    }

                    if let detected {
                        detectedCard(detected)
                    }

                    if let status {
                        Text(status)
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(isError ? .red : AeroTheme.accentEmerald)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.top, 8)
            }
            .navigationTitle("Cabina del volo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() }.toolbarLabel() }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var scanSection: some View {
        VStack(spacing: 14) {
            ZStack {
                QRScannerView(onCode: { code in
                    Task { await handle(code: code) }
                }, onError: { error in
                    isError = true
                    status = error == .permissionDenied
                        ? String(localized: "Consenti la fotocamera in Impostazioni per inquadrare la carta d'imbarco.")
                        : String(localized: "Fotocamera non disponibile.")
                }, metadataTypes: [.pdf417, .aztec, .qr])
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .frame(height: 300)
                .padding(.horizontal)
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(AeroTheme.accentCyan, lineWidth: 3)
                    .frame(width: 280, height: 160)
                if isBusy { ProgressView().tint(AeroTheme.accentCyan) }
            }
            Text("Inquadra il codice a barre della carta d'imbarco (anche dallo schermo di un altro telefono o dal Wallet). Leggiamo solo compagnia, numero di volo e data: nome e PNR vengono ignorati.")
                .font(.footnote)
                .foregroundStyle(AeroTheme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
    }

    private var manualSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("NUMERO DI VOLO")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AeroTheme.textSecondary)
                TextField("es. AZ 204", text: $flightText)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .padding(16)
                    .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .foregroundStyle(AeroTheme.textPrimary)
                    .font(AeroTheme.title(22, weight: .semibold))
            }
            DatePicker("Giorno di partenza", selection: $departureDay, displayedComponents: .date)
                .tint(AeroTheme.accentCyan)
                .foregroundStyle(AeroTheme.textPrimary)
                .padding(16)
                .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            Button {
                Haptics.tap()
                guard let pass = BoardingPass(flightText: flightText, departureDay: departureDay) else {
                    isError = true
                    status = String(localized: "Numero di volo non valido: usa il formato AZ 204.")
                    Haptics.error()
                    return
                }
                Task { await join(pass) }
            } label: {
                Label("Entra nella Cabina del volo", systemImage: "airplane")
                    .font(AeroTheme.title(18, weight: .bold))
                    .frame(maxWidth: .infinity, minHeight: 28)
                    .padding(.vertical, 14)
            }
            .buttonStyle(.borderedProminent)
            .tint(AeroTheme.accentCyan)
            .foregroundStyle(.black)
            .disabled(isBusy || flightText.trimmingCharacters(in: .whitespaces).isEmpty)
            Text("Tutti i passeggeri che inseriscono lo stesso volo e la stessa data entrano nella stessa stanza cifrata, senza scambiarsi QR.")
                .font(.footnote)
                .foregroundStyle(AeroTheme.textSecondary)
        }
        .padding(.horizontal, 20)
    }

    private func detectedCard(_ pass: BoardingPass) -> some View {
        HStack(spacing: 14) {
            Image(systemName: "airplane.departure")
                .font(.title2)
                .foregroundStyle(AeroTheme.accentCyan)
            VStack(alignment: .leading, spacing: 3) {
                Text(pass.flightCode)
                    .font(AeroTheme.title(20, weight: .bold))
                    .foregroundStyle(AeroTheme.textPrimary)
                Text([pass.routeText, pass.departureDay.formatted(date: .abbreviated, time: .omitted), pass.seat.map { String(localized: "posto \($0)") } ?? ""]
                    .filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.footnote)
                    .foregroundStyle(AeroTheme.textSecondary)
            }
            Spacer()
        }
        .padding(16)
        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: AeroTheme.cornerRadius, style: .continuous))
        .padding(.horizontal, 20)
    }

    private func handle(code: String) async {
        guard !isBusy else { return }
        do {
            let pass = try BoardingPass(bcbp: code)
            detected = pass
            await join(pass)
        } catch {
            // Un QR di pairing AeroChat inquadrato per sbaglio non e' una carta d'imbarco.
            isError = true
            status = code.hasPrefix(MeshProtocol.qrScheme)
                ? String(localized: "Questo è un QR di AeroChat: per aggiungere un compagno usa “Compagni di volo”.")
                : String(localized: "Codice non riconosciuto: inquadra il codice a barre della carta d'imbarco.")
        }
    }

    private func join(_ pass: BoardingPass) async {
        isBusy = true
        defer { isBusy = false }
        do {
            let lounge = try await environment.joinFlightLounge(pass)
            Haptics.success()
            isError = false
            status = String(localized: "Sei a bordo di \(pass.flightCode)! Chi scansiona la stessa carta ti trova qui.")
            try? await Task.sleep(for: .seconds(1))
            onJoined(lounge)
            dismiss()
        } catch {
            Haptics.error()
            isError = true
            status = String(localized: "Impossibile entrare nella Cabina del volo: \(error.localizedDescription)")
        }
    }
}
