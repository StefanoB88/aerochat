package com.example.aerochat.core.quiz

import com.example.aerochat.core.crypto.CryptoEngine
import com.example.aerochat.core.crypto.IdentityKeys
import com.example.aerochat.core.mesh.InboundPacket
import com.example.aerochat.core.mesh.MeshRouterActor
import com.example.aerochat.core.protocol.InnerPayloadFactory
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.MeshWire
import com.example.aerochat.core.protocol.nowUnixMilliseconds
import com.example.aerochat.core.protocol.toByteString
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.AppDatabase
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.proto.PacketType
import com.example.aerochat.proto.QuizAction
import com.example.aerochat.proto.QuizPacket
import com.example.aerochat.proto.QuizScore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quiz di cabina sulla Lounge pubblica: il conduttore (host) pubblica le domande, tutti rispondono,
 * l'host conta e pubblica la classifica. Nessun turno ne' elenco partecipanti: chi c'e' gioca.
 *
 * Port dell'`actor` Swift: stato confinato su un dispatcher seriale. [questions] fornisce la banca domande
 * nella lingua corrente (in app: `QuizQuestionBank.questions(context)`). Durate in ms (configurabili per i test).
 */
class QuizService(
    private val router: MeshRouterActor,
    private val database: AppDatabase,
    private val identity: IdentityKeys,
    nickname: String,
    private val questions: () -> List<QuizQuestion>,
    private val questionDuration: Long = Companion.questionDuration,
    private val revealDuration: Long = Companion.revealDuration,
) {

    enum class Phase(val rawValue: String) {
        waiting("waiting"),
        question("question"),
        reveal("reveal"),
        ended("ended"),
    }

    class ScoreEntry(val nodeID: ByteArray, val nickname: String, val points: Int) {
        /** `Identifiable.id` di Swift (hex del `node_id`, per le chiavi delle liste Compose). */
        val id: String get() = nodeID.toHex()

        override fun equals(other: Any?): Boolean =
            other is ScoreEntry && nodeID.contentEquals(other.nodeID) && nickname == other.nickname && points == other.points

        override fun hashCode(): Int = (nodeID.contentHashCode() * 31 + nickname.hashCode()) * 31 + points

        override fun toString(): String = "ScoreEntry(${nodeID.toHex()}, $nickname, $points)"
    }

    data class State(
        val quizID: String,
        val hostNodeID: ByteArray,
        val hostNickname: String,
        val isHost: Boolean,
        val round: Int = 0,
        val totalRounds: Int = 0,
        val phase: Phase = Phase.waiting,
        val question: String? = null,
        val options: List<String> = emptyList(),
        /** Epoch ms: dopo questo istante le risposte non contano. */
        val deadlineMs: Long = 0,
        val correctIndex: Int? = null,
        val myAnswer: Int? = null,
        val scores: List<ScoreEntry> = emptyList(),
        /** Solo host: risposte ricevute per la domanda corrente. */
        val answerCount: Int = 0,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is State) return false
            return quizID == other.quizID && hostNodeID.contentEquals(other.hostNodeID) &&
                hostNickname == other.hostNickname && isHost == other.isHost && round == other.round &&
                totalRounds == other.totalRounds && phase == other.phase && question == other.question &&
                options == other.options && deadlineMs == other.deadlineMs && correctIndex == other.correctIndex &&
                myAnswer == other.myAnswer && scores == other.scores && answerCount == other.answerCount
        }

        override fun hashCode(): Int {
            var result = quizID.hashCode()
            result = 31 * result + hostNodeID.contentHashCode()
            result = 31 * result + round
            result = 31 * result + phase.hashCode()
            result = 31 * result + (question?.hashCode() ?: 0)
            result = 31 * result + scores.hashCode()
            result = 31 * result + answerCount
            return result
        }
    }

    sealed class Failure(message: String) : Exception(message) {
        data object quizAlreadyRunning : Failure("quizAlreadyRunning")
        data object noQuiz : Failure("noQuiz")
        data object alreadyAnswered : Failure("alreadyAnswered")
        data object tooLate : Failure("tooLate")
    }

    sealed interface Event {
        data class Updated(val state: State?) : Event
    }

    private class Answer(val nodeID: ByteArray, val index: Int, val nickname: String, val atMs: Long)
    private class Points(val nodeID: ByteArray, val nickname: String, val points: Int)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val actorContext = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + actorContext)

    private var nickname: String = nickname
    private var inboundTask: Job? = null
    private var hostTask: Job? = null
    private val subscriberIDs = AtomicLong(0)
    private val subscribers = ConcurrentHashMap<Long, Channel<Event>>()

    private val _state = MutableStateFlow<State?>(null)
    /** Quiz in corso (`null` = nessuno). */
    val state: StateFlow<State?> = _state.asStateFlow()

    /** Host: risposte alla domanda corrente (prima risposta per nodo, chiave hex) e punteggi cumulati. */
    private val answers = HashMap<String, Answer>()
    private val points = HashMap<String, Points>()

    private var current: State?
        get() = _state.value
        set(value) {
            _state.value = value
        }

    suspend fun start(): Unit = withContext(actorContext) {
        if (inboundTask != null) return@withContext
        // Iscrizione registrata subito (prima dell'accensione della radio): solo i pacchetti QUIZ.
        val packets = router.inboundPackets(PacketType.QUIZ)
        inboundTask = scope.launch {
            packets.collect { packet ->
                if (packet.envelope.packetType != PacketType.QUIZ) return@collect
                try {
                    handle(packet)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stop() {
        inboundTask?.cancel()
        inboundTask = null
        hostTask?.cancel()
        hostTask = null
    }

    suspend fun setNickname(nickname: String): Unit = withContext(actorContext) {
        this@QuizService.nickname = nickname
    }

    /** Aggiornamenti dello stato (ultimi 16 bufferizzati per iscritto). L'iscrizione e' registrata alla chiamata. */
    fun events(): Flow<Event> {
        val id = subscriberIDs.incrementAndGet()
        val channel = Channel<Event>(16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        subscribers[id] = channel
        return channel.consumeAsFlow().onCompletion { subscribers.remove(id) }
    }

    private fun publish() {
        val event = Event.Updated(current)
        for (channel in subscribers.values) channel.trySend(event)
    }

    // MARK: - Conduzione

    /** Avvia un quiz come conduttore (se nessun altro e' in corso). */
    suspend fun host(rounds: Int = defaultRounds): Unit = withContext(actorContext) {
        val existing = current
        if (existing != null && existing.phase != Phase.ended) throw Failure.quizAlreadyRunning
        val lounge = database.conversations.currentCabinLounge()
        val picked = questions().shuffled().take(rounds)
        points.clear()
        answers.clear()
        current = State(
            quizID = CryptoEngine.randomMessageID().toHex(), hostNodeID = identity.nodeID,
            hostNickname = nickname, isHost = true, totalRounds = picked.size,
        )
        publish()
        hostTask?.cancel()
        hostTask = scope.launch { runRounds(picked, lounge) }
    }

    private suspend fun runRounds(questions: List<QuizQuestion>, lounge: ConversationRecord) {
        for ((index, question) in questions.withIndex()) {
            val state = current
            if (!scope.isActive || state == null || !state.isHost) return
            answers.clear()
            val deadline = nowUnixMilliseconds + questionDuration
            val asked = state.copy(
                round = index + 1,
                phase = Phase.question,
                question = question.text,
                options = question.options,
                deadlineMs = deadline,
                correctIndex = null,
                myAnswer = null,
                answerCount = 0,
            )
            current = asked
            publish()

            send(
                basePacket(asked, QuizAction.QUESTION)
                    .setQuestion(question.text)
                    .addAllOptions(question.options)
                    .setDeadlineMs(deadline),
                lounge,
            )

            // Un secondo di tolleranza per le risposte arrivate al limite.
            delay(questionDuration + 1_000)
            if (current?.isHost != true) return

            tally(question, deadline)
            val reveal = basePacket(asked, QuizAction.REVEAL)
                .setCorrectIndex(question.correctIndex)
                .addAllScores(leaderboard().map(::score))
            applyReveal(question.correctIndex)
            send(reveal, lounge)
            delay(revealDuration)
        }
        val state = current
        if (state == null || !state.isHost) return
        val board = leaderboard()
        val end = basePacket(state, QuizAction.END).addAllScores(board.map(::score))
        current = state.copy(phase = Phase.ended, scores = board)
        publish()
        send(end, lounge)
    }

    private fun score(entry: ScoreEntry): QuizScore =
        QuizScore.newBuilder()
            .setNodeId(entry.nodeID.toByteString())
            .setNickname(entry.nickname)
            .setPoints(entry.points)
            .build()

    private fun basePacket(state: State, action: QuizAction): QuizPacket.Builder =
        QuizPacket.newBuilder()
            .setQuizId(state.quizID)
            .setAction(action)
            .setRound(state.round)
            .setTotalRounds(state.totalRounds)
            .setHostNodeId(state.hostNodeID.toByteString())

    private suspend fun send(packet: QuizPacket.Builder, lounge: ConversationRecord) {
        try {
            val payload = InnerPayloadFactory.extra(MeshWire.serialize(packet.build()), identity = identity, nickname = nickname)
            router.sendPacket(type = PacketType.QUIZ, payload = payload, conversation = lounge, storeIfUnreachable = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Punti: 100 per la risposta giusta + fino a 50 di bonus velocita'. */
    private fun tally(question: QuizQuestion, deadline: Long) {
        val window = questionDuration.toDouble()
        for ((key, answer) in answers) {
            if (answer.index != question.correctIndex) continue
            val remaining = maxOf(0.0, (deadline - answer.atMs).toDouble())
            val bonus = ((remaining / window) * 50).toInt()
            val previous = points[key]?.points ?: 0
            points[key] = Points(answer.nodeID, answer.nickname, previous + 100 + bonus)
        }
        // Chi ha risposto (anche male) compare in classifica con i suoi punti precedenti.
        for ((key, answer) in answers) {
            if (points[key] == null) points[key] = Points(answer.nodeID, answer.nickname, 0)
        }
    }

    private fun leaderboard(): List<ScoreEntry> =
        points.values
            .map { ScoreEntry(nodeID = it.nodeID, nickname = it.nickname, points = it.points) }
            .sortedWith { a, b -> if (a.points == b.points) a.nickname.compareTo(b.nickname) else b.points.compareTo(a.points) }
            .take(maxScores)

    private fun applyReveal(correctIndex: Int) {
        current = current?.copy(phase = Phase.reveal, correctIndex = correctIndex, scores = leaderboard())
        publish()
    }

    // MARK: - Partecipazione

    /** Risponde alla domanda corrente (una sola volta, entro la scadenza). */
    suspend fun answer(index: Int): Unit = withContext(actorContext) {
        val state = current
        if (state == null || state.phase != Phase.question) throw Failure.noQuiz
        if (state.myAnswer != null) throw Failure.alreadyAnswered
        val now = nowUnixMilliseconds
        if (now > state.deadlineMs) throw Failure.tooLate
        var answered = state.copy(myAnswer = index)
        current = answered
        publish()
        if (answered.isHost) {
            answers[identity.nodeID.toHex()] = Answer(identity.nodeID, index, nickname, now)
            answered = answered.copy(answerCount = answers.size)
            current = answered
            publish()
        }
        val lounge = database.conversations.currentCabinLounge()
        send(basePacket(answered, QuizAction.ANSWER).setAnswerIndex(index), lounge)
    }

    /** Esce dal quiz corrente (la prossima domanda di un host lo fa rientrare). */
    suspend fun leave(): Unit = withContext(actorContext) {
        hostTask?.cancel()
        hostTask = null
        current = null
        publish()
    }

    // MARK: - Ricezione

    private fun handle(packet: InboundPacket) {
        val quiz = try {
            MeshWire.parseQuizPacket(packet.payload.extraData.toByteArray())
        } catch (_: Exception) {
            return
        }
        if (quiz.quizId.isEmpty() || quiz.quizId.length > 64 || quiz.hostNodeId.size() != MeshProtocol.nodeIDLength) return
        val sender = packet.sender
        val senderHex = sender.nodeID.toHex()
        val hostHex = quiz.hostNodeId.toByteArray().toHex()
        val myHex = identity.nodeID.toHex()

        when (quiz.action) {
            QuizAction.QUESTION -> {
                // Solo l'host dichiarato guida il quiz; un quiz in corso di un altro host non viene interrotto.
                if (senderHex != hostHex || senderHex == myHex) return
                if (quiz.optionsCount < 2 || quiz.optionsCount > 4 || quiz.question.isEmpty() ||
                    quiz.question.length > 300 || quiz.optionsList.any { it.length > 80 }
                ) return
                val state = current
                if (state != null && state.phase != Phase.ended && state.quizID != quiz.quizId) return
                val base = if (state != null && state.quizID == quiz.quizId) state else State(
                    quizID = quiz.quizId, hostNodeID = quiz.hostNodeId.toByteArray(),
                    hostNickname = sender.nickname, isHost = false,
                )
                current = base.copy(
                    round = quiz.round,
                    totalRounds = quiz.totalRounds,
                    phase = Phase.question,
                    question = quiz.question,
                    options = quiz.optionsList.toList(),
                    deadlineMs = quiz.deadlineMs,
                    correctIndex = null,
                    myAnswer = null,
                )
                publish()
            }
            QuizAction.ANSWER -> {
                val state = current ?: return
                if (!state.isHost || state.quizID != quiz.quizId || quiz.round != state.round ||
                    state.phase != Phase.question || answers.containsKey(senderHex) || senderHex == myHex
                ) return
                val now = nowUnixMilliseconds
                // uint32 su wire: un indice "negativo" in Java e' in realta' enorme, quindi non valido.
                if (now > state.deadlineMs + 2_000 || quiz.answerIndex < 0 || quiz.answerIndex >= state.options.size) return
                answers[senderHex] = Answer(sender.nodeID, quiz.answerIndex, sender.nickname, minOf(now, state.deadlineMs))
                current = state.copy(answerCount = answers.size)
                publish()
            }
            QuizAction.REVEAL, QuizAction.END -> {
                val state = current ?: return
                if (state.isHost || state.quizID != quiz.quizId || senderHex != state.hostNodeID.toHex()) return
                current = state.copy(
                    phase = if (quiz.action == QuizAction.END) Phase.ended else Phase.reveal,
                    correctIndex = if (quiz.action == QuizAction.REVEAL) quiz.correctIndex else state.correctIndex,
                    scores = quiz.scoresList.take(maxScores).map {
                        ScoreEntry(nodeID = it.nodeId.toByteArray(), nickname = it.nickname, points = it.points)
                    },
                )
                publish()
            }
            QuizAction.QUIZ_ACTION_UNSPECIFIED, QuizAction.UNRECOGNIZED -> return
        }
    }

    companion object {
        /** ms. */
        const val questionDuration: Long = 20_000L
        /** ms. */
        const val revealDuration: Long = 6_000L
        const val defaultRounds = 5
        const val maxScores = 32
    }
}
