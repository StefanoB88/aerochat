import Foundation

/// Classifica del volo: partite vinte durante il volo, noi e gli avversari incontrati.
enum FlightLeaderboard {

    struct Entry: Codable, Equatable, Sendable, Identifiable {
        var nodeIDHex: String
        var name: String
        var wins: Int
        var played: Int
        var isMe: Bool

        var id: String { nodeIDHex }
    }

    /// Solo partite finite. Scopone: conta per noi, gli avversari restano fuori (sono 3).
    static func compute(games: [GameSessionState], myNodeID: Data, myName: String) -> [Entry] {
        var entries: [String: Entry] = [myNodeID.hexString: Entry(nodeIDHex: myNodeID.hexString, name: myName, wins: 0, played: 0, isMe: true)]
        for game in games where game.status == .finished {
            entries[myNodeID.hexString]?.played += 1
            if game.outcome == .iWon { entries[myNodeID.hexString]?.wins += 1 }
            guard game.gameKind != .scopone else { continue }
            let opponent = game.opponentNodeID
            let key = opponent.hexString
            var entry = entries[key] ?? Entry(nodeIDHex: key, name: opponentName(in: game), wins: 0, played: 0, isMe: false)
            entry.played += 1
            if game.outcome == .iLost { entry.wins += 1 }
            entries[key] = entry
        }
        return entries.values.sorted { lhs, rhs in
            if lhs.wins != rhs.wins { return lhs.wins > rhs.wins }
            if lhs.played != rhs.played { return lhs.played < rhs.played }
            if lhs.isMe != rhs.isMe { return lhs.isMe }
            return lhs.name < rhs.name
        }
    }

    /// Posizione (1 = primo) nostra in classifica; nil se nessuna partita giocata.
    static func myRank(in entries: [Entry]) -> Int? {
        guard let me = entries.firstIndex(where: \.isMe), entries[me].played > 0 else { return nil }
        // A pari vittorie si condivide il posto.
        let wins = entries[me].wins
        return (entries.firstIndex { $0.wins == wins } ?? me) + 1
    }

    private static func opponentName(in game: GameSessionState) -> String {
        if let seats = game.seats, let names = game.seatNames, let index = seats.firstIndex(of: game.opponentNodeID),
           names.indices.contains(index) {
            return names[index]
        }
        return String(localized: "Avversario")
    }
}

/// Un volo vissuto con AeroChat: inizia quando c'e' una Cabina del volo attiva e finisce quando la Cabina
/// scade o quando, dopo la modalita' aereo, la rete cellulare torna (atterraggio). Alla fine i numeri
/// vengono congelati per la cartolina e parte una sola notifica.
struct FlightTrip: Codable, Equatable, Sendable, Identifiable {
    /// Codice canonico del volo ("CARRIER|FLIGHT|yyyy-MM-dd").
    var flightCode: String
    var title: String
    var startedAtMs: Int64
    var endedAtMs: Int64?
    /// `node_id` (hex) dei passeggeri visti con PRESENCE / in Cabina durante il volo (max 256).
    var metNodeIDs: [String] = []
    /// La modalita' aereo e' stata vista accesa durante il volo (serve a riconoscere l'atterraggio).
    var sawAirplaneMode = false
    var notified = false
    // Numeri congelati alla fine del volo.
    var messagesSent = 0
    var messagesReceived = 0
    var gamesWon = 0
    var gamesPlayed = 0
    var peopleMet = 0
    var leaderboard: [FlightLeaderboard.Entry] = []

    var id: String { flightCode }

    static let maxMet = 256

    /// "AZ204" dal codice canonico.
    var flightNumber: String { NearbyProtocol.displayFlightNumber(fromCanonical: flightCode) ?? title }

    var date: Date { Date(unixMilliseconds: startedAtMs) }
}

/// Macchina a stati del ricordo del viaggio (logica pura, persistita in `TripMemoryStore`).
struct TripMemory: Codable, Equatable, Sendable {

    var current: FlightTrip?
    /// Voli finiti (piu' recenti in fondo), al massimo 10.
    var finished: [FlightTrip] = []

    static let maxFinished = 10

    /// Volo attivo del momento (Cabina del volo non scaduta), se c'e'.
    struct ActiveFlight: Equatable, Sendable {
        var flightCode: String
        var title: String
    }

