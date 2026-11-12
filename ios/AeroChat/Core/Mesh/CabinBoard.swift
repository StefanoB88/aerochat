import Foundation

/// Annuncio della bacheca della cabina (`BOARD_POST`): solo in memoria, mai nel DB. Le chiavi dell'autore
/// servono per rispondere con "Saluta" (`CONTACT_REQUEST`) anche se non e' nei contatti.
struct BoardPostEntry: Sendable, Equatable, Identifiable, Hashable {
    let postID: Data
    let authorNodeID: Data
    let x25519Pub: Data
    let ed25519Pub: Data
    /// Nickname dell'autore (gia' filtrato se il filtro linguaggio e' attivo).
    var nickname: String
    var avatarColorHex: String?
    let kind: BoardKind
    /// 1...140 caratteri, senza link, gia' mascherato se il filtro e' attivo.
    let text: String
    /// Volo canonico dell'autore, nil se non in volo.
    let flightCode: String?
    let seat: String?
    let createdAt: Date
    /// Ultima ripetizione ricevuta (o, per i nostri, creazione).
    var lastSeen: Date
    /// Annuncio nostro (aperto finche' non lo chiudiamo o scade).
    let isMine: Bool

    var id: Data { postID }

    /// Autore come persona vicina, per "Saluta" con le chiavi della bacheca.
    var asNearbyPerson: NearbyPerson {
        NearbyPerson(nodeID: authorNodeID, x25519Pub: x25519Pub, ed25519Pub: ed25519Pub, nickname: nickname,
                     avatarColorHex: avatarColorHex, flightCode: flightCode, openToGames: false, rssi: nil,
                     lastSeen: lastSeen, seat: seat)
    }

    /// Saluto precompilato per rispondere: `"Per il tuo annuncio: <testo>"`, al massimo 140 caratteri
    /// (il testo dell'annuncio viene troncato con "…").
    static func replyGreeting(prefix: String, text: String, max: Int = NearbyProtocol.maxGreetingLength) -> String {
        let full = prefix + text
        if full.count <= max { return full }
        let room = max - prefix.count - 1
        guard room > 0 else { return String(prefix.prefix(max)) }
        let truncated = String(text.prefix(room)).trimmingCharacters(in: .whitespaces)
        return prefix + truncated + "…"
    }

    /// Testo valido di un annuncio: trim, 1...140 caratteri, niente link; altrimenti nil.
    static func validText(_ raw: String) -> String? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...NearbyProtocol.maxBoardTextLength).contains(text.count), !MeshRouterActor.containsLink(text) else { return nil }
        return text
    }
}

/// Bacheca della cabina (RELEASE_1_4_SPEC.md §A2): annunci ricevuti (cache in memoria, max 50, voce tolta se
/// non ripetuta da 10 min, chiusa, scaduta dopo 6 h o di un mittente bloccato; max 10 per mittente) e annunci
/// nostri aperti (max 3, scadono dopo 6 h). Logica pura (tempi espliciti, test deterministici).
struct BoardCache: Sendable, Equatable {

    private(set) var received: [Data: BoardPostEntry] = [:]
    private(set) var mine: [BoardPostEntry] = []
    let capacity: Int
    let perSender: Int
    let staleAfter: TimeInterval
    let lifetime: TimeInterval
    let maxOwn: Int

    init(capacity: Int = NearbyProtocol.boardCacheCapacity,
         perSender: Int = NearbyProtocol.maxBoardPostsPerSender,
         staleAfter: TimeInterval = NearbyProtocol.boardPostStaleAfter,
         lifetime: TimeInterval = NearbyProtocol.boardPostLifetime,
         maxOwn: Int = NearbyProtocol.maxOwnBoardPosts) {
        self.capacity = capacity
        self.perSender = perSender
        self.staleAfter = staleAfter
        self.lifetime = lifetime
        self.maxOwn = maxOwn
    }

    // MARK: Ricevuti

