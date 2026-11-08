package com.example.aerochat.core.calls

import com.example.aerochat.core.mesh.FakeMeshNetwork
import com.example.aerochat.core.mesh.MeshTestCase
import com.example.aerochat.core.mesh.TestNode
import com.example.aerochat.core.mesh.eventually
import com.example.aerochat.core.protocol.InnerPayloadFactory
import com.example.aerochat.core.protocol.MeshWire
import com.example.aerochat.core.protocol.toByteString
import com.example.aerochat.data.records.PeerRecord
import com.example.aerochat.proto.CallAction
import com.example.aerochat.proto.CallAudio
import com.example.aerochat.proto.CallSignal
import com.example.aerochat.proto.PacketType
import java.util.Collections
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Audio finto: registra i pacchetti ricevuti e permette di "catturare" frame verso il compagno. */
class FakeCallAudio : CallAudioPipeline {
    @Volatile var isRunning = false
    @Volatile var startCount = 0
    @Volatile var mutedFlag = false
    @Volatile var speakerFlag = false
    @Volatile private var onPacket: ((List<ByteArray>) -> Unit)? = null
    val received: MutableList<Pair<Int, List<ByteArray>>> = Collections.synchronizedList(ArrayList())

    override fun start(onPacket: (List<ByteArray>) -> Unit) {
        this.onPacket = onPacket
        isRunning = true
        startCount += 1
    }

    override fun stop() {
        isRunning = false
        onPacket = null
    }

    override fun setMuted(muted: Boolean) {
        mutedFlag = muted
    }

    override fun setSpeaker(enabled: Boolean) {
        speakerFlag = enabled
    }

    override fun receive(sequence: Int, frames: List<ByteArray>) {
        received += sequence to frames
    }

    fun capture(frames: List<ByteArray>) {
        onPacket?.invoke(frames)
    }
}

