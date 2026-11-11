package com.stefanobrandi.aerochat.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.data.records.PeerRecord
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.AeroSheet
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.SheetBarButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Crea un gruppo di viaggio scegliendo tra i compagni fidati gia' accoppiati. */
@Composable
fun NewGroupSheet(onDismiss: () -> Unit) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database by environment.database.collectAsStateWithLifecycle()
    val entitlementState by environment.entitlementState.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf("") }
    var peers by remember { mutableStateOf<List<PeerRecord>>(emptyList()) }
    /** `node_id` (hex) dei compagni scelti. */
    val selected = remember { mutableStateListOf<String>() }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isPro = entitlementState.let { environment.isPro }

    LaunchedEffect(database) {
        peers = runCatching { database?.peers?.trustedPeers() }.getOrNull() ?: emptyList()
    }

    fun create(dismiss: () -> Unit) {
        isCreating = true
        scope.launch {
            try {
                val members = peers.filter { it.nodeID.toHex() in selected }
                environment.createGroup(title = title.trim(), members = members)
                Haptics.success()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val description = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.toString()
                errorMessage = context.getString(R.string.chat_group_create_failed, description)
            } finally {
                isCreating = false
            }
        }
    }

    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.chat_new_group),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.chat_cancel), onClick = dismiss) },
        trailing = { dismiss ->
            SheetBarButton(
                stringResource(R.string.chat_create),
                onClick = { create(dismiss) },
                enabled = title.isNotBlank() && !isCreating,
                bold = true,
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().imePadding()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Section {
                        BasicTextField(
                            value = title,
                            onValueChange = { title = it },
                            singleLine = true,
                            textStyle = AeroTheme.body.copy(color = AeroTheme.textPrimary),
                            cursorBrush = SolidColor(AeroTheme.accentCyan),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (title.isEmpty()) {
                                        Text(
                                            stringResource(R.string.chat_group_name_placeholder),
                                            style = AeroTheme.body,
                                            color = AeroTheme.textSecondary,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                }
                item {
                    Text(
                        stringResource(R.string.chat_travel_buddies).uppercase(),
                        style = AeroTheme.footnote,
                        color = AeroTheme.textSecondary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                    )
                }
                item {
                    Section {
                        if (peers.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_no_paired_buddies),
                                color = AeroTheme.textSecondary,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        peers.forEachIndexed { index, peer ->
                            val id = peer.nodeID.toHex()
                            val isSelected = id in selected
                            if (index > 0) HorizontalDivider(color = AeroTheme.onBackground.copy(alpha = 0.08f), modifier = Modifier.padding(start = 16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        Haptics.tap()
                                        if (isSelected) selected.remove(id) else selected.add(id)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(peer.nickname, color = AeroTheme.textPrimary, style = AeroTheme.body)
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                    contentDescription = null,
                                    tint = AeroTheme.accentCyan,
                                )
                            }
                        }
                    }
                }
                if (!isPro) {
                    item {
                        Section(Modifier.padding(top = 16.dp)) {
                            Text(
                                stringResource(R.string.chat_group_pass_hint),
                                style = AeroTheme.footnote,
                                color = AeroTheme.textSecondary,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                errorMessage?.let { message ->
                    item {
                        Text(message, color = Color.Red, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

/** Sezione stile "inset grouped" di SwiftUI `List`. */
@Composable
private fun Section(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(12.dp)),
    ) { content() }
}
