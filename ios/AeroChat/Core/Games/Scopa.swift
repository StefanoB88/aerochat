import Foundation

/// Motore condiviso di Scopa (2 giocatori) e Scopone scientifico (4 giocatori, squadre {0,2} contro {1,3}).
/// Deterministico: tutti i dispositivi applicano gli stessi DEAL/MOVE nello stesso ordine e ottengono lo
/// stesso stato. Le "parti" (side) sono sempre 2: `side = posto % 2`.
struct ScopaGame: Codable, Sendable, Equatable {

    enum Phase: String, Codable, Sendable {
        /// Mano in corso.
        case playing
        /// Mano finita e punteggio sommato: si attende il DEAL del prossimo mazziere.
        case handOver
        /// Partita finita (una parte ha raggiunto 11 punti con totali diversi).
        case matchOver
    }

    enum Failure: Error, Equatable {
        case invalidDeck
        case invalidSeat
        case wrongDealer
        case notPlaying
        case notYourTurn
        case cardNotInHand
        case illegalCapture
    }

    /// Una giocata applicata (per l'ultima mossa mostrata al tavolo).
    struct Play: Codable, Sendable, Equatable {
        var seat: Int
        var card: Int
        var captured: [Int]
        var scopa: Bool
    }

    /// Punteggio di una mano, per parte (indice 0 = posti pari, 1 = posti dispari).
    struct HandScore: Codable, Sendable, Equatable {
        var cards: [Int]
        var denari: [Int]
        var settebello: [Int]
        /// Valore di primiera (0 = non valida, manca un seme).
        var primieraValue: [Int]
        var primiera: [Int]
        var scope: [Int]
        /// Punti della mano: carte + denari + settebello + primiera + scope.
        var total: [Int]

        /// Punto "carte": a chi ne ha di piu'.
        var cardsPoint: [Int] { Self.majority(cards) }
        var denariPoint: [Int] { Self.majority(denari) }

        static func majority(_ values: [Int]) -> [Int] {
            guard values.count == 2, values[0] != values[1] else { return [0, 0] }
            return values[0] > values[1] ? [1, 0] : [0, 1]
        }
    }

    static let targetScore = 11
    static let sides = 2

    let playerCount: Int
    private(set) var handNumber = 0
    private(set) var dealer: Int
    /// Ordine delle 40 carte della mano corrente (dal DEAL).
    private(set) var deck: [Int]
    /// Carte gia' distribuite da `deck`.
    private(set) var dealtCount = 0
    /// Carte in mano per posto, nell'ordine di distribuzione.
    private(set) var hands: [[Int]]
    private(set) var table: [Int] = []
    /// Mazzetti delle prese per parte.
    private(set) var captures: [[Int]] = [[], []]
    private(set) var scope: [Int] = [0, 0]
    private(set) var lastCaptureSeat: Int?
    private(set) var turn: Int
    private(set) var phase: Phase = .playing
    /// Totali della partita per parte.
    private(set) var scores: [Int] = [0, 0]
    private(set) var lastHandScore: HandScore?
    private(set) var lastPlay: Play?
    private(set) var winnerSide: Int?

    /// Prima mano: `dealer` distribuisce secondo `deck` (Scopa 3+3 e 4 in tavola, Scopone 10 a testa).
    init(playerCount: Int, dealer: Int, deck: [Int]) throws {
        guard playerCount == 2 || playerCount == 4 else { throw Failure.invalidSeat }
        guard (0..<playerCount).contains(dealer) else { throw Failure.invalidSeat }
        guard NeapolitanCards.isValidDeck(deck) else { throw Failure.invalidDeck }
        self.playerCount = playerCount
        self.dealer = dealer
        self.deck = deck
        self.hands = Array(repeating: [], count: playerCount)
        self.turn = (dealer + 1) % playerCount
        startHand(dealer: dealer, deck: deck)
    }

    var isScopone: Bool { playerCount == 4 }
    /// Mazziere della prossima mano (ruota in ordine di turno).
    var nextDealer: Int { (dealer + 1) % playerCount }
    var remainingInDeck: Int { deck.count - dealtCount }
    var isMatchOver: Bool { phase == .matchOver }

    static func side(of seat: Int) -> Int { seat % 2 }

    /// Nuova mano dopo `handOver`: solo il mazziere atteso puo' distribuire.
    mutating func deal(deck: [Int], dealer: Int) throws {
        guard phase == .handOver else { throw Failure.notPlaying }
        guard dealer == nextDealer else { throw Failure.wrongDealer }
        guard NeapolitanCards.isValidDeck(deck) else { throw Failure.invalidDeck }
        startHand(dealer: dealer, deck: deck)
    }

    private mutating func startHand(dealer: Int, deck: [Int]) {
        handNumber += 1
        self.dealer = dealer
        self.deck = deck
        dealtCount = 0
        hands = Array(repeating: [], count: playerCount)
        table = []
        captures = [[], []]
        scope = [0, 0]
        lastCaptureSeat = nil
        lastPlay = nil
        turn = (dealer + 1) % playerCount
        phase = .playing
        if isScopone {
            dealRound(cardsEach: 10)
        } else {
            dealRound(cardsEach: 3)
            table = Array(deck[dealtCount..<(dealtCount + 4)])
            dealtCount += 4
        }
    }

