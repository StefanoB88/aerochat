import SwiftUI

/// Bolla messaggio: testo, foto (BlurHash → WebP), nota vocale (forma d'onda), gioco, sistema.
struct MessageBubble: View {

    let message: MessageRecord
    let showSender: Bool
    let store: MediaStore?
    /// Player della nota vocale, solo per la bolla attualmente in riproduzione.
    let player: VoiceNotePlayer?
    let isPlaying: Bool
    /// Avanzamento del trasferimento media (0...1) finche' il file non e' completo.
    var transferProgress: Double?
    var onTogglePlayback: () -> Void = {}
    var onGameAction: (GameBubbleAction) -> Void = { _ in }
    var onSaveImage: () -> Void = {}
    /// Tocco sulla foto (solo con il file completo in locale): visore a schermo intero.
    var onOpenImage: () -> Void = {}
    /// Foto da un mittente non verificato: resta sfocata finche' l'utente non la tocca (anti AirDrop-molesto).
    var hidesMediaUntilTap = false
    /// "Elimina per tutti" nel menu della foto (il menu della foto copre quello della bolla); nil = non disponibile.
    var onDeleteForEveryone: (() -> Void)? = nil

    @State private var isRevealed = false

    private var isOutgoing: Bool { message.isOutgoing }

    /// Sfocatura attiva finche' la foto non viene rivelata (la scelta sopravvive allo scroll della lista).
    private var isBlurred: Bool { hidesMediaUntilTap && !isRevealed && !RevealedMedia.contains(message.msgID) }

    var body: some View {
        HStack(alignment: .bottom, spacing: 0) {
            if isOutgoing { Spacer(minLength: 56) }
            VStack(alignment: .leading, spacing: 6) {
                if showSender, !isOutgoing, let sender = message.senderNickname {
                    Text(sender)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AeroTheme.accentCyan)
                }
                content
                footer
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(isOutgoing ? AeroTheme.accentCyan.opacity(0.16) : AeroTheme.surface,
                        in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(alignment: .topLeading) {
                if let hops = message.hopCountReceived, hops > 1, !isOutgoing {
                    Text("\(hops) salti")
                        .font(.system(size: 9, weight: .semibold))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(Color.yellow.opacity(0.9), in: Capsule())
                        .foregroundStyle(.black)
                        .offset(x: -4, y: -8)
                }
            }
            if !isOutgoing { Spacer(minLength: 56) }
        }
        .frame(maxWidth: .infinity, alignment: isOutgoing ? .trailing : .leading)
    }

    @ViewBuilder
    private var content: some View {
        if message.isDeleted {
            deletedPlaceholder
        } else {
            liveContent
        }
    }

    /// Messaggio cancellato per tutti: segnaposto in corsivo, niente media ne' azioni.
    private var deletedPlaceholder: some View {
        Label {
            (isOutgoing ? Text("Hai eliminato questo messaggio") : Text("Messaggio eliminato"))
                .italic()
        } icon: {
            Image(systemName: "nosign")
        }
        .font(.body)
        .foregroundStyle(AeroTheme.textSecondary)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var liveContent: some View {
        switch message.contentType {
        case .text, .system:
            Text(message.text ?? "")
                .font(.body)
                .foregroundStyle(AeroTheme.textPrimary)
                .textSelection(.enabled)
        case .image:
            MediaImageView(blurhash: message.blurhash,
                           relativePath: message.mediaLocalPath,
                           store: store,
                           transferProgress: message.mediaLocalPath == nil || transferProgress != nil ? transferProgress : nil)
                .blur(radius: isBlurred ? 22 : 0)
                .overlay {
                    if isBlurred {
                        VStack(spacing: 6) {
                            Image(systemName: "eye.slash.fill").font(.title2)
                            Text("Foto da un contatto non verificato")
                                .font(.caption.weight(.semibold))
                                .multilineTextAlignment(.center)
                            Text("Tocca per mostrarla")
                                .font(.caption2)
                        }
                        .foregroundStyle(.white)
                        .padding(12)
                        .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .padding(8)
                    }
                }
                .frame(width: 220, height: 165)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: 12, style: .continuous))
                .contentShape(.interaction, RoundedRectangle(cornerRadius: 12, style: .continuous))
                .onTapGesture {
                    if isBlurred {
                        Haptics.tap()
                        RevealedMedia.insert(message.msgID)
                        withAnimation(.easeOut(duration: 0.3)) { isRevealed = true }
                        return
                    }
                    // BlurHash / ricezione in corso: niente visore finche' il file non e' completo.
                    guard message.mediaLocalPath != nil else { return }
                    onOpenImage()
                }
                .accessibilityAddTraits(message.mediaLocalPath != nil ? .isButton : [])
                .accessibilityHint(isBlurred ? Text("Tocca per mostrare la foto") : message.mediaLocalPath != nil ? Text("Apri la foto a schermo intero") : Text(""))
                .contextMenu {
                    if message.mediaLocalPath != nil, !isBlurred {
                        Button { onSaveImage() } label: { Label("Salva nelle Foto", systemImage: "square.and.arrow.down") }
                    }
                    if let onDeleteForEveryone {
                        Button(role: .destructive) { onDeleteForEveryone() } label: {
                            Label("Elimina per tutti", systemImage: "trash")
                        }
                    }
                }
        case .audio:
            HStack(spacing: 10) {
                if message.mediaLocalPath == nil, let transferProgress {
                    TransferProgressRing(progress: transferProgress, lineWidth: 3, size: 28)
                } else {
                    Button(action: onTogglePlayback) {
                        Image(systemName: isPlaying ? "pause.circle.fill" : "play.circle.fill")
                            .font(.system(size: 36))
                            .foregroundStyle(message.mediaLocalPath == nil ? AeroTheme.textSecondary : AeroTheme.accentCyan)
                    }
                    .buttonStyle(.haptic)
                    .disabled(message.mediaLocalPath == nil)
                    .accessibilityLabel(isPlaying ? "Pausa nota vocale" : "Riproduci nota vocale")
                }
                PlaybackWaveform(samples: message.audioWaveform ?? Data(), player: player)
                    .frame(width: 150, height: 28)
                    .opacity(message.mediaLocalPath == nil ? 0.5 : 1)
                Text(Self.durationLabel(message.durationMs))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(AeroTheme.textSecondary)
            }
        case .game:
            if let data = message.gameState, let state = GameStateCache.state(for: data) {
                GameBubble(state: state, onAction: onGameAction)
            } else {
                Text(message.text ?? "🎮")
            }
        }
    }

