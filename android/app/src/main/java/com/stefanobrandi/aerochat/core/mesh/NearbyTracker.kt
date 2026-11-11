package com.stefanobrandi.aerochat.core.mesh

import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.data.records.PeerRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persona vicina annunciata da un `PRESENCE` (NEARBY_SPEC.md §1): solo in memoria, scade 3 minuti dopo
 * l'ultimo annuncio. Le chiavi servono per cifrare "Saluta" / "Sfida" (`CONTACT_REQUEST`) anche se il peer
 * non e' ancora nel database.
 */
class NearbyPerson(
    val nodeID: ByteArray,
    val x25519Pub: ByteArray,
    val ed25519Pub: ByteArray,
    /** Nickname dichiarato (gia' filtrato se il filtro linguaggio e' attivo). */
    val nickname: String,
    /** `RRGGBB` maiuscolo, `null` se assente o non valido. */
    val avatarColorHex: String?,
    /** Volo canonico `CARRIER|FLIGHT|yyyy-MM-dd`, `null` se non e' nella Cabina di un volo. */
    val flightCode: String?,
    val openToGames: Boolean,
    /** RSSI del link diretto, se il trasporto lo conosce. */
    val rssi: Int?,
    val lastSeenAtMs: Long,
    /** Gia' nei contatti (peer fidato nel database). */
    val isContact: Boolean,
) {
    val id: String get() = nodeID.toHex()

    /** Record peer transitorio (non salvato) per cifrare un pacchetto diretto. */
    val transientPeer: PeerRecord
        get() = PeerRecord(nodeID = nodeID, x25519Pub = x25519Pub, ed25519Pub = ed25519Pub, nickname = nickname)

    fun isOnFlight(code: String?): Boolean = code != null && flightCode != null && code == flightCode

    fun copy(rssi: Int? = this.rssi, isContact: Boolean = this.isContact): NearbyPerson = NearbyPerson(
        nodeID, x25519Pub, ed25519Pub, nickname, avatarColorHex, flightCode, openToGames, rssi, lastSeenAtMs, isContact,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NearbyPerson) return false
        return nodeID.contentEquals(other.nodeID) && x25519Pub.contentEquals(other.x25519Pub) &&
            ed25519Pub.contentEquals(other.ed25519Pub) && nickname == other.nickname &&
            avatarColorHex == other.avatarColorHex && flightCode == other.flightCode &&
            openToGames == other.openToGames && rssi == other.rssi && lastSeenAtMs == other.lastSeenAtMs &&
            isContact == other.isContact
    }

    override fun hashCode(): Int {
        var result = nodeID.contentHashCode()
        result = 31 * result + nickname.hashCode()
        result = 31 * result + lastSeenAtMs.hashCode()
        result = 31 * result + isContact.hashCode()
        return result
    }

    override fun toString(): String = "NearbyPerson(${nodeID.toHex()}, $nickname, flight=$flightCode, games=$openToGames)"
}

/** "Saluta" / "Sfida" ricevuto (`CONTACT_REQUEST` REQUEST), in attesa di Accetta / Rifiuta (scade dopo 30 min). */
class IncomingContactRequest(
    val requestID: ByteArray,
    val senderNodeID: ByteArray,
    val x25519Pub: ByteArray,
    val ed25519Pub: ByteArray,
    val nickname: String,
    val avatarColorHex: String?,
    /** Gia' filtrato (linguaggio) e senza link; vuoto se assente. */
    val greeting: String,
    /** 0 = "Saluta"; altrimenti `GameKind` della sfida. */
    val gameKind: Int,
    val receivedAtMs: Long,
) {
    val id: String get() = requestID.toHex()
    val senderID: String get() = senderNodeID.toHex()
    val isChallenge: Boolean get() = gameKind != 0

    override fun equals(other: Any?): Boolean =
        other is IncomingContactRequest && requestID.contentEquals(other.requestID) &&
            senderNodeID.contentEquals(other.senderNodeID) && nickname == other.nickname &&
            greeting == other.greeting && gameKind == other.gameKind && receivedAtMs == other.receivedAtMs

    override fun hashCode(): Int = 31 * requestID.contentHashCode() + receivedAtMs.hashCode()

    override fun toString(): String = "IncomingContactRequest(${requestID.toHex()}, from=$nickname, game=$gameKind)"
}

/** Richiesta partita da noi: solo un ACCEPT/DECLINE con lo stesso `request_id` e dallo stesso nodo vale. */
class OutgoingContactRequest(
    val requestID: ByteArray,
    val peer: PeerRecord,
    val gameKind: Int,
    val sentAtMs: Long,
) {
    val id: String get() = requestID.toHex()
}

/**
 * Cache dei vicini (`PRESENCE`) e richieste "Saluta"/"Sfida" (`CONTACT_REQUEST`): solo in memoria.
 *
 * Le mutazioni avvengono dal router (dispatcher seriale); la UI osserva [people] e [requests]. Tutti i
 * tempi sono epoch ms passati esplicitamente (test deterministici).
 */
