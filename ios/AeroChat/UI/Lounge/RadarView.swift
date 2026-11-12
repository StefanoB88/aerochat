import SwiftUI

/// Radar di prossimita': i vicini radio disposti su anelli in base alla distanza. Tra iPhone con chip UWB
/// (entrambi col radar aperto) distanza e direzione sono misurate; altrimenti la distanza e' stimata
/// dall'RSSI Bluetooth filtrato. Serve a ritrovare un amico in aereo, al festival o in aeroporto.
struct RadarSheet: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var peers: [Data: PeerRecord] = [:]
    @State private var task: Task<Void, Never>?
    @State private var showDiagnosis = false

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                VStack(spacing: 16) {
                    RadarCanvas(neighbors: environment.neighbors, peers: peers, uwb: environment.uwb)
                        .frame(maxWidth: 340, maxHeight: 340)
                        .padding(.horizontal, 24)
                    if environment.neighbors.isEmpty {
                        Text("Nessun telefono AeroChat a portata diretta. Il radar mostra chi è collegato al tuo iPhone via Bluetooth.")
                            .font(.footnote)
                            .foregroundStyle(AeroTheme.textSecondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                        Button {
                            Haptics.tap()
                            showDiagnosis = true
                        } label: {
                            Label("Perché non vedo nessuno?", systemImage: "questionmark.circle")
                                .font(.footnote.weight(.semibold))
                        }
                        .buttonStyle(.bordered)
                        .tint(AeroTheme.accentEmerald)
                        .sheet(isPresented: $showDiagnosis) { NobodyNearbySheet() }
                    } else {
                        List(sortedNeighbors) { neighbor in
                            let peer = peers[neighbor.nodeID]
                            let uwb = environment.uwb.measurement(for: neighbor.nodeID)
                            HStack(spacing: 12) {
                                AvatarView(imageData: peer?.avatarImage, colorHex: peer?.avatarColorHex ?? Self.fallbackColor(neighbor.nodeID), size: 36)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(peer?.nickname ?? String(localized: "Passeggero"))
                                        .font(.body.weight(.medium))
                                        .foregroundStyle(AeroTheme.textPrimary)
                                    Text(Self.distanceLabel(rssi: neighbor.rssi, uwb: uwb))
                                        .font(.caption)
                                        .foregroundStyle(AeroTheme.textSecondary)
                                }
                                Spacer()
                                if uwb != nil {
                                    Text(verbatim: "UWB")
                                        .font(AeroTheme.monospaced(11).weight(.semibold))
                                        .foregroundStyle(AeroTheme.background)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(AeroTheme.accentCyan, in: Capsule())
                                        .accessibilityLabel(Text("Distanza misurata con precisione"))
                                } else if let rssi = neighbor.rssi {
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
                    if let hint = precisionHint {
                        Text(hint)
                            .font(.caption)
                            .foregroundStyle(AeroTheme.textSecondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                            .padding(.bottom, 8)
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
        .onChange(of: environment.uwb.isDenied) { _, denied in
            if denied { PermissionPrompt.showDenied(.nearbyInteraction) }
        }
        .onAppear {
            if environment.radioState == .unauthorized { PermissionPrompt.showDenied(.bluetooth) }
            environment.setRadarVisible(true)
            guard task == nil, let database = environment.database else { return }
            task = Task {
                for await list in database.peers.observeAll() {
                    let map = Dictionary(list.map { ($0.nodeID, $0) }, uniquingKeysWith: { first, _ in first })
                    if map != peers { peers = map }
                }
            }
        }
        .onDisappear {
            task?.cancel(); task = nil
            environment.setRadarVisible(false)
        }
    }

    /// Piu' vicini in cima: distanza UWB se misurata, altrimenti stimata dall'RSSI.
    private var sortedNeighbors: [Neighbor] {
        environment.neighbors.sorted {
            let first = ProximityEstimator.meters(rssi: $0.rssi, uwb: environment.uwb.measurement(for: $0.nodeID)) ?? .infinity
            let second = ProximityEstimator.meters(rssi: $1.rssi, uwb: environment.uwb.measurement(for: $1.nodeID)) ?? .infinity
            return first < second
        }
    }

    /// Spiega quando la distanza diventa precisa (solo su iPhone con UWB) o perche' non lo e'.
    private var precisionHint: String? {
        guard UWBRanger.isSupported, !environment.neighbors.isEmpty else { return nil }
        if environment.uwb.isDenied {
            return String(localized: "Per la distanza precisa consenti \"Interazioni nelle vicinanze\" ad AeroChat nelle Impostazioni.")
        }
        guard environment.uwb.measurements.isEmpty else { return nil }
        return String(localized: "Con gli iPhone che hanno il radar aperto, distanza e direzione sono misurate al centimetro. Per gli altri telefoni è una stima dal Bluetooth.")
    }

    /// Fascia di distanza + metri: misurati (UWB) o stimati dall'RSSI filtrato (Bluetooth: indicativi).
    static func distanceLabel(rssi: Int?, uwb: UWBMeasurement?) -> String {
        guard let meters = ProximityEstimator.meters(rssi: rssi, uwb: uwb) else {
            return String(localized: "Collegato (distanza sconosciuta)")
        }
        let bucket: String
        switch meters {
        case ..<1.2: bucket = String(localized: "Vicinissimo · stessa fila")
        case ..<3.5: bucket = String(localized: "Vicino · 1-3 file")
        case ..<10: bucket = String(localized: "Qualche fila più in là")
        default: bucket = String(localized: "Lontano · fondo cabina")
        }
        return "\(bucket) · \(ProximityEstimator.formatted(meters: meters, precise: uwb != nil))"
    }

    static func fallbackColor(_ nodeID: Data) -> String {
        OnboardingView.palette[Int(nodeID.first ?? 0) % OnboardingView.palette.count]
    }
}

/// Anelli concentrici con gli avatar dei vicini; l'angolo e' derivato dal node_id cosi' resta stabile.
private struct RadarCanvas: View {

    let neighbors: [Neighbor]
    let peers: [Data: PeerRecord]
    let uwb: UWBRanger

    var body: some View {
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height)
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)
            let maxRadius = side / 2 - 22
            ZStack {
                ForEach(ProximityEstimator.ringMeters, id: \.self) { meters in
                    let fraction = ProximityEstimator.radiusFraction(meters: meters)
                    Circle()
                        .stroke(AeroTheme.accentCyan.opacity(0.25), style: StrokeStyle(lineWidth: 1, dash: fraction >= 1 ? [4, 4] : []))
                        .frame(width: maxRadius * 2 * fraction, height: maxRadius * 2 * fraction)
                        .position(center)
                    Text(Measurement(value: meters, unit: UnitLength.meters)
                        .formatted(.measurement(width: .abbreviated, usage: .asProvided)))
                        .font(.system(size: 9, weight: .medium))
                        .foregroundStyle(AeroTheme.accentCyan.opacity(0.6))
                        .position(x: center.x + 4 + 12, y: center.y - maxRadius * fraction + 7)
                        .accessibilityHidden(true)
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
                    let measurement = uwb.measurement(for: neighbor.nodeID)
                    let fraction = ProximityEstimator.radiusFraction(meters: ProximityEstimator.meters(rssi: neighbor.rssi, uwb: measurement))
                    // Direzione UWB (0 = davanti all'iPhone = in alto sullo schermo) se c'e', altrimenti un angolo stabile.
                    let angle = measurement?.azimuth.map { CGFloat($0) - .pi / 2 } ?? Self.angle(for: neighbor.nodeID)
                    let peer = peers[neighbor.nodeID]
                    VStack(spacing: 2) {
                        AvatarView(imageData: peer?.avatarImage, colorHex: peer?.avatarColorHex ?? RadarSheet.fallbackColor(neighbor.nodeID), size: 34)
                            .overlay(Circle().stroke(AeroTheme.textPrimary.opacity(0.6), lineWidth: 1))
                        Text(peer?.nickname ?? String(localized: "Passeggero"))
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(AeroTheme.textPrimary)
                            .lineLimit(1)
                            .frame(maxWidth: 70)
                    }
                    .position(x: center.x + cos(angle) * maxRadius * fraction,
                              y: center.y + sin(angle) * maxRadius * fraction)
                    .animation(.spring(duration: 0.6), value: fraction)
                    .animation(.spring(duration: 0.6), value: angle)
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityLabel(Text("Radar: \(neighbors.count) telefoni a portata"))
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
