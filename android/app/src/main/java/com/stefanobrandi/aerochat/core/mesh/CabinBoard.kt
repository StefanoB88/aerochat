package com.stefanobrandi.aerochat.core.mesh

import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.proto.BoardKind
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Posto del passeggero (`Presence.seat`, `BoardPost.seat`, RELEASE_1_4_SPEC.md §A1): trim + maiuscole,
 * `^[0-9]{1,3}[A-K]$` (fila 1...999, lettera A...K). Gli zeri iniziali della carta d'imbarco ("014C") si
 * tolgono prima; tutto il resto vale "posto non condiviso".
 */
object SeatCode {
    private val pattern = Regex("^[0-9]{1,3}[A-K]$")

    /** Posto valido normalizzato ("14c " -> "14C"), altrimenti `null`. */
    fun normalized(raw: String?): String? {
        val value = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
        return if (pattern.matches(value)) value else null
    }

    /** Fila del posto ("14C" -> 14), `null` se non valido. */
    fun row(seat: String): Int? = normalized(seat)?.dropLast(1)?.toIntOrNull()

    /** Lettera del posto ("14C" -> 'C'), `null` se non valido. */
    fun letter(seat: String): Char? = normalized(seat)?.last()
}

/**
 * Annuncio della bacheca della cabina (`BOARD_POST`): solo in memoria, mai nel DB. Le chiavi dell'autore
 * servono per rispondere con "Saluta" (`CONTACT_REQUEST`) anche se non e' nei contatti.
 */
class BoardPostEntry(
    val postID: ByteArray,
    val authorNodeID: ByteArray,
    val x25519Pub: ByteArray,
    val ed25519Pub: ByteArray,
    /** Nickname dell'autore (gia' filtrato se il filtro linguaggio e' attivo). */
    val nickname: String,
    val avatarColorHex: String?,
    val kind: BoardKind,
    /** 1...140 caratteri, senza link, gia' mascherato se il filtro e' attivo. */
    val text: String,
    /** Volo canonico dell'autore, `null` se non in volo. */
    val flightCode: String?,
    val seat: String?,
    val createdAtMs: Long,
    /** Ultima ripetizione ricevuta (o, per i nostri, ultimo invio). */
    val lastSeenAtMs: Long,
    /** Annuncio nostro (aperto finche' non lo chiudiamo o scade). */
    val isMine: Boolean,
) {
    val id: String get() = postID.toHex()
    val authorID: String get() = authorNodeID.toHex()

    /** Autore come persona vicina, per "Saluta" con le chiavi della bacheca. */
    val asNearbyPerson: NearbyPerson
        get() = NearbyPerson(
            nodeID = authorNodeID, x25519Pub = x25519Pub, ed25519Pub = ed25519Pub, nickname = nickname,
            avatarColorHex = avatarColorHex, flightCode = flightCode, openToGames = false, rssi = null,
            lastSeenAtMs = lastSeenAtMs, isContact = false, seat = seat,
        )

    fun seen(at: Long): BoardPostEntry = BoardPostEntry(
        postID, authorNodeID, x25519Pub, ed25519Pub, nickname, avatarColorHex, kind, text, flightCode, seat,
        createdAtMs, at, isMine,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoardPostEntry) return false
        return postID.contentEquals(other.postID) && authorNodeID.contentEquals(other.authorNodeID) &&
            nickname == other.nickname && kind == other.kind && text == other.text && flightCode == other.flightCode &&
            seat == other.seat && createdAtMs == other.createdAtMs && lastSeenAtMs == other.lastSeenAtMs &&
            isMine == other.isMine && avatarColorHex == other.avatarColorHex
    }

    override fun hashCode(): Int = 31 * postID.contentHashCode() + lastSeenAtMs.hashCode()

    override fun toString(): String = "BoardPostEntry(${postID.toHex()}, $kind, from=$nickname, mine=$isMine)"

    companion object {
        /** Saluto precompilato per rispondere a un annuncio: `"Per il tuo annuncio: <testo>"`, al massimo 140 caratteri. */
        fun replyGreeting(prefix: String, text: String, max: Int = MeshProtocol.maxGreetingLength): String {
            val full = prefix + text
            if (full.codePointCount(0, full.length) <= max) return full
            val room = max - prefix.codePointCount(0, prefix.length) - 1
            if (room <= 0) return prefix.substring(0, prefix.offsetByCodePoints(0, max.coerceAtMost(prefix.codePointCount(0, prefix.length))))
            return prefix + text.substring(0, text.offsetByCodePoints(0, room)).trimEnd() + "…"
        }

        /** Testo valido di un annuncio: trim, 1...140 caratteri, niente link; altrimenti `null`. */
        fun validText(raw: String): String? {
            val text = raw.trim()
            val count = text.codePointCount(0, text.length)
            if (count < 1 || count > MeshProtocol.maxBoardTextLength) return null
            if (MeshRouterActor.containsLink(text)) return null
            return text
        }
    }
}

