import SwiftUI

/// Scelta dei 3 compagni di tavolo per lo Scopone: l'ordine di selezione e' l'ordine dei posti 1..3,
/// quindi il secondo scelto (posto 2) e' il compagno di squadra.
struct ScoponePlayerPicker: View {

    let members: [GroupMemberRecord]
    let onInvite: ([Data]) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selected: [Data] = []

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(members, id: \.nodeID) { member in
                        Button {
                            toggle(member.nodeID)
                        } label: {
                            HStack {
                                Text(member.nickname)
                                    .foregroundStyle(AeroTheme.textPrimary)
                                Spacer()
                                if let index = selected.firstIndex(of: member.nodeID) {
                                    Text(role(for: index))
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(index == 1 ? AeroTheme.accentEmerald : AeroTheme.accentCyan)
                                    Image(systemName: "\(index + 1).circle.fill")
                                        .foregroundStyle(index == 1 ? AeroTheme.accentEmerald : AeroTheme.accentCyan)
                                } else {
                                    Image(systemName: "circle")
                                        .foregroundStyle(AeroTheme.textSecondary)
                                }
                            }
                        }
                        .disabled(selected.count >= 3 && !selected.contains(member.nodeID))
                        .accessibilityAddTraits(selected.contains(member.nodeID) ? .isSelected : [])
                    }
                } header: {
                    Text("Scegli 3 giocatori")
                } footer: {
                    Text("Il secondo giocatore scelto sarà il tuo compagno di squadra.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(AeroTheme.background)
            .navigationTitle("Scopone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annulla") { dismiss() }.toolbarLabel() }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Invita") {
                        Haptics.tap()
                        onInvite(selected)
                        dismiss()
                    }
                    .disabled(selected.count != 3)
                    .toolbarLabel()
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private func role(for index: Int) -> String {
        index == 1 ? String(localized: "Compagno") : String(localized: "Avversario")
    }

    private func toggle(_ nodeID: Data) {
        Haptics.tap()
        if let index = selected.firstIndex(of: nodeID) {
            selected.remove(at: index)
        } else if selected.count < 3 {
            selected.append(nodeID)
        }
    }
}
