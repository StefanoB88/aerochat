import Foundation

/// Prima partita gratis (RELEASE_1_4_SPEC.md §B2): oltre al Pass, **una partita gratis al giorno e una per ogni
/// volo** per chi la avvia (accettare e' sempre gratis). Chiave di consumo `flight:<flight_code>` con un volo
/// attivo, altrimenti `day:<yyyy-MM-dd>` nel fuso locale. Si consuma quando l'invito parte.
enum FreeGameAllowance {

    /// Chiavi ricordate al massimo (le piu' vecchie non servono piu').
    static let maxRememberedKeys = 60

    /// Chiave della partita gratis per adesso.
    static func key(activeFlightCode: String?, now: Date = Date(), timeZone: TimeZone = .current) -> String {
        let flight = activeFlightCode?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !flight.isEmpty { return "flight:\(flight)" }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let parts = calendar.dateComponents([.year, .month, .day], from: now)
        return String(format: "day:%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// La partita gratis di `key` e' ancora disponibile?
    static func isAvailable(_ key: String, used: [String]) -> Bool {
        !used.contains(key)
    }

    /// Elenco aggiornato dopo il consumo di `key` (limitato alle chiavi piu' recenti, in fondo).
    static func consume(_ key: String, used: [String]) -> [String] {
        var updated = used.filter { $0 != key }
        updated.append(key)
        if updated.count > maxRememberedKeys { updated.removeFirst(updated.count - maxRememberedKeys) }
        return updated
    }

    // MARK: Persistenza

    static func isAvailableNow(activeFlightCode: String?, defaults: UserDefaults = .standard, now: Date = Date()) -> Bool {
        isAvailable(key(activeFlightCode: activeFlightCode, now: now), used: defaults.stringArray(forKey: AppSettings.Key.freeGamesUsed) ?? [])
    }

    static func consumeNow(activeFlightCode: String?, defaults: UserDefaults = .standard, now: Date = Date()) {
        let used = defaults.stringArray(forKey: AppSettings.Key.freeGamesUsed) ?? []
        defaults.set(consume(key(activeFlightCode: activeFlightCode, now: now), used: used), forKey: AppSettings.Key.freeGamesUsed)
    }
}
