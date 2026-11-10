package com.example.aerochat.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.aerochat.R
import com.example.aerochat.app.LocalAppEnvironment
import com.example.aerochat.ui.AeroTheme
import com.example.aerochat.ui.components.Haptics
import com.example.aerochat.ui.components.fromHexString
import com.example.aerochat.ui.components.hapticOnTap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Swift `OnboardingView.palette`: colori avatar proposti (`RRGGBB`). */
object OnboardingView {
    val palette = listOf("00E5FF", "10B981", "F59E0B", "EC4899", "8B5CF6", "F97316")
}

/** Onboarding zero-account in 5 secondi: nickname → chiavi nel Keystore → Home. */
@Composable
fun OnboardingView() {
    val environment = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var nickname by rememberSaveable { mutableStateOf("") }
    var colorHex by rememberSaveable { mutableStateOf(OnboardingView.palette[0]) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val trimmed = nickname.trim()
    val context = androidx.compose.ui.platform.LocalContext.current

    fun save() {
        if (trimmed.length < 2 || isSaving) return
        isSaving = true
        scope.launch {
            try {
                environment.completeOnboarding(nickname = trimmed, avatarColorHex = colorHex)
                Haptics.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.shell_profile_save_failed, e.localizedMessage ?: e.toString())
                Haptics.error()
            } finally {
                isSaving = false
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(AeroTheme.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                focusManager.clearFocus()
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.FlightTakeoff,
                    contentDescription = null,
                    tint = AeroTheme.accentCyan,
                    modifier = Modifier.size(52.dp),
                )
                Text(stringResource(R.string.app_name), style = AeroTheme.title(40f), color = AeroTheme.textPrimary)
                Text(
                    stringResource(R.string.shell_onboarding_tagline),
                    style = AeroTheme.body,
                    color = AeroTheme.textSecondary,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.shell_onboarding_question),
                    style = AeroTheme.captionSemibold,
                    color = AeroTheme.textSecondary,
                )
                NicknameField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    modifier = Modifier.focusRequester(focusRequester),
                    onGo = { save() },
                )
                AvatarColorPicker(selected = colorHex, onSelect = { colorHex = it }, circleSize = 34)
            }

            errorMessage?.let {
                Text(it, style = AeroTheme.footnote, color = Color.Red)
            }

            Button(
                onClick = { save() },
                enabled = trimmed.length >= 2 && !isSaving,
                modifier = Modifier.fillMaxWidth().hapticOnTap(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AeroTheme.accentCyan,
                    contentColor = Color.Black,
                    disabledContainerColor = AeroTheme.accentCyan.copy(alpha = 0.3f),
                    disabledContentColor = Color.Black.copy(alpha = 0.6f),
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isSaving) {
                        CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    }
                    Text(stringResource(R.string.shell_onboarding_board), style = AeroTheme.title(18f, FontWeight.Bold))
                }
            }

            Text(
                stringResource(R.string.shell_onboarding_keys_note),
                style = AeroTheme.caption,
                color = AeroTheme.textSecondary,
            )
        }
    }
}

/** Campo nickname comune a onboarding e profilo (superficie grafite, angoli 16, titolo rounded 20). */
@Composable
internal fun NicknameField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onGo: (() -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(
                stringResource(R.string.shell_nickname_placeholder),
                style = AeroTheme.title(20f, FontWeight.SemiBold),
                color = AeroTheme.textSecondary.copy(alpha = 0.5f),
            )
        },
        singleLine = true,
        textStyle = AeroTheme.title(20f, FontWeight.SemiBold).copy(color = AeroTheme.textPrimary),
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = if (onGo != null) ImeAction.Go else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo?.invoke() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AeroTheme.surface,
            unfocusedContainerColor = AeroTheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = AeroTheme.accentCyan,
        ),
    )
}

/** Riga di cerchi colorati della palette avatar (bordo bianco sul selezionato). */
@Composable
internal fun AvatarColorPicker(selected: String, onSelect: (String) -> Unit, circleSize: Int, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (hex in OnboardingView.palette) {
            val label = stringResource(R.string.shell_avatar_color_a11y, hex)
            Box(
                Modifier
                    .size(circleSize.dp)
                    .clip(CircleShape)
                    .background(Color.fromHexString(hex) ?: AeroTheme.accentCyan)
                    .let { if (selected == hex) it.border(3.dp, AeroTheme.textPrimary, CircleShape) else it }
                    .clickable(enabled = enabled) {
                        onSelect(hex)
                        Haptics.impact()
                    }
                    .semantics {
                        contentDescription = label
                        this.selected = selected == hex
                    },
            )
        }
    }
}
