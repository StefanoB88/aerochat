package com.example.aerochat.ui.lounge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aerochat.R
import com.example.aerochat.app.LocalAppEnvironment
import com.example.aerochat.core.quiz.QuizService
import com.example.aerochat.ui.AeroTheme
import com.example.aerochat.ui.chat.InfoBanner
import com.example.aerochat.ui.components.AeroSheet
import com.example.aerochat.ui.components.HapticButton
import com.example.aerochat.ui.components.Haptics
import com.example.aerochat.ui.components.SheetBarButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Quiz di cabina (Swift `CabinQuizSheet`): domande a tutta la Cabina con classifica live. Chi apre il
 * foglio senza quiz in corso puo' condurne uno.
 */
@Composable
fun CabinQuizSheet(onDismiss: () -> Unit) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val state by environment.quizState.collectAsStateWithLifecycle()
    val quiz by environment.quiz.collectAsStateWithLifecycle()
    val neighbors by environment.neighbors.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }

    /** Esegue un'azione del quiz mostrando [failure] se fallisce (come il `do/catch` di Swift). */
    fun run(failure: Int, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                errorMessage = context.getString(failure)
            }
        }
    }
    val hostQuiz: () -> Unit = {
        Haptics.impact()
        run(R.string.lounge11_quiz_already_running) { quiz?.host() }
    }

    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.lounge11_quiz_title),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.lounge11_close), onClick = dismiss) },
        trailing = {
            if (state != null) {
                HapticButton(
                    onClick = { scope.launch { quiz?.leave() } },
                    destructive = true,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.lounge11_leave), style = AeroTheme.body)
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(AeroTheme.background)) {
            val current = state
            if (current != null) {
                ActiveQuiz(
                    state = current,
                    onAnswer = { index ->
                        Haptics.tap()
                        run(R.string.lounge11_quiz_too_late) { quiz?.answer(index) }
                    },
                    onNewQuiz = {
                        Haptics.impact()
                        run(R.string.lounge11_quiz_already_running) {
                            quiz?.leave()
                            quiz?.host()
                        }
                    },
                )
            } else {
                QuizLobby(neighborCount = neighbors.size, onHost = hostQuiz)
            }
            InfoBanner(
                message = errorMessage,
                onExpire = { errorMessage = null },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun QuizLobby(neighborCount: Int, onHost: () -> Unit) {
    Column(
        Modifier.fillMaxSize().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.Quiz, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(64.dp))
        Text(stringResource(R.string.lounge11_quiz_lobby_title), style = AeroTheme.title(26f), color = AeroTheme.textPrimary)
        Text(
            stringResource(R.string.lounge11_quiz_lobby_text),
            style = AeroTheme.footnote,
            color = AeroTheme.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.weight(1f))
        ProminentButton(
            text = stringResource(R.string.lounge11_quiz_host),
            icon = { Icon(Icons.Filled.Campaign, contentDescription = null) },
            onClick = onHost,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            pluralStringResource(R.plurals.lounge11_quiz_in_range, neighborCount, neighborCount),
            style = AeroTheme.caption,
            color = AeroTheme.textSecondary,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

/** Pulsante pieno d'accento a tutta larghezza (Swift `.borderedProminent` + `.tint(accentCyan)`). */
@Composable
private fun ProminentButton(text: String, icon: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AeroTheme.accentCyan, contentColor = Color.Black),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(text, style = AeroTheme.title(18f, FontWeight.Bold))
    }
}

@Composable
private fun ActiveQuiz(state: QuizService.State, onAnswer: (Int) -> Unit, onNewQuiz: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        QuizHeader(state)
        when (state.phase) {
            QuizService.Phase.waiting -> Column(
                Modifier.fillMaxWidth().padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(color = AeroTheme.accentCyan)
                Text(stringResource(R.string.lounge11_quiz_preparing), style = AeroTheme.footnote, color = AeroTheme.textSecondary)
            }
            QuizService.Phase.question, QuizService.Phase.reveal -> {
                QuestionCard(state, onAnswer)
                if (state.phase == QuizService.Phase.reveal || state.isHost) Leaderboard(state)
            }
            QuizService.Phase.ended -> Podium(state, onNewQuiz)
        }
    }
}

@Composable
private fun QuizHeader(state: QuizService.State) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (state.isHost) stringResource(R.string.lounge11_quiz_you_host)
                else stringResource(R.string.lounge11_quiz_hosted_by, state.hostNickname),
                style = AeroTheme.captionSemibold,
                color = AeroTheme.accentEmerald,
            )
            if (state.round > 0) {
                Text(
                    stringResource(R.string.lounge11_quiz_round, state.round, state.totalRounds),
                    style = AeroTheme.title(20f, FontWeight.Bold),
                    color = AeroTheme.textPrimary,
                )
            }
        }
        if (state.phase == QuizService.Phase.question) {
            CountdownRing(deadlineMs = state.deadlineMs, durationMs = QuizService.questionDuration)
        }
    }
}

