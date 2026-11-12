package com.stefanobrandi.aerochat.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.growth.PassportBadge
import com.stefanobrandi.aerochat.core.growth.PassportStats
import com.stefanobrandi.aerochat.ui.AeroColors
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.AeroSheet
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.SheetBarButton
import com.stefanobrandi.aerochat.ui.components.hapticOnTap
import com.stefanobrandi.aerochat.ui.hex
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Titolo del badge nella lingua dell'app. */
@Composable
internal fun badgeTitle(badge: PassportBadge): String = stringResource(
    when (badge) {
        PassportBadge.firstFlight -> R.string.growth14_badge_first_flight
        PassportBadge.fiveFlights -> R.string.growth14_badge_five_flights
        PassportBadge.tenFlights -> R.string.growth14_badge_ten_flights
        PassportBadge.tenPeople -> R.string.growth14_badge_ten_people
        PassportBadge.fiftyPeople -> R.string.growth14_badge_fifty_people
        PassportBadge.firstWin -> R.string.growth14_badge_first_win
        PassportBadge.tenWins -> R.string.growth14_badge_ten_wins
        PassportBadge.hundredMessages -> R.string.growth14_badge_hundred_messages
    },
)

private fun badgeIcon(badge: PassportBadge): ImageVector = when (badge) {
    PassportBadge.firstFlight, PassportBadge.fiveFlights, PassportBadge.tenFlights -> Icons.Filled.FlightTakeoff
    PassportBadge.tenPeople, PassportBadge.fiftyPeople -> Icons.Filled.Groups
    PassportBadge.firstWin, PassportBadge.tenWins -> Icons.Filled.EmojiEvents
    PassportBadge.hundredMessages -> Icons.Filled.ChatBubble
}

/**
 * Passaporto di viaggio (RELEASE_1_4_SPEC.md §B9): numeri di tutti i voli e badge con la data di sblocco,
 * condivisibile come immagine Storie (1080x1920, come la cartolina). Da Impostazioni e dalla cartolina.
 */
@Composable
fun PassportSheet(onDismiss: () -> Unit) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stats by environment.passport.collectAsStateWithLifecycle()
    environment.identityRecord.collectAsStateWithLifecycle()
    var isSharing by remember { mutableStateOf(false) }
    val layer = rememberGraphicsLayer()

    fun share() {
        if (isSharing) return
        isSharing = true
        scope.launch {
            try {
                val bitmap = layer.toImageBitmap().asAndroidBitmap()
                val file = withContext(Dispatchers.IO) { writeCard(context, bitmap, name = "passport.png") }
                shareCard(context, file, context.getString(R.string.growth14_passport_title))
                Haptics.success()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Haptics.error()
            } finally {
                isSharing = false
            }
        }
    }

    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.growth14_passport_title),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.shell_close), dismiss) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(top = 8.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                Modifier.size(previewWidthDp.dp, previewHeightDp.dp).clip(RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val previewWidthPx = with(LocalDensity.current) { previewWidthDp.dp.toPx() }
                CompositionLocalProvider(LocalDensity provides Density(cardRenderDensity, 1f)) {
                    PassportCardContent(
                        stats = stats,
                        nickname = environment.nickname,
                        modifier = Modifier
                            .requiredSize(cardWidthDp.dp, cardHeightDp.dp)
                            .drawWithContent {
                                layer.record { this@drawWithContent.drawContent() }
                                scale(previewWidthPx / size.width) { drawLayer(layer) }
                            },
                    )
                }
            }
            Button(
                onClick = ::share,
                enabled = !isSharing,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 56.dp).hapticOnTap(),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AeroTheme.accentCyan, contentColor = Color.Black),
            ) {
                if (isSharing) {
                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Filled.Share, contentDescription = null)
                }
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.flight_card_share), style = AeroTheme.title(18f, FontWeight.Bold))
            }
            // Badge con data di sblocco (quelli mancanti con il lucchetto).
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PassportBadge.entries.forEach { badge -> BadgeRow(badge, stats.badges[badge]) }
            }
            Text(
                stringResource(R.string.growth14_passport_footer),
                style = AeroTheme.caption,
                color = AeroTheme.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun BadgeRow(badge: PassportBadge, unlockedAt: Long?) {
    val unlocked = unlockedAt != null
    Row(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(if (unlocked) AeroTheme.accentEmerald.copy(alpha = 0.2f) else AeroTheme.background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (unlocked) badgeIcon(badge) else Icons.Filled.Lock,
                contentDescription = null,
                tint = if (unlocked) AeroTheme.accentEmerald else AeroTheme.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                badgeTitle(badge),
                style = AeroTheme.title(16f, FontWeight.SemiBold),
                color = if (unlocked) AeroTheme.textPrimary else AeroTheme.textSecondary,
            )
            Text(
                if (unlockedAt != null) {
                    stringResource(R.string.growth14_badge_unlocked_on, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(unlockedAt)))
                } else {
                    stringResource(R.string.growth14_badge_locked)
                },
                style = AeroTheme.caption,
                color = AeroTheme.textSecondary,
            )
        }
    }
}

