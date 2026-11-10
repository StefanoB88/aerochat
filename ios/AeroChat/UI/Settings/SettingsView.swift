import SwiftUI

/// Impostazioni: profilo, pass, sicurezza (filtro, foto sfocate, bloccati, segnalazioni), legale e info.
struct SettingsView: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss

    @AppStorage(AppSettings.Key.languageFilter) private var languageFilter = true
    @AppStorage(AppSettings.Key.blurStrangerMedia) private var blurStrangerMedia = true
    @AppStorage(AppSettings.Key.demoMode) private var demoMode = false

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
                travelSection
                safetySection
                legalSection
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
        }
        .preferredColorScheme(.dark)
    }

    private var profileSection: some View {
        Section {
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

    private var travelSection: some View {
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
        } header: {
            Text("Viaggio ed eventi")
        } footer: {
            Text("AeroChat funziona ovunque manchi la rete: in volo, in crociera, a un festival, in montagna o durante un blackout. L'avviso di emergenza raggiunge tutti i telefoni vicini.")
        }
        .foregroundStyle(AeroTheme.textPrimary)
        .listRowBackground(AeroTheme.surface)
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

    private var safetySection: some View {
        Section {
            Toggle(isOn: $languageFilter) {
                Label("Filtro linguaggio in Cabina", systemImage: "textformat.abc.dottedunderline")
            }
            Toggle(isOn: $blurStrangerMedia) {
                Label("Sfoca le foto da non verificati", systemImage: "eye.slash")
            }
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
                ReportsListView()
            } label: {
                HStack {
                    Label("Segnalazioni", systemImage: "exclamationmark.bubble")
                    Spacer()
                    Text("\(reportCount)").foregroundStyle(AeroTheme.textSecondary)
                }
            }
        } header: {
            Text("Sicurezza")
        } footer: {
            Text("Le foto dei contatti che non hai mai scansionato di persona (membri dei gruppi arrivati via gossip) restano sfocate finché non le tocchi.")
        }
        .tint(AeroTheme.accentCyan)
        .foregroundStyle(AeroTheme.textPrimary)
        .listRowBackground(AeroTheme.surface)
    }

    private var legalSection: some View {
        Section("Legale") {
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
                    Label("Compagno di volo simulato (demo)", systemImage: "person.crop.circle.badge.questionmark")
                }
                .tint(AeroTheme.accentCyan)
            }
            Link(destination: URL(string: "mailto:\(AppLinks.supportEmail)")!) {
                Label("Contatta il supporto", systemImage: "envelope")
            }
        } header: {
            Text("Informazioni")
        } footer: {
            if showDemoToggle || demoMode {
                Text("Il compagno simulato risponde ai tuoi messaggi sullo stesso iPhone: serve a provare l'app (o a revisionarla) senza un secondo telefono. Non usa la radio.")
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
