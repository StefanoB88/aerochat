import Foundation

/// Testi delle partite a carte condivisi da bolla e tavolo (stesse frasi dell'app Android).
extension GameSessionState {

    /// Nome di una parte: il giocatore (2 giocatori) o la coppia (Scopone, "Anna e Bruno").
    func sideName(_ side: Int) -> String {
        if gameKind == .scopone {
            return String(localized: "\(name(ofSeat: side)) e \(name(ofSeat: side + 2))")
        }
        return name(ofSeat: side)
    }

    /// "Anna vs Bruno" oppure "Anna e Carlo vs Bruno e Dario".
    var playersLine: String {
        String(localized: "\(sideName(0)) vs \(sideName(1))")
    }

    /// Punteggio corrente per parte: punti partita (Scopa/Scopone) o punti carta (Briscola).
    var cardScores: [Int]? {
        if let scopa { return scopa.scores }
        if let briscola { return briscola.points }
        return nil
    }

    var cardStatusText: String {
        switch status {
        case .invited:
            if iAmInviter {
                if gameKind == .scopone {
                    let accepted = acceptedSeats?.count ?? 0
                    return String(localized: "Invito inviato · \(accepted)/3 hanno accettato")
                }
                return String(localized: "Invito inviato")
            }
            if mySeat == nil { return String(localized: "Invito tra i membri del gruppo") }
            if let mySeat, acceptedSeats?.contains(mySeat) == true {
                return String(localized: "In attesa degli altri giocatori…")
            }
            return String(localized: "Invito ricevuto")
        case .active:
            guard let turn = turnSeat else { return String(localized: "Distribuzione delle carte…") }
            if turn == mySeat { return String(localized: "Tocca a te") }
            return String(localized: "Tocca a \(name(ofSeat: turn))")
        case .finished:
            if mySeat == nil {
                if let winner = cardWinnerSide { return String(localized: "Vince \(sideName(winner))") }
                return String(localized: "Pareggio")
            }
            switch outcome {
            case .iWon: return String(localized: "Hai vinto!")
            case .iLost: return String(localized: "Hai perso")
            case .draw: return String(localized: "Pareggio")
            case .ongoing: return String(localized: "Partita conclusa")
            }
        }
    }

    /// Chi ha abbandonato (riga aggiuntiva a partita finita).
    var resignText: String? {
        guard let resignedSeat else { return nil }
        if resignedSeat == mySeat { return String(localized: "Hai abbandonato la partita") }
        return String(localized: "\(name(ofSeat: resignedSeat)) ha abbandonato la partita")
    }

    /// Ultima giocata: "Marco ha preso 3♦ + 4♣ con 7♦ — Scopa!", "Hai calato 5♠", "Anna prende la mano (11 punti)".
    var lastMoveText: String? {
        if let play = scopa?.lastPlay {
            let card = NeapolitanCards.shortName(play.card)
            let isMe = play.seat == mySeat
            var text: String
            if play.captured.isEmpty {
                text = isMe ? String(localized: "Hai calato \(card)")
                            : String(localized: "\(name(ofSeat: play.seat)) ha calato \(card)")
            } else {
                let taken = play.captured.map(NeapolitanCards.shortName).joined(separator: " + ")
                text = isMe ? String(localized: "Hai preso \(taken) con \(card)")
                            : String(localized: "\(name(ofSeat: play.seat)) ha preso \(taken) con \(card)")
            }
            if play.scopa { text += " — " + String(localized: "Scopa!") }
            return text
        }
        if let trick = briscola?.lastTrick {
            let cards = trick.cards.map(NeapolitanCards.shortName).joined(separator: " · ")
            if trick.winner == mySeat {
                return String(localized: "Prendi tu \(cards) (\(trick.points) punti)")
            }
            return String(localized: "\(name(ofSeat: trick.winner)) prende \(cards) (\(trick.points) punti)")
        }
        return nil
    }
}
