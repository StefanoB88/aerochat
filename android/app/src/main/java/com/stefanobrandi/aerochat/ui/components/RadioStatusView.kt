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

/**
 * Indicatore semantico "Stato Radio" nell'header della chat, con parole umane: "Vicino a te" (o "3 persone
 * vicine" per gruppi e Cabina, con [nearbyCount]), "Raggiungibile tramite 2 persone", "Fuori portata — il
 * messaggio aspettera'". Le persone "tramite" sono i ponti in mezzo (`hops - 1`).
 */
@Composable
fun RadioStatusView(reachability: Reachability, modifier: Modifier = Modifier, compact: Boolean = false, nearbyCount: Int? = null) {
    val color = when (reachability) {
        is Reachability.Direct -> AeroTheme.accentEmerald
        is Reachability.ViaBridge -> Color(0xFFFFD60A)
        Reachability.OutOfRange -> Color.Gray
    }
    val label = when (reachability) {
        is Reachability.Direct ->
            if (nearbyCount != null && nearbyCount > 0) pluralStringResource(R.plurals.nearby_status_people_nearby, nearbyCount, nearbyCount)
            else stringResource(R.string.nearby_status_near_you)
        is Reachability.ViaBridge -> {
            val people = (reachability.hops - 1).coerceAtLeast(1)
            if (compact) pluralStringResource(R.plurals.nearby_status_via_people_short, people, people)
            else pluralStringResource(R.plurals.nearby_status_via_people, people, people)
        }
        Reachability.OutOfRange ->
            if (compact) stringResource(R.string.shell_radio_out_of_range)
            else stringResource(R.string.nearby_status_out_of_range_waiting)
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
