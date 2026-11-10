import ActivityKit
import SwiftUI
import WidgetKit

/// Live Activity "A bordo": quanti telefoni sono a portata, messaggi non letti, Cabina del volo.
struct MeshLiveActivity: Widget {

    private let cyan = Color(red: 0, green: 0.898, blue: 1)
    private let emerald = Color(red: 0.063, green: 0.725, blue: 0.506)

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: MeshActivityAttributes.self) { context in
            // Schermata di blocco: sfondo di sistema, chiaro o scuro secondo l'aspetto.
            MeshLockScreenView(state: context.state)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 6) {
                        Image(systemName: "airplane").foregroundStyle(cyan)
                        Text("AeroChat").font(.headline)
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Label("\(context.state.neighborCount)", systemImage: "dot.radiowaves.left.and.right")
                        .font(.headline.monospacedDigit())
                        .foregroundStyle(emerald)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(Self.statusLine(context.state))
                            .font(.subheadline)
                        if let flight = context.state.flightTitle {
                            Text(flight).font(.caption).foregroundStyle(cyan)
                        }
                        if let turn = context.state.yourTurnIn {
                            Label("Tocca a te in \(turn)", systemImage: "gamecontroller.fill")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.yellow)
                        }
                    }
                }
            } compactLeading: {
                Image(systemName: "airplane").foregroundStyle(cyan)
            } compactTrailing: {
                if context.state.unreadCount > 0 {
                    Text("\(context.state.unreadCount)")
                        .font(.caption.weight(.bold).monospacedDigit())
                        .foregroundStyle(.black)
                        .padding(.horizontal, 6)
                        .background(cyan, in: Capsule())
                } else {
                    Text("\(context.state.neighborCount)")
                        .font(.caption.weight(.semibold).monospacedDigit())
                        .foregroundStyle(emerald)
                }
            } minimal: {
                Image(systemName: context.state.unreadCount > 0 ? "bubble.left.fill" : "airplane")
                    .foregroundStyle(cyan)
            }
            .keylineTint(cyan)
        }
    }

    fileprivate static func statusLine(_ state: MeshActivityAttributes.ContentState) -> String {
        if state.unreadCount > 0 {
            return String(localized: "\(state.unreadCount) messaggi da leggere")
        }
        return state.neighborCount == 0
            ? String(localized: "Nessun telefono a portata, in ascolto…")
            : String(localized: "Mesh Bluetooth attiva, niente Wi-Fi")
    }
}

/// Vista della Live Activity in schermata di blocco: colori adattivi (ciano e smeraldo piu' scuri in chiaro).
private struct MeshLockScreenView: View {

    let state: MeshActivityAttributes.ContentState
    @Environment(\.colorScheme) private var colorScheme

    private var isDark: Bool { colorScheme == .dark }
    private var cyan: Color { isDark ? Color(red: 0, green: 0.898, blue: 1) : Color(red: 0, green: 0.569, blue: 0.659) }
    private var emerald: Color { isDark ? Color(red: 0.063, green: 0.725, blue: 0.506) : Color(red: 0.020, green: 0.588, blue: 0.412) }

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(cyan.opacity(0.18)).frame(width: 48, height: 48)
                Image(systemName: "airplane")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(cyan)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text("AeroChat · a bordo")
                    .font(.headline)
                    .foregroundStyle(.primary)
                Text(MeshLiveActivity.statusLine(state))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let flight = state.flightTitle {
                    Text(flight)
                        .font(.caption)
                        .foregroundStyle(cyan)
                        .lineLimit(1)
                }
                if let turn = state.yourTurnIn {
                    Label("Tocca a te in \(turn)", systemImage: "gamecontroller.fill")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(isDark ? .yellow : .orange)
                }
            }
            Spacer()
            VStack(spacing: 2) {
                Text("\(state.neighborCount)")
                    .font(.system(size: 28, weight: .bold, design: .rounded).monospacedDigit())
                    .foregroundStyle(emerald)
                Text("a portata")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .activitySystemActionForegroundColor(cyan)
    }
}
