import Foundation

/// Costanti di "Persone vicine" (PRESENCE = 17) e "Saluta" / "Sfida" (CONTACT_REQUEST = 18), condivise
/// con Android (vedi `NEARBY_SPEC.md`).
enum NearbyProtocol {
    /// PRESENCE: solo vicini diretti.
    static let presenceTTL: UInt32 = 1
    /// CONTACT_REQUEST: al massimo un ponte.
    static let contactRequestTTL: UInt32 = 2
    /// Intervallo di invio del PRESENCE con l'app in primo piano.
    static let presenceInterval: TimeInterval = 60
    /// Una voce della cache scade 3 minuti dopo l'ultimo PRESENCE.
    static let presenceLifetime: TimeInterval = 180
    static let maxPresenceEntries = 64
    /// Una REQUEST per mittente ogni 10 minuti (le altre scartate).
    static let requestRateLimit: TimeInterval = 600
    static let maxPendingRequests = 20
    /// Le richieste ricevute (e quelle inviate in attesa di risposta) scadono dopo 30 minuti.
    static let requestLifetime: TimeInterval = 1_800
    static let requestIDLength = 16
    static let maxGreetingLength = 140
    /// Lunghezza massima accettata del `flight_code` canonico.
    static let maxFlightCodeLength = 64

    /// Giochi che si possono proporre con "Sfida" (solo quelli a 2 giocatori).
    static let challengeKinds: [GameKind] = [.scopa, .briscola, .ticTacToe, .battleship]

    static func isChallengeKind(_ raw: Int32) -> Bool {
        challengeKinds.contains { Int32($0.rawValue) == raw }
    }

    /// Saluto ripulito: trim, max 140 caratteri, niente link (scartato), parolacce mascherate se il filtro e' attivo.
    static func sanitizedGreeting(_ text: String, maskProfanity: Bool) -> String {
        let trimmed = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxGreetingLength))
        guard !trimmed.isEmpty, !MeshRouterActor.containsLink(trimmed) else { return "" }
        return maskProfanity ? ContentFilter.masked(trimmed) : trimmed
    }

    /// "CARRIER|FLIGHT|yyyy-MM-dd" -> "AZ204" (nil se il codice non e' nel formato canonico).
    static func displayFlightNumber(fromCanonical canonical: String?) -> String? {
        guard let canonical else { return nil }
        let parts = canonical.split(separator: "|", omittingEmptySubsequences: false)
        guard parts.count >= 2, !parts[0].isEmpty, !parts[1].isEmpty else { return nil }
        return "\(parts[0])\(parts[1])"
    }
}

/// Passeggero annunciato da un PRESENCE (solo in memoria, mai nel DB).
struct NearbyPerson: Sendable, Equatable, Identifiable, Hashable {
    let nodeID: Data
    var x25519Pub: Data
    var ed25519Pub: Data
    var nickname: String
    var avatarColorHex: String?
    /// Codice canonico del volo ("CARRIER|FLIGHT|yyyy-MM-dd") se e' nella Cabina di un volo.
    var flightCode: String?
    var openToGames: Bool
    var rssi: Int?
    var lastSeen: Date

    var id: Data { nodeID }

    var asPeerRecord: PeerRecord {
        PeerRecord(nodeID: nodeID, x25519Pub: x25519Pub, ed25519Pub: ed25519Pub, nickname: nickname)
    }
}

/// Cache dei PRESENCE ricevuti: una voce per `node_id`, scade 3 minuti dopo l'ultimo annuncio,
/// al massimo 64 voci (si scarta la meno recente). Logica pura condivisa da router e UI.
struct PresenceCache: Sendable, Equatable {

    private(set) var entries: [Data: NearbyPerson] = [:]
    let lifetime: TimeInterval
    let capacity: Int

    init(lifetime: TimeInterval = NearbyProtocol.presenceLifetime, capacity: Int = NearbyProtocol.maxPresenceEntries) {
        self.lifetime = lifetime
        self.capacity = capacity
    }

