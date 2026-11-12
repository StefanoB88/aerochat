package com.stefanobrandi.aerochat.ui.flight

import com.stefanobrandi.aerochat.core.growth.ReviewPrompt
import com.stefanobrandi.aerochat.core.qr.QRCodeGenerator
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.foundation.Image
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.flight.FlightStats
import com.stefanobrandi.aerochat.core.flight.FlightTrip
import androidx.compose.ui.res.pluralStringResource
import com.stefanobrandi.aerochat.ui.AeroColors
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.AeroSheet
import com.stefanobrandi.aerochat.ui.components.Haptics
import com.stefanobrandi.aerochat.ui.components.SheetBarButton
import com.stefanobrandi.aerochat.ui.components.hapticOnTap
import com.stefanobrandi.aerochat.ui.hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Dimensioni della cartolina: 360x640 dp resi a densita' 3 = 1080x1920 px (formato Storie). */
internal const val cardWidthDp = 360
internal const val cardHeightDp = 640
internal const val cardRenderDensity = 3f

/** Anteprima nel foglio (Swift `.scaleEffect(0.72)` di 360x640). */
internal const val previewWidthDp = 260
internal const val previewHeightDp = 462

/**
 * Cartolina di volo: i numeri del viaggio in formato Storie (1080x1920) da condividere.
 * La cartolina mostrata in anteprima e' la stessa che viene catturata (GraphicsLayer) e condivisa come PNG.
 * [tripStats]: numeri gia' pronti della cartolina di fine volo (al posto di quelli derivati da [trip]).
 */
