import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import libwebp

/// Codifica WebP lossy tramite libwebp (iOS non ha un encoder WebP di sistema; la decodifica
/// invece e' nativa in ImageIO da iOS 14). Android decodifica WebP nativamente con `BitmapFactory`.
enum WebPEncoder {

    enum Failure: Error, Equatable {
        case encodeFailed
        case decodeFailed
    }

    /// Codifica `image` in WebP lossy con la qualita' indicata (0...100).
    static func encode(_ image: CGImage, quality: Float) throws -> Data {
        let pixels = try RGBAPixels(image: image)
        var output: UnsafeMutablePointer<UInt8>?
        let size = pixels.data.withUnsafeBufferPointer { buffer -> Int in
            guard let base = buffer.baseAddress else { return 0 }
            return WebPEncodeRGBA(base, Int32(pixels.width), Int32(pixels.height), Int32(pixels.width * 4), quality, &output)
        }
        guard size > 0, let output else { throw Failure.encodeFailed }
        defer { WebPFree(output) }
        return Data(bytes: output, count: size)
    }

    /// Pixel massimi decodificati a piena risoluzione (4096x4096, 64 MB RGBA).
    static let maxDecodedPixels = 4_096 * 4_096

    /// Decodifica con ImageIO (nativo) e, in mancanza, con libwebp.
    static func decode(_ data: Data) throws -> CGImage {
        // Un WebP ricevuto dalla mesh puo' dichiarare dimensioni enormi in pochi KB (16383x16383 = 1 GB
        // RGBA): oltre il limite rifiutiamo invece di farci terminare da jetsam al primo disegno.
        let declared = dimensions(of: data)
        if let declared, declared.width * declared.height > maxDecodedPixels { throw Failure.decodeFailed }
        if let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceTypeIdentifierHint: UTType.webP.identifier] as CFDictionary),
           let image = CGImageSourceCreateImageAtIndex(source, 0, nil) {
            return image
        }
        // Fallback libwebp (sempre a piena risoluzione): solo con dimensioni note e gia' verificate.
        guard declared != nil else { throw Failure.decodeFailed }
        var width: Int32 = 0
        var height: Int32 = 0
        let decoded: UnsafeMutablePointer<UInt8>? = data.withUnsafeBytes { bytes in
            guard let base = bytes.bindMemory(to: UInt8.self).baseAddress else { return nil }
            return WebPDecodeRGBA(base, data.count, &width, &height)
        }
        guard let decoded, width > 0, height > 0 else { throw Failure.decodeFailed }
        defer { WebPFree(decoded) }
        let count = Int(width) * Int(height) * 4
        let pixels = Array(UnsafeBufferPointer(start: decoded, count: count))
        guard let image = RGBAPixels.makeImage(from: pixels, width: Int(width), height: Int(height)) else { throw Failure.decodeFailed }
        return image
    }

    /// `true` se i byte iniziano con il container RIFF/WEBP.
    static func isWebP(_ data: Data) -> Bool {
        data.count > 12 && data.prefix(4) == Data("RIFF".utf8) && data[data.startIndex + 8..<data.startIndex + 12] == Data("WEBP".utf8)
    }

    /// Dimensioni senza decodificare.
    static func dimensions(of data: Data) -> (width: Int, height: Int)? {
        var width: Int32 = 0
        var height: Int32 = 0
        let ok = data.withUnsafeBytes { bytes -> Int32 in
            guard let base = bytes.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return WebPGetInfo(base, data.count, &width, &height)
        }
        guard ok != 0 else { return nil }
        return (Int(width), Int(height))
    }
}
