import SwiftUI

/// Cartolina di volo: i numeri del viaggio in formato Storie (1080x1920) da condividere.
struct FlightCardSheet: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var period: FlightStats.Period = .trip
    @State private var stats: FlightStats?
    @State private var rendered: UIImage?

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                VStack(spacing: 18) {
                    Picker("Periodo", selection: $period) {
                        ForEach(FlightStats.Period.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)
                    if let stats {
                        FlightCardContent(stats: stats, nickname: environment.nickname)
                            .frame(width: 360, height: 640)
                            .scaleEffect(0.72)
                            .frame(width: 260, height: 460)
                            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                            .shadow(color: AeroTheme.accentCyan.opacity(0.25), radius: 24)
                    } else {
                        ProgressView().tint(AeroTheme.accentCyan).frame(height: 460)
                    }
                    if let rendered {
                        ShareLink(item: Image(uiImage: rendered),
                                  preview: SharePreview(String(localized: "La mia cartolina di volo"), image: Image(uiImage: rendered))) {
                            Label("Condividi la cartolina", systemImage: "square.and.arrow.up")
                                .font(AeroTheme.title(18, weight: .bold))
                                .frame(maxWidth: .infinity, minHeight: 28)
                                .padding(.vertical, 14)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(AeroTheme.accentCyan)
                        .foregroundStyle(.black)
                        .padding(.horizontal, 24)
                    }
                    Text("Solo numeri: nessun messaggio, nome o foto di altri passeggeri finisce nella cartolina.")
                        .font(.caption)
                        .foregroundStyle(AeroTheme.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                    Spacer(minLength: 0)
                }
                .padding(.top, 8)
            }
            .navigationTitle("Cartolina di volo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() }.toolbarLabel() }
            }
        }
        .task(id: period) { await reload() }
        .preferredColorScheme(.dark)
    }

    private func reload() async {
        guard let database = environment.database else { return }
        rendered = nil
        let computed = try? await FlightStats.compute(database: database, period: period)
        stats = computed
        guard let computed else { return }
        let renderer = ImageRenderer(content: FlightCardContent(stats: computed, nickname: environment.nickname).frame(width: 360, height: 640))
        renderer.scale = 3
        rendered = renderer.uiImage
    }
}

/// Grafica della cartolina (360x640 pt, resa a 3x = 1080x1920 px).
struct FlightCardContent: View {

    let stats: FlightStats
    let nickname: String

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(hex: 0x05080F), Color(hex: 0x0B1B2B), Color(hex: 0x003845)],
                           startPoint: .top, endPoint: .bottom)
            Circle()
                .stroke(AeroTheme.accentCyan.opacity(0.12), lineWidth: 1)
                .frame(width: 520, height: 520)
                .offset(y: -140)
            Circle()
                .stroke(AeroTheme.accentCyan.opacity(0.08), lineWidth: 1)
                .frame(width: 760, height: 760)
                .offset(y: -140)
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Image(systemName: "airplane")
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(AeroTheme.accentCyan)
                    Text("AeroChat")
                        .font(.system(size: 26, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                    Spacer()
                    Text(stats.period.title.uppercased())
                        .font(.system(size: 11, weight: .bold, design: .rounded))
                        .foregroundStyle(.black)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(AeroTheme.accentCyan, in: Capsule())
                }
                Spacer().frame(height: 28)
                Text("Il volo di \(nickname)")
                    .font(.system(size: 34, weight: .heavy, design: .rounded))
                    .foregroundStyle(.white)
                if let flight = stats.flightTitles.first {
                    Text(flight)
                        .font(.system(size: 15, weight: .semibold, design: .rounded))
                        .foregroundStyle(AeroTheme.accentCyan)
                        .padding(.top, 4)
                }
                Spacer().frame(height: 30)
                heroNumber(value: stats.totalMessages, label: String(localized: "messaggi senza internet"))
                Spacer().frame(height: 26)
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 16) {
                    tile(icon: "person.2.fill", value: stats.peopleMet, label: String(localized: "passeggeri incontrati"))
                    tile(icon: "gamecontroller.fill", value: stats.gamesWon, label: String(localized: "partite vinte su \(stats.gamesPlayed)"))
                    tile(icon: "photo.fill", value: stats.photos, label: String(localized: "foto via Bluetooth"))
                    tile(icon: "waveform", value: stats.voiceNotes, label: String(localized: "note vocali"))
                    tile(icon: "dot.radiowaves.left.and.right", value: stats.longestHop, label: String(localized: "telefoni in catena (record)"))
                    tile(icon: "bubble.left.and.text.bubble.right.fill", value: stats.loungeMessages, label: String(localized: "voci in Cabina"))
                }
                Spacer()
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Chat a 10.000 metri.")
                            .font(.system(size: 20, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)
                        Text("Senza Wi-Fi, senza account, solo Bluetooth.")
                            .font(.system(size: 13, weight: .medium, design: .rounded))
                            .foregroundStyle(.white.opacity(0.7))
                    }
                    Spacer()
                    Text(verbatim: "aeromesh.app")
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .foregroundStyle(AeroTheme.accentCyan)
                }
            }
            .padding(28)
        }
    }

    private func heroNumber(value: Int, label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(value)")
                .font(.system(size: 92, weight: .black, design: .rounded))
                .foregroundStyle(AeroTheme.accentCyan)
                .minimumScaleFactor(0.5)
                .lineLimit(1)
            Text(label)
                .font(.system(size: 17, weight: .semibold, design: .rounded))
                .foregroundStyle(.white.opacity(0.85))
        }
    }

    private func tile(icon: String, value: Int, label: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(AeroTheme.accentEmerald)
            Text("\(value)")
                .font(.system(size: 30, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
            Text(label)
                .font(.system(size: 12, weight: .medium, design: .rounded))
                .foregroundStyle(.white.opacity(0.7))
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
