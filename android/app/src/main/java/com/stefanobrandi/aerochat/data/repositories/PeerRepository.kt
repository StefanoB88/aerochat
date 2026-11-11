package com.stefanobrandi.aerochat.data.repositories

import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.data.AppDatabase
import com.stefanobrandi.aerochat.data.records.PeerRecord
import java.text.Collator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class PeerRepository(val database: AppDatabase) {

    private val dao: PeerDao get() = database.peerDao()

    /**
     * Inserisce o aggiorna un peer. Se gia' noto conserva `is_trusted`/`is_blocked` esistenti
     * (un gossip non deve mai declassare un compagno fidato) e il profilo ricevuto.
     * @param authoritativeNickname `true` se il nickname arriva firmato dal peer stesso;
     *   un nickname riferito da terzi (gossip, QR) non sovrascrive quello di un `PROFILE_UPDATE`.
     */
    suspend fun upsert(peer: PeerRecord, authoritativeNickname: Boolean = false): PeerRecord =
        database.withTransaction {
            val original = dao.fetchOne(peer.nodeID)
            if (original != null) {
                var existing = original
                if ((authoritativeNickname || existing.profileUpdatedAtMs == null) && existing.nickname != peer.nickname) {
                    existing = existing.copy(nickname = peer.nickname)
                    syncDirectTitle(peer.nodeID, peer.nickname)
                }
                existing = existing.copy(
                    x25519Pub = peer.x25519Pub,
                    ed25519Pub = peer.ed25519Pub,
                    isTrusted = existing.isTrusted || peer.isTrusted,
                )
                // Solo se qualcosa e' cambiato (spesso nulla): niente scrittura ne' refresh delle osservazioni.
                if (existing != original) dao.update(existing)
                return@withTransaction existing
            }
            dao.insert(peer)
            peer
        }

    /** Applica un `PROFILE_UPDATE` se piu' recente di quello noto. Ritorna `true` se applicato. */
    suspend fun applyProfile(
        nodeID: ByteArray,
        nickname: String,
        avatarColorHex: String?,
        avatarImage: ByteArray?,
        updatedAtMs: Long,
    ): Boolean = database.withTransaction {
        var peer = dao.fetchOne(nodeID) ?: return@withTransaction false
        if (updatedAtMs <= (peer.profileUpdatedAtMs ?: 0L)) return@withTransaction false
        if (nickname.isNotEmpty() && nickname != peer.nickname) {
            peer = peer.copy(nickname = nickname)
            syncDirectTitle(nodeID, nickname)
        }
        peer = peer.copy(avatarColorHex = avatarColorHex, avatarImage = avatarImage, profileUpdatedAtMs = updatedAtMs)
        dao.update(peer)
        true
    }

    /** Il titolo della chat 1-a-1 segue il nickname del compagno. */
    private suspend fun syncDirectTitle(nodeID: ByteArray, nickname: String) {
        database.conversationDao().syncDirectTitle(nodeID, nickname)
    }

    suspend fun fetch(nodeID: ByteArray): PeerRecord? = dao.fetchOne(nodeID)

    /** Tutti i peer in ordine alfabetico (case-insensitive, secondo la lingua corrente). */
    suspend fun fetchAll(): List<PeerRecord> = sortedByNickname(dao.fetchAll())

    /** Compagni di viaggio verso cui mantenere la connessione GATT persistente. */
    suspend fun trustedPeers(): List<PeerRecord> = dao.trustedPeers()

    /** Aggiorna telemetria radio del peer (chiamato dal router a ogni pacchetto ricevuto). `at` in epoch ms. */
    suspend fun markSeen(nodeID: ByteArray, hopCount: Int?, rssi: Int?, at: Long = System.currentTimeMillis()) {
        dao.markSeen(nodeID, at, hopCount, rssi)
    }

    suspend fun setTrusted(trusted: Boolean, nodeID: ByteArray) {
        dao.setTrusted(nodeID, trusted)
    }

    /** Blocca/silenzia un utente (pulsante rapido della Cabina Pubblica). */
    suspend fun setBlocked(blocked: Boolean, nodeID: ByteArray) {
        dao.setBlocked(nodeID, blocked)
    }

    /**
     * Memorizza l'identificatore BLE con cui abbiamo raggiunto il peer (ruolo central).
     * Su Android e' l'indirizzo del `BluetoothDevice` (su iOS l'UUID CoreBluetooth).
     */
    suspend fun setBLEIdentifier(identifier: String, nodeID: ByteArray) {
        dao.setBLEIdentifier(nodeID, identifier)
    }

    /** Identificatori BLE dei compagni fidati da tenere in riconnessione perenne. */
    suspend fun trustedPeripheralIdentifiers(): Set<String> = dao.trustedPeripheralIdentifiers().toSet()

    /** Contatti non bloccati a cui annunciare il nostro profilo. */
    suspend fun unblockedPeers(): List<PeerRecord> = dao.unblockedPeers()

    /** `node_id` bloccati, come stringhe esadecimali minuscole (`ByteArray.toHex()`). */
    suspend fun blockedNodeIDs(): Set<String> = dao.blockedNodeIDs().mapTo(mutableSetOf()) { it.toHex() }

    /** Stream di un singolo peer (header della chat 1-a-1). */
    fun observe(nodeID: ByteArray): Flow<PeerRecord?> = dao.observeOne(nodeID).distinctUntilChanged()

    /** Ordinamento (Collator) e confronto fuori dal main thread: i collector sono in UI. */
    fun observeAll(): Flow<List<PeerRecord>> =
        dao.observeAll().map { sortedByNickname(it) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private fun sortedByNickname(peers: List<PeerRecord>): List<PeerRecord> {
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return peers.sortedWith { a, b -> collator.compare(a.nickname, b.nickname) }
    }
}

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers WHERE node_id = :nodeID")
    suspend fun fetchOne(nodeID: ByteArray): PeerRecord?

    @Query("SELECT * FROM peers WHERE node_id = :nodeID")
    fun observeOne(nodeID: ByteArray): Flow<PeerRecord?>

    @Query("SELECT * FROM peers")
    suspend fun fetchAll(): List<PeerRecord>

    @Query("SELECT * FROM peers")
    fun observeAll(): Flow<List<PeerRecord>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(peer: PeerRecord)

    @Update
    suspend fun update(peer: PeerRecord)

    @Query("SELECT * FROM peers WHERE is_trusted = 1 AND is_blocked = 0")
    suspend fun trustedPeers(): List<PeerRecord>

    @Query(
        "UPDATE peers SET last_seen_at_ms = :seenAtMs, " +
            "last_hop_count = COALESCE(:hopCount, last_hop_count), " +
            "last_rssi = COALESCE(:rssi, last_rssi) WHERE node_id = :nodeID",
    )
    suspend fun markSeen(nodeID: ByteArray, seenAtMs: Long, hopCount: Int?, rssi: Int?)

    @Query("UPDATE peers SET is_trusted = :trusted WHERE node_id = :nodeID")
    suspend fun setTrusted(nodeID: ByteArray, trusted: Boolean)

    @Query("UPDATE peers SET is_blocked = :blocked WHERE node_id = :nodeID")
    suspend fun setBlocked(nodeID: ByteArray, blocked: Boolean)

    @Query("UPDATE peers SET ble_identifier = :identifier WHERE node_id = :nodeID")
    suspend fun setBLEIdentifier(nodeID: ByteArray, identifier: String)

    @Query("SELECT ble_identifier FROM peers WHERE is_trusted = 1 AND is_blocked = 0 AND ble_identifier IS NOT NULL")
    suspend fun trustedPeripheralIdentifiers(): List<String>

    @Query("SELECT * FROM peers WHERE is_blocked = 0")
    suspend fun unblockedPeers(): List<PeerRecord>

    @Query("SELECT node_id FROM peers WHERE is_blocked = 1")
    suspend fun blockedNodeIDs(): List<ByteArray>
}
