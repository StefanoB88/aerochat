import Foundation

/// Promemoria prima del volo (RELEASE_1_4_SPEC.md §B7): aggiungendo una carta d'imbarco con giorno di partenza
/// futuro, notifica locale alle 07:00 del giorno di partenza (fuso del telefono). Se il giorno e' oggi e le 07:00
/// sono gia' passate: nessun promemoria. Il tocco apre "Invita amici".
enum FlightReminder {

    static let reminderHour = 7

    /// Istante del promemoria per una partenza il giorno `departureDay` (mezzanotte UTC, come
    /// `BoardingPass.departureDay`), oppure nil se la partenza non e' futura o le 07:00 sono gia' passate.
    static func fireDate(departureDay: Date, now: Date = Date(), timeZone: TimeZone = .current) -> Date? {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let day = utc.dateComponents([.year, .month, .day], from: departureDay)
        var local = Calendar(identifier: .gregorian)
        local.timeZone = timeZone
        var components = DateComponents()
        components.year = day.year
        components.month = day.month
        components.day = day.day
        components.hour = reminderHour
        guard let fire = local.date(from: components), fire > now else { return nil }
        return fire
    }

    /// "AZ|204|2026-11-10" -> "AZ204".
    static func label(canonicalKey: String) -> String {
        NearbyProtocol.displayFlightNumber(fromCanonical: canonicalKey) ?? canonicalKey
    }
}
