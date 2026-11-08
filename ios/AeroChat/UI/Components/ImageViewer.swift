import SwiftUI
import ImageIO

/// Foto aperta nel visore a schermo intero (`fullScreenCover(item:)`).
struct ViewedImage: Identifiable {
    let message: MessageRecord
    var id: Data { message.msgID }
}

/// Visore foto a schermo intero: sfondo nero, immagine adattata, pinch fino a 4x, trascinamento
/// quando ingrandita, doppio tocco per ingrandire/ripristinare, trascinamento verso il basso per chiudere.
struct ImageViewer: View {

    let relativePath: String
    let store: MediaStore?
    /// Salvataggio nel rullino; restituisce il testo da mostrare (stessa logica del menu della bolla).
    let onSave: () async -> (text: String, isError: Bool)?

    @Environment(\.dismiss) private var dismiss

    @State private var image: UIImage?
    @State private var scale: CGFloat = 1
    @State private var baseScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var baseOffset: CGSize = .zero
    @State private var dismissOffset: CGFloat = 0
    @State private var isSaving = false
    @State private var feedback: (text: String, isError: Bool)?

    private static let maxScale: CGFloat = 4
    private static let doubleTapScale: CGFloat = 2.5

    init(relativePath: String, store: MediaStore?, onSave: @escaping () async -> (text: String, isError: Bool)?) {
        self.relativePath = relativePath
        self.store = store
        self.onSave = onSave
        // Subito la miniatura della bolla (gia' in cache), poi la versione a piena risoluzione.
        _image = State(initialValue: ChatImageCache.cached(relativePath: relativePath))
    }

