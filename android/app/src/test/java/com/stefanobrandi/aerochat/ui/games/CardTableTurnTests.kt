package com.stefanobrandi.aerochat.ui.games

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.stefanobrandi.aerochat.proto.GameKind
import com.stefanobrandi.aerochat.core.games.GameSessionState
import com.stefanobrandi.aerochat.core.games.GameStatus
import com.stefanobrandi.aerochat.core.games.ScopaGame
import com.stefanobrandi.aerochat.ui.components.GameBubbleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tavolo aperto durante il turno dell'avversario: quando arriva il nostro turno la mano deve rispondere
 * subito ai tocchi, senza chiudere e riaprire il tavolo (prima i gestori restavano quelli della prima
 * composizione, con "non e' il tuo turno").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardTableTurnTests {

    @get:Rule
    val compose = createComposeRule()

    private fun scopaState(myTurn: Boolean, sequence: Long) = GameSessionState(
        gameID = "00112233445566778899aabbccddeeff", kind = GameKind.SCOPA.number, conversationID = "c",
        opponentNodeID = ByteArray(8) { 1 }, iAmInviter = true, status = GameStatus.active,
        sequence = sequence, myTurn = myTurn, updatedAtMs = 1_700_000_000_000L,
    ).also {
        it.seats = listOf(ByteArray(8), ByteArray(8) { 1 })
        it.seatNames = listOf("Io", "Bob")
        it.mySeat = 0
        it.scopa = ScopaGame(playerCount = 2, dealer = 1, deck = (0 until 40).toList())
    }

    @Test
    fun turnArrivingWhileTableIsOpenAcceptsTaps() {
        val state = mutableStateOf(scopaState(myTurn = false, sequence = 1))
        val actions = mutableListOf<GameBubbleAction>()
        compose.setContent {
            CardTableView(state = state.value, onAction = { actions += it }, onDismiss = {})
        }
        compose.waitForIdle()

        // Turno dell'avversario: i tocchi non giocano nulla.
        compose.onAllNodes(isSelectable()).onFirst().performClick()
        compose.waitForIdle()
        assertTrue(actions.isEmpty())

        // Arriva la mossa dell'avversario: ora tocca a noi, con il tavolo ancora aperto.
        state.value = scopaState(myTurn = true, sequence = 2)
        compose.waitForIdle()

        // Primo tocco seleziona, secondo tocco gioca.
        compose.onAllNodes(isSelectable()).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodes(isSelectable()).onFirst().performClick()
        compose.waitForIdle()

        assertEquals(1, actions.size)
        assertTrue(actions.single() is GameBubbleAction.PlayCard)
    }
}
