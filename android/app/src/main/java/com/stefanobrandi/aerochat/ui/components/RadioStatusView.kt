package com.stefanobrandi.aerochat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.mesh.Reachability
import com.stefanobrandi.aerochat.ui.AeroTheme

/** Indicatore semantico "Stato Radio" nell'header della chat. */
@Composable
fun RadioStatusView(reachability: Reachability, modifier: Modifier = Modifier, compact: Boolean = false) {
    val color = when (reachability) {
        is Reachability.Direct -> AeroTheme.accentEmerald
        is Reachability.ViaBridge -> Color(0xFFFFD60A)
        Reachability.OutOfRange -> Color.Gray
    }
    val label = when (reachability) {
        is Reachability.Direct -> stringResource(R.string.shell_radio_direct)
        is Reachability.ViaBridge ->
            pluralStringResource(R.plurals.shell_radio_via_bridge, reachability.hops, reachability.hops)
        Reachability.OutOfRange ->
            if (compact) stringResource(R.string.shell_radio_out_of_range)
            else stringResource(R.string.shell_radio_out_of_range_queued)
    }
    val a11y = stringResource(R.string.shell_radio_status_a11y, label)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = a11y },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .shadow(4.dp, CircleShape, ambientColor = color, spotColor = color)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            label,
            style = AeroTheme.caption,
            color = AeroTheme.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