    private var footer: some View {
        HStack(spacing: 4) {
            if message.editedAtMs != nil, !message.isDeleted {
                Text("modificato")
                    .font(.caption2)
                    .foregroundStyle(AeroTheme.textSecondary)
            }
            Text(Date(unixMilliseconds: message.createdAtMs), style: .time)
                .font(.caption2)
                .foregroundStyle(AeroTheme.textSecondary)
            if isOutgoing {
                statusIcon
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }

    /// Spunte stile WhatsApp: orologio = non inviato, 1 spunta = inviato, 2 spunte grigie = consegnato,
    /// 2 spunte ciano = letto.
    @ViewBuilder
    private var statusIcon: some View {
        switch message.deliveryStatus {
        case .queued:
            Image(systemName: "clock")
                .font(.caption2)
                .foregroundStyle(AeroTheme.textSecondary)
                .accessibilityLabel("Non ancora inviato")
        case .sentMesh:
            DeliveryTicks(count: 1, color: AeroTheme.textSecondary)
                .accessibilityLabel("Inviato")
        case .deliveredAck:
            DeliveryTicks(count: 2, color: AeroTheme.textSecondary)
                .accessibilityLabel("Consegnato")
        case .read:
            DeliveryTicks(count: 2, color: AeroTheme.accentCyan)
                .accessibilityLabel("Letto")
        }
    }

    static func durationLabel(_ ms: Int?) -> String {
        let seconds = (ms ?? 0) / 1_000
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

/// Foto sfocate che l'utente ha scelto di mostrare in questa sessione (la lista ricrea le bolle scorrendo).
@MainActor
enum RevealedMedia {
    private static var ids: Set<Data> = []
    static func contains(_ msgID: Data) -> Bool { ids.contains(msgID) }
    static func insert(_ msgID: Data) { ids.insert(msgID) }
}

/// Una o due spunte sovrapposte (SF Symbols non ha una doppia spunta).
private struct DeliveryTicks: View {

    let count: Int
    let color: Color

    var body: some View {
        ZStack(alignment: .leading) {
            ForEach(0..<count, id: \.self) { index in
                Image(systemName: "checkmark")
                    .font(.system(size: 10, weight: .bold))
                    .offset(x: CGFloat(index) * 4)
            }
        }
        .foregroundStyle(color)
        .frame(width: count == 2 ? 16 : 12, alignment: .leading)
    }
}

/// Foto in preparazione (lettura dalla libreria + compressione WebP): segnaposto con spinner.
struct PendingPhotoBubble: View {

    var body: some View {
        HStack {
            Spacer(minLength: 56)
            VStack(spacing: 10) {
                ProgressView()
                    .tint(AeroTheme.accentCyan)
                    .controlSize(.large)
                Text("Preparazione foto…")
                    .font(.caption)
                    .foregroundStyle(AeroTheme.textSecondary)
            }
            .frame(width: 220, height: 165)
            .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(AeroTheme.accentCyan.opacity(0.16), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .accessibilityElement(children: .combine)
    }
}

/// Forma d'onda con l'avanzamento letto dentro la vista: solo la bolla in riproduzione si aggiorna
/// ai tick del player, non l'intera lista.
private struct PlaybackWaveform: View {

    let samples: Data
    let player: VoiceNotePlayer?

    var body: some View {
        WaveformView(samples: samples, progress: player?.progress ?? 0)
    }
}

/// Stato delle partite decodificato una volta per contenuto (prima: decodifica JSON a ogni ridisegno).
private enum GameStateCache {

    nonisolated(unsafe) private static let cache: NSCache<NSData, Box> = {
        let cache = NSCache<NSData, Box>()
        cache.countLimit = 128
        return cache
    }()

    private final class Box {
        let state: GameSessionState
        init(_ state: GameSessionState) { self.state = state }
    }

    static func state(for data: Data) -> GameSessionState? {
        let key = data as NSData
        if let box = cache.object(forKey: key) { return box.state }
        guard let state = try? GameSessionState.decode(data) else { return nil }
        cache.setObject(Box(state), forKey: key)
        return state
    }
}