/** Grafica del passaporto (sempre scura, 360x640 dp resi a 1080x1920 px), con il QR per scaricare l'app. */
@Composable
private fun PassportCardContent(stats: PassportStats, nickname: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.verticalGradient(listOf(Color.hex(0x05080F), Color.hex(0x10233A), Color.hex(0x0B3B2E))))) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2 - 160.dp.toPx())
            drawCircle(AeroColors.Dark.accentEmerald.copy(alpha = 0.12f), radius = 240.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
            drawCircle(AeroColors.Dark.accentCyan.copy(alpha = 0.08f), radius = 360.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
        }
        Column(Modifier.fillMaxSize().padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Flight, contentDescription = null, tint = AeroColors.Dark.accentCyan, modifier = Modifier.size(30.dp))
                Spacer(Modifier.size(8.dp))
                androidx.compose.material3.Text("AeroChat", style = rounded(26f, FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.Badge, contentDescription = null, tint = AeroColors.Dark.accentEmerald, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.growth14_passport_title).uppercase(), style = rounded(13f, FontWeight.Bold, AeroColors.Dark.accentEmerald))
            Text(nickname, style = rounded(34f, FontWeight.ExtraBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(22.dp))
            val tiles = listOf(
                Triple(Icons.Filled.FlightTakeoff, stats.flights, stringResource(R.string.growth14_passport_flights)),
                Triple(Icons.Filled.Groups, stats.peopleMet, stringResource(R.string.flight_card_people)),
                Triple(Icons.Filled.ChatBubble, stats.messages, stringResource(R.string.flight_card_messages)),
                Triple(Icons.Filled.SportsEsports, stats.gamesWon, stringResource(R.string.growth14_passport_wins, stats.gamesPlayed)),
                Triple(Icons.Filled.WifiTethering, stats.longestChain, stringResource(R.string.flight_card_hops)),
                Triple(Icons.Filled.WorkspacePremium, stats.badges.size, stringResource(R.string.growth14_passport_badges)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { (icon, value, label) ->
                            Column(
                                Modifier.weight(1f).background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp)).padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(icon, contentDescription = null, tint = AeroColors.Dark.accentEmerald, modifier = Modifier.size(18.dp))
                                Text("$value", style = rounded(30f, FontWeight.Bold), maxLines = 1)
                                Text(label, style = rounded(12f, FontWeight.Medium, Color.White.copy(alpha = 0.7f)), maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // Badge sbloccati come piccole icone.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PassportBadge.entries.filter { stats.badges.containsKey(it) }.take(8).forEach { badge ->
                    Box(Modifier.size(32.dp).background(AeroColors.Dark.accentEmerald.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(badgeIcon(badge), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.flight_card_slogan), style = rounded(20f, FontWeight.Bold))
                    Text(stringResource(R.string.flight_card_slogan2), style = rounded(13f, FontWeight.Medium, Color.White.copy(alpha = 0.7f)))
                }
                StoreQRBadge()
            }
        }
    }
}
