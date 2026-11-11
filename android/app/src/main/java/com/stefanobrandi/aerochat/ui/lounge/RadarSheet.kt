package com.stefanobrandi.aerochat.ui.lounge

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.AeroChatApplication
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.core.mesh.Neighbor
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.data.records.PeerRecord
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.AeroSheet
import com.stefanobrandi.aerochat.ui.components.AvatarView
import com.stefanobrandi.aerochat.ui.components.SheetBarButton
import com.stefanobrandi.aerochat.ui.onboarding.OnboardingView
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Radar di prossimita' (Swift `RadarSheet`): i vicini radio disposti su anelli in base all'RSSI (piu'
 * vicino al centro = segnale piu' forte). Serve a ritrovare un amico in aereo, al festival o in aeroporto.
 */
@Composable
fun RadarSheet(onDismiss: () -> Unit) {
    val environment = LocalAppEnvironment.current
    // Polling RSSI dei link solo mentre il radar e' a schermo (come iOS). Scope dell'Application: lo
    // spegnimento deve arrivare anche se la composizione viene distrutta.
    val applicationScope = (LocalContext.current.applicationContext as? AeroChatApplication)?.applicationScope
    DisposableEffect(environment, applicationScope) {
        applicationScope?.launch { environment.setRadarVisible(true) }
        onDispose { applicationScope?.launch { environment.setRadarVisible(false) } }
    }
    val database by environment.database.collectAsStateWithLifecycle()
    val neighbors by environment.neighbors.collectAsStateWithLifecycle()
    // Peer noti per node_id (hex): nickname e avatar dei vicini.
    val peers by produceState(emptyMap<String, PeerRecord>(), database) {
        val db = database ?: return@produceState
        db.peers.observeAll().collect { list ->
            val map = list.associateBy { it.nodeID.toHex() }
            if (map != value) value = map
        }
    }
    val sorted = remember(neighbors) { neighbors.sortedByDescending { it.rssi ?: -200 } }

    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.lounge11_radar_title),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.lounge11_close), onClick = dismiss) },
    ) {
        Column(
            Modifier.fillMaxSize().background(AeroTheme.background).padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RadarCanvas(
                neighbors = neighbors,
                peers = peers,
                modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 340.dp).fillMaxWidth(),
            )
            if (neighbors.isEmpty()) {
                Text(
                    stringResource(R.string.lounge11_radar_empty),
                    style = AeroTheme.footnote,
                    color = AeroTheme.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(sorted, key = { it.id }) { neighbor ->
                        NeighborRow(neighbor, peers[neighbor.id])
                    }
                }
            }
        }
    }
}

@Composable
private fun NeighborRow(neighbor: Neighbor, peer: PeerRecord?) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface, AeroTheme.cardShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarView(
            imageData = peer?.avatarImage,
            colorHex = peer?.avatarColorHex ?: RadarSheetDefaults.fallbackColor(neighbor.nodeID),
            size = 36.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                peer?.nickname ?: stringResource(R.string.lounge11_passenger),
                style = AeroTheme.body,
                fontWeight = FontWeight.Medium,
                color = AeroTheme.textPrimary,
            )
            Text(RadarSheetDefaults.distanceLabel(neighbor.rssi, context), style = AeroTheme.caption, color = AeroTheme.textSecondary)
        }
        neighbor.rssi?.let { rssi ->
            Text("$rssi dBm", style = AeroTheme.monospaced(12f), color = AeroTheme.accentCyan)
        }
    }
}

/** Funzioni di supporto del radar (Swift `RadarSheet.distanceLabel` / `fallbackColor`, `RadarCanvas.*`). */
object RadarSheetDefaults {

    /** Distanza stimata dall'RSSI (Bluetooth in cabina: molto approssimativa, ma utile a orientarsi). */
    fun distanceLabel(rssi: Int?, context: Context): String = context.getString(
        when {
            rssi == null -> R.string.lounge11_distance_unknown
            rssi >= -60 -> R.string.lounge11_distance_immediate
            rssi >= -72 -> R.string.lounge11_distance_near
            rssi >= -84 -> R.string.lounge11_distance_few_rows
            else -> R.string.lounge11_distance_far
        },
    )

