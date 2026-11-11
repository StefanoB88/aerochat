import SwiftUI

/// Indicatore semantico "Stato Radio" nell'header della chat.
struct RadioStatusView: View {

    let reachability: Reachability
    var compact = false
    /// Gruppi e Cabina: quante persone della conversazione sono a portata diretta (testo "3 persone vicine").
    var nearbyCount: Int?

    private var color: Color {
        switch reachability {
        case .direct: return AeroTheme.accentEmerald
        case .viaBridge: return .yellow
        case .outOfRange: return .gray
        }
    }

    /// Testo centrato sulle persone (la logica dello stato radio non cambia, solo il modo di dirlo).
    private var label: String {
        Self.text(for: reachability, compact: compact, nearbyCount: nearbyCount)
    }

    static func text(for reachability: Reachability, compact: Bool, nearbyCount: Int? = nil) -> String {
        switch reachability {
        case .direct:
            if let nearbyCount, nearbyCount > 0 {
                return String(localized: "\(nearbyCount) persone vicine")
            }
            return String(localized: "Vicino a te")
        case .viaBridge(let hops):
            // hop 2 = un telefono in mezzo: si contano le persone che fanno da ponte.
            let relays = max(1, hops - 1)
            return compact
                ? String(localized: "Tramite \(relays) persone")
                : String(localized: "Raggiungibile tramite \(relays) persone")
        case .outOfRange:
            return compact ? String(localized: "Fuori portata") : String(localized: "Fuori portata — il messaggio aspetterà")
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
                .minimumScaleFactor(0.8)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(label))
    }
}
