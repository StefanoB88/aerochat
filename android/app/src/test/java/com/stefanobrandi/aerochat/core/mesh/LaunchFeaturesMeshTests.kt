package com.stefanobrandi.aerochat.core.mesh

import com.stefanobrandi.aerochat.core.crypto.CryptoEngine
import com.stefanobrandi.aerochat.core.emergency.EmergencyAlert
import com.stefanobrandi.aerochat.core.emergency.EmergencyService
import com.stefanobrandi.aerochat.core.flight.BoardingPass
import com.stefanobrandi.aerochat.core.moderation.AppSettings
import com.stefanobrandi.aerochat.core.quiz.QuizQuestion
import com.stefanobrandi.aerochat.core.quiz.QuizQuestionBank
import com.stefanobrandi.aerochat.core.quiz.QuizService
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.DeliveryStatus
import com.stefanobrandi.aerochat.data.records.MessageContentType
import com.stefanobrandi.aerochat.data.records.MessageRecord
import com.stefanobrandi.aerochat.data.records.ReportReason
import com.stefanobrandi.aerochat.data.records.ReportRecord
import com.stefanobrandi.aerochat.proto.EmergencyStatus
import com.stefanobrandi.aerochat.proto.PacketType
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Release 1.1 end-to-end: segnalazioni, Cabina del volo, blocco nel relay, filtro, quiz ed emergenze. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchFeaturesMeshTests : MeshTestCase() {

    private class EmergencyRecorder : MeshNotifier {
        val alerts: MutableList<EmergencyAlert> = Collections.synchronizedList(ArrayList())
        override suspend fun notify(message: MessageRecord, conversation: ConversationRecord) {}
        override suspend fun notifyEmergency(alert: EmergencyAlert) {
            alerts.add(alert)
        }
    }

    @Before
    fun resetSettings() {
        AppSettings.resetForTesting()
    }

    @After
    fun restoreSettings() {
        AppSettings.resetForTesting()
    }

    // MARK: - Segnalazioni e Cabina del volo (DB)

    @Test
    fun reportsArePersisted() = meshTest {
        val database = node("Io").database
        val lounge = database.conversations.currentCabinLounge()
        val message = MessageRecord(
            msgID = CryptoEngine.randomMessageID(), conversationID = lounge.id,
            senderNodeID = ByteArray(8) { 9 }, senderNickname = "Troll",
            contentType = MessageContentType.text, text = "spam spam", isOutgoing = false,
            deliveryStatus = DeliveryStatus.deliveredAck,
        )
        database.messages.insert(message)
        val report = database.reports.insert(ReportRecord(message = message, reason = ReportReason.spam, details = "  link a un sito  "))
        assertNotNull(report.id)
        assertEquals("link a un sito", report.details)
        assertEquals(1, database.reports.count())
        assertTrue(report.emailBody.contains("Troll"))
        assertTrue(report.emailBody(context).contains("Troll"))
        assertEquals(ReportReason.spam, database.reports.fetchAll().single().reason)
        database.reports.delete(id = requireNotNull(report.id))
        assertEquals(0, database.reports.count())
    }

    @Test
    fun flightLoungeJoinIsIdempotent() = meshTest {
        val database = node("Io").database
        val day = Instant.parse("2026-11-10T20:30:00Z").toEpochMilli()
        val pass = requireNotNull(BoardingPass.fromFlightText("FR1234", departureDay = day))
        val first = database.conversations.joinFlightLounge(pass)
        val second = database.conversations.joinFlightLounge(pass)
        assertEquals(first.id, second.id)
        assertEquals(1, database.conversations.activeFlightLounges(now = day).size)
        assertTrue(database.conversations.fetchPermanent().any { it.id == first.id })
        assertEquals(pass.canonicalKey, database.conversations.fetch(id = first.id)?.flightCode)
        assertTrue(database.conversations.activeFlightLounges(now = pass.loungeExpiry + 1).isEmpty())
    }

    @Test
    fun flightLoungeTextBetweenPassengersWithoutQR() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val day = System.currentTimeMillis()
        val pass = requireNotNull(BoardingPass.fromFlightText("AZ204", departureDay = day))
        val aliceLounge = alice.router.joinFlightLounge(pass)
        val bobLounge = bob.router.joinFlightLounge(pass)
        assertEquals(aliceLounge.id, bobLounge.id)

        val sent = alice.router.sendText("Ciao fila 12!", conversation = aliceLounge)
        assertTrue(eventually { bob.database.messages.fetch(msgID = sent.msgID) != null })
        assertEquals(aliceLounge.id, bob.database.messages.fetch(msgID = sent.msgID)?.conversationID)
    }

    // MARK: - Moderazione nel router

    @Test
    fun loungeTextIsMaskedWhenFilterEnabled() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val lounge = alice.database.conversations.currentCabinLounge()

        val rude = alice.router.sendText("Che cazzo di ritardo", conversation = lounge)
        assertTrue(eventually { bob.database.messages.fetch(msgID = rude.msgID) != null })
        assertEquals("Che c**** di ritardo", bob.database.messages.fetch(msgID = rude.msgID)?.text)

        AppSettings.isLanguageFilterEnabled = false
        val raw = alice.router.sendText("Che cazzo di ritardo", conversation = lounge)
        assertTrue(eventually { bob.database.messages.fetch(msgID = raw.msgID) != null })
        assertEquals("Che cazzo di ritardo", bob.database.messages.fetch(msgID = raw.msgID)?.text)
    }

    @Test
    fun blockedSenderIsNeitherDeliveredNorRelayed() = meshTest {
        val alice = node("Molesto")
        val bob = node("Ponte")
        val carol = node("Carol")
        // Catena: Alice — Bob — Carol (Carol sente Alice solo tramite Bob).
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        FakeMeshNetwork.connect(bob.transport, carol.transport)
        val lounge = alice.database.conversations.currentCabinLounge()

        val first = alice.router.sendText("Primo messaggio", conversation = lounge)
        assertTrue(eventually { carol.database.messages.fetch(msgID = first.msgID) != null })

        // Bob blocca Alice (mai vista prima come peer: record segnaposto, come AppEnvironment.blockUser).
        bob.database.peers.upsert(
            com.stefanobrandi.aerochat.data.records.PeerRecord(
                nodeID = alice.nodeID, x25519Pub = alice.nodeID + ByteArray(24),
                ed25519Pub = ByteArray(32), nickname = "Molesto",
            ),
        )
        bob.database.peers.setBlocked(true, nodeID = alice.nodeID)
        bob.router.refreshBlockedPeers()

        val second = alice.router.sendText("Secondo messaggio", conversation = lounge)
        delay(600)
        assertNull("niente consegna al bloccante", bob.database.messages.fetch(msgID = second.msgID))
        assertNull("niente relay per il bloccato", carol.database.messages.fetch(msgID = second.msgID))
    }

    // MARK: - Emergenze

    @Test
    fun emergencyBroadcastReachesNeighborsAndIsStored() = meshTest {
        val alice = node("Luca")
        val bob = node("Bob")
        val recorder = EmergencyRecorder()
        val aliceService = EmergencyService(alice.router, alice.database, alice.identity, "Luca", NoopNotifier())
        val bobService = EmergencyService(bob.router, bob.database, bob.identity, "Bob", recorder)
        aliceService.start()
        bobService.start()
        try {
            FakeMeshNetwork.connect(alice.transport, bob.transport)
            val local = aliceService.broadcast(EmergencyStatus.NEED_HELP, "  Fila 23, passeggero svenuto  ", 41.8, 12.25)
            assertEquals(MessageContentType.system, local.contentType)

            assertTrue(eventually { recorder.alerts.isNotEmpty() })
            val alert = recorder.alerts.single()
            assertEquals(EmergencyStatus.NEED_HELP, alert.status)
            assertEquals("Fila 23, passeggero svenuto", alert.text)
            assertEquals("Luca", alert.senderNickname)
            assertTrue(alert.hasLocation)
            val stored = requireNotNull(bob.database.messages.fetch(msgID = local.msgID))
            assertEquals(ConversationRecord.cabinLoungeID, stored.conversationID)
            assertEquals(MessageContentType.system, stored.contentType)
            assertTrue(requireNotNull(stored.text).startsWith("🆘 Luca: Ho bisogno di aiuto"))
            assertTrue(PacketType.EMERGENCY.isStoreAndForwardEligible)
        } finally {
            aliceService.stop()
            bobService.stop()
        }
    }

    @Test
    fun emergencyWaitsInQueueForLateNeighbors() = meshTest {
        val alice = node("Luca")
        val bob = node("Bob")
        val recorder = EmergencyRecorder()
        val aliceService = EmergencyService(alice.router, alice.database, alice.identity, "Luca", NoopNotifier())
        val bobService = EmergencyService(bob.router, bob.database, bob.identity, "Bob", recorder)
        aliceService.start()
        bobService.start()
        try {
            // Nessun vicino: l'avviso resta in coda e parte all'apertura del link.
            aliceService.broadcast(EmergencyStatus.OK, "Tutto bene", null, null)
            assertTrue(eventually { alice.database.relayQueue.stats().count == 1 })
            FakeMeshNetwork.connect(alice.transport, bob.transport)
            assertTrue(eventually { recorder.alerts.isNotEmpty() })
            assertEquals(EmergencyStatus.OK, recorder.alerts.single().status)
        } finally {
            aliceService.stop()
            bobService.stop()
        }
    }

    // MARK: - Quiz

    @Test
    fun quizHostCountsAnswersAndPublishesLeaderboard() = meshTest {
        val host = node("Host")
        val player = node("Giocatore")
        val questions = listOf(QuizQuestion("Capitale dell'Australia?", listOf("Canberra", "Sydney"), correctIndex = 0))
        val hostQuiz = QuizService(
            host.router, host.database, host.identity, "Host", questions = { questions },
            questionDuration = 800, revealDuration = 200,
        )
        val playerQuiz = QuizService(
            player.router, player.database, player.identity, "Giocatore", questions = { questions },
            questionDuration = 800, revealDuration = 200,
        )
        hostQuiz.start()
        playerQuiz.start()
        try {
            FakeMeshNetwork.connect(host.transport, player.transport)
            hostQuiz.host(rounds = 1)
            assertTrue(eventually { playerQuiz.state.value?.phase == QuizService.Phase.question })
            val asked = requireNotNull(playerQuiz.state.value)
            assertEquals("Capitale dell'Australia?", asked.question)
            assertEquals(1, asked.totalRounds)
            playerQuiz.answer(0)
            assertTrue(eventually { hostQuiz.state.value?.answerCount == 1 })

            assertTrue(eventually { playerQuiz.state.value?.phase == QuizService.Phase.reveal || playerQuiz.state.value?.phase == QuizService.Phase.ended })
            assertTrue(eventually { playerQuiz.state.value?.phase == QuizService.Phase.ended })
            val board = requireNotNull(playerQuiz.state.value).scores
            assertEquals("Giocatore", board.single().nickname)
            assertTrue("100 punti + bonus velocita'", board.single().points in 100..150)
            assertEquals(QuizService.Phase.ended, hostQuiz.state.value?.phase)
        } finally {
            hostQuiz.stop()
            playerQuiz.stop()
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "it")
    fun quizQuestionBankIsLocalized() {
        val questions = QuizQuestionBank.questions(context)
        assertEquals(24, questions.size)
        assertTrue(questions.all { it.options.size == 4 && it.correctIndex in 0..3 && it.text.isNotEmpty() })
        assertEquals("Qual è la capitale dell'Australia?", questions[3].text)
        assertEquals("Canberra", questions[3].options[questions[3].correctIndex])
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun quizQuestionBankUsesTranslations() {
        val questions = QuizQuestionBank.questions(context)
        assertEquals("What is the capital of Australia?", questions[3].text)
        assertEquals(listOf("5", "7", "9", "11"), questions[4].options)
    }
}