    /// Una carta alla volta in ordine di turno partendo dal giocatore dopo il mazziere.
    private mutating func dealRound(cardsEach: Int) {
        let count = min(cardsEach * playerCount, remainingInDeck)
        for k in 0..<count {
            hands[(dealer + 1 + k) % playerCount].append(deck[dealtCount + k])
        }
        dealtCount += count
    }

    // MARK: Prese

    /// Prese legali giocando `card` sul tavolo `table`: carte di pari valore (una sola, a scelta) che
    /// escludono le combinazioni; altrimenti le combinazioni di 2+ carte con la stessa somma. Ordine
    /// canonico: ogni presa ordinata per id, poi ordine lessicografico crescente.
    static func captureOptions(card: Int, table: [Int]) -> [[Int]] {
        let value = NeapolitanCards.rank(card)
        let sameValue = table.filter { NeapolitanCards.rank($0) == value }.sorted()
        if !sameValue.isEmpty { return sameValue.map { [$0] } }

        let sortedTable = table.sorted()
        var options: [[Int]] = []
        var current: [Int] = []
        func search(from index: Int, remaining: Int) {
            if remaining == 0 {
                if current.count >= 2 { options.append(current) }
                return
            }
            guard index < sortedTable.count else { return }
            for next in index..<sortedTable.count {
                let cardValue = NeapolitanCards.rank(sortedTable[next])
                guard cardValue <= remaining else { continue }
                current.append(sortedTable[next])
                search(from: next + 1, remaining: remaining - cardValue)
                current.removeLast()
            }
        }
        search(from: 0, remaining: value)
        return options.sorted { $0.lexicographicallyPrecedes($1) }
    }

    func captureOptions(for card: Int) -> [[Int]] {
        Self.captureOptions(card: card, table: table)
    }

    /// `captured` legale come insieme (ordine indifferente); vuoto solo se non esiste alcuna presa.
    static func isLegalCapture(_ captured: [Int], card: Int, table: [Int]) -> Bool {
        let options = captureOptions(card: card, table: table)
        if options.isEmpty { return captured.isEmpty }
        let sorted = captured.sorted()
        return options.contains(sorted)
    }

    // MARK: Giocata

    /// Applica la giocata del posto `seat`. Restituisce la giocata (con l'eventuale scopa).
    @discardableResult
    mutating func play(seat: Int, card: Int, captured: [Int]) throws -> Play {
        guard phase == .playing else { throw Failure.notPlaying }
        guard (0..<playerCount).contains(seat) else { throw Failure.invalidSeat }
        guard seat == turn else { throw Failure.notYourTurn }
        guard let handIndex = hands[seat].firstIndex(of: card) else { throw Failure.cardNotInHand }
        guard Self.isLegalCapture(captured, card: card, table: table) else { throw Failure.illegalCapture }

        hands[seat].remove(at: handIndex)
        let side = Self.side(of: seat)
        let isLastPlay = remainingInDeck == 0 && hands.allSatisfy(\.isEmpty)
        var isScopa = false
        if captured.isEmpty {
            table.append(card)
        } else {
            let taken = Set(captured)
            table.removeAll { taken.contains($0) }
            captures[side].append(contentsOf: captured.sorted())
            captures[side].append(card)
            lastCaptureSeat = seat
            // L'ultima giocata della mano non vale mai scopa.
            if table.isEmpty && !isLastPlay {
                scope[side] += 1
                isScopa = true
            }
        }
        let applied = Play(seat: seat, card: card, captured: captured.sorted(), scopa: isScopa)
        lastPlay = applied
        turn = (seat + 1) % playerCount

        if hands.allSatisfy(\.isEmpty) {
            if remainingInDeck > 0 {
                dealRound(cardsEach: 3)
            } else {
                endHand()
            }
        }
        return applied
    }

    private mutating func endHand() {
        // Le carte rimaste in tavola vanno a chi ha fatto l'ultima presa (non e' scopa).
        if let lastCaptureSeat {
            captures[Self.side(of: lastCaptureSeat)].append(contentsOf: table)
        }
        table = []
        let score = Self.score(captures: captures, scope: scope)
        lastHandScore = score
        for side in 0..<Self.sides { scores[side] += score.total[side] }
        if scores.contains(where: { $0 >= Self.targetScore }) && scores[0] != scores[1] {
            winnerSide = scores[0] > scores[1] ? 0 : 1
            phase = .matchOver
        } else {
            phase = .handOver
        }
    }

    /// Punteggio di una mano a partire dai mazzetti delle due parti.
    static func score(captures: [[Int]], scope: [Int]) -> HandScore {
        let cards = captures.map(\.count)
        let denari = captures.map { pile in pile.filter { NeapolitanCards.suit($0) == .denari }.count }
        let settebello = captures.map { $0.contains(NeapolitanCards.settebello) ? 1 : 0 }
        let primieraValue = captures.map { NeapolitanCards.primiera(of: $0) }
        let primiera = HandScore.majority(primieraValue)
        let cardsPoint = HandScore.majority(cards)
        let denariPoint = HandScore.majority(denari)
        let total = (0..<sides).map { side in
            cardsPoint[side] + denariPoint[side] + settebello[side] + primiera[side] + scope[side]
        }
        return HandScore(cards: cards, denari: denari, settebello: settebello, primieraValue: primieraValue,
                         primiera: primiera, scope: scope, total: total)
    }
}