/** Parti "servizio" di `CallTests.swift` (segnalazione, squillo, registro, fast path audio) su FakeMeshNetwork. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallServiceTests : MeshTestCase() {

    private val services = ArrayList<CallService>()

    @After
    fun stopServices() = services.forEach { it.stop() }

    private suspend fun calls(node: TestNode, audio: FakeCallAudio = FakeCallAudio(), microphone: Boolean = true): CallService =
        CallService(
            context = context, router = node.router, database = node.database, identity = node.identity,
            nickname = node.nickname, audio = audio, hasRecordPermission = { microphone },
        ).also { it.start(); services += it }

    private suspend fun offer(callID: String, node: TestNode, peer: PeerRecord, action: CallAction = CallAction.CALL_OFFER): Boolean {
        val signal = CallSignal.newBuilder().setCallId(callID).setAction(action).build()
        val payload = InnerPayloadFactory.extra(MeshWire.serialize(signal), identity = node.identity, nickname = node.nickname)
        return node.router.sendCallPacket(type = PacketType.CALL_SIGNAL, payload = payload, to = peer)
    }

    @Test
    fun offerRingsAndHangupIsMissed() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val bobCalls = calls(bob)

        val bobPeer = alice.database.peers.fetch(nodeID = bob.nodeID)!!
        assertTrue(offer("call-1", alice, bobPeer))
        assertTrue(eventually { bobCalls.current.value?.phase == CallService.Phase.Ringing })
        assertEquals(CallService.Direction.incoming, bobCalls.current.value?.direction)
        assertTrue(bobCalls.current.value!!.peer.nodeID.contentEquals(alice.nodeID))
        assertFalse(bobCalls.isAudioActive.value)

        assertTrue(offer("call-1", alice, bobPeer, action = CallAction.CALL_HANGUP))
        assertTrue(eventually { bobCalls.current.value?.phase == CallService.Phase.Ended(CallService.EndReason.missed) })
        val conversation = bob.directConversation(with = alice)
        assertTrue(eventually { bob.database.messages.fetch(conversationID = conversation.id).any { it.text == "📞 Chiamata persa" } })
        // Il riepilogo sparisce dopo 1,5 s.
        assertTrue(eventually(timeout = 3_000) { bobCalls.current.value == null })
    }

    @Test
    fun declineEndsCall() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val aliceCalls = calls(alice)
        val bobCalls = calls(bob)

        aliceCalls.placeCall(alice.directConversation(with = bob))
        assertEquals(CallService.Direction.outgoing, aliceCalls.current.value?.direction)
        assertTrue(eventually { bobCalls.current.value?.phase == CallService.Phase.Ringing })
        bobCalls.decline()
        assertEquals(CallService.Phase.Ended(CallService.EndReason.rejected), bobCalls.current.value?.phase)
        assertTrue(eventually { aliceCalls.current.value?.phase == CallService.Phase.Ended(CallService.EndReason.rejected) })
        val conversation = alice.directConversation(with = bob)
        assertTrue(eventually { alice.database.messages.fetch(conversationID = conversation.id).any { it.text == "📞 Chiamata rifiutata" } })
    }

    @Test
    fun acceptedCallStreamsAudioBothWaysAndHangUpLogsDuration() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val aliceAudio = FakeCallAudio()
        val bobAudio = FakeCallAudio()
        val aliceCalls = calls(alice, aliceAudio)
        val bobCalls = calls(bob, bobAudio)

        aliceCalls.placeCall(alice.directConversation(with = bob))
        assertTrue(eventually { bobCalls.current.value?.phase == CallService.Phase.Ringing })
        val callID = aliceCalls.current.value!!.id
        assertEquals(callID, bobCalls.current.value!!.id)
        bobCalls.accept()
        assertTrue(eventually { aliceCalls.current.value?.phase == CallService.Phase.Active })
        assertTrue(eventually { bobCalls.current.value?.phase == CallService.Phase.Active })
        assertTrue(aliceAudio.isRunning && bobAudio.isRunning)
        assertTrue(aliceCalls.isAudioActive.value && bobCalls.isAudioActive.value)
        assertTrue(aliceCalls.current.value!!.connectedAt != null)

        val frames = listOf(byteArrayOf(8, 1, 2), byteArrayOf(8, 3, 4), byteArrayOf(8, 5, 6))
        aliceAudio.capture(frames)
        aliceAudio.capture(frames)
        assertTrue(eventually { bobAudio.received.size == 2 })
        assertEquals(listOf(1, 2), bobAudio.received.map { it.first })
        assertArrayEquals(frames[2], bobAudio.received[0].second[2])
        bobAudio.capture(frames)
        assertTrue(eventually { aliceAudio.received.size == 1 })

        aliceCalls.toggleMute()
        aliceCalls.toggleSpeaker()
        assertTrue(aliceAudio.mutedFlag && aliceAudio.speakerFlag)
        assertTrue(aliceCalls.current.value!!.isMuted && aliceCalls.current.value!!.isSpeakerOn)

        aliceCalls.hangUp()
        assertEquals(CallService.Phase.Ended(CallService.EndReason.hungUp), aliceCalls.current.value?.phase)
        // Lo stop dell'audio e' asincrono (fuori dal main thread).
        assertTrue(eventually { !aliceAudio.isRunning })
        assertFalse(aliceCalls.isAudioActive.value)
        assertTrue(eventually { bobCalls.current.value?.phase == CallService.Phase.Ended(CallService.EndReason.hungUp) })
        assertTrue(eventually { !bobAudio.isRunning })
        val conversation = bob.directConversation(with = alice)
        assertTrue(eventually { bob.database.messages.fetch(conversationID = conversation.id).any { it.text == "📞 Chiamata vocale · 0:00" } })
    }

    /** Regressione: doppio tocco su "Rispondi" non deve avviare due pipeline audio (il secondo canale muto). */
    @Test
    fun doubleAcceptStartsAudioOnce() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val aliceAudio = FakeCallAudio()
        val bobAudio = FakeCallAudio()
        val aliceCalls = calls(alice, aliceAudio)
        val bobCalls = calls(bob, bobAudio)

        aliceCalls.placeCall(alice.directConversation(with = bob))
        assertTrue(eventually { bobCalls.current.value?.phase == CallService.Phase.Ringing })
        bobCalls.accept()
        bobCalls.accept()
        assertTrue(eventually { bobCalls.current.value?.phase == CallService.Phase.Active })
        assertTrue(eventually { aliceCalls.current.value?.phase == CallService.Phase.Active })
        delay(200)
        assertEquals(1, bobAudio.startCount)
        assertEquals(1, aliceAudio.startCount)

        val frames = listOf(byteArrayOf(8, 1, 2))
        bobAudio.capture(frames)
        assertTrue(eventually { aliceAudio.received.size == 1 })
        aliceCalls.hangUp()
    }

    @Test
    fun secondCallerGetsBusy() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        val carol = node("Carol")
        TestNode.pair(alice, bob)
        TestNode.pair(carol, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        FakeMeshNetwork.connect(carol.transport, bob.transport)
        val aliceCalls = calls(alice)
        val bobCalls = calls(bob)
        val carolCalls = calls(carol)

        aliceCalls.placeCall(alice.directConversation(with = bob))
        assertTrue(eventually { bobCalls.current.value?.phase == CallService.Phase.Ringing })
        carolCalls.placeCall(carol.directConversation(with = bob))
        assertTrue(eventually { carolCalls.current.value?.phase == CallService.Phase.Ended(CallService.EndReason.busy) })
        // Bob resta sulla chiamata di Alice.
        assertEquals(aliceCalls.current.value?.id, bobCalls.current.value?.id)
        val conversation = carol.directConversation(with = bob)
        assertTrue(eventually { carol.database.messages.fetch(conversationID = conversation.id).any { it.text == "📞 Occupato" } })
    }

    @Test
    fun microphoneDeniedAndUnreachablePeerEndImmediately() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        val denied = calls(alice, microphone = false)
        denied.placeCall(alice.directConversation(with = bob))
        assertEquals(CallService.Phase.Ended(CallService.EndReason.microphoneDenied), denied.current.value?.phase)
        assertTrue(eventually(timeout = 3_000) { denied.current.value == null })

        // Nessun link diretto: l'OFFER non parte.
        val aliceCalls = calls(alice)
        aliceCalls.placeCall(alice.directConversation(with = bob))
        assertEquals(CallService.Phase.Ended(CallService.EndReason.unreachable), aliceCalls.current.value?.phase)
    }

    @Test
    fun callSignalNeedsDirectLink() = meshTest {
        val alice = node("Alice")
        val bridge = node("Ponte")
        val carol = node("Carol")
        TestNode.pair(alice, carol)
        FakeMeshNetwork.connect(alice.transport, bridge.transport)
        FakeMeshNetwork.connect(bridge.transport, carol.transport)

        val carolPeer = alice.database.peers.fetch(nodeID = carol.nodeID)!!
        assertFalse(offer("call-3", alice, carolPeer))
        assertTrue(alice.database.relayQueue.pending().isEmpty())
    }

    @Test
    fun callAudioFastPath() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val stream = bob.router.callAudioPackets()
        val seenBefore = bob.database.seenPackets.count()

        val frames = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val audio = CallAudio.newBuilder().setCallId("call-4").setSequence(1).addAllFrames(frames.map { it.toByteString() }).build()
        val payload = InnerPayloadFactory.extra(MeshWire.serialize(audio), identity = alice.identity, nickname = "Alice")
        val bobPeer = alice.database.peers.fetch(nodeID = bob.nodeID)!!
        assertTrue(alice.router.sendCallPacket(type = PacketType.CALL_AUDIO, payload = payload, to = bobPeer))

        val received = withTimeout(5_000) { stream.first() }
        assertTrue(received.senderNodeID.contentEquals(alice.nodeID))
        assertEquals(frames.map { it.toList() }, received.audio.framesList.map { it.toByteArray().toList() })
        assertEquals(seenBefore, bob.database.seenPackets.count())
    }

    @Test
    fun callLogTextsMatchIOS() {
        assertEquals("1:05", CallService.durationLabel(65_400))
        assertEquals("📞 Chiamata vocale · 0:12", CallService.logText(CallService.Direction.outgoing, CallService.EndReason.hungUp, 12_000))
        assertEquals("📞 Chiamata annullata", CallService.logText(CallService.Direction.outgoing, CallService.EndReason.hungUp, null))
        assertEquals("📞 Nessuna risposta", CallService.logText(CallService.Direction.outgoing, CallService.EndReason.noAnswer, null))
        assertEquals("📞 Chiamata non riuscita", CallService.logText(CallService.Direction.outgoing, CallService.EndReason.unreachable, null))
        assertEquals("📞 Chiamata persa", CallService.logText(CallService.Direction.incoming, CallService.EndReason.connectionLost, null))
        assertEquals("📞 Chiamata rifiutata", CallService.logText(CallService.Direction.incoming, CallService.EndReason.rejected, null))
    }
}
