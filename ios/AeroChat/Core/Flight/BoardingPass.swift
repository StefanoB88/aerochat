import Foundation
import CryptoKit

/// Carta d'imbarco letta dal codice a barre IATA BCBP (PDF417 / Aztec / QR) o inserita a mano.
/// Dal volo + data si deriva in modo deterministico la "Cabina del volo": un gruppo cifrato che tutti
/// i passeggeri dello stesso volo ottengono senza scambiarsi QR.
struct BoardingPass: Equatable, Sendable {

    /// Codice IATA della compagnia (2-3 caratteri, es. "AZ").
    var carrier: String
    /// Numero di volo senza zeri iniziali (es. "204").
    var flightNumber: String
    var origin: String?
    var destination: String?
    var seat: String?
    /// Giorno di partenza (mezzanotte, calendario gregoriano locale).
    var departureDay: Date

    enum ParseError: Error, Equatable {
        case notBoardingPass
        case invalidFlight
        case invalidDate
    }

    /// Etichetta leggibile: "AZ 204".
    var flightCode: String { "\(carrier) \(flightNumber)" }

    /// Tratta leggibile: "FCO → LHR" (vuota se sconosciuta).
    var routeText: String {
        guard let origin, let destination else { return "" }
        return "\(origin) → \(destination)"
    }

    /// Chiave canonica condivisa con Android: `CARRIER|FLIGHT|yyyy-MM-dd` (UTC-agnostica: giorno del calendario).
    var canonicalKey: String {
        "\(carrier.uppercased())|\(flightNumber)|\(Self.dayFormatter.string(from: departureDay))"
    }

    /// Titolo della conversazione: "AZ 204 · FCO → LHR · 10 nov".
    var loungeTitle: String {
        let day = departureDay.formatted(.dateTime.day().month(.abbreviated))
        let route = routeText
        return route.isEmpty ? "\(flightCode) · \(day)" : "\(flightCode) · \(route) · \(day)"
    }

    /// La Cabina del volo scade 36 ore dopo la mezzanotte del giorno di partenza (copre voli notturni e scali).
    var loungeExpiry: Date { departureDay.addingTimeInterval(36 * 3_600) }

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    // MARK: - Inserimento manuale

    /// Da "AZ204", "AZ 0204", "az-204" + data; `nil` se il numero di volo non e' valido.
    init?(flightText: String, departureDay: Date) {
        let cleaned = flightText.uppercased().replacingOccurrences(of: "[^A-Z0-9]", with: "", options: .regularExpression)
        guard let split = cleaned.firstIndex(where: \.isNumber) else { return nil }
        let carrier = String(cleaned[..<split])
        let digits = String(cleaned[split...])
        guard (2...3).contains(carrier.count), (1...4).contains(digits.count), digits.allSatisfy(\.isNumber) else { return nil }
        guard let number = Int(digits), number > 0 else { return nil }
        self.carrier = carrier
        self.flightNumber = String(number)
        self.origin = nil
        self.destination = nil
        self.seat = nil
        self.departureDay = Self.startOfDay(departureDay)
    }

    init(carrier: String, flightNumber: String, origin: String?, destination: String?, seat: String?, departureDay: Date) {
        self.carrier = carrier
        self.flightNumber = flightNumber
        self.origin = origin
        self.destination = destination
        self.seat = seat
        self.departureDay = Self.startOfDay(departureDay)
    }

    // MARK: - IATA BCBP

    /// Decodifica il formato IATA Resolution 792 (BCBP), primo segmento di volo:
    /// `M1NOME/COGNOME       EABC123 FCOLHRAZ 0204 314Y012A0001 100`
    /// - [0] "M" (o "S"), [1] numero di segmenti, [2..<22] nome, [22] e-ticket, [23..<30] PNR,
    ///   [30..<33] partenza, [33..<36] arrivo, [36..<39] compagnia, [39..<44] volo, [44..<47] giorno giuliano,
    ///   [47] classe, [48..<52] posto.
    init(bcbp raw: String, now: Date = Date()) throws {
        let text = raw.replacingOccurrences(of: "\r", with: "").replacingOccurrences(of: "\n", with: "")
        let chars = Array(text)
        guard chars.count >= 47, chars[0] == "M" || chars[0] == "S", chars[1].isNumber else { throw ParseError.notBoardingPass }
        func field(_ range: Range<Int>) -> String {
            guard range.upperBound <= chars.count else { return "" }
            return String(chars[range]).trimmingCharacters(in: .whitespaces)
        }
        let origin = field(30..<33)
        let destination = field(33..<36)
        let carrier = field(36..<39)
        let flightRaw = field(39..<44).trimmingCharacters(in: CharacterSet(charactersIn: "0 "))
        let julian = field(44..<47)
        let seat = chars.count >= 52 ? field(48..<52).trimmingCharacters(in: CharacterSet(charactersIn: "0")) : nil

        guard (2...3).contains(carrier.count), carrier.allSatisfy({ $0.isLetter || $0.isNumber }),
              !flightRaw.isEmpty, flightRaw.allSatisfy(\.isNumber) else { throw ParseError.invalidFlight }
        guard let dayOfYear = Int(julian), (1...366).contains(dayOfYear) else { throw ParseError.invalidDate }

        self.carrier = carrier
        self.flightNumber = flightRaw
        self.origin = origin.count == 3 ? origin : nil
        self.destination = destination.count == 3 ? destination : nil
        self.seat = (seat?.isEmpty ?? true) ? nil : seat
        self.departureDay = Self.date(julianDay: dayOfYear, near: now)
    }

    /// Il BCBP porta solo il giorno dell'anno: si sceglie l'anno che rende la data piu' vicina a oggi
    /// (una carta scansionata il 2 gennaio per il volo del 31 dicembre appartiene all'anno precedente).
    static func date(julianDay: Int, near now: Date) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let year = calendar.component(.year, from: now)
        let candidates = (year - 1...year + 1).compactMap { year -> Date? in
            var components = DateComponents()
            components.year = year
            components.day = 1
            components.month = 1
            guard let first = calendar.date(from: components) else { return nil }
            return calendar.date(byAdding: .day, value: julianDay - 1, to: first)
        }
        let best = candidates.min { abs($0.timeIntervalSince(now)) < abs($1.timeIntervalSince(now)) }
        return startOfDay(best ?? now)
    }

    private static func startOfDay(_ date: Date) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar.startOfDay(for: date)
    }

    // MARK: - Cabina del volo (derivazione condivisa con Android)

    /// Chiave di gruppo: `SHA256("AeroMesh-Flight-Key-v1|" + canonicalKey)` (32 byte).
    var loungeSecretKey: Data {
        Data(SHA256.hash(data: Data("AeroMesh-Flight-Key-v1|\(canonicalKey)".utf8)))
    }

    /// UUID del gruppo: primi 16 byte di `SHA256("AeroMesh-Flight-UUID-v1|" + canonicalKey)` con i bit di
    /// versione (4) e variante (RFC 4122) forzati, cosi' resta uno UUID valido su entrambe le piattaforme.
    var loungeGroupID: UUID {
        var bytes = Array(SHA256.hash(data: Data("AeroMesh-Flight-UUID-v1|\(canonicalKey)".utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0F) | 0x40
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                           bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
    }
}
