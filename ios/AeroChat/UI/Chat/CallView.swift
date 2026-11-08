import SwiftUI

/// Schermata a tutto schermo della chiamata: squillo in entrata/uscita, chiamata attiva con durata,
/// muto, vivavoce, indicatore di segnale debole e riepilogo finale.
struct CallView: View {

    @Environment(AppEnvironment.self) private var environment

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(hex: 0x0B2447), AeroTheme.background], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            if let calls = environment.calls, let call = calls.current {
                content(call, calls: calls)
            }
        }
        .preferredColorScheme(.dark)
    }

    private func content(_ call: CallService.Call, calls: CallService) -> some View {
        VStack(spacing: 24) {
            Spacer(minLength: 40)
            AvatarView(imageData: call.peer.avatarImage, colorHex: call.peer.avatarColorHex, size: 132)
                .overlay {
                    if call.phase == .ringing {
                        PulsingRing()
                    }
                }
            VStack(spacing: 8) {
                Text(call.peer.nickname)
                    .font(AeroTheme.title(30))
                    .foregroundStyle(AeroTheme.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                status(call)
                    .font(.headline)
                    .foregroundStyle(AeroTheme.textSecondary)
                if call.isSignalWeak, call.phase == .active {
                    Label("Segnale debole: avvicinatevi", systemImage: "wifi.exclamationmark")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.yellow)
                        .transition(.opacity)
                }
            }
            .padding(.horizontal, 24)
            Spacer()
            controls(call, calls: calls)
                .padding(.bottom, 48)
        }
        .animation(.easeInOut, value: call.isSignalWeak)
    }

    @ViewBuilder
    private func status(_ call: CallService.Call) -> some View {
        switch call.phase {
        case .ringing:
            Text(call.direction == .incoming ? "Chiamata in arrivo…" : "Squilla…")
        case .active:
            if let connectedAt = call.connectedAt {
                // Solo questo testo si aggiorna ogni secondo.
                TimelineView(.periodic(from: connectedAt, by: 1)) { context in
                    Text(MessageBubble.durationLabel(Int(context.date.timeIntervalSince(connectedAt) * 1_000)))
                        .monospacedDigit()
                }
            }
        case .ended(let reason):
            Text(Self.label(for: reason))
        }
    }

    @ViewBuilder
    private func controls(_ call: CallService.Call, calls: CallService) -> some View {
        switch call.phase {
        case .ringing where call.direction == .incoming:
            HStack(spacing: 72) {
                CallButton(systemImage: "phone.down.fill", title: "Rifiuta", tint: .red) { calls.decline() }
                CallButton(systemImage: "phone.fill", title: "Rispondi", tint: AeroTheme.accentEmerald) { calls.accept() }
            }
        case .ringing, .active:
            VStack(spacing: 36) {
                if call.phase == .active {
                    HStack(spacing: 48) {
                        CallButton(systemImage: call.isMuted ? "mic.slash.fill" : "mic.fill",
                                   title: call.isMuted ? "Riattiva" : "Muto",
                                   tint: call.isMuted ? .white : Color.white.opacity(0.18),
                                   foreground: call.isMuted ? .black : .white) { calls.toggleMute() }
                        CallButton(systemImage: call.isSpeakerOn ? "speaker.wave.3.fill" : "speaker.fill",
                                   title: "Vivavoce",
                                   tint: call.isSpeakerOn ? .white : Color.white.opacity(0.18),
                                   foreground: call.isSpeakerOn ? .black : .white) { calls.toggleSpeaker() }
                    }
                }
                CallButton(systemImage: "phone.down.fill", title: "Chiudi", tint: .red) { calls.hangUp() }
            }
        case .ended:
            EmptyView()
        }
    }

    static func label(for reason: CallService.EndReason) -> String {
        switch reason {
        case .hungUp: return String(localized: "Chiamata terminata")
        case .rejected: return String(localized: "Chiamata rifiutata")
        case .busy: return String(localized: "Occupato")
        case .noAnswer: return String(localized: "Nessuna risposta")
        case .missed: return String(localized: "Chiamata persa")
        case .connectionLost: return String(localized: "Collegamento perso")
        case .unreachable: return String(localized: "Compagno non raggiungibile")
        case .microphoneDenied: return String(localized: "Consenti il microfono in Impostazioni")
        case .failed: return String(localized: "Chiamata non riuscita")
        }
    }
}

/// Pulsante tondo grande della schermata di chiamata.
private struct CallButton: View {

    let systemImage: String
    let title: LocalizedStringKey
    let tint: Color
    var foreground: Color = .white
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 10) {
                Image(systemName: systemImage)
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(foreground)
                    .frame(width: 76, height: 76)
                    .background(tint, in: Circle())
                Text(title)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AeroTheme.textPrimary)
            }
        }
        .buttonStyle(.haptic)
        .accessibilityLabel(Text(title))
    }
}

/// Onde concentriche attorno all'avatar mentre squilla.
private struct PulsingRing: View {

    @State private var animate = false

    var body: some View {
        Circle()
            .stroke(AeroTheme.accentCyan.opacity(animate ? 0 : 0.7), lineWidth: 4)
            .scaleEffect(animate ? 1.5 : 1)
            .onAppear {
                withAnimation(.easeOut(duration: 1.4).repeatForever(autoreverses: false)) { animate = true }
            }
            .accessibilityHidden(true)
    }
}
