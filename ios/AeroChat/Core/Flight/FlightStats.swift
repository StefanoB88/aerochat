import Foundation

/// Numeri del viaggio per la cartolina condivisibile: solo conteggi, nessun contenuto ne' nome altrui.
struct FlightStats: Equatable, Sendable {

    enum Period: String, CaseIterable, Identifiable {
        case day
        case trip
        case all

        var id: String { rawValue }

        var title: String {
            switch self {
            case .day: return String(localized: "Ultime 24 ore")
            case .trip: return String(localized: "Ultimi 3 giorni")
            case .all: return String(localized: "Da sempre")
            }
        }

        var since: Date {
            switch self {
            case .day: return Date().addingTimeInterval(-24 * 3_600)
            case .trip: return Date().addingTimeInterval(-72 * 3_600)
            case .all: return .distantPast
            }
        }
    }

    var period: Period
    var messagesSent = 0
    var messagesReceived = 0
    /// Persone diverse da cui abbiamo ricevuto almeno un messaggio (chat, gruppi e Cabina).
    var peopleMet = 0
    var photos = 0
    var voiceNotes = 0
    var gamesPlayed = 0
    var gamesWon = 0
    /// Salti massimi attraversati da un messaggio ricevuto (quanto era lunga la catena di telefoni).
    var longestHop = 0
    var loungeMessages = 0
    /// Titoli delle Cabine del volo attive (es. "AZ 204 · FCO → LHR · 10 nov").
    var flightTitles: [String] = []
    /// Cartolina di un volo finito: "Volo AZ204 · 10 nov" al posto del periodo.
    var headline: String?
    /// Classifica del volo (partite vinte durante il volo in corso o finito).
    var leaderboard: [FlightLeaderboard.Entry] = []

    var myRank: Int? { FlightLeaderboard.myRank(in: leaderboard) }

    /// Numeri congelati di un volo finito (ricordo del viaggio).
    init(trip: FlightTrip) {
        self.period = .trip
        messagesSent = trip.messagesSent
        messagesReceived = trip.messagesReceived
        peopleMet = trip.peopleMet
        gamesWon = trip.gamesWon
        gamesPlayed = trip.gamesPlayed
        flightTitles = [trip.title]
        leaderboard = trip.leaderboard
        headline = String(localized: "Volo \(trip.flightNumber) · \(trip.date.formatted(.dateTime.day().month(.abbreviated)))")
    }

    init(period: Period) {
        self.period = period
    }

    var totalMessages: Int { messagesSent + messagesReceived }

    static func compute(database: AppDatabase, period: Period) async throws -> FlightStats {
        var stats = FlightStats(period: period)
        let messages = try await database.messages.fetch(since: period.since)
        var senders: Set<Data> = []
        for message in messages {
            if message.isOutgoing {
                stats.messagesSent += 1
            } else {
                stats.messagesReceived += 1
                senders.insert(message.senderNodeID)
                stats.longestHop = max(stats.longestHop, message.hopCountReceived ?? 0)
            }
            if message.conversationID == ConversationRecord.cabinLoungeID { stats.loungeMessages += 1 }
            switch message.contentType {
            case .image: stats.photos += 1
            case .audio: stats.voiceNotes += 1
            case .game:
                stats.gamesPlayed += 1
                if let data = message.gameState, let state = try? GameSessionState.decode(data), state.outcome == .iWon {
                    stats.gamesWon += 1
                }
            case .text, .system: break
            }
        }
        stats.peopleMet = senders.count
        stats.flightTitles = (try? await database.conversations.activeFlightLounges())?.map(\.title) ?? []
        return stats
    }
}