    /// Annuncio verificato ricevuto. Ritorna `true` se la bacheca e' cambiata. Un `post_id` gia' noto vale
    /// solo dallo stesso autore (aggiorna l'ultimo visto); un nuovo annuncio oltre i 10 del mittente e' scartato.
    @discardableResult
    mutating func receive(_ entry: BoardPostEntry, now: Date = Date()) -> Bool {
        purgeExpired(now: now)
        guard now.timeIntervalSince(entry.createdAt) <= lifetime else { return false }
        var updated = entry
        updated.lastSeen = now
        if let existing = received[entry.postID] {
            guard existing.authorNodeID == entry.authorNodeID else { return false }
            received[entry.postID] = updated
            return true
        }
        guard received.values.filter({ $0.authorNodeID == entry.authorNodeID }).count < perSender else { return false }
        received[entry.postID] = updated
        while received.count > capacity, let oldest = received.values.min(by: { $0.lastSeen < $1.lastSeen }) {
            received.removeValue(forKey: oldest.postID)
        }
        return true
    }

    /// L'autore ha chiuso l'annuncio (`is_closed`): via dalla bacheca (solo se e' davvero suo).
    @discardableResult
    mutating func close(_ postID: Data, author: Data) -> Bool {
        guard let existing = received[postID], existing.authorNodeID == author else { return false }
        received.removeValue(forKey: postID)
        return true
    }

    /// Mittenti bloccati: tutti i loro annunci spariscono.
    @discardableResult
    mutating func removeAuthors(_ authors: Set<Data>) -> Bool {
        let before = received.count
        received = received.filter { !authors.contains($0.value.authorNodeID) }
        return received.count != before
    }

    /// Autore di un annuncio ancora in bacheca (chiavi per aprire un suo `CONTACT_REQUEST`).
    func author(_ nodeID: Data, now: Date = Date()) -> BoardPostEntry? {
        received.values.first { $0.authorNodeID == nodeID && isAlive($0, now: now) }
    }

    // MARK: Nostri

    /// Nuovo annuncio nostro: `false` se ne abbiamo gia' 3 aperti.
    @discardableResult
    mutating func addMine(_ entry: BoardPostEntry, now: Date = Date()) -> Bool {
        purgeExpired(now: now)
        guard mine.count < maxOwn else { return false }
        mine.append(entry)
        return true
    }

    /// Chiude un nostro annuncio: ritorna quello tolto (per l'ultimo invio con `is_closed`).
    mutating func closeMine(_ postID: Data) -> BoardPostEntry? {
        guard let index = mine.firstIndex(where: { $0.postID == postID }) else { return nil }
        return mine.remove(at: index)
    }

    /// Annunci nostri ancora aperti (da ripetere ogni 60 s).
    mutating func openMine(now: Date = Date()) -> [BoardPostEntry] {
        purgeExpired(now: now)
        return mine
    }

    func canPost(now: Date = Date()) -> Bool {
        mine.filter { now.timeIntervalSince($0.createdAt) <= lifetime }.count < maxOwn
    }

    // MARK: Manutenzione

    /// Ritorna `true` se qualcosa e' stato tolto.
    @discardableResult
    mutating func purgeExpired(now: Date = Date()) -> Bool {
        let before = received.count + mine.count
        received = received.filter { isAlive($0.value, now: now) }
        mine.removeAll { now.timeIntervalSince($0.createdAt) > lifetime }
        return received.count + mine.count != before
    }

    mutating func removeAll() {
        received = [:]
        mine = []
    }

    /// Annunci da mostrare (nostri e degli altri), i piu' recenti prima.
    func posts(now: Date = Date()) -> [BoardPostEntry] {
        (mine.filter { now.timeIntervalSince($0.createdAt) <= lifetime } + received.values.filter { isAlive($0, now: now) })
            .sorted { lhs, rhs in
                if lhs.createdAt != rhs.createdAt { return lhs.createdAt > rhs.createdAt }
                return lhs.postID.lexicographicallyPrecedes(rhs.postID)
            }
    }

    private func isAlive(_ entry: BoardPostEntry, now: Date) -> Bool {
        now.timeIntervalSince(entry.lastSeen) <= staleAfter && now.timeIntervalSince(entry.createdAt) <= lifetime
    }
}
