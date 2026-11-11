package com.stefanobrandi.aerochat.ui.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.flight.BoardingPass
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.qr.QRScannerError
import com.stefanobrandi.aerochat.core.qr.QRScannerView
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.AeroSheet
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.SheetBarButton
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Modalita' del foglio (Swift `BoardingPassSheet.Mode`). */
private enum class BoardingMode { scan, manual }

/** Formati letti dallo scanner: le carte d'imbarco IATA usano PDF417 (carta), Aztec (Wallet/app) o QR. */
private const val boardingFormats = Barcode.FORMAT_PDF417 or Barcode.FORMAT_AZTEC or Barcode.FORMAT_QR_CODE

/** Mezzanotte UTC del giorno locale di oggi: e' il formato dei millisecondi del DatePicker Material. */
private fun todayUtcMidnight(): Long = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Giorno di partenza (mezzanotte UTC) formattato nella lingua corrente, senza spostamenti di fuso. */
internal fun formatDepartureDay(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneOffset.UTC)
        .format(java.time.Instant.ofEpochMilli(epochMs))

/**
 * Entra nella Cabina del volo: inquadra il codice della carta d'imbarco (PDF417/Aztec/QR, formato IATA)
 * oppure inserisci numero di volo e data. Nulla lascia il telefono: dal volo si deriva solo la chiave del gruppo.
 *
 * [onJoined] riceve la Cabina appena creata o ritrovata, poco prima che il foglio si chiuda da solo:
 * chi lo presenta la apre in [onDismiss] (come il `.sheet(onDismiss:)` di Swift).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardingPassSheet(
    onDismiss: () -> Unit,
    onJoined: (ConversationRecord) -> Unit = {},
) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(BoardingMode.scan) }
    var flightText by rememberSaveable { mutableStateOf("") }
    var departureDay by rememberSaveable { mutableLongStateOf(todayUtcMidnight()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf<BoardingPass?>(null) }

    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.flight_lounge_title),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.shell_close), dismiss) },
    ) { dismiss ->

        fun join(pass: BoardingPass) {
            if (isBusy) return
            isBusy = true
            scope.launch {
                try {
                    val lounge = environment.joinFlightLounge(pass)
                    Haptics.success()
                    isError = false
                    status = context.getString(R.string.flight_joined, pass.flightCode)
                    delay(1_000)
                    onJoined(lounge)
                    dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Haptics.error()
                    isError = true
                    status = context.getString(R.string.flight_join_failed, e.localizedMessage ?: e.toString())
                } finally {
                    isBusy = false
                }
            }
        }

        fun handle(code: String) {
            if (isBusy) return
            val pass = try {
                BoardingPass.fromBCBP(code)
            } catch (_: BoardingPass.ParseError) {
                null
            }
            if (pass == null) {
                // Un QR di pairing AeroChat inquadrato per sbaglio non e' una carta d'imbarco.
                isError = true
                status = if (code.startsWith(MeshProtocol.qrScheme)) context.getString(R.string.flight_pairing_qr_error)
                else context.getString(R.string.flight_unrecognized)
                return
            }
            detected = pass
            join(pass)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FlightSegmentedPicker(
                options = BoardingMode.entries,
                selected = mode,
                label = { stringResource(if (it == BoardingMode.scan) R.string.flight_mode_scan else R.string.flight_mode_manual) },
                onSelect = { mode = it },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            when (mode) {
                BoardingMode.scan -> ScanSection(
                    isBusy = isBusy,
                    onCode = ::handle,
                    onError = { error ->
                        isError = true
                        status = context.getString(
                            if (error == QRScannerError.permissionDenied) R.string.flight_camera_denied
                            else R.string.flight_camera_unavailable,
                        )
                    },
                )
                BoardingMode.manual -> ManualSection(
                    flightText = flightText,
                    onFlightTextChange = { flightText = it.uppercase() },
                    departureDay = departureDay,
                    onPickDate = { showDatePicker = true },
                    isBusy = isBusy,
                    onJoin = {
                        Haptics.tap()
                        val pass = BoardingPass.fromFlightText(flightText, departureDay)
                        if (pass == null) {
                            isError = true
                            status = context.getString(R.string.flight_invalid_number)
                            Haptics.error()
                        } else {
                            join(pass)
                        }
                    },
                )
            }

            detected?.let { DetectedCard(it) }

            status?.let {
                Text(
                    it,
                    style = AeroTheme.footnote,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isError) Color.Red else AeroTheme.accentEmerald,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = departureDay)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { departureDay = it }
                    showDatePicker = false
                }) { Text(stringResource(R.string.flight_ok), color = AeroTheme.accentCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.flight_cancel), color = AeroTheme.accentCyan)
                }
            },
            colors = DatePickerDefaults.colors(containerColor = AeroTheme.surface),
        ) {
            DatePicker(
                state = pickerState,
                colors = DatePickerDefaults.colors(
                    containerColor = AeroTheme.surface,
                    selectedDayContainerColor = AeroTheme.accentCyan,
                    selectedDayContentColor = Color.Black,
                    todayDateBorderColor = AeroTheme.accentCyan,
                    todayContentColor = AeroTheme.accentCyan,
                ),
            )
        }
    }
}

@Composable
private fun ScanSection(isBusy: Boolean, onCode: (String) -> Unit, onError: (QRScannerError) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(300.dp),
            contentAlignment = Alignment.Center,
        ) {
            QRScannerView(
                onCode = onCode,
                onError = onError,
                formats = boardingFormats,
                modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(24.dp)),
            )
            // Mirino rettangolare: i PDF417 sono larghi e bassi.
            Box(
                Modifier
                    .size(width = 280.dp, height = 160.dp)
                    .border(3.dp, AeroTheme.accentCyan, RoundedCornerShape(20.dp)),
            )
            if (isBusy) CircularProgressIndicator(color = AeroTheme.accentCyan)
        }
        Text(
            stringResource(R.string.flight_scan_hint),
            style = AeroTheme.footnote,
            color = AeroTheme.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun ManualSection(
    flightText: String,
    onFlightTextChange: (String) -> Unit,
    departureDay: Long,
    onPickDate: () -> Unit,
    isBusy: Boolean,
    onJoin: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.flight_number_header), style = AeroTheme.captionSemibold, color = AeroTheme.textSecondary)
            OutlinedTextField(
                value = flightText,
                onValueChange = onFlightTextChange,
                placeholder = {
                    Text(stringResource(R.string.flight_number_placeholder), style = AeroTheme.title(22f, FontWeight.SemiBold))
                },
                singleLine = true,
                textStyle = AeroTheme.title(22f, FontWeight.SemiBold),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AeroTheme.surface,
                    unfocusedContainerColor = AeroTheme.surface,
                    focusedBorderColor = AeroTheme.accentCyan,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = AeroTheme.textPrimary,
                    unfocusedTextColor = AeroTheme.textPrimary,
                    cursorColor = AeroTheme.accentCyan,
                    focusedPlaceholderColor = AeroTheme.textSecondary,
                    unfocusedPlaceholderColor = AeroTheme.textSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Riga "Giorno di partenza" con la data: tocca per aprire il calendario (Swift `DatePicker` compatto).
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AeroTheme.surface)
                .clickable {
                    Haptics.tap()
                    onPickDate()
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.flight_departure_day), style = AeroTheme.body, color = AeroTheme.textPrimary)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(formatDepartureDay(departureDay), style = AeroTheme.body, color = AeroTheme.accentCyan)
        }
        val enabled = !isBusy && flightText.isNotBlank()
        Button(
            onClick = onJoin,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AeroTheme.accentCyan,
                contentColor = Color.Black,
                disabledContainerColor = AeroTheme.accentCyan.copy(alpha = 0.3f),
                disabledContentColor = Color.Black.copy(alpha = 0.6f),
            ),
        ) {
            Icon(Icons.Filled.Flight, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.flight_join_button), style = AeroTheme.title(18f, FontWeight.Bold))
        }
        Text(stringResource(R.string.flight_manual_hint), style = AeroTheme.footnote, color = AeroTheme.textSecondary)
    }
}

@Composable
private fun DetectedCard(pass: BoardingPass) {
    val seat = pass.seat?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.flight_seat, it) } ?: ""
    val detail = listOf(pass.routeText, formatDepartureDay(pass.departureDay), seat).filter { it.isNotEmpty() }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(AeroTheme.surface, AeroTheme.cardShape)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Filled.FlightTakeoff, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(pass.flightCode, style = AeroTheme.title(20f, FontWeight.Bold), color = AeroTheme.textPrimary)
            Text(detail, style = AeroTheme.footnote, color = AeroTheme.textSecondary)
        }
    }
}

/** Selettore segmentato nello stile del foglio di pairing (Swift `.pickerStyle(.segmented)`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> FlightSegmentedPicker(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    SingleChoiceSegmentedButtonRow(
        modifier.fillMaxWidth().then(
            if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier,
        ),
    ) {
        options.forEachIndexed { index, item ->
            SegmentedButton(
                selected = selected == item,
                onClick = {
                    if (selected != item) Haptics.tap()
                    onSelect(item)
                },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = AeroTheme.surface,
                    activeContentColor = AeroTheme.textPrimary,
                    inactiveContainerColor = AeroTheme.background,
                    inactiveContentColor = AeroTheme.textSecondary,
                    activeBorderColor = AeroTheme.onBackground.copy(alpha = 0.2f),
                    inactiveBorderColor = AeroTheme.onBackground.copy(alpha = 0.2f),
                ),
                icon = {},
            ) { Text(label(item)) }
        }
    }
}
