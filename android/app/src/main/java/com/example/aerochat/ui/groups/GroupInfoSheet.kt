package com.example.aerochat.ui.groups

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.aerochat.R
import com.example.aerochat.app.LocalAppEnvironment
import com.example.aerochat.core.mesh.Reachability
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.records.ConversationRecord
import com.example.aerochat.data.records.GroupMemberRecord
import com.example.aerochat.data.records.PeerRecord
import com.example.aerochat.ui.AeroTheme
import com.example.aerochat.ui.components.AeroSheet
import com.example.aerochat.ui.components.AvatarView
import com.example.aerochat.ui.components.Haptics
import com.example.aerochat.ui.components.RadioStatusView
import com.example.aerochat.ui.components.SheetBarButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Scheda aperta toccando il nome del gruppo: partecipanti (noi compresi) con il loro stato radio.
 * [onOpenDirectChat] riceve la chat 1-a-1 scelta con "Scrivi"; la scheda poi si chiude da sola.
 */
@Composable
fun GroupInfoSheet(
    group: ConversationRecord,
    onDismiss: () -> Unit,
    onOpenDirectChat: (ConversationRecord) -> Unit = {},
) {
    val environment = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    val database by environment.database.collectAsStateWithLifecycle()
    val router by environment.router.collectAsStateWithLifecycle()
    // Ricomposizione quando cambia il nostro profilo (riga "Tu").
    environment.identityRecord.collectAsStateWithLifecycle()
    var members by remember { mutableStateOf<List<GroupMemberRecord>>(emptyList()) }
    /** Profili dei membri per `node_id` (hex): foto, colore, nickname aggiornato, ultimo avvistamento. */
    var peers by remember { mutableStateOf<Map<String, PeerRecord>>(emptyMap()) }
    /** Stato radio per `node_id` (hex). */
    var reachability by remember { mutableStateOf<Map<String, Reachability>>(emptyMap()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(database, group.id) {
        database?.conversations?.observeGroupMembers(of = group.id)?.collect { members = it }
    }
    LaunchedEffect(database) {
        database?.peers?.observeAll()?.collect { list -> peers = list.associateBy { it.nodeID.toHex() } }
    }
    // Stesso ritmo dell'header della chat (3 s); riparte subito quando cambiano i membri.
    // Solo mentre l'app e' in primo piano (STARTED): a schermo spento il foglio resta composto.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(router, members, lifecycleOwner) {
        val router = router ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val map = members.associate { it.nodeID.toHex() to router.reachability(of = it.nodeID) }
                if (map != reachability) reachability = map
                now = System.currentTimeMillis()
                delay(3_000)
            }
        }
    }

    fun nickname(member: GroupMemberRecord): String = peers[member.nodeID.toHex()]?.nickname ?: member.nickname
    // Ordine stabile per nickname: non salta a ogni aggiornamento radio.
    val sorted = members.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { nickname(it) })

    fun openDirectChat(member: GroupMemberRecord, dismiss: () -> Unit) {
        val peer = peers[member.nodeID.toHex()] ?: return
        val database = database ?: return
        Haptics.tap()
        scope.launch {
            try {
                onOpenDirectChat(database.conversations.ensureDirectConversation(with = peer))
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.chat_group_info_title),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.shell_close), onClick = dismiss) },
    ) { dismiss ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp).semantics(mergeDescendants = true) {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AvatarView(imageData = null, colorHex = "10B981", systemImage = Icons.Filled.Groups, size = 72.dp)
                    Text(
                        group.title,
                        style = AeroTheme.title(22f, FontWeight.SemiBold),
                        color = AeroTheme.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                    val count = members.size + 1
                    Text(
                        pluralStringResource(R.plurals.chat_group_participants, count, count),
                        style = AeroTheme.footnote,
                        color = AeroTheme.textSecondary,
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.chat_group_participants_header).uppercase(),
                    style = AeroTheme.footnote,
                    color = AeroTheme.textSecondary,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            item {
                Column(Modifier.fillMaxWidth().background(AeroTheme.surface, RoundedCornerShape(12.dp))) {
                    MemberRow(
                        imageData = environment.avatarImage,
                        colorHex = environment.avatarColorHex,
                        nickname = environment.nickname,
                    ) {
                        Text(stringResource(R.string.chat_group_you), style = AeroTheme.caption, color = AeroTheme.textSecondary)
                    }
                    sorted.forEach { member ->
                        val id = member.nodeID.toHex()
                        val peer = peers[id]
                        val status = reachability[id] ?: Reachability.OutOfRange
                        val name = nickname(member)
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(start = 68.dp))
                        MemberRow(
                            imageData = peer?.avatarImage,
                            colorHex = peer?.avatarColorHex,
                            nickname = name,
                            trailing = if (peer != null && !peer.isBlocked) {
                                {
                                    IconButton(onClick = { openDirectChat(member, dismiss) }) {
                                        Icon(
                                            Icons.Filled.ChatBubble,
                                            contentDescription = stringResource(R.string.chat_group_message_member, name),
                                            tint = AeroTheme.accentCyan,
                                        )
                                    }
                                }
                            } else null,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RadioStatusView(reachability = status, compact = true)
                                val seenAt = peer?.lastSeenAtMs
                                if (status == Reachability.OutOfRange && seenAt != null) {
                                    val relative = DateUtils.getRelativeTimeSpanString(
                                        minOf(seenAt, now), now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
                                    ).toString()
                                    Text(
                                        stringResource(R.string.chat_group_last_seen, relative),
                                        style = AeroTheme.caption,
                                        color = AeroTheme.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Riga partecipante: avatar, nickname e sotto lo stato (radio o "Tu"). */
@Composable
private fun MemberRow(
    imageData: ByteArray?,
    colorHex: String?,
    nickname: String,
    trailing: (@Composable () -> Unit)? = null,
    status: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarView(imageData = imageData, colorHex = colorHex, size = 40.dp)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                nickname,
                style = AeroTheme.body,
                fontWeight = FontWeight.Medium,
                color = AeroTheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            status()
        }
        trailing?.invoke()
    }
}
