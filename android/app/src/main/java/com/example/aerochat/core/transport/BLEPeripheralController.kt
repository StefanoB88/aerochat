package com.example.aerochat.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.uInt16LittleEndianBytes
import java.io.IOException
import java.util.concurrent.ScheduledFuture

/**
 * Lato *peripheral* del dual-role: pubblica il canale L2CAP (senza cifratura di sistema, niente
 * pop-up di pairing: `listenUsingInsecureL2capChannel`, come `publishL2CAPChannel(withEncryption: false)`
 * su iOS), espone PSM e `node_id` via GATT server e fa advertising legacy del solo Service UUID a 128 bit.
 *
 * Tutto lo stato e' confinato alla [queue]; i callback Android (thread binder/main) vengono postati li'.
 * Gli eventi escono tramite [handler] (chiamato sul thread della coda).
 */
@SuppressLint("MissingPermission") // Ogni chiamata e' preceduta dal controllo dei permessi e protetta da SecurityException.
class BLEPeripheralController(
    context: Context,
    private val localNodeID: ByteArray,
    private val queue: RunLoopThread,
    private val handler: (Event) -> Unit,
) {

    sealed interface Event {
        data class StateChanged(val state: RadioState) : Event
        data class PsmPublished(val psm: Int) : Event
        data class AdvertisingChanged(val isAdvertising: Boolean) : Event
        class CentralWroteNodeID(val centralID: String, val nodeID: ByteArray) : Event
        class ChannelOpened(val channel: L2CAPChannelBox, val centralID: String) : Event
        data class ChannelFailed(val centralID: String?, val reason: String) : Event
    }

    private val context: Context = context
    private var isRunning = false
    /** `null` finche' non e' stato letto la prima volta (il primo stato viene sempre notificato). */
    private var state: RadioState? = null
    private var stateReceiver: BroadcastReceiver? = null
    private var authorizationPoll: ScheduledFuture<*>? = null

    /** Server L2CAP pubblicato da questa istanza (PSM dinamico assegnato dallo stack). */
    private var serverSocket: BluetoothServerSocket? = null
    private var psm: Int = 0
    private var channelPublished = false

    private var gattServer: BluetoothGattServer? = null
    private var serviceAdded = false
    private var serviceAddInFlight = false
    private var nodeInfoCharacteristic: BluetoothGattCharacteristic? = null
    /** Central iscritti alle notify del Node Info (valore CCCD per indirizzo). */
    private val subscribedCentrals = mutableMapOf<String, ByteArray>()
    /** Dispositivi dei central iscritti (per le notify di risveglio di [nudge]). */
    private val subscribedDevices = mutableMapOf<String, BluetoothDevice>()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var isAdvertising = false
    private var advertiseInFlight = false
    private var wantsAdvertising = true
    private var lowLatency = true
    private var retryWork: ScheduledFuture<*>? = null

    // MARK: - API (thread-safe)

    fun start() {
        queue.perform {
            if (isRunning) return@perform
            isRunning = true
            wantsAdvertising = true
            registerStateReceiver()
            updateState()
        }
    }

    fun stop() {
        queue.perform {
            if (!isRunning) return@perform
            isRunning = false
            wantsAdvertising = false
            unregisterStateReceiver()
            authorizationPoll?.cancel(false)
            authorizationPoll = null
            tearDown()
            state = null
        }
    }

    fun setAdvertising(enabled: Boolean) {
        queue.perform {
            wantsAdvertising = enabled
            if (!isRunning || state != RadioState.poweredOn) return@perform
            if (enabled) {
                startAdvertisingIfReady()
            } else {
                stopAdvertising()
            }
        }
    }

    /** Primo piano: advertising LOW_LATENCY; in background BALANCED (batteria). */
    fun setLowLatency(enabled: Boolean) {
        queue.perform {
            if (lowLatency == enabled) return@perform
            lowLatency = enabled
            if (isAdvertising || advertiseInFlight) {
                stopAdvertising()
                startAdvertisingIfReady()
            }
        }
    }

    /** Ricontrolla permessi e adapter (da chiamare dopo la risposta al dialogo dei permessi). */
    fun refreshState() {
        queue.perform { if (isRunning) updateState() }
    }

    /**
     * Risveglia il central dopo un invio sul canale L2CAP: notify del nostro `node_id` sul Node Info a
     * cui e' iscritto. Su un iPhone bloccato la notify risveglia l'app sospesa (background mode
     * `bluetooth-central`), che cosi' legge i frame in attesa; per il peer e' un handshake idempotente.
     */
    fun nudge(centralID: String) {
        queue.perform {
            if (!isRunning || state != RadioState.poweredOn) return@perform
            val device = subscribedDevices[centralID] ?: return@perform
            notifyNodeInfo(device)
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
        val newState = BLEEnvironment.radioState(context, LinkRole.peripheral)
        // Nessun broadcast avvisa della concessione di un permesso: finche' manca ricontrolliamo.
        if (newState == RadioState.unauthorized) scheduleAuthorizationPoll() else {
            authorizationPoll?.cancel(false)
            authorizationPoll = null
        }
        if (newState == state) {
            if (newState == RadioState.poweredOn) setUpIfNeeded()
            return
        }
        state = newState
        handler(Event.StateChanged(newState))
        if (newState == RadioState.poweredOn) {
            setUpIfNeeded()
        } else {
            tearDown()
        }
    }

    private fun scheduleAuthorizationPoll() {
        if (authorizationPoll != null) return
        authorizationPoll = queue.performRepeating(AUTHORIZATION_POLL_MS) { if (isRunning) updateState() }
    }

    /** Chiude server L2CAP, GATT server e advertising (radio spenta, permesso revocato o `stop()`). */
    private fun tearDown() {
        retryWork?.cancel(false)
        retryWork = null
        stopAdvertising()
        advertiser = null
        serverSocket?.let { closeQuietly(it) }
        serverSocket = null
        psm = 0
        channelPublished = false
        try {
            gattServer?.clearServices()
            gattServer?.close()
        } catch (_: RuntimeException) {
        }
        gattServer = null
        serviceAdded = false
        serviceAddInFlight = false
        nodeInfoCharacteristic = null
        subscribedCentrals.clear()
        subscribedDevices.clear()
    }

    private fun scheduleRetry(delayMs: Long = SETUP_RETRY_MS) {
        retryWork?.cancel(false)
        retryWork = queue.performAfter(delayMs) {
            retryWork = null
            if (isRunning) setUpIfNeeded()
        }
    }

    // MARK: - Setup

    private fun setUpIfNeeded() {
        if (!isRunning || state != RadioState.poweredOn) return
        if (!channelPublished) {
            // 1. Canale L2CAP senza encryption di sistema: la sicurezza e' E2EE a livello app.
            if (!publishChannel()) return
        }
        if (!serviceAdded) {
            addService()
        } else {
            startAdvertisingIfReady()
        }
    }

    private fun publishChannel(): Boolean {
        val adapter = BLEEnvironment.adapter(context) ?: return false
        val server = try {
            adapter.listenUsingInsecureL2capChannel()
        } catch (error: IOException) {
            failSetup("listenUsingInsecureL2capChannel: ${error.message}")
            return false
        } catch (error: SecurityException) {
            failSetup("listenUsingInsecureL2capChannel: ${error.message}")
            return false
        }
        val newPSM = server.psm
        if (newPSM <= 0) {
            closeQuietly(server)
            failSetup("listenUsingInsecureL2capChannel: PSM 0")
            return false
        }
        serverSocket = server
        psm = newPSM
        channelPublished = true
        handler(Event.PsmPublished(newPSM))
        startAcceptLoop(server)
        return true
    }

    private fun failSetup(reason: String) {
        handler(Event.ChannelFailed(null, reason))
        // Senza canale / servizio nessuno puo' collegarsi a noi: riproviamo a breve.
        scheduleRetry()
    }

    /** Thread bloccante su `accept()`: ogni central che apre il canale produce un `BluetoothSocket`. */
    private fun startAcceptLoop(server: BluetoothServerSocket) {
        Thread({
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                } ?: break
                queue.perform {
                    if (serverSocket !== server) {
                        closeQuietly(socket)
                        return@perform
                    }
                    val centralID = socket.remoteDevice.address
                    handler(Event.ChannelOpened(L2CAPChannelBox(socket), centralID))
                }
            }
            queue.perform {
                // Chiusura inattesa (non nostra): ripubblichiamo con un nuovo PSM, letto dai central a ogni connessione.
                if (serverSocket === server && isRunning) {
                    serverSocket = null
                    psm = 0
                    channelPublished = false
                    failSetup("accept terminata")
                }
            }
        }, "AeroMesh.L2CAP.accept").apply { isDaemon = true }.start()
    }

    private fun addService() {
        if (psm == 0 || serviceAddInFlight) return
        val server = gattServer ?: openGattServer() ?: run {
            failSetup("openGattServer: null")
            return
        }
        val service = BluetoothGattService(TransportConstants.serviceUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // PSM: 2 byte UInt16 Little-Endian (sola lettura). Servito dinamicamente in onCharacteristicReadRequest.
        val psmCharacteristic = BluetoothGattCharacteristic(
            TransportConstants.psmCharacteristicUUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        // Node Info: Read / Write Without Response / Notify con gli 8 byte del node_id.
        val nodeInfo = BluetoothGattCharacteristic(
            TransportConstants.nodeInfoCharacteristicUUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        // iOS aggiunge da solo il CCCD alle characteristic con notify; su Android va dichiarato.
        nodeInfo.addDescriptor(
            BluetoothGattDescriptor(
                TransportConstants.clientCharacteristicConfigUUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(psmCharacteristic)
        service.addCharacteristic(nodeInfo)
        nodeInfoCharacteristic = nodeInfo

        serviceAddInFlight = true
        val accepted = try {
            server.clearServices()
            server.addService(service)
        } catch (error: SecurityException) {
            false
        }
        if (!accepted) {
            serviceAddInFlight = false
            failSetup("addService rifiutata")
        }
    }

    private fun openGattServer(): BluetoothGattServer? {
        val manager = BLEEnvironment.bluetoothManager(context) ?: return null
        val server = try {
            manager.openGattServer(context, serverCallback)
        } catch (_: SecurityException) {
            null
        } ?: return null
        gattServer = server
        return server
    }

    // MARK: - Advertising

    private fun startAdvertisingIfReady() {
        if (!isRunning || !wantsAdvertising || !serviceAdded || isAdvertising || advertiseInFlight) return
        if (state != RadioState.poweredOn) return
        val leAdvertiser = advertiser ?: BLEEnvironment.adapter(context)?.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            // Dispositivo senza supporto al ruolo peripheral: restiamo raggiungibili solo come central.
            Log.w(TAG, "bluetoothLeAdvertiser non disponibile")
            return
        }
        advertiser = leAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                if (lowLatency) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            )
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Solo Flags + Service UUID 128 bit (21 byte): il pacchetto legacy resta sotto i 31 byte. Niente nome.
        val data = AdvertiseData.Builder()
            .addServiceUuid(TransportConstants.serviceParcelUuid)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                queue.perform {
                    if (advertiseCallback !== this) return@perform
                    advertiseInFlight = false
                    setAdvertisingFlag(true)
                }
            }

            override fun onStartFailure(errorCode: Int) {
                queue.perform {
                    if (advertiseCallback !== this) return@perform
                    advertiseInFlight = false
                    if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                        setAdvertisingFlag(true)
                        return@perform
                    }
                    advertiseCallback = null
                    setAdvertisingFlag(false)
                    Log.w(TAG, "startAdvertising fallito: $errorCode")
                    if (errorCode != ADVERTISE_FAILED_FEATURE_UNSUPPORTED && errorCode != ADVERTISE_FAILED_DATA_TOO_LARGE) {
                        scheduleRetry(ADVERTISE_RETRY_MS)
                    }
                }
            }
        }
        advertiseCallback = callback
        advertiseInFlight = true
        try {
            leAdvertiser.startAdvertising(settings, data, callback)
        } catch (error: SecurityException) {
            advertiseInFlight = false
            advertiseCallback = null
            updateState()
        } catch (error: IllegalStateException) {
            // Adapter spento nel frattempo.
            advertiseInFlight = false
            advertiseCallback = null
        }
    }

    private fun stopAdvertising() {
        val callback = advertiseCallback
        advertiseCallback = null
        advertiseInFlight = false
        if (callback != null) {
            try {
                advertiser?.stopAdvertising(callback)
            } catch (_: RuntimeException) {
            }
        }
        setAdvertisingFlag(false)
    }

    private fun setAdvertisingFlag(value: Boolean) {
        if (isAdvertising == value) return
        isAdvertising = value
        handler(Event.AdvertisingChanged(value))
    }

    // MARK: - BluetoothGattServerCallback (thread binder → queue)

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            queue.perform {
                if (service?.uuid != TransportConstants.serviceUUID) return@perform
                serviceAddInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // Errore transitorio del database GATT: senza nuovo tentativo non saremmo piu' visibili.
                    failSetup("addService: status $status")
                    return@perform
                }
                serviceAdded = true
                startAdvertisingIfReady()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (device == null || newState != BluetoothProfile.STATE_DISCONNECTED) return
            val address = device.address
            queue.perform {
                subscribedCentrals.remove(address)
                subscribedDevices.remove(address)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?,
        ) {
            if (device == null || characteristic == null) return
            queue.perform {
                when (characteristic.uuid) {
                    TransportConstants.nodeInfoCharacteristicUUID -> respondRead(device, requestId, offset, localNodeID)
                    TransportConstants.psmCharacteristicUUID -> respondRead(device, requestId, offset, uInt16LittleEndianBytes(psm))
                    else -> sendResponse(device, requestId, ATT_ATTRIBUTE_NOT_FOUND, offset, null)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (device == null || characteristic == null) return
            val copy = value?.copyOf()
            queue.perform {
                // Handshake fallback: il central ci scrive il proprio node_id prima di aprire l'L2CAP.
                var status = BluetoothGatt.GATT_SUCCESS
                if (characteristic.uuid == TransportConstants.nodeInfoCharacteristicUUID) {
                    if (!preparedWrite && offset == 0 && copy != null && copy.size == MeshProtocol.nodeIDLength) {
                        handler(Event.CentralWroteNodeID(device.address, copy))
                    } else {
                        status = ATT_INVALID_ATTRIBUTE_LENGTH
                    }
                } else {
                    status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                }
                // Gli iPhone scrivono "without response": rispondiamo solo se richiesto (peer Android con response).
                if (responseNeeded) sendResponse(device, requestId, status, offset, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?,
        ) {
            if (device == null || descriptor == null) return
            queue.perform {
                if (descriptor.uuid == TransportConstants.clientCharacteristicConfigUUID) {
                    val value = subscribedCentrals[device.address] ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    respondRead(device, requestId, offset, value)
                } else {
                    sendResponse(device, requestId, ATT_ATTRIBUTE_NOT_FOUND, offset, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (device == null || descriptor == null) return
            val copy = value?.copyOf()
            queue.perform {
                if (descriptor.uuid != TransportConstants.clientCharacteristicConfigUUID || copy == null || copy.size != 2) {
                    if (responseNeeded) sendResponse(device, requestId, ATT_INVALID_ATTRIBUTE_LENGTH, offset, null)
                    return@perform
                }
                if (responseNeeded) sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                val enabled = copy[0].toInt() and 0x03 != 0
                if (enabled) {
                    subscribedCentrals[device.address] = copy
                    subscribedDevices[device.address] = device
                    // `didSubscribeTo`: inviamo subito il nostro node_id al central appena iscritto.
                    notifyNodeInfo(device)
                } else {
                    subscribedCentrals.remove(device.address)
                    subscribedDevices.remove(device.address)
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            if (device == null) return
            queue.perform { sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
        }
    }

    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray) {
        if (offset < 0 || offset > value.size) {
            sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            return
        }
        sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
    }

    private fun sendResponse(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (error: RuntimeException) {
            Log.w(TAG, "sendResponse fallita: ${error.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyNodeInfo(device: BluetoothDevice) {
        val server = gattServer ?: return
        val characteristic = nodeInfoCharacteristic ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, localNodeID)
            } else {
                characteristic.value = localNodeID
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "notifyCharacteristicChanged fallita: ${error.message}")
        }
    }

    private fun closeQuietly(resource: java.io.Closeable) {
        try {
            resource.close()
        } catch (_: IOException) {
        } catch (_: RuntimeException) {
        }
    }

    companion object {
        private const val TAG = "AeroMesh.Peripheral"
        /** ms. */
        const val SETUP_RETRY_MS = 2_000L
        /** ms. */
        const val ADVERTISE_RETRY_MS = 5_000L
        /** ms. */
        const val AUTHORIZATION_POLL_MS = 3_000L

        /** Codici ATT senza costante pubblica in `BluetoothGatt`. */
        private const val ATT_ATTRIBUTE_NOT_FOUND = 0x0A
        private const val ATT_INVALID_ATTRIBUTE_LENGTH = 0x0D
    }
}
