import SwiftUI

/// Anteprima istantanea da BlurHash (decodifica 32x32 in ~20 ms, poi upscaling della GPU).
struct BlurHashImage: View {

    let hash: String
    var punch: Double = 1

    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
            } else {
                AeroTheme.surface
            }
        }
        .task(id: hash) {
            let hash = self.hash
            let punch = self.punch
            let decoded = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                guard let cg = BlurHash.image(hash, width: 32, height: 32, punch: punch) else { return nil }
                return UIImage(cgImage: cg)
            }.value
            image = decoded
        }
    }
}

/// Anello di avanzamento del trasferimento (chunk ricevuti / totali) sopra l'anteprima BlurHash.
struct TransferProgressRing: View {

    let progress: Double
    var lineWidth: CGFloat = 4
    var size: CGFloat = 44

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.white.opacity(0.25), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: max(0.02, min(1, progress)))
                .stroke(AeroTheme.accentCyan, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.easeOut(duration: 0.25), value: progress)
            Text("\(Int((progress * 100).rounded()))%")
                .font(.system(size: size * 0.26, weight: .semibold, design: .rounded).monospacedDigit())
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .padding(6)
        .background(.black.opacity(0.45), in: Circle())
        .accessibilityLabel(Text("Trasferimento al \(Int((progress * 100).rounded())) per cento"))
    }
}

/// Foto ricevuta/inviata: BlurHash finche' il file WebP non e' pronto, poi l'immagine vera.
struct MediaImageView: View {

    let blurhash: String?
    let relativePath: String?
    let store: MediaStore?
    var transferProgress: Double?

    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let blurhash, image == nil {
                BlurHashImage(hash: blurhash)
            }
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .transition(.opacity)
            } else if let transferProgress {
                TransferProgressRing(progress: transferProgress)
            } else if relativePath == nil {
                ProgressView()
                    .tint(AeroTheme.accentCyan)
            }
        }
        .task(id: relativePath) {
            guard let relativePath, let store else { return }
            let url = store.absoluteURL(relativePath: relativePath)
            let loaded = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                guard let data = try? Data(contentsOf: url) else { return nil }
                if let ui = UIImage(data: data) { return ui }
                guard let cg = try? WebPEncoder.decode(data) else { return nil }
                return UIImage(cgImage: cg)
            }.value
            withAnimation(.easeOut(duration: 0.25)) { image = loaded }
        }
    }
}
