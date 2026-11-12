package com.stefanobrandi.aerochat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.stefanobrandi.aerochat.core.moderation.TravelMode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.core.moderation.AppSettings
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.Haptics

/** Azioni della scheda contestuale (le schermate appartengono a chi la ospita). */
class HomeCardActions(
    val openLounge: (ConversationRecord) -> Unit,
    val playAboard: () -> Unit,
    val addBoardingPass: () -> Unit,
    val inviteFriends: () -> Unit,
    val createGroup: () -> Unit,
    val openNearby: () -> Unit,
    /** Festival: "Radar: ritrova gli amici". */
    val openRadar: () -> Unit = {},
    /** Montagna: "Condividi la tua posizione" (apre un gruppo/chat). */
    val shareLocation: () -> Unit = {},
    /** Estero: "Traduzione offline" (spiegazione). */
    val showTranslationHelp: () -> Unit = {},
    /** "Nessuno vicino per ora": diagnosi "Perche' non vedo nessuno?". */
    val openDiagnosis: () -> Unit = {},
)

/** Titolo, sottotitolo e due azioni della scheda a terra per la modalita' viaggio (RELEASE_1_4_SPEC.md §B10). */
private class ModeContent(
    val title: Int,
    val hint: Int,
    val icon: ImageVector,
    val first: Triple<Int, ImageVector, (HomeCardActions) -> () -> Unit>,
    val second: Triple<Int, ImageVector, (HomeCardActions) -> () -> Unit>,
)

private fun modeContent(mode: TravelMode): ModeContent = when (mode) {
    TravelMode.flight -> ModeContent(
        R.string.nearby_home_ground_title, R.string.nearby_home_ground_hint, Icons.Filled.WavingHand,
        Triple(R.string.nearby_home_invite, Icons.Filled.QrCode) { it.inviteFriends },
        Triple(R.string.nearby_home_group, Icons.Filled.Groups) { it.createGroup },
    )
    TravelMode.ship -> ModeContent(
        R.string.growth14_mode_ship_title, R.string.growth14_mode_ship_hint, Icons.Filled.DirectionsBoat,
        Triple(R.string.growth14_mode_ship_group, Icons.Filled.Groups) { it.createGroup },
        Triple(R.string.nearby_title, Icons.Filled.WavingHand) { it.openNearby },
    )
    TravelMode.festival -> ModeContent(
        R.string.growth14_mode_festival_title, R.string.growth14_mode_festival_hint, Icons.Filled.MusicNote,
        Triple(R.string.growth14_mode_festival_group, Icons.Filled.Groups) { it.createGroup },
        Triple(R.string.growth14_mode_festival_radar, Icons.Filled.Radar) { it.openRadar },
    )
    TravelMode.mountain -> ModeContent(
        R.string.growth14_mode_mountain_title, R.string.growth14_mode_mountain_hint, Icons.Filled.Terrain,
        Triple(R.string.growth14_mode_mountain_group, Icons.Filled.Groups) { it.createGroup },
        Triple(R.string.growth14_mode_mountain_location, Icons.Filled.LocationOn) { it.shareLocation },
    )
    TravelMode.abroad -> ModeContent(
        R.string.growth14_mode_abroad_title, R.string.growth14_mode_abroad_hint, Icons.Filled.Public,
        Triple(R.string.nearby_title, Icons.Filled.WavingHand) { it.openNearby },
        Triple(R.string.growth14_mode_abroad_translate, Icons.Filled.Translate) { it.showTranslationHelp },
    )
}

@Composable
private fun modeLabel(mode: TravelMode): String = stringResource(
    when (mode) {
        TravelMode.flight -> R.string.growth14_mode_flight
        TravelMode.ship -> R.string.growth14_mode_ship
        TravelMode.festival -> R.string.growth14_mode_festival
        TravelMode.mountain -> R.string.growth14_mode_mountain
        TravelMode.abroad -> R.string.growth14_mode_abroad
    },
)

/** "AZ|204|2026-11-10" -> "AZ204". */
internal fun flightLabel(canonicalKey: String): String {
    val parts = canonicalKey.split('|')
    return if (parts.size >= 2) parts[0] + parts[1] else canonicalKey
}

/**
 * Scheda grande e adattiva in cima alla lista chat (in volo si chiude per la sessione, a terra per sempre):
 * - in volo (modalita' aereo o Cabina del volo attiva): "Sei sul volo AZ204" con "Entra nella Cabina del
 *   volo" e "Gioca con chi e' a bordo" (oppure "Aggiungi la carta d'imbarco" se manca la Cabina);
 * - a terra: "Invita amici" e "Crea un gruppo di viaggio", piu' "N persone vicine" se ce ne sono.
 */
