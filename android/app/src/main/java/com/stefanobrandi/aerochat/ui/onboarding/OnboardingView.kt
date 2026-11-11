package com.stefanobrandi.aerochat.ui.onboarding

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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
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
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.fromHexString
import com.stefanobrandi.aerochat.ui.components.hapticOnTap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Swift `OnboardingView.palette`: colori avatar proposti (`RRGGBB`). */
object OnboardingView {
    val palette = listOf("00E5FF", "10B981", "F59E0B", "EC4899", "8B5CF6", "F97316")
}

/**
 * Primo avvio: tre schermate chiare ("Funziona senza internet", "I messaggi passano da telefono a
 * telefono", "Piu' siete, piu' arriva lontano"), poi nickname e colore come sempre. Alla fine si puo'
 * entrare subito o "Prova subito" con il passeggero virtuale (chat demo solo locale).
 */
@Composable
fun OnboardingView() {
    var showProfile by rememberSaveable { mutableStateOf(false) }
    if (showProfile) {
        OnboardingProfile()
    } else {
        OnboardingIntro(onFinish = { showProfile = true })
    }
}

/** Una delle tre schermate introduttive. */
private class IntroPage(val icon: ImageVector, val title: Int, val body: Int)

private val introPages = listOf(
    IntroPage(Icons.Filled.WifiOff, R.string.nearby_onboarding_1_title, R.string.nearby_onboarding_1_body),
    IntroPage(Icons.Filled.SettingsInputAntenna, R.string.nearby_onboarding_2_title, R.string.nearby_onboarding_2_body),
    IntroPage(Icons.Filled.Groups, R.string.nearby_onboarding_3_title, R.string.nearby_onboarding_3_body),
)

@Composable
private fun OnboardingIntro(onFinish: () -> Unit) {
    val pagerState = rememberPagerState { introPages.size }
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == introPages.lastIndex
    Column(
        Modifier
            .fillMaxSize()
            .background(AeroTheme.background)
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FlightTakeoff, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.app_name), style = AeroTheme.title(22f), color = AeroTheme.textPrimary)
            Spacer(Modifier.weight(1f))
            if (!isLast) {
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.nearby_onboarding_skip), color = AeroTheme.textSecondary)
                }
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
            val page = introPages[index]
            Column(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(132.dp).clip(CircleShape).background(AeroTheme.accentCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(page.icon, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(64.dp))
                }
                Spacer(Modifier.height(32.dp))
                Text(
                    stringResource(page.title),
                    style = AeroTheme.title(30f),
                    color = AeroTheme.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(page.body),
                    style = AeroTheme.body,
                    color = AeroTheme.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(introPages.size) { index ->
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (index == pagerState.currentPage) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == pagerState.currentPage) AeroTheme.accentCyan else AeroTheme.textSecondary.copy(alpha = 0.4f),
                        ),
                )
            }
        }
        PrimaryButton(
            title = stringResource(if (isLast) R.string.nearby_onboarding_start else R.string.nearby_onboarding_next),
            enabled = true,
            isBusy = false,
        ) {
            if (isLast) onFinish() else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
        }
    }
}

/** Nickname e colore dell'avatar (onboarding zero-account): chiavi nel Keystore, poi Home o prova. */
@Composable
private fun OnboardingProfile() {
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

    /** Salva il profilo; con [tryDemo] apre subito la chat con il passeggero virtuale. */
    fun save(tryDemo: Boolean) {
        if (trimmed.length < 2 || isSaving) return
        isSaving = true
        scope.launch {
            try {
                if (tryDemo) {
                    environment.completeOnboardingAndTryDemo(nickname = trimmed, avatarColorHex = colorHex)
                } else {
                    environment.completeOnboarding(nickname = trimmed, avatarColorHex = colorHex)
                }
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
                    onGo = { save(tryDemo = false) },
                )
                AvatarColorPicker(selected = colorHex, onSelect = { colorHex = it }, circleSize = 34)
            }

            errorMessage?.let {
                Text(it, style = AeroTheme.footnote, color = Color.Red)
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    title = stringResource(R.string.shell_onboarding_board),
                    enabled = trimmed.length >= 2 && !isSaving,
                    isBusy = isSaving,
                ) { save(tryDemo = false) }
                // "Prova subito": una chat di prova con il passeggero virtuale, solo su questo telefono.
                TextButton(
                    onClick = { save(tryDemo = true) },
                    enabled = trimmed.length >= 2 && !isSaving,
                    modifier = Modifier.fillMaxWidth().hapticOnTap(),
                ) {
                    Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = AeroTheme.accentEmerald, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.nearby_onboarding_try), color = AeroTheme.accentEmerald, style = AeroTheme.headline)
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

@Composable
private fun PrimaryButton(title: String, enabled: Boolean, isBusy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
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
            if (isBusy) {
                CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
            Text(title, style = AeroTheme.title(18f, FontWeight.Bold))
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