    fun fallbackColor(nodeID: ByteArray): String {
        val palette = OnboardingView.palette
        val first = nodeID.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        return palette[first % palette.size]
    }

    /** -45 dBm al centro, -95 dBm al bordo; RSSI sconosciuto (link in ruolo peripheral) sul bordo. */
    fun radiusFraction(rssi: Int?): Float {
        if (rssi == null) return 1f
        val clamped = min(-45, max(-95, rssi))
        return ((-45 - clamped) / 50f) * 0.9f + 0.1f
    }

    /** Angolo (radianti) derivato dal node_id: la posizione resta stabile tra un aggiornamento e l'altro. */
    fun angle(nodeID: ByteArray): Float {
        val seed = nodeID.take(2).fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
        return Math.toRadians((seed % 360).toDouble()).toFloat()
    }
}

/** Anelli concentrici con lancetta rotante e gli avatar dei vicini. */
@Composable
private fun RadarCanvas(neighbors: List<Neighbor>, peers: Map<String, PeerRecord>, modifier: Modifier = Modifier) {
    val a11y = pluralStringResource(R.plurals.lounge11_radar_a11y, neighbors.size, neighbors.size)
    val transition = rememberInfiniteTransition(label = "radarSweep")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart),
        label = "radarSweepAngle",
    )
    val cyan = AeroTheme.accentCyan
    BoxWithConstraints(modifier.aspectRatio(1f).semantics { contentDescription = a11y }) {
        val density = LocalDensity.current
        val sidePx = with(density) { min(maxWidth.toPx(), maxHeight.toPx()) }
        val marginPx = with(density) { 22.dp.toPx() }
        val maxRadius = sidePx / 2 - marginPx
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            listOf(0.33f, 0.66f, 1f).forEach { fraction ->
                drawCircle(
                    color = cyan.copy(alpha = 0.25f),
                    radius = maxRadius * fraction,
                    center = center,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = if (fraction == 1f) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null,
                    ),
                )
            }
            // Lancetta: spicchio di 60 gradi che sfuma verso la coda, ruota in 4 s.
            rotate(degrees = sweep - 60f, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        (60f / 360f) to cyan.copy(alpha = 0.35f),
                        (60f / 360f) + 0.001f to Color.Transparent,
                        center = center,
                    ),
                    startAngle = 0f,
                    sweepAngle = 60f,
                    useCenter = true,
                    topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                    size = androidx.compose.ui.geometry.Size(maxRadius * 2, maxRadius * 2),
                )
            }
            drawCircle(cyan.copy(alpha = 0.4f), radius = 12.dp.toPx() - 3.dp.toPx(), center = center, style = Stroke(6.dp.toPx()))
            drawCircle(cyan, radius = 7.dp.toPx(), center = center)
        }
        neighbors.forEach { neighbor ->
            key(neighbor.id) {
                val target = RadarSheetDefaults.radiusFraction(neighbor.rssi)
                val fraction by animateFloatAsState(target, spring(), label = "radarRadius")
                val angle = remember(neighbor.id) { RadarSheetDefaults.angle(neighbor.nodeID) }
                val peer = peers[neighbor.id]
                val labelWidth = 70.dp
                val avatarSize = 34.dp
                Column(
                    Modifier
                        .width(labelWidth)
                        .offset {
                            val x = sidePx / 2 + cos(angle) * maxRadius * fraction - labelWidth.toPx() / 2
                            val y = sidePx / 2 + sin(angle) * maxRadius * fraction - avatarSize.toPx() / 2
                            IntOffset(x.roundToInt(), y.roundToInt())
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(Modifier.border(1.dp, AeroTheme.onBackground.copy(alpha = 0.6f), CircleShape)) {
                        AvatarView(
                            imageData = peer?.avatarImage,
                            colorHex = peer?.avatarColorHex ?: RadarSheetDefaults.fallbackColor(neighbor.nodeID),
                            size = avatarSize,
                        )
                    }
                    Text(
                        peer?.nickname ?: stringResource(R.string.lounge11_passenger),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AeroTheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
