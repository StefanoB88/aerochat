package com.stefanobrandi.aerochat.ui.nearby

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.mesh.BoardPostEntry
import com.stefanobrandi.aerochat.core.mesh.NearbyPerson
import com.stefanobrandi.aerochat.core.mesh.SeatCode
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.proto.BoardKind
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.AeroDropdownMenu
import com.stefanobrandi.aerochat.ui.components.AeroMenuHeader
import com.stefanobrandi.aerochat.ui.components.AeroMenuItem
import com.stefanobrandi.aerochat.ui.components.AeroSheet
import com.stefanobrandi.aerochat.ui.components.AvatarView
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.SheetBarButton

/** Tipi di annuncio proponibili, nell'ordine del selettore. */
internal val boardKinds: List<BoardKind> = listOf(
    BoardKind.BOARD_KIND_LOOKING_FOR, BoardKind.BOARD_KIND_OFFERING, BoardKind.BOARD_KIND_FOUND, BoardKind.BOARD_KIND_SEAT_SWAP,
)

@Composable
internal fun boardKindName(kind: BoardKind): String = stringResource(
    when (kind) {
        BoardKind.BOARD_KIND_LOOKING_FOR -> R.string.growth14_board_kind_looking
        BoardKind.BOARD_KIND_OFFERING -> R.string.growth14_board_kind_offering
        BoardKind.BOARD_KIND_FOUND -> R.string.growth14_board_kind_found
        else -> R.string.growth14_board_kind_seat_swap
    },
)

private fun boardKindColor(kind: BoardKind): Color = when (kind) {
    BoardKind.BOARD_KIND_LOOKING_FOR -> Color(0xFF00E5FF)
    BoardKind.BOARD_KIND_OFFERING -> Color(0xFF10B981)
    BoardKind.BOARD_KIND_FOUND -> Color(0xFFFF9F0A)
    else -> Color(0xFFBF5AF2)
}

/** "Ti va di scambiare il posto? Io sono al 14C." (senza la seconda frase se il posto non e' noto). */
@Composable
internal fun seatSwapGreeting(mySeat: String?): String =
    if (mySeat != null) stringResource(R.string.growth14_seat_swap_greeting_with_seat, mySeat)
    else stringResource(R.string.growth14_seat_swap_greeting)

// MARK: - Il mio posto

/** "Il mio posto" (dalla carta d'imbarco o a mano) e "Mostra il mio posto ai vicini" (default spento). */
@Composable
internal fun SeatCard(
    flightCode: String?,
    seat: String?,
    shared: Boolean,
    onEditSeat: () -> Unit,
    onSharedChange: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = flightCode != null) { Haptics.tap(); onEditSeat() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.EventSeat, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.growth14_my_seat), style = AeroTheme.body, color = AeroTheme.textPrimary, modifier = Modifier.weight(1f))
            Text(
                seat ?: stringResource(if (flightCode == null) R.string.growth14_seat_no_flight_short else R.string.growth14_seat_unknown),
                style = AeroTheme.title(17f, FontWeight.SemiBold),
                color = if (seat != null) AeroTheme.accentCyan else AeroTheme.textSecondary,
            )
            if (flightCode != null) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.growth14_seat_edit), tint = AeroTheme.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable { Haptics.tap(); onSharedChange(!shared) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.AirlineSeatReclineNormal, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.growth14_share_seat), style = AeroTheme.body, color = AeroTheme.textPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = shared,
                onCheckedChange = { Haptics.tap(); onSharedChange(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AeroTheme.accentCyan),
            )
        }
        Text(
            stringResource(if (flightCode == null) R.string.growth14_seat_no_flight else R.string.growth14_share_seat_hint),
            style = AeroTheme.caption,
            color = AeroTheme.textSecondary,
            modifier = Modifier.padding(start = 36.dp, bottom = 4.dp),
        )
    }
}

