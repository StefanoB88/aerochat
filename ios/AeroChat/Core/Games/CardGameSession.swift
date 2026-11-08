import Foundation

extension GameKind {
    /// Giochi di carte napoletane (Scopa, Scopone scientifico, Briscola).
    var isCardGame: Bool { self == .scopa || self == .scopone || self == .briscola }
    /// Giocatori al tavolo: 4 nello Scopone, 2 altrimenti.
    var cardPlayerCount: Int { self == .scopone ? 4 : 2 }
}

/// DEAL o MOVE di una partita a carte ricevuto fuori ordine: resta nello stato finche' non arrivano
/// quelli mancanti (la rete store-and-forward garantisce che prima o poi arrivino).
struct BufferedCardMove: Codable, Sendable, Equatable {
    var sequence: UInt32
    /// `GameAction.rawValue` (solo DEAL o MOVE).
    var action: Int
    var senderNodeID: Data
    /// DEAL: posto del mazziere. MOVE: id della carta giocata.
    var cell: Int
    /// DEAL: le 40 carte nell'ordine di distribuzione.
    var deck: [Int]
    /// MOVE (Scopa/Scopone): carte prese dal tavolo.
    var captured: [Int]

    init(sequence: UInt32, action: GameAction, senderNodeID: Data, cell: Int, deck: [Int] = [], captured: [Int] = []) {
        self.sequence = sequence
        self.action = action.rawValue
        self.senderNodeID = senderNodeID
        self.cell = cell
        self.deck = deck
        self.captured = captured
    }

    init(_ move: GameMove, senderNodeID: Data) {
        self.init(sequence: move.sequence, action: move.action, senderNodeID: senderNodeID, cell: Int(move.cell),
                  deck: move.deck.map(Int.init), captured: move.captured.map(Int.init))
    }

    var gameAction: GameAction { GameAction(rawValue: action) ?? .unspecified }
}

/// Logica pura delle partite a carte sopra `GameSessionState`: applicazione in ordine di DEAL/MOVE,
/// buffer delle mosse fuori ordine, validazione di mittente, turno, carta e presa. `GameService` si
/// occupa solo di rete e persistenza; i test usano direttamente questi metodi.
extension GameSessionState {

    enum CardFailure: Error, Equatable {
        case notAPlayer
        case wrongSender
        case invalidMove
        case notReady
    }

    var isCardGame: Bool { gameKind.isCardGame }
    var playerCount: Int { gameKind.cardPlayerCount }
    var isSpectator: Bool { isCardGame && mySeat == nil }

    func seat(of nodeID: Data) -> Int? { seats?.firstIndex(of: nodeID) }

    func name(ofSeat seat: Int) -> String {
        guard let names = seatNames, names.indices.contains(seat) else {
            return String(localized: "Giocatore \(seat + 1)")
        }
        return names[seat]
    }

    /// Posto di turno durante una mano (nil tra una mano e l'altra o a partita finita).
    var turnSeat: Int? {
        guard status == .active else { return nil }
        if let scopa, scopa.phase == .playing { return scopa.turn }
        if let briscola, briscola.phase == .playing { return briscola.turn }
        return nil
    }

    /// Mazziere atteso per il prossimo DEAL (nil se ora non serve distribuire).
    var expectedDealer: Int? {
        guard status != .finished else { return nil }
        switch gameKind {
        case .scopa, .scopone:
            guard let scopa else { return 0 }
            return scopa.phase == .handOver ? scopa.nextDealer : nil
        case .briscola:
            return briscola == nil ? 0 : nil
        default:
            return nil
        }
    }

    /// Tocca a noi distribuire: prima mano (chi invita, quando tutti hanno accettato) o mano successiva.
    var shouldDeal: Bool {
        guard let mySeat, let dealer = expectedDealer, dealer == mySeat else { return false }
        if scopa == nil && briscola == nil {
            guard iAmInviter else { return false }
            if gameKind == .scopone {
                return Set(acceptedSeats ?? []).isSuperset(of: [1, 2, 3])
            }
            return status == .active
        }
        return true
    }

    /// Applica subito il DEAL/MOVE con `sequence == self.sequence + 1`, mette nel buffer quelli piu' avanti
    /// (entro `maxGap`) e poi svuota il buffer finche' le sequenze sono contigue. Restituisce `true` se lo
    /// stato e' cambiato. Duplicati e sequenze vecchie sono ignorati.
    @discardableResult
    mutating func receiveCardMove(_ move: BufferedCardMove, maxGap: UInt32) -> Bool {
        guard status != .finished, move.sequence > sequence, move.sequence - sequence <= maxGap else { return false }
        var changed = false
        if move.sequence == sequence + 1 {
            changed = (try? applyCardMove(move)) != nil
        } else {
            var pending = pendingMoves ?? []
            guard !pending.contains(where: { $0.sequence == move.sequence }) else { return false }
            pending.append(move)
            pending.sort { $0.sequence < $1.sequence }
            pendingMoves = pending
            changed = true
        }
        if drainPendingMoves() { changed = true }
        return changed
    }

