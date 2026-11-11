package com.stefanobrandi.aerochat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.transport.RadioState
import com.stefanobrandi.aerochat.ui.AeroTheme

/** Card non bloccante mostrata quando il Bluetooth non e' acceso (tipicamente in Modalita' Aereo). */
@Composable
fun AirplaneModeBanner(state: RadioState, modifier: Modifier = Modifier) {
    val title = when (state) {
        RadioState.unauthorized -> stringResource(R.string.shell_banner_unauthorized_title)
        RadioState.unsupported -> stringResource(R.string.shell_banner_unsupported_title)
        else -> stringResource(R.string.shell_banner_off_title)
    }
    val message = when (state) {
        RadioState.unauthorized -> stringResource(R.string.shell_banner_unauthorized_message)
        RadioState.unsupported -> stringResource(R.string.shell_banner_unsupported_message)
        else -> stringResource(R.string.shell_banner_off_message)
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(AeroTheme.surface)
            .border(1.dp, AeroTheme.accentCyan.copy(alpha = 0.35f), shape)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AirplaneCircleIcon()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = AeroTheme.title(16f, FontWeight.SemiBold), color = AeroTheme.textPrimary)
            Text(message, style = AeroTheme.footnote, color = AeroTheme.textSecondary)
        }
    }
}

/** `airplane.circle.fill`: aereo nero su cerchio ciano. */
@Composable
private fun AirplaneCircleIcon() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AeroTheme.accentCyan),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.AirplanemodeActive, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
    }
}

/** Swift `AirplaneModeBanner.shouldShow(for:)`. */
object AirplaneModeBanner {
    fun shouldShow(state: RadioState): Boolean = when (state) {
        RadioState.poweredOff, RadioState.unauthorized, RadioState.unsupported -> true
        RadioState.poweredOn, RadioState.unknown, RadioState.resetting -> false
    }
}
