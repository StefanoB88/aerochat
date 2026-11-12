package com.stefanobrandi.aerochat.ui.paywall

import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.stefanobrandi.aerochat.core.growth.ReferralProgram
import com.stefanobrandi.aerochat.core.moderation.AppSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.entitlements.EntitlementManager
import com.stefanobrandi.aerochat.core.entitlements.StoreProduct
import com.stefanobrandi.aerochat.core.moderation.AppLinks
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.data.records.ProTier
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.AeroSheet
import com.stefanobrandi.aerochat.ui.components.HapticButton
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.SheetBarButton
import com.stefanobrandi.aerochat.ui.components.findActivity
import com.stefanobrandi.aerochat.ui.components.hapticOnTap
import com.stefanobrandi.aerochat.ui.settings.LegalDocument
import com.stefanobrandi.aerochat.ui.settings.LegalSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private data class Offer(val id: String, val title: String, val subtitle: String, val fallbackPrice: String)

/**
 * Flight Group Pass: Pass Volo Singolo 24 ore (0,29 €), Pass Viaggio 72 ore (0,49 €) e Frequent Flyer
 * Lifetime (2,49 €). Zero abbonamenti.
 * Prezzi da Google Play Billing; l'acquisto usa l'Activity corrente. Si presenta da solo come foglio.
 */
@Composable
fun PaywallView(onDismiss: () -> Unit) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val entitlements by environment.entitlements.collectAsStateWithLifecycle()
    val entitlementState by environment.entitlementState.collectAsStateWithLifecycle()
    val emptyProducts = remember { MutableStateFlow<List<StoreProduct>>(emptyList()) }
    val products by (entitlements?.products ?: emptyProducts).collectAsStateWithLifecycle()
    var purchasing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var legalDocument by remember { mutableStateOf<LegalDocument?>(null) }

    val offers = listOf(
        Offer(
            MeshProtocol.ProductID.pass24h,
            stringResource(R.string.flight_paywall_pass24_title),
            stringResource(R.string.flight_paywall_pass24_subtitle),
            "0,29 €",
        ),
        Offer(
            MeshProtocol.ProductID.pass72h,
            stringResource(R.string.shell_paywall_pass72_title),
            stringResource(R.string.shell_paywall_pass72_subtitle),
            "0,49 €",
        ),
        Offer(
            MeshProtocol.ProductID.lifetime,
            stringResource(R.string.shell_paywall_lifetime_title),
            stringResource(R.string.shell_paywall_lifetime_subtitle),
            "2,49 €",
        ),
    )

    LaunchedEffect(entitlements) {
        val manager = entitlements ?: return@LaunchedEffect
        if (manager.products.value.isEmpty()) runCatching { manager.loadProducts() }
        if (manager.products.value.isEmpty()) {
            message = context.getString(R.string.shell_paywall_prices_offline)
        }
    }

    fun purchase(productID: String) {
        val activity = context.findActivity() ?: return
        purchasing = productID
        scope.launch {
            try {
                environment.purchase(productID = productID, activity = activity)
                Haptics.success()
                message = context.getString(R.string.shell_paywall_thanks)
            } catch (e: EntitlementManager.Failure.Cancelled) {
                message = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error()
                message = context.getString(R.string.shell_paywall_failed, e.localizedMessage ?: e.toString())
            } finally {
                purchasing = null
            }
        }
    }

    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.shell_paywall_title),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.shell_close), dismiss) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Intestazione.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.ConfirmationNumber,
                    contentDescription = null,
                    tint = AeroTheme.accentEmerald,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    if (environment.isPro) stringResource(R.string.shell_paywall_header_pro)
                    else stringResource(R.string.shell_paywall_header_free),
                    style = AeroTheme.title(28f),
                    color = AeroTheme.textPrimary,
                )
                val settings by AppSettings.state.collectAsStateWithLifecycle()
                val referralUntil = settings.referralPassUntilMs
                if (!entitlementState.isPro() && referralUntil > System.currentTimeMillis()) {
                    val formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(referralUntil))
                    Text(
                        stringResource(R.string.growth14_referral_pass_until, formatted),
                        style = AeroTheme.footnote,
                        color = AeroTheme.accentEmerald,
                    )
                }
                val until = entitlementState.validUntilMs
                if (environment.isPro && until != null && entitlementState.tier.isTimeLimited) {
                    val formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(until))
                    Text(
                        stringResource(R.string.shell_paywall_active_until, formatted),
                        style = AeroTheme.footnote,
                        color = AeroTheme.accentEmerald,
                    )
                }
            }

            for (offer in offers) {
                val product = products.firstOrNull { it.id == offer.id }
                val isCurrent = entitlementState.tier == ProTier.lifetime && offer.id == MeshProtocol.ProductID.lifetime
                OfferCard(
                    offer = offer,
                    product = product,
                    isCurrent = isCurrent,
                    isPurchasing = purchasing == offer.id,
                    enabled = !isCurrent && purchasing == null && product != null,
                    onPurchase = { purchase(offer.id) },
                )
            }

            message?.let { Text(it, style = AeroTheme.footnote, color = AeroTheme.textSecondary) }

            // Premio inviti: "2 su 3 amici invitati" (al terzo, 24 h di Pass solo per te).
            ReferralProgressCard()

            // Codici promozionali Google Play: "Hai un codice?" -> pagina di riscatto del Play Store.
            PromoCodeSection()

            HapticButton(
                onClick = {
                    scope.launch {
                        environment.restorePurchases()
                        message = if (environment.isPro) context.getString(R.string.shell_paywall_restored)
                        else context.getString(R.string.shell_paywall_nothing_restored)
                    }
                },
                tint = AeroTheme.accentCyan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.shell_paywall_restore), style = AeroTheme.body, fontWeight = FontWeight.SemiBold)
            }

            Text(stringResource(R.string.shell_paywall_free_note), style = AeroTheme.caption, color = AeroTheme.textSecondary)

            // Link legali, come richiesto dagli store per gli acquisti in-app.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                LegalLink(stringResource(R.string.flight_paywall_terms)) { legalDocument = LegalDocument.terms }
                LegalLink(stringResource(R.string.flight_paywall_privacy)) { legalDocument = LegalDocument.privacy }
                LegalLink(stringResource(R.string.flight_paywall_eula)) { uriHandler.openUri(AppLinks.appleEULA) }
            }
            Text(
                stringResource(R.string.flight_paywall_one_time),
                style = TextStyle(fontSize = 11.sp),
                color = AeroTheme.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    legalDocument?.let { LegalSheet(document = it, onDismiss = { legalDocument = null }) }
}