    /// Applica le mosse in buffer che ora sono in sequenza.
    @discardableResult
    mutating func drainPendingMoves() -> Bool {
        var changed = false
        while var pending = pendingMoves, !pending.isEmpty {
            pending.removeAll { $0.sequence <= sequence }
            guard let index = pending.firstIndex(where: { $0.sequence == sequence + 1 }) else {
                pendingMoves = pending.isEmpty ? nil : pending
                break
            }
            let next = pending.remove(at: index)
            pendingMoves = pending.isEmpty ? nil : pending
            if (try? applyCardMove(next)) != nil {
                changed = true
            } else {
                // Mossa successiva non valida: resta scartata, le altre aspettano.
                break
            }
        }
        if status == .finished { pendingMoves = nil }
        return changed
    }

    /// Applica un DEAL/MOVE gia' in sequenza (anche le nostre mosse passano da qui). Valida il mittente
    /// (mazziere atteso / posto di turno), la carta e la presa; in caso di errore lo stato non cambia.
    mutating func applyCardMove(_ move: BufferedCardMove) throws {
        guard status != .finished else { throw CardFailure.invalidMove }
        guard let senderSeat = seat(of: move.senderNodeID) else { throw CardFailure.notAPlayer }
        var updated = self
        switch move.gameAction {
        case .deal:
            guard let dealer = expectedDealer, dealer == senderSeat, move.cell == dealer else { throw CardFailure.wrongSender }
            guard NeapolitanCards.isValidDeck(move.deck) else { throw CardFailure.invalidMove }
            switch gameKind {
            case .scopa, .scopone:
                if var game = updated.scopa {
                    try game.deal(deck: move.deck, dealer: dealer)
                    updated.scopa = game
                } else {
                    updated.scopa = try ScopaGame(playerCount: playerCount, dealer: dealer, deck: move.deck)
                }
            case .briscola:
                updated.briscola = try BriscolaGame(dealer: dealer, deck: move.deck)
            default:
                throw CardFailure.invalidMove
            }
            updated.status = .active
        case .move:
            guard updated.status == .active, let turn = updated.turnSeat else { throw CardFailure.notReady }
            guard turn == senderSeat else { throw CardFailure.wrongSender }
            guard NeapolitanCards.isValid(move.cell), move.captured.allSatisfy(NeapolitanCards.isValid) else {
                throw CardFailure.invalidMove
            }
            switch gameKind {
            case .scopa, .scopone:
                guard var game = updated.scopa else { throw CardFailure.notReady }
                do { try game.play(seat: senderSeat, card: move.cell, captured: move.captured) } catch { throw CardFailure.invalidMove }
                updated.scopa = game
            case .briscola:
                guard var game = updated.briscola, move.captured.isEmpty else { throw CardFailure.invalidMove }
                do { try game.play(seat: senderSeat, card: move.cell) } catch { throw CardFailure.invalidMove }
                updated.briscola = game
            default:
                throw CardFailure.invalidMove
            }
        default:
            throw CardFailure.invalidMove
        }
        updated.sequence = move.sequence
        updated.refreshCardOutcome()
        self = updated
    }

    /// Abbandono di un giocatore: la partita finisce per tutti e la sua parte perde.
    mutating func applyCardResign(seat resigning: Int) {
        guard status != .finished else { return }
        resignedSeat = resigning
        status = .finished
        pendingMoves = nil
        myTurn = false
        if let mySeat {
            outcome = mySeat % 2 == resigning % 2 ? .iLost : .iWon
        }
    }

    /// Parte vincitrice a partita finita (nil = pareggio o non ancora finita).
    var cardWinnerSide: Int? {
        if let resignedSeat { return 1 - resignedSeat % 2 }
        if let scopa, scopa.isMatchOver { return scopa.winnerSide }
        if let briscola, briscola.isFinished { return briscola.winnerSeat }
        return nil
    }

    var isCardMatchOver: Bool {
        resignedSeat != nil || scopa?.isMatchOver == true || briscola?.isFinished == true
    }

    /// Aggiorna `myTurn`, `status` e `outcome` dopo ogni mossa applicata.
    mutating func refreshCardOutcome() {
        if isCardMatchOver {
            status = .finished
            pendingMoves = nil
            if let mySeat {
                if let winner = cardWinnerSide {
                    outcome = winner == mySeat % 2 ? .iWon : .iLost
                } else {
                    outcome = .draw
                }
            }
        }
        myTurn = mySeat != nil && turnSeat == mySeat
    }

    /// Campo `result` dell'ultima MOVE (informativo): 1 vince la parte del mittente, 2 perde, 3 pareggio.
    func cardResult(forSenderSeat seat: Int) -> UInt32 {
        guard isCardMatchOver else { return 0 }
        guard let winner = cardWinnerSide else { return 3 }
        return winner == seat % 2 ? 1 : 2
    }
}
