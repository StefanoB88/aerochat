import Foundation

enum GameStatus: String, Codable, Sendable {
    case invited
    case active
    case finished
}

enum GameOutcome: String, Codable, Sendable {
    case ongoing
    case iWon
    case iLost
    case draw
}

/// Stato locale di una partita: vive in `messages.game_state` (la bolla di gioco e' un solo messaggio
/// che evolve a ogni mossa). `gameID` = hex del `msg_id` dell'invito, uguale per entrambi i giocatori.
struct GameSessionState: Codable, Sendable, Equatable {
    var gameID: String
    var kind: Int
    var conversationID: String
    var opponentNodeID: Data
    var iAmInviter: Bool
    var status: GameStatus = .invited
    var sequence: UInt32 = 0
    var myTurn = false
    var outcome: GameOutcome = .ongoing
    var ticTacToe: TicTacToe?
    var battleship: Battleship?
    /// Battaglia navale: cella del nostro ultimo colpo in attesa di REPORT.
    var pendingShot: Int?
    /// Carte: node_id dei giocatori per posto (0 = chi invita; Scopone: squadre {0,2} contro {1,3}).
    var seats: [Data]?
    /// Carte: nickname per posto (solo per la UI).
    var seatNames: [String]?
    /// Carte: il nostro posto; nil = spettatore (membro del gruppo non al tavolo).
    var mySeat: Int?
    /// Scopone: posti che hanno accettato l'invito.
    var acceptedSeats: [Int]?
    /// Scopa e Scopone scientifico (stesso motore).
    var scopa: ScopaGame?
    var briscola: BriscolaGame?
    /// Carte: DEAL/MOVE arrivati fuori ordine, applicati quando arrivano i precedenti.
    var pendingMoves: [BufferedCardMove]?
    /// Carte: posto di chi ha abbandonato (la sua parte perde).
    var resignedSeat: Int?
    var updatedAtMs: Int64 = Date.nowUnixMilliseconds

    var gameKind: GameKind { GameKind(rawValue: kind) ?? .unspecified }
    var myMark: TicTacToe.Mark { iAmInviter ? .x : .o }
    var messageID: Data? { Data(hexString: gameID) }

    func encoded() throws -> Data { try JSONEncoder().encode(self) }
    static func decode(_ data: Data) throws -> GameSessionState { try JSONDecoder().decode(GameSessionState.self, from: data) }
}

