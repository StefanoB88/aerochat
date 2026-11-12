package com.stefanobrandi.aerochat.ui.components

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.DirectionsBoat
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stefanobrandi.aerochat.R
import com.stefanobrandi.aerochat.core.media.VoiceNoteRecorder
import com.stefanobrandi.aerochat.proto.GameKind
import com.stefanobrandi.aerochat.ui.AeroTheme

/** File temporanei degli scatti (cache/camera, esposti alla fotocamera di sistema con FileProvider). */
internal object CameraShots {
    /** Gli scatti vengono letti subito dopo la cattura: oltre un'ora sono avanzi da eliminare. */
    private const val maxAgeMs = 3_600_000L

    fun newShotUri(context: Context): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.filter { now - it.lastModified() > maxAgeMs }?.forEach { it.delete() }
        val file = File(dir, "shot-$now.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
    }
}

/**
 * Barra di composizione: allegati (foto, giochi), testo, microfono / invio.
 *
 * La foto si sceglie con il Photo Picker di sistema (`PickVisualMedia`, nessun permesso richiesto):
 * [onPhotoPicked] riceve l'URI (Swift: binding `photoItem`).
 *
 * @param recorder `null` dove le note vocali non sono disponibili (Cabina Pubblica).
 * @param isEditing modifica di un proprio messaggio: striscia "Modifica messaggio" con la X per annullare
 *   ([onCancelEditing]), niente allegati ne' microfono; l'invio manda la revisione invece di un nuovo messaggio.
 */
