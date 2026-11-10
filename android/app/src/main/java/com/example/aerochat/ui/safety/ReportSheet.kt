package com.example.aerochat.ui.safety

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.aerochat.R
import com.example.aerochat.app.LocalAppEnvironment
import com.example.aerochat.core.moderation.AppLinks
import com.example.aerochat.data.records.MessageRecord
import com.example.aerochat.data.records.ReportReason
import com.example.aerochat.data.records.ReportRecord
import com.example.aerochat.ui.AeroTheme
import com.example.aerochat.ui.components.AeroSheet
import com.example.aerochat.ui.components.Haptics
import com.example.aerochat.ui.components.SheetBarButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Segnalazione di un messaggio: motivo, dettagli facoltativi, blocco del mittente e inoltro via email
 * al team di sicurezza (la mesh non ha un server a cui inviare il rapporto). Si presenta da solo come
 * foglio modale; [onDismiss] lo rimuove dalla composizione.
 *
 * @param onReported chiamato a segnalazione salvata: rapporto e se il mittente e' stato bloccato.
 */
@Composable
fun ReportSheet(
    message: MessageRecord,
    onDismiss: () -> Unit,
    onReported: (ReportRecord, Boolean) -> Unit = { _, _ -> },
) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var reason by remember { mutableStateOf(ReportReason.harassment) }
    var details by remember { mutableStateOf("") }
    var blockSender by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<ReportRecord?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (isSaving || saved != null) return
        isSaving = true
        errorMessage = null
        scope.launch {
            try {
                val report = environment.report(message = message, reason = reason, details = details, blockSender = blockSender)
                saved = report
                Haptics.success()
                onReported(report, blockSender)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error()
                errorMessage = context.getString(
                    R.string.safety_report_not_saved, e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.toString(),
                )
            } finally {
                isSaving = false
            }
        }
    }

    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.safety_report_title),
        leading = { dismiss ->
            SheetBarButton(stringResource(if (saved == null) R.string.safety_cancel else R.string.safety_close), dismiss)
        },
        trailing = {
            if (saved == null) {
                if (isSaving) {
                    CircularProgressIndicator(
                        color = AeroTheme.accentCyan, strokeWidth = 2.dp,
                        modifier = Modifier.padding(horizontal = 16.dp).size(20.dp),
                    )
                } else {
                    SheetBarButton(stringResource(R.string.safety_send), onClick = ::submit, bold = true)
                }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            FormHeader(stringResource(R.string.safety_reported_message))
            FormSection {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    message.senderNickname?.let {
                        Text(it, style = AeroTheme.captionSemibold, color = AeroTheme.accentCyan)
                    }
                    Text(
                        message.text ?: stringResource(R.string.safety_media_content),
                        style = AeroTheme.body,
                        color = AeroTheme.textPrimary,
                        maxLines = 4,
                    )
                }
            }

            FormHeader(stringResource(R.string.safety_reason))
            FormSection {
                ReportReason.entries.forEachIndexed { index, item ->
                    if (index > 0) FormDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = reason == item,
                                enabled = saved == null,
                                role = Role.RadioButton,
                                onClick = {
                                    Haptics.tap()
                                    reason = item
                                },
                            )
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = reason == item,
                            onClick = null,
                            enabled = saved == null,
                            colors = RadioButtonDefaults.colors(selectedColor = AeroTheme.accentCyan),
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        Text(item.title(context), style = AeroTheme.body, color = AeroTheme.textPrimary)
                    }
                }
            }

            FormHeader(stringResource(R.string.safety_details))
            FormSection {
                TextField(
                    value = details,
                    onValueChange = { details = it.take(500) },
                    enabled = saved == null,
                    placeholder = { Text(stringResource(R.string.safety_details_placeholder), color = AeroTheme.textSecondary) },
                    minLines = 2,
                    maxLines = 5,
                    textStyle = AeroTheme.body,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = AeroTheme.accentCyan,
                        focusedTextColor = AeroTheme.textPrimary,
                        unfocusedTextColor = AeroTheme.textPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(20.dp))
            FormSection {
                FormToggle(
                    title = stringResource(
                        R.string.safety_also_block,
                        message.senderNickname ?: stringResource(R.string.safety_this_user),
                    ),
                    icon = null,
                    checked = blockSender,
                    onCheckedChange = { if (saved == null) blockSender = it },
                )
            }
            FormFooter(stringResource(R.string.safety_block_footer))

            saved?.let { report ->
                Spacer(Modifier.height(20.dp))
                FormSection {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) {
                                Haptics.tap()
                                if (!openReportEmail(context, report)) {
                                    errorMessage = context.getString(R.string.safety_no_email_app, AppLinks.safetyEmail)
                                }
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(Icons.Outlined.Email, contentDescription = null, tint = AeroTheme.accentCyan)
                        Text(stringResource(R.string.safety_send_email), style = AeroTheme.body, color = AeroTheme.accentCyan)
                    }
                }
                FormFooter(stringResource(R.string.safety_saved_footer))
            }

            errorMessage?.let {
                Text(
                    it,
                    style = AeroTheme.footnote,
                    color = Color.Red,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                )
            }
        }
    }
}
