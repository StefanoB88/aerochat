import Foundation

/// Mazzo napoletano da 40 carte condiviso da Scopa, Scopone scientifico e Briscola.
/// Una carta viaggia (e si salva) come id `0..39` = `seme * 10 + (valore - 1)`: stesso schema su iOS e Android.
enum NeapolitanCards {

    /// Semi nell'ordine degli id: 0 denari, 1 coppe, 2 spade, 3 bastoni.
    enum Suit: Int, Codable, Sendable, CaseIterable {
        case denari = 0
        case coppe = 1
        case spade = 2
        case bastoni = 3

        /// Simbolo usato nelle descrizioni delle mosse (♦ ♥ ♠ ♣).
        var symbol: String {
            switch self {
            case .denari: return "♦"
            case .coppe: return "♥"
            case .spade: return "♠"
            case .bastoni: return "♣"
            }
        }

        var name: String {
            switch self {
            case .denari: return String(localized: "Denari")
            case .coppe: return String(localized: "Coppe")
            case .spade: return String(localized: "Spade")
            case .bastoni: return String(localized: "Bastoni")
            }
        }
    }

    static let deckSize = 40
    /// Il settebello: 7 di denari.
    static let settebello = 6

    static func id(suit: Suit, rank: Int) -> Int { suit.rawValue * 10 + (rank - 1) }
    static func suit(_ card: Int) -> Suit { Suit(rawValue: card / 10) ?? .denari }
    /// Valore 1..10 (1 Asso, 8 Fante, 9 Cavallo, 10 Re): e' anche il valore di presa nella Scopa.
    static func rank(_ card: Int) -> Int { card % 10 + 1 }
    static func isValid(_ card: Int) -> Bool { (0..<deckSize).contains(card) }
    static func isFigure(_ card: Int) -> Bool { rank(card) >= 8 }

    // MARK: Scopa

    /// Valore di primiera: 7→21, 6→18, Asso→16, 5→15, 4→14, 3→13, 2→12, figure→10.
    static func primieraValue(_ card: Int) -> Int {
        switch rank(card) {
        case 7: return 21
        case 6: return 18
        case 1: return 16
        case 5: return 15
        case 4: return 14
        case 3: return 13
        case 2: return 12
        default: return 10
        }
    }

    /// Primiera di un mazzetto: somma del miglior valore per seme; 0 (non valida) se manca un seme.
    static func primiera(of cards: [Int]) -> Int {
        var best = [Int](repeating: 0, count: 4)
        for card in cards where isValid(card) {
            let suitIndex = suit(card).rawValue
            best[suitIndex] = max(best[suitIndex], primieraValue(card))
        }
        return best.contains(0) ? 0 : best.reduce(0, +)
    }

    // MARK: Briscola

    /// Forza nella presa (piu' alta vince): Asso, 3, Re, Cavallo, Fante, 7, 6, 5, 4, 2.
    static func briscolaStrength(_ card: Int) -> Int {
        switch rank(card) {
        case 1: return 10
        case 3: return 9
        case 10: return 8
        case 9: return 7
        case 8: return 6
        case 7: return 5
        case 6: return 4
        case 5: return 3
        case 4: return 2
        default: return 1
        }
    }

    /// Punti carta: Asso 11, 3 10, Re 4, Cavallo 3, Fante 2, altre 0 (totale mazzo 120).
    static func briscolaPoints(_ card: Int) -> Int {
        switch rank(card) {
        case 1: return 11
        case 3: return 10
        case 10: return 4
        case 9: return 3
        case 8: return 2
        default: return 0
        }
    }

    // MARK: Mazzo

    /// Permutazione casuale uniforme degli id 0..39 (la invia il mazziere nel DEAL).
    static func shuffledDeck() -> [Int] {
        var generator = SystemRandomNumberGenerator()
        return Array(0..<deckSize).shuffled(using: &generator)
    }

    /// `deck` valido = esattamente una volta ciascun id 0..39.
    static func isValidDeck(_ deck: [Int]) -> Bool {
        deck.count == deckSize && Set(deck) == Set(0..<deckSize)
    }

    // MARK: Testi

    /// Etichetta d'angolo della carta: "A", "2"…"7", "F", "C", "R".
    static func cornerLabel(_ card: Int) -> String {
        switch rank(card) {
        case 1: return "A"
        case 8: return "F"
        case 9: return "C"
        case 10: return "R"
        case let value: return String(value)
        }
    }

    /// Forma breve per le descrizioni: "7♦", "A♠", "R♣".
    static func shortName(_ card: Int) -> String { cornerLabel(card) + suit(card).symbol }

    /// Nome completo per VoiceOver: "Asso di Spade", "7 di Denari", "Settebello".
    static func fullName(_ card: Int) -> String {
        if card == settebello { return String(localized: "Settebello") }
        let suitName = suit(card).name
        switch rank(card) {
        case 1: return String(localized: "Asso di \(suitName)")
        case 8: return String(localized: "Fante di \(suitName)")
        case 9: return String(localized: "Cavallo di \(suitName)")
        case 10: return String(localized: "Re di \(suitName)")
        case let value: return String(localized: "\(value) di \(suitName)")
        }
    }
}
