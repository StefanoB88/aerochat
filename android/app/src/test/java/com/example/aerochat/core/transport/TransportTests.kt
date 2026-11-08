package com.example.aerochat.core.transport

import android.content.ContextWrapper
import com.example.aerochat.core.protocol.FrameCodec
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.uInt16FromLittleEndianBytes
import com.example.aerochat.core.protocol.uInt16LittleEndianBytes
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Trasporto BLE – framing su stream, registro link, politiche.
 * Tutto senza radio: i socket L2CAP sono simulati con stream "piped", i link dell'attore con [FakeLink].
 * Il percorso CoreBluetooth/android.bluetooth reale richiede hardware e non e' testato qui.
 */
class TransportTests {

    /** Context finto: l'attore lo usa solo in `start()`, che questi test non chiamano. */
    private val context = ContextWrapper(null)

    private fun randomDevice(): String = UUID.randomUUID().toString()

    /** Coppia di connessioni collegate tra loro tramite pipe (simula un canale L2CAP con buffer da 4 KB). */
    private fun makeLinkedPair(
        onFrameA: (ByteArray) -> Unit,
        onFrameB: (ByteArray) -> Unit,
        onCloseB: (Throwable?) -> Unit = {},
    ): Pair<L2CAPConnection, L2CAPConnection> {
        val inputAToB = PipedInputStream(4_096)
        val outputAToB = PipedOutputStream(inputAToB)
        val inputBToA = PipedInputStream(4_096)
        val outputBToA = PipedOutputStream(inputBToA)
        val a = L2CAPConnection(
            id = LinkID(randomDevice(), LinkRole.central),
            inputStream = inputBToA, outputStream = outputAToB,
            onFrame = onFrameA, onClose = {},
        )
        val b = L2CAPConnection(
            id = LinkID(randomDevice(), LinkRole.peripheral),
            inputStream = inputAToB, outputStream = outputBToA,
            onFrame = onFrameB, onClose = onCloseB,
        )
        return a to b
    }

    @Test
    fun connectionPumpsFramesAcrossLinkedStreams() {
        val payloads = listOf(
            "ciao".toByteArray(),
            ByteArray(70_000) { 0xAB.toByte() }, // > buffer da 4 KB: piu' giri di scrittura bloccante
            ByteArray(300) { (it % 251).toByte() },
        )
        val received = LinkedBlockingQueue<ByteArray>()
        val (a, b) = makeLinkedPair(onFrameA = {}, onFrameB = { received.put(it) })
        a.open()
        b.open()
        payloads.forEach { a.send(it) }

        val collected = (payloads.indices).map {
            received.poll(5, TimeUnit.SECONDS) ?: run { fail("frame $it non arrivato"); ByteArray(0) }
        }
        payloads.zip(collected).forEach { (expected, actual) -> assertArrayEquals(expected, actual) }
        a.close()
        b.close()
    }

    @Test
    fun connectionReportsRemoteClose() {
        val closed = CountDownLatch(1)
        val (a, b) = makeLinkedPair(onFrameA = {}, onFrameB = {}, onCloseB = { closed.countDown() })
        a.open()
        b.open()
        a.send(byteArrayOf(1, 2, 3))
        a.close()
        assertTrue("onClose non chiamato", closed.await(5, TimeUnit.SECONDS))
        assertTrue(b.isClosed)
    }

    @Test
    fun connectionRejectsEmptyPayload() {
        val (a, _) = makeLinkedPair(onFrameA = {}, onFrameB = {})
        try {
            a.send(ByteArray(0))
            fail("atteso FrameCodec.Failure.EmptyFrame")
        } catch (error: FrameCodec.Failure.EmptyFrame) {
            // ok
        }
    }

    @Test
    fun connectionRejectsSendAfterClose() {
        val (a, _) = makeLinkedPair(onFrameA = {}, onFrameB = {})
        a.close()
        try {
            a.send(byteArrayOf(1))
            fail("atteso TransportError.LinkClosed")
        } catch (error: TransportError.LinkClosed) {
            // ok
        }
    }

