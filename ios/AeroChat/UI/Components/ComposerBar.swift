import SwiftUI
import PhotosUI

/// Barra di composizione: allegati (foto, giochi), testo, microfono / invio.
struct ComposerBar: View {

    @Binding var text: String
    @Binding var photoItem: PhotosPickerItem?
    /// nil dove le note vocali non sono disponibili (Cabina Pubblica).
    let recorder: VoiceNoteRecorder?
    let showsAttachments: Bool
    let onSend: () -> Void
    let onToggleRecording: () -> Void
    let onStartGame: (GameKind) -> Void
    /// Giochi proposti nel menu (dipendono dal tipo di chat).
    var availableGames: [GameKind] = [.ticTacToe, .battleship]
    /// Apre la fotocamera per scattare e inviare una foto; nil dove le foto non sono previste.
    var onTakePhoto: (() -> Void)? = nil
    /// Modifica di un messaggio gia' inviato: striscia "Modifica messaggio" con la X per annullare,
    /// niente allegati ne' microfono, l'invio manda la revisione.
    var isEditing = false
    var onCancelEdit: () -> Void = {}

    @FocusState private var focused: Bool
    @State private var showsLibrary = false

    private var isRecording: Bool { recorder?.isRecording ?? false }

    private var canSend: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        VStack(spacing: 0) {
            if isEditing {
                editingStrip
            }
            inputRow
        }
        .background(AeroTheme.background)
        .photosPicker(isPresented: $showsLibrary, selection: $photoItem, matching: .images, photoLibrary: .shared())
        .onChange(of: isEditing) { _, editing in
            if editing { focused = true }
        }
    }

    private var editingStrip: some View {
        HStack(spacing: 8) {
            Image(systemName: "pencil")
                .foregroundStyle(AeroTheme.accentCyan)
            Text("Modifica messaggio")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(AeroTheme.textPrimary)
            Spacer()
            Button {
                Haptics.tap()
                onCancelEdit()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(AeroTheme.textSecondary)
                    .frame(width: 44, height: 32)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Annulla modifica")
        }
        .padding(.leading, 16)
        .padding(.trailing, 4)
        .padding(.top, 6)
    }

    private var inputRow: some View {
        HStack(spacing: 10) {
            if showsAttachments && !isRecording && !isEditing {
                Menu {
                    ForEach(availableGames, id: \.rawValue) { kind in
                        Button { Haptics.tap(); onStartGame(kind) } label: { gameLabel(kind) }
                    }
                } label: {
                    Image(systemName: "gamecontroller")
                        .font(.title2)
                        .frame(width: 44, height: 44)
                        .foregroundStyle(AeroTheme.accentCyan)
                }
                .accessibilityLabel("Mini-giochi")

                if let onTakePhoto, CameraPicker.isAvailable {
                    // Con la fotocamera disponibile il pulsante offre scatto o libreria.
                    Menu {
                        Button { Haptics.tap(); onTakePhoto() } label: { Label("Scatta foto", systemImage: "camera") }
                        Button { Haptics.tap(); showsLibrary = true } label: {
                            Label("Scegli dalla libreria", systemImage: "photo.on.rectangle")
                        }
                    } label: {
                        photoIcon
                    }
                    .accessibilityLabel("Invia foto")
                } else {
                    PhotosPicker(selection: $photoItem, matching: .images, photoLibrary: .shared()) {
                        photoIcon
                    }
                    .accessibilityLabel("Invia foto")
                }
            }

            if isRecording {
                HStack(spacing: 8) {
                    if let recorder { RecordingMeter(recorder: recorder) }
                    Text("Registrazione… tocca per inviare")
                        .font(.footnote)
                        .foregroundStyle(AeroTheme.textSecondary)
                        .lineLimit(2)
                        .minimumScaleFactor(0.8)
                    Spacer()
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
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
                    Image(systemName: isEditing ? "checkmark.circle.fill" : "arrow.up.circle.fill")
                        .font(.system(size: 38))
                        .foregroundStyle(AeroTheme.accentCyan)
                }
                .accessibilityLabel(isEditing ? Text("Salva modifica") : Text("Invia"))
            } else if showsAttachments && !isEditing {
                Button(action: onToggleRecording) {
                    Image(systemName: isRecording ? "stop.circle.fill" : "mic.circle.fill")
                        .font(.system(size: 38))
                        .foregroundStyle(isRecording ? .red : AeroTheme.accentCyan)
                }
                .accessibilityLabel(isRecording ? "Ferma e invia nota vocale" : "Registra nota vocale")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private func gameLabel(_ kind: GameKind) -> some View {
        switch kind {
        case .ticTacToe: Label("Sfida a Tris", systemImage: "number")
        case .battleship: Label("Battaglia navale", systemImage: "ferry")
        case .scopa: Label("Scopa", systemImage: "suit.diamond.fill")
        case .briscola: Label("Briscola", systemImage: "suit.club.fill")
        case .scopone: Label("Scopone", systemImage: "suit.spade.fill")
        default: EmptyView()
        }
    }

    private var photoIcon: some View {
        Image(systemName: "photo")
            .font(.title2)
            .frame(width: 44, height: 44)
            .foregroundStyle(AeroTheme.accentCyan)
    }
}

/// Livello e durata della registrazione (aggiornati ogni 100 ms): letti solo qui, cosi' il resto
/// della barra e la chat non vengono ridisegnati a ogni tick.
private struct RecordingMeter: View {

    let recorder: VoiceNoteRecorder

    var body: some View {
        Circle().fill(Color.red).frame(width: 10, height: 10)
            .opacity(0.5 + Double(recorder.level) * 0.5)
        Text(MessageBubble.durationLabel(Int(recorder.elapsed * 1_000)))
            .font(.body.monospacedDigit())
            .foregroundStyle(AeroTheme.textPrimary)
    }
}

extension View {
    /// Chiude la tastiera toccando fuori dal campo di testo (come WhatsApp). Il gesto e' simultaneo,
    /// quindi pulsanti, menu contestuali e scroll dei contenuti continuano a funzionare.
    func dismissesKeyboardOnTap() -> some View {
        contentShape(Rectangle())
            .simultaneousGesture(TapGesture().onEnded {
                UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
            })
    }
}
