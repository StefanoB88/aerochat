import Testing
import Foundation
@testable import AeroChat

/// Vettori condivisi con Android (CARD_GAMES_SPEC.md §8): stessi numeri attesi su entrambe le piattaforme.
@Suite("Carte napoletane – motori Scopa, Scopone, Briscola (vettori §8)")
struct CardEngineTests {

    static let identityDeck = Array(0..<40)

    // MARK: Helper (§8: stato costruito da un `deck` esplicito)

    static func scopaState(players: Int = 2, deck: [Int] = identityDeck, dealer: Int = 0) throws -> ScopaGame {
        try ScopaGame(playerCount: players, dealer: dealer, deck: deck)
    }

    static func briscolaState(deck: [Int] = identityDeck, dealer: Int = 0) throws -> BriscolaGame {
        try BriscolaGame(dealer: dealer, deck: deck)
    }

    /// Bot deterministico (§8.7): gioca sempre la prima carta della mano (ordine di distribuzione) e
    /// sceglie la prima presa legale in ordine canonico. Gioca una mano intera.
    static func scriptedHand(_ game: inout ScopaGame) throws -> [ScopaGame.Play] {
        var plays: [ScopaGame.Play] = []
        while game.phase == .playing {
            let seat = game.turn
            let card = try #require(game.hands[seat].first)
            let captured = game.captureOptions(for: card).first ?? []
            plays.append(try game.play(seat: seat, card: card, captured: captured))
        }
        return plays
    }

    // MARK: §8.1

    @Test("§8.1 Id carta: 7 di denari = 6, Re di bastoni = 39, Asso di coppe = 10")
    func cardIDs() {
        #expect(NeapolitanCards.id(suit: .denari, rank: 7) == 6)
        #expect(NeapolitanCards.id(suit: .bastoni, rank: 10) == 39)
        #expect(NeapolitanCards.id(suit: .coppe, rank: 1) == 10)
        #expect(NeapolitanCards.suit(39) == .bastoni && NeapolitanCards.rank(39) == 10)
        #expect(NeapolitanCards.settebello == 6)
        #expect(NeapolitanCards.shortName(6) == "7♦")
        #expect(NeapolitanCards.cornerLabel(37) == "F" && NeapolitanCards.cornerLabel(38) == "C" && NeapolitanCards.cornerLabel(30) == "A")
        #expect((0..<40).map(NeapolitanCards.briscolaPoints).reduce(0, +) == 120)
    }

    // MARK: §8.2

    @Test("§8.2 Prese: pari valore batte le combinazioni, ordine canonico delle combinazioni")
    func captureOptions() {
        let table = [1, 34, 22, 13]
        #expect(ScopaGame.captureOptions(card: 4, table: table) == [[34]])
        #expect(ScopaGame.captureOptions(card: 26, table: table) == [[1, 34], [13, 22]])
        #expect(ScopaGame.captureOptions(card: 9, table: table) == [[1, 22, 34]])
        #expect(ScopaGame.captureOptions(card: 20, table: table) == [])
        // Piu' carte di pari valore: una sola a scelta.
        #expect(ScopaGame.captureOptions(card: 4, table: [14, 34, 1]) == [[14], [34]])
        // Validazione come insieme (ordine indifferente), vuoto solo se non c'e' presa.
        #expect(ScopaGame.isLegalCapture([34, 1], card: 26, table: table))
        #expect(!ScopaGame.isLegalCapture([], card: 26, table: table))
        #expect(!ScopaGame.isLegalCapture([1, 22], card: 26, table: table))
        #expect(ScopaGame.isLegalCapture([], card: 20, table: table))
    }

    // MARK: §8.3

