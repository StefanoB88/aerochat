import ActivityKit
import SwiftUI
import WidgetKit

/// Live Activity "A bordo": quanti telefoni sono a portata, messaggi non letti, Cabina del volo.
struct MeshLiveActivity: Widget {

    private let cyan = Color(red: 0, green: 0.898, blue: 1)
    private let emerald = Color(red: 0.063, green: 0.725, blue: 0.506)

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: MeshActivityAttributes.self) { context in
            lockScreen(context)
                .activityBackgroundTint(.black.opacity(0.85))
                .activitySystemActionForegroundColor(cyan)
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
                        Text(statusLine(context.state))
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

    private func lockScreen(_ context: ActivityViewContext<MeshActivityAttributes>) -> some View {
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
                    .foregroundStyle(.white)
                Text(statusLine(context.state))
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.85))
                if let flight = context.state.flightTitle {
                    Text(flight)
                        .font(.caption)
                        .foregroundStyle(cyan)
                        .lineLimit(1)
                }
                if let turn = context.state.yourTurnIn {
                    Label("Tocca a te in \(turn)", systemImage: "gamecontroller.fill")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.yellow)
                }
            }
            Spacer()
            VStack(spacing: 2) {
                Text("\(context.state.neighborCount)")
                    .font(.system(size: 28, weight: .bold, design: .rounded).monospacedDigit())
                    .foregroundStyle(emerald)
                Text("a portata")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
        .padding(16)
    }

    private func statusLine(_ state: MeshActivityAttributes.ContentState) -> String {
        if state.unreadCount > 0 {
            return String(localized: "\(state.unreadCount) messaggi da leggere")
        }
        return state.neighborCount == 0
            ? String(localized: "Nessun telefono a portata, in ascolto…")
            : String(localized: "Mesh Bluetooth attiva, niente Wi-Fi")
    }
}