/// Motore dei mini-giochi in chat (Tris, Battaglia navale, Scopa, Scopone scientifico, Briscola) sopra
/// i pacchetti `GAME_MOVE`.
actor GameService {

    /// Salto massimo accettato nel numero di sequenza di una mossa ricevuta.
    static let maxSequenceGap: UInt32 = 16

    enum Failure: Error, Equatable {
        case directConversationOnly
        case gameNotFound
        case notYourTurn
        case invalidState
        case unsupportedKind
        /// Scopone: si gioca solo in una chat di gruppo.
        case groupConversationOnly
        /// Scopone: servono esattamente 3 altri membri del gruppo, distinti.
        case invalidPlayers
        /// Carta o presa non valida.
        case invalidMove
    }

    enum Event: Sendable, Equatable {
        case updated(gameID: String, conversationID: String)
    }

    private let router: MeshRouterActor
    private let database: AppDatabase
    private let identity: IdentityKeys
    private var nickname: String
    private var inboundTask: Task<Void, Never>?
    private var subscribers: [UUID: AsyncStream<Event>.Continuation] = [:]
    /// Carte: mosse arrivate prima dell'invito della loro partita (rigiocate quando arriva l'invito).
    private var orphanCardMoves: [String: [(move: GameMove, packet: InboundPacket)]] = [:]
    /// Carte: le operazioni (lettura stato → mossa → persistenza → eventuale DEAL) non si intrecciano
    /// ai punti di sospensione dell'actor.
    private var cardLockBusy = false
    private var cardLockWaiters: [CheckedContinuation<Void, Never>] = []

    init(router: MeshRouterActor, database: AppDatabase, identity: IdentityKeys, nickname: String) {
        self.router = router
        self.database = database
        self.identity = identity
        self.nickname = nickname
    }

    func start() async {
        guard inboundTask == nil else { return }
        let packets = await router.inboundPackets()
        inboundTask = Task { [weak self] in
            for await packet in packets where packet.envelope.packetType == .gameMove {
                guard let self else { break }
                await self.handle(packet)
            }
        }
    }

    func stop() {
        inboundTask?.cancel()
        inboundTask = nil
    }

    func setNickname(_ nickname: String) {
        self.nickname = nickname
    }

    func events() -> AsyncStream<Event> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(32)) { continuation in
            subscribers[id] = continuation
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.removeSubscriber(id) }
            }
        }
    }

    // MARK: - Lettura

    func session(gameID: String) async throws -> GameSessionState {
        guard let msgID = Data(hexString: gameID),
              let message = try await database.messages.fetch(msgID: msgID),
              let data = message.gameState else { throw Failure.gameNotFound }
        return try GameSessionState.decode(data)
    }

    // MARK: - Azioni locali

    /// Invita l'interlocutore (chat 1-a-1) o, per lo Scopone, 3 membri del gruppo (`players` in ordine
    /// di posto 1..3: il secondo scelto e' il compagno). La bolla appare subito come "invito inviato".
    @discardableResult
    func invite(kind: GameKind, in conversation: ConversationRecord, players: [Data] = []) async throws -> GameSessionState {
        let opponent: Data
        var seats: [Data]?
        if kind == .scopone {
            guard conversation.type == .group else { throw Failure.groupConversationOnly }
            let members = Set(try await database.conversations.groupMembers(of: conversation.id).map(\.nodeID))
            guard players.count == 3, Set(players).count == 3, !players.contains(identity.nodeID),
                  players.allSatisfy(members.contains) else { throw Failure.invalidPlayers }
            opponent = players[0]
            seats = [identity.nodeID] + players
        } else {
            guard conversation.type == .direct, let peer = conversation.peerNodeID else { throw Failure.directConversationOnly }
            guard kind == .ticTacToe || kind == .battleship || kind == .scopa || kind == .briscola else { throw Failure.unsupportedKind }
            opponent = peer
            if kind.isCardGame { seats = [identity.nodeID, peer] }
        }
        let msgID = CryptoEngine.randomMessageID()
        var state = GameSessionState(gameID: msgID.hexString, kind: kind.rawValue, conversationID: conversation.id,
                                     opponentNodeID: opponent, iAmInviter: true)
        if kind == .ticTacToe { state.ticTacToe = TicTacToe() }
        if let seats {
            state.seats = seats
            state.mySeat = 0
            state.seatNames = await seatNames(for: seats, conversationID: conversation.id)
            if kind == .scopone { state.acceptedSeats = [] }
        }
        let message = MessageRecord(msgID: msgID, conversationID: conversation.id, senderNodeID: identity.nodeID,
                                    senderNickname: nickname, contentType: .game, text: Self.title(for: kind),
                                    gameState: try state.encoded(), isOutgoing: true, deliveryStatus: .queued)
        try await database.messages.insert(message)

        var move = GameMove()
        move.gameID = state.gameID
        move.kind = kind
        move.action = .invite
        move.sequence = 0
        if kind == .scopone, let seats { move.players = seats }
        let result = try await router.sendPacket(type: .gameMove, payload: payload(for: move), in: conversation, msgID: msgID,
                                                 retainUntilAck: true)
        try await database.messages.updateDeliveryStatus(result.reached ? .sentMesh : .queued, msgID: msgID)
        emit(.updated(gameID: state.gameID, conversationID: conversation.id))
        return state
    }

    /// Accetta un invito ricevuto: chi invita muove/spara per primo.
    @discardableResult
    func accept(gameID: String) async throws -> GameSessionState {
        if try await session(gameID: gameID).isCardGame {
            return try await withCardLock { try await acceptCardGame(gameID: gameID) }
        }
        var state = try await session(gameID: gameID)
        guard state.status == .invited, !state.iAmInviter else { throw Failure.invalidState }
        state.status = .active
        state.myTurn = false
        switch state.gameKind {
        case .ticTacToe: state.ticTacToe = TicTacToe()
        case .battleship: state.battleship = Battleship(ships: Battleship.randomFleet())
        default: throw Failure.unsupportedKind
        }
        state.sequence += 1
        var move = GameMove()
        move.gameID = gameID
        move.kind = state.gameKind
        move.action = .accept
        move.sequence = state.sequence
        try await send(move, state: &state)
        return state
    }

    /// Gioca una cella: mossa nel Tris, colpo nella Battaglia navale.
    @discardableResult
    func play(gameID: String, cell: Int) async throws -> GameSessionState {
        var state = try await session(gameID: gameID)
        guard state.status == .active, state.myTurn else { throw Failure.notYourTurn }
        var move = GameMove()
        move.gameID = gameID
        move.kind = state.gameKind
        move.action = .move
        move.cell = UInt32(cell)

        switch state.gameKind {
        case .ticTacToe:
            guard var board = state.ticTacToe else { throw Failure.invalidState }
            try board.play(cell, as: state.myMark)
            state.ticTacToe = board
            state.myTurn = false
            move.boardSnapshot = board.snapshot
            if board.winner == state.myMark {
                state.outcome = .iWon
                state.status = .finished
                move.result = 1
            } else if board.isDraw {
                state.outcome = .draw
                state.status = .finished
                move.result = 3
            }
        case .battleship:
            guard let board = state.battleship, board.canShoot(cell), state.pendingShot == nil else { throw Failure.invalidState }
            state.pendingShot = cell
            state.myTurn = false
            move.boardSnapshot = board.shotsSnapshot
        default:
            throw Failure.unsupportedKind
        }
        state.sequence += 1
        move.sequence = state.sequence
        try await send(move, state: &state)
        return state
    }

    @discardableResult
    func resign(gameID: String) async throws -> GameSessionState {
        if try await session(gameID: gameID).isCardGame {
            return try await withCardLock { try await resignCardGame(gameID: gameID) }
        }
        var state = try await session(gameID: gameID)
        guard state.status != .finished else { throw Failure.invalidState }
        state.status = .finished
        state.outcome = .iLost
        state.sequence += 1
        var move = GameMove()
        move.gameID = gameID
        move.kind = state.gameKind
        move.action = .resign
        move.sequence = state.sequence
        move.result = 2
        try await send(move, state: &state)
        return state
    }

    // MARK: - Ricezione

    private func handle(_ packet: InboundPacket) async {
        if let move = try? MeshWire.parseGameMove(packet.payload.extraData), !move.gameID.isEmpty, move.kind.isCardGame {
            await withCardLock { await handleCardMove(move, packet: packet) }
            return
        }
        guard packet.conversation.type == .direct,
              let move = try? MeshWire.parseGameMove(packet.payload.extraData),
              !move.gameID.isEmpty else { return }

        if move.action == .invite {
            await handleInvite(move, packet: packet)
            return
        }
        guard var state = try? await session(gameID: move.gameID),
              !state.isCardGame,
              state.opponentNodeID == packet.sender.nodeID,
              move.sequence > state.sequence,
              // Solo piccoli salti in avanti (mosse perse): un valore enorme farebbe andare in overflow
              // il nostro `sequence += 1` alla mossa successiva.
              move.sequence - state.sequence <= Self.maxSequenceGap else { return }
        state.sequence = move.sequence

        switch move.action {
        case .accept:
            guard state.status == .invited, state.iAmInviter else { return }
            state.status = .active
            state.myTurn = true
            if state.gameKind == .battleship, state.battleship == nil {
                state.battleship = Battleship(ships: Battleship.randomFleet())
            }
            if state.gameKind == .ticTacToe, state.ticTacToe == nil {
                state.ticTacToe = TicTacToe()
            }
            await persist(state)
        case .move:
            await handleOpponentMove(move, state: state)
        case .report:
            await handleReport(move, state: state)
        case .resign:
            state.status = .finished
            state.outcome = .iWon
            state.myTurn = false
            await persist(state)
        default:
            return
        }
    }

    private func handleInvite(_ move: GameMove, packet: InboundPacket) async {
        guard move.kind == .ticTacToe || move.kind == .battleship else { return }
        let msgID = packet.envelope.msgID
        guard msgID.hexString == move.gameID else { return }
        if (try? await database.messages.fetch(msgID: msgID)) != nil { return }
        guard move.sequence <= Self.maxSequenceGap else { return }
        var state = GameSessionState(gameID: move.gameID, kind: move.kind.rawValue, conversationID: packet.conversation.id,
                                     opponentNodeID: packet.sender.nodeID, iAmInviter: false)
        state.sequence = move.sequence
        let message = MessageRecord(msgID: msgID, conversationID: packet.conversation.id, senderNodeID: packet.sender.nodeID,
                                    senderNickname: packet.sender.nickname, contentType: .game, text: Self.title(for: move.kind),
                                    gameState: try? state.encoded(), isOutgoing: false, deliveryStatus: .deliveredAck,
                                    hopCountReceived: packet.hopCount, createdAtMs: packet.envelope.createdAtMs)
        _ = try? await database.messages.insert(message)
        emit(.updated(gameID: state.gameID, conversationID: packet.conversation.id))
    }

    private func handleOpponentMove(_ move: GameMove, state incoming: GameSessionState) async {
        var state = incoming
        guard state.status == .active else { return }
        switch state.gameKind {
        case .ticTacToe:
            guard var board = state.ticTacToe else { return }
            let cell = Int(move.cell)
            if (try? board.play(cell, as: state.myMark.opponent)) == nil {
                // Mossa incoerente con il nostro stato: ci allineiamo allo snapshot autorevole del mittente.
                guard let synced = TicTacToe(snapshot: move.boardSnapshot) else { return }
                board = synced
            }
            state.ticTacToe = board
            if board.winner == state.myMark.opponent || move.result == 1 {
                state.outcome = .iLost
                state.status = .finished
                state.myTurn = false
            } else if board.isDraw || move.result == 3 {
                state.outcome = .draw
                state.status = .finished
                state.myTurn = false
            } else {
                state.myTurn = true
            }
            await persist(state)
        case .battleship:
            guard var board = state.battleship, let outcome = try? board.receiveShot(Int(move.cell)) else { return }
            state.battleship = board
            var report = GameMove()
            report.gameID = state.gameID
            report.kind = .battleship
            report.action = .report
            report.cell = move.cell
            report.report = Self.shotReport(outcome)
            if board.allMyShipsSunk {
                state.status = .finished
                state.outcome = .iLost
                state.myTurn = false
                report.result = 2
            } else {
                state.myTurn = true
            }
            state.sequence += 1
            report.sequence = state.sequence
            try? await send(report, state: &state)
        default:
            return
        }
    }

    private func handleReport(_ move: GameMove, state incoming: GameSessionState) async {
        var state = incoming
        guard state.gameKind == .battleship, var board = state.battleship, let shot = state.pendingShot,
              Int(move.cell) == shot, let outcome = Self.outcome(move.report) else { return }
        board.recordMyShot(shot, outcome: outcome)
        state.battleship = board
        state.pendingShot = nil
        if move.result == 2 || board.enemyFleetDestroyed {
            state.status = .finished
            state.outcome = .iWon
            state.myTurn = false
        } else {
            state.myTurn = false
        }
        await persist(state)
    }

    // MARK: - Utility

    private func send(_ move: GameMove, state: inout GameSessionState) async throws {
        guard let conversation = try await database.conversations.fetch(id: state.conversationID) else { throw Failure.gameNotFound }
        state.updatedAtMs = Date.nowUnixMilliseconds
        await persist(state)
        // In coda fino al DELIVERY_ACK dell'avversario: una mossa persa non blocca piu' la partita.
        _ = try await router.sendPacket(type: .gameMove, payload: payload(for: move), in: conversation, retainUntilAck: true)
    }

    private func persist(_ state: GameSessionState) async {
        var updated = state
        updated.updatedAtMs = Date.nowUnixMilliseconds
        guard let msgID = updated.messageID, let data = try? updated.encoded() else { return }
        try? await database.messages.updateGameState(data, msgID: msgID)
        emit(.updated(gameID: updated.gameID, conversationID: updated.conversationID))
    }

    private func payload(for move: GameMove) throws -> InnerPayload {
        InnerPayload.extra(try MeshWire.serialize(move), identity: identity, nickname: nickname)
    }

    static func title(for kind: GameKind) -> String {
        switch kind {
        case .ticTacToe: return String(localized: "🎮 Tris")
        case .battleship: return String(localized: "🚢 Battaglia navale")
        case .scopa: return String(localized: "🃏 Scopa")
        case .scopone: return String(localized: "🃏 Scopone")
        case .briscola: return String(localized: "🃏 Briscola")
        default: return String(localized: "🎮 Gioco")
        }
    }

    static func shotReport(_ outcome: Battleship.Outcome) -> ShotReport {
        switch outcome {
        case .miss: return .miss
        case .hit: return .hit
        case .sunk: return .sunk
        }
    }

    static func outcome(_ report: ShotReport) -> Battleship.Outcome? {
        switch report {
        case .miss: return .miss
        case .hit: return .hit
        case .sunk: return .sunk
        default: return nil
        }
    }

    private func emit(_ event: Event) {
        for continuation in subscribers.values {
            continuation.yield(event)
        }
    }

    private func removeSubscriber(_ id: UUID) {
        subscribers.removeValue(forKey: id)
    }
}

