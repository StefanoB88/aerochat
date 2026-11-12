import SwiftUI

/// Passaporto di viaggio (RELEASE_1_4_SPEC.md §B9): numeri di tutti i voli e badge sbloccati, condivisibile
/// come immagine Storie (1080x1920).
struct PassportSheet: View {

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var rendered: UIImage?

    var body: some View {
        NavigationStack {
            ZStack {
                AeroTheme.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 18) {
                        numbers
                        badges
                        if let rendered {
                            ShareLink(item: Image(uiImage: rendered),
                                      preview: SharePreview(String(localized: "Passaporto di viaggio"), image: Image(uiImage: rendered))) {
                                Label("Condividi il passaporto", systemImage: "square.and.arrow.up")
                                    .font(AeroTheme.title(18, weight: .bold))
                                    .frame(maxWidth: .infinity, minHeight: 28)
                                    .padding(.vertical, 14)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(AeroTheme.accentCyan)
                            .foregroundStyle(.black)
                        }
                        Text("Numeri di tutti i tuoi voli con AeroChat, aggiornati alla fine di ogni volo. Restano solo su questo telefono.")
                            .font(.caption)
                            .foregroundStyle(AeroTheme.textSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Passaporto di viaggio")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Chiudi") { dismiss() }.toolbarLabel() }
            }
        }
        .task(id: environment.passport) { render() }
    }

    private var stats: PassportStats { environment.passport }

    private var numbers: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
            tile("airplane", value: stats.flights, label: String(localized: "voli con AeroChat"))
            tile("person.2.fill", value: stats.peopleMet, label: String(localized: "passeggeri incontrati"))
            tile("bubble.left.and.bubble.right.fill", value: stats.messages, label: String(localized: "messaggi senza internet"))
            tile("gamecontroller.fill", value: stats.gamesWon, label: String(localized: "partite vinte (su \(stats.gamesPlayed))"))
            tile("dot.radiowaves.left.and.right", value: stats.longestChain, label: String(localized: "telefoni in catena (record)"))
            tile("rosette", value: stats.badges.count, label: String(localized: "badge sbloccati"))
        }
    }

    private func tile(_ icon: String, value: Int, label: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: icon).foregroundStyle(AeroTheme.accentEmerald)
            Text("\(value)")
                .font(AeroTheme.title(26, weight: .bold))
                .foregroundStyle(AeroTheme.textPrimary)
            Text(label)
                .font(.caption)
                .foregroundStyle(AeroTheme.textSecondary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var badges: some View {
        VStack(spacing: 0) {
            ForEach(PassportBadge.allCases) { badge in
                let unlockedAt = stats.badges[badge]
                HStack(spacing: 12) {
                    Image(systemName: badge.systemImage)
                        .font(.title3)
                        .frame(width: 32)
                        .foregroundStyle(unlockedAt != nil ? AeroTheme.accentCyan : AeroTheme.textSecondary.opacity(0.5))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(badge.title)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(unlockedAt != nil ? AeroTheme.textPrimary : AeroTheme.textSecondary)
                        if let unlockedAt {
                            Text("Sbloccato il \(Date(unixMilliseconds: unlockedAt).formatted(date: .abbreviated, time: .omitted))")
                                .font(.caption)
                                .foregroundStyle(AeroTheme.accentEmerald)
                        } else {
                            Text("Da sbloccare")
                                .font(.caption)
                                .foregroundStyle(AeroTheme.textSecondary)
                        }
                    }
                    Spacer()
                    if unlockedAt != nil {
                        Image(systemName: "checkmark.seal.fill").foregroundStyle(AeroTheme.accentEmerald)
                    }
                }
                .padding(.vertical, 10)
                .padding(.horizontal, 14)
                .accessibilityElement(children: .combine)
            }
        }
        .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func render() {
        let renderer = ImageRenderer(content: PassportCardContent(stats: stats, nickname: environment.nickname)
            .frame(width: 360, height: 640))
        renderer.scale = 3
        rendered = renderer.uiImage
    }
}

/// Grafica del passaporto da condividere (360x640 pt, resa a 3x = 1080x1920 px), con il QR per scaricare l'app.
struct PassportCardContent: View {

    let stats: PassportStats
    let nickname: String

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Image(systemName: "airplane")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(AeroTheme.accentCyan)
                Text("AeroChat")
                    .font(.system(size: 24, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
            }
            Text("Passaporto di viaggio")
                .font(.system(size: 30, weight: .heavy, design: .rounded))
                .foregroundStyle(.white)
            Text(nickname)
                .font(.system(size: 16, weight: .semibold, design: .rounded))
                .foregroundStyle(AeroTheme.accentCyan)
            HStack(spacing: 10) {
                number(stats.flights, String(localized: "voli con AeroChat"))
                number(stats.peopleMet, String(localized: "passeggeri incontrati"))
            }
            HStack(spacing: 10) {
                number(stats.messages, String(localized: "messaggi senza internet"))
                number(stats.gamesWon, String(localized: "partite vinte (su \(stats.gamesPlayed))"))
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 10) {
                ForEach(PassportBadge.allCases) { badge in
                    Image(systemName: badge.systemImage)
                        .font(.system(size: 20, weight: .semibold))
                        .frame(width: 52, height: 52)
                        .background(Circle().fill(.white.opacity(stats.badges[badge] != nil ? 0.15 : 0.04)))
                        .foregroundStyle(stats.badges[badge] != nil ? AeroTheme.accentEmerald : .white.opacity(0.25))
                }
            }
            Spacer()
            DownloadQRFooter()
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(LinearGradient(colors: [Color(hex: 0x05080F), Color(hex: 0x0B1B2B), Color(hex: 0x003845)],
                                   startPoint: .top, endPoint: .bottom))
        .environment(\.colorScheme, .dark)
    }

    private func number(_ value: Int, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(value)")
                .font(.system(size: 28, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
            Text(label)
                .font(.system(size: 12, weight: .medium, design: .rounded))
                .foregroundStyle(.white.opacity(0.7))
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// Fondo delle immagini condivise: QR verso `https://aeromesh.app/get` con la scritta "Scarica AeroChat".
struct DownloadQRFooter: View {

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            if let image = QRCodeGenerator.image(for: QRCodeGenerator.storeLandingURL, scale: 6) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 76, height: 76)
                    .padding(6)
                    .background(.white, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Scarica AeroChat")
                    .font(.system(size: 18, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                Text(verbatim: "aeromesh.app/get")
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(AeroTheme.accentCyan)
            }
            Spacer(minLength: 0)
        }
    }
}
