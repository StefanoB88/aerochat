package com.example.aerochat.core.transport

import android.content.Context
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Trasporto BLE dual-role: orchestra [BLECentralController] + [BLEPeripheralController],
 * adotta i socket L2CAP in [L2CAPConnection] e offre al router un'API uniforme
 * basata su link identificati dal `node_id` del vicino.
 *
 * Equivalente dell'`actor` Swift: tutto lo stato e' confinato a un dispatcher seriale
 * (`limitedParallelism(1)`); cio' che arriva dai controller e dai thread dei socket passa da
 * un'unica coda FIFO ([inputs]), cosi' frame e chiusure non vengono riordinati.
 *
 * Requisiti prima di [start]: permessi runtime di [BLEPermissions.requiredPermissions] (se mancano lo
 * stato e' `unauthorized` e il trasporto si avvia da solo appena vengono concessi), adapter acceso.
 * Il [context] viene usato solo da [start] (ne prende l'`applicationContext`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BLETransportActor(
    private val context: Context,
    override val localNodeID: ByteArray,
    val configuration: Configuration = Configuration(),
) : TransportProvider {

    /** Su iOS conteneva anche gli identificatori di State Restoration, che su Android non esistono. */
    data class Configuration(
        val maxConcurrentLinks: Int = TransportConstants.maxConcurrentLinks,
    )

    private class LinkState(
        val connection: FrameLink,
        var peer: PeerLink,
        /** Ordine di registrazione: spareggio a parita' di `connectedAt` (ms). */
        val sequence: Long,
    )

    private sealed interface Input {
        class Central(val event: BLECentralController.Event) : Input
        class Peripheral(val event: BLEPeripheralController.Event) : Input
        class Frame(val frame: ByteArray, val id: LinkID, val token: Any) : Input
        class Closed(val id: LinkID, val token: Any) : Input
    }

    private val stateDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + stateDispatcher)
    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private var inputJob: Job? = null

    /** Nuova a ogni `start()`: una coda fermata non si puo' riavviare. */
    private var bleQueue: RunLoopThread? = null
    private var central: BLECentralController? = null
    private var peripheral: BLEPeripheralController? = null
    private var isStarted = false

    private var centralState = RadioState.unknown
    private var peripheralState = RadioState.unknown
    private var isAdvertising = false
    private var publishedPSM = 0

    private val links = mutableMapOf<LinkID, LinkState>()
    private val linkSequence = AtomicLong(0)
    /** `node_id` appresi via GATT, indicizzati per indirizzo del dispositivo remoto. */
    private val nodeIDsByDevice = mutableMapOf<String, ByteArray>()
    private val subscribers = ConcurrentHashMap<Long, Channel<TransportEvent>>()
    private val subscriberIDs = AtomicLong(0)
    /** Ultimo "risveglio" GATT per link (vedi [nudgeAfterSend]). */
    private val wakeNudges = WakeNudgeThrottle<LinkID>(WAKE_NUDGE_INTERVAL_MS)

    /** Solo test: osserva i risvegli GATT che verrebbero inviati (i controller sono `null` senza radio). */
    internal var nudgeObserverForTesting: ((LinkID) -> Unit)? = null

    init {
        require(localNodeID.size == MeshProtocol.nodeIDLength) { "node_id deve essere di 8 byte" }
    }

    // MARK: - Ciclo di vita

    override suspend fun start(): Unit = onState {
        if (isStarted) return@onState
        isStarted = true
        startInputLoopIfNeeded()

        val appContext = context.applicationContext ?: context
        val queue = RunLoopThread()
        bleQueue = queue
        val centralController = BLECentralController(
            context = appContext,
            localNodeID = localNodeID,
            queue = queue,
            maxConcurrentLinks = configuration.maxConcurrentLinks,
        ) { event -> inputs.trySend(Input.Central(event)) }
        val peripheralController = BLEPeripheralController(
            context = appContext,
            localNodeID = localNodeID,
            queue = queue,
        ) { event -> inputs.trySend(Input.Peripheral(event)) }
        central = centralController
        peripheral = peripheralController
        centralController.start()
        peripheralController.start()
    }

    override suspend fun stop(): Unit = onState {
        if (!isStarted) return@onState
        isStarted = false
        for ((id, state) in links) {
            state.connection.close()
            emit(TransportEvent.LinkClosed(id, state.peer.nodeID))
        }
        links.clear()
        central?.stop()
        peripheral?.stop()
        central = null
        peripheral = null
        // Gli stop dei controller sono gia' in coda: `shutdown` li esegue prima di fermarla.
        bleQueue?.shutdown()
        bleQueue = null
    }

    /** Unico consumatore della coda di input (vive quanto l'oggetto, sopravvive a stop/start). */
    private fun startInputLoopIfNeeded() {
        if (inputJob != null) return
        inputJob = scope.launch {
            for (input in inputs) {
                // Un'eccezione qui terminerebbe l'unico consumatore (trasporto sordo per sempre) e, senza
                // CoroutineExceptionHandler, arriverebbe all'uncaught handler: crash del processo.
                try {
                    process(input)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.e("AeroMesh.Transport", "evento di trasporto scartato", error)
                }
            }
        }
    }

    private fun process(input: Input) {
        when (input) {
            is Input.Central -> handleCentral(input.event)
            is Input.Peripheral -> handlePeripheral(input.event)
            is Input.Frame -> {
                if (links[input.id]?.connection !== input.token) return
                receivedLocked(input.frame, input.id)
            }
            is Input.Closed -> {
                if (links[input.id]?.connection !== input.token) return
                closeLinkLocked(input.id)
            }
        }
    }

    /** Compagni fidati da tenere in connessione perenne (indirizzi `BluetoothDevice.address`). */
    override suspend fun setTrustedPeripheralIdentifiers(identifiers: Set<String>): Unit = onState {
        central?.setTrustedPeripherals(identifiers)
    }

    suspend fun setScanning(enabled: Boolean): Unit = onState { central?.setScanning(enabled) }

    suspend fun setAdvertising(enabled: Boolean): Unit = onState { peripheral?.setAdvertising(enabled) }

    /**
     * Estensione Android: primo piano → scansione/advertising LOW_LATENCY; background → modalita'
     * a basso consumo. Da chiamare dal ciclo di vita dell'app.
     */
    suspend fun setForeground(isForeground: Boolean): Unit = onState {
        central?.setLowLatency(isForeground)
        peripheral?.setLowLatency(isForeground)
    }

    /** Estensione Android: ricontrolla subito permessi e adapter (es. appena concessi i permessi). */
    suspend fun refreshAuthorization(): Unit = onState {
        central?.refreshState()
        peripheral?.refreshState()
    }

    // MARK: - TransportProvider

    override fun events(): Flow<TransportEvent> {
        val id = subscriberIDs.incrementAndGet()
        // Senza limite: scartare eventi (linkOpened/linkClosed) desincronizzerebbe il router.
        val channel = Channel<TransportEvent>(Channel.UNLIMITED)
        subscribers[id] = channel
        return channel.consumeAsFlow().onCompletion { subscribers.remove(id) }
    }

    override suspend fun radioState(): RadioState = onState { combinedRadioState() }

    /** La radio e' una sola: se uno dei due ruoli non e' pronto, non lo e' nessuno. */
    private fun combinedRadioState(): RadioState =
        if (centralState.isReady && peripheralState.isReady) {
            RadioState.poweredOn
        } else if (centralState.isReady) {
            peripheralState
        } else {
            centralState
        }

    override suspend fun activeLinks(): List<PeerLink> = onState { sortedLinks().map { it.peer } }

    suspend fun isAdvertisingNow(): Boolean = onState { isAdvertising }

    suspend fun currentPSM(): Int = onState { publishedPSM }

    override suspend fun send(frame: ByteArray, to: LinkID): Unit = onState {
        val state = links[to] ?: throw TransportError.LinkNotFound
        state.connection.send(frame)
        nudgeAfterSend(state.peer.id)
    }

    override suspend fun send(frame: ByteArray, toNode: ByteArray): Boolean = onState {
        val target = preferredLink(toNode) ?: return@onState false
        target.connection.send(frame)
        nudgeAfterSend(target.peer.id)
        true
    }

    override suspend fun broadcast(frame: ByteArray, excluding: Set<LinkID>): Int = onState {
        val reachedNodes = mutableSetOf<String>()
        var count = 0
        for (state in sortedLinks()) {
            if (state.peer.id in excluding) continue
            val nodeID = state.peer.nodeID
            if (nodeID != null && !reachedNodes.add(nodeID.toHex())) continue
            try {
                state.connection.send(frame)
                count += 1
                nudgeAfterSend(state.peer.id)
            } catch (_: Exception) {
                continue
            }
        }
        count
    }

    // MARK: - Eventi dai controller

    private fun handleCentral(event: BLECentralController.Event) {
        when (event) {
            is BLECentralController.Event.StateChanged -> {
                centralState = event.state
                emit(TransportEvent.RadioStateChanged(combinedRadioState()))
            }
            is BLECentralController.Event.Discovered -> {
                val id = LinkID(event.deviceID, LinkRole.central)
                links[id]?.let { it.peer = it.peer.copy(rssi = event.rssi) }
                emit(TransportEvent.PeerDiscovered(event.deviceID, event.rssi))
            }
            is BLECentralController.Event.Connected -> Unit
            is BLECentralController.Event.Identified ->
                identify(event.deviceID, event.nodeID, LinkRole.central)
            is BLECentralController.Event.ChannelOpened ->
                adopt(event.channel, LinkID(event.deviceID, LinkRole.central))
            is BLECentralController.Event.Disconnected ->
                closeLinkLocked(LinkID(event.deviceID, LinkRole.central))
            is BLECentralController.Event.ConnectionFailed ->
                closeLinkLocked(LinkID(event.deviceID, LinkRole.central))
        }
    }

    private fun handlePeripheral(event: BLEPeripheralController.Event) {
        when (event) {
            is BLEPeripheralController.Event.StateChanged -> {
                peripheralState = event.state
                emit(TransportEvent.RadioStateChanged(combinedRadioState()))
            }
            is BLEPeripheralController.Event.PsmPublished -> publishedPSM = event.psm
            is BLEPeripheralController.Event.AdvertisingChanged -> {
                isAdvertising = event.isAdvertising
                emit(TransportEvent.AdvertisingChanged(event.isAdvertising))
            }
            is BLEPeripheralController.Event.CentralWroteNodeID ->
                identify(event.centralID, event.nodeID, LinkRole.peripheral)
            is BLEPeripheralController.Event.ChannelOpened ->
                adopt(event.channel, LinkID(event.centralID, LinkRole.peripheral))
            is BLEPeripheralController.Event.ChannelFailed -> Unit
        }
    }

    // MARK: - Gestione link

    /** Simula l'identificazione GATT di un dispositivo (test senza radio). */
    suspend fun identifyForTesting(deviceID: String, nodeID: ByteArray, role: LinkRole): Unit = onState {
        identify(deviceID, nodeID, role)
    }

    private fun identify(deviceID: String, nodeID: ByteArray, role: LinkRole) {
        nodeIDsByDevice[deviceID] = nodeID
        // Gli indirizzi BLE ruotano (indirizzi privati): oltre la soglia teniamo solo
        // i dispositivi con un link aperto, cosi' la mappa non cresce senza fine nei voli lunghi.
        if (nodeIDsByDevice.size > maxRememberedDevices) {
            val active = links.keys.map { it.deviceID }.toSet() + deviceID
            nodeIDsByDevice.keys.retainAll(active)
        }
        val id = LinkID(deviceID, role)
        val state = links[id]
        if (state != null && !state.peer.nodeID.contentEqualsNullable(nodeID)) {
            state.peer = state.peer.copy(nodeID = nodeID)
            emit(TransportEvent.LinkIdentified(id, nodeID))
        }
        resolveDuplicateLinks(nodeID)
    }

    private fun adopt(channel: L2CAPChannelBox, id: LinkID) {
        val socket = channel.channel
        if (!isStarted) {
            closeQuietly(socket)
            return
        }
        if (links.containsKey(id)) {
            // Riconnessione sullo stesso LinkID: chiudiamo (ed emettiamo linkClosed) il vecchio link.
            closeLinkLocked(id)
        }
        val streams = try {
            socket.inputStream to socket.outputStream
        } catch (error: IOException) {
            closeQuietly(socket)
            if (id.role == LinkRole.central) central?.channelClosed(id.deviceID)
            return
        }
        val box = ConnectionTokenBox()
        val connection = L2CAPConnection(
            id = id,
            inputStream = streams.first,
            outputStream = streams.second,
            closeable = socket,
            onFrame = { frame -> box.token?.let { inputs.trySend(Input.Frame(frame, id, it)) } },
            onClose = { _ -> box.token?.let { inputs.trySend(Input.Closed(id, it)) } },
        )
        box.token = connection
        registerLocked(connection, id)
        connection.open()
    }

    /** Registra un link gia' pronto (usato da `adopt` e dai test con link finti). */
    suspend fun register(link: FrameLink, id: LinkID): Unit = onState { registerLocked(link, id) }

    private fun registerLocked(connection: FrameLink, id: LinkID) {
        val peer = PeerLink(id, nodeIDsByDevice[id.deviceID], rssi = null, connectedAt = System.currentTimeMillis())
        links[id] = LinkState(connection, peer, linkSequence.incrementAndGet())
        emit(TransportEvent.LinkOpened(peer))
        peer.nodeID?.let { resolveDuplicateLinks(it) }
    }

    /** Chiamato dal link quando riceve un frame completo (anche dai test). */
    suspend fun received(frame: ByteArray, from: LinkID): Unit = onState { receivedLocked(frame, from) }

    private fun receivedLocked(frame: ByteArray, from: LinkID) {
        val state = links[from] ?: return
        emit(TransportEvent.FrameReceived(frame, from, state.peer.nodeID))
    }

    suspend fun closeLink(id: LinkID): Unit = onState { closeLinkLocked(id) }

    private fun closeLinkLocked(id: LinkID) {
        val state = links.remove(id) ?: return
        state.connection.close()
        wakeNudges.forget(id)
        emit(TransportEvent.LinkClosed(id, state.peer.nodeID))

        if (id.role == LinkRole.central) {
            // Estensione Android: senza canale la connessione GATT occuperebbe uno slot per sempre.
            central?.channelClosed(id.deviceID)
        }
        // Era il link "tenuto" di una coppia duplicata: il nostro lato central puo' ricollegarsi.
        val nodeID = state.peer.nodeID
        if (id.role == LinkRole.peripheral && nodeID != null &&
            links.values.none { it.peer.nodeID.contentEqualsNullable(nodeID) }
        ) {
            for (deviceID in devicesToResume(nodeID, nodeIDsByDevice)) central?.resumeReconnect(deviceID)
        }
    }

    /**
     * "Risveglio" dopo un invio: un iPhone bloccato ha l'app sospesa e non e' garantito che i dati in
     * arrivo sul canale L2CAP la risveglino; un evento GATT si' (background mode `bluetooth-peripheral`:
     * write ricevuta; `bluetooth-central`: notify ricevuta). Ripresentiamo quindi il nostro `node_id`
     * sulla characteristic Node Info (write-without-response se siamo central, notify se siamo
     * peripheral): per il peer e' un handshake idempotente, quindi compatibile con ogni versione iOS/Android.
     * Al massimo uno ogni [WAKE_NUDGE_INTERVAL_MS] per link: iOS lascia girare l'app ~10 s per evento.
     */
    private fun nudgeAfterSend(id: LinkID) {
        if (!wakeNudges.shouldNudge(id, System.currentTimeMillis())) return
        nudgeObserverForTesting?.invoke(id)
        when (id.role) {
            LinkRole.central -> central?.nudge(id.deviceID)
            LinkRole.peripheral -> peripheral?.nudge(id.deviceID)
        }
    }

    /**
     * Quando entrambi i nodi si connettono a vicenda (dual-role) restano due link verso lo stesso
     * `node_id`. Il nodo con `node_id` maggiore chiude il link che ha iniziato lui, l'altro lo tiene.
     */
    private fun resolveDuplicateLinks(nodeID: ByteArray) {
        val duplicates = sortedLinks().filter { it.peer.nodeID.contentEqualsNullable(nodeID) }
        if (duplicates.size <= 1 || shouldInitiateConnection(nodeID)) return
        val ours = duplicates.firstOrNull { it.peer.id.role == LinkRole.central } ?: return
        // Senza soppressione il fidato verrebbe ricollegato subito, creando un nuovo duplicato all'infinito.
        central?.disconnect(ours.peer.id.deviceID, suppressReconnect = true)
        closeLinkLocked(ours.peer.id)
    }

    /** Politica deterministica anti-doppio-link: inizia chi ha il `node_id` lessicograficamente minore. */
    fun shouldInitiateConnection(peerNodeID: ByteArray): Boolean =
        localNodeID.lexicographicallyPrecedes(peerNodeID)

    private fun preferredLink(nodeID: ByteArray): LinkState? {
        val candidates = sortedLinks().filter { it.peer.nodeID.contentEqualsNullable(nodeID) }
        return candidates.firstOrNull { it.peer.id.role == LinkRole.central } ?: candidates.firstOrNull()
    }

    private fun sortedLinks(): List<LinkState> =
        links.values.sortedWith(compareBy<LinkState>({ it.peer.connectedAt }, { it.sequence }))

    // MARK: - Eventi verso il router

    private fun emit(event: TransportEvent) {
        for (channel in subscribers.values) channel.trySend(event)
    }

    private suspend fun <T> onState(block: suspend () -> T): T = withContext(stateDispatcher) { block() }

    private fun closeQuietly(resource: java.io.Closeable) {
        try {
            resource.close()
        } catch (_: IOException) {
        } catch (_: RuntimeException) {
        }
    }

    companion object {
        const val maxRememberedDevices = 256

        /** ms: intervallo minimo tra due risvegli GATT verso lo stesso link. */
        const val WAKE_NUDGE_INTERVAL_MS = 3_000L

        /**
         * Dispositivi verso cui il nostro lato central puo' tornare a collegarsi quando si chiude l'ultimo
         * link verso [nodeID]. Include **anche** il dispositivo del link appena chiuso: quando un peer ci
         * contatta dallo stesso indirizzo che avevamo come peripheral (caso normale, un indirizzo per
         * telefono) la soppressione anti-duplicato era registrata proprio su quell'indirizzo, ed
         * escluderlo la lasciava attiva per sempre (niente piu' riconnessioni finche' l'indirizzo non ruota).
         */
        internal fun devicesToResume(nodeID: ByteArray, nodeIDsByDevice: Map<String, ByteArray>): List<String> =
            nodeIDsByDevice.filter { it.value.contentEquals(nodeID) }.map { it.key }
    }
}

/** Limita i risvegli GATT: al massimo uno ogni [intervalMs] per chiave. Non thread-safe (stato confinato). */
internal class WakeNudgeThrottle<K : Any>(private val intervalMs: Long) {
    private val last = HashMap<K, Long>()

    fun shouldNudge(key: K, nowMs: Long): Boolean {
        val previous = last[key]
        if (previous != null && nowMs - previous in 0 until intervalMs) return false
        last[key] = nowMs
        return true
    }

    fun forget(key: K) {
        last.remove(key)
    }
}

/** Token della connessione, impostato subito dopo la creazione (i callback non partono prima di `open()`). */
private class ConnectionTokenBox {
    @Volatile
    var token: Any? = null
}
