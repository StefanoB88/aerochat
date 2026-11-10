package com.example.aerochat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.DoNotTouch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aerochat.R
import com.example.aerochat.app.LocalAppEnvironment
import com.example.aerochat.core.moderation.AppLinks
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.records.PeerRecord
import com.example.aerochat.data.records.ReportRecord
import com.example.aerochat.ui.AeroTheme
import com.example.aerochat.ui.components.AvatarView
import com.example.aerochat.ui.components.Haptics
import com.example.aerochat.ui.safety.openReportEmail
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Utenti bloccati (chat e Cabina Pubblica) con sblocco (pulsante o scorrimento). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlockedUsersContent(modifier: Modifier = Modifier) {
    val environment = LocalAppEnvironment.current
    val database by environment.database.collectAsStateWithLifecycle()
    val flow = remember(database) {
        database?.peers?.observeAll()?.map { list -> list.filter { it.isBlocked } } ?: flowOf(emptyList())
    }
    val blocked by flow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    fun unblock(peer: PeerRecord) {
        Haptics.tap()
        scope.launch { environment.unblockUser(nodeID = peer.nodeID) }
    }

    val list = blocked ?: return
    if (list.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.DoNotTouch,
            tint = AeroTheme.accentCyan,
            title = stringResource(R.string.safety_blocked_empty),
            hint = stringResource(R.string.safety_blocked_empty_hint),
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list, key = { it.nodeID.toHex() }) { peer ->
            val state = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) unblock(peer)
                    false
                },
            )
            SwipeToDismissBox(
                state = state,
                enableDismissFromStartToEnd = false,
                backgroundContent = {
                    SwipeBackground(Icons.Outlined.ThumbUp, stringResource(R.string.safety_unblock), AeroTheme.accentEmerald, Alignment.CenterEnd)
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AeroTheme.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AvatarView(imageData = peer.avatarImage, colorHex = peer.avatarColorHex, size = 40.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(peer.nickname, style = AeroTheme.body, fontWeight = FontWeight.Medium, color = AeroTheme.textPrimary)
                        Text(peer.nodeID.toHex(), style = AeroTheme.monospaced(11f), color = AeroTheme.textSecondary)
                    }
                    OutlinedButton(
                        onClick = { unblock(peer) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AeroTheme.accentCyan),
                    ) {
                        Text(stringResource(R.string.safety_unblock), style = AeroTheme.footnote, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Segnalazioni salvate su questo telefono, con inoltro via email o eliminazione (scorrimento o pulsanti). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportsListContent(onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val database by environment.database.collectAsStateWithLifecycle()
    val flow = remember(database) { database?.reports?.observeAll() ?: flowOf(emptyList()) }
    val reports by flow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    fun delete(report: ReportRecord) {
        val id = report.id ?: return
        Haptics.impact(Haptics.ImpactStyle.medium)
        scope.launch { runCatching { database?.reports?.delete(id) } }
    }

    fun email(report: ReportRecord) {
        if (!openReportEmail(context, report)) {
            onMessage(context.getString(R.string.safety_no_email_app, AppLinks.safetyEmail))
        }
    }

    val list = reports ?: return
    if (list.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.VerifiedUser,
            tint = AeroTheme.accentEmerald,
            title = stringResource(R.string.safety_reports_empty),
            hint = null,
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list, key = { it.id ?: it.createdAtMs }) { report ->
            val state = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    when (value) {
                        SwipeToDismissBoxValue.EndToStart -> {
                            delete(report)
                            true
                        }
                        SwipeToDismissBoxValue.StartToEnd -> {
                            email(report)
                            false
                        }
                        SwipeToDismissBoxValue.Settled -> false
                    }
                },
            )
            SwipeToDismissBox(
                state = state,
                backgroundContent = {
                    if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                        SwipeBackground(Icons.Outlined.Email, stringResource(R.string.safety_email), AeroTheme.accentCyan, Alignment.CenterStart)
                    } else {
                        SwipeBackground(Icons.Outlined.Delete, stringResource(R.string.safety_delete), Color.Red, Alignment.CenterEnd)
                    }
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AeroTheme.surface, RoundedCornerShape(12.dp))
                        .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                report.reason.title(context),
                                style = AeroTheme.body,
                                fontWeight = FontWeight.SemiBold,
                                color = AeroTheme.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(dateFormat.format(Date(report.createdAtMs)), style = AeroTheme.caption, color = AeroTheme.textSecondary)
                        }
                        Text(report.reportedNickname ?: report.reportedNodeID.toHex(), style = AeroTheme.footnote, color = AeroTheme.accentCyan)
                        report.textSnapshot?.let {
                            Text(it, style = AeroTheme.footnote, color = AeroTheme.textSecondary, maxLines = 3)
                        }
                    }
                    Column {
                        IconButton(onClick = {
                            Haptics.tap()
                            email(report)
                        }) {
                            Icon(Icons.Outlined.Email, contentDescription = stringResource(R.string.safety_email), tint = AeroTheme.accentCyan)
                        }
                        IconButton(onClick = { delete(report) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.safety_delete), tint = Color.Red)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeBackground(icon: ImageVector, label: String, color: Color, alignment: Alignment) {
    Box(
        Modifier
            .fillMaxSize()
            .background(color, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = Color.Black)
            Text(label, style = AeroTheme.footnote, fontWeight = FontWeight.SemiBold, color = Color.Black)
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, tint: Color, title: String, hint: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(40.dp))
        Text(title, style = AeroTheme.title(20f, FontWeight.SemiBold), color = AeroTheme.textPrimary, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(hint, style = AeroTheme.footnote, color = AeroTheme.textSecondary, textAlign = TextAlign.Center)
        }
    }
}
