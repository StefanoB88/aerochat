package com.stefanobrandi.aerochat.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stefanobrandi.aerochat.ui.AeroTheme
import kotlinx.coroutines.launch

/**
 * Foglio modale a tutta altezza con barra "inline" in stile iOS (`NavigationStack` + toolbar dentro
 * `.sheet`): azione a sinistra (`cancellationAction`), titolo centrato, azione a destra
 * (`confirmationAction`). Il contenuto riceve `dismiss()` che chiude con animazione e poi chiama
 * [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AeroSheet(
    onDismiss: () -> Unit,
    title: String,
    leading: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    trailing: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = remember(sheetState) {
        { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AeroTheme.background,
        contentColor = AeroTheme.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp)) {
                Box(Modifier.align(Alignment.CenterStart)) { leading?.invoke(dismiss) }
                Text(
                    title,
                    style = AeroTheme.headline,
                    fontWeight = FontWeight.SemiBold,
                    color = AeroTheme.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
                )
                Box(Modifier.align(Alignment.CenterEnd)) { trailing?.invoke(dismiss) }
            }
            content(dismiss)
        }
    }
}

/** Pulsante testuale della barra del foglio (Annulla / Salva / Chiudi). */
@Composable
fun SheetBarButton(text: String, onClick: () -> Unit, enabled: Boolean = true, bold: Boolean = false) {
    HapticButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(text, style = AeroTheme.body, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}
