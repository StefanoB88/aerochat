package com.stefanobrandi.aerochat.ui.safety

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.moderation.AppLinks
import com.stefanobrandi.aerochat.data.records.ReportRecord
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.Haptics

// Mattoni dei "Form" in stile iOS (`List` `.insetGrouped`): intestazione, card della sezione, righe e piè di pagina.
// Condivisi da segnalazione, impostazioni e utenti bloccati.

/** Intestazione di sezione (maiuscoletto grigio come `Section("...")`). */
@Composable
internal fun FormHeader(text: String) {
    Text(
        text.uppercase(),
        style = AeroTheme.caption,
        color = AeroTheme.textSecondary,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** Testo sotto una sezione (`footer:`). */
@Composable
internal fun FormFooter(text: String) {
    Text(
        text,
        style = AeroTheme.footnote,
        color = AeroTheme.textSecondary,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
    )
}

/** Card della sezione: le righe sono separate da un divisore sottile. */
@Composable
internal fun FormSection(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(12.dp)),
        content = content,
    )
}

@Composable
internal fun FormDivider() {
    HorizontalDivider(color = AeroTheme.onBackground.copy(alpha = 0.08f), modifier = Modifier.padding(start = 52.dp))
}

/** Riga toccabile con icona, titolo, valore facoltativo e chevron (`NavigationLink` / `Button`). */
@Composable
internal fun FormRow(
    title: String,
    icon: ImageVector?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    value: String? = null,
    tint: Color = AeroTheme.textPrimary,
    showsChevron: Boolean = onClick != null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button) {
                    Haptics.tap()
                    onClick()
                } else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(title, style = AeroTheme.body, color = tint, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = AeroTheme.body, color = AeroTheme.textSecondary)
        }
        if (showsChevron) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = AeroTheme.textSecondary,
                modifier = Modifier.padding(start = 4.dp).size(20.dp),
            )
        }
    }
}

/** Riga con interruttore (`Toggle` con tinta ciano). */
@Composable
internal fun FormToggle(title: String, icon: ImageVector?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Switch) {
                Haptics.tap()
                onCheckedChange(!checked)
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(22.dp))
        }
        Text(title, style = AeroTheme.body, color = AeroTheme.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = {
                Haptics.tap()
                onCheckedChange(it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = AeroTheme.accentCyan,
            ),
        )
    }
}

/**
 * Apre l'app email con il rapporto indirizzato al team di sicurezza ([AppLinks.safetyEmail]).
 * Restituisce `false` se sul telefono non c'e' nessuna app email.
 */
fun openReportEmail(context: Context, report: ReportRecord): Boolean {
    val subject = context.getString(R.string.safety_email_subject, report.reason.title(context))
    val body = report.emailBody(context)
    val uri = Uri.parse("mailto:${AppLinks.safetyEmail}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
    val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
        // Alcuni client ignorano i parametri della mailto: oggetto e testo anche come extra.
        putExtra(Intent.EXTRA_EMAIL, arrayOf(AppLinks.safetyEmail))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Apre un link esterno nel browser; `false` se nessuna app lo gestisce. */
fun openExternalLink(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}