// MARK: - Carte napoletane (Scopa, Scopone scientifico, Briscola)

extension GameService {

    /// Massimo di mosse tenute in memoria per partite di cui non e' ancora arrivato l'invito.
    static let maxOrphanCardMoves = 64

    /// Gioca una carta: `captured` = carte prese dal tavolo (Scopa/Scopone; vuoto se non c'e' presa).
    @discardableResult
    func playCard(gameID: String, card: Int, captured: [Int] = []) async throws -> GameSessionState {
        try await withCardLock {
            var state = try await session(gameID: gameID)
            guard state.isCardGame, let seat = state.mySeat else { throw Failure.invalidState }
            guard state.status == .active, state.turnSeat == seat else { throw Failure.notYourTurn }
            let applied = BufferedCardMove(sequence: state.sequence + 1, action: .move, senderNodeID: identity.nodeID,
                                           cell: card, captured: captured)
            do { try state.applyCardMove(applied) } catch { throw Failure.invalidMove }
            var move = GameMove()
            move.gameID = gameID
            move.kind = state.gameKind
            move.action = .move
            move.sequence = state.sequence
            move.cell = UInt32(card)
            move.captured = Data(captured.sorted().map { UInt8($0) })
            move.result = state.cardResult(forSenderSeat: seat)
            try await send(move, state: &state)
            return await dealIfNeeded(state)
        }
    }

