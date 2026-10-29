import SwiftUI

/// Bolla messaggio: testo, foto (BlurHash → WebP), nota vocale (forma d'onda), gioco, sistema.
struct MessageBubble: View {

    let message: MessageRecord
    let showSender: Bool
    let store: MediaStore?
    let playbackProgress: Double?
    let isPlaying: Bool
    /// Avanzamento del trasferimento media (0...1) finche' il file non e' completo.
    var transferProgress: Double?
    var onTogglePlayback: () -> Void = {}
    var onGameAction: (GameBubbleAction) -> Void = { _ in }

    private var isOutgoing: Bool { message.isOutgoing }

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
                .frame(width: 220, height: 165)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        case .audio:
            HStack(spacing: 10) {
                if message.mediaLocalPath == nil, let transferProgress {
                    TransferProgressRing(progress: transferProgress, lineWidth: 3, size: 28)
                } else {
                    Button(action: onTogglePlayback) {
                        Image(systemName: isPlaying ? "pause.circle.fill" : "play.circle.fill")
                            .font(.system(size: 32))
                            .foregroundStyle(message.mediaLocalPath == nil ? AeroTheme.textSecondary : AeroTheme.accentCyan)
                    }
                    .buttonStyle(.plain)
                    .disabled(message.mediaLocalPath == nil)
                    .accessibilityLabel(isPlaying ? "Pausa nota vocale" : "Riproduci nota vocale")
                }
                WaveformView(samples: message.audioWaveform ?? Data(), progress: playbackProgress ?? 0)
                    .frame(width: 150, height: 28)
                    .opacity(message.mediaLocalPath == nil ? 0.5 : 1)
                Text(Self.durationLabel(message.durationMs))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(AeroTheme.textSecondary)
            }
        case .game:
            if let data = message.gameState, let state = try? GameSessionState.decode(data) {
                GameBubble(state: state, onAction: onGameAction)
            } else {
                Text(message.text ?? "🎮")
            }
        }
    }

    private var footer: some View {
        HStack(spacing: 4) {
            Text(Date(unixMilliseconds: message.createdAtMs), style: .time)
                .font(.caption2)
                .foregroundStyle(AeroTheme.textSecondary)
            if isOutgoing {
                statusIcon
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch message.deliveryStatus {
        case .queued:
            Image(systemName: "clock")
                .font(.caption2)
                .foregroundStyle(AeroTheme.textSecondary)
                .accessibilityLabel("In coda")
        case .sentMesh:
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.caption2)
                .foregroundStyle(AeroTheme.textSecondary)
                .accessibilityLabel("Inviato nella mesh")
        case .deliveredAck:
            Image(systemName: "checkmark.circle.fill")
                .font(.caption2)
                .foregroundStyle(AeroTheme.textSecondary)
                .accessibilityLabel("Consegnato")
        case .read:
            Image(systemName: "checkmark.circle.fill")
                .font(.caption2)
                .foregroundStyle(AeroTheme.accentCyan)
                .accessibilityLabel("Letto")
        }
    }

    static func durationLabel(_ ms: Int?) -> String {
        let seconds = (ms ?? 0) / 1_000
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}