    @Test("§8.3 L'ultima giocata della mano che svuota il tavolo non e' scopa")
    func lastPlayIsNotScopa() throws {
        // Mazzo scelto perche' il bot chiuda la mano prendendo tutte le carte rimaste in tavola.
        let deck = [26, 36, 25, 15, 18, 39, 1, 2, 6, 28, 12, 17, 21, 4, 11, 30, 16, 32, 34, 7,
                    20, 27, 19, 38, 22, 13, 3, 5, 8, 0, 9, 14, 31, 10, 23, 33, 29, 24, 35, 37]
        var game = try Self.scopaState(deck: deck)
        let plays = try Self.scriptedHand(&game)
        let last = try #require(plays.last)
        #expect(plays.count == 36)
        #expect(last.card == 37 && last.captured == [22, 24])
        #expect(!last.scopa)
        #expect(game.table.isEmpty)
        #expect(game.lastHandScore?.scope == [0, 0])
        #expect(game.phase == .handOver)

        // Controprova: a meta' mano una presa che svuota il tavolo vale scopa.
        var identity = try Self.scopaState()
        let identityPlays = try Self.scriptedHand(&identity)
        #expect(identityPlays.dropLast().contains { $0.scopa })
    }

    // MARK: §8.4

    @Test("§8.4 Primiera: 7♦ 7♣ 6♠ A♥ = 76; un seme mancante = 0")
    func primiera() {
        #expect(NeapolitanCards.primiera(of: [6, 36, 25, 10]) == 76)
        #expect(NeapolitanCards.primiera(of: [6, 16, 26, 0, 1]) == 0)
        let score = ScopaGame.score(captures: [[6, 36, 25, 10], [16, 26, 0]], scope: [0, 0])
        #expect(score.primieraValue == [76, 0])
        #expect(score.primiera == [1, 0])
        // Entrambe non valide: nessuno.
        #expect(ScopaGame.score(captures: [[0], [1]], scope: [0, 0]).primiera == [0, 0])
    }

    // MARK: §8.5

    @Test("§8.5 Presa della Briscola: briscola, seme di uscita, seme diverso")
    func briscolaTrick() {
        #expect(BriscolaGame.followerWins(lead: 20, follow: 11, trump: .coppe))
        #expect(!BriscolaGame.followerWins(lead: 2, follow: 9, trump: .coppe))
        #expect(!BriscolaGame.followerWins(lead: 9, follow: 20, trump: .coppe))
        // Due briscole: vince la piu' forte (Asso > 3).
        #expect(BriscolaGame.followerWins(lead: 12, follow: 10, trump: .coppe))
    }

    // MARK: §8.6

    @Test("§8.6 Distribuzione da mazzo identita' con mazziere 0")
    func dealing() throws {
        let scopa = try Self.scopaState()
        #expect(scopa.hands[1] == [0, 2, 4])
        #expect(scopa.hands[0] == [1, 3, 5])
        #expect(scopa.table == [6, 7, 8, 9])
        #expect(scopa.turn == 1)

        let scopone = try Self.scopaState(players: 4)
        #expect(scopone.hands[1] == Array(stride(from: 0, through: 36, by: 4)))
        #expect(scopone.hands[2] == Array(stride(from: 1, through: 37, by: 4)))
        #expect(scopone.hands[3] == Array(stride(from: 2, through: 38, by: 4)))
        #expect(scopone.hands[0] == Array(stride(from: 3, through: 39, by: 4)))
        #expect(scopone.table.isEmpty)
        #expect(scopone.turn == 1)

        let briscola = try Self.briscolaState()
        #expect(briscola.hands[1] == [0, 2, 4])
        #expect(briscola.hands[0] == [1, 3, 5])
        #expect(briscola.briscolaCard == 6)
        #expect(briscola.trumpSuit == .denari)
        #expect(briscola.stock == Array(7...39) + [6])
        #expect(briscola.turn == 1)

        // Mazziere 1: si parte dal posto 0.
        let rotated = try Self.scopaState(dealer: 1)
        #expect(rotated.hands[0] == [0, 2, 4] && rotated.hands[1] == [1, 3, 5])
        #expect(throws: ScopaGame.Failure.invalidDeck) { try ScopaGame(playerCount: 2, dealer: 0, deck: Array(0..<39) + [0]) }
    }

    // MARK: §8.7

    @Test("§8.7 Mano scriptata di Scopa: punteggi per parte")
    func scriptedScopa() throws {
        var game = try Self.scopaState()
        let plays = try Self.scriptedHand(&game)
        #expect(plays.count == 36)
        let score = try #require(game.lastHandScore)
        #expect(score.cards == [14, 26])
        #expect(score.denari == [4, 6])
        #expect(score.settebello == [0, 1])
        #expect(score.primieraValue == [72, 84])
        #expect(score.primiera == [0, 1])
        #expect(score.scope == [1, 1])
        #expect(score.total == [1, 5])
        #expect(game.scores == [1, 5])
        #expect(game.phase == .handOver)
        #expect(game.nextDealer == 1)
    }

