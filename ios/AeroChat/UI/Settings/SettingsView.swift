import SwiftUI

/// Impostazioni in 5 sezioni: Profilo, Privacy, Notifiche, Abbonamento, Info e legale (stesse funzioni di prima,
/// meno righe in primo piano: strumenti di viaggio, filtri e documenti legali stanno in sotto-pagine).
struct SettingsView: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss

    @AppStorage(AppSettings.Key.languageFilter) private var languageFilter = true
    @AppStorage(AppSettings.Key.blurStrangerMedia) private var blurStrangerMedia = true
    @AppStorage(AppSettings.Key.demoMode) private var demoMode = false
    @AppStorage(AppSettings.Key.nearbyVisible) private var nearbyVisible = true
    @AppStorage(AppSettings.Key.openToGames) private var openToGames = true
    @Environment(\.openURL) private var openURL

    @State private var showPaywall = false
    @State private var showFlightCard = false
    @State private var showRadar = false
    @State private var showEmergency = false
    @AppStorage(AppSettings.Key.smartReplies) private var smartReplies = true
    @State private var blockedCount = 0
    @State private var reportCount = 0
    /// Tocchi sulla riga della versione: al quinto compare l'interruttore della modalita' demo (App Review).
    @State private var versionTaps = 0
    @State private var showDemoToggle = false

    var body: some View {
        NavigationStack {
            List {
                profileSection
                privacySection
                notificationsSection
                subscriptionSection
                aboutSection
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(AeroTheme.background)
            .navigationTitle("Impostazioni")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(AeroTheme.background, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() }.toolbarLabel() }
            }
            .sheet(isPresented: $showPaywall) { PaywallView() }
            .sheet(isPresented: $showFlightCard) { FlightCardSheet() }
            .sheet(isPresented: $showRadar) { RadarSheet() }
            .sheet(isPresented: $showEmergency) { EmergencySheet() }
            .task { await refreshCounts() }
            .onChange(of: demoMode) { _, enabled in
                Task { await environment.setDemoMode(enabled) }
            }
            .onChange(of: nearbyVisible) { _, _ in environment.nearbySettingsChanged() }
            .onChange(of: openToGames) { _, _ in environment.nearbySettingsChanged() }
        }
    }

    // MARK: Profilo

    private var profileSection: some View {
        Section("Profilo") {
            NavigationLink {
                ProfileEditView()
            } label: {
                HStack(spacing: 12) {
                    AvatarView(imageData: environment.avatarImage, colorHex: environment.avatarColorHex, size: 48)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(environment.nickname)
                            .font(AeroTheme.title(18, weight: .semibold))
                            .foregroundStyle(AeroTheme.textPrimary)
                        Text("Modifica nickname, colore e foto")
                            .font(.caption)
                            .foregroundStyle(AeroTheme.textSecondary)
                    }
                }
                .padding(.vertical, 4)
            }
            NavigationLink {
                travelToolsList
            } label: {
                Label("Strumenti di viaggio", systemImage: "suitcase.rolling")
            }
        }
        .foregroundStyle(AeroTheme.textPrimary)
        .listRowBackground(AeroTheme.surface)
    }

    /// Cartolina, radar, emergenza e risposte rapide: stessi strumenti di prima, in una sotto-pagina.
    private var travelToolsList: some View {
        List {
            Section {
                settingsRow("Cartolina di volo", systemImage: "sparkles.rectangle.stack") { showFlightCard = true }
                settingsRow("Radar di prossimità", systemImage: "dot.radiowaves.left.and.right") { showRadar = true }
                settingsRow("Avviso di emergenza", systemImage: "exclamationmark.triangle") { showEmergency = true }
                if CabinAssistant.isAvailable {
                    Toggle(isOn: $smartReplies) {
                        Label("Risposte rapide (Apple Intelligence)", systemImage: "sparkles")
                    }
                    .tint(AeroTheme.accentCyan)
                }
            } footer: {
                Text("AeroChat funziona ovunque manchi la rete: in volo, in crociera, a un festival, in montagna o durante un blackout. L'avviso di emergenza raggiunge tutti i telefoni vicini.")
            }
            .foregroundStyle(AeroTheme.textPrimary)
            .listRowBackground(AeroTheme.surface)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AeroTheme.background)
        .navigationTitle("Strumenti di viaggio")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func settingsRow(_ title: LocalizedStringKey, systemImage: String, action: @escaping () -> Void) -> some View {
        Button {
            Haptics.tap()
            action()
        } label: {
            HStack {
                Label(title, systemImage: systemImage)
                    .foregroundStyle(AeroTheme.textPrimary)
                Spacer()
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(AeroTheme.textSecondary)
            }
        }
    }

    // MARK: Privacy

    private var privacySection: some View {
        Section {
            Toggle(isOn: $nearbyVisible) {
                Label("Visibile alle persone vicine", systemImage: "person.wave.2")
            }
            Toggle(isOn: $openToGames) {
                Label("Accetto sfide a carte", systemImage: "suit.spade")
            }
            .disabled(!nearbyVisible)
            NavigationLink {
                BlockedUsersView()
            } label: {
                HStack {
                    Label("Utenti bloccati", systemImage: "hand.raised.fill")
                    Spacer()
                    Text("\(blockedCount)").foregroundStyle(AeroTheme.textSecondary)
                }
            }
            NavigationLink {
                contentSafetyList
            } label: {
                Label("Filtro e contenuti", systemImage: "textformat.abc.dottedunderline")
            }
        } header: {
            Text("Privacy")
        } footer: {
            Text("Da invisibile continui a vedere chi è vicino, ma nessuno vede te. Nessuno diventa tuo contatto finché non accetti.")
        }
        .tint(AeroTheme.accentCyan)
        .foregroundStyle(AeroTheme.textPrimary)
        .listRowBackground(AeroTheme.surface)
    }

    /// Filtro linguaggio, foto sfocate e segnalazioni.
    private var contentSafetyList: some View {
        List {
            Section {
                Toggle(isOn: $languageFilter) {
                    Label("Filtro linguaggio in Cabina", systemImage: "textformat.abc.dottedunderline")
                }
                Toggle(isOn: $blurStrangerMedia) {
                    Label("Sfoca le foto da non verificati", systemImage: "eye.slash")
                }
                NavigationLink {
                    ReportsListView()
                } label: {
                    HStack {
                        Label("Segnalazioni", systemImage: "exclamationmark.bubble")
                        Spacer()
                        Text("\(reportCount)").foregroundStyle(AeroTheme.textSecondary)
                    }
                }
            } footer: {
                Text("Le foto dei contatti che non hai mai scansionato di persona (membri dei gruppi arrivati via gossip) restano sfocate finché non le tocchi.")
            }
            .tint(AeroTheme.accentCyan)
            .foregroundStyle(AeroTheme.textPrimary)
            .listRowBackground(AeroTheme.surface)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AeroTheme.background)
        .navigationTitle("Filtro e contenuti")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: Notifiche

    private var notificationsSection: some View {
        Section {
            Button {
                Haptics.tap()
                if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
            } label: {
                HStack {
                    Label("Notifiche di AeroChat", systemImage: "bell.badge")
                        .foregroundStyle(AeroTheme.textPrimary)
                    Spacer()
                    Image(systemName: "arrow.up.forward.app").font(.caption.weight(.semibold)).foregroundStyle(AeroTheme.textSecondary)
                }
            }
        } header: {
            Text("Notifiche")
        } footer: {
            Text("Messaggi, richieste di persone vicine e la cartolina a fine volo arrivano anche a schermo spento.")
        }
        .listRowBackground(AeroTheme.surface)
    }

    // MARK: Abbonamento

    private var subscriptionSection: some View {
        Section("Abbonamento") {
            Button {
                Haptics.tap()
                showPaywall = true
            } label: {
                HStack {
                    Label(environment.isPro ? "Flight Group Pass attivo" : "Flight Group Pass", systemImage: environment.isPro ? "ticket.fill" : "ticket")
                        .foregroundStyle(AeroTheme.textPrimary)
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(AeroTheme.textSecondary)
                }
            }
        }
        .listRowBackground(AeroTheme.surface)
    }

    // MARK: Info e legale

    private var legalList: some View {
        List {
            Section {
                NavigationLink {
                    LegalContentView(document: .terms)
                        .navigationTitle(LegalDocument.terms.title)
                        .navigationBarTitleDisplayMode(.inline)
                } label: {
                    Label("Termini di utilizzo", systemImage: "doc.text")
                }
                NavigationLink {
                    LegalContentView(document: .privacy)
                        .navigationTitle(LegalDocument.privacy.title)
                        .navigationBarTitleDisplayMode(.inline)
                } label: {
                    Label("Informativa privacy", systemImage: "lock.shield")
                }
                Link(destination: AppLinks.appleEULA) {
                    Label("Licenza acquisti in-app (EULA Apple)", systemImage: "apple.logo")
                }
            }
            .foregroundStyle(AeroTheme.textPrimary)
            .listRowBackground(AeroTheme.surface)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AeroTheme.background)
        .navigationTitle("Legale")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var aboutSection: some View {
        Section {
            HStack {
                Label("Versione", systemImage: "info.circle")
                Spacer()
                Text(Self.versionString).foregroundStyle(AeroTheme.textSecondary)
            }
            .contentShape(Rectangle())
            .onTapGesture {
                versionTaps += 1
                if versionTaps >= 5 {
                    showDemoToggle = true
                    Haptics.impact()
                }
            }
            if showDemoToggle || demoMode {
                Toggle(isOn: $demoMode) {
                    Label("Passeggero virtuale (prova)", systemImage: "person.crop.circle.badge.questionmark")
                }
                .tint(AeroTheme.accentCyan)
            }
            NavigationLink {
                legalList
            } label: {
                Label("Legale", systemImage: "doc.text")
            }
            Link(destination: URL(string: "mailto:\(AppLinks.supportEmail)")!) {
                Label("Contatta il supporto", systemImage: "envelope")
            }
        } header: {
            Text("Info e legale")
        } footer: {
            if showDemoToggle || demoMode {
                Text("Il passeggero virtuale risponde ai tuoi messaggi e gioca sullo stesso iPhone: serve a provare l'app (o a revisionarla) senza un secondo telefono. Non usa la radio.")
            }
        }
        .foregroundStyle(AeroTheme.textPrimary)
        .listRowBackground(AeroTheme.surface)
    }

    private func refreshCounts() async {
        guard let database = environment.database else { return }
        blockedCount = (try? await database.peers.blockedNodeIDs().count) ?? 0
        reportCount = (try? await database.reports.count()) ?? 0
    }

    static var versionString: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "\(version) (\(build))"
    }
}
