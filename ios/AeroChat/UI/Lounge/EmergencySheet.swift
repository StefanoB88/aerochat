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

    private let statuses: [EmergencyStatus] = [.ok, .needHelp, .allClear]

    var body: some View {
        NavigationStack {
            Form {
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

                Section("Messaggio (facoltativo)") {
                    TextField("Dove sei, cosa serve…", text: $text, axis: .vertical)
                        .lineLimit(2...4)
                        .onChange(of: text) { _, value in
                            if value.count > EmergencyService.maxTextLength { text = String(value.prefix(EmergencyService.maxTextLength)) }
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
                        Button("Invia") { Task { await send() } }
                            .toolbarLabel()
                            .disabled(includeLocation && locator.coordinate == nil && !locator.isDenied)
                    }
                }
            }
            .onChange(of: includeLocation) { _, enabled in
                if enabled { locator.request() }
            }
        }
    }

    private func send() async {
        guard let emergency = environment.emergency else { return }
        isSending = true
        defer { isSending = false }
        do {
            let coordinate = includeLocation ? locator.coordinate : nil
            try await emergency.broadcast(status: status, text: text, latitude: coordinate?.latitude, longitude: coordinate?.longitude)
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
    private(set) var isDenied = false
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func request() {
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .denied, .restricted: isDenied = true
        default: manager.requestLocation()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            switch status {
            case .authorizedAlways, .authorizedWhenInUse: self.manager.requestLocation()
            case .denied, .restricted: self.isDenied = true
            default: break
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        let coordinate = last.coordinate
        Task { @MainActor in self.coordinate = coordinate }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
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
