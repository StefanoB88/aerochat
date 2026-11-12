import SwiftUI
import CoreBluetooth
import UserNotifications

/// "Perche' non vedo nessuno?" (RELEASE_1_4_SPEC.md §B6): controlli con esito ✓/✗ e un'azione (Bluetooth,
/// permesso, notifiche, visibilita'), consigli (app aperta sull'altro telefono, distanza, versione),
/// "Riavvia la radio" e "Prova con il passeggero virtuale". Raggiungibile da Persone vicine vuota, radar vuoto
/// e dalla card principale "Nessuno vicino per ora".
struct NobodyNearbySheet: View {

    private enum CheckResult {
        case ok
        case failed
        case warning
    }

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(AppSettings.Key.nearbyVisible) private var isVisible = true
    @State private var notificationStatus: UNAuthorizationStatus?
    @State private var isRestarting = false

    private var bluetoothOn: Bool { environment.radioState == .poweredOn }
    private var hasPermission: Bool {
        CBManager.authorization == .allowedAlways
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Controlliamo insieme cosa serve per trovare chi ha AeroChat vicino a te.")
                        .font(.footnote)
                        .foregroundStyle(AeroTheme.textSecondary)
                        .listRowBackground(Color.clear)
                }
                Section {
                    checkRow(bluetoothOn ? .ok : .failed, title: "Bluetooth acceso",
                             detail: bluetoothOn
                                ? String(localized: "La radio è accesa e cerca chi è vicino.")
                                : String(localized: "Il Bluetooth è spento. In modalità aereo puoi riaccenderlo dal Centro di Controllo: AeroChat funziona anche in volo, senza rete."),
                             action: nil, perform: {})
                    checkRow(hasPermission ? .ok : .failed, title: "Permesso Bluetooth",
                             detail: hasPermission
                                ? String(localized: "Permesso concesso.")
                                : String(localized: "Senza il permesso Bluetooth AeroChat non può vedere nessuno."),
                             action: hasPermission ? nil : String(localized: "Concedi il permesso")) {
                        PermissionPrompt.openSettings(for: .bluetooth)
                    }
                    checkRow(notificationStatus == .denied ? .warning : .ok, title: "Notifiche",
                             detail: notificationStatus == .denied
                                ? String(localized: "Notifiche disattivate: non saprai quando qualcuno ti saluta o ti scrive.")
                                : String(localized: "Ti avvisiamo quando qualcuno ti saluta o ti scrive."),
                             action: notificationStatus == .denied ? String(localized: "Attiva le notifiche") : nil) {
                        PermissionPrompt.openSettings(for: .notifications)
                    }
                    checkRow(isVisible ? .ok : .failed, title: "Visibile alle persone vicine",
                             detail: isVisible
                                ? String(localized: "Chi ha AeroChat aperto vicino a te vede il tuo nickname e può salutarti.")
                                : String(localized: "Sei invisibile: non comparirai agli altri, ma continui a vederli."),
                             action: isVisible ? nil : String(localized: "Diventa visibile")) {
                        isVisible = true
                        environment.nearbySettingsChanged()
                    }
                }
                .listRowBackground(AeroTheme.surface)

                Section("Consigli") {
                    tip("L'altra persona deve avere AeroChat aperta (almeno una volta dopo l'accensione del telefono).")
                    tip("Restate a meno di 30 metri, con pochi sedili in mezzo: il Bluetooth non attraversa bene corpi e bagagli.")
                    tip("Aggiornate entrambi AeroChat all'ultima versione prima di partire.")
                }
                .listRowBackground(AeroTheme.surface)

                Section {
                    Button {
                        guard !isRestarting else { return }
                        Haptics.tap()
                        isRestarting = true
                        Task {
                            await environment.restartRadio()
                            isRestarting = false
                            Haptics.success()
                        }
                    } label: {
                        HStack {
                            if isRestarting {
                                ProgressView().tint(.black)
                            } else {
                                Image(systemName: "arrow.clockwise")
                            }
                            Text("Riavvia la radio").font(.body.weight(.bold))
                        }
                        .frame(maxWidth: .infinity, minHeight: 28)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AeroTheme.accentCyan)
                    .foregroundStyle(.black)
                    .disabled(isRestarting)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())

                    Button {
                        Haptics.tap()
                        Task {
                            if await environment.startDemo() != nil { dismiss() }
                        }
                    } label: {
                        Label("Prova con il passeggero virtuale", systemImage: "sparkles")
                            .frame(maxWidth: .infinity, minHeight: 28)
                    }
                    .buttonStyle(.bordered)
                    .tint(AeroTheme.accentEmerald)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 0, trailing: 0))
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(AeroTheme.background)
            .navigationTitle("Perché non vedo nessuno?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() }.toolbarLabel() }
            }
        }
        .task { await refreshNotifications() }
        // Di ritorno dalle Impostazioni di iOS i controlli si aggiornano da soli.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await refreshNotifications() } }
        }
    }

    private func refreshNotifications() async {
        notificationStatus = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    private func checkRow(_ result: CheckResult, title: LocalizedStringKey, detail: String, action: String?,
                          perform: @escaping () -> Void) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon(result))
                .font(.title3)
                .foregroundStyle(color(result))
                .accessibilityLabel(label(result))
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AeroTheme.textPrimary)
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(AeroTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let action {
                    Button(action) {
                        Haptics.tap()
                        perform()
                    }
                    .font(.footnote.weight(.semibold))
                    .buttonStyle(.bordered)
                    .tint(AeroTheme.accentCyan)
                    .padding(.top, 2)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func tip(_ text: LocalizedStringKey) -> some View {
        Label {
            Text(text)
                .font(.footnote)
                .foregroundStyle(AeroTheme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: "lightbulb").foregroundStyle(AeroTheme.accentCyan)
        }
    }

    private func icon(_ result: CheckResult) -> String {
        switch result {
        case .ok: return "checkmark.circle.fill"
        case .failed: return "xmark.circle.fill"
        case .warning: return "exclamationmark.triangle.fill"
        }
    }

    private func color(_ result: CheckResult) -> Color {
        switch result {
        case .ok: return AeroTheme.accentEmerald
        case .failed: return .red
        case .warning: return .orange
        }
    }

    private func label(_ result: CheckResult) -> Text {
        switch result {
        case .ok: return Text("A posto")
        case .failed: return Text("Da sistemare")
        case .warning: return Text("Attenzione")
        }
    }
}
