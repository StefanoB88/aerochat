package com.example.aerochat.core.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.core.protocol.toHex
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// MARK: - UUID BLE

/**
 * Estensione di [MeshProtocol] con le costanti specifiche del trasporto BLE
 * (su iOS `extension MeshProtocol` in `TransportTypes.swift`).
 */
object TransportConstants {
    /** UUID del Primary Service AeroMesh (pubblicizzato nell'advertising, filtro di scansione). */
    val serviceUUID: UUID get() = UUID.fromString(MeshProtocol.serviceUUIDString)
    val serviceParcelUuid: ParcelUuid get() = ParcelUuid(serviceUUID)
    /** Characteristic PSM: `UInt16` Little-Endian, sola lettura. */
    val psmCharacteristicUUID: UUID get() = UUID.fromString(MeshProtocol.psmCharacteristicUUIDString)
    /** Characteristic Node Info: 8 byte di `node_id`, Read / Write Without Response / Notify. */
    val nodeInfoCharacteristicUUID: UUID get() = UUID.fromString(MeshProtocol.nodeInfoCharacteristicUUIDString)
    /** Client Characteristic Configuration Descriptor (0x2902), necessario su Android per le notify. */
    val clientCharacteristicConfigUUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Byte stimati del pacchetto Legacy Advertising: 3 (Flags AD) + 2 (header AD) + 16 (UUID 128 bit). */
    const val estimatedLegacyAdvertisingBytes = 3 + 2 + 16

    /** Numero massimo di link L2CAP simultanei (batteria e banda 2.4 GHz). */
    const val maxConcurrentLinks = 8
}

/** Alias con il nome Swift (`MeshProtocol.estimatedLegacyAdvertisingBytes`). */
val MeshProtocol.estimatedLegacyAdvertisingBytes: Int get() = TransportConstants.estimatedLegacyAdvertisingBytes

/** Alias con il nome Swift (`MeshProtocol.maxConcurrentLinks`). */
val MeshProtocol.maxConcurrentLinks: Int get() = TransportConstants.maxConcurrentLinks

// MARK: - Stato radio

/**
 * Stato della radio Bluetooth (alimenta il banner "Modalita' Aereo"). Su iOS mappa `CBManagerState`;
 * su Android deriva da presenza dell'adapter, permessi runtime e `BluetoothAdapter.getState()`.
 */
enum class RadioState {
    unknown,
    resetting,
    unsupported,
    unauthorized,
    poweredOff,
    poweredOn;

    val isReady: Boolean get() = this == poweredOn

    companion object {
        /** Mappa `BluetoothAdapter.STATE_*` (anche da `ACTION_STATE_CHANGED`). */
        fun fromAdapterState(state: Int): RadioState = when (state) {
            BluetoothAdapter.STATE_ON -> poweredOn
            BluetoothAdapter.STATE_OFF -> poweredOff
            BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.STATE_TURNING_OFF -> resetting
            else -> unknown
        }
    }
}

// MARK: - Link

/** Ruolo locale sul link: `central` = abbiamo aperto noi il canale, `peripheral` = ci hanno contattato. */
enum class LinkRole { central, peripheral }

/**
 * Identificatore di un link fisico: dispositivo remoto + ruolo locale.
 * Su Android [deviceID] e' l'indirizzo `BluetoothDevice.address` (su iOS era l'UUID CoreBluetooth).
 */
data class LinkID(val deviceID: String, val role: LinkRole) {
    override fun toString(): String = "${role.name}:${deviceID.take(8)}"
}

/** Link L2CAP attivo verso un vicino radio. */
data class PeerLink(
    val id: LinkID,
    /** `node_id` del vicino (noto dopo la lettura/scrittura della characteristic Node Info). */
    val nodeID: ByteArray?,
    val rssi: Int?,
    /** Epoch ms. */
    val connectedAt: Long,
) {
    val isIdentified: Boolean get() = nodeID != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerLink) return false
        return id == other.id && nodeID.contentEqualsNullable(other.nodeID) &&
            rssi == other.rssi && connectedAt == other.connectedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (nodeID?.contentHashCode() ?: 0)
        result = 31 * result + (rssi ?: 0)
        result = 31 * result + connectedAt.hashCode()
        return result
    }

    override fun toString(): String = "PeerLink(id=$id, nodeID=${nodeID?.toHex()}, rssi=$rssi, connectedAt=$connectedAt)"
}

// MARK: - Eventi

sealed interface TransportEvent {
    data class RadioStateChanged(val state: RadioState) : TransportEvent
    data class AdvertisingChanged(val isAdvertising: Boolean) : TransportEvent
    data class PeerDiscovered(val deviceID: String, val rssi: Int) : TransportEvent
    data class LinkOpened(val link: PeerLink) : TransportEvent

    data class LinkIdentified(val id: LinkID, val nodeID: ByteArray) : TransportEvent {
        override fun equals(other: Any?): Boolean =
            other is LinkIdentified && id == other.id && nodeID.contentEquals(other.nodeID)
        override fun hashCode(): Int = 31 * id.hashCode() + nodeID.contentHashCode()
        override fun toString(): String = "LinkIdentified(id=$id, nodeID=${nodeID.toHex()})"
    }

    data class LinkClosed(val id: LinkID, val nodeID: ByteArray?) : TransportEvent {
        override fun equals(other: Any?): Boolean =
            other is LinkClosed && id == other.id && nodeID.contentEqualsNullable(other.nodeID)
        override fun hashCode(): Int = 31 * id.hashCode() + (nodeID?.contentHashCode() ?: 0)
        override fun toString(): String = "LinkClosed(id=$id, nodeID=${nodeID?.toHex()})"
    }