/**
 * Bacheca della cabina (RELEASE_1_4_SPEC.md §A2): annunci ricevuti (cache in memoria, max 50, voce tolta se
 * non ripetuta da 10 min, chiusa, scaduta dopo 6 h o di un mittente bloccato; max 10 per mittente) e annunci
 * nostri aperti (max 3, scadono dopo 6 h). Tempi in epoch ms passati esplicitamente (test deterministici).
 */
class BoardTracker(
    private val capacity: Int = MeshProtocol.boardCacheCapacity,
    private val perSender: Int = MeshProtocol.maxBoardPostsPerSender,
    private val staleAfter: Long = MeshProtocol.boardPostStaleAfter,
    private val lifetime: Long = MeshProtocol.boardPostLifetime,
    private val maxOwn: Int = MeshProtocol.maxOwnBoardPosts,
) {
    private val received = LinkedHashMap<String, BoardPostEntry>()
    private val mine = LinkedHashMap<String, BoardPostEntry>()

    private val _posts = MutableStateFlow<List<BoardPostEntry>>(emptyList())
    /** Annunci da mostrare (nostri e degli altri), i piu' recenti prima. */
    val posts: StateFlow<List<BoardPostEntry>> = _posts.asStateFlow()

    // MARK: Ricevuti

    /**
     * Annuncio verificato ricevuto. Ritorna `true` se la bacheca e' cambiata. Un `post_id` gia' noto vale
     * solo dallo stesso autore (aggiorna `last_seen`); un nuovo annuncio oltre i 10 del mittente e' scartato.
     */
    @Synchronized
    fun receive(entry: BoardPostEntry, now: Long): Boolean {
        purgeLocked(now)
        if (now - entry.createdAtMs > lifetime) return false
        val existing = received[entry.id]
        if (existing != null) {
            if (existing.authorID != entry.authorID) return false
            received[entry.id] = entry.seen(now)
            publish()
            return true
        }
        if (received.values.count { it.authorID == entry.authorID } >= perSender) return false
        received[entry.id] = entry.seen(now)
        while (received.size > capacity) {
            val oldest = received.values.minByOrNull { it.lastSeenAtMs } ?: break
            received.remove(oldest.id)
        }
        publish()
        return true
    }

    /** L'autore ha chiuso l'annuncio (`is_closed`): via dalla bacheca (solo se e' davvero suo). */
    @Synchronized
    fun close(postHex: String, authorHex: String): Boolean {
        val existing = received[postHex] ?: return false
        if (existing.authorID != authorHex) return false
        received.remove(postHex)
        publish()
        return true
    }

    /** Mittente bloccato: tutti i suoi annunci spariscono. */
    @Synchronized
    fun removeAuthor(nodeHex: String) {
        val before = received.size
        received.values.removeAll { it.authorID == nodeHex }
        if (before != received.size) publish()
    }

    /** Autore di un annuncio ancora in bacheca (chiavi per aprire un suo `CONTACT_REQUEST`). */
    @Synchronized
    fun author(nodeHex: String, now: Long): BoardPostEntry? {
        purgeLocked(now)
        return received.values.firstOrNull { it.authorID == nodeHex }
    }

    // MARK: Nostri

    /** Nuovo annuncio nostro: `false` se ne abbiamo gia' 3 aperti. */
    @Synchronized
    fun addMine(entry: BoardPostEntry, now: Long): Boolean {
        purgeLocked(now)
        if (mine.size >= maxOwn) return false
        mine[entry.id] = entry
        publish()
        return true
    }

    /** Chiude un nostro annuncio: ritorna quello tolto (per l'ultimo invio con `is_closed`). */
    @Synchronized
    fun closeMine(postHex: String): BoardPostEntry? {
        val removed = mine.remove(postHex) ?: return null
        publish()
        return removed
    }

    /** Annunci nostri ancora aperti (da ripetere ogni 60 s). */
    @Synchronized
    fun openMine(now: Long): List<BoardPostEntry> {
        purgeLocked(now)
        return mine.values.toList()
    }

    @Synchronized
    fun canPost(now: Long): Boolean {
        purgeLocked(now)
        return mine.size < maxOwn
    }

    // MARK: Manutenzione

    @Synchronized
    fun purge(now: Long) {
        purgeLocked(now)
    }

    private fun purgeLocked(now: Long) {
        val before = received.size + mine.size
        received.values.removeAll { now - it.lastSeenAtMs > staleAfter || now - it.createdAtMs > lifetime }
        mine.values.removeAll { now - it.createdAtMs > lifetime }
        if (before != received.size + mine.size) publish()
    }

    @Synchronized
    fun clear() {
        received.clear()
        mine.clear()
        publish()
    }

    private fun publish() {
        _posts.value = (mine.values + received.values).sortedByDescending { it.createdAtMs }
    }
}
