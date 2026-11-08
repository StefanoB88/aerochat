import Foundation
import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit

/// Genera QR Code (pairing personale, invito gruppo, link agli store) con CoreImage.
enum QRCodeGenerator {

    /// Link mostrato al gate finche' c'e' ancora rete: punta alla pagina con i badge degli store.
    static let storeLandingURL = "https://aeromesh.app/get"

    /// Creare un `CIContext` e' costoso: uno solo per l'app, e i QR gia' generati restano in cache
    /// (la stessa stringa viene richiesta a ogni ridisegno dello sheet).
    private static let context = CIContext(options: [.useSoftwareRenderer: false])
    nonisolated(unsafe) private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 16
        return cache
    }()

    static func image(for string: String, scale: CGFloat = 12, correctionLevel: String = "M") -> UIImage? {
        let key = "\(correctionLevel)|\(scale)|\(string)" as NSString
        if let cached = cache.object(forKey: key) { return cached }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = correctionLevel
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        // Invertiamo i colori per il tema OLED: moduli chiari su sfondo nero restano leggibili dagli scanner.
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        let image = UIImage(cgImage: cgImage)
        cache.setObject(image, forKey: key)
        return image
    }

    static func pairingImage(for payload: PairingPayload, scale: CGFloat = 12) -> UIImage? {
        image(for: payload.uriString, scale: scale)
    }
}