/** Contatore del premio inviti (paywall e "Invita amici"). */
@Composable
fun ReferralProgressCard(modifier: Modifier = Modifier) {
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val progress = minOf(settings.referralInvited.size, ReferralProgram.goal)
    Column(
        modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, AeroTheme.cardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.CardGiftcard, contentDescription = null, tint = AeroTheme.accentEmerald, modifier = Modifier.size(24.dp))
            Text(
                stringResource(R.string.growth14_referral_progress, progress, ReferralProgram.goal),
                style = AeroTheme.title(17f, FontWeight.SemiBold),
                color = AeroTheme.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }
        LinearProgressIndicator(
            progress = { progress / ReferralProgram.goal.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = AeroTheme.accentEmerald,
            trackColor = AeroTheme.background,
        )
        Text(
            stringResource(if (settings.referralRewardGranted) R.string.growth14_referral_done else R.string.growth14_referral_hint),
            style = AeroTheme.footnote,
            color = AeroTheme.textSecondary,
        )
    }
}

/** "Hai un codice?": campo testo e apertura di `https://play.google.com/redeem?code=<codice>`. */
@Composable
private fun PromoCodeSection() {
    val uriHandler = LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    if (!expanded) {
        HapticButton(onClick = { expanded = true }, tint = AeroTheme.accentCyan, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.growth14_promo_have_code), style = AeroTheme.body, fontWeight = FontWeight.SemiBold)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = code,
            onValueChange = { value -> code = value.filter { !it.isWhitespace() }.take(64) },
            placeholder = { Text(stringResource(R.string.growth14_promo_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AeroTheme.surface,
                unfocusedContainerColor = AeroTheme.surface,
                focusedBorderColor = AeroTheme.accentCyan,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = AeroTheme.textPrimary,
                unfocusedTextColor = AeroTheme.textPrimary,
                cursorColor = AeroTheme.accentCyan,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                Haptics.tap()
                runCatching { uriHandler.openUri(PromoCode.redeemURL(code)) }
            },
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AeroTheme.accentCyan, contentColor = Color.Black),
        ) {
            Text(stringResource(R.string.growth14_promo_redeem), fontWeight = FontWeight.Bold)
        }
        Text(stringResource(R.string.growth14_promo_hint), style = AeroTheme.caption, color = AeroTheme.textSecondary)
    }
}

/** Riscatto dei codici promozionali sul Play Store. */
object PromoCode {
    fun redeemURL(code: String): String =
        AppLinks.playRedeem + "?code=" + java.net.URLEncoder.encode(code.trim(), "UTF-8")
}

@Composable
private fun LegalLink(title: String, onClick: () -> Unit) {
    Text(
        title,
        style = AeroTheme.captionSemibold,
        color = AeroTheme.accentCyan,
        modifier = Modifier
            .clickable {
                Haptics.tap()
                // Nessun browser installato: il link resta semplicemente inattivo.
                runCatching { onClick() }
            }
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun OfferCard(
    offer: Offer,
    product: StoreProduct?,
    isCurrent: Boolean,
    isPurchasing: Boolean,
    enabled: Boolean,
    onPurchase: () -> Unit,
) {
    val displayName = product?.displayName?.takeIf { it.isNotEmpty() } ?: offer.title
    val tint = if (offer.id == MeshProtocol.ProductID.lifetime) AeroTheme.accentEmerald else AeroTheme.accentCyan
    Column(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, AeroTheme.cardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(displayName, style = AeroTheme.title(18f, FontWeight.SemiBold), color = AeroTheme.textPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                product?.displayPrice ?: offer.fallbackPrice,
                style = AeroTheme.title(18f, FontWeight.Bold),
                color = AeroTheme.accentCyan,
            )
        }
        Text(offer.subtitle, style = AeroTheme.footnote, color = AeroTheme.textSecondary)
        Button(
            onClick = onPurchase,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).hapticOnTap(enabled),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = tint,
                contentColor = Color.Black,
                disabledContainerColor = tint.copy(alpha = 0.3f),
                disabledContentColor = Color.Black.copy(alpha = 0.6f),
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isPurchasing) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(
                    if (isCurrent) stringResource(R.string.shell_paywall_active) else stringResource(R.string.shell_paywall_buy),
                    style = AeroTheme.headline,
                )
            }
        }
    }
}