@Composable
fun ComposerBar(
    text: String,
    onTextChange: (String) -> Unit,
    recorder: VoiceNoteRecorder?,
    showsAttachments: Boolean,
    onSend: () -> Unit,
    onToggleRecording: () -> Unit,
    onStartGame: (GameKind) -> Unit,
    modifier: Modifier = Modifier,
    /** Giochi del menu (Swift `availableGames`): 1-a-1 tutti quelli a 2, gruppi da almeno 4 solo lo Scopone. */
    games: List<GameKind> = listOf(GameKind.TIC_TAC_TOE, GameKind.BATTLESHIP),
    onPhotoPicked: (Uri) -> Unit = {},
    canTakePhoto: () -> Boolean = { true },
    onCameraDenied: () -> Unit = {},
    isEditing: Boolean = false,
    onCancelEditing: () -> Unit = {},
    /** "Posizione" nel menu allegati (chat 1-a-1 e gruppi); `null` = voce assente. */
    onShareLocation: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val attachmentsVisible = showsAttachments && !isEditing
    val isRecording = recorder?.isRecording?.collectAsStateWithLifecycle()?.value ?: false
    val canSend = text.isNotBlank()
    var showGames by remember { mutableStateOf(false) }
    var showPhotoMenu by remember { mutableStateOf(false) }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPhotoPicked(uri)
    }
    // URI dello scatto in corso: salvabile, perche' l'Activity puo' essere ricreata mentre e' aperta la fotocamera.
    var pendingShot by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val shot = pendingShot
        pendingShot = null
        if (saved && shot != null) onPhotoPicked(Uri.parse(shot))
    }
    val launchGallery = {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val launchCamera = {
        val uri = CameraShots.newShotUri(context)
        pendingShot = uri.toString()
        try {
            takePicture.launch(uri)
        } catch (_: ActivityNotFoundException) {
            pendingShot = null
            launchGallery()
        }
    }
    // Negata in passato: di nuovo il dialogo di sistema se possibile, altrimenti "Apri Impostazioni".
    val cameraPermission = rememberPermissionRequest(DeniedPermission.camera, Manifest.permission.CAMERA)

    Column(modifier = modifier.fillMaxWidth().background(AeroTheme.background)) {
    if (isEditing) EditingStrip(onCancel = onCancelEditing)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (attachmentsVisible && !isRecording) {
            Box {
                val gamesDescription = stringResource(R.string.chat_minigames)
                HapticButton(
                    onClick = { showGames = true },
                    tint = AeroTheme.accentCyan,
                    modifier = Modifier.semantics { contentDescription = gamesDescription },
                ) {
                    Icon(Icons.Outlined.SportsEsports, contentDescription = null, modifier = Modifier.size(28.dp))
                }
                // Ordine di dichiarazione = `availableGames` di iOS: aprendosi verso l'alto il primo gioco sta sopra il pulsante.
                AeroDropdownMenu(
                    expanded = showGames,
                    onDismissRequest = { showGames = false },
                    items = games.map { kind ->
                        AeroMenuItem(gameMenuTitle(kind), gameMenuIcon(kind), hapticOnTap = true) {
                            showGames = false
                            onStartGame(kind)
                        }
                    },
                )
            }
            val photoDescription = stringResource(R.string.chat_send_photo)
            Box {
                HapticButton(
                    onClick = {
                        // Con la fotocamera (o la posizione) il pulsante apre il menu allegati (come iOS).
                        if (hasCamera || onShareLocation != null) showPhotoMenu = true else launchGallery()
                    },
                    tint = AeroTheme.accentCyan,
                    modifier = Modifier.semantics { contentDescription = photoDescription },
                ) {
                    Icon(Icons.Outlined.Photo, contentDescription = null, modifier = Modifier.size(28.dp))
                }
                // Come iOS: Scatta foto, Scegli dalla libreria (verso l'alto lo scatto resta sopra il pulsante).
                AeroDropdownMenu(
                    expanded = showPhotoMenu,
                    onDismissRequest = { showPhotoMenu = false },
                    items = buildList {
                        if (hasCamera) {
                            add(
                                AeroMenuItem(stringResource(R.string.chat_take_photo), Icons.Outlined.PhotoCamera, hapticOnTap = true) {
                                    showPhotoMenu = false
                                    // Come iOS: niente scatto se le foto non possono partire (serve link diretto).
                                    if (canTakePhoto()) {
                                        // Il manifest dichiara CAMERA (scanner QR): senza permesso concesso
                                        // ACTION_IMAGE_CAPTURE lancerebbe SecurityException.
                                        cameraPermission.run(onDenied = onCameraDenied) { launchCamera() }
                                    }
                                },
                            )
                        }
                        add(
                            AeroMenuItem(stringResource(R.string.chat_choose_from_gallery), Icons.Outlined.PhotoLibrary, hapticOnTap = true) {
                                showPhotoMenu = false
                                launchGallery()
                            },
                        )
                        if (onShareLocation != null) {
                            add(
                                AeroMenuItem(stringResource(R.string.growth14_location), Icons.Outlined.LocationOn, hapticOnTap = true) {
                                    showPhotoMenu = false
                                    onShareLocation()
                                },
                            )
                        }
                    },
                )
            }
        }

        if (isRecording) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .background(AeroTheme.surface, CircleShape)
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                recorder?.let { RecordingMeter(it) }
                Text(
                    stringResource(R.string.chat_recording_tap_send),
                    style = AeroTheme.footnote,
                    color = AeroTheme.textSecondary,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
            }
        } else {
            MessageField(
                text = text,
                onTextChange = onTextChange,
                onSubmit = { if (canSend) onSend() },
                modifier = Modifier.weight(1f),
            )
        }

        if (canSend && !isRecording) {
            val description = stringResource(R.string.chat_send)
            HapticButton(
                onClick = onSend,
                tint = AeroTheme.accentCyan,
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                Icon(Icons.Filled.ArrowCircleUp, contentDescription = null, modifier = Modifier.size(42.dp))
            }
        } else if (attachmentsVisible) {
            val description = stringResource(if (isRecording) R.string.chat_stop_send_voice else R.string.chat_record_voice)
            HapticButton(
                onClick = onToggleRecording,
                tint = if (isRecording) Color.Red else AeroTheme.accentCyan,
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                if (isRecording) {
                    Icon(Icons.Filled.StopCircle, contentDescription = null, modifier = Modifier.size(42.dp))
                } else {
                    // SF Symbol `mic.circle.fill`: microfono scuro su cerchio pieno.
                    Box(
                        Modifier.size(38.dp).background(AeroTheme.accentCyan, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
    }
}

/** Striscia "Modifica messaggio" sopra il campo, con la X per annullare la modifica. */
@Composable
private fun EditingStrip(onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AeroTheme.surface)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Edit, contentDescription = null, tint = AeroTheme.accentCyan, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.revisions_editing_title),
            style = AeroTheme.captionSemibold,
            color = AeroTheme.accentCyan,
            modifier = Modifier.weight(1f),
        )
        val description = stringResource(R.string.revisions_cancel_editing)
        HapticButton(
            onClick = onCancel,
            tint = AeroTheme.textSecondary,
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

/** Campo di testo multi-riga (1...5 righe) con segnaposto "Messaggio" e invio dalla tastiera. */
@Composable
private fun MessageField(text: String, onTextChange: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier) {
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier,
        textStyle = AeroTheme.body.copy(color = AeroTheme.textPrimary),
        cursorBrush = SolidColor(AeroTheme.accentCyan),
        minLines = 1,
        maxLines = 5,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        decorationBox = { inner ->
            Box(
                Modifier
                    .background(AeroTheme.surface, RoundedCornerShape(20.dp))
                    .defaultMinSize(minHeight = 40.dp)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(stringResource(R.string.chat_message_placeholder), style = AeroTheme.body, color = AeroTheme.textSecondary)
                }
                inner()
            }
        },
    )
}

/**
 * Livello e durata della registrazione (aggiornati ogni 100 ms): letti solo qui, cosi' il resto
 * della barra e la chat non vengono ricomposti a ogni tick.
 */
@Composable
private fun RecordingMeter(recorder: VoiceNoteRecorder) {
    val level by recorder.level.collectAsStateWithLifecycle()
    val elapsed by recorder.elapsed.collectAsStateWithLifecycle()
    Box(Modifier.size(10.dp).alpha(0.5f + level * 0.5f).background(Color.Red, CircleShape))
    Text(
        MessageBubble.durationLabel(elapsed),
        style = AeroTheme.body.copy(fontFeatureSettings = "tnum"),
        color = AeroTheme.textPrimary,
    )
}

@Composable
private fun gameMenuTitle(kind: GameKind): String = when (kind) {
    GameKind.TIC_TAC_TOE -> stringResource(R.string.chat_tictactoe_challenge)
    GameKind.BATTLESHIP -> stringResource(R.string.chat_battleship)
    GameKind.SCOPA -> stringResource(R.string.games_scopa)
    GameKind.BRISCOLA -> stringResource(R.string.games_briscola)
    GameKind.SCOPONE -> stringResource(R.string.games_scopone)
    else -> stringResource(R.string.chat_minigames)
}

private fun gameMenuIcon(kind: GameKind): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    GameKind.TIC_TAC_TOE -> Icons.Outlined.Tag
    GameKind.BATTLESHIP -> Icons.Outlined.DirectionsBoat
    GameKind.BRISCOLA -> Icons.Outlined.Layers
    else -> Icons.Outlined.Style
}