/** Inserimento manuale del posto ("14C"): validato con [SeatCode]. */
@Composable
internal fun SeatEditSheet(current: String?, onDismiss: () -> Unit, onSave: (String?) -> Boolean) {
    var text by rememberSaveable { mutableStateOf(current.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.growth14_my_seat),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.flight_cancel), dismiss) },
        trailing = { dismiss ->
            SheetBarButton(stringResource(R.string.growth14_save), onClick = {
                val value = text.trim().ifEmpty { null }
                if (value != null && SeatCode.normalized(value) == null) {
                    invalid = true
                    Haptics.error()
                } else if (onSave(value)) {
                    Haptics.success()
                    dismiss()
                } else {
                    invalid = true
                    Haptics.error()
                }
            }, bold = true)
        },
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.uppercase().take(4); invalid = false },
                placeholder = { Text("14C", style = AeroTheme.title(22f, FontWeight.SemiBold)) },
                singleLine = true,
                textStyle = AeroTheme.title(22f, FontWeight.SemiBold),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
                isError = invalid,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(if (invalid) R.string.growth14_seat_invalid else R.string.growth14_seat_hint),
                style = AeroTheme.footnote,
                color = if (invalid) Color(0xFFFF453A) else AeroTheme.textSecondary,
            )
        }
    }
}

// MARK: - Mappa dei posti

/**
 * Mappa della cabina: righe per numero di fila crescente, colonne per lettera; i vicini dello stesso volo con
 * posto noto e noi evidenziati. Tocco su un passeggero: "Saluta" o "Proponi scambio posto".
 */
@Composable
internal fun SeatMap(
    flightCode: String?,
    mySeat: String?,
    people: List<NearbyPerson>,
    onHello: (NearbyPerson) -> Unit,
    onSwap: (NearbyPerson) -> Unit,
) {
    val seated = people.mapNotNull { person -> person.seatOnFlight(flightCode)?.let { it to person } }
    if (flightCode == null || seated.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().background(AeroTheme.surface, RoundedCornerShape(16.dp)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.EventSeat, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(36.dp))
            Text(
                stringResource(if (flightCode == null) R.string.growth14_map_no_flight else R.string.growth14_map_empty),
                style = AeroTheme.footnote,
                color = AeroTheme.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val bySeat = seated.associate { (seat, person) -> seat to person }
    val allSeats = bySeat.keys + listOfNotNull(mySeat)
    val rows = allSeats.mapNotNull { SeatCode.row(it) }.distinct().sorted()
    val letters = allSeats.mapNotNull { SeatCode.letter(it) }.distinct().sorted()
    Column(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(16.dp))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(32.dp))
            letters.forEach { letter ->
                Text("$letter", style = AeroTheme.captionSemibold, color = AeroTheme.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp))
            }
        }
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$row", style = AeroTheme.captionSemibold, color = AeroTheme.textSecondary, modifier = Modifier.width(32.dp))
                letters.forEach { letter ->
                    val seat = "$row$letter"
                    SeatCell(
                        seat = seat,
                        person = bySeat[seat],
                        isMine = seat == mySeat,
                        onHello = onHello,
                        onSwap = onSwap,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeatCell(
    seat: String,
    person: NearbyPerson?,
    isMine: Boolean,
    onHello: (NearbyPerson) -> Unit,
    onSwap: (NearbyPerson) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(44.dp)
            .background(
                when {
                    isMine -> AeroTheme.accentCyan
                    person != null -> AeroTheme.accentEmerald.copy(alpha = 0.22f)
                    else -> AeroTheme.background
                },
                shape,
            )
            .border(1.dp, if (person != null || isMine) Color.Transparent else AeroTheme.onBackground.copy(alpha = 0.12f), shape)
            .clickable(enabled = person != null && !isMine) { Haptics.tap(); showMenu = true },
        contentAlignment = Alignment.Center,
    ) {
        when {
            isMine -> Text(stringResource(R.string.growth14_map_you), style = AeroTheme.captionSemibold, color = Color.Black)
            person != null -> AvatarView(imageData = null, colorHex = person.avatarColorHex, size = 32.dp)
            else -> Unit
        }
        if (person != null) {
            AeroDropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                header = AeroMenuHeader("${person.nickname} · $seat"),
                items = listOf(
                    AeroMenuItem(stringResource(if (person.isContact) R.string.nearby_open_chat else R.string.nearby_say_hello), Icons.Filled.WavingHand, iconTint = AeroTheme.accentCyan) {
                        showMenu = false
                        onHello(person)
                    },
                    AeroMenuItem(stringResource(R.string.growth14_map_propose_swap), Icons.Filled.SwapHoriz, iconTint = AeroTheme.accentEmerald) {
                        showMenu = false
                        onSwap(person)
                    },
                ),
            )
        }
    }
}

// MARK: - Bacheca

