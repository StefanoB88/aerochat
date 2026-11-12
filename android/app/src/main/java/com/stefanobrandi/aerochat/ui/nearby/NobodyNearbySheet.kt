package com.stefanobrandi.aerochat.ui.nearby

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.moderation.AppSettings
import com.stefanobrandi.aerochat.core.transport.BLEPermissions
import com.stefanobrandi.aerochat.core.transport.RadioState
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.AppNavigator
import com.stefanobrandi.aerochat.ui.components.AeroSheet
import com.stefanobrandi.aerochat.ui.components.DeniedPermission
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.SheetBarButton
import com.stefanobrandi.aerochat.ui.components.rememberMeshPermissionsRequester
import com.stefanobrandi.aerochat.ui.components.rememberPermissionRequest
import kotlinx.coroutines.launch

/** Esito di un controllo della diagnosi. */
private enum class CheckResult { ok, failed, warning }

/**
 * "Perche' non vedo nessuno?" (RELEASE_1_4_SPEC.md §B6): controlli con esito ✓/✗ e un'azione (Bluetooth,
 * permesso, notifiche, visibilita'), consigli (app aperta sull'altro telefono, distanza, versione),
 * "Riavvia la radio" e "Prova col passeggero virtuale". Raggiungibile da Persone vicine vuota, radar vuoto
 * e dalla scheda principale "Nessuno vicino per ora".
 */
@Composable
fun NobodyNearbySheet(onDismiss: () -> Unit) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val radioState by environment.radioState.collectAsStateWithLifecycle()
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val requestMeshPermissions = rememberMeshPermissionsRequester()
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionRequest(DeniedPermission.notifications, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }
    // Ricontrollo al ritorno dalle Impostazioni di sistema.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    var isRestarting by remember { mutableStateOf(false) }

    val hasPermission = remember(refresh, radioState) { BLEPermissions.hasAllPermissions(context) }
    val bluetoothOn = radioState == RadioState.poweredOn
    val notificationsOn = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }

    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.growth14_diag_title),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.shell_close), dismiss) },
    ) { dismiss ->
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.growth14_diag_intro), style = AeroTheme.footnote, color = AeroTheme.textSecondary)

            CheckRow(
                result = if (bluetoothOn) CheckResult.ok else CheckResult.failed,
                title = stringResource(R.string.growth14_diag_bluetooth),
                detail = stringResource(if (bluetoothOn) R.string.growth14_diag_bluetooth_ok else R.string.growth14_diag_bluetooth_off),
                action = if (bluetoothOn) null else stringResource(R.string.growth14_diag_bluetooth_action),
                onAction = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            )
            CheckRow(
                result = if (hasPermission) CheckResult.ok else CheckResult.failed,
                title = stringResource(R.string.growth14_diag_permission),
                detail = stringResource(if (hasPermission) R.string.growth14_diag_permission_ok else R.string.growth14_diag_permission_missing),
                action = if (hasPermission) null else stringResource(R.string.growth14_diag_permission_action),
                onAction = { requestMeshPermissions() },
            )
            CheckRow(
                result = if (notificationsOn) CheckResult.ok else CheckResult.warning,
                title = stringResource(R.string.growth14_diag_notifications),
                detail = stringResource(if (notificationsOn) R.string.growth14_diag_notifications_ok else R.string.growth14_diag_notifications_off),
                action = if (notificationsOn) null else stringResource(R.string.growth14_diag_notifications_action),
                onAction = {
                    val request = notificationPermission
                    if (request != null) request.run { refresh++ } else {
                        com.stefanobrandi.aerochat.ui.components.AppPermissions.openNotificationSettings(context)
                    }
                },
            )
            CheckRow(
                result = if (settings.isNearbyVisible) CheckResult.ok else CheckResult.failed,
                title = stringResource(R.string.nearby_visible_toggle),
                detail = stringResource(if (settings.isNearbyVisible) R.string.nearby_visible_on_hint else R.string.nearby_visible_off_hint),
                action = if (settings.isNearbyVisible) null else stringResource(R.string.growth14_diag_visible_action),
                onAction = { scope.launch { environment.setNearbyVisible(true) } },
            )

            Text(
                stringResource(R.string.growth14_diag_tips).uppercase(),
                style = AeroTheme.captionSemibold,
                color = AeroTheme.textSecondary,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
            TipRow(stringResource(R.string.growth14_diag_tip_open))
            TipRow(stringResource(R.string.growth14_diag_tip_distance))
            TipRow(stringResource(R.string.growth14_diag_tip_update))

            Button(
                onClick = {
                    if (isRestarting) return@Button
                    Haptics.tap()
                    isRestarting = true
                    scope.launch {
                        runCatching { environment.restartRadio() }
                        isRestarting = false
                        Haptics.success()
                    }
                },
                enabled = !isRestarting,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AeroTheme.accentCyan, contentColor = Color.Black),
            ) {
                if (isRestarting) {
                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.growth14_diag_restart), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    Haptics.tap()
                    scope.launch {
                        val chat = runCatching { environment.startDemo() }.getOrNull() ?: return@launch
                        dismiss()
                        AppNavigator.openConversation(chat.id)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = AeroTheme.accentEmerald, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.nearby_try_demo), color = AeroTheme.accentEmerald)
            }
        }
    }
}

@Composable
private fun CheckRow(result: CheckResult, title: String, detail: String, action: String?, onAction: () -> Unit) {
    val (icon, tint) = when (result) {
        CheckResult.ok -> Icons.Filled.CheckCircle to AeroTheme.accentEmerald
        CheckResult.failed -> Icons.Filled.Error to Color(0xFFFF453A)
        CheckResult.warning -> Icons.Filled.Warning to Color(0xFFFF9F0A)
    }
    val status = stringResource(
        when (result) {
            CheckResult.ok -> R.string.growth14_diag_ok
            CheckResult.failed -> R.string.growth14_diag_failed
            CheckResult.warning -> R.string.growth14_diag_warning
        },
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = status, tint = tint, modifier = Modifier.size(22.dp))
            Text(title, style = AeroTheme.title(16f, FontWeight.SemiBold), color = AeroTheme.textPrimary, modifier = Modifier.weight(1f))
        }
        Text(detail, style = AeroTheme.footnote, color = AeroTheme.textSecondary, modifier = Modifier.padding(start = 32.dp))
        if (action != null) {
            TextButton(onClick = { Haptics.tap(); onAction() }, modifier = Modifier.padding(start = 20.dp)) {
                Text(action, color = AeroTheme.accentCyan, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TipRow(text: String, icon: ImageVector = Icons.Filled.Lightbulb) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(20.dp))
        Text(text, style = AeroTheme.footnote, color = AeroTheme.textPrimary, modifier = Modifier.weight(1f))
    }
}
