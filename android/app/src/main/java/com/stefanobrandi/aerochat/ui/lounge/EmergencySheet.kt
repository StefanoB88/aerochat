package com.stefanobrandi.aerochat.ui.lounge

import com.stefanobrandi.aerochat.ui.components.rememberPermissionRequest
import com.stefanobrandi.aerochat.ui.components.DeniedPermission
import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.emergency.EmergencyAlert
import com.stefanobrandi.aerochat.core.emergency.EmergencyService
import com.stefanobrandi.aerochat.core.moderation.AppSettings
import com.stefanobrandi.aerochat.proto.EmergencyStatus
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.AeroSheet
import com.stefanobrandi.aerochat.ui.components.HapticButton
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.SheetBarButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** Avviso di emergenza/evento (Swift `EmergencySheet`): stato, testo breve e posizione facoltativa a tutti i telefoni vicini. */
@Composable
fun EmergencySheet(onDismiss: () -> Unit) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val emergency by environment.emergency.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(EmergencyStatus.OK) }
    var text by remember { mutableStateOf("") }
    var includeLocation by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var coordinate by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var isDenied by remember { mutableStateOf(false) }
    val statuses = remember { listOf(EmergencyStatus.OK, EmergencyStatus.NEED_HELP, EmergencyStatus.ALL_CLEAR) }
    // In volo (Cabina del volo attiva o modalita' aereo probabile): l'equipaggio prima di tutto.
    val airplaneMode by environment.airplaneMode.collectAsStateWithLifecycle()
    val database by environment.database.collectAsStateWithLifecycle()
    val hasFlightLounge by androidx.compose.runtime.produceState(false, database) {
        val db = database ?: return@produceState
        db.conversations.observeActiveFlightLounges().collect { value = it.isNotEmpty() }
    }
    val inFlight = airplaneMode || hasFlightLounge
    var confirmHelp by remember { mutableStateOf(false) }
    val needsLongerText = inFlight && status == EmergencyStatus.NEED_HELP &&
        text.trim().codePointCount(0, text.trim().length) < EmergencySafety.minimumHelpTextLength

    /** Una sola lettura della posizione; senza fix l'interruttore si spegne e l'avviso parte senza coordinate. */
    fun locate() {
        scope.launch {
            val location = OneShotLocator.current(context)
            if (location != null) {
                coordinate = location.latitude to location.longitude
            } else if (coordinate == null) {
                Haptics.warning()
                includeLocation = false
            }
        }
    }
    // Negata in passato: di nuovo il dialogo di sistema se possibile, altrimenti "Apri Impostazioni".
    val locationPermission = rememberPermissionRequest(
        DeniedPermission.location,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        anyOf = true,
    )

    fun send(dismiss: () -> Unit) {
        val service = emergency ?: return
        val now = System.currentTimeMillis()
        // In volo: al massimo un avviso ogni 10 minuti, qualunque sia lo stato.
        if (inFlight && !EmergencySafety.canSend(AppSettings.lastEmergencyAtMs, now)) {
            Haptics.error()
            val minutes = EmergencySafety.minutesUntilNext(AppSettings.lastEmergencyAtMs, now)
            errorMessage = context.resources.getQuantityString(R.plurals.growth14_emergency_wait, minutes, minutes)
            return
        }
        isSending = true
        scope.launch {
            try {
                val point = if (includeLocation) coordinate else null
                service.broadcast(status = status, text = text, latitude = point?.first, longitude = point?.second)
                if (inFlight) AppSettings.lastEmergencyAtMs = System.currentTimeMillis()
                Haptics.success()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error()
                val description = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.toString()
                errorMessage = context.getString(R.string.lounge11_emergency_failed, description)
            } finally {
                isSending = false
            }
        }
    }

    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.lounge11_emergency_title),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.lounge11_cancel), onClick = dismiss) },
        trailing = { dismiss ->
            if (isSending) {
                CircularProgressIndicator(color = AeroTheme.accentCyan, strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 16.dp).size(22.dp))
            } else {
                SheetBarButton(
                    stringResource(R.string.lounge11_send),
                    onClick = {
                        // "Ho bisogno di aiuto" in volo: prima l'equipaggio, poi una conferma esplicita.
                        if (inFlight && status == EmergencyStatus.NEED_HELP) confirmHelp = true else send(dismiss)
                    },
                    enabled = emergency != null && !(includeLocation && coordinate == null && !isDenied) && !needsLongerText,
                    bold = true,
                )
                if (confirmHelp) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmHelp = false },
                        title = { Text(stringResource(R.string.growth14_emergency_confirm_title)) },
                        text = { Text(stringResource(R.string.growth14_emergency_confirm_message), color = AeroTheme.textSecondary) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                confirmHelp = false
                                send(dismiss)
                            }) { Text(stringResource(R.string.growth14_emergency_confirm_send), color = Color(0xFFFF453A)) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { confirmHelp = false }) {
                                Text(stringResource(R.string.lounge11_cancel), color = AeroTheme.accentCyan)
                            }
                        },
                        containerColor = AeroTheme.surface,
                    )
                }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(AeroTheme.background)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (inFlight) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFF9F0A).copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("🧑‍✈️", style = AeroTheme.body)
                    Text(stringResource(R.string.growth14_emergency_crew_banner), style = AeroTheme.footnote, color = AeroTheme.textPrimary)
                }
            }
            SectionHeader(stringResource(R.string.lounge11_emergency_status))
            SectionCard {
                statuses.forEachIndexed { index, candidate ->
                    if (index > 0) HorizontalDivider(color = AeroTheme.onBackground.copy(alpha = 0.08f))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                Haptics.tap()
                                status = candidate
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(EmergencyService.emoji(candidate), style = AeroTheme.body)
                        Text(
                            EmergencyService.title(candidate, context),
                            style = AeroTheme.body,
                            color = AeroTheme.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (status == candidate) Icon(Icons.Filled.Check, contentDescription = null, tint = AeroTheme.accentCyan)
                    }
                }
            }
            SectionFooter(stringResource(R.string.lounge11_emergency_footer))

            SectionHeader(stringResource(R.string.lounge11_emergency_message_header))
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = limit(value, EmergencyService.maxTextLength) },
                placeholder = { Text(stringResource(R.string.lounge11_emergency_placeholder)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AeroTheme.surface,
                    unfocusedContainerColor = AeroTheme.surface,
                    focusedBorderColor = AeroTheme.accentCyan,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = AeroTheme.accentCyan,
                    focusedTextColor = AeroTheme.textPrimary,
                    unfocusedTextColor = AeroTheme.textPrimary,
                ),
            )
            Spacer8()

            SectionCard {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.LocationOn, contentDescription = null, tint = AeroTheme.accentCyan)
                    Text(
                        stringResource(R.string.lounge11_emergency_attach_location),
                        style = AeroTheme.body,
                        color = AeroTheme.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = includeLocation,
                        onCheckedChange = { enabled ->
                            Haptics.tap()
                            includeLocation = enabled
                            if (!enabled) return@Switch
                            // Il permesso si chiede solo adesso, quando l'utente vuole allegare la posizione.
                            locationPermission.run(onDenied = { isDenied = true }) {
                                isDenied = false
                                locate()
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = AeroTheme.accentCyan),
                    )
                }
                if (includeLocation) {
                    val point = coordinate
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            point != null -> Text(
                                String.format(Locale.US, "📍 %.5f, %.5f", point.first, point.second),
                                style = AeroTheme.monospaced(12f),
                                color = AeroTheme.accentCyan,
                            )
                            isDenied -> Text(
                                stringResource(R.string.lounge11_emergency_location_denied),
                                style = AeroTheme.footnote,
                                color = Color.Red,
                            )
                            else -> {
                                CircularProgressIndicator(color = AeroTheme.accentCyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                Text(
                                    stringResource(R.string.lounge11_emergency_locating),
                                    style = AeroTheme.footnote,
                                    color = AeroTheme.textSecondary,
                                    modifier = Modifier.padding(start = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
            SectionFooter(stringResource(R.string.lounge11_emergency_location_footer))
            if (needsLongerText) {
                Text(
                    stringResource(R.string.growth14_emergency_min_text, EmergencySafety.minimumHelpTextLength),
                    style = AeroTheme.footnote,
                    color = Color(0xFFFF9F0A),
                )
            }

            errorMessage?.let { Text(it, style = AeroTheme.footnote, color = Color.Red) }
        }
    }
}

/**
 * Regole dell'emergenza in volo (RELEASE_1_4_SPEC.md §B12): "Ho bisogno di aiuto" con almeno 10 caratteri e
 * conferma esplicita, al massimo un avviso ogni 10 minuti per qualunque stato. Tempi in epoch ms.
 */
internal object EmergencySafety {
    const val minimumHelpTextLength = 10
    const val cooldown: Long = 10 * 60_000L

    fun canSend(lastSentAtMs: Long, now: Long): Boolean = lastSentAtMs <= 0L || now - lastSentAtMs >= cooldown

    /** Minuti (arrotondati per eccesso, almeno 1) prima del prossimo avviso. */
    fun minutesUntilNext(lastSentAtMs: Long, now: Long): Int {
        val remaining = cooldown - (now - lastSentAtMs)
        return maxOf(1, ((remaining + 59_999L) / 60_000L).toInt())
    }
}

/** Tronca a [max] code point senza spezzare le coppie surrogate (emoji). */
private fun limit(value: String, max: Int): String {
    if (value.codePointCount(0, value.length) <= max) return value
    return value.substring(0, value.offsetByCodePoints(0, max))
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = AeroTheme.caption,
        color = AeroTheme.textSecondary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(text, style = AeroTheme.caption, color = AeroTheme.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(AeroTheme.surface, RoundedCornerShape(14.dp)), content = content)
}

@Composable
private fun Spacer8() {
    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
}

/** Swift `OneShotLocator`: una sola lettura della posizione, solo quando l'utente la chiede. */
internal object OneShotLocator {

    fun hasPermission(context: Context): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Posizione attuale (API 30+ `getCurrentLocation`), altrimenti l'ultima nota; `null` se non disponibile. */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val enabled = runCatching { manager.getProviders(true) }.getOrNull().orEmpty()
        val preferred = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { it in enabled }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in preferred) {
                val location = runCatching { currentLocation(manager, provider) }.getOrNull()
                if (location != null) return location
            }
        }
        return (preferred + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun currentLocation(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, { it.run() }) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }
}

