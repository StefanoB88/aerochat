package com.stefanobrandi.aerochat.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.uInt16FromLittleEndianBytes
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ScheduledFuture

/**
 * Lato *central* del dual-role: scansiona il Service UUID, si connette ai nodi AeroMesh (GATT client),
 * legge il PSM, scambia il `node_id` e apre il canale L2CAP (`createInsecureL2capChannel`). Mantiene
 * connessioni perenni (`autoConnect = true`, nessun timeout) verso i compagni di viaggio fidati.
 *
 * Handshake identico a iOS, nell'ordine delle characteristic del servizio:
 * 1. read PSM (UInt16 LE) → appena noto si apre il canale L2CAP;
 * 2. write-without-response del nostro `node_id` sul Node Info, read del Node Info, subscribe alle notify.
 *
 * Android ammette una sola operazione GATT pendente per connessione: le operazioni passano da una coda
 * seriale per sessione ([GattOperationQueue]). Tutto lo stato e' confinato alla [queue]; i callback
 * binder vengono postati li'. Gli eventi escono tramite [handler] (sul thread della coda).
 */
@SuppressLint("MissingPermission") // Ogni chiamata e' preceduta dal controllo dei permessi e protetta da SecurityException.
class BLECentralController(
    context: Context,
    private val localNodeID: ByteArray,
    private val queue: RunLoopThread,
    private val maxConcurrentLinks: Int = TransportConstants.maxConcurrentLinks,
    private val handler: (Event) -> Unit,
) {

    sealed interface Event {
        data class StateChanged(val state: RadioState) : Event
        data class Discovered(val deviceID: String, val rssi: Int) : Event
        data class Connected(val deviceID: String) : Event
        class Identified(val deviceID: String, val nodeID: ByteArray) : Event
        class ChannelOpened(val channel: L2CAPChannelBox, val deviceID: String) : Event
        data class Disconnected(val deviceID: String, val reason: String?) : Event
        data class ConnectionFailed(val deviceID: String, val reason: String?) : Event
    }

    private enum class SessionState { connecting, connected }

    /** Una connessione GATT (un `connectGatt`) verso un dispositivo. Nuova a ogni tentativo. */
    private inner class Session(val device: BluetoothDevice, val autoConnect: Boolean) {
        val address: String = device.address
        var gatt: BluetoothGatt? = null
        var state = SessionState.connecting
        var psm: Int? = null
        var pendingOpen = false
        var channelOpen = false
        /** Socket L2CAP in `connect()` (bloccante, su un thread dedicato): chiuderlo annulla il tentativo. */
        var pendingSocket: BluetoothSocket? = null
        var handshakeTimeout: ScheduledFuture<*>? = null
        /** Node Info del peer (se accetta write-without-response): serve ai risvegli di [nudge]. */
        var nodeInfoCharacteristic: BluetoothGattCharacteristic? = null
        val operations = GattOperationQueue(queue, GATT_OPERATION_TIMEOUT_MS)
    }

    private val context: Context = context
    private var isRunning = false
    /** `null` finche' non e' stato letto la prima volta (il primo stato viene sempre notificato). */
    private var state: RadioState? = null
    private var stateReceiver: BroadcastReceiver? = null
    private var authorizationPoll: ScheduledFuture<*>? = null
    /** Ricontrollo dei permessi mancanti: 3 s, 6 s… fino a 60 s (nessun broadcast avvisa della concessione). */
    private val authorizationBackoff = RetryBackoff(AUTHORIZATION_POLL_MS, MAX_AUTHORIZATION_POLL_MS)

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var isScanning = false
    private var wantsScanning = true
    /**
     * Primo piano (scansione aggressiva, intervallo di connessione corto). Parte `false`: lo decide il ciclo
     * di vita del processo ([setLowLatency]); un riavvio "sticky" del servizio senza UI resta a basso consumo.
     */
    private var lowLatency = false
    private var scanRefreshTimer: ScheduledFuture<*>? = null
    /** Lettura periodica dell'RSSI dei link aperti (radar di prossimita'): la scansione non la ripete. */
    private var rssiTimer: ScheduledFuture<*>? = null
    /** Il polling RSSI serve solo al radar: acceso da [setRSSIPolling] mentre e' visibile. */
    private var wantsRSSIPolling = false
    private var pendingScanStart: ScheduledFuture<*>? = null
    /** Riavvio della scansione con le nuove impostazioni, rinviato per non superare il limite di avvii. */
    private var pendingModeRestart: ScheduledFuture<*>? = null
    /** Nuovo tentativo dopo `onScanFailed` (con backoff 5 s → 60 s). */
    private var scanRetryWork: ScheduledFuture<*>? = null
    private val scanFailureBackoff = RetryBackoff(SCAN_RETRY_MS, MAX_SCAN_FAILURE_BACKOFF_MS)
    /** Avvii di scansione recenti (uptime ms): Android blocca chi avvia piu' di 5 scansioni in 30 s. */
    private val scanStarts = ArrayDeque<Long>()

    /** Sessioni GATT attive o in corso, per indirizzo. */
    private val sessions = mutableMapOf<String, Session>()
    private var trustedIdentifiers: Set<String> = emptySet()
    /**
     * Link chiusi perche' duplicati (il peer e' gia' collegato a noi come central): niente
     * auto-reconnect finche' quel link resta aperto, altrimenti i due telefoni si ricollegano all'infinito.
     */
    private val suppressedReconnects = mutableSetOf<String>()
    /** Tentativi falliti consecutivi per fidato: backoff esponenziale invece di un loop connect/fail. */
    private val connectFailures = mutableMapOf<String, Int>()
    private val reconnectWork = mutableMapOf<String, ScheduledFuture<*>>()
    /** `BluetoothDevice` visti in scansione (conservano il tipo di indirizzo, utile ai fidati). */
    private val deviceCache = lruMap<String, BluetoothDevice>(MAX_CACHED_DEVICES)
    /** Ultima segnalazione `Discovered` per dispositivo (uptime ms): con CALLBACK_TYPE_ALL_MATCHES gli advert arrivano a raffica. */
    private val lastDiscoveryReport = lruMap<String, Long>(MAX_CACHED_DEVICES)
    /**
     * Tentativi falliti consecutivi (canale L2CAP mai aperto) verso dispositivi NON fidati e istante
     * (uptime ms) prima del quale non li ricolleghiamo dalla scansione. Senza questo, un peer che fallisce
     * sempre (GATT 133, servizio assente, PSM illeggibile) veniva ricollegato a ogni advert: un loop
     * connect/fail continuo che consuma batteria e occupa la radio.
     */
    private val scanFailures = lruMap<String, Int>(MAX_CACHED_DEVICES)
    private val scanRetryAfter = lruMap<String, Long>(MAX_CACHED_DEVICES)

    // MARK: - API (thread-safe)

    fun start() {
        queue.perform {
            if (isRunning) return@perform
            isRunning = true
            registerStateReceiver()
            updateState()
        }
    }

    fun stop() {
        queue.perform {
            if (!isRunning) return@perform
            isRunning = false
            unregisterStateReceiver()
            authorizationPoll?.cancel(false)
            authorizationPoll = null
            authorizationBackoff.reset()
            scanRetryWork?.cancel(false)
            scanRetryWork = null
            scanFailureBackoff.reset()
            stopScan()
            stopScanRefresh()
            stopRSSIPolling()
            for (session in sessions.values.toList()) {
                tearDown(session, reason = "stop", notify = false)
            }
            sessions.clear()
            reconnectWork.values.forEach { it.cancel(false) }
            reconnectWork.clear()
            suppressedReconnects.clear()
            connectFailures.clear()
            scanFailures.clear()
            scanRetryAfter.clear()
            scanStarts.clear()
            state = null
        }
    }

    fun setScanning(enabled: Boolean) {
        queue.perform {
            wantsScanning = enabled
            if (!isRunning || state != RadioState.poweredOn) return@perform
            if (enabled) startScanIfNeeded() else stopScan()
        }
    }

    /**
     * Primo piano: scansione `SCAN_MODE_LOW_LATENCY` aggressiva e `CONNECTION_PRIORITY_HIGH` sui link;
     * in background scansione `SCAN_MODE_LOW_POWER` (sticky, a batch se offloaded) e priorita' BALANCED.
     */
    fun setLowLatency(enabled: Boolean) {
        queue.perform {
            if (lowLatency == enabled) return@perform
            lowLatency = enabled
            for (session in sessions.values) {
                if (session.state == SessionState.connected) applyConnectionPriority(session)
            }
            if (isScanning) restartScanForModeChange()
        }
    }

    /**
     * Lettura periodica dell'RSSI dei link aperti: serve solo al radar di prossimita' (come iOS).
     * All'accensione legge subito, poi ogni [RSSI_POLL_INTERVAL_MS].
     */
    fun setRSSIPolling(enabled: Boolean) {
        queue.perform {
            wantsRSSIPolling = enabled
            if (enabled && isRunning && state == RadioState.poweredOn) {
                if (rssiTimer == null) {
                    startRSSIPolling()
                    pollRSSI()
                }
            } else {
                stopRSSIPolling()
            }
        }
    }

    /**
     * Ricontrolla permessi e adapter (dopo la risposta al dialogo dei permessi o al ritorno in primo
     * piano): il ricontrollo periodico dei permessi riparte dall'intervallo minimo.
     */
    fun refreshState() {
        queue.perform {
            if (!isRunning) return@perform
            authorizationPoll?.cancel(false)
            authorizationPoll = null
            authorizationBackoff.reset()
            updateState()
        }
    }

    /** Imposta i peripheral fidati (indirizzi) da tenere in connessione perenne. */
    fun setTrustedPeripherals(identifiers: Set<String>) {
        queue.perform {
            trustedIdentifiers = identifiers.map { it.uppercase() }.toSet()
            // Connessioni perenni verso chi non e' piu' fidato: resterebbero pendenti per sempre.
            for (session in sessions.values.toList()) {
                if (session.autoConnect && session.state == SessionState.connecting && session.address !in trustedIdentifiers) {
                    tearDown(session, reason = "non piu' fidato", notify = false)
                }
            }
            connectTrustedPeripherals()
        }
    }

    /**
     * Chiude il link verso il dispositivo (es. link duplicato o peer bloccato). Con [suppressReconnect]
     * il dispositivo non viene ricollegato (ne' come fidato ne' da scansione) fino a [resumeReconnect].
     */
    fun disconnect(deviceID: String, suppressReconnect: Boolean = false) {
        queue.perform {
            if (suppressReconnect) suppressedReconnects.add(deviceID)
            val session = sessions[deviceID] ?: return@perform
            tearDown(session, reason = "disconnessione locale")
        }
    }

    /** Il link alternativo verso quel peer si e' chiuso: torniamo a collegarci noi. */
    fun resumeReconnect(deviceID: String) {
        queue.perform {
            if (!suppressedReconnects.remove(deviceID)) return@perform
            if (sessions[deviceID] == null && deviceID in trustedIdentifiers && state == RadioState.poweredOn) {
                connectTrusted(deviceID)
            } else {
                restartScan()
            }
        }
    }

    /**
     * Risveglia il peer dopo un invio sul canale L2CAP: riscrive il nostro `node_id` (write-without-response)
     * sul suo Node Info. Su un iPhone bloccato la write GATT risveglia l'app sospesa (background mode
     * `bluetooth-peripheral`), che cosi' legge i frame in attesa; per il peer e' un handshake idempotente.
     */
    fun nudge(deviceID: String) {
        queue.perform {
            val session = sessions[deviceID] ?: return@perform
            if (!session.channelOpen || session.state != SessionState.connected) return@perform
            val gatt = session.gatt ?: return@perform
            val characteristic = session.nodeInfoCharacteristic ?: return@perform
            // Operazioni GATT gia' in coda (handshake): il peer e' comunque attivo, niente accumulo.
            if (!session.operations.isIdle) return@perform
            session.operations.enqueue("nudge node_id") { writeNodeID(gatt, characteristic) }
        }
    }

    /**
     * Il canale L2CAP aperto da noi si e' chiuso (EOF/errore) mentre la connessione GATT restava su:
     * la chiudiamo, cosi' lo slot si libera e il dispositivo torna rilevabile (o il fidato si ricollega).
     * Estensione Android: su iOS CoreBluetooth rilascia da solo il peripheral inutilizzato.
     */
    fun channelClosed(deviceID: String) {
        queue.perform {
            val session = sessions[deviceID] ?: return@perform
            if (session.channelOpen) tearDown(session, reason = "canale L2CAP chiuso")
        }
    }

    // MARK: - Stato radio

    private fun registerStateReceiver() {
        if (stateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                queue.perform { if (isRunning) updateState() }
            }
        }
        try {
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            stateReceiver = receiver
        } catch (error: RuntimeException) {
            Log.w(TAG, "registerReceiver fallita: ${error.message}")
        }
    }

    private fun unregisterStateReceiver() {
        val receiver = stateReceiver ?: return
        stateReceiver = null
        try {
            context.unregisterReceiver(receiver)
        } catch (_: RuntimeException) {
        }
    }

    private fun updateState() {
        val newState = BLEEnvironment.radioState(context, LinkRole.central)
        // Nessun broadcast avvisa della concessione di un permesso: finche' manca ricontrolliamo (con backoff).
        if (newState == RadioState.unauthorized) {
            scheduleAuthorizationPoll()
        } else {
            authorizationPoll?.cancel(false)
            authorizationPoll = null
            authorizationBackoff.reset()
        }
        if (newState == state) return
        state = newState
        handler(Event.StateChanged(newState))
        if (newState == RadioState.poweredOn) {
            startScanIfNeeded()
            startScanRefresh()
            if (wantsRSSIPolling) startRSSIPolling()
            connectTrustedPeripherals()
        } else {
            stopScanRefresh()
            stopRSSIPolling()
            isScanning = false
            scanCallback = null
            scanner = null
            // Radio spenta o permesso revocato: le connessioni GATT non valgono piu'.
            for (session in sessions.values.toList()) {
                tearDown(session, reason = "radio $newState")
            }
        }
    }

    private fun scheduleAuthorizationPoll() {
        if (authorizationPoll != null) return
        authorizationPoll = queue.performAfter(authorizationBackoff.next()) {
            authorizationPoll = null
            if (isRunning) updateState()
        }
    }

    // MARK: - Scansione

    /** Toglie dalla finestra gli avvii piu' vecchi di [SCAN_START_WINDOW_MS]. */
    private fun pruneScanStarts(now: Long) {
        while (scanStarts.isNotEmpty() && now - scanStarts.first() > SCAN_START_WINDOW_MS) scanStarts.removeFirst()
    }

    /** ms prima di poter avviare un'altra scansione senza superare il limite (0 = subito). */
    private fun scanStartWaitMs(now: Long): Long {
        pruneScanStarts(now)
        if (scanStarts.size < MAX_SCAN_STARTS_PER_WINDOW) return 0
        return SCAN_START_WINDOW_MS - (now - scanStarts.first()) + 100
    }

    /**
     * Cambio primo piano/background: la scansione va riavviata con le nuove impostazioni. Se il budget di
     * avvii e' esaurito la scansione corrente resta attiva (vecchie impostazioni) e il riavvio e' rinviato.
     */
    private fun restartScanForModeChange() {
        pendingModeRestart?.cancel(false)
        pendingModeRestart = null
        val wait = scanStartWaitMs(SystemClock.elapsedRealtime())
        if (wait == 0L) {
            stopScan()
            startScanIfNeeded()
            return
        }
        pendingModeRestart = queue.performAfter(wait) {
            pendingModeRestart = null
            if (isScanning) {
                stopScan()
                startScanIfNeeded()
            }
        }
    }

    private fun startScanIfNeeded() {
        if (!isRunning || !wantsScanning || state != RadioState.poweredOn || isScanning) return
        val now = SystemClock.elapsedRealtime()
        pruneScanStarts(now)
        if (scanStarts.size >= MAX_SCAN_STARTS_PER_WINDOW) {
            // Oltre il limite Android ignorerebbe la scansione in silenzio: rinviamo all'uscita dalla finestra.
            if (pendingScanStart == null) {
                val wait = SCAN_START_WINDOW_MS - (now - scanStarts.first()) + 100
                pendingScanStart = queue.performAfter(wait) {
                    pendingScanStart = null
                    startScanIfNeeded()
                }
            }
            return
        }
        val adapter = BLEEnvironment.adapter(context) ?: return
        val leScanner = adapter.bluetoothLeScanner ?: return
        // Sempre filtrata sul Service UUID: con schermo spento Android consegna solo scansioni filtrate.
        val filters = listOf(ScanFilter.Builder().setServiceUuid(TransportConstants.serviceParcelUuid).build())
        val builder = ScanSettings.Builder().setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        if (lowLatency) {
            builder
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setReportDelay(0)
        } else {
            // Background: duty cycle basso, match solo con segnale stabile e, se il controller lo supporta,
            // risultati a batch (la CPU si sveglia ogni 5 s invece che a ogni advert).
            builder
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                .setNumOfMatches(ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT)
            val batching = try {
                adapter.isOffloadedScanBatchingSupported
            } catch (_: RuntimeException) {
                false
            }
            builder.setReportDelay(if (batching) BACKGROUND_SCAN_REPORT_DELAY_MS else 0)
        }
        val settings = builder.build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result ?: return
                queue.perform {
                    if (scanCallback !== this) return@perform
                    scanFailureBackoff.reset()
                    discovered(result.device, result.rssi)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                val copy = results?.toList() ?: return
                queue.perform {
                    if (scanCallback !== this) return@perform
                    scanFailureBackoff.reset()
                    copy.forEach { discovered(it.device, it.rssi) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                queue.perform {
                    if (scanCallback !== this) return@perform
                    if (errorCode == SCAN_FAILED_ALREADY_STARTED) return@perform
                    Log.w(TAG, "startScan fallita: $errorCode")
                    isScanning = false
                    scanCallback = null
                    // Backoff 5 s, 10 s… 60 s: un errore persistente non deve risvegliare la radio ogni 5 s.
                    scanRetryWork?.cancel(false)
                    scanRetryWork = queue.performAfter(scanFailureBackoff.next()) {
                        scanRetryWork = null
                        startScanIfNeeded()
                    }
                }
            }
        }
        try {
            leScanner.startScan(filters, settings, callback)
        } catch (error: SecurityException) {
            updateState()
            return
        } catch (error: IllegalStateException) {
            return // Adapter spento nel frattempo: arrivera' ACTION_STATE_CHANGED.
        }
        scanner = leScanner
        scanCallback = callback
        isScanning = true
        scanStarts.addLast(now)
    }

    private fun stopScan() {
        pendingScanStart?.cancel(false)
        pendingScanStart = null
        pendingModeRestart?.cancel(false)
        pendingModeRestart = null
        val callback = scanCallback
        scanCallback = null
        isScanning = false
        if (callback != null) {
            try {
                scanner?.stopScan(callback)
            } catch (_: RuntimeException) {
            }
        }
    }

    /**
     * Su iOS (AllowDuplicates = false) una nuova sessione di scansione serve a rivedere i dispositivi
     * gia' segnalati. Su Android `CALLBACK_TYPE_ALL_MATCHES` li riporta comunque: basta assicurarsi che
     * la scansione sia attiva (riavviarla spesso farebbe scattare il limite di 5 avvii / 30 s).
     */
    private fun restartScan() {
        lastDiscoveryReport.clear()
        startScanIfNeeded()
    }

    /** Riavvio periodico: Android degrada a "opportunistic" le scansioni piu' lunghe di 30 minuti. */
    private fun startScanRefresh() {
        if (scanRefreshTimer != null) return
        scanRefreshTimer = queue.performRepeating(SCAN_REFRESH_INTERVAL_MS) {
            if (isScanning) {
                stopScan()
                startScanIfNeeded()
            } else {
                startScanIfNeeded()
            }
        }
    }

    private fun stopScanRefresh() {
        scanRefreshTimer?.cancel(false)
        scanRefreshTimer = null
    }

    private fun startRSSIPolling() {
        if (rssiTimer != null) return
        rssiTimer = queue.performRepeating(RSSI_POLL_INTERVAL_MS) { pollRSSI() }
    }

    private fun stopRSSIPolling() {
        rssiTimer?.cancel(false)
        rssiTimer = null
    }

    /**
     * Chiede l'RSSI a ogni sessione con canale aperto; la risposta arriva in `onReadRemoteRssi`.
     * Solo a coda GATT libera: la lettura non deve ritardare handshake o risvegli.
     */
    private fun pollRSSI() {
        if (!isRunning) return
        for (session in sessions.values) {
            val gatt = session.gatt ?: continue
            if (!session.channelOpen || session.state != SessionState.connected || !session.operations.isIdle) continue
            session.operations.enqueue("readRemoteRssi") { gatt.readRemoteRssi() }
        }
    }

    private fun discovered(device: BluetoothDevice, rssi: Int) {
        if (!isRunning) return
        val address = device.address
        deviceCache[address] = device
        val now = SystemClock.elapsedRealtime()
        val last = lastDiscoveryReport[address]
        if (last == null || now - last >= DISCOVERY_REPORT_INTERVAL_MS) {
            lastDiscoveryReport[address] = now
            handler(Event.Discovered(address, rssi))
        }
        if (address in suppressedReconnects) return
        val existing = sessions[address]
        if (existing != null) {
            // Fidato in attesa con autoConnect (scansione a basso duty cycle dello stack): ora che e' in
            // vista una connessione diretta e' molto piu' rapida.
            if (existing.autoConnect && existing.state == SessionState.connecting) {
                tearDown(existing, reason = "riconnessione diretta", notify = false)
            } else {
                return
            }
        }
        val trusted = address in trustedIdentifiers
        if (!trusted && (scanRetryAfter[address] ?: 0L) > now) return
        if (activeOrPendingLinkCount() >= maxConcurrentLinks && !trusted) return
        connect(device, autoConnect = false)
    }

    // MARK: - Connessione

    /**
     * Link attivi o in corso. Le connessioni perenni verso fidati fuori portata non occupano slot,
     * altrimenti bastano pochi compagni lontani per non collegarsi piu' a nessuno.
     */
    private fun activeOrPendingLinkCount(): Int = sessions.values.count {
        it.state == SessionState.connected || (it.state == SessionState.connecting && it.address !in trustedIdentifiers)
    }

    private fun connectTrustedPeripherals() {
        if (!isRunning || state != RadioState.poweredOn) return
        for (address in trustedIdentifiers) {
            if (address in suppressedReconnects || sessions.containsKey(address)) continue
            connectTrusted(address)
        }
    }

    /** Connessione perenne (nessun timeout): la richiesta resta pendente finche' il compagno torna a portata. */
    private fun connectTrusted(address: String) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return
        val device = deviceCache[address] ?: try {
            BLEEnvironment.adapter(context)?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return
        connect(device, autoConnect = true)
    }

    private fun connect(device: BluetoothDevice, autoConnect: Boolean) {
        if (!isRunning || state != RadioState.poweredOn) return
        val session = Session(device, autoConnect)
        reconnectWork.remove(session.address)?.cancel(false)
        sessions[session.address] = session
        val gatt = try {
            device.connectGatt(context, autoConnect, SessionCallback(session), BluetoothDevice.TRANSPORT_LE)
        } catch (error: SecurityException) {
            sessions.remove(session.address)
            updateState()
            return
        }
        if (gatt == null) {
            sessions.remove(session.address)
            return
        }
        session.gatt = gatt
        if (!autoConnect) {
            armHandshakeTimeout(session, CONNECT_TIMEOUT_MS + HANDSHAKE_TIMEOUT_MS)
        }
    }

    /**
     * Se entro [intervalMs] il canale L2CAP non e' aperto, il tentativo viene annullato e il
     * dispositivo torna rilevabile.
     */
    private fun armHandshakeTimeout(session: Session, intervalMs: Long) {
        session.handshakeTimeout?.cancel(false)
        session.handshakeTimeout = queue.performAfter(intervalMs) {
            session.handshakeTimeout = null
            if (sessions[session.address] !== session || session.channelOpen) return@performAfter
            tearDown(session, reason = "timeout handshake")
        }
    }

    /**
     * Chiude la sessione: GATT (`disconnect` + `close`, obbligatorio su Android per non esaurire i client),
     * socket L2CAP pendente, timer. Emette `Disconnected` / `ConnectionFailed` (se [notify]) e applica la
     * politica di riconnessione dei fidati.
     */
    private fun tearDown(session: Session, reason: String?, notify: Boolean = true) {
        val wasCurrent = sessions[session.address] === session
        if (wasCurrent) sessions.remove(session.address)
        session.handshakeTimeout?.cancel(false)
        session.handshakeTimeout = null
        session.operations.clear()
        session.pendingSocket?.let { closeQuietly(it) }
        session.pendingSocket = null
        session.gatt?.let { gatt ->
            try {
                gatt.disconnect()
            } catch (_: RuntimeException) {
            }
            try {
                gatt.close()
            } catch (_: RuntimeException) {
            }
        }
        session.gatt = null
        if (!wasCurrent || !notify) return

        val address = session.address
        val wasConnected = session.state == SessionState.connected
        if (wasConnected) {
            handler(Event.Disconnected(address, reason))
        } else {
            handler(Event.ConnectionFailed(address, reason))
        }
        if (address !in trustedIdentifiers && !session.channelOpen) {
            // Backoff 1, 2, 4… 30 s prima di ritentare dalla scansione (i fidati hanno il proprio, sotto).
            val failures = (scanFailures[address] ?: 0) + 1
            scanFailures[address] = failures
            scanRetryAfter[address] = SystemClock.elapsedRealtime() + scanRetryBackoffMs(failures)
        }
        if (address in trustedIdentifiers && address !in suppressedReconnects && isRunning && state == RadioState.poweredOn) {
            if (wasConnected) {
                // Auto-reconnect infinito: lo stack ci ricollega appena il compagno torna a portata.
                connectFailures.remove(address)
                connectTrusted(address)
            } else {
                // Errori ripetibili (chiavi rimosse, limite connessioni): ritenta con backoff 1, 2, 4… 60 s.
                val failures = (connectFailures[address] ?: 0) + 1
                connectFailures[address] = failures
                val delay = minOf(MAX_RECONNECT_BACKOFF_MS, 1_000L shl (failures - 1).coerceAtMost(16))
                reconnectWork.remove(address)?.cancel(false)
                queue.performAfter(delay) {
                    reconnectWork.remove(address)
                    if (!isRunning || state != RadioState.poweredOn) return@performAfter
                    if (address !in trustedIdentifiers || address in suppressedReconnects || sessions.containsKey(address)) {
                        return@performAfter
                    }
                    connectTrusted(address)
                }?.let { reconnectWork[address] = it }
            }
        }
        // Il dispositivo deve poter essere rilevato di nuovo dalla scansione.
        restartScan()
    }

    // MARK: - BluetoothGattCallback (thread binder → queue)

    private inner class SessionCallback(private val session: Session) : BluetoothGattCallback() {

        /** Esegue [block] sulla coda solo se la sessione e' ancora quella corrente per il dispositivo. */
        private inline fun post(gatt: BluetoothGatt?, crossinline block: () -> Unit) {
            queue.perform {
                if (sessions[session.address] !== session) return@perform
                if (gatt != null && session.gatt != null && session.gatt !== gatt) return@perform
                block()
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            post(gatt) { handleConnectionState(session, status, newState) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            post(gatt) {
                session.operations.complete()
                handleServicesDiscovered(session, status)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            val copy = value.copyOf()
            val uuid = characteristic.uuid
            post(gatt) {
                session.operations.complete()
                if (status == BluetoothGatt.GATT_SUCCESS) handleValue(session, uuid, copy)
            }
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            val copy = characteristic?.value?.copyOf()
            val uuid = characteristic?.uuid
            post(gatt) {
                session.operations.complete()
                if (status == BluetoothGatt.GATT_SUCCESS && copy != null && uuid != null) handleValue(session, uuid, copy)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            val copy = value.copyOf()
            val uuid = characteristic.uuid
            post(gatt) { handleValue(session, uuid, copy) }
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val copy = characteristic?.value?.copyOf() ?: return
            val uuid = characteristic.uuid
            post(gatt) { handleValue(session, uuid, copy) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            post(gatt) { session.operations.complete() }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            post(gatt) { session.operations.complete() }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            post(gatt) {
                session.operations.complete()
                // 127 = valore non disponibile secondo la specifica Bluetooth.
                if (status == BluetoothGatt.GATT_SUCCESS && rssi != 127) handler(Event.Discovered(session.address, rssi))
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            // Il peer ha ripubblicato il servizio (es. nuovo PSM dopo un riavvio): ripartiamo dalla discovery.
            post(gatt) {
                session.psm = null
                enqueueDiscovery(session)
            }
        }
    }

    private fun handleConnectionState(session: Session, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            if (session.state == SessionState.connected) return
            session.state = SessionState.connected
            handler(Event.Connected(session.address))
            armHandshakeTimeout(session, HANDSHAKE_TIMEOUT_MS)
            applyConnectionPriority(session)
            enqueueDiscovery(session)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            tearDown(session, reason = "GATT status $status")
        }
    }

    /**
     * Primo piano: intervallo di connessione corto (il throughput dell'L2CAP CoC dipende da questo).
     * Background: BALANCED, la radio si sveglia molto meno spesso sui link perenni.
     */
    private fun applyConnectionPriority(session: Session) {
        val priority = if (lowLatency) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        try {
            session.gatt?.requestConnectionPriority(priority)
        } catch (_: RuntimeException) {
        }
    }

    private fun enqueueDiscovery(session: Session) {
        session.operations.enqueue("discoverServices") { session.gatt?.discoverServices() == true }
    }

    private fun handleServicesDiscovered(session: Session, status: Int) {
        val gatt = session.gatt ?: return
        val service = if (status == BluetoothGatt.GATT_SUCCESS) gatt.getService(TransportConstants.serviceUUID) else null
        if (service == null) {
            tearDown(session, reason = "servizio AeroMesh assente (status $status)")
            return
        }
        for (characteristic in service.characteristics) {
            when (characteristic.uuid) {
                TransportConstants.psmCharacteristicUUID ->
                    session.operations.enqueue("read PSM") { gatt.readCharacteristic(characteristic) }

                TransportConstants.nodeInfoCharacteristicUUID -> {
                    // Handshake fallback: presentiamo il nostro node_id, poi leggiamo il suo.
                    val properties = characteristic.properties
                    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                        session.nodeInfoCharacteristic = characteristic
                        session.operations.enqueue("write node_id") { writeNodeID(gatt, characteristic) }
                    }
                    session.operations.enqueue("read node_id") { gatt.readCharacteristic(characteristic) }
                    if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                        session.operations.enqueue("subscribe node_id") { subscribe(gatt, characteristic) }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeNodeID(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic, localNodeID, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = localNodeID
            gatt.writeCharacteristic(characteristic)
        }

    @Suppress("DEPRECATION")
    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val cccd = characteristic.getDescriptor(TransportConstants.clientCharacteristicConfigUUID) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = value
            gatt.writeDescriptor(cccd)
        }
    }

    private fun handleValue(session: Session, uuid: UUID, value: ByteArray) {
        when (uuid) {
            TransportConstants.psmCharacteristicUUID -> {
                val psm = uInt16FromLittleEndianBytes(value) ?: return
                if (psm == 0) return
                session.psm = psm
                openChannelIfReady(session)
            }
            TransportConstants.nodeInfoCharacteristicUUID -> {
                if (value.size != MeshProtocol.nodeIDLength) return
                handler(Event.Identified(session.address, value))
            }
        }
    }

    // MARK: - L2CAP

    private fun openChannelIfReady(session: Session) {
        val psm = session.psm ?: return
        if (session.pendingOpen || session.channelOpen) return
        session.pendingOpen = true
        val device = session.device
        // `connect()` e' bloccante: thread dedicato. Chiudere `pendingSocket` (timeout/teardown) lo annulla.
        val socket = try {
            device.createInsecureL2capChannel(psm)
        } catch (error: IOException) {
            channelOpenFailed(session, error.message)
            return
        } catch (error: SecurityException) {
            channelOpenFailed(session, error.message)
            return
        }
        session.pendingSocket = socket
        Thread({
            val result = try {
                socket.connect()
                null
            } catch (error: IOException) {
                error.message ?: "connect fallita"
            } catch (error: SecurityException) {
                error.message ?: "permesso negato"
            }
            queue.perform {
                val current = sessions[session.address] === session && session.pendingSocket === socket
                if (!current) {
                    closeQuietly(socket)
                    return@perform
                }
                session.pendingSocket = null
                if (result != null) {
                    closeQuietly(socket)
                    channelOpenFailed(session, result)
                    return@perform
                }
                session.pendingOpen = false
                session.channelOpen = true
                connectFailures.remove(session.address)
                scanFailures.remove(session.address)
                scanRetryAfter.remove(session.address)
                session.handshakeTimeout?.cancel(false)
                session.handshakeTimeout = null
                handler(Event.ChannelOpened(L2CAPChannelBox(socket), session.address))
            }
        }, "AeroMesh.L2CAP.connect").apply { isDaemon = true }.start()
    }

    private fun channelOpenFailed(session: Session, reason: String?) {
        session.pendingOpen = false
        // Riproviamo da zero: la disconnessione rende il dispositivo di nuovo rilevabile.
        tearDown(session, reason = "openL2CAPChannel: $reason")
    }

    private fun closeQuietly(resource: java.io.Closeable) {
        try {
            resource.close()
        } catch (_: IOException) {
        } catch (_: RuntimeException) {
        }
    }

    companion object {
        private const val TAG = "AeroMesh.Central"

        /** ms. */
        const val CONNECT_TIMEOUT_MS = 12_000L
        /** ms. */
        const val HANDSHAKE_TIMEOUT_MS = 15_000L
        /** ms (iOS: 20 s per rivedere i dispositivi; su Android serve solo contro il degrado a 30 min). */
        const val SCAN_REFRESH_INTERVAL_MS = 5 * 60_000L
        /** ms. */
        const val MAX_RECONNECT_BACKOFF_MS = 60_000L
        /** ms: timeout di sicurezza per un'operazione GATT senza callback. */
        const val GATT_OPERATION_TIMEOUT_MS = 5_000L
        /** ms: frequenza massima degli eventi `Discovered` (e quindi degli aggiornamenti RSSI) per dispositivo. */
        const val DISCOVERY_REPORT_INTERVAL_MS = 5_000L
        /** ms: lettura periodica dell'RSSI dei link aperti (iOS `rssiPollInterval`). */
        const val RSSI_POLL_INTERVAL_MS = 4_000L
        /** ms: primo ricontrollo dei permessi mancanti (poi backoff fino a [MAX_AUTHORIZATION_POLL_MS]). */
        const val AUTHORIZATION_POLL_MS = 3_000L
        /** ms. */
        const val MAX_AUTHORIZATION_POLL_MS = 60_000L
        /** ms: primo nuovo tentativo dopo `onScanFailed` (poi backoff fino a [MAX_SCAN_FAILURE_BACKOFF_MS]). */
        const val SCAN_RETRY_MS = 5_000L
        /** ms. */
        const val MAX_SCAN_FAILURE_BACKOFF_MS = 60_000L
        /** ms: batch dei risultati di scansione in background (solo con batching offloaded nel controller). */
        const val BACKGROUND_SCAN_REPORT_DELAY_MS = 5_000L
        private const val SCAN_START_WINDOW_MS = 30_000L
        private const val MAX_SCAN_STARTS_PER_WINDOW = 4
        private const val MAX_CACHED_DEVICES = 256
        /** ms: tetto del backoff verso dispositivi non fidati che falliscono l'handshake. */
        const val MAX_SCAN_RETRY_BACKOFF_MS = 30_000L

        /** Backoff (ms) dopo [failures] tentativi falliti consecutivi: 1, 2, 4, 8, 16, 30, 30… s. */
        internal fun scanRetryBackoffMs(failures: Int): Long =
            minOf(MAX_SCAN_RETRY_BACKOFF_MS, 1_000L shl (failures - 1).coerceIn(0, 16))
    }
}

/**
 * Coda seriale delle operazioni GATT di una connessione (Android ne ammette una sola pendente).
 * Confinata al thread di [queue]; ogni operazione ha un timeout di sicurezza.
 */
internal class GattOperationQueue(private val queue: RunLoopThread, private val timeoutMs: Long) {

    private class Operation(val name: String, val start: () -> Boolean)

    private val pending = ArrayDeque<Operation>()
    private var current: Operation? = null
    private var timeout: ScheduledFuture<*>? = null

    val isIdle: Boolean get() = current == null && pending.isEmpty()

    /** Accoda un'operazione; [start] ritorna `false` se lo stack l'ha rifiutata (si passa alla successiva). */
    fun enqueue(name: String, start: () -> Boolean) {
        pending.addLast(Operation(name, start))
        if (current == null) next()
    }

    /** L'operazione in corso ha ricevuto il suo callback. */
    fun complete() {
        timeout?.cancel(false)
        timeout = null
        current = null
        next()
    }

    fun clear() {
        timeout?.cancel(false)
        timeout = null
        current = null
        pending.clear()
    }

    private fun next() {
        while (current == null) {
            val operation = pending.removeFirstOrNull() ?: return
            current = operation
            val started = try {
                operation.start()
            } catch (_: RuntimeException) {
                false
            }
            if (!started) {
                Log.w("AeroMesh.GATT", "operazione rifiutata: ${operation.name}")
                current = null
                continue
            }
            timeout = queue.performAfter(timeoutMs) {
                if (current === operation) {
                    Log.w("AeroMesh.GATT", "timeout: ${operation.name}")
                    timeout = null
                    current = null
                    next()
                }
            }
        }
    }
}

/** `LinkedHashMap` in ordine di accesso con capacita' massima (LRU). */
internal fun <K, V> lruMap(maxSize: Int): MutableMap<K, V> =
    object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }
