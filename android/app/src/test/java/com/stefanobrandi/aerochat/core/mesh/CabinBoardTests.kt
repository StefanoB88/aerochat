package com.stefanobrandi.aerochat.core.mesh

import com.stefanobrandi.aerochat.core.crypto.IdentityKeys
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.proto.BoardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release 1.4: posto del passeggero e bacheca della cabina (logica pura, RELEASE_1_4_SPEC.md §A). */
class CabinBoardTests {

    private val author = IdentityKeys.generate()
    private val other = IdentityKeys.generate()

    private fun entry(
        keys: IdentityKeys = author,
        id: Int = 1,
        createdAt: Long = 1_000_000L,
        mine: Boolean = false,
        text: String = "Cerco un caricatore USB-C",
    ) = BoardPostEntry(
        postID = ByteArray(16) { (id + it).toByte() }.also { it[0] = id.toByte(); it[15] = (id shr 8).toByte() },
        authorNodeID = keys.nodeID, x25519Pub = keys.x25519PublicKeyRaw, ed25519Pub = keys.ed25519PublicKeyRaw,
        nickname = "Marco", avatarColorHex = null, kind = BoardKind.BOARD_KIND_LOOKING_FOR, text = text,
        flightCode = null, seat = null, createdAtMs = createdAt, lastSeenAtMs = createdAt, isMine = mine,
    )

    @Test
    fun seatValidation() {
        assertEquals("14C", SeatCode.normalized(" 14c "))
        assertEquals("1A", SeatCode.normalized("1A"))
        assertEquals("999K", SeatCode.normalized("999k"))
        assertNull("lettera oltre K", SeatCode.normalized("14L"))
        assertNull(SeatCode.normalized("1234A"))
        assertNull(SeatCode.normalized("C14"))
        assertNull(SeatCode.normalized(""))
        assertNull(SeatCode.normalized(null))
        assertNull(SeatCode.normalized("14 C"))
        assertEquals(14, SeatCode.row("14C"))
        assertEquals('C', SeatCode.letter("14c"))
        assertNull(SeatCode.row("XX"))
    }

    @Test
    fun boardTextRules() {
        assertEquals("Offro snack", BoardPostEntry.validText("  Offro snack  "))
        assertNull(BoardPostEntry.validText("   "))
        assertNull(BoardPostEntry.validText("x".repeat(141)))
        assertNotNull(BoardPostEntry.validText("😀".repeat(140)))
        assertNull(BoardPostEntry.validText("Guarda https://example.com"))
        assertNull(BoardPostEntry.validText("www.example.com"))
    }

    @Test
    fun replyGreetingIsTruncatedTo140() {
        val prefix = "Per il tuo annuncio: "
        assertEquals(prefix + "Cerco un caricatore", BoardPostEntry.replyGreeting(prefix, "Cerco un caricatore"))
        val long = BoardPostEntry.replyGreeting(prefix, "a".repeat(140))
        assertEquals(MeshProtocol.maxGreetingLength, long.codePointCount(0, long.length))
        assertTrue(long.startsWith(prefix))
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun receiverCacheExpiresAfterTenMinutesAndSixHours() {
        val tracker = BoardTracker()
        val t0 = 1_000_000L
        assertTrue(tracker.receive(entry(createdAt = t0), now = t0))
        assertEquals(1, tracker.posts.value.size)
        // Ripetuto entro 10 minuti: resta.
        assertTrue(tracker.receive(entry(createdAt = t0), now = t0 + 9 * 60_000L))
        tracker.purge(t0 + 18 * 60_000L)
        assertEquals(1, tracker.posts.value.size)
        // Non ripetuto da oltre 10 minuti: via.
        tracker.purge(t0 + 20 * 60_000L)
        assertTrue(tracker.posts.value.isEmpty())

        // Piu' vecchio di 6 ore: rifiutato anche se ripetuto.
        assertFalse(tracker.receive(entry(createdAt = t0), now = t0 + MeshProtocol.boardPostLifetime + 1))
    }

    @Test
    fun receiverLimitsPerSenderAndCapacity() {
        val tracker = BoardTracker()
        val now = 5_000_000L
        (1..12).forEach { tracker.receive(entry(id = it, createdAt = now), now = now) }
        assertEquals("max 10 per mittente", MeshProtocol.maxBoardPostsPerSender, tracker.posts.value.size)

        val big = BoardTracker()
        (1..60).forEach { index ->
            val keys = IdentityKeys.generate()
            big.receive(entry(keys = keys, id = index, createdAt = now), now = now + index)
        }
        assertEquals(MeshProtocol.boardCacheCapacity, big.posts.value.size)
    }

    @Test
    fun closeOnlyByAuthorAndBlockedRemoval() {
        val tracker = BoardTracker()
        val now = 7_000_000L
        val post = entry(createdAt = now)
        tracker.receive(post, now)
        // Un altro nodo non puo' chiudere (ne' riusare) l'annuncio altrui.
        assertFalse(tracker.close(post.id, other.nodeID.toHex()))
        assertFalse(tracker.receive(entry(keys = other, createdAt = now), now))
        assertEquals(1, tracker.posts.value.size)
        assertNotNull(tracker.author(post.authorID, now))
        assertTrue(tracker.close(post.id, post.authorID))
        assertTrue(tracker.posts.value.isEmpty())

        tracker.receive(post, now)
        tracker.removeAuthor(post.authorID)
        assertTrue(tracker.posts.value.isEmpty())
    }

    @Test
    fun ownPostsMaxThreeAndExpireAfterSixHours() {
        val tracker = BoardTracker()
        val now = 9_000_000L
        assertTrue(tracker.addMine(entry(id = 1, createdAt = now, mine = true), now))
        assertTrue(tracker.addMine(entry(id = 2, createdAt = now, mine = true), now))
        assertTrue(tracker.addMine(entry(id = 3, createdAt = now, mine = true), now))
        assertFalse(tracker.canPost(now))
        assertFalse(tracker.addMine(entry(id = 4, createdAt = now, mine = true), now))
        val closed = tracker.closeMine(entry(id = 2).id)
        assertNotNull(closed)
        assertTrue(tracker.canPost(now))
        assertEquals(2, tracker.openMine(now).size)
        // I nostri annunci non scadono per "silenzio" (li ripetiamo noi), ma dopo 6 ore si'.
        assertEquals(2, tracker.openMine(now + 30 * 60_000L).size)
        assertTrue(tracker.openMine(now + MeshProtocol.boardPostLifetime + 1).isEmpty())
    }
}
