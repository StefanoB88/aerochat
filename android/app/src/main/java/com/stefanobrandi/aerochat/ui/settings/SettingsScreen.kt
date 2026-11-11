package com.stefanobrandi.aerochat.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Shop
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Visibility
import android.content.Intent
import android.provider.Settings as SystemSettings
import androidx.core.app.NotificationManagerCompat
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.BuildConfig
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.moderation.AppLinks
import com.stefanobrandi.aerochat.core.moderation.AppSettings
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.chat.InfoBanner
import com.stefanobrandi.aerochat.ui.components.AvatarView
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.SheetBarButton
import com.stefanobrandi.aerochat.ui.onboarding.ProfileEditSheet
import com.stefanobrandi.aerochat.ui.paywall.PaywallView
import com.stefanobrandi.aerochat.ui.safety.FormDivider
import com.stefanobrandi.aerochat.ui.safety.FormFooter
import com.stefanobrandi.aerochat.ui.safety.FormHeader
import com.stefanobrandi.aerochat.ui.safety.FormRow
import com.stefanobrandi.aerochat.ui.safety.FormSection
import com.stefanobrandi.aerochat.ui.safety.FormToggle
import com.stefanobrandi.aerochat.ui.safety.openExternalLink
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Termini di servizio di Google Play (equivalente Android della EULA standard di Apple citata su iOS). */
private const val playTermsURL = "https://play.google.com/about/play-terms/"

/**
 * Pagine navigabili dentro le Impostazioni (Swift `NavigationLink`): cinque gruppi (Profilo, Privacy,
 * Notifiche, Abbonamento, Info e legale) e le loro sottopagine. [parent] = dove porta "Indietro".
 */
private enum class SettingsPage(val parent: SettingsPage?) {
    main(null),
    profile(main), privacyGroup(main), notifications(main), subscription(main), about(main),
    blocked(privacyGroup), reports(privacyGroup), terms(about), privacy(about),
}

/**
 * Impostazioni (Swift `SettingsView`): profilo, pass, viaggio, sicurezza (filtro, foto sfocate, bloccati,
 * segnalazioni), legale e informazioni. Schermata a tutto schermo con navigazione interna; [onDismiss]
 * la rimuove dalla composizione.
 *
 * Le righe di "Viaggio ed eventi" (cartolina di volo, radar, emergenza) compaiono solo se chi chiama
 * passa la rispettiva azione: le schermate appartengono ad altri pacchetti (ui/flight, ui/lounge).
 */
