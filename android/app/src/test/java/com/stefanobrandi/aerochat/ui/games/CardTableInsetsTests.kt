package com.stefanobrandi.aerochat.ui.games

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.test.core.app.ApplicationProvider
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.games.GameSessionState
import com.stefanobrandi.aerochat.core.games.GameStatus
import com.stefanobrandi.aerochat.core.games.ScopaGame
import com.stefanobrandi.aerochat.proto.GameKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pixel 8a (412x915 dp) con navigazione a gesti: mano e "Gioca" devono stare sopra la maniglia di sistema
 * (almeno 48 dp) anche quando gli inset letti dalla composizione arrivano a zero, e non devono spostarsi
 * con il turno, il messaggio di stato o la scelta della presa.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-420dpi")
class CardTableInsetsTests {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun paddingIsTheMaxOfKnownInsetsWithMinimumBottom() {
        // Tutto a zero (inset consumati / finestra del Dialog): resta il minimo in basso.
        val none = tableContentPadding(TableInsets(), TableInsets())
        assertEquals(TableMinBottomInset, none.calculateBottomPadding())
        assertEquals(0.dp, none.calculateTopPadding())

        // Activity: barra di stato 24, maniglia 48; Dialog: solo la barra di navigazione (24).
        val root = TableInsets(top = 24.dp, bottom = 48.dp)
        val dialog = TableInsets(PaddingValues(start = 0.dp, top = 0.dp, end = 30.dp, bottom = 24.dp), LayoutDirection.Ltr)
        val merged = tableContentPadding(root, dialog)
        assertEquals(24.dp, merged.calculateTopPadding())
        assertEquals(48.dp, merged.calculateBottomPadding())
        assertEquals(30.dp, merged.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(30.dp, merged.calculateLeftPadding(LayoutDirection.Rtl))
    }

    /**
     * Scopa con mazziere 1: al posto 0 vanno deck[0], deck[2], deck[4], al posto 1 deck[1], deck[3],
     * deck[5], in tavola deck[6..9]. Con [captureDeck] il 5 di denari (4) del posto 0 ha due prese
     * possibili in tavola (asso + 4, 2 + 3).
     */
    private fun scopaState(myTurn: Boolean = true, sequence: Long = 1, captureDeck: Boolean = false): GameSessionState {
        val deck = if (captureDeck) {
            val head = listOf(4, 10, 15, 11, 16, 12, 0, 1, 2, 3)
            head + (0 until 40).filter { it !in head }
        } else {
            (0 until 40).toList()
        }
        return GameSessionState(
            gameID = "00112233445566778899aabbccddeeff", kind = GameKind.SCOPA.number, conversationID = "c",
            opponentNodeID = ByteArray(8) { 1 }, iAmInviter = true, status = GameStatus.active,
            sequence = sequence, myTurn = myTurn, updatedAtMs = 1_700_000_000_000L,
        ).also {
            it.seats = listOf(ByteArray(8), ByteArray(8) { 1 })
            it.seatNames = listOf("Io", "Bob")
            it.mySeat = 0
            it.scopa = ScopaGame(playerCount = 2, dealer = 1, deck = deck)
        }
    }

    @Test
    fun handAndPlayButtonStayAboveBottomInset() {
        // Navigazione a gesti del Pixel 8a: barra di navigazione ~24 dp, il tavolo tiene almeno 48 dp.
        val navigationBar = 24.dp
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                CardTableView(
                    state = scopaState(captureDeck = true), onAction = {}, onDismiss = {},
                    contentPadding = tableContentPadding(TableInsets(top = 24.dp, bottom = navigationBar)),
                )
            }
        }
        compose.waitForIdle()
        val rootBottom = compose.onRoot().getUnclippedBoundsInRoot().bottom
        val limit = rootBottom - maxOf(navigationBar, TableMinBottomInset)
        assertEquals(48.dp, TableMinBottomInset)

        fun assertAboveInset() {
            val play = compose.onNodeWithTag(PlayButtonTag).getUnclippedBoundsInRoot()
            assertTrue("Gioca sopra l'inset: ${play.bottom} <= $limit", play.bottom <= limit)
            val hand = compose.onNodeWithTag(HandTag).getUnclippedBoundsInRoot()
            assertTrue("mano sopra l'inset: ${hand.bottom} <= $limit", hand.bottom <= limit)
            val cards = compose.onAllNodes(handCard).fetchSemanticsNodes()
            assertEquals(3, cards.size)
            val density = compose.density
            for (card in cards) {
                val top = with(density) { card.boundsInRoot.top.toDp() }
                val bottom = with(density) { card.boundsInRoot.bottom.toDp() }
                assertTrue("carta sopra l'inset: $bottom <= $limit", bottom <= limit + 0.5.dp)
                assertTrue("carta intera nella mano: $top >= ${hand.top}", top >= hand.top - 0.5.dp)
            }
        }
        assertAboveInset()
        // Anche con la scelta della presa aperta.
        compose.onAllNodes(handCard).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.games_choose_capture)).assertExists()
        assertAboveInset()
        // Schermo di prova alto come un Pixel 8a.
        assertTrue(rootBottom > 900.dp)
    }

    @Test
    fun layoutDoesNotMoveWithTurnStatusOrCaptureOptions() {
        val state = mutableStateOf(scopaState(myTurn = false, sequence = 1, captureDeck = true))
        val status = mutableStateOf<String?>(null)
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                CardTableView(
                    state = state.value, onAction = {}, onDismiss = {},
                    statusMessage = status.value,
                    contentPadding = tableContentPadding(TableInsets(top = 24.dp, bottom = 24.dp)),
                )
            }
        }
        compose.waitForIdle()

        fun snapshot(): List<Dp> {
            val play = compose.onNodeWithTag(PlayButtonTag).getUnclippedBoundsInRoot()
            val hand = compose.onNodeWithTag(HandTag).getUnclippedBoundsInRoot()
            val capture = compose.onNodeWithTag(CaptureSlotTag).getUnclippedBoundsInRoot()
            return listOf(play.top, play.bottom, hand.top, hand.bottom, capture.top, capture.bottom)
        }

        // Turno dell'avversario, nessun messaggio.
        val reference = snapshot()
        compose.onNodeWithTag(PlayButtonTag).assertExists().assertIsNotEnabled()

        // Messaggio di stato in sovrimpressione.
        status.value = "Non e' il tuo turno"
        compose.waitForIdle()
        compose.onNodeWithText("Non e' il tuo turno").assertExists()
        assertEquals(reference, snapshot())

        // Ora tocca a me (messaggio ancora visibile, poi sparito).
        state.value = scopaState(myTurn = true, sequence = 2, captureDeck = true)
        compose.waitForIdle()
        assertEquals(reference, snapshot())
        status.value = null
        compose.waitForIdle()
        assertEquals(reference, snapshot())

        // Carta scelta con due prese possibili: compare la scelta, niente si sposta.
        compose.onAllNodes(handCard).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.games_choose_capture)).assertExists()
        compose.onNodeWithTag(PlayButtonTag).assertIsEnabled()
        assertEquals(reference, snapshot())

        // Di nuovo il turno dell'avversario: la scelta sparisce, il pulsante resta dov'era.
        state.value = scopaState(myTurn = false, sequence = 3, captureDeck = true)
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.games_choose_capture)).assertDoesNotExist()
        compose.onNodeWithTag(PlayButtonTag).assertIsNotEnabled()
        assertEquals(reference, snapshot())
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Carte della propria mano (anche i chip della scelta della presa sono "selectable"). */
    private val handCard = isSelectable() and hasAnyAncestor(hasTestTag(HandTag))
}
