package com.stefanobrandi.aerochat.ui.pairing

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.crypto.PairingPayload
import com.stefanobrandi.aerochat.core.mesh.RouterEvent
import com.stefanobrandi.aerochat.core.protocol.base64URLDecoded
import com.stefanobrandi.aerochat.core.protocol.base64URLEncodedString
import com.stefanobrandi.aerochat.core.qr.QRCodeGenerator
import com.stefanobrandi.aerochat.core.qr.QRScannerError
import com.stefanobrandi.aerochat.core.qr.QRScannerView
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.ConversationType
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.AeroSheet
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.SheetBarButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tipi annidati di Swift `QRPairingSheet`. */
object QRPairingSheet {
    enum class Mode {
        show,
        scan;

        val id: String get() = name
    }
}

/**
 * Pairing via QR: mostra il proprio codice (personale o di gruppo, se [group] non e' nullo) oppure
 * scansiona quello altrui. Si presenta da solo come foglio modale; [onDismiss] lo rimuove.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRPairingSheet(
    onDismiss: () -> Unit,
    group: ConversationRecord? = null,
    initialMode: QRPairingSheet.Mode = QRPairingSheet.Mode.show,
) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var payload by remember { mutableStateOf<PairingPayload?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    // Token di abbinamento `pt`: uno per presentazione della scheda, stabile finche' resta aperta (anche
    // ruotando lo schermo). Salvato prima di mostrare il QR, cosi' il PAIR_HELLO di chi scansiona si apre.
    var pairingToken by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(group?.id) {
        val router = environment.router.value
        val keys = environment.identityKeys.value
        val base = if (group != null && router != null) {
            router.groupInvitePayload(group)
        } else if (keys != null) {
            PairingPayload(keys, environment.nickname)
        } else {
            null
        }
        val token = pairingToken?.base64URLDecoded() ?: try {
            (router?.issuePairingToken() ?: environment.database.value?.pairingTokens?.issue())
                ?.also { pairingToken = it.base64URLEncodedString() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Senza token il QR funziona come prima (nessun PAIR_HELLO).
            null
        }
        payload = base?.withPairingToken(token)
    }

    // Mentre il QR e' mostrato: chi lo scansiona si presenta (PAIR_HELLO) e la sua chat e' gia' pronta.
    LaunchedEffect(Unit) {
        val router = environment.router.value ?: return@LaunchedEffect
        router.events().collect { event ->
            if (event is RouterEvent.PeerPaired) {
                Haptics.success()
                status = context.getString(R.string.pairing_peer_added, event.nickname)
            }
        }
    }

    AeroSheet(
        onDismiss = onDismiss,
        title = if (group == null) stringResource(R.string.shell_pairing_title_people)
        else stringResource(R.string.shell_pairing_title_group),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.shell_close), dismiss) },
    ) { dismiss ->
        fun handle(code: String) {
            if (isBusy) return
            isBusy = true
            scope.launch {
                try {
                    val conversation = environment.pair(uriString = code)
                    Haptics.success()
                    status = if (conversation.type == ConversationType.group) {
                        context.getString(R.string.shell_pairing_joined_group, conversation.title)
                    } else {
                        context.getString(R.string.shell_pairing_added, conversation.title)
                    }
                    delay(1_000)
                    dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Haptics.error()
                    status = context.getString(R.string.shell_pairing_unrecognized)
                } finally {
                    isBusy = false
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val modeLabel = stringResource(R.string.shell_pairing_mode_picker)
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = modeLabel },
            ) {
                QRPairingSheet.Mode.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = mode == item,
                        onClick = { mode = item; Haptics.tap() },
                        shape = SegmentedButtonDefaults.itemShape(index, QRPairingSheet.Mode.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = AeroTheme.surface,
                            activeContentColor = AeroTheme.textPrimary,
                            inactiveContainerColor = AeroTheme.background,
                            inactiveContentColor = AeroTheme.textSecondary,
                            activeBorderColor = AeroTheme.onBackground.copy(alpha = 0.2f),
                            inactiveBorderColor = AeroTheme.onBackground.copy(alpha = 0.2f),
                        ),
                        icon = {},
                    ) {
                        Text(
                            when (item) {
                                QRPairingSheet.Mode.show -> stringResource(R.string.shell_pairing_mode_show)
                                QRPairingSheet.Mode.scan -> stringResource(R.string.shell_pairing_mode_scan)
                            },
                        )
                    }
                }
            }

            when (mode) {
                QRPairingSheet.Mode.show -> ShowSection(payload, group)
                QRPairingSheet.Mode.scan -> ScanSection(
                    isBusy = isBusy,
                    onCode = { handle(it) },
                    onError = { error ->
                        status = if (error == QRScannerError.permissionDenied) {
                            context.getString(R.string.shell_pairing_camera_denied)
                        } else {
                            context.getString(R.string.shell_pairing_camera_unavailable)
                        }
                    },
                )
            }

            status?.let {
                Text(
                    it,
                    style = AeroTheme.footnote,
                    fontWeight = FontWeight.SemiBold,
                    color = AeroTheme.accentEmerald,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ShowSection(payload: PairingPayload?, group: ConversationRecord?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        var image by remember { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(payload) {
            image = payload?.let { withContext(Dispatchers.Default) { QRCodeGenerator.pairingImage(it) } }
        }
        val bitmap = image
        if (bitmap != null) {
            QRImage(bitmap, stringResource(R.string.shell_pairing_qr_a11y))
        } else {
            Box(Modifier.height(292.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AeroTheme.accentCyan)
            }
        }
        Text(
            if (group != null) stringResource(R.string.shell_pairing_group_hint, group.title)
            else stringResource(R.string.shell_pairing_person_hint),
            style = AeroTheme.footnote,
            color = AeroTheme.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        // Invito via Nearby Share / Messaggi: link allo store + link di pairing, il compagno e' gia' collegato al primo avvio.
        if (payload != null) InviteShareButton(payload, group)
    }
}

@Composable
private fun ScanSection(isBusy: Boolean, onCode: (String) -> Unit, onError: (QRScannerError) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(360.dp), contentAlignment = Alignment.Center) {
        QRScannerView(
            onCode = onCode,
            onError = onError,
            modifier = Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(24.dp)),
        )
        Box(
            Modifier
                .size(220.dp)
                .border(3.dp, AeroTheme.accentCyan, RoundedCornerShape(AeroTheme.cornerRadius)),
        )
        if (isBusy) CircularProgressIndicator(color = AeroTheme.accentCyan)
    }
}

/** QR nitido (nearest neighbour) su fondo bianco arrotondato. */
@Composable
internal fun QRImage(bitmap: Bitmap, contentDescription: String?) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .padding(16.dp)
            .size(260.dp),
    )
}

/** QR statico verso gli store, da mostrare al gate finche' c'e' ancora rete. */
@Composable
fun StoreQRSheet(onDismiss: () -> Unit) {
    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.shell_store_qr_title),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.shell_close), dismiss) },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 24.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            var image by remember { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(Unit) {
                image = withContext(Dispatchers.Default) { QRCodeGenerator.image(QRCodeGenerator.storeLandingURL) }
            }
            image?.let { QRImage(it, null) }
            Text(
                stringResource(R.string.shell_store_qr_hint),
                style = AeroTheme.footnote,
                color = AeroTheme.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Text(QRCodeGenerator.storeLandingURL, style = AeroTheme.monospaced(), color = AeroTheme.accentCyan)
        }
    }
}
