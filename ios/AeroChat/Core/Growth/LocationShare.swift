import Foundation

/// Posizione condivisa in chat (RELEASE_1_4_SPEC.md §B11): un normale `CHAT_TEXT`
/// `"📍 <lat 5 decimali>, <lon 5 decimali> (±<accuratezza> m)"`. Le bolle che lo contengono mostrano
/// "Apri in Mappe" (`maps://?ll=lat,lon`).
enum LocationShare {

    struct Point: Equatable, Sendable {
        var latitude: Double
        var longitude: Double
        var accuracyMeters: Int?
    }

    /// Testo del messaggio (punto decimale e cifre fisse, qualunque sia la lingua del telefono).
    static func format(latitude: Double, longitude: Double, accuracyMeters: Double?) -> String {
        let base = String(format: "📍 %.5f, %.5f", locale: Locale(identifier: "en_US_POSIX"), latitude, longitude)
        guard let accuracyMeters, accuracyMeters.isFinite, accuracyMeters >= 0 else { return base }
        return "\(base) (±\(Int(accuracyMeters.rounded())) m)"
    }

    /// Prima posizione valida nel testo, se c'e'.
    static func parse(_ text: String) -> Point? {
        guard let regex = try? NSRegularExpression(
            pattern: #"📍\s*(-?\d{1,2}(?:\.\d+)?),\s*(-?\d{1,3}(?:\.\d+)?)(?:\s*\(±\s*(\d+)\s*m\))?"#) else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        guard let match = regex.firstMatch(in: text, range: range),
              let latRange = Range(match.range(at: 1), in: text),
              let lonRange = Range(match.range(at: 2), in: text),
              let latitude = Double(text[latRange]), let longitude = Double(text[lonRange]),
              (-90...90).contains(latitude), (-180...180).contains(longitude) else { return nil }
        let accuracy = Range(match.range(at: 3), in: text).flatMap { Int(text[$0]) }
        return Point(latitude: latitude, longitude: longitude, accuracyMeters: accuracy)
    }

    /// URL di Apple Maps: `maps://?ll=lat,lon&q=lat,lon`.
    static func mapsURL(for point: Point) -> URL? {
        let coordinates = String(format: "%.5f,%.5f", locale: Locale(identifier: "en_US_POSIX"), point.latitude, point.longitude)
        return URL(string: "maps://?ll=\(coordinates)&q=\(coordinates)")
    }
}
