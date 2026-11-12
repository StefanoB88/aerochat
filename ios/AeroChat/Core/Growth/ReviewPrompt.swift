import Foundation

/// Richiesta di recensione (RELEASE_1_4_SPEC.md §B5): nei momenti felici (cartolina di fine volo mostrata, partita
/// vinta) solo se sono passati almeno 3 giorni dalla prima apertura, con almeno 5 aperture e al massimo una
/// richiesta ogni 90 giorni. Ogni ritorno in primo piano conta come apertura.
enum ReviewPrompt {

    static let minimumDaysSinceFirstOpen: Double = 3
    static let minimumOpens = 5
    static let minimumDaysBetweenRequests: Double = 90
    private static let day: TimeInterval = 24 * 3_600

    enum Moment: Sendable {
        case tripCardShown
        case gameWon
    }

    /// `firstOpen` nil = sconosciuta; `lastRequest` nil = mai chiesta.
    static func shouldRequest(now: Date, firstOpen: Date?, openCount: Int, lastRequest: Date?) -> Bool {
        guard let firstOpen, now.timeIntervalSince(firstOpen) >= minimumDaysSinceFirstOpen * day else { return false }
        guard openCount >= minimumOpens else { return false }
        if let lastRequest, now.timeIntervalSince(lastRequest) < minimumDaysBetweenRequests * day { return false }
        return true
    }

    // MARK: Persistenza

    /// Un'apertura (avvio o ritorno in primo piano).
    static func recordOpen(defaults: UserDefaults = .standard, now: Date = Date()) {
        if defaults.object(forKey: AppSettings.Key.firstOpenAt) == nil {
            defaults.set(now.timeIntervalSince1970, forKey: AppSettings.Key.firstOpenAt)
        }
        defaults.set(defaults.integer(forKey: AppSettings.Key.openCount) + 1, forKey: AppSettings.Key.openCount)
    }

    /// `true` (e richiesta registrata) se adesso si puo' chiedere la recensione.
    static func consumeIfAllowed(defaults: UserDefaults = .standard, now: Date = Date()) -> Bool {
        let firstOpen = (defaults.object(forKey: AppSettings.Key.firstOpenAt) as? Double).map(Date.init(timeIntervalSince1970:))
        let lastRequest = (defaults.object(forKey: AppSettings.Key.lastReviewRequestAt) as? Double).map(Date.init(timeIntervalSince1970:))
        guard shouldRequest(now: now, firstOpen: firstOpen, openCount: defaults.integer(forKey: AppSettings.Key.openCount),
                            lastRequest: lastRequest) else { return false }
        defaults.set(now.timeIntervalSince1970, forKey: AppSettings.Key.lastReviewRequestAt)
        return true
    }
}
