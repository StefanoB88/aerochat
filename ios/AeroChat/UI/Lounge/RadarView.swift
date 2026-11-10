import SwiftUI

/// Radar di prossimita': i vicini radio disposti su anelli in base all'RSSI (piu' vicino al centro =
/// segnale piu' forte). Serve a ritrovare un amico in aereo, al festival o in aeroporto.
struct RadarSheet: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var peers: [Data: PeerRecord] = [:]
    @State private var task: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                VStack(spacing: 16) {
                    RadarCanvas(neighbors: environment.neighbors, peers: peers)
                        .frame(maxWidth: 340, maxHeight: 340)
                        .padding(.horizontal, 24)
                    if environment.neighbors.isEmpty {
                        Text("Nessun telefono AeroChat a portata diretta. Il radar mostra chi è collegato al tuo iPhone via Bluetooth.")
                            .font(.footnote)
                            .foregroundStyle(AeroTheme.textSecondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                    } else {
                        List(environment.neighbors.sorted { ($0.rssi ?? -200) > ($1.rssi ?? -200) }) { neighbor in
                            let peer = peers[neighbor.nodeID]
                            HStack(spacing: 12) {
                                AvatarView(imageData: peer?.avatarImage, colorHex: peer?.avatarColorHex ?? Self.fallbackColor(neighbor.nodeID), size: 36)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(peer?.nickname ?? String(localized: "Passeggero"))
                                        .font(.body.weight(.medium))
                                        .foregroundStyle(AeroTheme.textPrimary)
                                    Text(Self.distanceLabel(rssi: neighbor.rssi))
                                        .font(.caption)
                                        .foregroundStyle(AeroTheme.textSecondary)
                                }
                                Spacer()
                                if let rssi = neighbor.rssi {
                                    Text("\(rssi) dBm")
                                        .font(AeroTheme.monospaced(12))
                                        .foregroundStyle(AeroTheme.accentCyan)
                                }
                            }
                            .listRowBackground(AeroTheme.surface)
                        }
                        .listStyle(.insetGrouped)
                        .scrollContentBackground(.hidden)
                    }
                }
                .padding(.top, 12)
            }
            .navigationTitle("Radar")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() }.toolbarLabel() }
            }
        }
        .onAppear {
            guard task == nil, let database = environment.database else { return }
            task = Task {
                for await list in database.peers.observeAll() {
                    let map = Dictionary(list.map { ($0.nodeID, $0) }, uniquingKeysWith: { first, _ in first })
                    if map != peers { peers = map }
                }
            }
        }
        .onDisappear { task?.cancel(); task = nil }
        .preferredColorScheme(.dark)
    }

    /// Distanza stimata dall'RSSI (Bluetooth in cabina: molto approssimativa, ma utile a orientarsi).
    static func distanceLabel(rssi: Int?) -> String {
        guard let rssi else { return String(localized: "Collegato (distanza sconosciuta)") }
        switch rssi {
        case (-60)...: return String(localized: "Vicinissimo · stessa fila")
        case (-72)..<(-60): return String(localized: "Vicino · 1-3 file")
        case (-84)..<(-72): return String(localized: "Qualche fila più in là")
        default: return String(localized: "Lontano · fondo cabina")
        }
    }

    static func fallbackColor(_ nodeID: Data) -> String {
        OnboardingView.palette[Int(nodeID.first ?? 0) % OnboardingView.palette.count]
    }
}

/// Anelli concentrici con gli avatar dei vicini; l'angolo e' derivato dal node_id cosi' resta stabile.
private struct RadarCanvas: View {

    let neighbors: [Neighbor]
    let peers: [Data: PeerRecord]

    var body: some View {
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height)
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)
            let maxRadius = side / 2 - 22
            ZStack {
                ForEach([0.33, 0.66, 1.0], id: \.self) { fraction in
                    Circle()
                        .stroke(AeroTheme.accentCyan.opacity(0.25), style: StrokeStyle(lineWidth: 1, dash: fraction == 1 ? [4, 4] : []))
                        .frame(width: maxRadius * 2 * fraction, height: maxRadius * 2 * fraction)
                        .position(center)
                }
                RadarSweep()
                    .frame(width: maxRadius * 2, height: maxRadius * 2)
                    .position(center)
                ZStack {
                    Circle().fill(AeroTheme.accentCyan).frame(width: 14, height: 14)
                    Circle().stroke(AeroTheme.accentCyan.opacity(0.4), lineWidth: 6).frame(width: 24, height: 24)
                }
                .position(center)
                ForEach(neighbors) { neighbor in
                    let fraction = Self.radiusFraction(rssi: neighbor.rssi)
                    let angle = Self.angle(for: neighbor.nodeID)
                    let peer = peers[neighbor.nodeID]
                    VStack(spacing: 2) {
                        AvatarView(imageData: peer?.avatarImage, colorHex: peer?.avatarColorHex ?? RadarSheet.fallbackColor(neighbor.nodeID), size: 34)
                            .overlay(Circle().stroke(.white.opacity(0.6), lineWidth: 1))
                        Text(peer?.nickname ?? String(localized: "Passeggero"))
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(AeroTheme.textPrimary)
                            .lineLimit(1)
                            .frame(maxWidth: 70)
                    }
                    .position(x: center.x + cos(angle) * maxRadius * fraction,
                              y: center.y + sin(angle) * maxRadius * fraction)
                    .animation(.spring(duration: 0.6), value: fraction)
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityLabel(Text("Radar: \(neighbors.count) telefoni a portata"))
    }

    /// -45 dBm al centro, -95 dBm al bordo; RSSI sconosciuto (link in ruolo peripheral) sul bordo.
    static func radiusFraction(rssi: Int?) -> CGFloat {
        guard let rssi else { return 1 }
        let clamped = min(-45, max(-95, rssi))
        return CGFloat(Double(-45 - clamped) / 50) * 0.9 + 0.1
    }

    static func angle(for nodeID: Data) -> CGFloat {
        let seed = nodeID.prefix(2).reduce(0) { $0 << 8 | Int($1) }
        return CGFloat(seed % 360) * .pi / 180
    }
}

/// Lancetta che ruota: da' il senso di "scansione in corso".
private struct RadarSweep: View {

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30)) { context in
            let seconds = context.date.timeIntervalSinceReferenceDate
            let angle = Angle.degrees((seconds.truncatingRemainder(dividingBy: 4) / 4) * 360)
            AngularGradient(colors: [.clear, AeroTheme.accentCyan.opacity(0.35)], center: .center,
                            startAngle: angle - .degrees(60), endAngle: angle)
                .clipShape(Circle())
        }
    }
}
