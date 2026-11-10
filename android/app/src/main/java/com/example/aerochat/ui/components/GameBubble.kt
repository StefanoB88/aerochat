package com.example.aerochat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.aerochat.R
import com.example.aerochat.core.games.Battleship
import com.example.aerochat.core.games.GameOutcome
import com.example.aerochat.core.games.GameSessionState
import com.example.aerochat.core.games.GameStatus
import com.example.aerochat.core.games.TicTacToe
import com.example.aerochat.core.games.isCardGame
import com.example.aerochat.core.games.isSpectator
import com.example.aerochat.ui.games.cardGameTitle
import com.example.aerochat.ui.games.cardScores
import com.example.aerochat.ui.games.cardStatusText
import com.example.aerochat.ui.games.playersLine
import com.example.aerochat.ui.games.resignText
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import com.example.aerochat.proto.GameKind
import com.example.aerochat.ui.AeroTheme

/** Azione dell'utente su una bolla di gioco (Swift `enum GameBubbleAction`). */
sealed interface GameBubbleAction {
    data object Accept : GameBubbleAction
    data class Play(val cell: Int) : GameBubbleAction
    data object Resign : GameBubbleAction
    /** Carte: gioca `card` prendendo `captured` dal tavolo (vuoto = nessuna presa). */
    data class PlayCard(val card: Int, val captured: List<Int>) : GameBubbleAction
    /** Carte: apre il tavolo a schermo intero. */
    data object OpenTable : GameBubbleAction
}

/** Titolo localizzato del gioco (Swift `GameService.title(for:)`, `String(localized:)`). */
@Composable
fun gameTitle(kind: GameKind): String = when (kind) {
    GameKind.TIC_TAC_TOE -> stringResource(R.string.chat_game_title_tictactoe)
    GameKind.BATTLESHIP -> stringResource(R.string.chat_game_title_battleship)
    GameKind.SCOPA, GameKind.SCOPONE, GameKind.BRISCOLA -> cardGameTitle(kind)
    else -> stringResource(R.string.chat_game_title_generic)
}

/**
 * Bolla interattiva di gioco: Tris o Battaglia navale giocabili dentro la chat; per le carte
 * (Scopa, Scopone, Briscola) una bolla compatta che apre il tavolo a schermo intero.
 */