    @Test
    fun connectionClosesOnMalformedFrame() {
        val input = PipedInputStream(4_096)
        val remote = PipedOutputStream(input)
        val closed = LinkedBlockingQueue<Any>()
        val connection = L2CAPConnection(
            id = LinkID(randomDevice(), LinkRole.peripheral),
            inputStream = input, outputStream = java.io.ByteArrayOutputStream(),
            onFrame = {}, onClose = { closed.put(it ?: "nil") },
        )
        connection.open()
        // Lunghezza 0 = frame vuoto: il peer non parla il nostro protocollo.
        remote.write(byteArrayOf(0, 0, 0, 0))
        remote.flush()
        val reason = closed.poll(5, TimeUnit.SECONDS)
        assertTrue("atteso errore di framing, ricevuto $reason", reason is FrameCodec.Failure)
    }

    /**
     * Regressione foto: una raffica di chunk media (~8.5 KB l'uno, come `MEDIA_CHUNK` cifrato) accodata
     * tutta in un colpo su un canale lento (pipe da 1 KB) deve arrivare completa e in ordine, in entrambe
     * le direzioni contemporaneamente, senza backpressure ne' chiusure del link.
     */
    @Test
    fun connectionDeliversBurstOfLargeFramesInOrderBothWays() {
        val random = java.util.Random(42)
        fun burst() = List(48) { index -> ByteArray(8_400 + index) { random.nextInt().toByte() } }
        val toB = burst()
        val toA = burst()
        val receivedByB = LinkedBlockingQueue<ByteArray>()
        val receivedByA = LinkedBlockingQueue<ByteArray>()
        val closes = LinkedBlockingQueue<Any>()
        val inputAToB = PipedInputStream(1_024)
        val outputAToB = PipedOutputStream(inputAToB)
        val inputBToA = PipedInputStream(1_024)
        val outputBToA = PipedOutputStream(inputBToA)
        val a = L2CAPConnection(
            id = LinkID(randomDevice(), LinkRole.central), inputStream = inputBToA, outputStream = outputAToB,
            onFrame = { receivedByA.put(it) }, onClose = { closes.put(it ?: "nil") },
        )
        val b = L2CAPConnection(
            id = LinkID(randomDevice(), LinkRole.peripheral), inputStream = inputAToB, outputStream = outputBToA,
            onFrame = { receivedByB.put(it) }, onClose = { closes.put(it ?: "nil") },
        )
        a.open()
        b.open()
        // Accodati senza attendere la scrittura, come fa il router per ogni chunk.
        toB.forEach { a.send(it) }
        toA.forEach { b.send(it) }

        toB.forEachIndexed { index, expected ->
            val actual = receivedByB.poll(10, TimeUnit.SECONDS) ?: return fail("chunk $index A->B non arrivato")
            assertArrayEquals("chunk $index A->B", expected, actual)
        }
        toA.forEachIndexed { index, expected ->
            val actual = receivedByA.poll(10, TimeUnit.SECONDS) ?: return fail("chunk $index B->A non arrivato")
            assertArrayEquals("chunk $index B->A", expected, actual)
        }
        assertNull("il link non deve chiudersi durante la raffica", closes.poll())
        assertEquals(toB.sumOf { it.size + FrameCodec.headerLength }.toLong(), a.bytesSent)
        assertEquals(a.bytesSent, b.bytesReceived)
        a.close()
        b.close()
    }

