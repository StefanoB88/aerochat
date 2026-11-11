package com.stefanobrandi.aerochat.ui.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.data.records.GroupMemberRecord
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.Haptics

/**
 * Scelta dei 3 compagni di tavolo per lo Scopone: l'ordine di selezione e' l'ordine dei posti 1..3,
 * quindi il secondo scelto (posto 2) e' il compagno di squadra.
 */
@Composable
fun ScoponePlayerPicker(members: List<GroupMemberRecord>, onInvite: (List<ByteArray>) -> Unit, onDismiss: () -> Unit) {
    val selected = remember { mutableStateListOf<String>() }
    fun toggle(member: GroupMemberRecord) {
        Haptics.tap()
        val hex = member.nodeID.toHex()
        if (selected.contains(hex)) selected.remove(hex) else if (selected.size < 3) selected.add(hex)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AeroTheme.surface,
        titleContentColor = AeroTheme.textPrimary,
        textContentColor = AeroTheme.textPrimary,
        title = { Text(stringResource(R.string.games_scopone)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.games_pick_three), style = AeroTheme.captionSemibold, color = AeroTheme.textSecondary)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(members, key = { it.nodeID.toHex() }) { member ->
                        val hex = member.nodeID.toHex()
                        val index = selected.indexOf(hex)
                        val enabled = index >= 0 || selected.size < 3
                        val color = if (index == 1) AeroTheme.accentEmerald else AeroTheme.accentCyan
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled, role = Role.Checkbox) { toggle(member) }
                                .padding(vertical = 10.dp)
                                .semantics { this.selected = index >= 0 },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                member.nickname,
                                style = AeroTheme.body,
                                color = if (enabled) AeroTheme.textPrimary else AeroTheme.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            if (index >= 0) {
                                Text(
                                    stringResource(if (index == 1) R.string.games_partner else R.string.games_opponent),
                                    style = AeroTheme.captionSemibold, color = color,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${index + 1}",
                                    style = AeroTheme.caption.copy(fontWeight = FontWeight.Bold),
                                    color = Color.Black,
                                    modifier = Modifier.background(color, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            } else {
                                Icon(Icons.Outlined.Circle, contentDescription = null, tint = AeroTheme.textSecondary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                Text(stringResource(R.string.games_partner_hint), style = AeroTheme.caption, color = AeroTheme.textSecondary)
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.size == 3,
                onClick = {
                    Haptics.tap()
                    val byHex = members.associateBy { it.nodeID.toHex() }
                    onInvite(selected.mapNotNull { byHex[it]?.nodeID })
                    onDismiss()
                },
            ) { Text(stringResource(R.string.games_invite), color = if (selected.size == 3) AeroTheme.accentCyan else AeroTheme.textSecondary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.games_cancel), color = AeroTheme.accentCyan) }
        },
    )
}
