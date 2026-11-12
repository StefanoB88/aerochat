import SwiftUI
import CoreLocation

/// Avviso di emergenza/evento: stato, testo breve e posizione facoltativa a tutti i telefoni vicini.
struct EmergencySheet: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var status: EmergencyStatus = .ok
    @State private var text = ""
    @State private var includeLocation = false
    @State private var isSending = false
    @State private var errorMessage: String?
    @State private var locator = OneShotLocator()
    @State private var showCrewConfirmation = false

    /// Regole in volo (Cabina del volo attiva o modalita' aereo probabile).
    private var inFlight: Bool { environment.isLikelyInFlight }

    private let statuses: [EmergencyStatus] = [.ok, .needHelp, .allClear]

    var body: some View {
        NavigationStack {
            Form {
                if inFlight {
                    Section {
                        Label("In volo l'equipaggio è il primo riferimento.", systemImage: "person.badge.shield.checkmark.fill")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.orange)
                    }
                    .listRowBackground(Color.orange.opacity(0.12))
                }
                Section {
                    ForEach(statuses, id: \.rawValue) { candidate in
                        Button {
                            Haptics.tap()
                            status = candidate
                        } label: {
                            HStack {
                                Text(EmergencyService.emoji(for: candidate))
                                Text(EmergencyService.title(for: candidate))
                                    .foregroundStyle(AeroTheme.textPrimary)
                                Spacer()
                                if status == candidate {
                                    Image(systemName: "checkmark").foregroundStyle(AeroTheme.accentCyan)
                                }
                            }
                        }
                    }
                } header: {
                    Text("Stato")
                } footer: {
                    Text("L'avviso raggiunge tutti i telefoni AeroChat a portata, rimbalza fino a 7 salti e resta in coda per chi arriva dopo. Non sostituisce i soccorsi: se puoi, chiama il numero di emergenza locale.")
                }
                .listRowBackground(AeroTheme.surface)

                Section {
                    TextField("Dove sei, cosa serve…", text: $text, axis: .vertical)
                        .lineLimit(2...4)
                        .onChange(of: text) { _, value in
                            if value.count > EmergencyService.maxTextLength { text = String(value.prefix(EmergencyService.maxTextLength)) }
                        }
                } header: {
                    if needsHelpText {
                        Text("Messaggio")
                    } else {
                        Text("Messaggio (facoltativo)")
                    }
                } footer: {
                    if needsHelpText, !EmergencySafety.isHelpTextSufficient(text) {
                        Text("Descrivi cosa succede (almeno \(EmergencySafety.minimumHelpTextLength) caratteri).")
                    }
                }
                .listRowBackground(AeroTheme.surface)

                Section {
                    Toggle(isOn: $includeLocation) {
                        Label("Allega la mia posizione GPS", systemImage: "location.fill")
                    }
                    .tint(AeroTheme.accentCyan)
                    if includeLocation {
                        if let coordinate = locator.coordinate {
                            Text(String(format: "📍 %.5f, %.5f", coordinate.latitude, coordinate.longitude))
                                .font(AeroTheme.monospaced(12))
                                .foregroundStyle(AeroTheme.accentCyan)
                        } else if locator.isDenied {
                            Text("Posizione non autorizzata: attivala in Impostazioni › Privacy.")
                                .font(.footnote).foregroundStyle(.red)
                            Button("Apri Impostazioni") { PermissionPrompt.openSettings(for: .location) }
                                .font(.footnote.weight(.semibold))
                                .tint(AeroTheme.accentCyan)
                        } else {
                            ProgressView("Ricerca della posizione…").tint(AeroTheme.accentCyan)
                        }
                    }
                } footer: {
                    Text("Le coordinate viaggiano solo via Bluetooth, cifrate, verso i telefoni vicini. Nessun server.")
                }
                .listRowBackground(AeroTheme.surface)

                if let errorMessage {
                    Text(errorMessage).font(.footnote).foregroundStyle(.red).listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AeroTheme.background)
            .navigationTitle("Avviso di emergenza")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annulla") { dismiss() }.toolbarLabel() }
                ToolbarItem(placement: .confirmationAction) {
                    if isSending {
                        ProgressView().tint(AeroTheme.accentCyan)
                    } else {
                        Button("Invia") { attemptSend() }
                            .toolbarLabel()
                            .disabled((includeLocation && locator.coordinate == nil && !locator.isDenied)
                                      || (needsHelpText && !EmergencySafety.isHelpTextSufficient(text)))
                    }
                }
            }
            .onChange(of: includeLocation) { _, enabled in
                if enabled { locator.request() }
            }
            .alert("Avvisare tutta la cabina?", isPresented: $showCrewConfirmation) {
                Button("Invia l'avviso", role: .destructive) { Task { await send() } }
                Button("Annulla", role: .cancel) {}
            } message: {
                Text("Avvisa prima l'equipaggio: usa il pulsante di chiamata sopra il tuo posto. Inviare l'avviso a tutta la cabina?")
            }
        }
    }

    /// "Ho bisogno di aiuto" in volo: testo di almeno 10 caratteri.
    private var needsHelpText: Bool { EmergencySafety.requiresConfirmation(status, inFlight: inFlight) }

    /// In volo: al massimo un avviso ogni 10 minuti (qualunque stato) e conferma esplicita per "Ho bisogno di aiuto".
    private func attemptSend() {
        if inFlight, !EmergencySafety.canSend(lastSent: EmergencySafety.lastSentAt) {
            Haptics.error()
            let minutes = EmergencySafety.minutesUntilNext(lastSent: EmergencySafety.lastSentAt)
            errorMessage = String(localized: "In volo puoi inviare un avviso ogni 10 minuti: riprova tra \(minutes) min.")
            return
        }
        if needsHelpText {
            Haptics.warning()
            showCrewConfirmation = true
        } else {
            Task { await send() }
        }
    }

    private func send() async {
        guard let emergency = environment.emergency else { return }
        isSending = true
        defer { isSending = false }
        do {
            let coordinate = includeLocation ? locator.coordinate : nil
            try await emergency.broadcast(status: status, text: text, latitude: coordinate?.latitude, longitude: coordinate?.longitude)
            if inFlight { EmergencySafety.recordSent() }
            Haptics.success()
            dismiss()
        } catch {
            Haptics.error()
            errorMessage = String(localized: "Avviso non inviato: \(error.localizedDescription)")
        }
    }
}

