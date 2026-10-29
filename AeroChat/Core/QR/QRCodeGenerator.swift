import Foundation
import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit

/// Genera QR Code (pairing personale, invito gruppo, link agli store) con CoreImage.
enum QRCodeGenerator {

    /// Link mostrato al gate finche' c'e' ancora rete: punta alla pagina con i badge degli store.
    static let storeLandingURL = "https://aeromesh.app/get"

    static func image(for string: String, scale: CGFloat = 12, correctionLevel: String = "M") -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = correctionLevel
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        // Invertiamo i colori per il tema OLED: moduli chiari su sfondo nero restano leggibili dagli scanner.
        let context = CIContext(options: [.useSoftwareRenderer: false])
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }

    static func pairingImage(for payload: PairingPayload, scale: CGFloat = 12) -> UIImage? {
        image(for: payload.uriString, scale: scale)
    }
}
