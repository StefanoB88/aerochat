package com.stefanobrandi.aerochat.core.mesh

import com.stefanobrandi.aerochat.core.flight.BoardingPass
import com.stefanobrandi.aerochat.core.moderation.AppSettings
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.MeshWire
import com.stefanobrandi.aerochat.core.protocol.toByteString
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.proto.BoardKind
import com.stefanobrandi.aerochat.proto.BoardPost
import com.stefanobrandi.aerochat.proto.PacketType
import com.stefanobrandi.aerochat.proto.Presence
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Release 1.4: `Presence.seat` e bacheca della cabina (`BOARD_POST`), contratto RELEASE_1_4_SPEC.md §A. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoardMeshTests : MeshTestCase() {

    @Before
    fun resetSettings() = AppSettings.resetForTesting()

    @After
    fun cleanSettings() = AppSettings.resetForTesting()

    private fun TestNode.boardPosts(): List<BoardPostEntry> = router.board.posts.value.filter { !it.isMine }

    private fun TestNode.sees(other: TestNode): NearbyPerson? =
        router.nearby.people.value.firstOrNull { it.nodeID.contentEquals(other.nodeID) }

    private fun pass(flight: String = "204") = BoardingPass(
        carrier = "AZ", flightNumber = flight, origin = "FCO", destination = "LHR", seat = "014C",
        departureDay = System.currentTimeMillis(),
    )

    // MARK: - Contratto wire

    @Test
    fun wireConstantsAndCrossPlatformVectors() {
        assertEquals(20, PacketType.BOARD_POST.number)
        assertFalse(PacketType.BOARD_POST.isStoreAndForwardEligible)
        assertEquals(3, MeshProtocol.boardPostTTL)
        assertEquals(1, BoardKind.BOARD_KIND_LOOKING_FOR.number)
        assertEquals(2, BoardKind.BOARD_KIND_OFFERING.number)
        assertEquals(3, BoardKind.BOARD_KIND_FOUND.number)
        assertEquals(4, BoardKind.BOARD_KIND_SEAT_SWAP.number)

        // Presence con il posto (campo 5): stessi byte del vettore 1.3 + `2a 03 "14C"`.
        val presence = Presence.newBuilder()
            .setAvatarColorHex("00E5FF")
            .setFlightCode("AZ|204|2026-11-10")
            .setOpenToGames(true)
            .setSentAtMs(1_700_000_000_000L)
            .setSeat("14C")
            .build()
        assertEquals(
            "0a063030453546461211415a7c3230347c323032362d31312d313018012080d095ffbc312a03313443",
            MeshWire.serialize(presence).toHex(),
        )
        assertEquals(presence, MeshWire.parsePresence(MeshWire.serialize(presence)))

        val post = BoardPost.newBuilder()
            .setPostId(ByteArray(16) { it.toByte() }.toByteString())
            .setKind(BoardKind.BOARD_KIND_SEAT_SWAP)
            .setText("Ciao")
            .setFlightCode("AZ|204|2026-11-10")
            .setSeat("14C")
            .setCreatedAtMs(1_700_000_000_000L)
            .setIsClosed(true)
            .build()
        assertEquals(
            "0a10000102030405060708090a0b0c0d0e0f" + "1004" + "1a044369616f" +
                "2211415a7c3230347c323032362d31312d3130" + "2a03313443" + "3080d095ffbc31" + "3801",
            MeshWire.serialize(post).toHex(),
        )
        assertEquals(post, MeshWire.parseBoardPost(MeshWire.serialize(post)))
    }

    // MARK: - Presence.seat

    @Test
    fun seatIsSharedOnlyWhenEnabledAndOnSameFlight() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        val flight = pass()
        alice.router.joinFlightLounge(flight)
        bob.router.joinFlightLounge(flight)
        AppSettings.setSeat("14C", flightCode = flight.canonicalKey)

        FakeMeshNetwork.connect(alice.transport, bob.transport)
        assertTrue(eventually { bob.sees(alice) != null })
        assertNull("posto non condiviso di default", bob.sees(alice)?.seat)

        AppSettings.isSeatShared = true
        alice.router.announcePresence()
        assertTrue(eventually { bob.sees(alice)?.seat == "14C" })
        assertEquals("14C", bob.sees(alice)?.seatOnFlight(flight.canonicalKey))
        assertNull("altro volo: il posto non si mostra", bob.sees(alice)?.seatOnFlight("AZ|999|2026-11-10"))
    }

    // MARK: - BOARD_POST

    @Test
    fun postIsRelayedAndClosed() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        val carol = node("Carol")
        // Catena Alice - Bob - Carol: Carol riceve tramite il ponte (ttl 3).
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        FakeMeshNetwork.connect(bob.transport, carol.transport)
        assertTrue(eventually { alice.sees(bob) != null && carol.sees(bob) != null })

        val posted = alice.router.postToBoard(BoardKind.BOARD_KIND_LOOKING_FOR, "  Cerco un caricatore USB-C  ")
        assertEquals("Cerco un caricatore USB-C", posted.text)
        assertTrue(eventually { carol.boardPosts().any { it.id == posted.id } })
        val seen = carol.boardPosts().first { it.id == posted.id }
        assertEquals("Alice", seen.nickname)
        assertEquals(BoardKind.BOARD_KIND_LOOKING_FOR, seen.kind)
        assertTrue(seen.authorNodeID.contentEquals(alice.nodeID))
        assertTrue(bob.boardPosts().any { it.id == posted.id })

        // Mai nel DB: nessun peer salvato per un annuncio.
        assertNull(carol.database.peers.fetch(nodeID = alice.nodeID))

        // Chiuso dall'autore: sparisce anche dopo il ponte.
        assertTrue(alice.router.closeBoardPost(posted.id))
        assertTrue(eventually { carol.boardPosts().isEmpty() && bob.boardPosts().isEmpty() })
    }

    @Test
    fun ownLimitAndInvalidText() = meshTest {
        val alice = node("Alice")
        alice.router.start()
        repeat(3) { alice.router.postToBoard(BoardKind.BOARD_KIND_OFFERING, "Snack $it") }
        try {
            alice.router.postToBoard(BoardKind.BOARD_KIND_OFFERING, "Quarto")
            fail("massimo 3 annunci aperti")
        } catch (_: RouterError.BoardLimitReached) {
        }
        val first = alice.router.board.openMine(System.currentTimeMillis()).first()
        alice.router.closeBoardPost(first.id)
        try {
            alice.router.postToBoard(BoardKind.BOARD_KIND_OFFERING, "Vedi https://example.com")
            fail("niente link")
        } catch (_: RouterError.InvalidBoardPost) {
        }
        assertNotNull(alice.router.postToBoard(BoardKind.BOARD_KIND_FOUND, "Trovati occhiali fila 12"))
    }

    @Test
    fun otherFlightPostsAreIgnored() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        alice.router.joinFlightLounge(pass("204"))
        bob.router.joinFlightLounge(pass("777"))
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        assertTrue(eventually { bob.sees(alice) != null })
        alice.router.postToBoard(BoardKind.BOARD_KIND_SEAT_SWAP, "Scambio posto", seat = "14c")
        delay(400)
        assertTrue("annuncio di un altro volo ignorato", bob.boardPosts().isEmpty())
    }

    @Test
    fun replyToPostReachesAuthorAsHello() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        // Alice invisibile: Bob conosce le sue chiavi solo dalla bacheca.
        alice.router.setPresenceOptions(visible = false, openToGames = true)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        assertTrue(eventually { alice.sees(bob) != null })
        val posted = alice.router.postToBoard(BoardKind.BOARD_KIND_OFFERING, "Offro cuffie in prestito")
        assertTrue(eventually { bob.boardPosts().any { it.id == posted.id } })
        assertNull(bob.sees(alice))

        val entry = bob.boardPosts().first { it.id == posted.id }
        val greeting = BoardPostEntry.replyGreeting("Per il tuo annuncio: ", entry.text)
        assertTrue(bob.router.sendContactRequest(to = entry.asNearbyPerson, greeting = greeting))
        assertTrue(eventually { alice.router.nearby.requests.value.isNotEmpty() })
        val request = alice.router.nearby.requests.value.first()
        assertEquals("Per il tuo annuncio: Offro cuffie in prestito", request.greeting)
        assertTrue(request.senderNodeID.contentEquals(bob.nodeID))
    }
}
