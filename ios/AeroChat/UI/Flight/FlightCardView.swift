import SwiftUI

/// Cartolina di volo: i numeri del viaggio in formato Storie (1080x1920) da condividere.
struct FlightCardSheet: View {

    /// Volo finito (notifica del ricordo del viaggio): cartolina gia' compilata, niente scelta del periodo.
    var trip: FlightTrip?

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var period: FlightStats.Period = .trip
    @State private var stats: FlightStats?
    @State private var rendered: UIImage?
    @State private var showPassport = false

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                ScrollView {
                VStack(spacing: 18) {
                    if trip == nil {
                        Picker("Periodo", selection: $period) {
                            ForEach(FlightStats.Period.allCases) { Text($0.title).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        .padding(.horizontal)
                    }
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
                    if let stats, stats.leaderboard.contains(where: { $0.played > 0 }) {
                        leaderboardView(stats.leaderboard)
                    }
                    Button {
                        Haptics.tap()
                        showPassport = true
                    } label: {
                        Label("Passaporto di viaggio", systemImage: "book.closed.fill")
                            .font(.body.weight(.semibold))
                    }
                    .buttonStyle(.bordered)
                    .tint(AeroTheme.accentEmerald)
                    Spacer(minLength: 0)
                }
                .padding(.top, 8)
                }
            }
            .navigationTitle("Cartolina di volo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() }.toolbarLabel() }
            }
        }
        .task(id: period) { await reload() }
        .sheet(isPresented: $showPassport) { PassportSheet() }
        // Cartolina di fine volo mostrata: momento felice per chiedere la recensione.
        .onAppear { if trip != nil { environment.reviewMoment(.tripCardShown) } }
    }

    /// Classifica del volo: solo nell'app, non nella cartolina condivisa (che resta senza nomi altrui).
    private func leaderboardView(_ entries: [FlightLeaderboard.Entry]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Classifica del volo", systemImage: "trophy.fill")
                .font(AeroTheme.title(17, weight: .semibold))
                .foregroundStyle(AeroTheme.textPrimary)
            ForEach(Array(entries.prefix(5).enumerated()), id: \.element.id) { index, entry in
                HStack {
                    Text("\(index + 1).")
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(AeroTheme.textSecondary)
                    Text(entry.isMe ? String(localized: "Tu") : entry.name)
                        .font(.subheadline.weight(entry.isMe ? .bold : .regular))
                        .foregroundStyle(AeroTheme.textPrimary)
                    Spacer()
                    Text("\(entry.wins) vinte su \(entry.played)")
                        .font(.subheadline)
                        .foregroundStyle(AeroTheme.textSecondary)
                }
            }
        }
        .padding(16)
        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 24)
    }

    private func reload() async {
        guard let database = environment.database else { return }
        rendered = nil
        var computed: FlightStats?
        if let trip {
            computed = FlightStats(trip: trip)
            #if DEBUG
            if StoreScreenshots.isEnabled, let override = StoreScreenshots.flightStats { computed = override }
            #endif
        } else {
            computed = try? await FlightStats.compute(database: database, period: period)
            // Volo in corso: classifica delle partite giocate da quando siamo nella Cabina del volo.
            if let current = environment.currentTrip, let live = await environment.trip(flightCode: current.flightCode) {
                computed?.leaderboard = live.leaderboard
            }
        }
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
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Image(systemName: "airplane")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(AeroTheme.accentCyan)
                Text("AeroChat")
                    .font(.system(size: 26, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                Spacer()
                Text((stats.headline ?? stats.period.title).uppercased())
                    .font(.system(size: 11, weight: .bold, design: .rounded))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(AeroTheme.accentCyan, in: Capsule())
            }
            Spacer().frame(height: 18)
            Text("Il volo di \(nickname)")
                .font(.system(size: 34, weight: .heavy, design: .rounded))
                .foregroundStyle(.white)
            if let flight = stats.flightTitles.first {
                Text(flight)
                    .font(.system(size: 15, weight: .semibold, design: .rounded))
                    .foregroundStyle(AeroTheme.accentCyan)
                    .padding(.top, 4)
            }
            Spacer().frame(height: 10)
            heroNumber(value: stats.totalMessages, label: String(localized: "messaggi senza internet"))
            Spacer().frame(height: 10)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                tile(icon: "person.2.fill", value: stats.peopleMet, label: String(localized: "passeggeri incontrati"))
                tile(icon: "gamecontroller.fill", value: stats.gamesWon, label: String(localized: "partite vinte su \(stats.gamesPlayed)"))
                tile(icon: "photo.fill", value: stats.photos, label: String(localized: "foto via Bluetooth"))
                tile(icon: "waveform", value: stats.voiceNotes, label: String(localized: "note vocali"))
                tile(icon: "dot.radiowaves.left.and.right", value: stats.longestHop, label: String(localized: "telefoni in catena (record)"))
                if let rank = stats.myRank {
                    tile(icon: "trophy.fill", value: rank, label: String(localized: "posto nella classifica del volo"))
                } else {
                    tile(icon: "bubble.left.and.text.bubble.right.fill", value: stats.loungeMessages, label: String(localized: "voci in Cabina"))
                }
            }
            Spacer(minLength: 8)
            Text("Chat a 10.000 metri. Senza Wi-Fi, senza account, solo Bluetooth.")
                .font(.system(size: 13, weight: .medium, design: .rounded))
                .foregroundStyle(.white.opacity(0.7))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Spacer().frame(height: 10)
            // QR verso la pagina di download: chi vede la Storia puo' scaricare l'app.
            DownloadQRFooter()
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        // Sfondo e cerchi decorativi dietro al contenuto: i cerchi (piu' grandi della cartolina) non
        // devono allargare il layout, altrimenti il testo usciva dai bordi della cartolina.
        .background {
            LinearGradient(colors: [Color(hex: 0x05080F), Color(hex: 0x0B1B2B), Color(hex: 0x003845)],
                           startPoint: .top, endPoint: .bottom)
                .overlay {
                    Circle()
                        .stroke(AeroTheme.accentCyan.opacity(0.12), lineWidth: 1)
                        .frame(width: 520, height: 520)
                        .offset(y: -140)
                }
                .overlay {
                    Circle()
                        .stroke(AeroTheme.accentCyan.opacity(0.08), lineWidth: 1)
                        .frame(width: 760, height: 760)
                        .offset(y: -140)
                }
        }
        .clipped()
        // Cartolina con grafica propria scura: stessa resa in app e nell'immagine condivisa, qualunque sia l'aspetto.
        .environment(\.colorScheme, .dark)
    }

    private func heroNumber(value: Int, label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(value)")
                .font(.system(size: 64, weight: .black, design: .rounded))
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
                .font(.system(size: 24, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
            Text(label)
                .font(.system(size: 11, weight: .medium, design: .rounded))
                .foregroundStyle(.white.opacity(0.7))
                .lineLimit(2)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
