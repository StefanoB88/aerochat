package com.stefanobrandi.aerochat.core.mesh

import android.content.Context
import com.stefanobrandi.aerochat.core.crypto.IdentityKeys
import com.stefanobrandi.aerochat.core.crypto.PairingPayload
import com.stefanobrandi.aerochat.core.transport.LinkID
import com.stefanobrandi.aerochat.core.transport.LinkRole
import com.stefanobrandi.aerochat.core.transport.PeerLink
import com.stefanobrandi.aerochat.core.transport.RadioState
import com.stefanobrandi.aerochat.core.transport.TransportError
import com.stefanobrandi.aerochat.core.transport.TransportEvent
import com.stefanobrandi.aerochat.core.transport.TransportProvider
import com.stefanobrandi.aerochat.data.AppDatabase
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.IdentityRecord
import com.stefanobrandi.aerochat.data.records.MessageRecord
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion

/**
 * Trasporto finto in memoria: piu' [FakeTransport] collegati tra loro simulano link L2CAP
 * bidirezionali gia' identificati (come dopo lo scambio del node_id via GATT).
 *
 * Stato protetto da `synchronized`; le consegne al trasporto remoto avvengono fuori dal lock
 * (due nodi che si scrivono a vicenda non si bloccano).
 */
class FakeTransport(override val localNodeID: ByteArray) : TransportProvider {

    class Sent(val link: LinkID, val frame: ByteArray)

    private class Remote(val transport: FakeTransport, val remoteLink: LinkID)

    private val lock = Any()
    private val remotes = LinkedHashMap<LinkID, Remote>()
    private val peers = LinkedHashMap<LinkID, PeerLink>()
    private val subscriberIDs = AtomicLong(0)
    private val subscribers = ConcurrentHashMap<Long, Channel<TransportEvent>>()
    private val sentFrames = ArrayList<Sent>()

    @Volatile
    var trustedIdentifiers: Set<String> = emptySet()
        private set

    @Volatile
    var state: RadioState = RadioState.poweredOn

    val sent: List<Sent> get() = synchronized(lock) { sentFrames.toList() }

    override suspend fun start() {}
    override suspend fun stop() {}

    override fun events(): Flow<TransportEvent> {
        val id = subscriberIDs.incrementAndGet()
        val channel = Channel<TransportEvent>(Channel.UNLIMITED)
        subscribers[id] = channel
        return channel.consumeAsFlow().onCompletion { subscribers.remove(id) }
    }

    override suspend fun radioState(): RadioState = state

    override suspend fun activeLinks(): List<PeerLink> = synchronized(lock) { peers.values.toList() }

    override suspend fun send(frame: ByteArray, to: LinkID) {
        val remote = synchronized(lock) {
            val remote = remotes[to] ?: throw TransportError.LinkNotFound
            sentFrames.add(Sent(to, frame))
            remote
        }
        remote.transport.inject(frame, from = remote.remoteLink)
    }

    override suspend fun send(frame: ByteArray, toNode: ByteArray): Boolean {
        val link = synchronized(lock) { peers.values.firstOrNull { it.nodeID?.contentEquals(toNode) == true } } ?: return false
        send(frame, to = link.id)
        return true
    }

    override suspend fun broadcast(frame: ByteArray, excluding: Set<LinkID>): Int {
        val snapshot = synchronized(lock) { peers.entries.map { it.key to it.value } }
        val reached = HashSet<String>()
        var count = 0
        for ((id, peer) in snapshot) {
            if (excluding.contains(id)) continue
            val nodeID = peer.nodeID ?: continue
            if (!reached.add(nodeID.contentToString())) continue
            try {
                send(frame, to = id)
            } catch (_: Exception) {
            }
            count += 1
        }
        return count
    }

    override suspend fun setTrustedPeripheralIdentifiers(identifiers: Set<String>) {
        trustedIdentifiers = identifiers
    }

    // MARK: - Simulazione

    fun inject(frame: ByteArray, from: LinkID) {
        val nodeID = synchronized(lock) { peers[from]?.nodeID }
        emit(TransportEvent.FrameReceived(frame, from = from, nodeID = nodeID))
    }

    fun attach(remote: FakeTransport, localLink: LinkID, remoteLink: LinkID, rssi: Int = -60) {
        val peer = PeerLink(id = localLink, nodeID = remote.localNodeID, rssi = rssi, connectedAt = System.currentTimeMillis())
        synchronized(lock) {
            remotes[localLink] = Remote(remote, remoteLink)
            peers[localLink] = peer
        }
        emit(TransportEvent.LinkOpened(peer))
    }

    fun detach(localLink: LinkID) {
        val peer = synchronized(lock) {
            remotes.remove(localLink)
            peers.remove(localLink)
        }
        if (peer != null) emit(TransportEvent.LinkClosed(localLink, nodeID = peer.nodeID))
    }

    fun framesSent(): Int = synchronized(lock) { sentFrames.size }