    mutating func upsert(_ person: NearbyPerson, now: Date = Date()) {
        purgeExpired(now: now)
        var updated = person
        // L'RSSI del trasporto puo' mancare in un annuncio: si tiene l'ultimo noto.
        if updated.rssi == nil, let previous = entries[person.nodeID] { updated.rssi = previous.rssi }
        entries[person.nodeID] = updated
        while entries.count > capacity, let oldest = entries.values.min(by: { $0.lastSeen < $1.lastSeen }) {
            entries.removeValue(forKey: oldest.nodeID)
        }
    }

    @discardableResult
    mutating func purgeExpired(now: Date = Date()) -> Bool {
        let before = entries.count
        entries = entries.filter { now.timeIntervalSince($0.value.lastSeen) < lifetime }
        return entries.count != before
    }

    mutating func remove(_ nodeID: Data) {
        entries.removeValue(forKey: nodeID)
    }

    func person(_ nodeID: Data, now: Date = Date()) -> NearbyPerson? {
        guard let person = entries[nodeID], now.timeIntervalSince(person.lastSeen) < lifetime else { return nil }
        return person
    }

    /// Ordine della lista: segnale piu' forte prima (senza RSSI in fondo), poi visto piu' di recente.
    func sorted(now: Date = Date()) -> [NearbyPerson] {
        entries.values
            .filter { now.timeIntervalSince($0.lastSeen) < lifetime }
            .sorted { lhs, rhs in
                switch (lhs.rssi, rhs.rssi) {
                case let (l?, r?) where l != r: return l > r
                case (.some, nil): return true
                case (nil, .some): return false
                default:
                    if lhs.lastSeen != rhs.lastSeen { return lhs.lastSeen > rhs.lastSeen }
                    return lhs.nodeID.lexicographicallyPrecedes(rhs.nodeID)
                }
            }
    }

    var nextExpiry: Date? {
        entries.values.map { $0.lastSeen.addingTimeInterval(lifetime) }.min()
    }
}

/// "Saluta" / "Sfida" ricevuto, in attesa di Accetta / Rifiuta (scade dopo 30 minuti).
struct IncomingContactRequest: Sendable, Equatable, Identifiable, Hashable {
    let requestID: Data
    let sender: PeerRecord
    var avatarColorHex: String?
    let greeting: String
    /// 0 = "Saluta"; altrimenti `GameKind.rawValue` della "Sfida".
    let gameKind: Int32
    let receivedAt: Date

    var id: Data { requestID }
    var isChallenge: Bool { gameKind != 0 }
    var game: GameKind? { gameKind == 0 ? nil : GameKind(rawValue: Int(gameKind)) }

    func isExpired(now: Date = Date()) -> Bool {
        now.timeIntervalSince(receivedAt) >= NearbyProtocol.requestLifetime
    }
}

/// Esito di una nostra richiesta, notificato dal router.
enum ContactRequestOutcome: Sendable, Equatable {
    case accepted(conversationID: String)
    case declined
}

extension GameKind {
    /// Nome del gioco senza emoji ("Scopa", "Briscola"...), per "Marco ti sfida a Scopa".
    var displayName: String {
        switch self {
        case .scopa: return String(localized: "Scopa")
        case .briscola: return String(localized: "Briscola")
        case .scopone: return String(localized: "Scopone")
        case .ticTacToe: return String(localized: "Tris")
        case .battleship: return String(localized: "Battaglia navale")
        default: return String(localized: "un gioco")
        }
    }
}

/// Vista iniziale di "Persone vicine".
enum NearbyFilter: String, CaseIterable, Identifiable, Sendable {
    /// Tutti, per segnale.
    case all
    /// Prima chi e' sul nostro volo ("Gioca con chi e' a bordo").
    case flight
    /// Solo chi accetta sfide ("Sfida chi e' vicino").
    case games

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: return String(localized: "Tutti")
        case .flight: return String(localized: "Sul tuo volo")
        case .games: return String(localized: "Sfide")
        }
    }
}