    data class FrameReceived(val frame: ByteArray, val from: LinkID, val nodeID: ByteArray?) : TransportEvent {
        override fun equals(other: Any?): Boolean =
            other is FrameReceived && from == other.from && frame.contentEquals(other.frame) &&
                nodeID.contentEqualsNullable(other.nodeID)
        override fun hashCode(): Int =
            31 * (31 * frame.contentHashCode() + from.hashCode()) + (nodeID?.contentHashCode() ?: 0)
        override fun toString(): String = "FrameReceived(${frame.size} byte, from=$from, nodeID=${nodeID?.toHex()})"
    }
}

sealed class TransportError(message: String) : Exception(message) {
    data class RadioNotReady(val state: RadioState) : TransportError("radio not ready: $state")
    data object LinkNotFound : TransportError("link not found") {
        private fun readResolve(): Any = LinkNotFound
    }
    data object LinkClosed : TransportError("link closed") {
        private fun readResolve(): Any = LinkClosed
    }
    data object NodeUnreachable : TransportError("node unreachable") {
        private fun readResolve(): Any = NodeUnreachable
    }
    data class WriteFailed(val reason: String) : TransportError("write failed: $reason")
}

// MARK: - Provider

/** Astrazione del trasporto radio usata dal `MeshRouterActor`: BLE oggi, Wi-Fi Aware / TCP domani. */
interface TransportProvider {
    val localNodeID: ByteArray
    suspend fun start()
    suspend fun stop()

    /**
     * Nuovo iscritto agli eventi. L'iscrizione e' registrata **subito** (alla chiamata, non alla
     * `collect`), con buffer illimitato, come `AsyncStream(bufferingPolicy: .unbounded)` su iOS:
     * nessun evento va perso tra `events()` e l'inizio della raccolta. Il Flow va raccolto una volta sola.
     */
    fun events(): Flow<TransportEvent>
    suspend fun radioState(): RadioState
    suspend fun activeLinks(): List<PeerLink>

    /** Invia un frame (gia' serializzato, senza prefisso di lunghezza) su un link specifico. */
    @Throws(Exception::class)
    suspend fun send(frame: ByteArray, to: LinkID)

    /** Invia a un nodo identificato; `false` se nessun link porta a quel `node_id`. */
    @Throws(Exception::class)
    suspend fun send(frame: ByteArray, toNode: ByteArray): Boolean

    /** Invia a tutti i vicini (una sola volta per `node_id`), escludendo i link indicati. Ritorna i link raggiunti. */
    suspend fun broadcast(frame: ByteArray, excluding: Set<LinkID> = emptySet()): Int

    /** Compagni fidati da tenere in connessione perenne (indirizzi `BluetoothDevice.address` dei loro peripheral). */
    suspend fun setTrustedPeripheralIdentifiers(identifiers: Set<String>)
}

/**
 * Contenitore del socket L2CAP CoC (`BluetoothSocket`) passato dai controller all'attore.
 * Il socket e' poi usato esclusivamente dai thread di [L2CAPConnection] dopo l'adozione.
 */
class L2CAPChannelBox(val channel: BluetoothSocket) {
    val peerIdentifier: String get() = channel.remoteDevice.address
}

// MARK: - Permessi e ambiente Android

/**
 * Permessi runtime richiesti dal trasporto. L'app deve chiederli prima di `start()` (o in qualsiasi
 * momento: i controller ricontrollano periodicamente finche' lo stato e' `unauthorized`).
 */
object BLEPermissions {

    /** Permessi da richiedere a runtime per il dual-role completo sulla versione Android corrente. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            // API 29-30: la scansione BLE richiede la posizione precisa (e la Localizzazione attiva).
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Scansione + connessione GATT/L2CAP come client. */
    fun hasCentralPermissions(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, Manifest.permission.BLUETOOTH_SCAN) &&
                granted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Advertising + GATT server + server L2CAP. */
    fun hasPeripheralPermissions(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, Manifest.permission.BLUETOOTH_ADVERTISE) &&
                granted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true // BLUETOOTH / BLUETOOTH_ADMIN sono permessi di installazione.
        }

    fun hasAllPermissions(context: Context): Boolean =
        hasCentralPermissions(context) && hasPeripheralPermissions(context)

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/** Stato radio visto da un singolo ruolo (equivalente di `CBCentralManager.state` / `CBPeripheralManager.state`). */
internal object BLEEnvironment {

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun bluetoothManager(context: Context): BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    fun radioState(context: Context, role: LinkRole): RadioState {
        val adapter = adapter(context) ?: return RadioState.unsupported
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return RadioState.unsupported
        }
        val authorized = when (role) {
            LinkRole.central -> BLEPermissions.hasCentralPermissions(context)
            LinkRole.peripheral -> BLEPermissions.hasPeripheralPermissions(context)
        }
        if (!authorized) return RadioState.unauthorized
        return try {
            RadioState.fromAdapterState(adapter.state)
        } catch (_: SecurityException) {
            RadioState.unauthorized
        }
    }
}

// MARK: - Helper interni

internal fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null) other == null else other != null && this.contentEquals(other)

/** `Data.lexicographicallyPrecedes` di Swift: confronto byte senza segno, il prefisso piu' corto precede. */
internal fun ByteArray.lexicographicallyPrecedes(other: ByteArray): Boolean {
    val n = minOf(size, other.size)
    for (i in 0 until n) {
        val a = this[i].toInt() and 0xFF
        val b = other[i].toInt() and 0xFF
        if (a != b) return a < b
    }
    return size < other.size
}
