package com.example.aerochat.data.records

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.aerochat.core.crypto.PairingPayload

/** Contatto conosciuto (scansionato via QR o appreso dal Group Key Gossip). */
@Entity(
    tableName = PeerRecord.databaseTableName,
    indices = [
        Index(value = ["x25519_pub"], name = "index_peers_on_x25519_pub", unique = true),
        Index(value = ["is_trusted"], name = "index_peers_on_is_trusted"),
    ],
)
data class PeerRecord(
    @PrimaryKey
    @ColumnInfo(name = "node_id")
    val nodeID: ByteArray,
    @ColumnInfo(name = "x25519_pub")
    val x25519Pub: ByteArray,
    @ColumnInfo(name = "ed25519_pub")
    val ed25519Pub: ByteArray,
    @ColumnInfo(name = "nickname")
    val nickname: String,
    /** Compagno di viaggio: verso di lui manteniamo la connessione GATT persistente in background. */
    @ColumnInfo(name = "is_trusted", defaultValue = "0")
    val isTrusted: Boolean = false,
    @ColumnInfo(name = "is_blocked", defaultValue = "0")
    val isBlocked: Boolean = false,
    @ColumnInfo(name = "last_seen_at_ms")
    val lastSeenAtMs: Long? = null,
    @ColumnInfo(name = "last_hop_count")
    val lastHopCount: Int? = null,
    @ColumnInfo(name = "last_rssi")
    val lastRSSI: Int? = null,
    /** epoch ms (Swift: `createdAt: Date`). */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long = System.currentTimeMillis(),
    /**
     * Identificatore BLE del dispositivo per la riconnessione in background.
     * Su iOS e' l'UUID CoreBluetooth del peripheral; su Android l'indirizzo del `BluetoothDevice`.
     */
    @ColumnInfo(name = "ble_identifier")
    val bleIdentifier: String? = null,
    /** Profilo pubblico ricevuto con `PROFILE_UPDATE` (colore avatar e foto WebP). */
    @ColumnInfo(name = "avatar_color_hex")
    val avatarColorHex: String? = null,
    @ColumnInfo(name = "avatar_image")
    val avatarImage: ByteArray? = null,
    @ColumnInfo(name = "profile_updated_at_ms")
    val profileUpdatedAtMs: Long? = null,
) {

    /** Peer da un QR di pairing: i compagni scansionati di persona sono fidati per definizione. `createdAt` in epoch ms. */
    @Ignore
    constructor(
        pairing: PairingPayload,
        isTrusted: Boolean = true,
        createdAt: Long = System.currentTimeMillis(),
    ) : this(
        nodeID = pairing.nodeID,
        x25519Pub = pairing.x25519PublicKeyRaw,
        ed25519Pub = pairing.ed25519PublicKeyRaw,
        nickname = pairing.nickname,
        isTrusted = isTrusted,
        createdAtMs = createdAt,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerRecord) return false
        return nodeID.contentEquals(other.nodeID) &&
            x25519Pub.contentEquals(other.x25519Pub) &&
            ed25519Pub.contentEquals(other.ed25519Pub) &&
            nickname == other.nickname &&
            isTrusted == other.isTrusted &&
            isBlocked == other.isBlocked &&
            lastSeenAtMs == other.lastSeenAtMs &&
            lastHopCount == other.lastHopCount &&
            lastRSSI == other.lastRSSI &&
            createdAtMs == other.createdAtMs &&
            bleIdentifier == other.bleIdentifier &&
            avatarColorHex == other.avatarColorHex &&
            avatarImage.contentEquals(other.avatarImage) &&
            profileUpdatedAtMs == other.profileUpdatedAtMs
    }

    override fun hashCode(): Int {
        var result = nodeID.contentHashCode()
        result = 31 * result + x25519Pub.contentHashCode()
        result = 31 * result + ed25519Pub.contentHashCode()
        result = 31 * result + nickname.hashCode()
        result = 31 * result + isTrusted.hashCode()
        result = 31 * result + isBlocked.hashCode()
        result = 31 * result + (lastSeenAtMs?.hashCode() ?: 0)
        result = 31 * result + (lastHopCount ?: 0)
        result = 31 * result + (lastRSSI ?: 0)
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + (bleIdentifier?.hashCode() ?: 0)
        result = 31 * result + (avatarColorHex?.hashCode() ?: 0)
        result = 31 * result + avatarImage.contentHashCode()
        result = 31 * result + (profileUpdatedAtMs?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val databaseTableName = "peers"
    }
}
