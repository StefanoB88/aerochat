package com.example.aerochat.ui.components

import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aerochat.R
import com.example.aerochat.core.games.GameSessionState
import com.example.aerochat.core.media.MediaStore
import com.example.aerochat.core.media.VoiceNotePlayer
import com.example.aerochat.core.protocol.toHex
import com.example.aerochat.data.records.DeliveryStatus
import com.example.aerochat.data.records.MessageContentType
import com.example.aerochat.data.records.MessageRecord
import com.example.aerochat.data.records.canBeEdited
import com.example.aerochat.data.records.canBeRevised
import com.example.aerochat.ui.AeroTheme
import java.util.Date
import java.util.Locale

/** Membri statici di Swift `MessageBubble`. */
object MessageBubble {
    /** `m:ss` da una durata in ms. */
    fun durationLabel(ms: Int?): String {
        val seconds = (ms ?: 0) / 1_000
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    fun durationLabel(ms: Long): String = durationLabel(ms.toInt())
}

/**
 * Foto sfocate che l'utente ha scelto di mostrare in questa sessione (Swift `RevealedMedia`): la lista
 * ricrea le bolle scorrendo, la scelta deve sopravvivere. Osservabile da Compose.
 */
object RevealedMedia {
    private val ids = mutableStateListOf<String>()
    fun contains(msgIDHex: String): Boolean = msgIDHex in ids
    fun insert(msgIDHex: String) {
        if (msgIDHex !in ids) ids += msgIDHex
    }
}

/** Ora breve (Swift `Text(date, style: .time)`), secondo il formato 12/24 h del dispositivo. */
@Composable
fun timeLabel(epochMs: Long): String {
    val context = LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    return remember(epochMs, format) { format.format(Date(epochMs)) }
}

/**
 * Bolla messaggio: testo, foto (BlurHash → WebP), nota vocale (forma d'onda), gioco, sistema.
 *
 * @param player player della nota vocale, solo per la bolla attualmente in riproduzione.
 * @param transferProgress avanzamento del trasferimento media (0...1) finche' il file non e' completo.
 * @param selectableText testo selezionabile (Swift `.textSelection(.enabled)`); `false` dove la bolla
 *   ha gia' un menu a pressione prolungata (Cabina Pubblica), per non contendersi il gesto.
 * @param hidesMediaUntilTap foto da un mittente non verificato: resta sfocata (solo BlurHash) finche'
 *   l'utente non la tocca (anti AirDrop-molesto). La scelta vale per la sessione ([RevealedMedia]).
 * @param onReport / [onBlock] voci "Segnala messaggio" / [blockLabel] nel menu a pressione prolungata
 *   (solo messaggi ricevuti; `null` = voce assente). Il menu unico contiene anche Copia / Salva nelle Foto.
 * @param onEdit / [onDeleteForEveryone] voci "Modifica" (solo testo) ed "Elimina per tutti" (rossa, con
 *   conferma) sui propri messaggi: compaiono solo entro 15 minuti dall'invio (`null` = mai, es. chat scaduta).
 */
@Composable
fun MessageBubble(
    message: MessageRecord,
    showSender: Boolean,
    store: MediaStore?,
    player: VoiceNotePlayer?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    transferProgress: Double? = null,
    onTogglePlayback: () -> Unit = {},
    onGameAction: (GameBubbleAction) -> Unit = {},
    onSaveImage: () -> Unit = {},
    onOpenImage: () -> Unit = {},
    selectableText: Boolean = true,
    hidesMediaUntilTap: Boolean = false,
    onReport: (() -> Unit)? = null,
    onBlock: (() -> Unit)? = null,
    blockLabel: String? = null,
    onEdit: (() -> Unit)? = null,
    onDeleteForEveryone: (() -> Unit)? = null,
) {
    val isOutgoing = message.isOutgoing
    var confirmDelete by remember { mutableStateOf(false) }
    val menu = BubbleMenu(
        onReport = if (isOutgoing || message.isDeleted) null else onReport,
        onBlock = if (isOutgoing || message.isDeleted) null else onBlock,
        blockLabel = blockLabel,
        onEdit = if (isOutgoing && !message.isDeleted) onEdit else null,
        onDelete = if (isOutgoing && !message.isDeleted && onDeleteForEveryone != null) {
            { confirmDelete = true }
        } else {
            null
        },
    )
    if (confirmDelete && onDeleteForEveryone != null) {
        DeleteForEveryoneDialog(
            onConfirm = {
                confirmDelete = false
                onDeleteForEveryone()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (isOutgoing) Spacer(Modifier.width(56.dp))
        Box(Modifier.weight(1f, fill = false)) {
            Column(
                modifier = Modifier
                    .background(
                        if (isOutgoing) AeroTheme.accentCyan.copy(alpha = 0.16f) else AeroTheme.surface,
                        RoundedCornerShape(18.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val sender = message.senderNickname
                if (showSender && !isOutgoing && sender != null) {
                    Text(sender, style = AeroTheme.captionSemibold, color = AeroTheme.accentCyan)
                }
                if (message.isDeleted) {
                    DeletedMessageContent(isOutgoing = isOutgoing)
                } else BubbleContent(
                    message = message,
                    store = store,
                    player = player,
                    isPlaying = isPlaying,
                    transferProgress = transferProgress,
                    onTogglePlayback = onTogglePlayback,
                    onGameAction = onGameAction,
                    onSaveImage = onSaveImage,
                    onOpenImage = onOpenImage,
                    selectableText = selectableText,
                    hidesMediaUntilTap = hidesMediaUntilTap,
                    menu = menu,
                )
                BubbleFooter(message)
            }
            val hops = message.hopCountReceived
            if (hops != null && hops > 1 && !isOutgoing) {
                Text(
                    pluralStringResource(R.plurals.chat_hops, hops, hops),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Black,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-4).dp, y = (-8).dp)
                        .background(Color(0xFFFFCC00).copy(alpha = 0.9f), CircleShape)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        if (!isOutgoing) Spacer(Modifier.width(56.dp))
    }
}

/**
 * Voci del menu della bolla: sicurezza (segnala / blocca il mittente) sui messaggi ricevuti, modifica e
 * cancellazione per tutti sui propri.
 */
private class BubbleMenu(
    val onReport: (() -> Unit)?,
    val onBlock: (() -> Unit)?,
    val blockLabel: String?,
    val onEdit: (() -> Unit)?,
    val onDelete: (() -> Unit)?,
) {
    val hasSafetyItems: Boolean get() = onReport != null || onBlock != null
}

/** Voci "Modifica" (solo testo) ed "Elimina per tutti" (rossa) sui propri messaggi, entro 15 minuti. */
@Composable
private fun revisionMenuItems(menu: BubbleMenu, message: MessageRecord, close: () -> Unit): List<AeroMenuItem> {
    val editLabel = stringResource(R.string.revisions_edit)
    val deleteLabel = stringResource(R.string.revisions_delete_for_everyone)
    val now = System.currentTimeMillis()
    return listOfNotNull(
        menu.onEdit?.takeIf { message.canBeEdited(now) }?.let { edit ->
            AeroMenuItem(editLabel, Icons.Outlined.Edit) {
                close()
                Haptics.tap()
                edit()
            }
        },
        menu.onDelete?.takeIf { message.canBeRevised(now) }?.let { delete ->
            AeroMenuItem(deleteLabel, Icons.Outlined.Delete, destructive = true) {
                close()
                Haptics.tap()
                delete()
            }
        },
    )
}

/** Conferma di "Elimina per tutti". */
@Composable
private fun DeleteForEveryoneDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AeroTheme.surface,
        titleContentColor = AeroTheme.textPrimary,
        textContentColor = AeroTheme.textSecondary,
        title = { Text(stringResource(R.string.revisions_delete_confirm_title)) },
        text = { Text(stringResource(R.string.revisions_delete_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.revisions_delete_for_everyone), color = Color.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.revisions_cancel), color = AeroTheme.accentCyan) }
        },
    )
}

/** Segnaposto in corsivo di un messaggio cancellato per tutti (niente risposta, copia o segnalazione). */
@Composable
private fun DeletedMessageContent(isOutgoing: Boolean) {
    Text(
        stringResource(if (isOutgoing) R.string.revisions_deleted_by_you else R.string.revisions_deleted_message),
        style = AeroTheme.body,
        fontStyle = FontStyle.Italic,
        color = AeroTheme.textSecondary,
    )
}

/** Voci "Segnala messaggio" e "Blocca …" (rosso) in coda al menu a pressione prolungata. */
@Composable
private fun safetyMenuItems(menu: BubbleMenu, close: () -> Unit): List<AeroMenuItem> {
    val reportLabel = stringResource(R.string.safety_report_message)
    val blockLabel = menu.blockLabel ?: stringResource(R.string.safety_block_user, stringResource(R.string.safety_user))
    return listOfNotNull(
        menu.onReport?.let { report ->
            AeroMenuItem(reportLabel, Icons.Outlined.Report) {
                close()
                Haptics.tap()
                report()
            }
        },
        menu.onBlock?.let { block ->
            AeroMenuItem(blockLabel, Icons.Filled.PanTool, destructive = true) {
                close()
                Haptics.tap()
                block()
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleContent(
    message: MessageRecord,
    store: MediaStore?,
    player: VoiceNotePlayer?,
    isPlaying: Boolean,
    transferProgress: Double?,
    onTogglePlayback: () -> Unit,
    onGameAction: (GameBubbleAction) -> Unit,
    onSaveImage: () -> Unit,
    onOpenImage: () -> Unit,
    selectableText: Boolean,
    hidesMediaUntilTap: Boolean,
    menu: BubbleMenu,
) {
    when (message.contentType) {
        MessageContentType.text, MessageContentType.system -> {
            // Niente SelectionContainer per bolla: in una lista ne servivano decine, ognuno con il proprio
            // gestore di selezione e dei gesti, ed era il costo maggiore all'apertura della chat. La copia
            // passa da una pressione prolungata (menu di sistema "Copia").
            val content = message.text ?: ""
            // Menu unico: Copia (se il testo e' copiabile) + Modifica / Elimina per tutti (propri, 15 minuti)
            // + Segnala / Blocca (messaggi ricevuti).
            val isText = message.contentType == MessageContentType.text
            val safety = isText && menu.hasSafetyItems
            val revisable = isText && (menu.onEdit != null || menu.onDelete != null)
            if (selectableText || safety || revisable) {
                var showMenu by remember { mutableStateOf(false) }
                val clipboard = LocalClipboardManager.current
                Box {
                    Text(
                        content,
                        style = AeroTheme.body,
                        color = AeroTheme.textPrimary,
                        modifier = Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            onLongClick = {
                                Haptics.impact(Haptics.ImpactStyle.medium)
                                showMenu = true
                            },
                        ),
                    )
                    val copyLabel = stringResource(android.R.string.copy)
                    val safetyItems = if (safety) safetyMenuItems(menu) { showMenu = false } else emptyList()
                    val revisionItems = if (revisable && showMenu) {
                        revisionMenuItems(menu, message) { showMenu = false }
                    } else {
                        emptyList()
                    }
                    AeroDropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        items = buildList {
                            if (selectableText) {
                                add(
                                    AeroMenuItem(copyLabel, Icons.Outlined.ContentCopy) {
                                        showMenu = false
                                        clipboard.setText(AnnotatedString(content))
                                        Haptics.tap()
                                    },
                                )
                            }
                            addAll(revisionItems)
                            addAll(safetyItems)
                        },
                    )
                }
            } else {
                Text(content, style = AeroTheme.body, color = AeroTheme.textPrimary)
            }
        }
        MessageContentType.image -> {
            var showMenu by remember { mutableStateOf(false) }
            val hasFile = message.mediaLocalPath != null
            val id = remember(message.msgID) { message.msgID.toHex() }
            // Sfocata finche' non viene rivelata: si mostra solo il BlurHash, il file vero non viene nemmeno caricato.
            val isBlurred = hidesMediaUntilTap && !RevealedMedia.contains(id)
            val canSave = hasFile && !isBlurred
            val hasMenu = canSave || menu.hasSafetyItems || menu.onDelete != null
            val revealHint = stringResource(R.string.safety_tap_to_show_hint)
            Box {
                Box(
                    modifier = Modifier
                        .size(width = 220.dp, height = 165.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = when {
                                isBlurred -> revealHint
                                hasFile -> stringResource(R.string.chat_open_photo)
                                else -> null
                            },
                            onClick = {
                                if (isBlurred) {
                                    Haptics.tap()
                                    RevealedMedia.insert(id)
                                    return@combinedClickable
                                }
                                // BlurHash / ricezione in corso: niente visore finche' il file non e' completo.
                                if (hasFile) onOpenImage()
                            },
                            onLongClick = if (hasMenu) {
                                {
                                    Haptics.impact(Haptics.ImpactStyle.medium)
                                    showMenu = true
                                }
                            } else null,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    MediaImageView(
                        blurhash = message.blurhash,
                        relativePath = if (isBlurred) null else message.mediaLocalPath,
                        store = store,
                        transferProgress = if (message.mediaLocalPath == null || transferProgress != null) transferProgress else null,
                        modifier = Modifier.size(width = 220.dp, height = 165.dp),
                    )
                    if (isBlurred) BlurredMediaOverlay()
                }
                val saveLabel = stringResource(R.string.chat_save_to_photos)
                val safetyItems = safetyMenuItems(menu) { showMenu = false }
                val revisionItems = if (showMenu) revisionMenuItems(menu, message) { showMenu = false } else emptyList()
                AeroDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    items = buildList {
                        if (canSave) {
                            add(
                                AeroMenuItem(saveLabel, Icons.Outlined.Download) {
                                    showMenu = false
                                    onSaveImage()
                                },
                            )
                        }
                        addAll(revisionItems)
                        addAll(safetyItems)
                    },
                )
            }
        }
        MessageContentType.audio -> {
            val missing = message.mediaLocalPath == null
            var showMenu by remember { mutableStateOf(false) }
            val hasMenu = menu.hasSafetyItems || menu.onDelete != null
            Box {
            Row(
                modifier = if (hasMenu) {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = {
                            Haptics.impact(Haptics.ImpactStyle.medium)
                            showMenu = true
                        },
                    )
                } else Modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (missing && transferProgress != null) {
                    TransferProgressRing(progress = transferProgress, lineWidth = 3.dp, size = 28.dp)
                } else {
                    val description = stringResource(if (isPlaying) R.string.chat_pause_voice else R.string.chat_play_voice)
                    HapticButton(
                        onClick = onTogglePlayback,
                        enabled = !missing,
                        tint = if (missing) AeroTheme.textSecondary else AeroTheme.accentCyan,
                        modifier = Modifier.semantics { contentDescription = description },
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                PlaybackWaveform(
                    samples = message.audioWaveform ?: ByteArray(0),
                    player = player,
                    modifier = Modifier
                        .size(width = 150.dp, height = 28.dp)
                        .alpha(if (missing) 0.5f else 1f),
                )
                Text(
                    MessageBubble.durationLabel(message.durationMs),
                    style = AeroTheme.caption.copy(fontFeatureSettings = "tnum"),
                    color = AeroTheme.textSecondary,
                )
            }
            if (hasMenu) {
                val revisionItems = if (showMenu) revisionMenuItems(menu, message) { showMenu = false } else emptyList()
                val safetyItems = safetyMenuItems(menu) { showMenu = false }
                AeroDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    items = revisionItems + safetyItems,
                )
            }
            }
        }
        MessageContentType.game -> {
            val data = message.gameState
            // Chiave sul contenuto, non sul riferimento: Room ricrea l'array a ogni emissione e la decodifica
            // JSON ripartiva sul main thread per ogni bolla di gioco visibile.
            val contentKey = remember(data) { data?.contentHashCode() ?: 0 }
            val state = remember(contentKey, data?.size) { data?.let { GameStateCache.state(it) } }
            if (state != null) {
                GameBubble(state = state, onAction = onGameAction)
            } else {
                Text(message.text ?: "🎮", color = AeroTheme.textPrimary)
            }
        }
    }
}

/** Velo sopra la foto sfocata: "Foto da un contatto non verificato · Tocca per mostrarla". */
@Composable
private fun BlurredMediaOverlay() {
    Column(
        modifier = Modifier
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        Text(
            stringResource(R.string.safety_unverified_photo),
            style = AeroTheme.captionSemibold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(stringResource(R.string.safety_tap_to_show), fontSize = 11.sp, color = Color.White)
    }
}

@Composable
private fun ColumnScope.BubbleFooter(message: MessageRecord) {
    Row(
        modifier = Modifier.align(Alignment.End),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message.editedAtMs != null && !message.isDeleted) {
            Text(stringResource(R.string.revisions_edited), fontSize = 11.sp, color = AeroTheme.textSecondary)
        }
        Text(timeLabel(message.createdAtMs), fontSize = 11.sp, color = AeroTheme.textSecondary)
        if (message.isOutgoing) StatusIcon(message.deliveryStatus)
    }
}

/**
 * Spunte stile WhatsApp: orologio = non inviato, 1 spunta = inviato, 2 spunte grigie = consegnato,
 * 2 spunte ciano = letto (nei gruppi: letto da tutti i membri). Come iOS: niente stato "fallito" e
 * nessuna animazione; glifi disegnati come gli SF Symbols `clock` (caption2) e `checkmark` (10 pt bold).
 */
@Composable
private fun StatusIcon(status: DeliveryStatus) {
    when (status) {
        DeliveryStatus.queued -> {
            val description = stringResource(R.string.chat_not_sent_yet)
            Icon(
                StatusGlyphs.clock,
                contentDescription = description,
                tint = AeroTheme.textSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
        DeliveryStatus.sentMesh ->
            DeliveryTicks(1, AeroTheme.textSecondary, stringResource(R.string.chat_sent))
        DeliveryStatus.deliveredAck ->
            DeliveryTicks(2, AeroTheme.textSecondary, stringResource(R.string.chat_delivered))
        DeliveryStatus.read ->
            DeliveryTicks(2, AeroTheme.accentCyan, stringResource(R.string.chat_read))
    }
}

/** Una o due spunte sovrapposte, sfalsate di 4 dp (SF Symbols non ha una doppia spunta: come iOS). */
@Composable
private fun DeliveryTicks(count: Int, color: Color, description: String) {
    val width: Dp = if (count == 2) 16.dp else 12.dp
    Box(Modifier.width(width).clearAndSetSemantics { contentDescription = description }) {
        repeat(count) { index ->
            Icon(
                StatusGlyphs.checkmark,
                contentDescription = null,
                tint = color,
                modifier = Modifier.offset(x = (index * 4).dp).size(10.dp),
            )
        }
    }
}

/** Equivalenti vettoriali degli SF Symbols usati per lo stato di consegna (tratto arrotondato). */
private object StatusGlyphs {

    /** SF Symbol `checkmark` a 10 pt, peso bold. */
    val checkmark: ImageVector by lazy {
        ImageVector.Builder("checkmark", defaultWidth = 10.dp, defaultHeight = 10.dp, viewportWidth = 10f, viewportHeight = 10f)
            .addPath(
                pathData = PathParser().parsePathString("M1.3,5.4 L3.9,8.2 L8.7,1.7").toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }

    /** SF Symbol `clock` a caption2, peso regular: quadrante e lancette a ore 12 e 4. */
    val clock: ImageVector by lazy {
        ImageVector.Builder("clock", defaultWidth = 12.dp, defaultHeight = 12.dp, viewportWidth = 12f, viewportHeight = 12f)
            .addPath(
                pathData = PathParser().parsePathString(
                    "M6,0.8 A5.2,5.2 0 1,1 6,11.2 A5.2,5.2 0 1,1 6,0.8 Z M6,3.1 L6,6.1 L8.2,7.4",
                ).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
    }
}

/** Foto in preparazione (lettura dalla galleria + compressione WebP): segnaposto con spinner. */
@Composable
fun PendingPhotoBubble(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Spacer(Modifier.width(56.dp))
        Box(
            Modifier
                .background(AeroTheme.accentCyan.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Column(
                modifier = Modifier
                    .size(width = 220.dp, height = 165.dp)
                    .background(AeroTheme.surface, RoundedCornerShape(12.dp)),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = AeroTheme.accentCyan, modifier = Modifier.size(36.dp))
                Text(stringResource(R.string.chat_preparing_photo), style = AeroTheme.caption, color = AeroTheme.textSecondary)
            }
        }
    }
}

/**
 * Forma d'onda con l'avanzamento letto dentro la vista: solo la bolla in riproduzione si aggiorna
 * ai tick del player, non l'intera lista.
 */
@Composable
private fun PlaybackWaveform(samples: ByteArray, player: VoiceNotePlayer?, modifier: Modifier = Modifier) {
    if (player != null) {
        val progress by player.progress.collectAsStateWithLifecycle()
        WaveformView(samples = samples, progress = progress, modifier = modifier)
    } else {
        WaveformView(samples = samples, progress = 0.0, modifier = modifier)
    }
}

/** Stato delle partite decodificato una volta per contenuto (non a ogni ricomposizione). */
private object GameStateCache {

    private class Key(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun equals(other: Any?): Boolean = other is Key && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = hash
    }

    private val cache = LruCache<Key, GameSessionState>(128)

    fun state(data: ByteArray): GameSessionState? {
        val key = Key(data)
        cache.get(key)?.let { return it }
        val state = try {
            GameSessionState.decode(data)
        } catch (_: Exception) {
            return null
        }
        cache.put(key, state)
        return state
    }
}