    /** Lo stream L2CAP consegna SDU di dimensione arbitraria: il frame va ricomposto a cavallo delle letture. */
    @Test
    fun connectionReassemblesFramesSplitAcrossSmallSDUs() {
        val input = PipedInputStream(16_384)
        val remote = PipedOutputStream(input)
        val received = LinkedBlockingQueue<ByteArray>()
        val connection = L2CAPConnection(
            id = LinkID(randomDevice(), LinkRole.peripheral),
            inputStream = input, outputStream = java.io.ByteArrayOutputStream(),
            onFrame = { received.put(it) }, onClose = {},
        )
        connection.open()
        val random = java.util.Random(7)
        val payloads = List(12) { ByteArray(8_000 + it * 37) { random.nextInt().toByte() } }
        val stream = payloads.fold(ByteArray(0)) { acc, payload -> acc + FrameCodec.frame(payload) }
        // SDU come quelli di iOS/Android (MTU variabili, anche a cavallo dell'header di 4 byte).
        var offset = 0
        var step = 0
        val sizes = intArrayOf(1, 3, 251, 495, 2, 1_251, 4_096, 7)
        while (offset < stream.size) {
            val size = minOf(sizes[step++ % sizes.size], stream.size - offset)
            remote.write(stream, offset, size)
            remote.flush()
            offset += size
        }
        payloads.forEachIndexed { index, expected ->
            val actual = received.poll(5, TimeUnit.SECONDS) ?: return fail("frame $index non ricomposto")
            assertArrayEquals(expected, actual)
        }
        connection.close()
    }

    // MARK: - Registro link dell'attore (con link finti, senza radio)

    class FakeLink(override val id: LinkID) : FrameLink {
        val sent = CopyOnWriteArrayList<ByteArray>()
        @Volatile var isClosed = false
        @Volatile var failSend = false

        override fun send(payload: ByteArray) {
            if (failSend) throw TransportError.WriteFailed("fake")
            sent.add(payload)
        }

        override fun close() {
            isClosed = true
        }
    }

    @Test
    fun broadcastDeduplicatesByNodeID() = runBlocking {
        val me = ByteArray(8) { 0x10 }
        val transport = BLETransportActor(context, me)
        val peerA = ByteArray(8) { 0xA0.toByte() }

        val deviceA1 = randomDevice()
        val deviceA2 = randomDevice()
        val deviceUnknown = randomDevice()
        // Il peer A e' raggiungibile su due link (abbiamo iniziato noi e lui ha iniziato con noi).
        val linkA1 = FakeLink(LinkID(deviceA1, LinkRole.peripheral))
        val linkA2 = FakeLink(LinkID(deviceA2, LinkRole.central))
        val linkUnknown = FakeLink(LinkID(deviceUnknown, LinkRole.central))

        // Nodo con id maggiore del nostro: entrambi i link restano (siamo noi a dover iniziare).
        transport.register(linkA1, linkA1.id)
        transport.register(linkA2, linkA2.id)
        transport.register(linkUnknown, linkUnknown.id)
        transport.identifyForTesting(deviceA1, peerA, LinkRole.peripheral)
        transport.identifyForTesting(deviceA2, peerA, LinkRole.central)

        assertEquals(3, transport.activeLinks().size)
        val reached = transport.broadcast(byteArrayOf(0x01), excluding = emptySet())
        assertEquals("una copia per peerA + una per il link non identificato", 2, reached)
        assertEquals(1, linkA1.sent.size + linkA2.sent.size)
        assertEquals(1, linkUnknown.sent.size)

        val reachedExcluding = transport.broadcast(byteArrayOf(0x02), excluding = setOf(linkUnknown.id))
        assertEquals(1, reachedExcluding)
        assertEquals(1, linkUnknown.sent.size)

        // Invio mirato: preferisce il link che abbiamo aperto noi (ruolo central).
        assertTrue(transport.send(byteArrayOf(0x03), toNode = peerA))
        assertArrayEquals(byteArrayOf(0x03), linkA2.sent.last())
        assertFalse(transport.send(byteArrayOf(0x04), toNode = ByteArray(8) { 0xFF.toByte() }))
        try {
            transport.send(byteArrayOf(0x05), to = LinkID(randomDevice(), LinkRole.central))
            fail("atteso TransportError.LinkNotFound")
        } catch (error: TransportError.LinkNotFound) {
            // ok
        }
    }