    fileprivate func acceptCardGame(gameID: String) async throws -> GameSessionState {
        var state = try await session(gameID: gameID)
        guard state.status == .invited, !state.iAmInviter, let seat = state.mySeat else { throw Failure.invalidState }
        var move = GameMove()
        move.gameID = gameID
        move.kind = state.gameKind
        move.action = .accept
        if state.gameKind == .scopone {
            // Scopone: ACCEPT con sequence 0, non fa avanzare il contatore; si aspetta il DEAL di chi invita.
            var accepted = Set(state.acceptedSeats ?? [])
            guard accepted.insert(seat).inserted else { throw Failure.invalidState }
            state.acceptedSeats = accepted.sorted()
            move.sequence = 0
        } else {
            state.status = .active
            state.sequence = 1
            move.sequence = 1
        }
        state.myTurn = false
        try await send(move, state: &state)
        return state
    }

    fileprivate func resignCardGame(gameID: String) async throws -> GameSessionState {
        var state = try await session(gameID: gameID)
        guard state.status != .finished, let seat = state.mySeat else { throw Failure.invalidState }
        state.applyCardResign(seat: seat)
        state.sequence += 1
        var move = GameMove()
        move.gameID = gameID
        move.kind = state.gameKind
        move.action = .resign
        move.sequence = state.sequence
        move.result = 2
        try await send(move, state: &state)
        return state
    }

