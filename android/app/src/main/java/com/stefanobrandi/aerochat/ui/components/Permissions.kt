package com.stefanobrandi.aerochat.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.transport.BLEPermissions
import com.stefanobrandi.aerochat.ui.AeroTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Permessi runtime Android (su iOS li chiede il sistema al primo uso). Flusso:
 *
 * 1. [MeshPermissionsGate] (composto da `MainTabView`): al primo ingresso, se mancano i permessi BLE
 *    ("Dispositivi nelle vicinanze" / posizione su API 29-30), mostra la spiegazione e poi chiede in un
 *    solo passaggio BLE + `POST_NOTIFICATIONS` (API 33+). Dopo la risposta chiama
 *    `AppEnvironment.refreshAuthorization()` (il trasporto riaccende radio/advertising e parte il servizio
 *    in primo piano).
 * 2. Gli altri si chiedono solo quando servono, con [rememberPermissionRequest] (fotocamera, posizione)
 *    e [rememberMicrophonePermission] (note vocali, chiamate).
 * 3. Negato in passato: Android non mostra piu' il dialogo (dopo due "Non consentire"). Allora, a ogni
 *    tentativo, [PermissionSettingsDialogHost] spiega a cosa serve e offre "Apri Impostazioni".
 */
object AppPermissions {

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasRecordAudio(context: Context): Boolean = isGranted(context, Manifest.permission.RECORD_AUDIO)

    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted(context, Manifest.permission.POST_NOTIFICATIONS)

    /** Permessi BLE + notifiche ancora da concedere. */
    fun missingMeshPermissions(context: Context): Array<String> {
        val wanted = BLEPermissions.requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        return wanted.filterNot { isGranted(context, it) }.toTypedArray()
    }

    /** Apre la scheda dell'app nelle Impostazioni di sistema (permesso negato "per sempre"). */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Impostazioni delle notifiche dell'app (Android 8+), altrimenti la scheda dell'app. */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { openAppSettings(context) }
    }

    /** `true` se il sistema non mostrerebbe piu' il dialogo (negato due volte): serve aprire le Impostazioni. */
    fun isPermanentlyDenied(activity: Activity?, permission: String, alreadyAsked: Boolean): Boolean {
        if (activity == null || !alreadyAsked) return false
        return !isGranted(activity, permission) && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
}

/** Permessi che, negati, bloccano una funzione: testo dell'avviso con "Apri Impostazioni". */
enum class DeniedPermission(val title: Int, val message: Int) {
    bluetooth(R.string.permission_bluetooth_title, R.string.permission_bluetooth_message),
    camera(R.string.permission_camera_title, R.string.permission_camera_message),
    microphone(R.string.permission_microphone_title, R.string.permission_microphone_message),
    location(R.string.permission_location_title, R.string.permission_location_message),
    notifications(R.string.permission_notifications_title, R.string.permission_notifications_message),
}

/** Avviso "permesso negato" da mostrare (uno alla volta), osservato da [PermissionSettingsDialogHost]. */
object PermissionPrompts {
    private val _denied = MutableStateFlow<DeniedPermission?>(null)
    val denied: StateFlow<DeniedPermission?> = _denied.asStateFlow()

    fun show(permission: DeniedPermission) {
        _denied.value = permission
    }

    fun dismiss() {
        _denied.value = null
    }

    /**
     * Sotto questa durata la risposta al `launch` non e' passata da un dialogo di sistema: Android l'ha
     * negata da solo perche' l'utente aveva gia' scelto "Non consentire" (o un criterio la blocca).
     */
    const val systemDialogMinimumMillis = 450L
}

