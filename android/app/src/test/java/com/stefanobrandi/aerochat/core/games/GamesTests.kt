package com.stefanobrandi.aerochat.core.games

import com.stefanobrandi.aerochat.core.mesh.FakeMeshNetwork
import com.stefanobrandi.aerochat.core.mesh.MeshTestCase
import com.stefanobrandi.aerochat.core.mesh.TestNode
import com.stefanobrandi.aerochat.core.mesh.eventually
import com.stefanobrandi.aerochat.core.protocol.MeshWire
import com.stefanobrandi.aerochat.core.protocol.hexToByteArray
import com.stefanobrandi.aerochat.data.records.MessageContentType
import com.stefanobrandi.aerochat.proto.GameAction
import com.stefanobrandi.aerochat.proto.GameKind
import com.stefanobrandi.aerochat.proto.GameMove
import com.stefanobrandi.aerochat.proto.PacketType
import com.stefanobrandi.aerochat.proto.ShotReport
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `GamesTests.swift`: Tris, Battaglia navale, GameService sulla mesh. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GamesTests : MeshTestCase() {

    private val services = ArrayList<GameService>()

    @After
    fun stopServices() = services.forEach { it.stop() }

    private suspend fun games(node: TestNode): GameService =
        GameService(router = node.router, database = node.database, identity = node.identity, nickname = node.nickname)
            .also { it.start(); services += it }

    @Test
    fun ticTacToeEngine() {
        val game = TicTacToe()
        assertThrows(TicTacToe.Failure.notYourTurn::class.java) { game.play(0, TicTacToe.Mark.o) }
        game.play(0, TicTacToe.Mark.x)
        assertThrows(TicTacToe.Failure.cellTaken::class.java) { game.play(0, TicTacToe.Mark.o) }
        assertThrows(TicTacToe.Failure.outOfBounds::class.java) { game.play(9, TicTacToe.Mark.o) }
        game.play(3, TicTacToe.Mark.o)
        game.play(1, TicTacToe.Mark.x)
        game.play(4, TicTacToe.Mark.o)
        game.play(2, TicTacToe.Mark.x)
        assertEquals(TicTacToe.Mark.x, game.winner)
        assertTrue(game.isFinished)
        assertThrows(TicTacToe.Failure.finished::class.java) { game.play(5, TicTacToe.Mark.o) }
        assertArrayEquals(byteArrayOf(1, 1, 1, 2, 2, 0, 0, 0, 0), game.snapshot)
        assertEquals(game, TicTacToe(snapshot = game.snapshot))
        assertNull("troppi O", TicTacToe(snapshot = byteArrayOf(2, 2, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(TicTacToe(snapshot = byteArrayOf(1, 0, 0)))
        assertNull(TicTacToe(snapshot = byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0, 0)))

        val draw = TicTacToe()
        val cells = listOf(0, 1, 2, 4, 3, 5, 7, 6, 8)
        val marks = listOf(TicTacToe.Mark.x, TicTacToe.Mark.o, TicTacToe.Mark.x, TicTacToe.Mark.o, TicTacToe.Mark.x,
            TicTacToe.Mark.o, TicTacToe.Mark.x, TicTacToe.Mark.o, TicTacToe.Mark.x)
        for ((cell, mark) in cells.zip(marks)) draw.play(cell, mark)
        assertTrue(draw.isDraw)
        assertNull(draw.winner)
    }

    @Test
    fun battleshipEngine() {
        val fleet = Battleship.randomFleet(using = Random(42))
        assertTrue(Battleship.isValidFleet(fleet))
        assertEquals(4, fleet.size)
        assertTrue(Battleship.isValidFleet(Battleship.randomFleet()))

        val board = Battleship(fleet)
        val ship = fleet[0]
        val outcomes = ship.cells.map { board.receiveShot(it) }
        assertTrue(outcomes.dropLast(1).all { it == Battleship.Outcome.hit })
        assertEquals(Battleship.Outcome.sunk, outcomes.last())
        assertEquals(3, board.myShipsRemaining)
        assertThrows(Battleship.Failure.alreadyShot::class.java) { board.receiveShot(ship.cells[0]) }
        assertThrows(Battleship.Failure.outOfBounds::class.java) { board.receiveShot(100) }

        val occupied = fleet.flatMap { it.cells }.toSet()
        val water = (0 until 100).first { !occupied.contains(it) }
        assertEquals(Battleship.Outcome.miss, board.receiveShot(water))

        for (other in fleet.drop(1)) for (cell in other.cells) board.receiveShot(cell)
        assertTrue(board.allMyShipsSunk)

        board.recordMyShot(5, Battleship.Outcome.sunk)
        assertEquals(3.toByte(), board.shotsSnapshot[5])
        assertEquals(100, board.shotsSnapshot.size)
        assertTrue(!board.canShoot(5) && board.canShoot(6))
    }

    @Test
    fun sessionStateJSONRoundTripMatchesSwiftKeys() {
        val board = Battleship(Battleship.randomFleet(using = Random(7)))
        board.receiveShot(board.myShips[0].cells[0])
        board.recordMyShot(42, Battleship.Outcome.hit)
        val state = GameSessionState(
            gameID = "00112233445566778899aabbccddeeff", kind = GameKind.BATTLESHIP.number, conversationID = "conv",
            opponentNodeID = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), iAmInviter = true, status = GameStatus.active,
            sequence = 4_000_000_000L, myTurn = true, battleship = board, pendingShot = 17, updatedAtMs = 1_700_000_000_000L,
        )
        val json = String(state.encoded())
        assertTrue(json.contains("\"opponentNodeID\":\"AQIDBAUGBwg=\""))
        assertTrue(json.contains("\"myShots\":{\"42\":2}"))
        assertEquals(state, GameSessionState.decode(state.encoded()))

        val swiftJSON = """{"gameID":"ab","kind":1,"conversationID":"c","opponentNodeID":"AQI=","iAmInviter":false,""" +
            """"status":"finished","sequence":7,"myTurn":false,"outcome":"iLost","ticTacToe":{"board":[1,1,1,2,2,0,0,0,0],"next":2},""" +
            """"updatedAtMs":5}"""
        val decoded = GameSessionState.decode(swiftJSON.toByteArray())
        assertEquals(GameOutcome.iLost, decoded.outcome)
        assertEquals(GameKind.TIC_TAC_TOE, decoded.gameKind)
        assertEquals(TicTacToe.Mark.x, decoded.ticTacToe?.winner)
        assertEquals(TicTacToe.Mark.o, decoded.myMark)
        assertNull(decoded.pendingShot)
    }

    @Test
    fun ticTacToeOverMesh() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val aliceGames = games(alice)
        val bobGames = games(bob)

        val conversation = alice.directConversation(with = bob)
        val invite = aliceGames.invite(GameKind.TIC_TAC_TOE, conversation)
        assertTrue(invite.status == GameStatus.invited && invite.iAmInviter)

        // Wire: l'invito e' un GAME_MOVE con game_id = hex del msg_id dell'envelope.
        assertTrue(eventually { runCatching { bobGames.session(invite.gameID) }.isSuccess })
        val bobView = bobGames.session(invite.gameID)
        assertTrue(bobView.status == GameStatus.invited && !bobView.iAmInviter)
        assertEquals(MessageContentType.game, bob.database.messages.fetch(msgID = invite.gameID.hexToByteArray()!!)?.contentType)

        bobGames.accept(invite.gameID)
        assertTrue(eventually { aliceGames.session(invite.gameID).myTurn })

        // X: 0,1,2  O: 3,4
        aliceGames.play(invite.gameID, cell = 0)
        assertTrue(eventually { bobGames.session(invite.gameID).myTurn })
        try {
            aliceGames.play(invite.gameID, cell = 1)
            fail("atteso notYourTurn")
        } catch (_: GameService.Failure.notYourTurn) {
        }
        bobGames.play(invite.gameID, cell = 3)
        assertTrue(eventually { aliceGames.session(invite.gameID).myTurn })
        aliceGames.play(invite.gameID, cell = 1)
        assertTrue(eventually { bobGames.session(invite.gameID).myTurn })
        bobGames.play(invite.gameID, cell = 4)
        assertTrue(eventually { aliceGames.session(invite.gameID).myTurn })
        val final = aliceGames.play(invite.gameID, cell = 2)
        assertTrue(final.outcome == GameOutcome.iWon && final.status == GameStatus.finished)

        assertTrue(eventually { bobGames.session(invite.gameID).outcome == GameOutcome.iLost })
        val bobFinal = bobGames.session(invite.gameID)
        assertArrayEquals(byteArrayOf(1, 1, 1, 2, 2, 0, 0, 0, 0), bobFinal.ticTacToe?.snapshot)
        assertEquals(GameStatus.finished, bobFinal.status)
    }

    @Test
    fun battleshipOverMesh() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val aliceGames = games(alice)
        val bobGames = games(bob)

        val conversation = alice.directConversation(with = bob)
        val invite = aliceGames.invite(GameKind.BATTLESHIP, conversation)
        assertTrue(eventually { runCatching { bobGames.session(invite.gameID) }.isSuccess })
        val accepted = bobGames.accept(invite.gameID)
        val bobShips = accepted.battleship!!.myShips
        assertTrue(Battleship.isValidFleet(bobShips))
        assertTrue(eventually { aliceGames.session(invite.gameID).myTurn })

        // Alice conosce (nel test) la flotta di Bob e affonda la prima nave; Bob risponde sparando in acqua
        // (celle libere della flotta di Alice, piazzata alla ricezione dell'ACCEPT).
        val target = bobShips[0]
        val aliceShips = aliceGames.session(invite.gameID).battleship!!.myShips
        val occupiedByAlice = aliceShips.flatMap { it.cells }.toSet()
        val bobWaterShots = (0 until 100).reversed().filter { !occupiedByAlice.contains(it) }.iterator()

        for ((index, cell) in target.cells.withIndex()) {
            val shot = aliceGames.play(invite.gameID, cell = cell)
            assertEquals(cell, shot.pendingShot)
            assertTrue(eventually { bobGames.session(invite.gameID).myTurn })
            assertTrue(eventually { aliceGames.session(invite.gameID).pendingShot == null })
            val aliceView = aliceGames.session(invite.gameID)
            val expected = if (index == target.cells.size - 1) Battleship.Outcome.sunk else Battleship.Outcome.hit
            assertEquals(expected, aliceView.battleship?.myShots?.get(cell))

            bobGames.play(invite.gameID, cell = bobWaterShots.next())
            assertTrue(eventually { bobGames.session(invite.gameID).pendingShot == null })
            assertTrue(eventually { aliceGames.session(invite.gameID).myTurn })
        }

        val aliceView = aliceGames.session(invite.gameID)
        assertEquals(1, aliceView.battleship?.enemyShipsSunk)
        val bobView = bobGames.session(invite.gameID)
        assertEquals(3, bobView.battleship?.myShipsRemaining)
        assertEquals(target.cells.size, bobView.battleship?.myShots?.size)
        assertTrue(bobView.battleship!!.myShots.values.all { it == Battleship.Outcome.miss })

        bobGames.resign(invite.gameID)
        assertTrue(eventually { aliceGames.session(invite.gameID).outcome == GameOutcome.iWon })
    }

    @Test
    fun movesFromStrangersOrWithHugeSequenceGapAreIgnored() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)
        val aliceGames = games(alice)
        val bobGames = games(bob)
        val conversation = alice.directConversation(with = bob)
        val invite = aliceGames.invite(GameKind.TIC_TAC_TOE, conversation)
        assertTrue(eventually { runCatching { bobGames.session(invite.gameID) }.isSuccess })
        bobGames.accept(invite.gameID)
        assertTrue(eventually { aliceGames.session(invite.gameID).myTurn })

        // Bob invia a mano una mossa con sequence troppo avanti: Alice la scarta.
        val bobConversation = bob.directConversation(with = alice)
        val move = GameMove.newBuilder().setGameId(invite.gameID).setKind(GameKind.TIC_TAC_TOE)
            .setAction(GameAction.RESIGN).setSequence(1 + 17).setResult(2).build()
        bob.router.sendPacket(
            type = PacketType.GAME_MOVE,
            payload = com.stefanobrandi.aerochat.core.protocol.InnerPayloadFactory.extra(MeshWire.serialize(move), bob.identity, "Bob"),
            conversation = bobConversation,
        )
        kotlinx.coroutines.delay(300)
        val state = aliceGames.session(invite.gameID)
        assertEquals(GameStatus.active, state.status)
        assertEquals(GameOutcome.ongoing, state.outcome)

        // Fuori turno o gioco di gruppo: errori espliciti.
        try {
            bobGames.play(invite.gameID, cell = 0)
            fail("atteso notYourTurn")
        } catch (_: GameService.Failure.notYourTurn) {
        }
        assertEquals(ShotReport.SUNK, GameService.shotReport(Battleship.Outcome.sunk))
        assertNull(GameService.outcome(ShotReport.SHOT_REPORT_UNSPECIFIED))
        assertEquals("🎮 Tris", GameService.title(GameKind.TIC_TAC_TOE))
        assertFalse(aliceGames.session(invite.gameID).iAmInviter.not())
        assertNotNull(aliceGames.session(invite.gameID).ticTacToe)
    }
}
