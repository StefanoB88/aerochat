import Foundation
import Observation

/// "Persone vicine": cache in memoria dei PRESENCE ricevuti (scade da sola 3 minuti dopo l'ultimo annuncio).
@MainActor
@Observable
final class PresenceStore {

    private(set) var people: [NearbyPerson] = []

    @ObservationIgnored private var cache: PresenceCache
    @ObservationIgnored private let clock: @MainActor () -> Date
    @ObservationIgnored private var expiryTask: Task<Void, Never>?
    /// Ogni vicino visto (per il ricordo del viaggio).
    @ObservationIgnored var onPersonSeen: ((NearbyPerson) -> Void)?

    init(lifetime: TimeInterval = NearbyProtocol.presenceLifetime, clock: @escaping @MainActor () -> Date = { Date() }) {
        self.cache = PresenceCache(lifetime: lifetime)
        self.clock = clock
    }

    var count: Int { people.count }
    var isEmpty: Bool { people.isEmpty }

    func update(_ person: NearbyPerson) {
        cache.upsert(person, now: clock())
        onPersonSeen?(person)
        publish()
    }

    func person(_ nodeID: Data) -> NearbyPerson? { cache.person(nodeID, now: clock()) }

    /// Persone sullo stesso volo (stesso `flight_code` canonico).
    func onFlight(_ flightCode: String?) -> [NearbyPerson] {
        guard let flightCode else { return [] }
        return people.filter { $0.flightCode == flightCode }
    }

    func purgeExpired() {
        if cache.purgeExpired(now: clock()) { publish() }
    }

    func remove(_ nodeID: Data) {
        cache.remove(nodeID)
        publish()
    }

    func removeAll() {
        expiryTask?.cancel()
        expiryTask = nil
        cache = PresenceCache(lifetime: cache.lifetime, capacity: cache.capacity)
        if !people.isEmpty { people = [] }
    }

    private func publish() {
        let sorted = cache.sorted(now: clock())
        if sorted != people { people = sorted }
        scheduleExpiry()
    }

    private func scheduleExpiry() {
        expiryTask?.cancel()
        guard let next = cache.nextExpiry else {
            expiryTask = nil
            return
        }
        let delay = max(0.5, next.timeIntervalSince(clock()) + 0.1)
        expiryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self else { return }
            self.purgeExpired()
            self.scheduleExpiry()
        }
    }
}

/// "Richieste": "Saluta" / "Sfida" ricevuti (in memoria, scadono dopo 30 minuti), stato delle nostre
/// richieste e banner in app.
@MainActor
@Observable
final class ContactRequestStore {

    enum OutgoingState: Equatable, Sendable {
        case pending(requestID: Data)
        case accepted(conversationID: String)
        case declined
    }

    /// Banner in cima allo schermo (nuova richiesta o risposta a una nostra).
    struct Banner: Equatable, Identifiable {
        enum Kind: Equatable {
            case incoming(IncomingContactRequest)
            case accepted(nickname: String, conversationID: String)
            case declined(nickname: String)
        }
        let id = UUID()
        let kind: Kind
    }

    private(set) var incoming: [IncomingContactRequest] = []
    /// Stato delle nostre richieste per `node_id` del destinatario.
    private(set) var outgoing: [Data: OutgoingState] = [:]
    var banner: Banner?

    @ObservationIgnored private let clock: @MainActor () -> Date

    init(clock: @escaping @MainActor () -> Date = { Date() }) {
        self.clock = clock
    }

    func received(_ request: IncomingContactRequest) {
        purgeExpired()
        incoming.removeAll { $0.requestID == request.requestID || $0.sender.nodeID == request.sender.nodeID }
        incoming.insert(request, at: 0)
        banner = Banner(kind: .incoming(request))
    }

    func remove(_ requestID: Data) {
        incoming.removeAll { $0.requestID == requestID }
        if case .incoming(let request) = banner?.kind, request.requestID == requestID { banner = nil }
    }

    func sent(_ requestID: Data, to nodeID: Data) {
        outgoing[nodeID] = .pending(requestID: requestID)
    }

    func answered(peerNodeID: Data, nickname: String, outcome: ContactRequestOutcome) {
        switch outcome {
        case .accepted(let conversationID):
            outgoing[peerNodeID] = .accepted(conversationID: conversationID)
            banner = Banner(kind: .accepted(nickname: nickname, conversationID: conversationID))
        case .declined:
            outgoing[peerNodeID] = .declined
            banner = Banner(kind: .declined(nickname: nickname))
        }
    }

    func purgeExpired() {
        let now = clock()
        let alive = incoming.filter { !$0.isExpired(now: now) }
        if alive != incoming { incoming = alive }
    }

    func removeAll() {
        incoming = []
        outgoing = [:]
        banner = nil
    }

    // MARK: - Testi

    /// "Marco ti saluta" / "Marco ti sfida a Scopa".
    static func title(for request: IncomingContactRequest) -> String {
        if let game = request.game {
            return String(localized: "\(request.sender.nickname) ti sfida a \(game.displayName)")
        }
        return String(localized: "\(request.sender.nickname) ti saluta")
    }
}