    /// Ricezione di una mossa di carte: Scopa/Briscola solo in chat 1-a-1, Scopone solo nel gruppo.
    fileprivate func handleCardMove(_ move: GameMove, packet: InboundPacket) async {
        let sender = packet.sender.nodeID
        guard sender != identity.nodeID else { return }
        let expectedType: ConversationType = move.kind == .scopone ? .group : .direct
        guard packet.conversation.type == expectedType else { return }
        if move.action == .invite {
            await handleCardInvite(move, packet: packet)
            return
        }
        guard var state = try? await session(gameID: move.gameID) else {
            rememberOrphan(move, packet: packet)
            return
        }
        guard state.gameKind == move.kind, state.conversationID == packet.conversation.id,
              let senderSeat = state.seat(of: sender) else { return }

        switch move.action {
        case .accept:
            guard state.status == .invited else { return }
            if move.kind == .scopone {
                guard (1...3).contains(senderSeat), move.sequence == 0 else { return }
                var accepted = Set(state.acceptedSeats ?? [])
                guard accepted.insert(senderSeat).inserted else { return }
                state.acceptedSeats = accepted.sorted()
            } else {
                guard state.iAmInviter, senderSeat == 1, move.sequence == 1, state.sequence == 0 else { return }
                state.sequence = 1
                state.status = .active
            }
            await persist(state)
            _ = await dealIfNeeded(state)
        case .resign:
            // L'abbandono vale subito, anche fuori ordine (puo' incrociarsi con una mossa avversaria).
            guard state.status != .finished,
                  move.sequence <= state.sequence || move.sequence - state.sequence <= Self.maxSequenceGap else { return }
            state.applyCardResign(seat: senderSeat)
            state.sequence = max(state.sequence, move.sequence)
            await persist(state)
        case .deal, .move:
            guard state.receiveCardMove(BufferedCardMove(move, senderNodeID: sender), maxGap: Self.maxSequenceGap) else { return }
            await persist(state)
            _ = await dealIfNeeded(state)
        default:
            return
        }
    }

