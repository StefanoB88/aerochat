package com.stefanobrandi.aerochat.ui.nearby

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.app.AppEnvironment.NearbyBanner
import com.stefanobrandi.aerochat.app.LocalAppEnvironment
import com.stefanobrandi.aerochat.ui.AeroTheme
import com.stefanobrandi.aerochat.ui.components.AvatarView
import com.stefanobrandi.aerochat.ui.components.Haptics
import kotlinx.coroutines.delay

/**
 * Banner in app delle persone vicine (in cima, sopra a ogni schermata): "Marco ti saluta" con **Rispondi**,
 * "Marco ha accettato" con **Apri la chat**, "Richiesta rifiutata". Si chiude da solo dopo qualche secondo.
 */
@Composable
fun NearbyBannerHost(onOpenNearby: () -> Unit, onOpenChat: (String) -> Unit, modifier: Modifier = Modifier) {
    val environment = LocalAppEnvironment.current
    val banner by environment.nearbyBanner.collectAsStateWithLifecycle()
    // Ultimo banner mostrato, per l'animazione di uscita.
    var shown by remember { mutableStateOf(banner) }
    if (banner != null) shown = banner
    LaunchedEffect(banner) {
        if (banner == null) return@LaunchedEffect
        Haptics.impact(Haptics.ImpactStyle.light)
        delay(if (banner is NearbyBanner.Request) 10_000 else 6_000)
        environment.dismissNearbyBanner()
    }
    AnimatedVisibility(
        visible = banner != null,
        enter = slideInVertically(spring()) { -it } + fadeIn(),
        exit = slideOutVertically(spring()) { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val current = shown ?: return@AnimatedVisibility
        val (title, action, colorHex) = when (current) {
            is NearbyBanner.Request -> Triple(requestTitle(current.request), stringResource(R.string.nearby_reply), current.request.avatarColorHex)
            is NearbyBanner.Accepted -> Triple(
                stringResource(R.string.nearby_accepted, current.nickname), stringResource(R.string.nearby_open_chat), null,
            )
            is NearbyBanner.Declined -> Triple(stringResource(R.string.nearby_declined, current.nickname), null, null)
        }
        val onAction = {
            environment.dismissNearbyBanner()
            when (current) {
                is NearbyBanner.Request -> onOpenNearby()
                is NearbyBanner.Accepted -> onOpenChat(current.conversationID)
                is NearbyBanner.Declined -> Unit
            }
        }
        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .fillMaxWidth()
                .background(AeroTheme.elevatedSurface, RoundedCornerShape(18.dp))
                .border(1.dp, AeroTheme.accentCyan.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .clickable { onAction() }
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AvatarView(imageData = null, colorHex = colorHex, size = 34.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = AeroTheme.title(15f, FontWeight.SemiBold),
                    color = AeroTheme.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current is NearbyBanner.Request && current.request.greeting.isNotEmpty()) {
                    Text(current.request.greeting, style = AeroTheme.caption, color = AeroTheme.textSecondary, maxLines = 1)
                }
            }
            if (action != null) {
                TextButton(onClick = onAction) { Text(action, color = AeroTheme.accentCyan, fontWeight = FontWeight.Bold) }
            }
            IconButton(onClick = { environment.dismissNearbyBanner() }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.shell_close),
                    tint = AeroTheme.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