@Composable
private fun QuestionCard(state: QuizService.State, onAnswer: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(state.question.orEmpty(), style = AeroTheme.title(22f, FontWeight.SemiBold), color = AeroTheme.textPrimary)
        state.options.forEachIndexed { index, option ->
            val enabled = state.phase == QuizService.Phase.question && state.myAnswer == null
            HapticButton(
                onClick = { onAnswer(index) },
                enabled = enabled,
                tint = AeroTheme.textPrimary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(optionBackground(index, state), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(28.dp).background(Color.White.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(letter(index), style = AeroTheme.headline, fontWeight = FontWeight.Bold, color = AeroTheme.textPrimary)
                    }
                    Text(option, style = AeroTheme.body, fontWeight = FontWeight.Medium, color = AeroTheme.textPrimary, modifier = Modifier.weight(1f))
                    when {
                        state.correctIndex == index ->
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AeroTheme.accentEmerald)
                        state.myAnswer == index && state.phase == QuizService.Phase.reveal ->
                            Icon(Icons.Filled.Cancel, contentDescription = null, tint = Color.Red)
                        state.myAnswer == index ->
                            Icon(Icons.Filled.Check, contentDescription = null, tint = AeroTheme.accentCyan)
                    }
                }
            }
        }
        if (state.isHost && state.phase == QuizService.Phase.question) {
            Text(
                pluralStringResource(R.plurals.lounge11_quiz_answers, state.answerCount, state.answerCount),
                style = AeroTheme.caption,
                color = AeroTheme.textSecondary,
            )
        }
        if (state.phase == QuizService.Phase.question && state.myAnswer != null) {
            Text(stringResource(R.string.lounge11_quiz_answer_sent), style = AeroTheme.caption, color = AeroTheme.textSecondary)
        }
    }
}

private fun optionBackground(index: Int, state: QuizService.State): Color = when {
    state.correctIndex == index -> AeroTheme.accentEmerald.copy(alpha = 0.25f)
    state.myAnswer == index -> AeroTheme.accentCyan.copy(alpha = 0.2f)
    else -> AeroTheme.surface
}

private fun letter(index: Int): String = listOf("A", "B", "C", "D")[min(index, 3)]

@Composable
private fun Leaderboard(state: QuizService.State) {
    val environment = LocalAppEnvironment.current
    val identity by environment.identityKeys.collectAsStateWithLifecycle()
    val myNodeID = remember(identity) { identity?.nodeID }
    Column(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, AeroTheme.cardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.lounge11_quiz_leaderboard), style = AeroTheme.captionSemibold, color = AeroTheme.textSecondary)
        if (state.scores.isEmpty()) {
            Text(stringResource(R.string.lounge11_quiz_no_scores), style = AeroTheme.footnote, color = AeroTheme.textSecondary)
        }
        state.scores.take(10).forEachIndexed { position, entry ->
            val isMe = myNodeID != null && entry.nodeID.contentEquals(myNodeID)
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${position + 1}.",
                    style = AeroTheme.body.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold,
                    color = AeroTheme.textSecondary,
                    modifier = Modifier.width(32.dp),
                )
                Text(
                    entry.nickname,
                    style = AeroTheme.body,
                    color = if (isMe) AeroTheme.accentCyan else AeroTheme.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${entry.points}",
                    style = AeroTheme.body.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = AeroTheme.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun Podium(state: QuizService.State, onNewQuiz: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = Color(0xFFFFD60A), modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.lounge11_quiz_finished), style = AeroTheme.title(26f), color = AeroTheme.textPrimary)
        state.scores.firstOrNull()?.let { winner ->
            Text(
                pluralStringResource(R.plurals.lounge11_quiz_winner, winner.points, winner.nickname, winner.points),
                style = AeroTheme.body,
                fontWeight = FontWeight.SemiBold,
                color = AeroTheme.accentEmerald,
                textAlign = TextAlign.Center,
            )
        }
        Leaderboard(state)
        ProminentButton(
            text = stringResource(R.string.lounge11_quiz_new),
            icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
            onClick = onNewQuiz,
        )
    }
}

/** Anello del tempo rimanente per rispondere (aggiornato ogni mezzo secondo, come il `TimelineView` iOS). */
@Composable
private fun CountdownRing(deadlineMs: Long, durationMs: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadlineMs) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= deadlineMs) break
            delay(500)
        }
    }
    val remainingMs = max(0L, deadlineMs - now)
    val fraction = min(1f, remainingMs.toFloat() / durationMs)
    val seconds = ceil(remainingMs / 1000.0).toInt()
    val color = if (remainingMs < 5_000) Color.Red else AeroTheme.accentCyan
    val label = pluralStringResource(R.plurals.lounge11_quiz_seconds_left, (remainingMs / 1000).toInt(), (remainingMs / 1000).toInt())
    Box(Modifier.size(52.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("$seconds", style = AeroTheme.headline.copy(fontFamily = FontFamily.Monospace, fontSize = 17.sp), color = AeroTheme.textPrimary)
    }
}
