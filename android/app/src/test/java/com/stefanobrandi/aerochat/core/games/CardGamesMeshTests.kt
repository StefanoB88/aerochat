package com.stefanobrandi.aerochat.core.games

import com.stefanobrandi.aerochat.core.crypto.CryptoEngine
import com.stefanobrandi.aerochat.core.mesh.FakeMeshNetwork
import com.stefanobrandi.aerochat.core.mesh.MeshTestCase
import com.stefanobrandi.aerochat.core.mesh.TestNode
import com.stefanobrandi.aerochat.core.mesh.eventually
import com.stefanobrandi.aerochat.core.protocol.InnerPayloadFactory
import com.stefanobrandi.aerochat.core.protocol.MeshWire
import com.stefanobrandi.aerochat.core.protocol.toByteString
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.PeerRecord
import com.stefanobrandi.aerochat.proto.GameAction
import com.stefanobrandi.aerochat.proto.GameKind
import com.stefanobrandi.aerochat.proto.GameMove
import com.stefanobrandi.aerochat.proto.PacketType
import java.util.UUID
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Scopa, Scopone scientifico e Briscola sulla mesh finta (GameService completo su Room in memoria). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardGamesMeshTests : MeshTestCase() {

    private val services = ArrayList<GameService>()

    @After
    fun stopServices() = services.forEach { it.stop() }

    private suspend fun games(node: TestNode): GameService =
        GameService(router = node.router, database = node.database, identity = node.identity, nickname = node.nickname)
            .also { it.start(); services += it }

    private val identityDeck = (0 until 40).toList()

    /** Bot §8.7: prima carta in mano, prima presa legale in ordine canonico. */
    private fun botChoice(state: GameSessionState): Pair<Int, List<Int>> {
        val seat = state.mySeat!!
        state.scopa?.let { game ->
            val card = game.hands[seat].first()
            return card to (game.captureOptions(card).firstOrNull() ?: emptyList())
        }
        val game = state.briscola!!
        return game.hands[seat].first() to emptyList()
    }

    /**
     * Gioca col bot finche' la partita non e' finita (o per `maxMoves` mosse): a ogni passo attende che
     * tutti (giocatori e spettatori) abbiano lo stesso `sequence` e che tocchi a qualcuno.
     */
    private suspend fun botPlay(
        players: List<GameService>,
        watchers: List<GameService>,
        gameID: String,
        maxMoves: Int = Int.MAX_VALUE,
    ): Int {
        var moves = 0
        while (moves < maxMoves) {
            val everyone = players + watchers
            val settled = eventually(timeout = 10_000) {
                val states = everyone.map { it.session(gameID) }
                states.map { it.sequence }.toSet().size == 1 && states.all { it.pendingMoves == null } &&
                    (states.all { it.status == GameStatus.finished } || states.any { it.myTurn })
            }
            assertTrue("stati non allineati dopo $moves mosse", settled)
            val states = players.map { it.session(gameID) }
            if (states.all { it.status == GameStatus.finished }) break
            val index = states.indexOfFirst { it.myTurn }
            val (card, captured) = botChoice(states[index])
            players[index].playCard(gameID, card = card, captured = captured)
            moves += 1
        }
        return moves
    }

    private fun assertSameTable(states: List<GameSessionState>) {
        val first = states.first()
        for (state in states.drop(1)) {
            assertEquals(first.sequence, state.sequence)
            assertEquals(first.scopa, state.scopa)
            assertEquals(first.briscola, state.briscola)
            assertEquals(first.status, state.status)
        }
    }

    @Test
    fun scopaFullMatchOverMesh() = meshTest(timeoutMs = 300_000) {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val aliceGames = games(alice)
        val bobGames = games(bob)

        val invite = aliceGames.invite(GameKind.SCOPA, alice.directConversation(with = bob))
        assertEquals(0, invite.mySeat)
        assertEquals(listOf("Alice", "Bob"), invite.seatNames)
        assertTrue(eventually { runCatching { bobGames.session(invite.gameID) }.isSuccess })
        val bobInvite = bobGames.session(invite.gameID)
        assertEquals(1, bobInvite.mySeat)
        assertEquals(GameStatus.invited, bobInvite.status)
        assertEquals(listOf("Alice", "Bob"), bobInvite.seatNames)
        try {
            bobGames.playCard(invite.gameID, card = 0)
            fail("atteso notYourTurn")
        } catch (_: GameService.Failure.notYourTurn) {
        }

        // ACCEPT (seq 1) → Alice, mazziere della prima mano, distribuisce da sola (DEAL seq 2).
        bobGames.accept(invite.gameID)
        assertTrue(eventually { bobGames.session(invite.gameID).scopa != null && bobGames.session(invite.gameID).myTurn })
        val dealt = aliceGames.session(invite.gameID)
        assertEquals(2L, dealt.sequence)
        assertEquals(0, dealt.scopa!!.dealer)
        assertEquals(1, dealt.turnSeat)
        assertFalse(dealt.myTurn)
        assertSameTable(listOf(dealt, bobGames.session(invite.gameID)))

        // Mossa illegale (presa non valida) rifiutata in locale.
        val bobState = bobGames.session(invite.gameID)
        val card = bobState.scopa!!.hands[1].first()
        val illegal = bobState.scopa!!.table.filter { it !in (bobState.scopa!!.captureOptions(card).firstOrNull() ?: emptyList()) }
        if (illegal.isNotEmpty()) {
            try {
                bobGames.playCard(invite.gameID, card = card, captured = listOf(illegal.first()))
                fail("attesa presa non valida")
            } catch (_: GameService.Failure.invalidMove) {
            }
        }

        val moves = botPlay(listOf(aliceGames, bobGames), emptyList(), invite.gameID)
        val final = listOf(aliceGames.session(invite.gameID), bobGames.session(invite.gameID))
        assertSameTable(final)
        val scopa = final[0].scopa!!
        assertTrue(scopa.isMatchOver)
        assertTrue(scopa.scores.max() >= 11 && scopa.scores[0] != scopa.scores[1])
        assertEquals(scopa.handNumber * 36, moves)
        // Il mazziere ruota: con piu' mani l'ultima e' stata distribuita da (handNumber - 1) % 2.
        assertEquals((scopa.handNumber - 1) % 2, scopa.dealer)
        val aliceWon = scopa.winnerSide == 0
        assertEquals(if (aliceWon) GameOutcome.iWon else GameOutcome.iLost, final[0].outcome)
        assertEquals(if (aliceWon) GameOutcome.iLost else GameOutcome.iWon, final[1].outcome)
        assertTrue(final.all { !it.myTurn })
    }

    @Test
    fun briscolaFullHandOverMesh() = meshTest(timeoutMs = 120_000) {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val aliceGames = games(alice)
        val bobGames = games(bob)

        val invite = aliceGames.invite(GameKind.BRISCOLA, alice.directConversation(with = bob))
        assertTrue(eventually { runCatching { bobGames.session(invite.gameID) }.isSuccess })
        bobGames.accept(invite.gameID)
        assertTrue(eventually { bobGames.session(invite.gameID).briscola != null })
        val start = bobGames.session(invite.gameID)
        assertEquals(34, start.briscola!!.stock.size)
        assertEquals(start.briscola!!.briscolaCard, start.briscola!!.stock.last())

        val moves = botPlay(listOf(aliceGames, bobGames), emptyList(), invite.gameID)
        assertEquals(40, moves)
        val final = listOf(aliceGames.session(invite.gameID), bobGames.session(invite.gameID))
        assertSameTable(final)
        val briscola = final[0].briscola!!
        assertEquals(120, briscola.points.sum())
        when (briscola.winnerSeat) {
            0 -> assertEquals(listOf(GameOutcome.iWon, GameOutcome.iLost), final.map { it.outcome })
            1 -> assertEquals(listOf(GameOutcome.iLost, GameOutcome.iWon), final.map { it.outcome })
            null -> assertEquals(listOf(GameOutcome.draw, GameOutcome.draw), final.map { it.outcome })
        }
        // Nessun nuovo DEAL dopo la fine.
        delay(200)
        assertEquals(final[0].sequence, aliceGames.session(invite.gameID).sequence)
    }

    @Test
    fun resignEndsCardMatchForBoth() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val aliceGames = games(alice)
        val bobGames = games(bob)
        val invite = aliceGames.invite(GameKind.BRISCOLA, alice.directConversation(with = bob))
        assertTrue(eventually { runCatching { bobGames.session(invite.gameID) }.isSuccess })
        bobGames.accept(invite.gameID)
        assertTrue(eventually { aliceGames.session(invite.gameID).briscola != null })
        val resigned = aliceGames.resign(invite.gameID)
        assertEquals(GameOutcome.iLost, resigned.outcome)
        assertEquals(0, resigned.resignedSeat)
        assertTrue(eventually { bobGames.session(invite.gameID).outcome == GameOutcome.iWon })
        assertEquals(0, bobGames.session(invite.gameID).resignedSeat)
        assertEquals(GameStatus.finished, bobGames.session(invite.gameID).status)
    }

    /** Gruppo con `owner` che conosce tutti; gli altri conoscono solo `owner` (come dopo il QR di gruppo). */
    private suspend fun makeGroup(owner: TestNode, others: List<TestNode>): Map<TestNode, ConversationRecord> {
        val groupID = UUID.randomUUID()
        val key = CryptoEngine.randomGroupSecretKey()
        suspend fun peers(of: TestNode, nodes: List<TestNode>): List<PeerRecord> =
            nodes.map { requireNotNull(of.database.peers.fetch(nodeID = it.nodeID)) }
        val result = LinkedHashMap<TestNode, ConversationRecord>()
        result[owner] = owner.database.conversations.createGroup(id = groupID, title = "Volo", secretKey = key, members = peers(owner, others))
        for (node in others) {
            result[node] = node.database.conversations.createGroup(id = groupID, title = "Volo", secretKey = key, members = peers(node, listOf(owner)))
        }
        return result
    }

    @Test
    fun scoponeWithFourPlayersAndSpectator() = meshTest(timeoutMs = 300_000) {
        val alice = node("Alice")
        val bob = node("Bob")
        val carol = node("Carol")
        val dave = node("Dave")
        val eve = node("Eve")
        val others = listOf(bob, carol, dave, eve)
        for (node in others) {
            TestNode.pair(alice, node)
            FakeMeshNetwork.connect(alice.transport, node.transport)
        }
        val groups = makeGroup(alice, others)
        val aliceGames = games(alice)
        val bobGames = games(bob)
        val carolGames = games(carol)
        val daveGames = games(dave)
        val eveGames = games(eve)

        // Validazione dei giocatori: esattamente 3 membri distinti, solo nel gruppo.
        val group = groups.getValue(alice)
        for (bad in listOf(listOf(bob.nodeID, carol.nodeID), listOf(bob.nodeID, bob.nodeID, carol.nodeID), listOf(alice.nodeID, bob.nodeID, carol.nodeID))) {
            try {
                aliceGames.invite(GameKind.SCOPONE, group, players = bad)
                fail("attesi giocatori non validi")
            } catch (_: GameService.Failure.invalidPlayers) {
            }
        }
        try {
            aliceGames.invite(GameKind.SCOPONE, alice.directConversation(with = bob), players = listOf(bob.nodeID, carol.nodeID, dave.nodeID))
            fail("Scopone solo nei gruppi")
        } catch (_: GameService.Failure.groupConversationOnly) {
        }
        try {
            aliceGames.invite(GameKind.SCOPA, group)
            fail("Scopa solo 1-a-1")
        } catch (_: GameService.Failure.directConversationOnly) {
        }

        // Posti: 0 Alice, 1 Bob, 2 Carol (compagna di Alice), 3 Dave. Eve guarda.
        val invite = aliceGames.invite(GameKind.SCOPONE, group, players = listOf(bob.nodeID, carol.nodeID, dave.nodeID))
        assertEquals(listOf("Alice", "Bob", "Carol", "Dave"), invite.seatNames)
        val all = listOf(aliceGames, bobGames, carolGames, daveGames, eveGames)
        assertTrue(eventually { all.all { runCatching { it.session(invite.gameID) }.isSuccess } })
        assertEquals(1, bobGames.session(invite.gameID).mySeat)
        assertEquals(2, carolGames.session(invite.gameID).mySeat)
        assertEquals(3, daveGames.session(invite.gameID).mySeat)
        val spectator = eveGames.session(invite.gameID)
        assertNull(spectator.mySeat)
        assertTrue(spectator.isSpectator)
        try {
            eveGames.accept(invite.gameID)
            fail("lo spettatore non accetta")
        } catch (_: GameService.Failure.invalidState) {
        }

        // Accettano tutti (sequence 0); il DEAL parte solo dopo il terzo.
        bobGames.accept(invite.gameID)
        carolGames.accept(invite.gameID)
        assertTrue(eventually { aliceGames.session(invite.gameID).acceptedSeats == listOf(1, 2) })
        assertNull(aliceGames.session(invite.gameID).scopa)
        assertEquals(0L, aliceGames.session(invite.gameID).sequence)
        daveGames.accept(invite.gameID)
        assertTrue(eventually { all.all { it.session(invite.gameID).scopa != null } })
        val dealt = all.map { it.session(invite.gameID) }
        assertSameTable(dealt)
        assertEquals(1L, dealt[0].sequence)
        assertEquals(listOf(10, 10, 10, 10), dealt[0].scopa!!.hands.map { it.size })
        assertTrue(bobGames.session(invite.gameID).myTurn)
        assertFalse(eveGames.session(invite.gameID).myTurn)

        // Una mano intera (40 giocate), poi il nuovo mazziere (Bob, posto 1) distribuisce da solo.
        val players = listOf(aliceGames, bobGames, carolGames, daveGames)
        val moves = botPlay(players, listOf(eveGames), invite.gameID, maxMoves = 40)
        assertEquals(40, moves)
        assertTrue(eventually { all.all { val s = it.session(invite.gameID); s.scopa!!.handNumber == 2 || s.status == GameStatus.finished } })
        val second = all.map { it.session(invite.gameID) }
        assertSameTable(second)
        val scopone = second[0].scopa!!
        assertNotNull(scopone.lastHandScore)
        assertEquals(40, scopone.lastHandScore!!.cards.sum())
        if (second[0].status != GameStatus.finished) {
            assertEquals(1, scopone.dealer)
            assertEquals(2, scopone.turn)
            assertTrue(carolGames.session(invite.gameID).myTurn)
        }
        assertEquals(scopone.scores, eveGames.session(invite.gameID).scopa!!.scores)

        // Carol (compagna di Alice) abbandona: la coppia {0, 2} perde per tutti, lo spettatore vede la fine.
        if (second[0].status != GameStatus.finished) {
            carolGames.resign(invite.gameID)
            assertTrue(eventually { all.all { it.session(invite.gameID).status == GameStatus.finished } })
            assertEquals(GameOutcome.iLost, aliceGames.session(invite.gameID).outcome)
            assertEquals(GameOutcome.iWon, bobGames.session(invite.gameID).outcome)
            assertEquals(GameOutcome.iWon, daveGames.session(invite.gameID).outcome)
            val watched = eveGames.session(invite.gameID)
            assertEquals(GameOutcome.ongoing, watched.outcome)
            assertEquals(1, watched.cardWinnerSide)
            assertEquals(2, watched.resignedSeat)
        }
    }

    @Test
    fun scoponeOutOfOrderMovesAreBuffered() = meshTest(timeoutMs = 120_000) {
        val alice = node("Alice")
        val bob = node("Bob")
        val carol = node("Carol")
        val dave = node("Dave")
        val eve = node("Eve")
        val others = listOf(bob, carol, dave, eve)
        for (node in others) {
            TestNode.pair(alice, node)
            FakeMeshNetwork.connect(alice.transport, node.transport)
        }
        val groups = makeGroup(alice, others)
        val aliceGames = games(alice)
        val bobGames = games(bob)
        val carolGames = games(carol)
        val daveGames = games(dave)
        val eveGames = games(eve)

        val invite = aliceGames.invite(GameKind.SCOPONE, groups.getValue(alice), players = listOf(bob.nodeID, carol.nodeID, dave.nodeID))
        val all = listOf(aliceGames, bobGames, carolGames, daveGames, eveGames)
        assertTrue(eventually { all.all { runCatching { it.session(invite.gameID) }.isSuccess } })
        bobGames.accept(invite.gameID)
        carolGames.accept(invite.gameID)
        daveGames.accept(invite.gameID)
        assertTrue(eventually { all.all { it.session(invite.gameID).scopa != null } })

        // Bob e Carol "spengono l'app": le loro mosse le costruiamo a mano e le inviamo in ordine inverso.
        bobGames.stop()
        carolGames.stop()
        val start = aliceGames.session(invite.gameID)
        assertEquals(1, start.turnSeat)
        val simulated = start.deepCopy()
        fun nextMove(state: GameSessionState, node: TestNode): BufferedCardMove {
            val seat = state.turnSeat!!
            val card = state.scopa!!.hands[seat].first()
            val captured = state.scopa!!.captureOptions(card).firstOrNull() ?: emptyList()
            return BufferedCardMove(state.sequence + 1, GameAction.MOVE, node.nodeID, card, captured = captured)
        }
        val bobMove = nextMove(simulated, bob)
        simulated.applyCardMove(bobMove)
        val carolMove = nextMove(simulated, carol)
        simulated.applyCardMove(carolMove)

        suspend fun send(node: TestNode, move: BufferedCardMove) {
            val wire = GameMove.newBuilder().setGameId(invite.gameID).setKind(GameKind.SCOPONE).setAction(GameAction.MOVE)
                .setSequence(move.sequence.toInt()).setCell(move.cell)
                .setCaptured(NeapolitanCards.bytes(move.captured).toByteString()).build()
            node.router.sendPacket(
                type = PacketType.GAME_MOVE,
                payload = InnerPayloadFactory.extra(MeshWire.serialize(wire), node.identity, node.nickname),
                conversation = groups.getValue(node),
            )
        }
        val watchers = listOf(aliceGames, daveGames, eveGames)
        send(carol, carolMove)
        assertTrue(eventually { watchers.all { it.session(invite.gameID).pendingMoves?.size == 1 } })
        assertTrue(watchers.all { it.session(invite.gameID).sequence == 1L })

        // Mossa fuori turno (Dave, posto 3, con la sequence giusta): ignorata.
        val daveState = daveGames.session(invite.gameID)
        val stranger = GameMove.newBuilder().setGameId(invite.gameID).setKind(GameKind.SCOPONE).setAction(GameAction.MOVE)
            .setSequence(2).setCell(daveState.scopa!!.hands[3].first()).build()
        dave.router.sendPacket(
            type = PacketType.GAME_MOVE,
            payload = InnerPayloadFactory.extra(MeshWire.serialize(stranger), dave.identity, dave.nickname),
            conversation = groups.getValue(dave),
        )
        delay(300)
        assertTrue(watchers.filter { it !== daveGames }.all { it.session(invite.gameID).sequence == 1L })

        send(bob, bobMove)
        assertTrue(eventually { watchers.all { it.session(invite.gameID).sequence == 3L } })
        for (service in watchers) {
            val state = service.session(invite.gameID)
            assertNull(state.pendingMoves)
            assertEquals(simulated.scopa, state.scopa)
            assertEquals(3, state.turnSeat)
        }
        assertTrue(daveGames.session(invite.gameID).myTurn)
    }

    @Test
    fun oldKindsStillDirectOnlyAndCardMovesFromStrangersIgnored() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        val mallory = node("Mallory")
        TestNode.pair(alice, bob)
        TestNode.pair(alice, mallory)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        FakeMeshNetwork.connect(alice.transport, mallory.transport)
        val aliceGames = games(alice)
        val bobGames = games(bob)
        val invite = aliceGames.invite(GameKind.SCOPA, alice.directConversation(with = bob))
        assertTrue(eventually { runCatching { bobGames.session(invite.gameID) }.isSuccess })
        bobGames.accept(invite.gameID)
        assertTrue(eventually { bobGames.session(invite.gameID).myTurn })
        val before = aliceGames.session(invite.gameID)

        // Mallory (non al tavolo) prova a giocare per Bob in chat diretta con Alice: ignorato.
        val card = before.scopa!!.hands[1].first()
        val forged = GameMove.newBuilder().setGameId(invite.gameID).setKind(GameKind.SCOPA).setAction(GameAction.MOVE)
            .setSequence(3).setCell(card)
            .setCaptured(NeapolitanCards.bytes(before.scopa!!.captureOptions(card).firstOrNull() ?: emptyList()).toByteString())
            .build()
        mallory.router.sendPacket(
            type = PacketType.GAME_MOVE,
            payload = InnerPayloadFactory.extra(MeshWire.serialize(forged), mallory.identity, mallory.nickname),
            conversation = mallory.directConversation(with = alice),
        )
        delay(300)
        assertEquals(before.sequence, aliceGames.session(invite.gameID).sequence)
        assertEquals(before.scopa, aliceGames.session(invite.gameID).scopa)
    }

    @Test
    fun cardStateJSONRoundTripMatchesSwiftKeys() {
        val state = GameSessionState(
            gameID = "00112233445566778899aabbccddeeff", kind = GameKind.SCOPONE.number, conversationID = "g",
            opponentNodeID = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), iAmInviter = true, status = GameStatus.active,
            sequence = 3, updatedAtMs = 1_700_000_000_000L,
        )
        state.seats = (0 until 4).map { seat -> ByteArray(8) { seat.toByte() } }
        state.seatNames = listOf("Alice", "Bob", "Carol", "Dave")
        state.mySeat = 0
        state.acceptedSeats = listOf(1, 2, 3)
        val game = ScopaGame(playerCount = 4, dealer = 0, deck = identityDeck)
        game.play(1, 0, emptyList())
        state.scopa = game
        state.pendingMoves = listOf(BufferedCardMove(5, GameAction.MOVE, state.seats!![3], 2, captured = listOf(1)))
        val json = String(state.encoded())
        for (key in listOf("\"seats\":[\"AAAAAAAAAAA=\"", "\"seatNames\"", "\"mySeat\":0", "\"acceptedSeats\":[1,2,3]",
            "\"scopa\":{", "\"dealtCount\":40", "\"phase\":\"playing\"", "\"lastPlay\":{", "\"pendingMoves\":[{",
            "\"senderNodeID\":\"AwMDAwMDAwM=\"")) {
            assertTrue("manca $key in $json", json.contains(key))
        }
        assertFalse(json.contains("lastCaptureSeat"))
        assertFalse(json.contains("briscola"))
        assertEquals(state, GameSessionState.decode(state.encoded()))

        val briscolaState = state.deepCopy().also {
            it.kind = GameKind.BRISCOLA.number
            it.scopa = null
            it.briscola = BriscolaGame(dealer = 0, deck = identityDeck).also { game -> game.play(1, 0); game.play(0, 1) }
            it.resignedSeat = 1
        }
        assertEquals(briscolaState, GameSessionState.decode(briscolaState.encoded()))

        // JSON prodotto dal `Codable` Swift (chiavi = nomi delle proprieta', opzionali omessi).
        val swiftJSON = """{"gameID":"ab","kind":5,"conversationID":"c","opponentNodeID":"AQI=","iAmInviter":false,""" +
            """"status":"active","sequence":4,"myTurn":true,"outcome":"ongoing","seats":["AQI=","AwQ="],""" +
            """"seatNames":["Alice","Bob"],"mySeat":1,"briscola":{"dealer":0,"hands":[[1,3],[2,4,7]],""" +
            """"stock":[8,9,6],"briscolaCard":6,"trick":[5],"leader":0,"turn":1,"points":[0,11],""" +
            """"won":[[],[0,1]],"lastTrick":{"leader":1,"cards":[0,1],"winner":1,"points":11},"phase":"playing"},""" +
            """"pendingMoves":[{"sequence":6,"action":3,"senderNodeID":"AQI=","cell":3,"deck":[],"captured":[]}],""" +
            """"updatedAtMs":5}"""
        val decoded = GameSessionState.decode(swiftJSON.toByteArray())
        assertEquals(1, decoded.mySeat)
        assertEquals(1, decoded.turnSeat)
        assertEquals(listOf(5), decoded.briscola!!.trick)
        assertEquals(11, decoded.briscola!!.lastTrick!!.points)
        assertEquals(6L, decoded.pendingMoves!!.single().sequence)
        assertEquals(GameAction.MOVE, decoded.pendingMoves!!.single().gameAction)
        assertNull(decoded.briscola!!.winnerSeat)
    }
}
