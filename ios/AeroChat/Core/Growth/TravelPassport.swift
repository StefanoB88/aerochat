import Foundation

/// Badge del passaporto, con la soglia che li sblocca (RELEASE_1_4_SPEC.md §B9).
enum PassportBadge: String, Codable, CaseIterable, Sendable, Identifiable {
    case firstFlight
    case fiveFlights
    case tenFlights
    case tenPeople
    case fiftyPeople
    case firstWin
    case tenWins
    case hundredMessages

    var id: String { rawValue }

    func isUnlocked(by stats: PassportStats) -> Bool {
        switch self {
        case .firstFlight: return stats.flights >= 1
        case .fiveFlights: return stats.flights >= 5
        case .tenFlights: return stats.flights >= 10
        case .tenPeople: return stats.peopleMet >= 10
        case .fiftyPeople: return stats.peopleMet >= 50
        case .firstWin: return stats.gamesWon >= 1
        case .tenWins: return stats.gamesWon >= 10
        case .hundredMessages: return stats.messages >= 100
        }
    }

    var title: String {
        switch self {
        case .firstFlight: return String(localized: "Primo volo")
        case .fiveFlights: return String(localized: "5 voli")
        case .tenFlights: return String(localized: "10 voli")
        case .tenPeople: return String(localized: "10 passeggeri incontrati")
        case .fiftyPeople: return String(localized: "50 passeggeri incontrati")
        case .firstWin: return String(localized: "Prima vittoria")
        case .tenWins: return String(localized: "10 vittorie")
        case .hundredMessages: return String(localized: "100 messaggi senza internet")
        }
    }

    var systemImage: String {
        switch self {
        case .firstFlight: return "airplane.departure"
        case .fiveFlights: return "airplane"
        case .tenFlights: return "globe.europe.africa.fill"
        case .tenPeople: return "person.2.fill"
        case .fiftyPeople: return "person.3.fill"
        case .firstWin: return "trophy"
        case .tenWins: return "trophy.fill"
        case .hundredMessages: return "bubble.left.and.bubble.right.fill"
        }
    }
}

/// Statistiche cumulative di tutti i voli (non solo gli ultimi 10 del ricordo del viaggio), aggiornate alla
/// fine di ogni volo, con i badge sbloccati e la loro data.
struct PassportStats: Codable, Equatable, Sendable {
    var flights = 0
    var peopleMet = 0
    var messages = 0
    var gamesPlayed = 0
    var gamesWon = 0
    /// Record di telefoni in catena (salti massimi di un messaggio ricevuto in volo).
    var longestChain = 0
    /// Badge sbloccati -> istante (epoch ms).
    var badges: [PassportBadge: Int64] = [:]
    /// Voli gia' contati (chiave canonica), per non contarli due volte.
    var countedFlights: [String] = []
}

/// Logica pura del passaporto: aggiunge un volo concluso e sblocca i badge raggiunti.
enum TravelPassport {

    /// Voli contati ricordati al massimo (per l'idempotenza; i contatori restano comunque).
    static let maxCountedFlights = 200

    struct Update: Equatable, Sendable {
        var stats: PassportStats
        var newBadges: [PassportBadge]
    }

    static func record(_ trip: FlightTrip, into stats: PassportStats, now: Date = Date()) -> Update {
        guard !stats.countedFlights.contains(trip.flightCode) else { return Update(stats: stats, newBadges: []) }
        var updated = stats
        updated.countedFlights.append(trip.flightCode)
        if updated.countedFlights.count > maxCountedFlights {
            updated.countedFlights.removeFirst(updated.countedFlights.count - maxCountedFlights)
        }
        updated.flights += 1
        updated.peopleMet += trip.peopleMet
        updated.messages += trip.messagesSent + trip.messagesReceived
        updated.gamesPlayed += trip.gamesPlayed
        updated.gamesWon += trip.gamesWon
        updated.longestChain = max(updated.longestChain, trip.longestHop ?? 0)
        return unlockBadges(updated, now: now)
    }

    /// Sblocca i badge con la soglia raggiunta (quelli gia' sbloccati tengono la loro data).
    static func unlockBadges(_ stats: PassportStats, now: Date = Date()) -> Update {
        let fresh = PassportBadge.allCases.filter { stats.badges[$0] == nil && $0.isUnlocked(by: stats) }
        guard !fresh.isEmpty else { return Update(stats: stats, newBadges: []) }
        var updated = stats
        for badge in fresh { updated.badges[badge] = now.unixMilliseconds }
        return Update(stats: updated, newBadges: fresh)
    }
}

/// Passaporto salvato in `UserDefaults` (JSON), iniettabile nei test.
struct TravelPassportStore: @unchecked Sendable {

    let defaults: UserDefaults
    let key: String

    init(defaults: UserDefaults = .standard, key: String = "passport.stats") {
        self.defaults = defaults
        self.key = key
    }

    func load() -> PassportStats {
        guard let data = defaults.data(forKey: key), let stats = try? JSONDecoder().decode(PassportStats.self, from: data) else {
            return PassportStats()
        }
        return stats
    }

    /// Volo concluso: contatori aggiornati; ritorna i badge appena sbloccati.
    @discardableResult
    func record(_ trip: FlightTrip, now: Date = Date()) -> [PassportBadge] {
        let current = load()
        let update = TravelPassport.record(trip, into: current, now: now)
        if update.stats != current, let data = try? JSONEncoder().encode(update.stats) {
            defaults.set(data, forKey: key)
        }
        return update.newBadges
    }
}
