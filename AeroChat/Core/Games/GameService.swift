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
    var updatedAtMs: Int64 = Date.nowUnixMilliseconds

    var gameKind: GameKind { GameKind(rawValue: kind) ?? .unspecified }
    var myMark: TicTacToe.Mark { iAmInviter ? .x : .o }
    var messageID: Data? { Data(hexString: gameID) }

    func encoded() throws -> Data { try JSONEncoder().encode(self) }
    static func decode(_ data: Data) throws -> GameSessionState { try JSONDecoder().decode(GameSessionState.self, from: data) }
}

/// Motore dei mini-giochi in chat (Tris, Battaglia navale) sopra i pacchetti `GAME_MOVE`.
actor GameService {

    enum Failure: Error, Equatable {
        case directConversationOnly
        case gameNotFound
        case notYourTurn
        case invalidState
        case unsupportedKind
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

    /// Invita l'interlocutore (solo chat 1-a-1). La bolla appare subito come "invito inviato".
    @discardableResult
    func invite(kind: GameKind, in conversation: ConversationRecord) async throws -> GameSessionState {
        guard conversation.type == .direct, let opponent = conversation.peerNodeID else { throw Failure.directConversationOnly }
        guard kind == .ticTacToe || kind == .battleship else { throw Failure.unsupportedKind }
        let msgID = CryptoEngine.randomMessageID()
        var state = GameSessionState(gameID: msgID.hexString, kind: kind.rawValue, conversationID: conversation.id,
                                     opponentNodeID: opponent, iAmInviter: true)
        if kind == .ticTacToe { state.ticTacToe = TicTacToe() }
        let message = MessageRecord(msgID: msgID, conversationID: conversation.id, senderNodeID: identity.nodeID,
                                    senderNickname: nickname, contentType: .game, text: Self.title(for: kind),
                                    gameState: try state.encoded(), isOutgoing: true, deliveryStatus: .queued)
        try await database.messages.insert(message)

        var move = GameMove()
        move.gameID = state.gameID
        move.kind = kind
        move.action = .invite
        move.sequence = 0
        let result = try await router.sendPacket(type: .gameMove, payload: payload(for: move), in: conversation, msgID: msgID)
        try await database.messages.updateDeliveryStatus(result.reached ? .sentMesh : .queued, msgID: msgID)
        emit(.updated(gameID: state.gameID, conversationID: conversation.id))
        return state
    }

    /// Accetta un invito ricevuto: chi invita muove/spara per primo.
    @discardableResult
    func accept(gameID: String) async throws -> GameSessionState {
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
        guard packet.conversation.type == .direct,
              let move = try? MeshWire.parseGameMove(packet.payload.extraData),
              !move.gameID.isEmpty else { return }

        if move.action == .invite {
            await handleInvite(move, packet: packet)
            return
        }
        guard var state = try? await session(gameID: move.gameID),
              state.opponentNodeID == packet.sender.nodeID,
              move.sequence > state.sequence else { return }
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
        _ = try await router.sendPacket(type: .gameMove, payload: payload(for: move), in: conversation)
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