@Composable
fun HomeCard(actions: HomeCardActions, modifier: Modifier = Modifier) {
    val environment = LocalAppEnvironment.current
    val flightCardDismissed by environment.homeCardDismissed.collectAsStateWithLifecycle()
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val airplaneMode by environment.airplaneMode.collectAsStateWithLifecycle()
    val people by environment.nearbyPeople.collectAsStateWithLifecycle()
    val trip by environment.activeTrip.collectAsStateWithLifecycle()
    val database by environment.database.collectAsStateWithLifecycle()
    val flightLounge by produceState<ConversationRecord?>(null, database) {
        val db = database ?: return@produceState
        db.conversations.observeActiveFlightLounges().collect { value = it.firstOrNull() }
    }
    val lounge = flightLounge
    val inFlight = airplaneMode || lounge != null
    if (if (inFlight) flightCardDismissed else settings.isInviteCardDismissed) return
    val mode = modeContent(settings.travelMode)

    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(AeroTheme.accentCyan.copy(alpha = 0.22f), AeroTheme.accentEmerald.copy(alpha = 0.14f)),
                ),
                RoundedCornerShape(22.dp),
            )
            .border(1.dp, AeroTheme.accentCyan.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (inFlight) Icons.Filled.Flight else mode.icon,
                contentDescription = null,
                tint = AeroTheme.accentCyan,
                modifier = Modifier.padding(top = 2.dp).size(30.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when {
                        lounge?.flightCode != null -> stringResource(R.string.nearby_home_on_flight, flightLabel(lounge.flightCode))
                        inFlight -> stringResource(R.string.nearby_home_in_flight)
                        else -> stringResource(mode.title)
                    },
                    style = AeroTheme.title(22f),
                    color = AeroTheme.textPrimary,
                )
                Text(
                    stringResource(
                        when {
                            lounge != null -> R.string.nearby_home_on_flight_hint
                            inFlight -> R.string.nearby_home_in_flight_hint
                            else -> mode.hint
                        },
                    ),
                    style = AeroTheme.footnote,
                    color = AeroTheme.textSecondary,
                )
                val current = trip
                if (inFlight && current != null && current.gamesPlayed > 0) {
                    Text(
                        pluralStringResource(R.plurals.nearby_leaderboard_wins, current.gamesPlayed, current.gamesWon, current.gamesPlayed),
                        style = AeroTheme.captionSemibold,
                        color = AeroTheme.accentEmerald,
                    )
                }
            }
            IconButton(onClick = { Haptics.tap(); environment.dismissHomeCard(inFlight) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.shell_close), tint = AeroTheme.textSecondary)
            }
        }
        if (inFlight) {
            if (lounge != null) {
                CardButton(stringResource(R.string.nearby_home_enter_lounge), Icons.Filled.Flight, AeroTheme.accentCyan) {
                    actions.openLounge(lounge)
                }
            } else {
                CardButton(stringResource(R.string.nearby_home_add_boarding), Icons.Filled.FlightTakeoff, AeroTheme.accentCyan, actions.addBoardingPass)
            }
            CardButton(stringResource(R.string.nearby_home_play_aboard), Icons.Filled.SportsEsports, AeroTheme.accentEmerald, actions.playAboard)
        } else {
            // Modalita' viaggio: Volo, Nave, Festival, Montagna, Estero (salvata nelle impostazioni).
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TravelMode.entries.forEach { candidate ->
                    FilterChip(
                        selected = settings.travelMode == candidate,
                        onClick = { Haptics.tap(); AppSettings.travelMode = candidate },
                        label = { Text(modeLabel(candidate), maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = AeroTheme.surface.copy(alpha = 0.6f),
                            labelColor = AeroTheme.textSecondary,
                            selectedContainerColor = AeroTheme.accentCyan.copy(alpha = 0.3f),
                            selectedLabelColor = AeroTheme.textPrimary,
                        ),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    CardButton(stringResource(mode.first.first), mode.first.second, AeroTheme.accentCyan, mode.first.third(actions))
                }
                Box(Modifier.weight(1f)) {
                    CardButton(stringResource(mode.second.first), mode.second.second, AeroTheme.accentEmerald, mode.second.third(actions))
                }
            }
        }
        if (people.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(AeroTheme.surface, RoundedCornerShape(12.dp))
                    .clickable { Haptics.tap(); actions.openNearby() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.WavingHand, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(18.dp))
                Text(
                    pluralStringResource(R.plurals.nearby_people_count, people.size, people.size),
                    style = AeroTheme.title(15f, FontWeight.SemiBold),
                    color = AeroTheme.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AeroTheme.textSecondary)
            }
        } else {
            // "Nessuno vicino per ora": tocco -> "Perche' non vedo nessuno?".
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(AeroTheme.surface, RoundedCornerShape(12.dp))
                    .clickable { Haptics.tap(); actions.openDiagnosis() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.WavingHand, contentDescription = null, tint = AeroTheme.textSecondary, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.nearby_status_nobody), style = AeroTheme.title(15f, FontWeight.SemiBold), color = AeroTheme.textPrimary)
                    Text(stringResource(R.string.growth14_diag_title), style = AeroTheme.caption, color = AeroTheme.accentCyan)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AeroTheme.textSecondary)
            }
        }
    }
}

@Composable
private fun CardButton(title: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Button(
        onClick = { Haptics.tap(); onClick() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = Color.Black),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(title, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
