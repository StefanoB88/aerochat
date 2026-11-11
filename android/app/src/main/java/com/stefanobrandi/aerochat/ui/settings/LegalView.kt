package com.stefanobrandi.aerochat.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.moderation.AppLinks
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.AeroSheet
import com.stefanobrandi.aerochat.ui.components.HapticButton
import com.stefanobrandi.aerochat.ui.components.SheetBarButton
import com.stefanobrandi.aerochat.ui.safety.openExternalLink

/** Documenti legali consultabili offline (a bordo non c'e' rete): Termini di utilizzo e Informativa privacy. */
enum class LegalDocument(@StringRes val titleRes: Int, val onlineURL: String) {
    terms(R.string.safety_legal_terms, AppLinks.termsOfUse),
    privacy(R.string.safety_legal_privacy, AppLinks.privacyPolicy);

    /** Sezione: titolo e testo. */
    data class Section(@StringRes val heading: Int, @StringRes val body: Int)

    val sections: List<Section>
        get() = when (this) {
            terms -> listOf(
                Section(R.string.safety_terms_1_h, R.string.safety_terms_1_b),
                Section(R.string.safety_terms_2_h, R.string.safety_terms_2_b),
                Section(R.string.safety_terms_3_h, R.string.safety_terms_3_b),
                Section(R.string.safety_terms_4_h, R.string.safety_terms_4_b),
                Section(R.string.safety_terms_5_h, R.string.safety_terms_5_b),
                Section(R.string.safety_terms_6_h, R.string.safety_terms_6_b),
                Section(R.string.safety_terms_7_h, R.string.safety_terms_7_b),
            )
            privacy -> listOf(
                Section(R.string.safety_privacy_1_h, R.string.safety_privacy_1_b),
                Section(R.string.safety_privacy_2_h, R.string.safety_privacy_2_b),
                Section(R.string.safety_privacy_3_h, R.string.safety_privacy_3_b),
                Section(R.string.safety_privacy_4_h, R.string.safety_privacy_4_b),
                Section(R.string.safety_privacy_5_h, R.string.safety_privacy_5_b),
                Section(R.string.safety_privacy_6_h, R.string.safety_privacy_6_b),
                Section(R.string.safety_privacy_7_h, R.string.safety_privacy_7_b),
            )
        }
}

/** Documento legale come foglio modale autonomo (Swift `LegalView`), es. dalle regole della Cabina. */
@Composable
fun LegalSheet(document: LegalDocument, onDismiss: () -> Unit) {
    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(document.titleRes),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.safety_close), dismiss) },
    ) {
        LegalContent(document)
    }
}

/** Contenuto del documento, riutilizzato dal foglio modale e dalla navigazione delle Impostazioni. */
@Composable
fun LegalContent(document: LegalDocument, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Text(stringResource(R.string.safety_legal_updated), style = AeroTheme.caption, color = AeroTheme.textSecondary)
        document.sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(section.heading), style = AeroTheme.title(17f, FontWeight.SemiBold), color = AeroTheme.textPrimary)
                val body = if (section.body == R.string.safety_privacy_7_b) {
                    stringResource(section.body, AppLinks.supportEmail)
                } else {
                    stringResource(section.body)
                }
                Text(body, style = AeroTheme.footnote, color = AeroTheme.textSecondary)
            }
        }
        HapticButton(onClick = { openExternalLink(context, document.onlineURL) }, tint = AeroTheme.accentCyan) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                Text(stringResource(R.string.safety_legal_online), style = AeroTheme.footnote, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
