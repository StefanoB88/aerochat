import Foundation

/// Quiz di cabina sulla Lounge pubblica: il conduttore (host) pubblica le domande, tutti rispondono,
/// l'host conta e pubblica la classifica. Nessun turno ne' elenco partecipanti: chi c'e' gioca.
actor QuizService {

    static let questionDuration: TimeInterval = 20
    static let revealDuration: TimeInterval = 6
    static let defaultRounds = 5
    static let maxScores = 32

    enum Phase: String, Sendable, Equatable {
        case waiting
        case question
        case reveal
        case ended
    }

    struct ScoreEntry: Sendable, Equatable, Identifiable {
        let nodeID: Data
        let nickname: String
        let points: Int
        var id: Data { nodeID }
    }

    struct State: Sendable, Equatable {
        var quizID: String
        var hostNodeID: Data
        var hostNickname: String
        var isHost: Bool
        var round = 0
        var totalRounds = 0
        var phase: Phase = .waiting
        var question: String?
        var options: [String] = []
        var deadlineMs: Int64 = 0
        var correctIndex: Int?
        var myAnswer: Int?
        var scores: [ScoreEntry] = []
        /// Solo host: risposte ricevute per la domanda corrente.
        var answerCount = 0
    }

    enum Failure: Error, Equatable {
        case quizAlreadyRunning
        case noQuiz
        case alreadyAnswered
        case tooLate
    }

    enum Event: Sendable, Equatable {
        case updated(State?)
    }

    private let router: MeshRouterActor
    private let database: AppDatabase
    private let identity: IdentityKeys
    private var nickname: String
    private var inboundTask: Task<Void, Never>?
    private var hostTask: Task<Void, Never>?
    private var subscribers: [UUID: AsyncStream<Event>.Continuation] = [:]
    private(set) var state: State?
    /// Host: risposte alla domanda corrente (prima risposta per nodo) e punteggi cumulati.
    private var answers: [Data: (index: Int, nickname: String, atMs: Int64)] = [:]
    private var points: [Data: (nickname: String, points: Int)] = [:]
    private var currentQuestion: QuizQuestion?

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
            for await packet in packets where packet.envelope.packetType == .quiz {
                guard let self else { break }
                await self.handle(packet)
            }
        }
    }

    func stop() {
        inboundTask?.cancel()
        inboundTask = nil
        hostTask?.cancel()
        hostTask = nil
    }

    func setNickname(_ nickname: String) {
        self.nickname = nickname
    }

    func events() -> AsyncStream<Event> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(16)) { continuation in
            subscribers[id] = continuation
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.removeSubscriber(id) }
            }
        }
    }

    private func removeSubscriber(_ id: UUID) {
        subscribers.removeValue(forKey: id)
    }

    private func publish() {
        for continuation in subscribers.values {
            continuation.yield(.updated(state))
        }
    }

    // MARK: - Conduzione

    /// Avvia un quiz come conduttore (se nessun altro e' in corso).
    func host(rounds: Int = QuizService.defaultRounds) async throws {
        if let state, state.phase != .ended { throw Failure.quizAlreadyRunning }
        let lounge = try await database.conversations.currentCabinLounge()
        let picked = Array(QuizQuestionBank.questions.shuffled().prefix(rounds))
        points = [:]
        answers = [:]
        state = State(quizID: CryptoEngine.randomMessageID().hexString, hostNodeID: identity.nodeID,
                      hostNickname: nickname, isHost: true, totalRounds: picked.count)
        publish()
        hostTask?.cancel()
        hostTask = Task { [weak self] in
            await self?.runRounds(picked, lounge: lounge)
        }
    }

    private func runRounds(_ questions: [QuizQuestion], lounge: ConversationRecord) async {
        for (index, question) in questions.enumerated() {
            guard !Task.isCancelled, var current = state, current.isHost else { return }
            answers = [:]
            currentQuestion = question
            let deadline = Date.nowUnixMilliseconds + Int64(Self.questionDuration * 1_000)
            current.round = index + 1
            current.phase = .question
            current.question = question.text
            current.options = question.options
            current.deadlineMs = deadline
            current.correctIndex = nil
            current.myAnswer = nil
            current.answerCount = 0
            state = current
            publish()

            var packet = basePacket(for: current, action: .question)
            packet.question = question.text
            packet.options = question.options
            packet.deadlineMs = deadline
            await send(packet, in: lounge)

            // Un secondo di tolleranza per le risposte arrivate al limite.
            try? await Task.sleep(for: .seconds(Self.questionDuration + 1))
            guard !Task.isCancelled, state?.isHost == true else { return }

            tally(question: question, deadline: deadline)
            var reveal = basePacket(for: current, action: .reveal)
            reveal.correctIndex = UInt32(question.correctIndex)
            reveal.scores = leaderboard().map { entry in
                var score = QuizScore()
                score.nodeID = entry.nodeID
                score.nickname = entry.nickname
                score.points = UInt32(entry.points)
                return score
            }
            applyReveal(correctIndex: question.correctIndex)
            await send(reveal, in: lounge)
            try? await Task.sleep(for: .seconds(Self.revealDuration))
        }
        guard !Task.isCancelled, let current = state, current.isHost else { return }
        var end = basePacket(for: current, action: .end)
        end.scores = leaderboard().map { entry in
            var score = QuizScore()
            score.nodeID = entry.nodeID
            score.nickname = entry.nickname
            score.points = UInt32(entry.points)
            return score
        }
        state?.phase = .ended
        state?.scores = leaderboard()
        publish()
        await send(end, in: lounge)
    }

    private func basePacket(for state: State, action: QuizAction) -> QuizPacket {
        var packet = QuizPacket()
        packet.quizID = state.quizID
        packet.action = action
        packet.round = UInt32(state.round)
        packet.totalRounds = UInt32(state.totalRounds)
        packet.hostNodeID = state.hostNodeID
        return packet
    }

    private func send(_ packet: QuizPacket, in lounge: ConversationRecord) async {
        guard let data = try? MeshWire.serialize(packet) else { return }
        let payload = InnerPayload.extra(data, identity: identity, nickname: nickname)
        _ = try? await router.sendPacket(type: .quiz, payload: payload, in: lounge, storeIfUnreachable: false)
    }

    /// Punti: 100 per la risposta giusta + fino a 50 di bonus velocita'.
    private func tally(question: QuizQuestion, deadline: Int64) {
        let window = Double(Self.questionDuration * 1_000)
        for (nodeID, answer) in answers where answer.index == question.correctIndex {
            let remaining = max(0, Double(deadline - answer.atMs))
            let bonus = Int((remaining / window) * 50)
            let previous = points[nodeID]?.points ?? 0
            points[nodeID] = (answer.nickname, previous + 100 + bonus)
        }
        // Chi ha risposto (anche male) compare in classifica con i suoi punti precedenti.
        for (nodeID, answer) in answers where points[nodeID] == nil {
            points[nodeID] = (answer.nickname, 0)
        }
    }

    private func leaderboard() -> [ScoreEntry] {
        points.map { ScoreEntry(nodeID: $0.key, nickname: $0.value.nickname, points: $0.value.points) }
            .sorted { $0.points == $1.points ? $0.nickname < $1.nickname : $0.points > $1.points }
            .prefix(Self.maxScores)
            .map { $0 }
    }

    private func applyReveal(correctIndex: Int) {
        state?.phase = .reveal
        state?.correctIndex = correctIndex
        state?.scores = leaderboard()
        publish()
    }

    // MARK: - Partecipazione

    /// Risponde alla domanda corrente (una sola volta, entro la scadenza).
    func answer(_ index: Int) async throws {
        guard var current = state, current.phase == .question else { throw Failure.noQuiz }
        guard current.myAnswer == nil else { throw Failure.alreadyAnswered }
        let now = Date.nowUnixMilliseconds
        guard now <= current.deadlineMs else { throw Failure.tooLate }
        current.myAnswer = index
        state = current
        publish()
        if current.isHost {
            answers[identity.nodeID] = (index, nickname, now)
            state?.answerCount = answers.count
            publish()
        }
        let lounge = try await database.conversations.currentCabinLounge()
        var packet = basePacket(for: current, action: .answer)
        packet.answerIndex = UInt32(index)
        await send(packet, in: lounge)
    }

    /// Esce dal quiz corrente (la prossima domanda di un host lo fa rientrare).
    func leave() {
        hostTask?.cancel()
        hostTask = nil
        state = nil
        publish()
    }

    // MARK: - Ricezione

    private func handle(_ packet: InboundPacket) async {
        guard let quiz = try? MeshWire.parseQuizPacket(packet.payload.extraData),
              !quiz.quizID.isEmpty, quiz.quizID.count <= 64,
              quiz.hostNodeID.count == MeshProtocol.nodeIDLength else { return }
        let sender = packet.sender

        switch quiz.action {
        case .question:
            // Solo l'host dichiarato guida il quiz; un quiz in corso di un altro host non viene interrotto.
            guard sender.nodeID == quiz.hostNodeID, sender.nodeID != identity.nodeID else { return }
            guard quiz.options.count >= 2, quiz.options.count <= 4, !quiz.question.isEmpty,
                  quiz.question.count <= 300, quiz.options.allSatisfy({ $0.count <= 80 }) else { return }
            if let current = state, current.phase != .ended, current.quizID != quiz.quizID { return }
            var next = state?.quizID == quiz.quizID ? state! : State(quizID: quiz.quizID, hostNodeID: quiz.hostNodeID,
                                                                       hostNickname: sender.nickname, isHost: false)
            next.round = Int(quiz.round)
            next.totalRounds = Int(quiz.totalRounds)
            next.phase = .question
            next.question = quiz.question
            next.options = quiz.options
            next.deadlineMs = quiz.deadlineMs
            next.correctIndex = nil
            next.myAnswer = nil
            state = next
            publish()
        case .answer:
            guard var current = state, current.isHost, current.quizID == quiz.quizID,
                  Int(quiz.round) == current.round, current.phase == .question,
                  answers[sender.nodeID] == nil, sender.nodeID != identity.nodeID else { return }
            let now = Date.nowUnixMilliseconds
            guard now <= current.deadlineMs + 2_000, Int(quiz.answerIndex) < current.options.count else { return }
            answers[sender.nodeID] = (Int(quiz.answerIndex), sender.nickname, min(now, current.deadlineMs))
            current.answerCount = answers.count
            state = current
            publish()
        case .reveal, .end:
            guard var current = state, !current.isHost, current.quizID == quiz.quizID,
                  sender.nodeID == current.hostNodeID else { return }
            current.phase = quiz.action == .end ? .ended : .reveal
            if quiz.action == .reveal { current.correctIndex = Int(quiz.correctIndex) }
            current.scores = quiz.scores.prefix(Self.maxScores).map {
                ScoreEntry(nodeID: $0.nodeID, nickname: $0.nickname, points: Int($0.points))
            }
            state = current
            publish()
        case .unspecified, .UNRECOGNIZED:
            return
        }
    }
}