@Composable
fun GameBubble(state: GameSessionState, onAction: (GameBubbleAction) -> Unit) {
    if (state.isCardGame) {
        CardGameBubbleContent(state, onAction)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                gameTitle(state.gameKind),
                style = AeroTheme.title(15f, FontWeight.SemiBold),
                color = AeroTheme.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            if (state.status == GameStatus.active) {
                HapticButton(onClick = { onAction(GameBubbleAction.Resign) }, destructive = true) {
                    Text(stringResource(R.string.chat_resign), style = AeroTheme.footnote, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(statusText(state), style = AeroTheme.caption, color = statusColor(state))
        when (state.gameKind) {
            GameKind.TIC_TAC_TOE -> TicTacToeBoard(state, onAction)
            GameKind.BATTLESHIP -> BattleshipBoards(state, onAction)
            else -> Unit
        }
        if (state.status == GameStatus.invited && !state.iAmInviter) {
            Button(
                onClick = { onAction(GameBubbleAction.Accept) },
                modifier = Modifier.fillMaxWidth().hapticOnTap(),
                colors = ButtonDefaults.buttonColors(containerColor = AeroTheme.accentEmerald, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    stringResource(R.string.chat_accept_challenge),
                    style = AeroTheme.body,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.defaultMinSize(minHeight = 28.dp).padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun statusText(state: GameSessionState): String = when (state.status) {
    GameStatus.invited ->
        if (state.iAmInviter) stringResource(R.string.chat_invite_sent_waiting) else stringResource(R.string.chat_invite_received)
    GameStatus.active -> when {
        state.gameKind == GameKind.BATTLESHIP && state.pendingShot != null -> stringResource(R.string.chat_shot_fired)
        state.myTurn -> stringResource(R.string.chat_your_turn)
        else -> stringResource(R.string.chat_opponent_turn)
    }
    GameStatus.finished -> when (state.outcome) {
        GameOutcome.iWon -> stringResource(R.string.chat_you_won)
        GameOutcome.iLost -> stringResource(R.string.chat_you_lost)
        GameOutcome.draw -> stringResource(R.string.chat_draw)
        GameOutcome.ongoing -> stringResource(R.string.chat_game_over)
    }
}

@Composable
private fun statusColor(state: GameSessionState): Color = when (state.outcome) {
    GameOutcome.iWon -> AeroTheme.accentEmerald
    GameOutcome.iLost -> Color.Red
    else -> if (state.myTurn) AeroTheme.accentCyan else AeroTheme.textSecondary
}

// MARK: Tris

@Composable
private fun TicTacToeBoard(state: GameSessionState, onAction: (GameBubbleAction) -> Unit) {
    val board = state.ticTacToe ?: TicTacToe()
    val marks = board.board
    val canPlay = state.status == GameStatus.active && state.myTurn
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (column in 0 until 3) {
                    val cell = row * 3 + column
                    val mark = marks[cell]
                    val symbol = when (mark) {
                        TicTacToe.Mark.x -> "X"
                        TicTacToe.Mark.o -> "O"
                        TicTacToe.Mark.empty -> ""
                    }
                    val description = if (mark == TicTacToe.Mark.empty) {
                        stringResource(R.string.chat_cell_empty, cell + 1)
                    } else {
                        stringResource(R.string.chat_cell_mark, cell + 1, symbol)
                    }
                    GridCellButton(
                        onClick = { onAction(GameBubbleAction.Play(cell)) },
                        enabled = canPlay && mark == TicTacToe.Mark.empty,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                    ) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .background(AeroTheme.onBackground.copy(alpha = 0.06f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                symbol,
                                style = AeroTheme.title(24f),
                                color = if (mark == state.myMark) AeroTheme.accentCyan else AeroTheme.accentEmerald,
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: Battaglia navale

@Composable
private fun BattleshipBoards(state: GameSessionState, onAction: (GameBubbleAction) -> Unit) {
    val board = state.battleship
    val canShoot = state.status == GameStatus.active && state.myTurn && state.pendingShot == null
    val myShots = remember(board) { board?.myShots ?: emptyMap() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.chat_enemy_waters), style = AeroTheme.caption, color = AeroTheme.textSecondary)
        BattleshipGrid(cellSize = 22.dp) { cell ->
            val outcome = myShots[cell]
            GridCellButton(
                onClick = { onAction(GameBubbleAction.Play(cell)) },
                enabled = canShoot && outcome == null,
            ) {
                // Celle gia' colpite: niente attenuazione da "disabilitato", il colore e' l'informazione.
                Box(Modifier.size(22.dp).background(colorForShot(outcome)))
            }
        }
        if (board != null) {
            val remaining = board.myShipsRemaining
            Text(
                // Android ignora quantity="zero": stringa dedicata, come la variante "zero" di iOS.
                if (remaining == 0) stringResource(R.string.chat_your_fleet_zero)
                else pluralStringResource(R.plurals.chat_your_fleet, remaining, remaining),
                style = AeroTheme.caption,
                color = AeroTheme.textSecondary,
            )
            val shipCells = remember(board) { board.myShips.flatMap { it.cells }.toSet() }
            val hits = remember(board) { board.myShips.flatMap { it.hits }.toSet() }
            val received = remember(board) { board.shotsReceived }
            BattleshipGrid(cellSize = 10.dp) { cell ->
                val color = when {
                    cell in hits -> Color.Red
                    cell in shipCells -> AeroTheme.accentEmerald.copy(alpha = 0.8f)
                    cell in received -> AeroTheme.onBackground.copy(alpha = 0.25f)
                    else -> AeroTheme.onBackground.copy(alpha = 0.06f)
                }
                Box(Modifier.size(10.dp).background(color))
            }
        }
    }
}

/**
 * Cella toccabile delle griglie di gioco (Swift `.buttonStyle(.hapticCompact)`): compressione e
 * vibrazione alla pressione, nessuna area minima e nessuna attenuazione da disabilitata (il colore
 * della cella e' gia' l'informazione).
 */
@Composable
private fun GridCellButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(150), label = "cellScale")
    LaunchedEffect(pressed) { if (pressed) Haptics.tap() }
    Box(
        modifier
            .scale(scale)
            .alpha(if (pressed) 0.6f else 1f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    ) { content() }
}

@Composable
private fun BattleshipGrid(cellSize: Dp, cell: @Composable (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in 0 until Battleship.size) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (column in 0 until Battleship.size) {
                    Box(Modifier.width(cellSize)) { cell(row * Battleship.size + column) }
                }
            }
        }
    }
}

@Composable
private fun colorForShot(outcome: Battleship.Outcome?): Color = when (outcome) {
    null -> AeroTheme.onBackground.copy(alpha = 0.08f)
    Battleship.Outcome.miss -> AeroTheme.onBackground.copy(alpha = 0.3f)
    Battleship.Outcome.hit -> Color(0xFFFF9500)
    Battleship.Outcome.sunk -> Color.Red
}

// MARK: Carte

/** Bolla compatta delle partite a carte: titolo, giocatori, punteggio, stato, "Accetta" e "Apri tavolo". */
@Composable
private fun CardGameBubbleContent(state: GameSessionState, onAction: (GameBubbleAction) -> Unit) {
    val mySeat = state.mySeat
    val canAccept = state.status == GameStatus.invited && !state.iAmInviter && mySeat != null &&
        state.acceptedSeats?.contains(mySeat) != true
    val hasTable = state.scopa != null || state.briscola != null
    Column(Modifier.widthIn(min = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(gameTitle(state.gameKind), style = AeroTheme.title(15f, FontWeight.SemiBold), color = AeroTheme.textPrimary)
            Spacer(Modifier.weight(1f))
            if (state.isSpectator) {
                Icon(Icons.Outlined.Visibility, contentDescription = null, tint = AeroTheme.textSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.games_spectator), style = AeroTheme.captionSemibold, color = AeroTheme.textSecondary)
            }
        }
        Text(playersLine(state), style = AeroTheme.caption, color = AeroTheme.textSecondary, maxLines = 2)
        val scores = cardScores(state)
        if (scores != null && scores.size == 2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (state.gameKind == GameKind.BRISCOLA) R.string.games_points else R.string.games_score),
                    style = AeroTheme.caption, color = AeroTheme.textSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${scores[0]} – ${scores[1]}",
                    style = AeroTheme.body.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold,
                    color = AeroTheme.textPrimary,
                )
            }
        }
        Text(cardStatusText(state), style = AeroTheme.captionSemibold, color = statusColor(state))
        resignText(state)?.let { Text(it, style = AeroTheme.caption, color = AeroTheme.textSecondary) }
        if (canAccept) {
            Button(
                onClick = { onAction(GameBubbleAction.Accept) },
                modifier = Modifier.fillMaxWidth().hapticOnTap(),
                colors = ButtonDefaults.buttonColors(containerColor = AeroTheme.accentEmerald, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    stringResource(R.string.games_accept), style = AeroTheme.body, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.defaultMinSize(minHeight = 28.dp).padding(vertical = 4.dp),
                )
            }
        }
        if (hasTable) {
            OutlinedButton(
                onClick = { onAction(GameBubbleAction.OpenTable) },
                modifier = Modifier.fillMaxWidth().hapticOnTap(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AeroTheme.accentCyan.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AeroTheme.accentCyan),
            ) {
                Icon(Icons.Outlined.Style, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.games_open_table), style = AeroTheme.body, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.defaultMinSize(minHeight = 28.dp).padding(vertical = 4.dp),
                )
            }
        }
    }
}