class NearbyTracker(
    private val presenceLifetime: Long = MeshProtocol.presenceLifetime,
    private val capacity: Int = MeshProtocol.presenceCapacity,
    private val requestLifetime: Long = MeshProtocol.contactRequestLifetime,
    private val rateLimit: Long = MeshProtocol.contactRequestRateLimit,
    private val maxPending: Int = MeshProtocol.maxPendingContactRequests,
) {
    private val entries = LinkedHashMap<String, NearbyPerson>()
    private val incoming = LinkedHashMap<String, IncomingContactRequest>()
    private val outgoing = LinkedHashMap<String, OutgoingContactRequest>()
    /** Ultima REQUEST accettata in coda per mittente (hex): limite di una ogni 10 minuti. */
    private val lastRequestAt = HashMap<String, Long>()

    private val _people = MutableStateFlow<List<NearbyPerson>>(emptyList())
    /** Vicini ordinati per segnale (piu' forte prima) e poi per ultimo visto. */
    val people: StateFlow<List<NearbyPerson>> = _people.asStateFlow()

    private val _requests = MutableStateFlow<List<IncomingContactRequest>>(emptyList())
    /** Richieste ricevute in attesa, le piu' recenti prima. */
    val requests: StateFlow<List<IncomingContactRequest>> = _requests.asStateFlow()

    // MARK: Presenza

    /** Nuovo `PRESENCE` verificato: sostituisce la voce del nodo (oltre la capienza esce la meno recente). */
    @Synchronized
    fun updatePresence(person: NearbyPerson, now: Long) {
        purgeLocked(now)
        entries.remove(person.id)
        entries[person.id] = person
        while (entries.size > capacity) {
            val oldest = entries.values.minByOrNull { it.lastSeenAtMs } ?: break
            entries.remove(oldest.id)
        }
        publishPeople()
    }

    @Synchronized
    fun person(nodeHex: String, now: Long): NearbyPerson? {
        val entry = entries[nodeHex] ?: return null
        return if (now - entry.lastSeenAtMs > presenceLifetime) null else entry
    }

    /** Il peer e' diventato un contatto (QR o ACCEPT): "Gia' nei contatti". */
    @Synchronized
    fun markContact(nodeHex: String) {
        val entry = entries[nodeHex] ?: return
        if (entry.isContact) return
        entries[nodeHex] = entry.copy(isContact = true)
        publishPeople()
    }

    @Synchronized
    fun remove(nodeHex: String) {
        val removed = entries.remove(nodeHex) != null
        val before = incoming.size
        incoming.values.removeAll { it.senderID == nodeHex }
        if (removed) publishPeople()
        if (before != incoming.size) publishRequests()
    }

    /** Dimentica vicini e richieste scaduti. */
    @Synchronized
    fun purge(now: Long) {
        purgeLocked(now)
    }

    private fun purgeLocked(now: Long) {
        val peopleBefore = entries.size
        entries.values.removeAll { now - it.lastSeenAtMs > presenceLifetime }
        if (peopleBefore != entries.size) publishPeople()
        val requestsBefore = incoming.size
        incoming.values.removeAll { now - it.receivedAtMs > requestLifetime }
        if (requestsBefore != incoming.size) publishRequests()
        outgoing.values.removeAll { now - it.sentAtMs > requestLifetime }
        lastRequestAt.entries.removeAll { now - it.value >= rateLimit }
    }

    // MARK: Richieste ricevute

    /**
     * Mette in "Richieste" una REQUEST verificata. Ritorna `false` (scartata) se lo stesso mittente ne ha gia'
     * inviata una negli ultimi 10 minuti o se ci sono gia' 20 richieste in attesa.
     */
    @Synchronized
    fun addIncoming(request: IncomingContactRequest, now: Long): Boolean {
        purgeLocked(now)
        val last = lastRequestAt[request.senderID]
        if (last != null && now - last < rateLimit) return false
        if (incoming.size >= maxPending) return false
        if (incoming.containsKey(request.id)) return false
        lastRequestAt[request.senderID] = now
        incoming[request.id] = request
        publishRequests()
        return true
    }

    /** Toglie la richiesta (risposta data o ignorata). */
    @Synchronized
    fun takeIncoming(requestHex: String): IncomingContactRequest? {
        val request = incoming.remove(requestHex) ?: return null
        publishRequests()
        return request
    }

    // MARK: Richieste inviate

    @Synchronized
    fun addOutgoing(request: OutgoingContactRequest, now: Long) {
        purgeLocked(now)
        outgoing[request.id] = request
    }

    /** Richiesta inviata da noi con questo id verso questo nodo (consumata), se ancora valida. */
    @Synchronized
    fun takeOutgoing(requestHex: String, fromNodeHex: String, now: Long): OutgoingContactRequest? {
        purgeLocked(now)
        val request = outgoing[requestHex] ?: return null
        if (request.peer.nodeID.toHex() != fromNodeHex) return null
        outgoing.remove(requestHex)
        return request
    }

    /** Peer di una nostra richiesta in corso verso questo nodo (chiavi per aprire ACCEPT/DECLINE). */
    @Synchronized
    fun outgoingPeer(toNodeHex: String, now: Long): PeerRecord? {
        purgeLocked(now)
        return outgoing.values.firstOrNull { it.peer.nodeID.toHex() == toNodeHex }?.peer
    }

    @Synchronized
    fun clear() {
        entries.clear()
        incoming.clear()
        outgoing.clear()
        lastRequestAt.clear()
        publishPeople()
        publishRequests()
    }

    private fun publishPeople() {
        _people.value = entries.values.sortedWith(
            compareByDescending<NearbyPerson> { it.rssi ?: Int.MIN_VALUE }.thenByDescending { it.lastSeenAtMs },
        )
    }

    private fun publishRequests() {
        _requests.value = incoming.values.sortedByDescending { it.receivedAtMs }
    }
}