/** Banner rosso (aiuto) o arancione sopra le tab quando arriva un avviso (Swift `EmergencyBanner`). */
@Composable
fun EmergencyBanner(alert: EmergencyAlert, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val background = if (alert.status == EmergencyStatus.NEED_HELP) Color(0xFFFF3B30) else Color(0xFFFF9500)
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp)
            .background(background, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(alert.emoji, fontSize = 22.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${alert.senderNickname}: ${alert.statusTitle(context)}",
                style = AeroTheme.body.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            if (alert.text.isNotEmpty()) {
                Text(alert.text, style = AeroTheme.footnote, color = Color.White, maxLines = 3)
            }
            val latitude = alert.latitude
            val longitude = alert.longitude
            if (latitude != null && longitude != null) {
                Row(
                    Modifier
                        .padding(top = 4.dp)
                        .clickable { openInMaps(context, latitude, longitude, alert.senderNickname) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Filled.Map, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text(
                        stringResource(R.string.lounge11_open_in_maps),
                        style = AeroTheme.captionSemibold,
                        color = Color.White,
                    )
                }
            }
        }
        HapticButton(
            onClick = onDismiss,
            tint = Color.White,
            minimumTapSize = 32.dp,
            contentDescription = stringResource(R.string.lounge11_dismiss_alert),
        ) {
            Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.lounge11_dismiss_alert))
        }
    }
}

/** Apre la posizione nell'app di mappe predefinita (URI `geo:` con segnaposto etichettato). */
private fun openInMaps(context: Context, latitude: Double, longitude: Double, label: String) {
    val point = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
    val uri = Uri.parse("geo:$point?q=$point(${Uri.encode(label)})")
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Haptics.error()
    }
}
