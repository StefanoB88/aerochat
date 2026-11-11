package com.stefanobrandi.aerochat.core.transport

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Consegna verso telefoni bloccati: risveglio GATT dopo gli invii (iPhone con app sospesa) e
 * riconnessione dopo la chiusura del link "tenuto" di una coppia duplicata.
 */
class BackgroundWakeTests {

    private val context = ContextWrapper(null)

    private fun device(): String = UUID.randomUUID().toString()

    @Test
    fun throttleAllowsOneNudgePerIntervalPerKey() {
        val throttle = WakeNudgeThrottle<String>(intervalMs = 3_000)
        assertTrue(throttle.shouldNudge("a", nowMs = 10_000))
        assertFalse(throttle.shouldNudge("a", nowMs = 11_000))
        assertTrue("chiavi indipendenti", throttle.shouldNudge("b", nowMs = 11_000))
        assertTrue(throttle.shouldNudge("a", nowMs = 13_000))
        throttle.forget("a")
        assertTrue("dopo forget si riparte subito", throttle.shouldNudge("a", nowMs = 13_001))
        // Orologio tornato indietro: meglio un risveglio in piu' che nessuno.
        assertTrue(throttle.shouldNudge("b", nowMs = 1_000))
    }

    @Test
    fun sendsNudgeTheLinkOncePerBurst() = runBlocking {
        val transport = BLETransportActor(context, ByteArray(8) { 0x10 })
        val nudged = CopyOnWriteArrayList<LinkID>()
        transport.nudgeObserverForTesting = { nudged.add(it) }
        val peer = ByteArray(8) { 0x42 }
        val link = TransportTests.FakeLink(LinkID(device(), LinkRole.peripheral))
        transport.register(link, link.id)
        transport.identifyForTesting(link.id.deviceID, peer, LinkRole.peripheral)

        assertTrue(transport.send(byteArrayOf(1), toNode = peer))
        transport.send(byteArrayOf(2), to = link.id)
        transport.broadcast(byteArrayOf(3))
        assertEquals(3, link.sent.size)
        assertEquals("un solo risveglio per raffica", listOf(link.id), nudged.toList())

        // Link richiuso e riaperto (nuovo canale): il primo invio risveglia di nuovo.
        transport.closeLink(link.id)
        val reopened = TransportTests.FakeLink(link.id)
        transport.register(reopened, reopened.id)
        transport.send(byteArrayOf(4), to = reopened.id)
        assertEquals(2, nudged.size)
    }

    @Test
    fun failedSendDoesNotNudge() = runBlocking {
        val transport = BLETransportActor(context, ByteArray(8) { 0x10 })
        val nudged = CopyOnWriteArrayList<LinkID>()
        transport.nudgeObserverForTesting = { nudged.add(it) }
        val bad = TransportTests.FakeLink(LinkID(device(), LinkRole.central)).apply { failSend = true }
        transport.register(bad, bad.id)
        assertEquals(0, transport.broadcast(byteArrayOf(1)))
        assertTrue(nudged.isEmpty())
    }

    @Test
    fun suppressedAddressIsResumedEvenWhenItIsTheClosedLinkAddress() {
        // Un iPhone (o Android) ci contatta dallo stesso indirizzo a cui eravamo collegati come central:
        // la soppressione anti-duplicato sta proprio su quell'indirizzo e va tolta alla chiusura.
        val peer = ByteArray(8) { 0x01 }
        val other = ByteArray(8) { 0x02 }
        val map = mapOf("AA:AA" to peer, "BB:BB" to peer, "CC:CC" to other)
        assertEquals(setOf("AA:AA", "BB:BB"), BLETransportActor.devicesToResume(peer, map).toSet())
        assertTrue(BLETransportActor.devicesToResume(ByteArray(8) { 0x03 }, map).isEmpty())
    }
}