/// Una sola lettura della posizione, solo quando l'utente la chiede.
@MainActor
@Observable
final class OneShotLocator: NSObject, CLLocationManagerDelegate {

    private(set) var coordinate: CLLocationCoordinate2D?
    /// Ultima lettura completa (con l'accuratezza), per la posizione condivisa in chat.
    private(set) var location: CLLocation?
    private(set) var isDenied = false
    /// Lettura fallita (GPS non disponibile, al chiuso): si puo' riprovare.
    private(set) var didFail = false
    /// Chiamato a ogni lettura riuscita (posizione in chat: una lettura e invio).
    @ObservationIgnored var onLocation: ((CLLocation) -> Void)?
    @ObservationIgnored var onFailure: (() -> Void)?
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func request() {
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            isDenied = true
            // Negata in passato: a ogni tentativo spieghiamo come riattivarla.
            PermissionPrompt.showDenied(.location)
        default:
            isDenied = false
            didFail = false
            manager.requestLocation()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            switch status {
            case .authorizedAlways, .authorizedWhenInUse: self.manager.requestLocation()
            case .denied, .restricted:
                self.isDenied = true
                self.onFailure?()
            default: break
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        Task { @MainActor in
            self.coordinate = last.coordinate
            self.location = last
            self.onLocation?(last)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            self.didFail = true
            self.onFailure?()
        }
    }
}

/// Banner rosso sopra le tab quando arriva un avviso.
struct EmergencyBanner: View {

    let alert: EmergencyAlert
    var onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(EmergencyService.emoji(for: alert.status))
                .font(.title2)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(alert.senderNickname): \(alert.statusTitle)")
                    .font(.subheadline.weight(.bold))
                if !alert.text.isEmpty {
                    Text(alert.text).font(.footnote).lineLimit(3)
                }
                if let latitude = alert.latitude, let longitude = alert.longitude {
                    Link(destination: URL(string: "maps://?ll=\(latitude),\(longitude)&q=\(alert.senderNickname)")!) {
                        Label("Apri la posizione in Mappe", systemImage: "map")
                            .font(.caption.weight(.semibold))
                    }
                }
            }
            Spacer()
            Button { onDismiss() } label: {
                Image(systemName: "xmark.circle.fill").font(.title3)
            }
            .accessibilityLabel("Chiudi avviso")
        }
        .foregroundStyle(.white)
        .padding(14)
        .background(alert.status == .needHelp ? Color.red : Color.orange, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.top, 6)
    }
}