    private fun emit(event: TransportEvent) {
        for (channel in subscribers.values) channel.trySend(event)
    }
}

/** Collega due trasporti finti con un link bidirezionale. */
object FakeMeshNetwork {
    fun connect(a: FakeTransport, b: FakeTransport): Pair<LinkID, LinkID> {
        val linkA = LinkID(deviceID = UUID.randomUUID().toString(), role = LinkRole.central)
        val linkB = LinkID(deviceID = UUID.randomUUID().toString(), role = LinkRole.peripheral)
        a.attach(b, localLink = linkA, remoteLink = linkB)
        b.attach(a, localLink = linkB, remoteLink = linkA)
        return linkA to linkB
    }

    fun disconnect(a: FakeTransport, linkA: LinkID, b: FakeTransport, linkB: LinkID) {
        a.detach(localLink = linkA)
        b.detach(localLink = linkB)
    }
}

/** Nodo di test completo: identita', DB temporaneo (Room in memoria), trasporto finto e router avviato. */
class TestNode(
    val nickname: String,
    val identity: IdentityKeys,
    val database: AppDatabase,
    val transport: FakeTransport,
    val router: MeshRouterActor,
    val notifier: RecordingNotifier,
) {
    val pairingPayload: PairingPayload get() = PairingPayload(identity = identity, nickname = nickname)

    val nodeID: ByteArray get() = identity.nodeID

    suspend fun directConversation(with: TestNode): ConversationRecord {
        val peer = database.peers.fetch(nodeID = with.nodeID) ?: throw RouterError.UnknownPeer(with.nodeID)
        return database.conversations.ensureDirectConversation(with = peer)
    }

    companion object {
        /** `relayBackoff` in ms (iOS `0.0...0.05` s). */
        suspend fun make(
            context: Context,
            nickname: String,
            relayEnabled: Boolean = true,
            relayBackoff: LongRange = 0L..50L,
        ): TestNode {
            val identity = IdentityKeys.generate()
            val database = AppDatabase.makeTemporary(context)
            database.identities.save(IdentityRecord(identity = identity, nickname = nickname, avatarColorHex = "00E5FF"))
            val transport = FakeTransport(localNodeID = identity.nodeID)
            val notifier = RecordingNotifier()
            val configuration = MeshRouterActor.Configuration(
                relayEnabled = relayEnabled,
                relayBackoff = relayBackoff,
                maintenanceInterval = 3_600_000L,
            )
            val router = MeshRouterActor(
                identity = identity, database = database, transport = transport,
                nickname = nickname, notifier = notifier, configuration = configuration,
            )
            router.start()
            return TestNode(nickname, identity, database, transport, router, notifier)
        }

        /** Pairing reciproco via QR (entrambi si scansionano). */
        suspend fun pair(a: TestNode, b: TestNode) {
            a.router.pair(with = b.pairingPayload)
            b.router.pair(with = a.pairingPayload)
        }
    }
}

class RecordingNotifier : MeshNotifier {
    private val lock = Any()
    private val recorded = ArrayList<MessageRecord>()
    val notified: List<MessageRecord> get() = synchronized(lock) { recorded.toList() }

    override suspend fun notify(message: MessageRecord, conversation: ConversationRecord) {
        synchronized(lock) { recorded.add(message) }
    }
}

/**
 * Attende (max `timeout` ms, tempo reale) che la condizione asincrona diventi vera
 * (un'eccezione conta come `false`).
 */
suspend fun eventually(timeout: Long = 5_000, interval: Long = 25, condition: suspend () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeout
    while (System.currentTimeMillis() < deadline) {
        if (runCatching { condition() }.getOrDefault(false)) return true
        delay(interval)
    }
    return runCatching { condition() }.getOrDefault(false)
}

/**
 * Base dei test end-to-end del router: crea [TestNode] su Room in memoria (Robolectric) e li chiude
 * a fine test. I test girano su dispatcher reali (`runBlocking` + timeout), non in tempo virtuale:
 * il router usa `Dispatchers.Default` e Room i suoi executor.
 */
abstract class MeshTestCase {

    protected val context: Context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    private val nodes = java.util.Collections.synchronizedList(ArrayList<TestNode>())

    protected suspend fun node(
        nickname: String,
        relayEnabled: Boolean = true,
        relayBackoff: LongRange = 0L..50L,
    ): TestNode = TestNode.make(context, nickname, relayEnabled, relayBackoff).also { nodes.add(it) }

    /** Esegue un test asincrono con timeout globale. */
    protected fun meshTest(timeoutMs: Long = 60_000, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(timeoutMs) { block() } }

    @org.junit.After
    fun closeNodes() {
        kotlinx.coroutines.runBlocking {
            for (node in nodes.toList()) {
                node.router.stop()
            }
            // Lascia terminare eventuali relay/ACK in volo prima di chiudere i DB.
            delay(50)
            for (node in nodes.toList()) node.database.close()
        }
        nodes.clear()
    }
}