    @Test
    fun broadcastSkipsFailingLinks() = runBlocking {
        val transport = BLETransportActor(context, ByteArray(8) { 0x10 })
        val good = FakeLink(LinkID(randomDevice(), LinkRole.central))
        val bad = FakeLink(LinkID(randomDevice(), LinkRole.central)).apply { failSend = true }
        transport.register(good, good.id)
        transport.register(bad, bad.id)
        assertEquals(1, transport.broadcast(byteArrayOf(7)))
    }

    @Test
    fun duplicateLinkResolutionPrefersSmallerInitiator() = runBlocking {
        val me = ByteArray(8) { 0xF0.toByte() } // noi siamo "maggiori" (confronto senza segno)
        val peer = ByteArray(8) { 0x01 }
        val transport = BLETransportActor(context, me)
        assertFalse(transport.shouldInitiateConnection(peer))

        val inbound = FakeLink(LinkID(randomDevice(), LinkRole.peripheral))
        val outbound = FakeLink(LinkID(randomDevice(), LinkRole.central))
        transport.register(inbound, inbound.id)
        transport.register(outbound, outbound.id)
        transport.identifyForTesting(inbound.id.deviceID, peer, LinkRole.peripheral)
        transport.identifyForTesting(outbound.id.deviceID, peer, LinkRole.central)

        val remaining = transport.activeLinks()
        assertEquals(1, remaining.size)
        assertEquals(inbound.id, remaining.first().id)
        assertTrue(outbound.isClosed)
    }

    @Test
    fun smallerNodeKeepsBothLinksUntilPeerResolves() = runBlocking {
        val me = ByteArray(8) { 0x01 }
        val peer = ByteArray(8) { 0xF0.toByte() }
        val transport = BLETransportActor(context, me)
        assertTrue(transport.shouldInitiateConnection(peer))
        val inbound = FakeLink(LinkID(randomDevice(), LinkRole.peripheral))
        val outbound = FakeLink(LinkID(randomDevice(), LinkRole.central))
        transport.register(inbound, inbound.id)
        transport.register(outbound, outbound.id)
        transport.identifyForTesting(inbound.id.deviceID, peer, LinkRole.peripheral)
        transport.identifyForTesting(outbound.id.deviceID, peer, LinkRole.central)
        assertEquals(2, transport.activeLinks().size)
        assertFalse(outbound.isClosed)
    }

    @Test
    fun nodeIDLearnedBeforeChannelIsAppliedOnRegister() = runBlocking {
        // Handshake GATT completato prima dell'apertura L2CAP: il link nasce gia' identificato.
        val transport = BLETransportActor(context, ByteArray(8) { 0x10 })
        val device = randomDevice()
        val peer = ByteArray(8) { 0x42 }
        transport.identifyForTesting(device, peer, LinkRole.central)
        val link = FakeLink(LinkID(device, LinkRole.central))
        transport.register(link, link.id)
        val opened = transport.activeLinks().single()
        assertTrue(opened.isIdentified)
        assertArrayEquals(peer, opened.nodeID)
    }

    @Test
    fun eventStreamDeliversLifecycle() = runBlocking {
        val transport = BLETransportActor(context, ByteArray(8) { 0x10 })
        val events = transport.events()

        val link = FakeLink(LinkID(randomDevice(), LinkRole.central))
        val peer = ByteArray(8) { 0x77 }
        transport.register(link, link.id)
        transport.identifyForTesting(link.id.deviceID, peer, LinkRole.central)
        transport.received(byteArrayOf(9, 9), from = link.id)
        transport.closeLink(link.id)

        val collected = withTimeout(5_000) { events.take(4).toList() }
        assertEquals(4, collected.size)
        val opened = collected[0] as TransportEvent.LinkOpened
        assertEquals(link.id, opened.link.id)
        assertNull(opened.link.nodeID)
        assertEquals(TransportEvent.LinkIdentified(link.id, peer), collected[1])
        assertEquals(TransportEvent.FrameReceived(byteArrayOf(9, 9), link.id, peer), collected[2])
        assertEquals(TransportEvent.LinkClosed(link.id, peer), collected[3])
        assertTrue(link.isClosed)
        assertTrue(transport.activeLinks().isEmpty())
    }