    /// Nuovo stato osservato. Ritorna il volo appena finito (una sola volta per volo) da notificare.
    mutating func observe(activeFlight: ActiveFlight?, airplaneMode: Bool, now: Date = Date()) -> FlightTrip? {
        var ended: FlightTrip?
        if var trip = current {
            if activeFlight?.flightCode != trip.flightCode {
                // Cabina del volo scaduta (o sostituita da un altro volo).
                ended = finish(&trip, now: now)
            } else if airplaneMode {
                if !trip.sawAirplaneMode {
                    trip.sawAirplaneMode = true
                    current = trip
                }
            } else if trip.sawAirplaneMode {
                // Modalita' aereo spenta dopo essere stata accesa: atterrati.
                ended = finish(&trip, now: now)
            }
        }
        if current == nil, let activeFlight, !finished.contains(where: { $0.flightCode == activeFlight.flightCode }) {
            current = FlightTrip(flightCode: activeFlight.flightCode, title: activeFlight.title,
                                 startedAtMs: now.unixMilliseconds, sawAirplaneMode: airplaneMode)
        }
        return ended
    }

    /// Passeggero incontrato (PRESENCE o messaggio in Cabina) durante il volo in corso.
    mutating func recordMet(_ nodeID: Data) {
        guard var trip = current else { return }
        let hex = nodeID.hexString
        guard !trip.metNodeIDs.contains(hex), trip.metNodeIDs.count < FlightTrip.maxMet else { return }
        trip.metNodeIDs.append(hex)
        current = trip
    }

    /// Aggiorna i numeri congelati di un volo finito (calcolati dal DB).
    mutating func updateFinished(_ trip: FlightTrip) {
        guard let index = finished.firstIndex(where: { $0.flightCode == trip.flightCode }) else { return }
        finished[index] = trip
    }

    func trip(flightCode: String) -> FlightTrip? {
        if current?.flightCode == flightCode { return current }
        return finished.last { $0.flightCode == flightCode }
    }

    private mutating func finish(_ trip: inout FlightTrip, now: Date) -> FlightTrip? {
        current = nil
        trip.endedAtMs = now.unixMilliseconds
        let shouldNotify = !trip.notified
        trip.notified = true
        finished.removeAll { $0.flightCode == trip.flightCode }
        finished.append(trip)
        if finished.count > Self.maxFinished { finished.removeFirst(finished.count - Self.maxFinished) }
        return shouldNotify ? trip : nil
    }
}

/// Persistenza minima del ricordo del viaggio in `UserDefaults` (iniettabile nei test).
struct TripMemoryStore: @unchecked Sendable {

    let defaults: UserDefaults
    let key: String

    init(defaults: UserDefaults = .standard, key: String = "trip.memory") {
        self.defaults = defaults
        self.key = key
    }

    func load() -> TripMemory {
        guard let data = defaults.data(forKey: key), let memory = try? JSONDecoder().decode(TripMemory.self, from: data) else {
            return TripMemory()
        }
        return memory
    }

    func save(_ memory: TripMemory) {
        guard let data = try? JSONEncoder().encode(memory) else { return }
        defaults.set(data, forKey: key)
    }
}

extension FlightTrip {

    /// Congela i numeri del volo: messaggi inviati/ricevuti, partite (classifica) e persone incontrate
    /// (PRESENCE + chi ci ha scritto, in Cabina o in chat) tra inizio e fine.
    func withComputedCounters(database: AppDatabase, myNodeID: Data, myName: String) async throws -> FlightTrip {
        var trip = self
        let start = Date(unixMilliseconds: startedAtMs)
        let end = endedAtMs ?? Date.nowUnixMilliseconds
        let messages = try await database.messages.fetch(since: start).filter { $0.createdAtMs <= end }
        var met = Set(metNodeIDs)
        var games: [GameSessionState] = []
        trip.messagesSent = 0
        trip.messagesReceived = 0
        for message in messages {
            if message.contentType == .game {
                if let data = message.gameState, let state = try? GameSessionState.decode(data) { games.append(state) }
                continue
            }
            if message.isOutgoing {
                trip.messagesSent += 1
            } else {
                trip.messagesReceived += 1
                met.insert(message.senderNodeID.hexString)
            }
        }
        met.remove(myNodeID.hexString)
        met.remove(Data(DemoPeerIdentity.nodeID).hexString)
        trip.peopleMet = met.count
        trip.leaderboard = FlightLeaderboard.compute(games: games, myNodeID: myNodeID, myName: myName)
        let mine = trip.leaderboard.first(where: \.isMe)
        trip.gamesWon = mine?.wins ?? 0
        trip.gamesPlayed = mine?.played ?? 0
        return trip
    }
}