/** Bacheca: pulsanti "Nuovo annuncio" e "Scambio posto", poi gli annunci (nostri con "Chiudi", altrui con "Rispondi"). */
@Composable
internal fun BoardHeader(onCompose: () -> Unit, onQuickSeatSwap: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(AeroTheme.surface, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.growth14_board_hint), style = AeroTheme.footnote, color = AeroTheme.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { Haptics.tap(); onCompose() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AeroTheme.accentCyan, contentColor = Color.Black),
            ) {
                Icon(Icons.Outlined.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.growth14_board_new), fontWeight = FontWeight.Bold, maxLines = 1)
            }
            OutlinedButton(onClick = { Haptics.tap(); onQuickSeatSwap() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = AeroTheme.accentEmerald, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.growth14_board_kind_seat_swap), color = AeroTheme.textPrimary, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun BoardPostRow(post: BoardPostEntry, onSameFlight: Boolean, onReply: () -> Unit, onClose: () -> Unit) {
    val color = boardKindColor(post.kind)
    Column(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, if (post.isMine) color.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                boardKindName(post.kind).uppercase(),
                style = AeroTheme.captionSemibold,
                color = color,
                modifier = Modifier.background(color.copy(alpha = 0.16f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            val seat = post.seat?.takeIf { onSameFlight || post.isMine }
            Text(
                listOfNotNull(
                    if (post.isMine) stringResource(R.string.growth14_board_mine) else post.nickname,
                    seat?.let { stringResource(R.string.flight_seat, it) },
                ).joinToString(" · "),
                style = AeroTheme.caption,
                color = AeroTheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(post.text, style = AeroTheme.body, color = AeroTheme.textPrimary)
        if (post.isMine) {
            TextButton(onClick = { Haptics.tap(); onClose() }) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = AeroTheme.textSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.growth14_board_close), color = AeroTheme.textSecondary)
            }
        } else {
            Button(
                onClick = { Haptics.tap(); onReply() },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AeroTheme.accentCyan, contentColor = Color.Black),
            ) {
                Icon(Icons.Filled.WavingHand, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.nearby_reply), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun BoardEmpty() {
    Text(
        stringResource(R.string.growth14_board_empty),
        style = AeroTheme.footnote,
        color = AeroTheme.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 16.dp),
    )
}

/** Nuovo annuncio: tipo, testo (1...140 caratteri, niente link). */
@Composable
internal fun BoardComposeSheet(initialKind: BoardKind, initialText: String, onDismiss: () -> Unit, onPost: (BoardKind, String) -> Unit) {
    var kind by remember { mutableStateOf(initialKind) }
    var text by rememberSaveable { mutableStateOf(initialText) }
    val valid = BoardPostEntry.validText(text) != null
    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.growth14_board_new),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.flight_cancel), dismiss) },
        trailing = { dismiss ->
            SheetBarButton(stringResource(R.string.growth14_board_publish), onClick = {
                onPost(kind, text)
                dismiss()
            }, enabled = valid, bold = true)
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                boardKinds.forEach { candidate ->
                    FilterChip(
                        selected = kind == candidate,
                        onClick = { Haptics.tap(); kind = candidate },
                        label = { Text(boardKindName(candidate)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = boardKindColor(candidate).copy(alpha = 0.25f),
                            selectedLabelColor = AeroTheme.textPrimary,
                            labelColor = AeroTheme.textSecondary,
                        ),
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    text = if (value.codePointCount(0, value.length) <= MeshProtocol.maxBoardTextLength) value
                    else value.substring(0, value.offsetByCodePoints(0, MeshProtocol.maxBoardTextLength))
                },
                placeholder = { Text(stringResource(R.string.growth14_board_placeholder)) },
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${text.trim().codePointCount(0, text.trim().length)}/${MeshProtocol.maxBoardTextLength} · " +
                    stringResource(R.string.growth14_board_rules),
                style = AeroTheme.caption,
                color = AeroTheme.textSecondary,
            )
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = AeroTheme.surface,
    unfocusedContainerColor = AeroTheme.surface,
    focusedBorderColor = AeroTheme.accentCyan,
    unfocusedBorderColor = Color.Transparent,
    focusedTextColor = AeroTheme.textPrimary,
    unfocusedTextColor = AeroTheme.textPrimary,
    cursorColor = AeroTheme.accentCyan,
)