    private func handleCardInvite(_ move: GameMove, packet: InboundPacket) async {
        let msgID = packet.envelope.msgID
        let sender = packet.sender.nodeID
        guard msgID.hexString == move.gameID, move.sequence == 0 else { return }
        if (try? await database.messages.fetch(msgID: msgID)) != nil { return }
        let seats: [Data]
        if move.kind == .scopone {
            guard move.players.count == 4, move.players.allSatisfy({ $0.count == identity.nodeID.count }),
                  Set(move.players).count == 4, move.players[0] == sender else { return }
            seats = move.players
        } else {
            seats = [sender, identity.nodeID]
        }
        var state = GameSessionState(gameID: move.gameID, kind: move.kind.rawValue, conversationID: packet.conversation.id,
                                     opponentNodeID: sender, iAmInviter: false)
        state.seats = seats
        state.mySeat = seats.firstIndex(of: identity.nodeID)
        state.seatNames = await seatNames(for: seats, conversationID: packet.conversation.id,
                                          known: [sender: packet.sender.nickname])
        if move.kind == .scopone { state.acceptedSeats = [] }
        let message = MessageRecord(msgID: msgID, conversationID: packet.conversation.id, senderNodeID: sender,
                                    senderNickname: packet.sender.nickname, contentType: .game, text: Self.title(for: move.kind),
                                    gameState: try? state.encoded(), isOutgoing: false, deliveryStatus: .deliveredAck,
                                    hopCountReceived: packet.hopCount, createdAtMs: packet.envelope.createdAtMs)
        guard (try? await database.messages.insert(message)) != nil else { return }
        emit(.updated(gameID: state.gameID, conversationID: packet.conversation.id))

        // Mosse della stessa partita arrivate prima dell'invito.
        for orphan in orphanCardMoves.removeValue(forKey: move.gameID) ?? [] {
            await handleCardMove(orphan.move, packet: orphan.packet)
        }
    }

