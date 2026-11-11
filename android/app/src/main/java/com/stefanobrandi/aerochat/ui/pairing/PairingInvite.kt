package com.stefanobrandi.aerochat.ui.pairing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.crypto.PairingPayload
import com.stefanobrandi.aerochat.core.qr.QRCodeGenerator
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.Haptics

/** Inviti via app di sistema (Swift `QRPairingSheet.inviteText` + `ShareLink`). */
object PairingInvite {

    /**
     * Testo dell'invito: finche' c'e' rete scarica l'app, poi il link `aeromesh://` apre AeroChat e
     * collega i due telefoni (o fa entrare nel gruppo, se [group] non e' nullo).
     */
    fun inviteText(context: Context, payload: PairingPayload, group: ConversationRecord?): String {
        val intro = group?.let { context.getString(R.string.shell11_invite_intro_group, it.title) }
            ?: context.getString(R.string.shell11_invite_intro_person)
        return buildString {
            appendLine(intro)
            appendLine("1) ${context.getString(R.string.shell11_invite_download)}: ${QRCodeGenerator.storeLandingURL}")
            append("2) ${context.getString(R.string.shell11_invite_open_link)}: ${payload.uriString}")
        }
    }

    /** Foglio di condivisione di sistema (Nearby Share, Messaggi, WhatsApp, ...) con il testo dell'invito. */
    fun share(context: Context, payload: PairingPayload, group: ConversationRecord?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, inviteText(context, payload, group))
            group?.let { putExtra(Intent.EXTRA_SUBJECT, it.title) }
        }
        val chooser = Intent.createChooser(send, null)
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Log.w("PairingInvite", "nessuna app per condividere: $e")
        }
    }
}

/** Pulsante "Invita un compagno (Nearby Share, Messaggi…)" / "Invita nel gruppo (...)". */
@Composable
fun InviteShareButton(payload: PairingPayload, group: ConversationRecord?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            Haptics.tap()
            PairingInvite.share(context, payload, group)
        },
        modifier = modifier,
        border = BorderStroke(1.dp, AeroTheme.accentCyan.copy(alpha = 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AeroTheme.accentCyan),
    ) {
        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (group == null) R.string.shell11_invite_person else R.string.shell11_invite_group),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Esito di un link di invito `aeromesh://` aperto da fuori (Swift `.alert("AeroChat", ...)` su
 * `incomingLinkMessage` in MainTabView). Va composto sopra la UI radice.
 */
@Composable
fun IncomingLinkAlert() {
    val environment = LocalAppEnvironment.current
    val message by environment.incomingLinkMessage.collectAsStateWithLifecycle()
    val text = message ?: return
    AlertDialog(
        onDismissRequest = { environment.dismissIncomingLinkMessage() },
        containerColor = AeroTheme.surface,
        titleContentColor = AeroTheme.textPrimary,
        textContentColor = AeroTheme.textSecondary,
        title = { Text(stringResource(R.string.app_name)) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { environment.dismissIncomingLinkMessage() }) {
                Text(stringResource(R.string.shell_ok), color = AeroTheme.accentCyan)
            }
        },
    )
}