    var body: some View {
        GeometryReader { geometry in
            let container = geometry.size
            ZStack {
                Color.black
                    .opacity(backgroundOpacity)
                    .ignoresSafeArea()
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .interpolation(.high)
                        .scaledToFit()
                        .frame(width: container.width, height: container.height)
                        .scaleEffect(scale)
                        .offset(x: offset.width, y: offset.height + dismissOffset)
                        .contentShape(Rectangle())
                        .onTapGesture(count: 2, coordinateSpace: .local) { location in
                            toggleZoom(at: location, container: container, imageSize: image.size)
                        }
                        .gesture(magnifyGesture(container: container, imageSize: image.size)
                            .simultaneously(with: dragGesture(container: container, imageSize: image.size)))
                        .accessibilityLabel("Foto")
                        .accessibilityAddTraits(.isImage)
                } else {
                    ProgressView()
                        .tint(AeroTheme.accentCyan)
                        .controlSize(.large)
                }
            }
            .frame(width: container.width, height: container.height)
        }
        .ignoresSafeArea()
        .overlay(alignment: .top) { controls.opacity(controlsOpacity) }
        .overlay(alignment: .bottom) { feedbackToast }
        .animation(.easeInOut, value: feedback?.text)
        .presentationBackground(.clear)
        .statusBarHidden()
        .task(id: relativePath) { await loadFullImage() }
    }

    // MARK: Controlli

    private var controls: some View {
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 40, height: 40)
                    .background(.white.opacity(0.15), in: Circle())
            }
            .buttonStyle(.haptic)
            .tint(.white)
            .accessibilityLabel("Chiudi")
            Spacer()
            Button { save() } label: {
                Group {
                    if isSaving {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: "square.and.arrow.down")
                            .font(.system(size: 17, weight: .semibold))
                    }
                }
                .frame(width: 40, height: 40)
                .background(.white.opacity(0.15), in: Circle())
            }
            .buttonStyle(.haptic)
            .tint(.white)
            .disabled(isSaving)
            .accessibilityLabel("Salva nelle Foto")
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    @ViewBuilder
    private var feedbackToast: some View {
        if let feedback {
            Text(feedback.text)
                .font(.footnote)
                .foregroundStyle(AeroTheme.textPrimary)
                .padding(12)
                .background(AeroTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .padding()
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: feedback.text) {
                    try? await Task.sleep(for: .seconds(3))
                    self.feedback = nil
                }
        }
    }

    private var backgroundOpacity: Double {
        max(0.2, 1 - Double(abs(dismissOffset)) / 500)
    }

    private var controlsOpacity: Double {
        dismissOffset == 0 ? 1 : max(0, 1 - Double(abs(dismissOffset)) / 150)
    }

    private func save() {
        guard !isSaving else { return }
        isSaving = true
        Task {
            let result = await onSave()
            isSaving = false
            if let result { feedback = result }
        }
    }

    // MARK: Gesti

    private func magnifyGesture(container: CGSize, imageSize: CGSize) -> some Gesture {
        MagnifyGesture()
            .onChanged { value in
                scale = min(max(baseScale * value.magnification, 1), Self.maxScale)
                offset = clamped(offset, scale: scale, container: container, imageSize: imageSize)
            }
            .onEnded { _ in
                withAnimation(.spring(duration: 0.3)) {
                    if scale <= 1.01 {
                        scale = 1
                        offset = .zero
                    } else {
                        offset = clamped(offset, scale: scale, container: container, imageSize: imageSize)
                    }
                }
                baseScale = scale
                baseOffset = offset
            }
    }

    private func dragGesture(container: CGSize, imageSize: CGSize) -> some Gesture {
        DragGesture()
            .onChanged { value in
                if baseScale > 1 {
                    let proposed = CGSize(width: baseOffset.width + value.translation.width,
                                          height: baseOffset.height + value.translation.height)
                    offset = clamped(proposed, scale: scale, container: container, imageSize: imageSize)
                } else if scale <= 1 {
                    // Non ingrandita: solo verso il basso, per chiudere.
                    dismissOffset = max(0, value.translation.height)
                }
            }
            .onEnded { value in
                if baseScale > 1 {
                    baseOffset = offset
                } else if dismissOffset > 0 {
                    if value.translation.height > 120 || value.predictedEndTranslation.height > 320 {
                        Haptics.impact(.light)
                        dismiss()
                    } else {
                        withAnimation(.spring(duration: 0.3)) { dismissOffset = 0 }
                    }
                }
            }
    }

    private func toggleZoom(at location: CGPoint, container: CGSize, imageSize: CGSize) {
        Haptics.impact(.light)
        withAnimation(.spring(duration: 0.3)) {
            if scale > 1 {
                scale = 1
                offset = .zero
            } else {
                // Ingrandisce attorno al punto toccato.
                let target = Self.doubleTapScale
                let proposed = CGSize(width: (container.width / 2 - location.x) * (target - 1),
                                      height: (container.height / 2 - location.y) * (target - 1))
                scale = target
                offset = clamped(proposed, scale: target, container: container, imageSize: imageSize)
            }
        }
        baseScale = scale
        baseOffset = offset
    }

    /// Spostamento limitato ai bordi della foto ingrandita (niente bande nere oltre l'immagine).
    private func clamped(_ proposed: CGSize, scale: CGFloat, container: CGSize, imageSize: CGSize) -> CGSize {
        guard imageSize.width > 0, imageSize.height > 0, container.width > 0, container.height > 0 else { return .zero }
        let fit = min(container.width / imageSize.width, container.height / imageSize.height)
        let scaledWidth = imageSize.width * fit * scale
        let scaledHeight = imageSize.height * fit * scale
        let maxX = max(0, (scaledWidth - container.width) / 2)
        let maxY = max(0, (scaledHeight - container.height) / 2)
        return CGSize(width: min(max(proposed.width, -maxX), maxX),
                      height: min(max(proposed.height, -maxY), maxY))
    }

    // MARK: Caricamento

    private func loadFullImage() async {
        guard let store else { return }
        let url = store.absoluteURL(relativePath: relativePath)
        let loaded = await Task.detached(priority: .userInitiated) { Self.decodeFullSize(url: url) }.value
        if let loaded { image = loaded }
    }

    /// Decodifica a piena risoluzione (limitata a 4096 px) fuori dal main thread; niente cache:
    /// la cache della chat tiene solo le miniature delle bolle.
    nonisolated private static func decodeFullSize(url: URL) -> UIImage? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: 4096,
        ]
        if let source = CGImageSourceCreateWithData(data as CFData, nil),
           let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) {
            return UIImage(cgImage: cgImage)
        }
        if let decoded = try? WebPEncoder.decode(data) {
            return UIImage(cgImage: decoded)
        }
        return nil
    }
}
