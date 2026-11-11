package com.stefanobrandi.aerochat.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import kotlinx.coroutines.launch

/**
 * Permessi runtime Android (su iOS li chiede il sistema al primo uso). Flusso:
 *
 * 1. [MeshPermissionsGate] (composto da `MainTabView`): al primo ingresso, se mancano i permessi BLE
 *    ("Dispositivi nelle vicinanze" / posizione su API 29-30), mostra la spiegazione e poi chiede in un
 *    solo passaggio BLE + `POST_NOTIFICATIONS` (API 33+). Dopo la risposta chiama
 *    `AppEnvironment.refreshAuthorization()` (il trasporto riaccende radio/advertising e parte il servizio
 *    in primo piano).
 * 2. `RECORD_AUDIO` si chiede solo quando serve: [rememberMicrophonePermission] per note vocali e chiamate.
 * 3. `CAMERA` la chiede `QRScannerView`.
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

    /** `true` se il sistema non mostrerebbe piu' il dialogo (negato due volte): serve aprire le Impostazioni. */
    fun isPermanentlyDenied(activity: Activity?, permission: String, alreadyAsked: Boolean): Boolean {
        if (activity == null || !alreadyAsked) return false
        return !isGranted(activity, permission) && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { environment.refreshAuthorization() }
    }
    return remember(launcher) {
        {
            val missing = AppPermissions.missingMeshPermissions(context)
            val activity = context.findActivity()
            when {
                missing.isEmpty() -> scope.launch { environment.refreshAuthorization() }
                missing.all { AppPermissions.isPermanentlyDenied(activity, it, asked) } ->
                    AppPermissions.openAppSettings(context)
                else -> {
                    asked = true
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
        launch { granted ->
            if (granted) {
                action()
            } else {
                val activity = context.findActivity()
                onDenied(AppPermissions.isPermanentlyDenied(activity, Manifest.permission.RECORD_AUDIO, alreadyAsked = true))
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