    /// Se tocca a noi distribuire, invia il DEAL (mazzo mescolato qui) e lo applica subito.
    private func dealIfNeeded(_ incoming: GameSessionState) async -> GameSessionState {
        var state = incoming
        guard state.shouldDeal, let dealer = state.mySeat else { return state }
        let deck = NeapolitanCards.shuffledDeck()
        let applied = BufferedCardMove(sequence: state.sequence + 1, action: .deal, senderNodeID: identity.nodeID,
                                       cell: dealer, deck: deck)
        guard (try? state.applyCardMove(applied)) != nil else { return incoming }
        state.drainPendingMoves()
        var move = GameMove()
        move.gameID = state.gameID
        move.kind = state.gameKind
        move.action = .deal
        move.sequence = applied.sequence
        move.cell = UInt32(dealer)
        move.deck = Data(deck.map { UInt8($0) })
        try? await send(move, state: &state)
        return state
    }

    private func rememberOrphan(_ move: GameMove, packet: InboundPacket) {
        guard move.action == .accept || move.action == .deal || move.action == .move || move.action == .resign else { return }
        let total = orphanCardMoves.values.reduce(0) { $0 + $1.count }
        guard total < Self.maxOrphanCardMoves else { return }
        orphanCardMoves[move.gameID, default: []].append((move, packet))
    }

    /// Nickname per posto: noi, poi i contatti noti, poi i membri del gruppo.
    private func seatNames(for seats: [Data], conversationID: String, known: [Data: String] = [:]) async -> [String] {
        let members = (try? await database.conversations.groupMembers(of: conversationID)) ?? []
        var names: [String] = []
        for (index, nodeID) in seats.enumerated() {
            if nodeID == identity.nodeID {
                names.append(nickname)
            } else if let name = known[nodeID], !name.isEmpty {
                names.append(name)
            } else if let peer = try? await database.peers.fetch(nodeID: nodeID) {
                names.append(peer.nickname)
            } else if let member = members.first(where: { $0.nodeID == nodeID }) {
                names.append(member.nickname)
            } else {
                names.append(String(localized: "Giocatore \(index + 1)"))
            }
        }
        return names
    }

    /// Serializza le operazioni sulle partite a carte (l'actor e' rientrante ai punti `await`).
    fileprivate func withCardLock<T: Sendable>(_ body: () async throws -> T) async rethrows -> T {
        if cardLockBusy {
            await withCheckedContinuation { cardLockWaiters.append($0) }
        } else {
            cardLockBusy = true
        }
        defer {
            if cardLockWaiters.isEmpty {
                cardLockBusy = false
            } else {
                cardLockWaiters.removeFirst().resume()
            }
        }
        return try await body()
    }
}
