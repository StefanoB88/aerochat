import SwiftUI

/// Pairing via QR: mostra il proprio codice (personale o di gruppo) oppure scansiona quello altrui.
struct QRPairingSheet: View {

    enum Mode: String, CaseIterable, Identifiable {
        case show
        case scan
        var id: String { rawValue }

        var title: String {
            switch self {
            case .show: return String(localized: "Il mio QR")
            case .scan: return String(localized: "Scansiona")
            }
        }
    }

    var group: ConversationRecord?
    var initialMode: Mode = .show

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var mode: Mode = .show
    @State private var payload: PairingPayload?
    @State private var status: String?
    @State private var isBusy = false

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
                    case .show: showSection
                    case .scan: scanSection
                    }

                    if let status {
                        Text(status)
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(AeroTheme.accentEmerald)
                            .padding(.horizontal)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.top, 8)
            }
            .navigationTitle(group == nil ? String(localized: "Compagni di volo") : String(localized: "Invita nel gruppo"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Chiudi") { dismiss() }
                }
            }
        }
        .task {
            mode = initialMode
            await loadPayload()
        }
        .preferredColorScheme(.dark)
    }

    private var showSection: some View {
        VStack(spacing: 16) {
            if let payload, let image = QRCodeGenerator.pairingImage(for: payload) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 260, height: 260)
                    .padding(16)
                    .background(.white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                    .accessibilityLabel("QR code di pairing")
            } else {
                ProgressView().tint(AeroTheme.accentCyan).frame(height: 260)
            }
            Group {
                if let group {
                    Text("Chi inquadra questo codice entra nel gruppo \"\(group.title)\" e riceve automaticamente gli altri membri.")
                } else {
                    Text("Fai inquadrare questo codice al tuo compagno di volo: vi riconoscerete per sempre, anche sul volo di ritorno.")
                }
            }
            .font(.footnote)
            .foregroundStyle(AeroTheme.textSecondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 32)

            // Invito via AirDrop / Messaggi: link allo store + link di pairing, il compagno e' gia' collegato al primo avvio.
            if let payload {
                ShareLink(item: Self.inviteText(payload: payload, group: group)) {
                    Label(group == nil ? "Invita un compagno (AirDrop, Messaggi…)" : "Invita nel gruppo (AirDrop, Messaggi…)", systemImage: "square.and.arrow.up")
                        .font(.body.weight(.semibold))
                }
                .buttonStyle(.bordered)
                .tint(AeroTheme.accentCyan)
            }
        }
    }

    /// Testo dell'invito: finche' c'e' rete scarica l'app, poi il link `aeromesh://` apre AeroChat e collega i due telefoni.
    static func inviteText(payload: PairingPayload, group: ConversationRecord?) -> String {
        let intro = group.map { String(localized: "Entra nel mio gruppo AeroChat “\($0.title)”: chat via Bluetooth, anche in aereo senza Wi-Fi.") }
            ?? String(localized: "Chattiamo con AeroChat: via Bluetooth, anche in aereo senza Wi-Fi.")
        return """
        \(intro)
        1) \(String(localized: "Scarica l'app")): \(QRCodeGenerator.storeLandingURL)
        2) \(String(localized: "Poi apri questo link per collegarti a me")): \(payload.uriString)
        """
    }

    private var scanSection: some View {
        ZStack {
            QRScannerView(onCode: { code in
                Task { await handle(code: code) }
            }, onError: { error in
                status = error == .permissionDenied
                    ? String(localized: "Consenti la fotocamera in Impostazioni per scansionare.")
                    : String(localized: "Fotocamera non disponibile.")
            })
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .frame(height: 360)
            .padding(.horizontal)
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(AeroTheme.accentCyan, lineWidth: 3)
                .frame(width: 220, height: 220)
            if isBusy {
                ProgressView().tint(AeroTheme.accentCyan)
            }
        }
    }

    private func loadPayload() async {
        if let group, let router = environment.router {
            payload = await router.groupInvitePayload(for: group)
        } else if let keys = environment.identityKeys {
            payload = PairingPayload(identity: keys, nickname: environment.nickname)
        }
    }

    private func handle(code: String) async {
        guard !isBusy else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            let conversation = try await environment.pair(uriString: code)
            Haptics.success()
            status = conversation.type == .group
                ? String(localized: "Sei nel gruppo \(conversation.title)!")
                : String(localized: "Aggiunto \(conversation.title)!")
            try? await Task.sleep(for: .seconds(1))
            dismiss()
        } catch {
            Haptics.error()
            status = String(localized: "QR non riconosciuto: inquadra un codice AeroChat.")
        }
    }
}

/// QR statico verso gli store, da mostrare al gate finche' c'e' ancora rete.
struct StoreQRSheet: View {

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                VStack(spacing: 20) {
                    if let image = QRCodeGenerator.image(for: QRCodeGenerator.storeLandingURL) {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 260, height: 260)
                            .padding(16)
                            .background(.white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                    }
                    Text("Fai scaricare l'app ai compagni di volo adesso, finché c'è ancora 4G: a bordo basterà il Bluetooth.")
                        .font(.footnote)
                        .foregroundStyle(AeroTheme.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                    Text(verbatim: QRCodeGenerator.storeLandingURL)
                        .font(AeroTheme.monospaced())
                        .foregroundStyle(AeroTheme.accentCyan)
                    Spacer()
                }
                .padding(.top, 24)
            }
            .navigationTitle("Scarica al gate")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() } }
            }
        }
        .preferredColorScheme(.dark)
    }
}