/** Avviso globale con "Apri Impostazioni" (composto una volta, alla radice dell'app). */
@Composable
fun PermissionSettingsDialogHost() {
    val context = LocalContext.current
    val denied by PermissionPrompts.denied.collectAsStateWithLifecycle()
    val permission = denied ?: return
    AlertDialog(
        onDismissRequest = { PermissionPrompts.dismiss() },
        title = { Text(stringResource(permission.title), style = AeroTheme.title(20f, FontWeight.SemiBold)) },
        text = { Text(stringResource(permission.message), color = AeroTheme.textSecondary) },
        confirmButton = {
            TextButton(onClick = {
                PermissionPrompts.dismiss()
                if (permission == DeniedPermission.notifications) {
                    AppPermissions.openNotificationSettings(context)
                } else {
                    AppPermissions.openAppSettings(context)
                }
            }, modifier = androidx.compose.ui.Modifier.hapticOnTap()) {
                Text(stringResource(R.string.permission_open_settings), color = AeroTheme.accentCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = { PermissionPrompts.dismiss() }, modifier = androidx.compose.ui.Modifier.hapticOnTap()) {
                Text(stringResource(R.string.permission_not_now), color = AeroTheme.textSecondary)
            }
        },
        containerColor = AeroTheme.surface,
    )
}

/**
 * Permesso runtime chiesto al momento del bisogno. Se il sistema puo' ancora mostrare il dialogo lo
 * mostra (anche se l'utente aveva gia' detto no una volta); se non puo' piu' (negato in modo permanente)
 * compare l'avviso con "Apri Impostazioni". Con `anyOf` basta uno dei permessi (posizione approssimata).
 */
class PermissionRequest internal constructor(
    private val context: Context,
    private val permissions: Array<String>,
    private val anyOf: Boolean,
    private val kind: DeniedPermission,
    private val launch: (callback: (Boolean) -> Unit) -> Unit,
) {
    val isGranted: Boolean
        get() = if (anyOf) permissions.any { AppPermissions.isGranted(context, it) } else permissions.all { AppPermissions.isGranted(context, it) }

    /** Esegue `action` col permesso concesso; altrimenti `onDenied` (dopo l'eventuale avviso). */
    fun run(onDenied: () -> Unit = {}, action: () -> Unit) {
        if (isGranted) {
            action()
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        launch { granted ->
            if (granted) {
                action()
                return@launch
            }
            // Risposta immediata = nessun dialogo mostrato: il permesso era gia' negato per sempre.
            if (SystemClock.elapsedRealtime() - startedAt < PermissionPrompts.systemDialogMinimumMillis) {
                PermissionPrompts.show(kind)
            }
            onDenied()
        }
    }
}

@Composable
fun rememberPermissionRequest(kind: DeniedPermission, vararg permissions: String, anyOf: Boolean = false): PermissionRequest {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = if (anyOf) result.values.any { it } else result.isNotEmpty() && result.values.all { it }
        pending.value?.invoke(granted)
        pending.value = null
    }
    val key = permissions.joinToString()
    return remember(launcher, key, anyOf, kind) {
        PermissionRequest(context, arrayOf(*permissions), anyOf, kind) { callback ->
            pending.value = callback
            launcher.launch(arrayOf(*permissions))
        }
    }
}

/** Activity che ospita il composable (per rationale, Billing, CameraX). */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Ritorna una funzione che chiede (di nuovo) i permessi BLE + notifiche; se l'utente li ha gia' negati
 * in modo permanente apre le Impostazioni. Al termine aggiorna il trasporto.
 */
@Composable
fun rememberMeshPermissionsRequester(): () -> Unit {
    val context = LocalContext.current
    val environment = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    var asked by rememberSaveable { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Negato senza dialogo (gia' "Non consentire" in passato): avviso con "Apri Impostazioni".
        if (!BLEPermissions.hasAllPermissions(context) &&
            SystemClock.elapsedRealtime() - startedAt < PermissionPrompts.systemDialogMinimumMillis
        ) {
            PermissionPrompts.show(DeniedPermission.bluetooth)
        }
        scope.launch { environment.refreshAuthorization() }
    }
    return remember(launcher) {
        {
            val missing = AppPermissions.missingMeshPermissions(context)
            val activity = context.findActivity()
            when {
                missing.isEmpty() -> scope.launch { environment.refreshAuthorization() }
                missing.all { AppPermissions.isPermanentlyDenied(activity, it, asked) } ->
                    PermissionPrompts.show(DeniedPermission.bluetooth)
                else -> {
                    asked = true
                    startedAt = SystemClock.elapsedRealtime()
                    launcher.launch(missing)
                }
            }
        }
    }
}

/** Primo avvio dopo l'onboarding: spiegazione + richiesta dei permessi mesh (una volta per processo). */
@Composable
fun MeshPermissionsGate() {
    val context = LocalContext.current
    val environment = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    var checked by rememberSaveable { mutableStateOf(false) }
    var showRationale by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { environment.refreshAuthorization() }
    }
    LaunchedEffect(Unit) {
        if (checked) return@LaunchedEffect
        checked = true
        val missing = AppPermissions.missingMeshPermissions(context)
        when {
            missing.isEmpty() -> Unit
            // Solo le notifiche mancanti: il dialogo di sistema basta, senza spiegazione.
            BLEPermissions.hasAllPermissions(context) -> launcher.launch(missing)
            else -> showRationale = true
        }
    }
    if (!showRationale) return

    AlertDialog(
        onDismissRequest = { showRationale = false },
        icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = AeroTheme.accentCyan) },
        title = {
            Text(stringResource(R.string.shell_permission_title), style = AeroTheme.title(20f, FontWeight.SemiBold))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.shell_permission_bluetooth_rationale), color = AeroTheme.textSecondary)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    Text(
                        stringResource(R.string.shell_permission_location_note),
                        style = AeroTheme.footnote,
                        color = AeroTheme.textSecondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                showRationale = false
                launcher.launch(AppPermissions.missingMeshPermissions(context))
            }, modifier = androidx.compose.ui.Modifier.hapticOnTap()) {
                Text(stringResource(R.string.shell_permission_allow), color = AeroTheme.accentCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = { showRationale = false }, modifier = androidx.compose.ui.Modifier.hapticOnTap()) {
                Text(stringResource(R.string.shell_permission_later), color = AeroTheme.textSecondary)
            }
        },
        containerColor = AeroTheme.surface,
    )
}

