import SwiftUI
import PhotosUI

/// Barra di composizione: allegati (foto, giochi), testo, microfono / invio.
struct ComposerBar: View {

    @Binding var text: String
    @Binding var photoItem: PhotosPickerItem?
    let isRecording: Bool
    let recordingLevel: Float
    let recordingElapsed: TimeInterval
    let showsAttachments: Bool
    let onSend: () -> Void
    let onToggleRecording: () -> Void
    let onStartGame: (GameKind) -> Void

    @FocusState private var focused: Bool

    private var canSend: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        HStack(spacing: 10) {
            if showsAttachments && !isRecording {
                Menu {
                    Button { onStartGame(.ticTacToe) } label: { Label("Sfida a Tris", systemImage: "number") }
                    Button { onStartGame(.battleship) } label: { Label("Battaglia navale", systemImage: "ferry") }
                } label: {
                    Image(systemName: "gamecontroller")
                        .font(.title3)
                        .foregroundStyle(AeroTheme.accentCyan)
                }
                .accessibilityLabel("Mini-giochi")

                PhotosPicker(selection: $photoItem, matching: .images, photoLibrary: .shared()) {
                    Image(systemName: "photo")
                        .font(.title3)
                        .foregroundStyle(AeroTheme.accentCyan)
                }
                .accessibilityLabel("Invia foto")
            }

            if isRecording {
                HStack(spacing: 8) {
                    Circle().fill(Color.red).frame(width: 10, height: 10)
                        .opacity(0.5 + Double(recordingLevel) * 0.5)
                    Text(MessageBubble.durationLabel(Int(recordingElapsed * 1_000)))
                        .font(.body.monospacedDigit())
                        .foregroundStyle(AeroTheme.textPrimary)
                    Text("Registrazione… tocca per inviare")
                        .font(.footnote)
                        .foregroundStyle(AeroTheme.textSecondary)
                    Spacer()
                }
                .padding(.horizontal, 14)
                .frame(height: 40)
                .background(AeroTheme.surface, in: Capsule())
            } else {
                TextField("Messaggio", text: $text, axis: .vertical)
                    .lineLimit(1...5)
                    .focused($focused)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .foregroundStyle(AeroTheme.textPrimary)
                    .submitLabel(.send)
                    .onSubmit { if canSend { onSend() } }
            }

            if canSend && !isRecording {
                Button(action: onSend) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 32))
                        .foregroundStyle(AeroTheme.accentCyan)
                }
                .accessibilityLabel("Invia")
            } else if showsAttachments {
                Button(action: onToggleRecording) {
                    Image(systemName: isRecording ? "stop.circle.fill" : "mic.circle.fill")
                        .font(.system(size: 32))
                        .foregroundStyle(isRecording ? .red : AeroTheme.accentCyan)
                }
                .accessibilityLabel(isRecording ? "Ferma e invia nota vocale" : "Registra nota vocale")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(AeroTheme.background)
    }
}