    @Test
    fun gattContract() {
        assertArrayEquals(byteArrayOf(0xC1.toByte(), 0x00), uInt16LittleEndianBytes(0x00C1))
        assertEquals(0x00C1, uInt16FromLittleEndianBytes(byteArrayOf(0xC1.toByte(), 0x00)))
        // PSM dinamici Android (0x80..0xFF) e oltre i 15 bit restano senza segno.
        assertEquals(0x80F1, uInt16FromLittleEndianBytes(uInt16LittleEndianBytes(0x80F1)))
        assertTrue(MeshProtocol.estimatedLegacyAdvertisingBytes <= MeshProtocol.maxLegacyAdvertisingBytes)
        assertEquals(
            "F1164700-B1E0-4A18-9C0D-000000000001",
            TransportConstants.serviceUUID.toString().uppercase(),
        )
        assertEquals("f1164700-b1e0-4a18-9c0d-000000000002", TransportConstants.psmCharacteristicUUID.toString())
        assertEquals("f1164700-b1e0-4a18-9c0d-000000000003", TransportConstants.nodeInfoCharacteristicUUID.toString())
        assertEquals(8, MeshProtocol.maxConcurrentLinks)
    }

    @Test
    fun lexicographicOrderIsUnsigned() {
        assertTrue(byteArrayOf(0x01).lexicographicallyPrecedes(byteArrayOf(0xF0.toByte())))
        assertFalse(byteArrayOf(0xF0.toByte()).lexicographicallyPrecedes(byteArrayOf(0x01)))
        assertTrue(byteArrayOf(0x01).lexicographicallyPrecedes(byteArrayOf(0x01, 0x00)))
        assertFalse(byteArrayOf(0x01).lexicographicallyPrecedes(byteArrayOf(0x01)))
    }

    @Test
    fun linkIDDescriptionAndEquality() {
        val id = LinkID("AA:BB:CC:DD:EE:FF", LinkRole.central)
        assertEquals("central:AA:BB:CC", id.toString())
        assertEquals(id, LinkID("AA:BB:CC:DD:EE:FF", LinkRole.central))
        assertFalse(id == LinkID("AA:BB:CC:DD:EE:FF", LinkRole.peripheral))
    }

    @Test
    fun highSpeedTransportsFallBackToBLE() = runBlocking {
        val selector = HighSpeedTransportSelector()
        assertNull(selector.best())
        val missing = File("/nonexistent/${UUID.randomUUID()}.jpg")
        try {
            WiFiAwareTransport().sendFile(missing, ByteArray(8) { 1 }, null)
            fail("atteso HighSpeedTransportError.FileNotFound")
        } catch (error: HighSpeedTransportError.FileNotFound) {
            // ok
        }
    }

    /** Regressione: i dispositivi non fidati che falliscono l'handshake vengono ritentati con backoff. */
    @Test
    fun scanRetryBackoffGrowsAndCaps() {
        assertEquals(1_000L, BLECentralController.scanRetryBackoffMs(1))
        assertEquals(2_000L, BLECentralController.scanRetryBackoffMs(2))
        assertEquals(16_000L, BLECentralController.scanRetryBackoffMs(5))
        assertEquals(BLECentralController.MAX_SCAN_RETRY_BACKOFF_MS, BLECentralController.scanRetryBackoffMs(6))
        assertEquals(BLECentralController.MAX_SCAN_RETRY_BACKOFF_MS, BLECentralController.scanRetryBackoffMs(1_000))
        assertEquals(1_000L, BLECentralController.scanRetryBackoffMs(0))
    }
}
