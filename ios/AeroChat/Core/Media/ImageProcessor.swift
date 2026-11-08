import Foundation
import CoreGraphics
import ImageIO

/// Pipeline foto per la mesh: ridimensiona (lato lungo max 1280 px), codifica WebP a qualita' 65
/// puntando a 50-80 KB e calcola il BlurHash 4x3 per l'anteprima istantanea.
enum ImageProcessor {

    struct Result: Sendable, Equatable {
        let webp: Data
        let blurhash: String
        let width: Int
        let height: Int
        let quality: Float
    }

    enum Failure: Error, Equatable {
        case unreadableImage
    }

    static let maxLongSide = 1_280
    static let minimumLongSide = 640
    static let targetMinBytes = 50_000
    static let targetMaxBytes = 80_000
    static let initialQuality: Float = 65
    static let minimumQuality: Float = 35

    /// Da dati compressi (JPEG/HEIC/PNG dalla fotocamera o dalla libreria), applicando l'orientamento EXIF.
    static func prepareForMesh(imageData: Data) throws -> Result {
        let image = try downscale(imageData: imageData, maxLongSide: maxLongSide)
        return try prepareForMesh(image: image)
    }

    static func prepareForMesh(image: CGImage) throws -> Result {
        var working = downscale(image, maxLongSide: maxLongSide)
        var quality = initialQuality
        var webp = try WebPEncoder.encode(working, quality: quality)

        // Scala la qualita' finche' non rientriamo negli 80 KB (foto molto dettagliate).
        var attempts = 0
        while webp.count > targetMaxBytes, quality > minimumQuality, attempts < 6 {
            quality = max(minimumQuality, quality - 8)
            webp = try WebPEncoder.encode(working, quality: quality)
            attempts += 1
        }
        // Ultima risorsa (immagini ad altissimo dettaglio): riduci il lato lungo del 20% alla volta,
        // mai sotto i 640 px, finche' il file non rientra nel budget della banda 2.4 GHz.
        while webp.count > targetMaxBytes, max(working.width, working.height) > minimumLongSide {
            let nextLongSide = max(minimumLongSide, Int(Double(max(working.width, working.height)) * 0.8))
            working = downscale(working, maxLongSide: nextLongSide)
            webp = try WebPEncoder.encode(working, quality: quality)
        }

        let blurhash = try BlurHash.encode(working)
        return Result(webp: webp, blurhash: blurhash, width: working.width, height: working.height, quality: quality)
    }

    /// Decodifica + thumbnail in un colpo solo (ImageIO non carica l'immagine piena in memoria).
    static func downscale(imageData: Data, maxLongSide: Int) throws -> CGImage {
        guard let source = CGImageSourceCreateWithData(imageData as CFData, nil) else { throw Failure.unreadableImage }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxLongSide,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw Failure.unreadableImage
        }
        return image
    }

    static func downscale(_ image: CGImage, maxLongSide: Int) -> CGImage {
        let longSide = max(image.width, image.height)
        guard longSide > maxLongSide else { return image }
        let scale = Double(maxLongSide) / Double(longSide)
        let width = max(1, Int((Double(image.width) * scale).rounded()))
        let height = max(1, Int((Double(image.height) * scale).rounded()))
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: colorSpace, bitmapInfo: bitmapInfo) else { return image }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage() ?? image
    }
}