    @Test("§8.7 Mano scriptata di Scopone scientifico: punteggi per squadra")
    func scriptedScopone() throws {
        var game = try Self.scopaState(players: 4)
        let plays = try Self.scriptedHand(&game)
        #expect(plays.count == 40)
        let score = try #require(game.lastHandScore)
        #expect(score.cards == [12, 28])
        #expect(score.denari == [3, 7])
        #expect(score.settebello == [0, 1])
        #expect(score.primieraValue == [72, 84])
        #expect(score.primiera == [0, 1])
        #expect(score.scope == [1, 2])
        #expect(score.total == [1, 6])
        #expect(game.scores == [1, 6])
    }

    @Test("§8.7 Mano scriptata di Briscola: punti per giocatore")
    func scriptedBriscola() throws {
        var game = try Self.briscolaState()
        var tricks = 0
        while !game.isFinished {
            let seat = game.turn
            if try game.play(seat: seat, card: try #require(game.hands[seat].first)) != nil { tricks += 1 }
        }
        #expect(tricks == 20)
        #expect(game.points == [59, 61])
        #expect(game.won.map(\.count) == [18, 22])
        #expect(game.winnerSeat == 1)
    }

    // MARK: Regole aggiuntive

    @Test("Scopa: turno, carta in mano, presa obbligatoria e fine partita a 11")
    func scopaValidationAndMatch() throws {
        var game = try Self.scopaState()
        #expect(throws: ScopaGame.Failure.notYourTurn) { try game.play(seat: 0, card: 1, captured: []) }
        #expect(throws: ScopaGame.Failure.cardNotInHand) { try game.play(seat: 1, card: 1, captured: []) }
        // Asso di denari (0): nessuna presa possibile sul tavolo 7-8-9-R di denari.
        #expect(throws: ScopaGame.Failure.illegalCapture) { try game.play(seat: 1, card: 0, captured: [6]) }
        try game.play(seat: 1, card: 0, captured: [])
        #expect(game.table == [6, 7, 8, 9, 0])

        // Partita completa con mazzi casuali: si arriva a un vincitore e il mazziere ruota.
        var match = try Self.scopaState(deck: NeapolitanCards.shuffledDeck())
        var hands = 0
        while !match.isMatchOver {
            _ = try Self.scriptedHand(&match)
            hands += 1
            let score = try #require(match.lastHandScore)
            #expect(score.cards.reduce(0, +) == 40)
            if match.phase == .handOver {
                let expectedDealer = match.nextDealer
                #expect(throws: ScopaGame.Failure.wrongDealer) { try match.deal(deck: NeapolitanCards.shuffledDeck(), dealer: 1 - expectedDealer) }
                try match.deal(deck: NeapolitanCards.shuffledDeck(), dealer: expectedDealer)
                #expect(match.turn == (expectedDealer + 1) % 2)
            }
            #expect(hands < 40)
        }
        let winner = try #require(match.winnerSide)
        #expect(match.scores[winner] >= ScopaGame.targetScore)
        #expect(match.scores[winner] > match.scores[1 - winner])
    }

    @Test("Briscola: pesca prima chi prende, briscola scoperta pescata per ultima, totale 120")
    func briscolaDrawing() throws {
        var game = try Self.briscolaState()
        // Posto 1 apre con A♦ (0), posto 0 risponde 2♦ (1): vince l'Asso, che pesca per primo deck[7] = 7.
        try game.play(seat: 1, card: 0)
        let trick = try #require(try game.play(seat: 0, card: 1))
        #expect(trick.winner == 1 && trick.points == 11)
        #expect(game.hands[1].last == 7 && game.hands[0].last == 8)
        #expect(game.turn == 1)
        #expect(throws: BriscolaGame.Failure.notYourTurn) { try game.play(seat: 0, card: 3) }

        var random = try Self.briscolaState(deck: NeapolitanCards.shuffledDeck(), dealer: 1)
        let briscola = random.briscolaCard
        var lastDrawn: Int?
        while !random.isFinished {
            let before = random.stock
            try random.play(seat: random.turn, card: try #require(random.hands[random.turn].first))
            if before.count > 0, random.stock.isEmpty { lastDrawn = before.last }
        }
        #expect(lastDrawn == briscola)
        #expect(random.points.reduce(0, +) == 120)
        #expect(random.won.map(\.count).reduce(0, +) == 40)
    }
}

// MARK: - Stato di sessione (sequenze, buffer, validazione del mittente)

@Suite("Carte – sessione: ordine delle mosse, buffer e validazione")
struct CardSessionTests {

    static let nodes: [Data] = (1...4).map { Data(repeating: UInt8($0), count: 8) }

    static func sconeSession(mySeat: Int?) -> GameSessionState {
        var state = GameSessionState(gameID: "00", kind: GameKind.scopone.rawValue, conversationID: "g",
                                     opponentNodeID: nodes[0], iAmInviter: mySeat == 0)
        state.seats = nodes
        state.mySeat = mySeat
        state.acceptedSeats = []
        return state
    }

    static func move(_ sequence: UInt32, from seat: Int, card: Int, captured: [Int] = []) -> BufferedCardMove {
        BufferedCardMove(sequence: sequence, action: .move, senderNodeID: nodes[seat], cell: card, captured: captured)
    }

    @Test("DEAL e MOVE fuori ordine: buffer e applicazione in sequenza; duplicati e mittenti errati ignorati")
    func outOfOrderBuffering() throws {
        var state = Self.sconeSession(mySeat: 3)
        var applied: Bool
        let deck = Array(0..<40)
        // Il mazziere della prima mano deve essere il posto 0.
        let wrongDeal = BufferedCardMove(sequence: 1, action: .deal, senderNodeID: Self.nodes[1], cell: 1, deck: deck)
        applied = state.receiveCardMove(wrongDeal, maxGap: 16)
        #expect(!applied)
        #expect(state.scopa == nil)

        // Arrivano prima le mosse 2 e 3 (posti 1 e 2), poi il DEAL 1.
        applied = state.receiveCardMove(Self.move(3, from: 2, card: 1), maxGap: 16)
        #expect(applied)
        applied = state.receiveCardMove(Self.move(2, from: 1, card: 0), maxGap: 16)
        #expect(applied)
        #expect(state.pendingMoves?.count == 2)
        applied = state.receiveCardMove(Self.move(2, from: 1, card: 0), maxGap: 16)
        #expect(!applied, "duplicato")
        applied = state.receiveCardMove(Self.move(40, from: 1, card: 0), maxGap: 16)
        #expect(!applied, "salto troppo grande")
        let deal = BufferedCardMove(sequence: 1, action: .deal, senderNodeID: Self.nodes[0], cell: 0, deck: deck)
        applied = state.receiveCardMove(deal, maxGap: 16)
        #expect(applied)
        #expect(state.sequence == 3)
        #expect(state.pendingMoves == nil)
        #expect(state.status == .active)
        #expect(state.scopa?.table == [0, 1])
        #expect(state.myTurn)

        // Mossa dal posto sbagliato o carta non in mano: ignorata, sequenza invariata.
        applied = state.receiveCardMove(Self.move(4, from: 0, card: 3), maxGap: 16)
        #expect(!applied)
        applied = state.receiveCardMove(Self.move(4, from: 3, card: 3), maxGap: 16)
        #expect(!applied)
        #expect(state.sequence == 3)
        // Posto 3 (carte 2, 6, 10…) gioca il 3♦: prende A♦ + 2♦ e svuota il tavolo → scopa della parte 1.
        applied = state.receiveCardMove(Self.move(4, from: 3, card: 2, captured: [0, 1]), maxGap: 16)
        #expect(applied)
        #expect(state.sequence == 4)
        #expect(state.scopa?.table.isEmpty == true)
        #expect(state.scopa?.scope == [0, 1])
    }

    @Test("Abbandono nello Scopone: perde la squadra di chi abbandona; spettatore senza esito")
    func resignTeams() {
        var partner = Self.sconeSession(mySeat: 0)
        partner.applyCardResign(seat: 2)
        #expect(partner.status == .finished && partner.outcome == .iLost)
        #expect(partner.cardWinnerSide == 1)

        var opponent = Self.sconeSession(mySeat: 1)
        opponent.applyCardResign(seat: 2)
        #expect(opponent.outcome == .iWon)

        var spectator = Self.sconeSession(mySeat: nil)
        spectator.applyCardResign(seat: 2)
        #expect(spectator.status == .finished && spectator.outcome == .ongoing)
        #expect(spectator.isSpectator)
    }

    @Test("Chi distribuisce: prima mano chi invita (dopo tutti gli ACCEPT), poi il mazziere successivo")
    func dealerSelection() throws {
        var inviter = Self.sconeSession(mySeat: 0)
        #expect(!inviter.shouldDeal)
        inviter.acceptedSeats = [1, 2]
        #expect(!inviter.shouldDeal)
        inviter.acceptedSeats = [1, 2, 3]
        #expect(inviter.shouldDeal)

        var invitee = Self.sconeSession(mySeat: 1)
        invitee.acceptedSeats = [1, 2, 3]
        #expect(!invitee.shouldDeal)
        try invitee.applyCardMove(BufferedCardMove(sequence: 1, action: .deal, senderNodeID: Self.nodes[0], cell: 0, deck: Array(0..<40)))
        #expect(!invitee.shouldDeal)
        #expect(invitee.expectedDealer == nil)
    }

    @Test("GameSessionState con le carte: JSON andata e ritorno")
    func codableRoundTrip() throws {
        var state = Self.sconeSession(mySeat: 2)
        state.seatNames = ["Anna", "Bruno", "Carla", "Dario"]
        _ = state.receiveCardMove(BufferedCardMove(sequence: 1, action: .deal, senderNodeID: Self.nodes[0], cell: 0,
                                                   deck: NeapolitanCards.shuffledDeck()), maxGap: 16)
        _ = state.receiveCardMove(Self.move(5, from: 0, card: 0), maxGap: 16)
        let decoded = try GameSessionState.decode(state.encoded())
        #expect(decoded == state)
        #expect(decoded.pendingMoves?.count == 1)
        #expect(decoded.sideName(0) == "Anna e Carla")
    }

    @Test("GameMove: nuovi campi 9-11 e azione DEAL sul filo")
    func wireFields() throws {
        var move = GameMove()
        move.gameID = "abc"
        move.kind = .scopone
        move.action = .deal
        move.sequence = 1
        move.deck = Data((0..<40).map { UInt8($0) })
        move.captured = Data([1, 34])
        move.players = Self.nodes
        let bytes = try MeshWire.serialize(move)
        // Tag protobuf: kind (2) = 4, action (3) = 6, deck (9) = 0x4A, captured (10) = 0x52, players (11) = 0x5A.
        #expect(bytes.contains(0x4A) && bytes.contains(0x52) && bytes.contains(0x5A))
        let parsed = try MeshWire.parseGameMove(bytes)
        #expect(parsed == move)
        #expect(parsed.kind.rawValue == 4 && parsed.action.rawValue == 6)
        #expect(GameKind(rawValue: 3) == .scopa && GameKind(rawValue: 5) == .briscola)
        // Un valore sconosciuto resta UNRECOGNIZED (come accade alle versioni vecchie con SCOPA/DEAL).
        #expect(GameKind(rawValue: 9) == .UNRECOGNIZED(9))
    }
}

// MARK: - Mesh

@Suite("Carte sulla mesh – Scopa, Briscola, Scopone a 4 con spettatore", .serialized)
struct CardGamesMeshTests {

    struct Player: Sendable {
        let node: TestNode
        let games: GameService
    }

    static func makePlayers(_ names: [String]) async throws -> [Player] {
        var players: [Player] = []
        for name in names {
            let node = try await TestNode.make(name)
            let games = GameService(router: node.router, database: node.database, identity: node.identity, nickname: name)
            await games.start()
            players.append(Player(node: node, games: games))
        }
        return players
    }

    /// Tutti i nodi hanno applicato le stesse mosse e (se la partita non e' finita) qualcuno ha il turno.
    static func synced(_ services: [GameService], gameID: String) async -> Bool {
        var states: [GameSessionState] = []
        for service in services {
            guard let state = try? await service.session(gameID: gameID) else { return false }
            states.append(state)
        }
        guard let first = states.first else { return false }
        for state in states {
            guard state.sequence == first.sequence, state.scopa == first.scopa, state.briscola == first.briscola,
                  state.status == first.status, state.pendingMoves == nil else { return false }
        }
        return first.status == .finished || (first.status == .active && first.turnSeat != nil)
    }

    /// Bot del §8.7 sulla mesh: chi ha il turno gioca la prima carta e la prima presa canonica.
    @discardableResult
    static func drive(_ services: [GameService], gameID: String, maxMoves: Int = 600,
                      stopWhen: @Sendable (GameSessionState) -> Bool = { _ in false }) async throws -> Int {
        var moves = 0
        while moves < maxMoves {
            #expect(await eventually(timeout: 10) { await synced(services, gameID: gameID) })
            var states: [GameSessionState] = []
            for service in services { states.append(try await service.session(gameID: gameID)) }
            if states[0].status == .finished || stopWhen(states[0]) { return moves }
            let turnIndex = states.firstIndex { $0.myTurn }
            let index = try #require(turnIndex)
            let state = states[index]
            let seat = try #require(state.mySeat)
            #expect(state.turnSeat == seat)
            #expect(states.filter { $0.myTurn }.count == 1)
            let card: Int
            var captured: [Int] = []
            if let scopa = state.scopa {
                card = try #require(scopa.hands[seat].first)
                captured = scopa.captureOptions(for: card).first ?? []
            } else {
                card = try #require(state.briscola?.hands[seat].first)
            }
            try await services[index].playCard(gameID: gameID, card: card, captured: captured)
            moves += 1
        }
        Issue.record("partita non conclusa entro \(maxMoves) mosse")
        return moves
    }

    @Test("Scopa 1-a-1: invito, accettazione, DEAL automatico, mani successive fino a 11, esito speculare")
    func scopaFullMatch() async throws {
        let players = try await Self.makePlayers(["Alice", "Bob"])
        let (alice, bob) = (players[0], players[1])
        try await TestNode.pair(alice.node, bob.node)
        await FakeMeshNetwork.connect(alice.node.transport, bob.node.transport)

        let conversation = try await alice.node.directConversation(with: bob.node)
        let invite = try await alice.games.invite(kind: .scopa, in: conversation)
        #expect(invite.seats == [alice.node.nodeID, bob.node.nodeID] && invite.mySeat == 0)
        #expect(await eventually { (try? await bob.games.session(gameID: invite.gameID)) != nil })
        let bobInvite = try await bob.games.session(gameID: invite.gameID)
        #expect(bobInvite.mySeat == 1 && bobInvite.status == .invited)
        #expect(bobInvite.seatNames == ["Alice", "Bob"])

        // Prima dell'ACCEPT nessuno puo' giocare.
        await #expect(throws: GameService.Failure.notYourTurn) { try await alice.games.playCard(gameID: invite.gameID, card: 1) }
        try await bob.games.accept(gameID: invite.gameID)
        // Alice (posto 0) distribuisce da sola con sequence 2; Bob (dopo il mazziere) gioca per primo.
        #expect(await eventually { await Self.synced([alice.games, bob.games], gameID: invite.gameID) })
        let dealt = try await bob.games.session(gameID: invite.gameID)
        #expect(dealt.sequence == 2 && dealt.myTurn && dealt.scopa?.dealer == 0)
        #expect(dealt.scopa?.table.count == 4)

        // Mossa illegale (carta non in mano / presa non legale): rifiutata in locale.
        let aliceHand = try #require(dealt.scopa?.hands[0])
        await #expect(throws: GameService.Failure.invalidMove) {
            try await bob.games.playCard(gameID: invite.gameID, card: aliceHand[0])
        }

        let moves = try await Self.drive([alice.games, bob.games], gameID: invite.gameID)
        #expect(moves >= 36 && moves % 36 == 0)

        let aliceFinal = try await alice.games.session(gameID: invite.gameID)
        let bobFinal = try await bob.games.session(gameID: invite.gameID)
        let scopa = try #require(aliceFinal.scopa)
        #expect(scopa.isMatchOver)
        #expect(scopa.scores.max() ?? 0 >= 11)
        #expect(aliceFinal.status == .finished && bobFinal.status == .finished)
        #expect(Set([aliceFinal.outcome, bobFinal.outcome]) == [.iWon, .iLost])
        #expect(aliceFinal.outcome == (scopa.winnerSide == 0 ? .iWon : .iLost))
        // Il mazziere ruota a ogni mano.
        #expect(scopa.dealer == (scopa.handNumber - 1) % 2)
    }

    @Test("Briscola 1-a-1: invito, accettazione, DEAL, 40 carte giocate, 120 punti e esito")
    func briscolaFullMatch() async throws {
        let players = try await Self.makePlayers(["Alice", "Bob"])
        let (alice, bob) = (players[0], players[1])
        try await TestNode.pair(alice.node, bob.node)
        await FakeMeshNetwork.connect(alice.node.transport, bob.node.transport)

        let conversation = try await alice.node.directConversation(with: bob.node)
        let invite = try await alice.games.invite(kind: .briscola, in: conversation)
        #expect(await eventually { (try? await bob.games.session(gameID: invite.gameID)) != nil })
        try await bob.games.accept(gameID: invite.gameID)

        let moves = try await Self.drive([alice.games, bob.games], gameID: invite.gameID)
        #expect(moves == 40)
        let aliceFinal = try await alice.games.session(gameID: invite.gameID)
        let bobFinal = try await bob.games.session(gameID: invite.gameID)
        let game = try #require(aliceFinal.briscola)
        #expect(game.isFinished && game.points.reduce(0, +) == 120)
        #expect(bobFinal.briscola == game)
        switch game.winnerSeat {
        case 0?: #expect(aliceFinal.outcome == .iWon && bobFinal.outcome == .iLost)
        case 1?: #expect(aliceFinal.outcome == .iLost && bobFinal.outcome == .iWon)
        default: #expect(aliceFinal.outcome == .draw && bobFinal.outcome == .draw)
        }
    }

    @Test("Scopone nel gruppo: 4 giocatori + spettatore, ACCEPT a sequence 0, consegna fuori ordine, DEAL del mazziere successivo, abbandono")
    func scoponeOverMesh() async throws {
        let players = try await Self.makePlayers(["Alice", "Bob", "Carla", "Dario", "Elena"])
        for i in players.indices {
            for j in players.indices where j > i {
                try await TestNode.pair(players[i].node, players[j].node)
                await FakeMeshNetwork.connect(players[i].node.transport, players[j].node.transport)
            }
        }
        let groupID = UUID()
        let key = CryptoEngine.randomGroupSecretKey()
        var groups: [ConversationRecord] = []
        for player in players {
            var members: [PeerRecord] = []
            for other in players where other.node.nodeID != player.node.nodeID {
                members.append(try #require(try await player.node.database.peers.fetch(nodeID: other.node.nodeID)))
            }
            groups.append(try await player.node.database.conversations.createGroup(id: groupID, title: "Volo", secretKey: key,
                                                                                 members: members))
        }
        let (alice, bob, carla, dario, elena) = (players[0], players[1], players[2], players[3], players[4])

        // Scelta non valida: servono 3 membri distinti.
        await #expect(throws: GameService.Failure.invalidPlayers) {
            try await alice.games.invite(kind: .scopone, in: groups[0], players: [bob.node.nodeID, carla.node.nodeID])
        }
        await #expect(throws: GameService.Failure.directConversationOnly) {
            try await alice.games.invite(kind: .scopa, in: groups[0])
        }
        // Posti: 0 Alice, 1 Bob, 2 Carla (compagna di Alice), 3 Dario. Elena guarda.
        let invite = try await alice.games.invite(kind: .scopone, in: groups[0],
                                                  players: [bob.node.nodeID, carla.node.nodeID, dario.node.nodeID])
        let gameID = invite.gameID
        let all = players.map(\.games)
        #expect(await eventually {
            for service in all where (try? await service.session(gameID: gameID)) == nil { return false }
            return true
        })
        let elenaView = try await elena.games.session(gameID: gameID)
        #expect(elenaView.isSpectator && elenaView.seats?.count == 4)
        #expect(try await carla.games.session(gameID: gameID).mySeat == 2)
        await #expect(throws: GameService.Failure.invalidState) { try await elena.games.accept(gameID: gameID) }

        // Gli ACCEPT non fanno avanzare il contatore; il DEAL (sequence 1) parte solo dopo il terzo.
        try await bob.games.accept(gameID: gameID)
        try await carla.games.accept(gameID: gameID)
        #expect(await eventually { (try? await alice.games.session(gameID: gameID))?.acceptedSeats == [1, 2] })
        let waiting = try await alice.games.session(gameID: gameID)
        #expect(waiting.sequence == 0 && waiting.scopa == nil && waiting.status == .invited)
        try await dario.games.accept(gameID: gameID)
        #expect(await eventually { await Self.synced(all, gameID: gameID) })
        let dealt = try await elena.games.session(gameID: gameID)
        #expect(dealt.sequence == 1 && dealt.scopa?.dealer == 0 && dealt.status == .active)
        #expect(dealt.scopa?.hands.allSatisfy { $0.count == 10 } == true)
        #expect(try await bob.games.session(gameID: gameID).myTurn)

        // Consegna fuori ordine verso Dario: le mosse 2 e 3 gli arrivano invertite.
        await dario.node.transport.holdIncoming()
        try await Self.playTurn(bob.games, gameID: gameID)
        #expect(await eventually { await Self.synced([alice.games, bob.games, carla.games, elena.games], gameID: gameID) })
        try await Self.playTurn(carla.games, gameID: gameID)
        #expect(await eventually { await Self.synced([alice.games, bob.games, carla.games, elena.games], gameID: gameID) })
        #expect(try await alice.games.session(gameID: gameID).sequence == 3)
        #expect(try await dario.games.session(gameID: gameID).sequence == 1)
        #expect(await dario.node.transport.heldFrameCount() > 0)
        await dario.node.transport.releaseHeldFrames(reversed: true)
        #expect(await eventually { await Self.synced(all, gameID: gameID) })
        let darioView = try await dario.games.session(gameID: gameID)
        #expect(darioView.sequence == 3 && darioView.myTurn && darioView.pendingMoves == nil)

        // Resto della prima mano: alla fine Bob (posto 1) distribuisce la seconda.
        try await Self.drive(all, gameID: gameID) { state in (state.scopa?.handNumber ?? 0) >= 2 }
        let secondHand = try await elena.games.session(gameID: gameID)
        let scopone = try #require(secondHand.scopa)
        #expect(scopone.dealer == 1 && scopone.turn == 2 && secondHand.sequence == 42)
        let firstHandScore = try #require(scopone.lastHandScore)
        #expect(firstHandScore.cards.reduce(0, +) == 40)
        #expect(scopone.scores == firstHandScore.total)

        // Carla abbandona: perde la squadra Alice-Carla, per tutti.
        try await carla.games.resign(gameID: gameID)
        #expect(await eventually {
            for service in all where (try? await service.session(gameID: gameID))?.status != .finished { return false }
            return true
        })
        #expect(try await alice.games.session(gameID: gameID).outcome == .iLost)
        #expect(try await carla.games.session(gameID: gameID).outcome == .iLost)
        #expect(try await bob.games.session(gameID: gameID).outcome == .iWon)
        #expect(try await dario.games.session(gameID: gameID).outcome == .iWon)
        let spectatorEnd = try await elena.games.session(gameID: gameID)
        #expect(spectatorEnd.outcome == .ongoing && spectatorEnd.resignedSeat == 2 && spectatorEnd.cardWinnerSide == 1)
    }

    /// Una giocata del bot per chi ha il turno su `service`.
    static func playTurn(_ service: GameService, gameID: String) async throws {
        let state = try await service.session(gameID: gameID)
        let seat = try #require(state.mySeat)
        let scopa = try #require(state.scopa)
        #expect(state.myTurn)
        let card = try #require(scopa.hands[seat].first)
        try await service.playCard(gameID: gameID, card: card, captured: scopa.captureOptions(for: card).first ?? [])
    }
}