@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    onOpenFlightCard: (() -> Unit)? = null,
    onOpenRadar: (() -> Unit)? = null,
    onOpenEmergency: (() -> Unit)? = null,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.main) }
    var info by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        BackHandler(enabled = page != SettingsPage.main) { page = page.parent ?: SettingsPage.main }
        Box(Modifier.fillMaxSize().background(AeroTheme.background)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp)) {
                    Box(Modifier.align(Alignment.CenterStart)) {
                        if (page == SettingsPage.main) {
                            SheetBarButton(stringResource(R.string.safety_close), onDismiss)
                        } else {
                            IconButton(onClick = {
                                Haptics.tap()
                                page = page.parent ?: SettingsPage.main
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.safety_back),
                                    tint = AeroTheme.accentCyan,
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(
                            when (page) {
                                SettingsPage.main -> R.string.safety_settings_title
                                SettingsPage.profile -> R.string.nearby_settings_profile
                                SettingsPage.privacyGroup -> R.string.nearby_settings_privacy
                                SettingsPage.notifications -> R.string.nearby_settings_notifications
                                SettingsPage.subscription -> R.string.nearby_settings_subscription
                                SettingsPage.about -> R.string.nearby_settings_about
                                SettingsPage.blocked -> R.string.safety_settings_blocked
                                SettingsPage.reports -> R.string.safety_settings_reports
                                SettingsPage.terms -> R.string.safety_legal_terms
                                SettingsPage.privacy -> R.string.safety_legal_privacy
                            },
                        ),
                        style = AeroTheme.headline,
                        color = AeroTheme.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
                    )
                }
                when (page) {
                    SettingsPage.main -> SettingsMain(onNavigate = { page = it })
                    SettingsPage.profile -> SettingsProfile(
                        onOpenFlightCard = onOpenFlightCard,
                        onOpenRadar = onOpenRadar,
                        onOpenEmergency = onOpenEmergency,
                    )
                    SettingsPage.privacyGroup -> SettingsPrivacy(onNavigate = { page = it })
                    SettingsPage.notifications -> SettingsNotifications()
                    SettingsPage.subscription -> SettingsSubscription()
                    SettingsPage.about -> SettingsAbout(onNavigate = { page = it }, onMessage = { info = it })
                    SettingsPage.blocked -> BlockedUsersContent(Modifier.navigationBarsPadding())
                    SettingsPage.reports -> ReportsListContent(onMessage = { info = it }, modifier = Modifier.navigationBarsPadding())
                    SettingsPage.terms -> LegalContent(LegalDocument.terms)
                    SettingsPage.privacy -> LegalContent(LegalDocument.privacy)
                }
            }
            InfoBanner(message = info, onExpire = { info = null }, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

/** Pagina principale: il profilo in cima e cinque gruppi (meno righe, stesse funzioni). */
@Composable
private fun SettingsMain(onNavigate: (SettingsPage) -> Unit) {
    val environment = LocalAppEnvironment.current
    // Ricomposizione a profilo / pass aggiornati.
    environment.identityRecord.collectAsStateWithLifecycle()
    environment.entitlementState.collectAsStateWithLifecycle()
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        FormSection {
            ProfileHeaderRow(onClick = { onNavigate(SettingsPage.profile) })
        }
        Spacer(Modifier.height(16.dp))
        FormSection {
            FormRow(
                title = stringResource(R.string.nearby_settings_privacy),
                icon = Icons.Outlined.Lock,
                value = stringResource(if (settings.isNearbyVisible) R.string.nearby_settings_visible_short else R.string.nearby_settings_hidden_short),
                onClick = { onNavigate(SettingsPage.privacyGroup) },
            )
            FormDivider()
            FormRow(
                title = stringResource(R.string.nearby_settings_notifications),
                icon = Icons.Outlined.Notifications,
                onClick = { onNavigate(SettingsPage.notifications) },
            )
            FormDivider()
            val isPro = environment.isPro
            FormRow(
                title = stringResource(R.string.nearby_settings_subscription),
                icon = if (isPro) Icons.Filled.ConfirmationNumber else Icons.Outlined.ConfirmationNumber,
                value = if (isPro) stringResource(R.string.nearby_settings_pro_active) else null,
                onClick = { onNavigate(SettingsPage.subscription) },
            )
            FormDivider()
            FormRow(
                title = stringResource(R.string.nearby_settings_about),
                icon = Icons.Outlined.Info,
                onClick = { onNavigate(SettingsPage.about) },
            )
        }
    }
}

/** Avatar, nickname e "Modifica profilo". */
@Composable
private fun ProfileHeaderRow(onClick: () -> Unit) {
    val environment = LocalAppEnvironment.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) {
                Haptics.tap()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarView(imageData = environment.avatarImage, colorHex = environment.avatarColorHex, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(environment.nickname, style = AeroTheme.title(18f, FontWeight.SemiBold), color = AeroTheme.textPrimary)
            Text(stringResource(R.string.safety_settings_edit_profile), style = AeroTheme.caption, color = AeroTheme.textSecondary)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AeroTheme.textSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingsPageColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
        content = content,
    )
}

/** Profilo: modifica del profilo, risposte rapide e strumenti di viaggio (cartolina, radar, emergenza). */
@Composable
private fun SettingsProfile(
    onOpenFlightCard: (() -> Unit)?,
    onOpenRadar: (() -> Unit)?,
    onOpenEmergency: (() -> Unit)?,
) {
    val environment = LocalAppEnvironment.current
    environment.identityRecord.collectAsStateWithLifecycle()
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    var showProfile by remember { mutableStateOf(false) }
    SettingsPageColumn {
        Spacer(Modifier.height(8.dp))
        FormSection { ProfileHeaderRow(onClick = { showProfile = true }) }
        FormHeader(stringResource(R.string.safety_settings_travel))
        FormSection {
            val rows = listOfNotNull(
                onOpenFlightCard?.let { Triple(R.string.safety_settings_flight_card, Icons.Outlined.Style, it) },
                onOpenRadar?.let { Triple(R.string.safety_settings_radar, Icons.Outlined.Radar, it) },
                onOpenEmergency?.let { Triple(R.string.safety_settings_emergency, Icons.Outlined.WarningAmber, it) },
            )
            rows.forEach { (title, icon, action) ->
                FormRow(title = stringResource(title), icon = icon, onClick = action)
                FormDivider()
            }
            FormToggle(
                title = stringResource(R.string.safety_settings_smart_replies),
                icon = Icons.Outlined.AutoAwesome,
                checked = settings.smartRepliesEnabled,
                onCheckedChange = { AppSettings.smartRepliesEnabled = it },
            )
        }
        FormFooter(stringResource(R.string.safety_settings_travel_footer))
    }
    if (showProfile) ProfileEditSheet(onDismiss = { showProfile = false })
}

/** Privacy: visibilita' alle persone vicine, sfide, filtro, foto sfocate, bloccati e segnalazioni. */
@Composable
private fun SettingsPrivacy(onNavigate: (SettingsPage) -> Unit) {
    val environment = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val database by environment.database.collectAsStateWithLifecycle()
    val blockedFlow = remember(database) {
        database?.peers?.observeAll()?.map { list -> list.count { it.isBlocked } } ?: flowOf(0)
    }
    val blockedCount by blockedFlow.collectAsStateWithLifecycle(initialValue = 0)
    val reportsFlow = remember(database) { database?.reports?.observeAll()?.map { it.size } ?: flowOf(0) }
    val reportCount by reportsFlow.collectAsStateWithLifecycle(initialValue = 0)
    SettingsPageColumn {
        FormHeader(stringResource(R.string.nearby_title))
        FormSection {
            FormToggle(
                title = stringResource(R.string.nearby_visible_toggle),
                icon = Icons.Outlined.Visibility,
                checked = settings.isNearbyVisible,
                onCheckedChange = { scope.launch { environment.setNearbyVisible(it) } },
            )
            FormDivider()
            FormToggle(
                title = stringResource(R.string.nearby_settings_open_to_games),
                icon = Icons.Outlined.SportsEsports,
                checked = settings.isOpenToGames,
                onCheckedChange = { scope.launch { environment.setOpenToGames(it) } },
            )
        }
        FormFooter(stringResource(R.string.nearby_settings_visibility_footer))

        FormHeader(stringResource(R.string.safety_settings_safety))
        FormSection {
            FormToggle(
                title = stringResource(R.string.safety_settings_language_filter),
                icon = Icons.Outlined.Spellcheck,
                checked = settings.isLanguageFilterEnabled,
                onCheckedChange = { AppSettings.isLanguageFilterEnabled = it },
            )
            FormDivider()
            FormToggle(
                title = stringResource(R.string.safety_settings_blur),
                icon = Icons.Outlined.VisibilityOff,
                checked = settings.blursStrangerMedia,
                onCheckedChange = { AppSettings.blursStrangerMedia = it },
            )
            FormDivider()
            FormRow(
                title = stringResource(R.string.safety_settings_blocked),
                icon = Icons.Outlined.PanTool,
                value = blockedCount.toString(),
                onClick = { onNavigate(SettingsPage.blocked) },
            )
            FormDivider()
            FormRow(
                title = stringResource(R.string.safety_settings_reports),
                icon = Icons.Outlined.Report,
                value = reportCount.toString(),
                onClick = { onNavigate(SettingsPage.reports) },
            )
        }
        FormFooter(stringResource(R.string.safety_settings_safety_footer))
    }
}

/** Notifiche: stato del permesso e scorciatoia alle impostazioni di sistema dell'app. */
@Composable
private fun SettingsNotifications() {
    val context = LocalContext.current
    // Riletto a ogni ritorno sulla pagina (il permesso si cambia nelle impostazioni di sistema).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SettingsPageColumn {
        Spacer(Modifier.height(8.dp))
        FormSection {
            FormRow(
                title = stringResource(R.string.nearby_settings_notifications_status),
                icon = Icons.Outlined.Notifications,
                value = stringResource(if (enabled) R.string.nearby_settings_on else R.string.nearby_settings_off),
                onClick = null,
            )
            FormDivider()
            FormRow(
                title = stringResource(R.string.nearby_settings_notifications_open),
                icon = Icons.Outlined.Settings,
                onClick = {
                    val intent = Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
            )
        }
        FormFooter(stringResource(R.string.nearby_settings_notifications_footer))
    }
}

/** Abbonamento: Flight Group Pass (acquisto e ripristino nel paywall). */
@Composable
private fun SettingsSubscription() {
    val environment = LocalAppEnvironment.current
    environment.entitlementState.collectAsStateWithLifecycle()
    var showPaywall by remember { mutableStateOf(false) }
    SettingsPageColumn {
        Spacer(Modifier.height(8.dp))
        val isPro = environment.isPro
        FormSection {
            FormRow(
                title = stringResource(if (isPro) R.string.safety_settings_pass_active else R.string.safety_settings_pass),
                icon = if (isPro) Icons.Filled.ConfirmationNumber else Icons.Outlined.ConfirmationNumber,
                onClick = { showPaywall = true },
            )
        }
        FormFooter(stringResource(R.string.nearby_settings_subscription_footer))
    }
    if (showPaywall) PaywallView(onDismiss = { showPaywall = false })
}

/** Info e legale: termini, privacy, versione (con l'interruttore nascosto della demo) e supporto. */
@Composable
private fun SettingsAbout(onNavigate: (SettingsPage) -> Unit, onMessage: (String) -> Unit) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    /** Tocchi sulla riga della versione: al quinto compare l'interruttore della modalita' demo (revisione store). */
    var versionTaps by remember { mutableIntStateOf(0) }
    var showDemoToggle by remember { mutableStateOf(false) }
    val demoVisible = showDemoToggle || settings.isDemoModeEnabled
    SettingsPageColumn {
        FormHeader(stringResource(R.string.safety_settings_legal))
        FormSection {
            FormRow(stringResource(R.string.safety_legal_terms), Icons.Outlined.Description, onClick = { onNavigate(SettingsPage.terms) })
            FormDivider()
            FormRow(stringResource(R.string.safety_legal_privacy), Icons.Outlined.PrivacyTip, onClick = { onNavigate(SettingsPage.privacy) })
            FormDivider()
            FormRow(
                stringResource(R.string.safety_settings_play_terms),
                Icons.Outlined.Shop,
                onClick = { openExternalLink(context, playTermsURL) },
            )
        }

        FormHeader(stringResource(R.string.safety_settings_about))
        FormSection {
            FormRow(
                title = stringResource(R.string.safety_settings_version),
                icon = Icons.Outlined.Info,
                value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                showsChevron = false,
                // Nessun feedback visivo: e' un comando nascosto per la revisione dello store.
                modifier = Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) {
                    versionTaps += 1
                    if (versionTaps >= 5 && !showDemoToggle) {
                        showDemoToggle = true
                        Haptics.impact()
                    }
                },
                onClick = null,
            )
            if (demoVisible) {
                FormDivider()
                FormToggle(
                    title = stringResource(R.string.safety_settings_demo),
                    icon = Icons.Outlined.SmartToy,
                    checked = settings.isDemoModeEnabled,
                    onCheckedChange = { enabled -> scope.launch { environment.setDemoMode(enabled) } },
                )
            }
            FormDivider()
            FormRow(
                stringResource(R.string.safety_settings_support),
                Icons.Outlined.Email,
                onClick = {
                    if (!openExternalLink(context, "mailto:${AppLinks.supportEmail}")) {
                        onMessage(context.getString(R.string.safety_no_email_app, AppLinks.supportEmail))
                    }
                },
            )
        }
        if (demoVisible) {
            FormFooter(stringResource(R.string.safety_settings_demo_footer))
        }
    }
}