/**
 * Permesso microfono richiesto al momento del bisogno (note vocali, chiamate).
 *
 * ```
 * val microphone = rememberMicrophonePermission()
 * microphone.runWithPermission(onDenied = { showError(...) }) { startRecording() }
 * ```
 * Se il permesso e' negato in modo permanente, `onDenied` riceve `true` (proporre "Apri Impostazioni":
 * `AppPermissions.openAppSettings(context)`).
 */
class MicrophonePermission internal constructor(
    private val context: Context,
    private val launch: (callback: (Boolean) -> Unit) -> Unit,
) {
    val isGranted: Boolean get() = AppPermissions.hasRecordAudio(context)

    fun runWithPermission(onDenied: (permanently: Boolean) -> Unit = {}, action: () -> Unit) {
        if (isGranted) {
            action()
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        launch { granted ->
            if (granted) {
                action()
            } else {
                // Nessun dialogo di sistema (negato per sempre): avviso con "Apri Impostazioni".
                val permanently = SystemClock.elapsedRealtime() - startedAt < PermissionPrompts.systemDialogMinimumMillis
                if (permanently) PermissionPrompts.show(DeniedPermission.microphone)
                onDenied(permanently)
            }
        }
    }

    fun openSettings() = AppPermissions.openAppSettings(context)
}

@Composable
fun rememberMicrophonePermission(): MicrophonePermission {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending.value?.invoke(granted)
        pending.value = null
    }
    return remember(launcher) {
        MicrophonePermission(context) { callback ->
            pending.value = callback
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
