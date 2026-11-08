package com.example.aerochat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aerochat.app.AppEnvironment
import com.example.aerochat.app.LocalAppEnvironment
import com.example.aerochat.ui.AeroTheme
import com.example.aerochat.ui.MainTabView
import com.example.aerochat.ui.onboarding.OnboardingView

/** Root: avvio → onboarding zero-account → tab principali. */
@Composable
fun ContentView() {
    val environment = LocalAppEnvironment.current
    val phase by environment.phase.collectAsStateWithLifecycle()
    val identityRecord by environment.identityRecord.collectAsStateWithLifecycle()

    // Stato radice: 0 avvio, 1 errore, 2 onboarding, 3 tab principali.
    val screen = when (phase) {
        AppEnvironment.Phase.Booting -> 0
        is AppEnvironment.Phase.Failed -> 1
        AppEnvironment.Phase.Ready -> if (identityRecord != null) 3 else 2
    }
    Box(Modifier.fillMaxSize().background(AeroTheme.background)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                if (targetState == 2) {
                    (slideInVertically(tween(350)) { it } + fadeIn(tween(350))) togetherWith fadeOut(tween(350))
                } else {
                    fadeIn(tween(350)) togetherWith fadeOut(tween(350))
                }
            },
            label = "root",
        ) { target ->
            when (target) {
                0 -> BootView()
                1 -> FailureView((phase as? AppEnvironment.Phase.Failed)?.message.orEmpty())
                2 -> OnboardingView()
                else -> MainTabView()
            }
        }
    }
}

@Composable
private fun BootView() {
    Box(Modifier.fillMaxSize().background(AeroTheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Filled.FlightTakeoff, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(52.dp))
            Text(stringResource(R.string.app_name), style = AeroTheme.title(32f), color = AeroTheme.textPrimary)
            CircularProgressIndicator(color = AeroTheme.accentCyan, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun FailureView(message: String) {
    Box(
        Modifier.fillMaxSize().background(AeroTheme.background).safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.Red)
                Text(
                    stringResource(R.string.shell_boot_failed),
                    style = AeroTheme.title(20f, FontWeight.SemiBold),
                    color = Color.Red,
                )
            }
            SelectionContainer {
                Text(message, style = AeroTheme.monospaced(12f), color = AeroTheme.textSecondary)
            }
        }
    }
}
