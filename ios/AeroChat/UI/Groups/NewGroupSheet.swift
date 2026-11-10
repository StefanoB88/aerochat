import SwiftUI

/// Crea un gruppo di viaggio scegliendo tra i compagni fidati gia' accoppiati.
struct NewGroupSheet: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var peers: [PeerRecord] = []
    @State private var selected: Set<Data> = []
    @State private var isCreating = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                List {
                    Section {
                        TextField("Nome del gruppo (es. Volo FR1234)", text: $title)
                            .foregroundStyle(AeroTheme.textPrimary)
                    }
                    .listRowBackground(AeroTheme.surface)

                    Section("Compagni di volo") {
                        if peers.isEmpty {
                            Text("Nessun compagno accoppiato: scansiona prima il loro QR.")
                                .foregroundStyle(AeroTheme.textSecondary)
                        }
                        ForEach(peers, id: \.nodeID) { peer in
                            Button {
                                if selected.contains(peer.nodeID) { selected.remove(peer.nodeID) } else { selected.insert(peer.nodeID) }
                            } label: {
                                HStack {
                                    Text(peer.nickname).foregroundStyle(AeroTheme.textPrimary)
                                    Spacer()
                                    Image(systemName: selected.contains(peer.nodeID) ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(AeroTheme.accentCyan)
                                }
                            }
                        }
                    }
                    .listRowBackground(AeroTheme.surface)

                    if !environment.isPro {
                        Section {
                            Text("Le chat di gruppo da 3+ persone si attivano con il Flight Group Pass di un solo membro. Potrai comunque creare il gruppo e mostrare il QR.")
                                .font(.footnote)
                                .foregroundStyle(AeroTheme.textSecondary)
                        }
                        .listRowBackground(AeroTheme.surface)
                    }
                    if let errorMessage {
                        Text(errorMessage).foregroundStyle(.red)
                    }
                }
                .scrollContentBackground(.hidden)
                .scrollDismissesKeyboard(.interactively)
            }
            .navigationTitle("Nuovo gruppo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annulla") { dismiss() }.toolbarLabel() }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Crea") { Task { await create() } }
                        .toolbarLabel()
                        .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || isCreating)
                }
            }
        }
        .task {
            peers = (try? await environment.database?.peers.trustedPeers()) ?? []
        }
    }

    private func create() async {
        isCreating = true
        defer { isCreating = false }
        do {
            let members = peers.filter { selected.contains($0.nodeID) }
            _ = try await environment.createGroup(title: title.trimmingCharacters(in: .whitespaces), members: members)
            Haptics.success()
            dismiss()
        } catch {
            errorMessage = String(localized: "Impossibile creare il gruppo: \(error.localizedDescription)")
        }
    }
}
