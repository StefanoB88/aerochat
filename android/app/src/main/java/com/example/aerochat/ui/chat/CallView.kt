package com.example.aerochat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SignalWifiBad
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aerochat.R
import com.example.aerochat.app.LocalAppEnvironment
import com.example.aerochat.core.calls.CallService
import com.example.aerochat.ui.AeroForcedDark
import com.example.aerochat.ui.AeroTheme
import com.example.aerochat.ui.components.AvatarView
import com.example.aerochat.ui.components.HapticButton
import com.example.aerochat.ui.components.MessageBubble
import com.example.aerochat.ui.components.rememberMicrophonePermission
import com.example.aerochat.ui.hex
import kotlinx.coroutines.delay

/**
 * Schermata a tutto schermo della chiamata: squillo in entrata/uscita, chiamata attiva con durata,
 * muto, vivavoce, indicatore di segnale debole e riepilogo finale.
 */
@Composable
fun CallView() = AeroForcedDark {
    // Schermata di chiamata: design scuro a tutto schermo, indipendente dal tema di sistema.
    val environment = LocalAppEnvironment.current
    val calls by environment.calls.collectAsStateWithLifecycle()
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.hex(0x0B2447), AeroTheme.background))),
    ) {
        val service = calls ?: return@Box
        val call by service.current.collectAsStateWithLifecycle()
        val current = call ?: return@Box
        CallContent(current, service)
    }
}

@Composable
private fun CallContent(call: CallService.Call, calls: CallService) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Box(contentAlignment = Alignment.Center) {
            AvatarView(imageData = call.peer.avatarImage, colorHex = call.peer.avatarColorHex, size = 132.dp)
            if (call.phase == CallService.Phase.Ringing) {
                PulsingRing(Modifier.size(132.dp))
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                call.peer.nickname,
                style = AeroTheme.title(30f),
                color = AeroTheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CallStatus(call)
            AnimatedVisibility(
                visible = call.isSignalWeak && call.phase == CallService.Phase.Active,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.SignalWifiBad, contentDescription = null, tint = Color(0xFFFFD60A), modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.chat_signal_weak),
                        style = AeroTheme.footnote,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFFD60A),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        CallControls(call, calls, Modifier.padding(bottom = 48.dp))
    }
}

@Composable
private fun CallStatus(call: CallService.Call) {
    val style = AeroTheme.headline
    when (val phase = call.phase) {
        CallService.Phase.Ringing -> Text(
            stringResource(
                if (call.direction == CallService.Direction.incoming) R.string.chat_call_incoming else R.string.chat_call_ringing,
            ),
            style = style,
            color = AeroTheme.textSecondary,
        )
        CallService.Phase.Active -> {
            val connectedAt = call.connectedAt ?: return
            // Solo questo testo si aggiorna ogni secondo.
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(connectedAt) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1_000 - (now - connectedAt).mod(1_000L))
                }
            }
            Text(
                MessageBubble.durationLabel(now - connectedAt),
                style = style.copy(fontFeatureSettings = "tnum"),
                color = AeroTheme.textSecondary,
            )
        }
        is CallService.Phase.Ended -> Text(endReasonLabel(phase.reason), style = style, color = AeroTheme.textSecondary)
    }
}

@Composable
private fun CallControls(call: CallService.Call, calls: CallService, modifier: Modifier = Modifier) {
    // Rispondere accende il microfono: su Android il permesso va chiesto dalla UI prima di accettare
    // (se negato si accetta comunque: il servizio chiude con `microphoneDenied` e lo mostra nel riepilogo).
    val microphone = rememberMicrophonePermission()
    when (call.phase) {
        CallService.Phase.Ringing -> if (call.direction == CallService.Direction.incoming) {
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(72.dp)) {
                CallButton(Icons.Filled.CallEnd, stringResource(R.string.chat_call_reject), Color.Red) { calls.decline() }
                CallButton(Icons.Filled.Call, stringResource(R.string.chat_call_answer), AeroTheme.accentEmerald) {
                    microphone.runWithPermission(onDenied = { calls.accept() }) { calls.accept() }
                }
            }
        } else {
            ActiveControls(call, calls, modifier)
        }
        CallService.Phase.Active -> ActiveControls(call, calls, modifier)
        is CallService.Phase.Ended -> Unit
    }
}

@Composable
private fun ActiveControls(call: CallService.Call, calls: CallService, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(36.dp)) {
        if (call.phase == CallService.Phase.Active) {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                CallButton(
                    icon = if (call.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    title = stringResource(if (call.isMuted) R.string.chat_call_unmute else R.string.chat_call_mute),
                    tint = if (call.isMuted) Color.White else Color.White.copy(alpha = 0.18f),
                    foreground = if (call.isMuted) Color.Black else Color.White,
                ) { calls.toggleMute() }
                CallButton(
                    icon = if (call.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeMute,
                    title = stringResource(R.string.chat_call_speaker),
                    tint = if (call.isSpeakerOn) Color.White else Color.White.copy(alpha = 0.18f),
                    foreground = if (call.isSpeakerOn) Color.Black else Color.White,
                ) { calls.toggleSpeaker() }
            }
        }
        CallButton(Icons.Filled.CallEnd, stringResource(R.string.chat_call_close), Color.Red) { calls.hangUp() }
    }
}

/** Testo del riepilogo finale (Swift `CallView.label(for:)`). */
@Composable
fun endReasonLabel(reason: CallService.EndReason): String = stringResource(
    when (reason) {
        CallService.EndReason.hungUp -> R.string.chat_call_ended_hung_up
        CallService.EndReason.rejected -> R.string.chat_call_ended_rejected
        CallService.EndReason.busy -> R.string.chat_call_ended_busy
        CallService.EndReason.noAnswer -> R.string.chat_call_ended_no_answer
        CallService.EndReason.missed -> R.string.chat_call_ended_missed
        CallService.EndReason.connectionLost -> R.string.chat_call_ended_connection_lost
        CallService.EndReason.unreachable -> R.string.chat_call_ended_unreachable
        CallService.EndReason.microphoneDenied -> R.string.chat_call_ended_microphone_denied
        CallService.EndReason.failed -> R.string.chat_call_ended_failed
    },
)

/** Pulsante tondo grande della schermata di chiamata. */
@Composable
private fun CallButton(
    icon: ImageVector,
    title: String,
    tint: Color,
    foreground: Color = Color.White,
    action: () -> Unit,
) {
    HapticButton(onClick = action, tint = foreground, modifier = Modifier.semantics { contentDescription = title }) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.clearAndSetSemantics {},
        ) {
            Box(Modifier.size(76.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(34.dp))
            }
            Text(title, style = AeroTheme.footnote, fontWeight = FontWeight.SemiBold, color = AeroTheme.textPrimary)
        }
    }
}

/** Onde concentriche attorno all'avatar mentre squilla. */
@Composable
private fun PulsingRing(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ring")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearOutSlowInEasing), RepeatMode.Restart),
        label = "ringProgress",
    )
    // Scala letta in fase di layer (graphicsLayer), non in composizione: niente ricomposizione a ogni frame.
    val ringColor = AeroTheme.accentCyan
    Canvas(
        modifier
            .graphicsLayer {
                val scale = 1f + 0.5f * progress
                scaleX = scale
                scaleY = scale
            }
            .clearAndSetSemantics {},
    ) {
        val stroke = 4.dp.toPx()
        drawCircle(
            color = ringColor.copy(alpha = 0.7f * (1f - progress)),
            radius = size.minDimension / 2f - stroke / 2f,
            style = Stroke(width = stroke),
        )
    }
}
