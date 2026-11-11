import Foundation
import CryptoKit

/// Identita' fissa del passeggero virtuale (derivata da etichette costanti, nessuna chiave reale):
/// riattivare la prova ritrova la stessa chat. Fuori dal MainActor perche' la usa anche il router.
enum DemoPeerIdentity {
    static let nodeID = Data(SHA256.hash(data: Data("AeroMesh-Demo-Peer-Node".utf8))).prefix(MeshProtocol.nodeIDLength)
    static let x25519Pub = Data(SHA256.hash(data: Data("AeroMesh-Demo-Peer-X25519".utf8)))
    static let ed25519Pub = Data(SHA256.hash(data: Data("AeroMesh-Demo-Peer-Ed25519".utf8)))

    static var conversationID: String { "direct:\(Data(nodeID).hexString)" }

    static func isDemoPeer(_ nodeID: Data?) -> Bool { nodeID.map { Data($0) == Data(Self.nodeID) } ?? false }

    /// Pacchetti verso il passeggero virtuale: mai sulla radio.
    static func isLocalOnly(_ recipientID: Data) -> Bool { Data(recipientID) == Data(nodeID) }
}

/// Avversario del passeggero virtuale: tiene una copia della partita dal suo lato (stesse regole di
/// `GameSessionState`) e risponde alle nostre mosse con mosse legali. Scopa: prende se puo' (preferendo
/// scope, settebello e denari); Briscola: una carta legale scelta con buon senso; Tris: vince o blocca.
actor DemoGameBot {

    /// Giochi che il passeggero virtuale sa giocare.
    static let supportedKinds: [GameKind] = [.scopa, .briscola, .ticTacToe]

    nonisolated let botNodeID: Data
    private var mirrors: [String: GameSessionState] = [:]

    init(botNodeID: Data = Data(DemoPeerIdentity.nodeID)) {
        self.botNodeID = botNodeID
    }

    /// Stato della partita visto dal passeggero virtuale (test).
    func mirror(gameID: String) -> GameSessionState? { mirrors[gameID] }

    /// Mosse di risposta (in ordine) a una nostra mossa; vuoto se non tocca al passeggero virtuale.
    func respond(to move: GameMove, from player: Data) -> [GameMove] {
        if move.action == .invite {
            return accept(move, from: player)
        }
        guard var state = mirrors[move.gameID], state.status != .finished else { return [] }
        if move.action == .resign {
            state.status = .finished
            mirrors[move.gameID] = state
            return []
        }
        var replies: [GameMove] = []
        if move.kind.isCardGame {
            guard move.action == .deal || move.action == .move else { return [] }
            state.receiveCardMove(BufferedCardMove(move, senderNodeID: player), maxGap: GameService.maxSequenceGap)
            replies = playCardTurns(&state)
        } else if move.kind == .ticTacToe, move.action == .move {
            replies = playTicTacToe(move, state: &state)
        }
        mirrors[move.gameID] = state
        return replies
    }

    // MARK: Invito

    private func accept(_ invite: GameMove, from player: Data) -> [GameMove] {
        guard Self.supportedKinds.contains(invite.kind), invite.sequence == 0, mirrors[invite.gameID] == nil else { return [] }
        var state = GameSessionState(gameID: invite.gameID, kind: invite.kind.rawValue, conversationID: DemoPeerIdentity.conversationID,
                                     opponentNodeID: player, iAmInviter: false)
        state.status = .active
        state.sequence = 1
        if invite.kind.isCardGame {
            state.seats = [player, botNodeID]
            state.mySeat = 1
        } else {
            state.ticTacToe = TicTacToe()
        }
        mirrors[invite.gameID] = state
        var reply = GameMove()
        reply.gameID = invite.gameID
        reply.kind = invite.kind
        reply.action = .accept
        reply.sequence = 1
        return [reply]
    }

    // MARK: Carte

    private func playCardTurns(_ state: inout GameSessionState) -> [GameMove] {
        var replies: [GameMove] = []
        // Al massimo una mano intera di risposte: niente cicli infiniti su uno stato inatteso.
        for _ in 0..<8 {
            guard state.status != .finished else { break }
            if state.shouldDeal, let dealer = state.mySeat {
                let deck = NeapolitanCards.shuffledDeck()
                let applied = BufferedCardMove(sequence: state.sequence + 1, action: .deal, senderNodeID: botNodeID,
                                               cell: dealer, deck: deck)
                guard (try? state.applyCardMove(applied)) != nil else { break }
                var move = GameMove()
                move.gameID = state.gameID
                move.kind = state.gameKind
                move.action = .deal
                move.sequence = applied.sequence
                move.cell = UInt32(dealer)
                move.deck = Data(deck.map { UInt8($0) })
                replies.append(move)
                continue
            }
            guard let seat = state.mySeat, state.turnSeat == seat, let choice = Self.chooseCard(in: state, seat: seat) else { break }
            let applied = BufferedCardMove(sequence: state.sequence + 1, action: .move, senderNodeID: botNodeID,
                                           cell: choice.card, captured: choice.captured)
            guard (try? state.applyCardMove(applied)) != nil else { break }
            var move = GameMove()
            move.gameID = state.gameID
            move.kind = state.gameKind
            move.action = .move
            move.sequence = applied.sequence
            move.cell = UInt32(choice.card)
            move.captured = Data(choice.captured.sorted().map { UInt8($0) })
            move.result = state.cardResult(forSenderSeat: seat)
            replies.append(move)
        }
        return replies
    }

    static func chooseCard(in state: GameSessionState, seat: Int) -> (card: Int, captured: [Int])? {
        if let scopa = state.scopa {
            guard scopa.hands.indices.contains(seat) else { return nil }
            return chooseScopaPlay(hand: scopa.hands[seat], table: scopa.table)
        }
        if let briscola = state.briscola {
            guard briscola.hands.indices.contains(seat) else { return nil }
            return chooseBriscolaCard(hand: briscola.hands[seat], trick: briscola.trick, trump: briscola.trumpSuit).map { ($0, []) }
        }
        return nil
    }

    /// Scopa: se esiste una presa la fa (scopa > settebello > piu' carte e denari), altrimenti scarta la
    /// carta piu' bassa evitando denari e sette.
    static func chooseScopaPlay(hand: [Int], table: [Int]) -> (card: Int, captured: [Int])? {
        guard !hand.isEmpty else { return nil }
        let settebello = NeapolitanCards.id(suit: .denari, rank: 7)
        var best: (card: Int, captured: [Int], score: Int)?
        for card in hand {
            for option in ScopaGame.captureOptions(card: card, table: table) {
                let taken = option + [card]
                var score = taken.count * 10
                score += taken.filter { NeapolitanCards.suit($0) == .denari }.count * 4
                if taken.contains(settebello) { score += 60 }
                if option.count == table.count { score += 100 }
                score += taken.filter { NeapolitanCards.rank($0) == 7 }.count * 6
                if best == nil || score > best!.score { best = (card, option, score) }
            }
        }
        if let best { return (best.card, best.captured) }
        let discard = hand.min { lhs, rhs in discardCost(lhs) < discardCost(rhs) } ?? hand[0]
        return (discard, [])
    }

    private static func discardCost(_ card: Int) -> Int {
        var cost = NeapolitanCards.rank(card) * 2
        if NeapolitanCards.suit(card) == .denari { cost += 12 }
        if NeapolitanCards.rank(card) == 7 { cost += 20 }
        return cost
    }

    /// Briscola: ogni carta in mano e' legale. Se si risponde a una presa che vale punti, la si vince con la
    /// carta piu' economica possibile; altrimenti si "liscia" la carta che vale meno (meglio non di briscola).
    static func chooseBriscolaCard(hand: [Int], trick: [Int], trump: NeapolitanCards.Suit) -> Int? {
        guard !hand.isEmpty else { return nil }
        func cost(_ card: Int) -> Int {
            NeapolitanCards.briscolaPoints(card) * 10 + NeapolitanCards.briscolaStrength(card)
                + (NeapolitanCards.suit(card) == trump ? 100 : 0)
        }
        if let lead = trick.first {
            let winners = hand.filter { BriscolaGame.followerWins(lead: lead, follow: $0, trump: trump) }
            if NeapolitanCards.briscolaPoints(lead) > 0, let cheapest = winners.min(by: { cost($0) < cost($1) }) {
                return cheapest
            }
        }
        return hand.min { cost($0) < cost($1) }
    }

    // MARK: Tris

    private func playTicTacToe(_ move: GameMove, state: inout GameSessionState) -> [GameMove] {
        guard var board = state.ticTacToe, move.sequence > state.sequence else { return [] }
        // Chi invita (noi) gioca con la X, il passeggero virtuale con la O.
        guard (try? board.play(Int(move.cell), as: .x)) != nil else { return [] }
        state.sequence = move.sequence
        state.ticTacToe = board
        guard !board.isFinished, let cell = Self.chooseTicTacToeCell(board, mark: .o) else {
            state.status = .finished
            return []
        }
        try? board.play(cell, as: .o)
        state.ticTacToe = board
        state.sequence += 1
        var reply = GameMove()
        reply.gameID = state.gameID
        reply.kind = .ticTacToe
        reply.action = .move
        reply.sequence = state.sequence
        reply.cell = UInt32(cell)
        reply.boardSnapshot = board.snapshot
        if board.winner == .o {
            reply.result = 1
            state.status = .finished
        } else if board.isDraw {
            reply.result = 3
            state.status = .finished
        }
        return [reply]
    }

    /// Vince se puo', altrimenti blocca, poi centro, angoli e lati.
    static func chooseTicTacToeCell(_ board: TicTacToe, mark: TicTacToe.Mark) -> Int? {
        let free = (0..<9).filter { board.board[$0] == .empty }
        guard !free.isEmpty else { return nil }
        for candidate in [mark, mark.opponent] {
            for cell in free {
                var copy = board.board
                copy[cell] = candidate
                if Self.lines.contains(where: { line in line.allSatisfy { copy[$0] == candidate } }) { return cell }
            }
        }
        for cell in [4, 0, 2, 6, 8, 1, 3, 5, 7] where free.contains(cell) { return cell }
        return free.first
    }

    private static let lines = [[0, 1, 2], [3, 4, 5], [6, 7, 8], [0, 3, 6], [1, 4, 7], [2, 5, 8], [0, 4, 8], [2, 4, 6]]
}
