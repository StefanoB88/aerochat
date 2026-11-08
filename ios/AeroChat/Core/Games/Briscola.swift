import Foundation

/// Briscola a 2 giocatori, una mano da 120 punti carta. Deterministica a partire dal `deck` del DEAL:
/// 3 carte a testa, `deck[6]` scoperta e' la briscola (seme di briscola) e finisce in fondo al tallone.
struct BriscolaGame: Codable, Sendable, Equatable {

    enum Phase: String, Codable, Sendable {
        case playing
        case finished
    }

    enum Failure: Error, Equatable {
        case invalidDeck
        case invalidSeat
        case notPlaying
        case notYourTurn
        case cardNotInHand
    }

    /// Presa completata: carte nell'ordine di gioco (prima quella di chi apre).
    struct Trick: Codable, Sendable, Equatable {
        var leader: Int
        var cards: [Int]
        var winner: Int
        var points: Int
    }

    static let playerCount = 2
    static let totalPoints = 120

    private(set) var dealer: Int
    private(set) var hands: [[Int]]
    /// Tallone: si pesca dal primo elemento; l'ultimo e' la briscola scoperta.
    private(set) var stock: [Int]
    private(set) var briscolaCard: Int
    /// Presa in corso: carte gia' giocate (0, 1).
    private(set) var trick: [Int] = []
    private(set) var leader: Int
    private(set) var turn: Int
    private(set) var points: [Int] = [0, 0]
    /// Carte vinte per posto.
    private(set) var won: [[Int]] = [[], []]
    private(set) var lastTrick: Trick?
    private(set) var phase: Phase = .playing
    /// Vincitore a fine mano; nil con `phase == .finished` = pareggio 60-60.
    private(set) var winnerSeat: Int?

    init(dealer: Int, deck: [Int]) throws {
        guard (0..<Self.playerCount).contains(dealer) else { throw Failure.invalidSeat }
        guard NeapolitanCards.isValidDeck(deck) else { throw Failure.invalidDeck }
        self.dealer = dealer
        var hands: [[Int]] = [[], []]
        for k in 0..<6 {
            hands[(dealer + 1 + k) % Self.playerCount].append(deck[k])
        }
        self.hands = hands
        self.briscolaCard = deck[6]
        self.stock = Array(deck[7...]) + [deck[6]]
        self.leader = (dealer + 1) % Self.playerCount
        self.turn = leader
    }

    var trumpSuit: NeapolitanCards.Suit { NeapolitanCards.suit(briscolaCard) }
    var isFinished: Bool { phase == .finished }

    /// `true` se vince chi risponde: una sola briscola vince; due briscole o stesso seme → piu' forte;
    /// seme diverso non di briscola → vince chi ha aperto.
    static func followerWins(lead: Int, follow: Int, trump: NeapolitanCards.Suit) -> Bool {
        let leadSuit = NeapolitanCards.suit(lead)
        let followSuit = NeapolitanCards.suit(follow)
        let leadTrump = leadSuit == trump
        let followTrump = followSuit == trump
        if leadTrump != followTrump { return followTrump }
        guard followSuit == leadSuit else { return false }
        return NeapolitanCards.briscolaStrength(follow) > NeapolitanCards.briscolaStrength(lead)
    }

    /// Gioca una carta; restituisce la presa se questa carta la completa.
    @discardableResult
    mutating func play(seat: Int, card: Int) throws -> Trick? {
        guard phase == .playing else { throw Failure.notPlaying }
        guard (0..<Self.playerCount).contains(seat) else { throw Failure.invalidSeat }
        guard seat == turn else { throw Failure.notYourTurn }
        guard let index = hands[seat].firstIndex(of: card) else { throw Failure.cardNotInHand }
        hands[seat].remove(at: index)
        trick.append(card)
        if trick.count < Self.playerCount {
            turn = (seat + 1) % Self.playerCount
            return nil
        }

        let follower = (leader + 1) % Self.playerCount
        let winner = Self.followerWins(lead: trick[0], follow: trick[1], trump: trumpSuit) ? follower : leader
        let trickPoints = trick.map(NeapolitanCards.briscolaPoints).reduce(0, +)
        points[winner] += trickPoints
        won[winner].append(contentsOf: trick)
        let completed = Trick(leader: leader, cards: trick, winner: winner, points: trickPoints)
        lastTrick = completed
        trick = []
        // Pesca: prima chi ha preso, poi l'altro, finche' c'e' tallone.
        if !stock.isEmpty {
            hands[winner].append(stock.removeFirst())
            if !stock.isEmpty {
                hands[(winner + 1) % Self.playerCount].append(stock.removeFirst())
            }
        }
        leader = winner
        turn = winner
        if stock.isEmpty && hands.allSatisfy(\.isEmpty) {
            phase = .finished
            if points[0] != points[1] {
                winnerSeat = points[0] > points[1] ? 0 : 1
            }
        }
        return completed
    }
}