@Composable
fun FlightCardSheet(onDismiss: () -> Unit, trip: FlightTrip? = null, tripStats: FlightStats? = null) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database by environment.database.collectAsStateWithLifecycle()
    environment.identityRecord.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(FlightStats.Period.trip) }
    var stats by remember { mutableStateOf<FlightStats?>(null) }
    var isSharing by remember { mutableStateOf(false) }
    val layer = rememberGraphicsLayer()

    val activeTrip by environment.activeTrip.collectAsStateWithLifecycle()
    var showPassport by remember { mutableStateOf(false) }
    // Cartolina di fine volo mostrata: momento felice per chiedere una recensione (con i limiti di frequenza).
    LaunchedEffect(trip?.key) {
        if (trip != null) environment.requestReviewIfAppropriate(ReviewPrompt.Moment.tripCardShown)
    }
    LaunchedEffect(database, period, trip) {
        // Cartolina di fine volo: i contatori del viaggio, gia' pronti.
        if (trip != null) {
            stats = tripStats ?: trip.stats()
            return@LaunchedEffect
        }
        val database = database ?: return@LaunchedEffect
        stats = null
        stats = try {
            FlightStats.compute(database, period)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun share() {
        if (isSharing) return
        isSharing = true
        scope.launch {
            try {
                val bitmap = layer.toImageBitmap().asAndroidBitmap()
                val file = withContext(Dispatchers.IO) { writeCard(context, bitmap) }
                shareCard(context, file, context.getString(R.string.flight_card_share_title))
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
        title = stringResource(R.string.flight_card_title),
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
            if (trip != null) {
                Text(
                    stringResource(R.string.nearby_trip_card_header, trip.flightCode, trip.flightDate),
                    style = AeroTheme.title(17f, FontWeight.SemiBold),
                    color = AeroTheme.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                FlightSegmentedPicker(
                    options = FlightStats.Period.entries,
                    selected = period,
                    label = { stringResource(it.titleRes) },
                    onSelect = { period = it },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentDescription = stringResource(R.string.flight_card_period),
                )
            }
            // Classifica del volo: partite vinte durante il volo in corso.
            val leaderboard = trip ?: activeTrip
            if (leaderboard != null && leaderboard.gamesPlayed > 0) {
                FlightLeaderboard(leaderboard)
            }
            val current = stats
            if (current != null) {
                Box(
                    Modifier
                        .size(previewWidthDp.dp, previewHeightDp.dp)
                        .shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = AeroTheme.accentCyan, spotColor = AeroTheme.accentCyan)
                        .clip(RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val previewWidthPx = with(LocalDensity.current) { previewWidthDp.dp.toPx() }
                    // Densita' fissa: la cartolina misura sempre 1080x1920 px, qualunque sia lo schermo.
                    CompositionLocalProvider(LocalDensity provides Density(cardRenderDensity, 1f)) {
                        FlightCardContent(
                            stats = current,
                            nickname = environment.nickname,
                            modifier = Modifier
                                .requiredSize(cardWidthDp.dp, cardHeightDp.dp)
                                .drawWithContent {
                                    // Registra a piena risoluzione, poi disegna ridotta nell'anteprima.
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
            } else {
                Box(Modifier.height(previewHeightDp.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AeroTheme.accentCyan)
                }
            }
            TextButton(onClick = { Haptics.tap(); showPassport = true }) {
                Icon(Icons.Filled.Badge, contentDescription = null, tint = AeroTheme.accentEmerald, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.growth14_passport_title), color = AeroTheme.accentEmerald, fontWeight = FontWeight.SemiBold)
            }
            Text(
                stringResource(R.string.flight_card_privacy),
                style = AeroTheme.caption,
                color = AeroTheme.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
    if (showPassport) PassportSheet(onDismiss = { showPassport = false })
}

/** Salva la cartolina in `cache/camera/` (percorso gia' esposto dal FileProvider `${applicationId}.camera`). */
internal fun writeCard(context: Context, bitmap: Bitmap, name: String = "flight_card.png"): File {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, name)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

internal fun shareCard(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, title)
        // ClipData: anteprima nel foglio di condivisione e permesso di lettura propagato.
        clipData = ClipData.newUri(context.contentResolver, title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, title)
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

internal fun rounded(size: Float, weight: FontWeight, color: Color = Color.White): TextStyle =
    TextStyle(fontSize = size.sp, fontWeight = weight, color = color)

/** Grafica della cartolina (sempre scura: e' un'immagine condivisibile, indipendente dal tema) (360x640 dp; resa a densita' 3 = 1080x1920 px). */
@Composable
private fun FlightCardContent(stats: FlightStats, nickname: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.verticalGradient(listOf(Color.hex(0x05080F), Color.hex(0x0B1B2B), Color.hex(0x003845))),
        ),
    ) {
        // Cerchi del radar, centrati 140 dp sopra il centro della cartolina.
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2 - 140.dp.toPx())
            drawCircle(AeroColors.Dark.accentCyan.copy(alpha = 0.12f), radius = 260.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
            drawCircle(AeroColors.Dark.accentCyan.copy(alpha = 0.08f), radius = 380.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
        }
        Column(Modifier.fillMaxSize().padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Flight, contentDescription = null, tint = AeroColors.Dark.accentCyan, modifier = Modifier.size(30.dp))
                Spacer(Modifier.size(8.dp))
                Text("AeroChat", style = rounded(26f, FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(stats.period.titleRes).uppercase(),
                    style = rounded(11f, FontWeight.Bold, Color.Black),
                    modifier = Modifier
                        .background(AeroColors.Dark.accentCyan, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.flight_card_whose, nickname),
                style = rounded(34f, FontWeight.ExtraBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            stats.flightTitles.firstOrNull()?.let { flight ->
                Text(flight, style = rounded(15f, FontWeight.SemiBold, AeroColors.Dark.accentCyan), modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(30.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val hero = "${stats.totalMessages}"
                Text(
                    hero,
                    style = rounded(if (hero.length > 5) 64f else 92f, FontWeight.Black, AeroColors.Dark.accentCyan)
                        .copy(lineHeight = (if (hero.length > 5) 68f else 96f).sp),
                    maxLines = 1,
                )
                Text(stringResource(R.string.flight_card_messages), style = rounded(17f, FontWeight.SemiBold, Color.White.copy(alpha = 0.85f)))
            }
            Spacer(Modifier.height(26.dp))
            val tiles = listOf(
                Triple(Icons.Filled.Groups, stats.peopleMet, stringResource(R.string.flight_card_people)),
                Triple(Icons.Filled.SportsEsports, stats.gamesWon, stringResource(R.string.flight_card_games, stats.gamesPlayed)),
                Triple(Icons.Filled.Photo, stats.photos, stringResource(R.string.flight_card_photos)),
                Triple(Icons.Filled.GraphicEq, stats.voiceNotes, stringResource(R.string.flight_card_voice)),
                Triple(Icons.Filled.WifiTethering, stats.longestHop, stringResource(R.string.flight_card_hops)),
                Triple(Icons.Filled.ChatBubble, stats.loungeMessages, stringResource(R.string.flight_card_lounge)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { (icon, value, label) -> StatTile(icon, value, label, Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.flight_card_slogan), style = rounded(20f, FontWeight.Bold))
                    Text(stringResource(R.string.flight_card_slogan2), style = rounded(13f, FontWeight.Medium, Color.White.copy(alpha = 0.7f)))
                }
                // QR verso la pagina degli store: chi vede la cartolina scarica l'app.
                StoreQRBadge()
            }
        }
    }
}

/** QR `https://stefanob88.github.io/aerochatwebsite/get` con "Scarica AeroChat" in basso a destra della cartolina
 * (l'indirizzo non e' scritto: con il dominio github.io sarebbe troppo lungo per il badge). */
@Composable
internal fun StoreQRBadge() {
    val qr = remember { QRCodeGenerator.image(QRCodeGenerator.storeLandingURL, scale = 8)?.asImageBitmap() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = null,
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .size(96.dp)
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .padding(6.dp),
            )
        }
        Text(stringResource(R.string.growth14_card_download), style = rounded(12f, FontWeight.Bold, AeroColors.Dark.accentCyan))
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: Int, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AeroColors.Dark.accentEmerald, modifier = Modifier.size(18.dp))
        Text("$value", style = rounded(30f, FontWeight.Bold).copy(lineHeight = 32.sp), maxLines = 1)
        Text(
            label,
            style = rounded(12f, FontWeight.Medium, Color.White.copy(alpha = 0.7f)).copy(lineHeight = 15.sp),
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Numeri della cartolina dai contatori del viaggio (persone incontrate, messaggi, partite, volo). */
private fun FlightTrip.stats(): FlightStats = FlightStats(
    period = FlightStats.Period.trip,
    messagesSent = messagesSent,
    messagesReceived = messagesReceived,
    peopleMet = people.size,
    gamesPlayed = gamesPlayed,
    gamesWon = gamesWon,
    flightTitles = listOf(title.ifEmpty { "$flightCode · $flightDate" }),
)

/** "Classifica del volo": partite vinte su quelle giocate durante il volo. */
@Composable
private fun FlightLeaderboard(trip: FlightTrip) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(AeroTheme.surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = AeroTheme.accentEmerald)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.nearby_leaderboard_title, trip.flightCode),
                style = AeroTheme.captionSemibold,
                color = AeroTheme.textSecondary,
            )
            Text(
                pluralStringResource(R.plurals.nearby_leaderboard_wins, trip.gamesPlayed, trip.gamesWon, trip.gamesPlayed),
                style = AeroTheme.title(16f, FontWeight.SemiBold),
                color = AeroTheme.textPrimary,
            )
        }
    }
}
