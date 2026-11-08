import SwiftUI
import ImageIO

/// Avatar circolare: foto profilo WebP se presente, altrimenti icona sul colore scelto dall'utente.
struct AvatarView: View {

    let imageData: Data?
    let colorHex: String?
    var systemImage = "person.fill"
    var size: CGFloat = 46

    @State private var image: UIImage?

    init(imageData: Data?, colorHex: String?, systemImage: String = "person.fill", size: CGFloat = 46) {
        self.imageData = imageData
        self.colorHex = colorHex
        self.systemImage = systemImage
        self.size = size
        // Gia' decodificato (riga riapparsa scorrendo): niente icona segnaposto per un frame.
        _image = State(initialValue: imageData.flatMap(AvatarImageCache.cached))
    }

    var body: some View {
        ZStack {
            Circle().fill(Color(hexString: colorHex) ?? AeroTheme.accentCyan)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: systemImage)
                    .font(.system(size: size * 0.4))
                    .foregroundStyle(.black.opacity(0.8))
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
        .task(id: imageData) {
            guard let imageData else {
                image = nil
                return
            }
            if let cached = AvatarImageCache.cached(imageData) {
                image = cached
                return
            }
            image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                AvatarImageCache.load(imageData)
            }.value
        }
    }

    nonisolated static func decode(_ data: Data) -> UIImage? {
        if let image = UIImage(data: data) { return image }
        return (try? WebPEncoder.decode(data)).map(UIImage.init(cgImage:))
    }
}

/// Avatar decodificati una volta sola: le liste (chat, membri del gruppo) ricreano le righe a ogni
/// scroll e ogni `AvatarView` ridecodificava il WebP. La miniatura ImageIO limita anche la memoria
/// di una foto profilo ostile che dichiari dimensioni enormi in pochi KB.
enum AvatarImageCache {

    /// 128 px dal protocollo; 384 px copre le viste piu' grandi a @3x senza perdere qualita'.
    static let maxPixelSize = 384

    nonisolated(unsafe) private static let cache: NSCache<NSData, UIImage> = {
        let cache = NSCache<NSData, UIImage>()
        cache.countLimit = 128
        return cache
    }()

    static func cached(_ data: Data) -> UIImage? {
        cache.object(forKey: data as NSData)
    }

    static func load(_ data: Data) -> UIImage? {
        if let cached = cached(data) { return cached }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        let image: UIImage?
        if let source = CGImageSourceCreateWithData(data as CFData, nil),
           let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) {
            image = UIImage(cgImage: thumbnail)
        } else if let decoded = try? WebPEncoder.decode(data) {
            image = UIImage(cgImage: ImageProcessor.downscale(decoded, maxLongSide: maxPixelSize))
        } else {
            image = nil
        }
        if let image { cache.setObject(image, forKey: data as NSData) }
        return image
    }
}

extension Color {
    /// Colore da stringa `RRGGBB` (es. dal profilo di un contatto); `nil` se non valida.
    init?(hexString: String?) {
        guard let hexString, hexString.count == 6, let value = UInt32(hexString, radix: 16) else { return nil }
        self.init(hex: value)
    }
}
