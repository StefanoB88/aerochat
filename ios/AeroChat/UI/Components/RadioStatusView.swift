import SwiftUI

/// Indicatore semantico "Stato Radio" nell'header della chat.
struct RadioStatusView: View {

    let reachability: Reachability
    var compact = false

    private var color: Color {
        switch reachability {
        case .direct: return AeroTheme.accentEmerald
        case .viaBridge: return .yellow
        case .outOfRange: return .gray
        }
    }

    private var label: String {
        switch reachability {
        case .direct:
            return String(localized: "Connessione diretta")
        case .viaBridge(let hops):
            return String(localized: "Tramite ponte (\(hops) salti)")
        case .outOfRange:
            return compact ? String(localized: "Fuori portata") : String(localized: "Fuori portata — invio in coda")
        }
    }

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
                .shadow(color: color.opacity(0.8), radius: 4)
            Text(label)
                .font(.caption)
                .foregroundStyle(AeroTheme.textSecondary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text("Stato radio: \(label)"))
    }
}
