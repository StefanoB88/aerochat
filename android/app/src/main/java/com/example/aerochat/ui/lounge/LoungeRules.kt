package com.example.aerochat.ui.lounge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.aerochat.R
import com.example.aerochat.ui.AeroTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.example.aerochat.ui.components.Haptics
import com.example.aerochat.ui.components.HapticButton
import com.example.aerochat.ui.components.SheetBarButton
import com.example.aerochat.ui.components.hapticOnTap
import com.example.aerochat.ui.settings.LegalDocument
import com.example.aerochat.ui.settings.LegalSheet

private data class LoungeRule(val icon: ImageVector, val title: Int, val detail: Int)

private val loungeRules = listOf(
    LoungeRule(Icons.Outlined.People, R.string.safety_rules_1_t, R.string.safety_rules_1_d),
    LoungeRule(Icons.Outlined.VisibilityOff, R.string.safety_rules_2_t, R.string.safety_rules_2_d),
    LoungeRule(Icons.Outlined.LinkOff, R.string.safety_rules_3_t, R.string.safety_rules_3_d),
    LoungeRule(Icons.Outlined.Lock, R.string.safety_rules_4_t, R.string.safety_rules_4_d),
    LoungeRule(Icons.Outlined.Timer, R.string.safety_rules_5_t, R.string.safety_rules_5_d),
)

/**
 * Regole della Cabina Pubblica (Swift `LoungeRulesSheet`), mostrate alla prima apertura (accettazione
 * obbligatoria: il foglio non si chiude senza "Accetto le regole") e dal pulsante info. Richiesto dalle
 * norme degli store sui contenuti generati dagli utenti.
 *
 * @param onAccept chiamato al tocco del pulsante principale (chi chiama salva `AppSettings.acceptLoungeRules()`
 *   se [requiresAcceptance]); subito dopo il foglio si chiude e chiama [onDismiss].
 * @param onDismiss rimuove il foglio dalla composizione. Con [requiresAcceptance] lo swipe/back non chiude:
 *   chi chiama decide cosa fare (es. tornare indietro dalla Cabina) se l'utente esce senza accettare.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoungeRulesSheet(requiresAcceptance: Boolean, onAccept: () -> Unit, onDismiss: () -> Unit) {
    var showTerms by remember { mutableStateOf(false) }
    // Con accettazione obbligatoria il foglio non si chiude trascinandolo (Swift `.interactiveDismissDisabled`):
    // solo il pulsante "Accetto" (che abilita la chiusura) o il tasto indietro.
    var allowHide by remember { mutableStateOf(!requiresAcceptance) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || allowHide },
    )
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        allowHide = true
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AeroTheme.background,
        contentColor = AeroTheme.textPrimary,
        dragHandle = if (requiresAcceptance) null else ({ BottomSheetDefaults.DragHandle() }),
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp)) {
            if (!requiresAcceptance) {
                Box(Modifier.align(Alignment.CenterStart)) { SheetBarButton(stringResource(R.string.safety_close), dismiss) }
            }
            Text(
                stringResource(R.string.safety_rules_title),
                style = AeroTheme.headline,
                color = AeroTheme.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(
                Icons.Filled.AirplanemodeActive,
                contentDescription = null,
                tint = AeroTheme.accentCyan,
                modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
            )
            Text(
                stringResource(R.string.safety_rules_welcome),
                style = AeroTheme.title(26f),
                color = AeroTheme.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.safety_rules_intro),
                style = AeroTheme.footnote,
                color = AeroTheme.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            loungeRules.forEach { rule ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(rule.icon, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(26.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(rule.title), style = AeroTheme.body, fontWeight = FontWeight.SemiBold, color = AeroTheme.textPrimary)
                        Text(stringResource(rule.detail), style = AeroTheme.footnote, color = AeroTheme.textSecondary)
                    }
                }
            }
            HapticButton(
                onClick = { showTerms = true },
                tint = AeroTheme.accentCyan,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.safety_rules_read_terms), style = AeroTheme.footnote, fontWeight = FontWeight.SemiBold)
            }
        }
        Button(
            onClick = {
                Haptics.success()
                onAccept()
                dismiss()
            },
            colors = ButtonDefaults.buttonColors(containerColor = AeroTheme.accentCyan, contentColor = Color.Black),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .hapticOnTap()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Text(
                stringResource(if (requiresAcceptance) R.string.safety_rules_accept else R.string.safety_rules_understood),
                style = AeroTheme.title(18f, FontWeight.Bold),
            )
        }
    }
    if (showTerms) {
        LegalSheet(document = LegalDocument.terms, onDismiss = { showTerms = false })
    }
}
