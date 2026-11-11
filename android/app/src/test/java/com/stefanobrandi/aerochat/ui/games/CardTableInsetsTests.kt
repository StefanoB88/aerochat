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
 * Pixel 8a con navigazione a gesti: mano e "Gioca" devono stare sopra la maniglia di sistema anche quando
 * gli inset letti dalla composizione arrivano a zero.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
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

    private fun scopaState() = GameSessionState(
        gameID = "00112233445566778899aabbccddeeff", kind = GameKind.SCOPA.number, conversationID = "c",
        opponentNodeID = ByteArray(8) { 1 }, iAmInviter = true, status = GameStatus.active,
        sequence = 1, myTurn = true, updatedAtMs = 1_700_000_000_000L,
    ).also {
        it.seats = listOf(ByteArray(8), ByteArray(8) { 1 })
        it.seatNames = listOf("Io", "Bob")
        it.mySeat = 0
        it.scopa = ScopaGame(playerCount = 2, dealer = 1, deck = (0 until 40).toList())
    }

    @Test
    fun handAndPlayButtonStayAboveBottomInset() {
        val bottomInset = 48.dp
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                CardTableView(
                    state = scopaState(), onAction = {}, onDismiss = {},
                    contentPadding = tableContentPadding(TableInsets(bottom = bottomInset)),
                )
            }
        }
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val limit = compose.onRoot().getUnclippedBoundsInRoot().bottom - bottomInset
        val play = compose.onNodeWithText(context.getString(R.string.games_play)).getUnclippedBoundsInRoot()
        assertTrue("Gioca sopra l'inset: ${play.bottom} <= $limit", play.bottom <= limit)
        val cards = compose.onAllNodes(isSelectable()).fetchSemanticsNodes()
        assertTrue(cards.isNotEmpty())
        val density = compose.density
        for (card in cards) {
            val bottom = with(density) { card.boundsInRoot.bottom.toDp() }
            assertTrue("carta sopra l'inset: $bottom <= $limit", bottom <= limit + 0.5.dp)
        }
        // Schermo di prova alto come un Pixel 8a (al netto delle barre).
        assertTrue(limit > 700.dp)
    }
}
