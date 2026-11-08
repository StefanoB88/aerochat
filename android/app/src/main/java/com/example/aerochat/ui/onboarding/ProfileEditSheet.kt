package com.example.aerochat.ui.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.aerochat.R
import com.example.aerochat.app.LocalAppEnvironment
import com.example.aerochat.core.media.ImageProcessor
import com.example.aerochat.core.protocol.MeshProtocol
import com.example.aerochat.ui.AeroTheme
import com.example.aerochat.ui.components.AeroSheet
import com.example.aerochat.ui.components.AvatarView
import com.example.aerochat.ui.components.Haptics
import com.example.aerochat.ui.components.HapticButton
import com.example.aerochat.ui.components.SheetBarButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modifica del profilo in qualsiasi momento: foto, nickname e colore vengono annunciati ai contatti.
 * Si presenta da solo come foglio modale; [onDismiss] lo rimuove dalla composizione.
 */
@Composable
fun ProfileEditSheet(onDismiss: () -> Unit) {
    val environment = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var nickname by remember { mutableStateOf(environment.nickname) }
    var colorHex by remember { mutableStateOf(environment.avatarColorHex) }
    var avatarImage by remember { mutableStateOf(environment.avatarImage) }
    var isProcessingPhoto by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val trimmed = nickname.trim()

    fun processPhoto(uri: Uri) {
        isProcessingPhoto = true
        errorMessage = null
        scope.launch {
            try {
                avatarImage = withContext(Dispatchers.Default) {
                    val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException(uri.toString())
                    ImageProcessor.prepareAvatar(imageData = data)
                }
                Haptics.impact(Haptics.ImpactStyle.light)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.shell_profile_photo_unusable, e.localizedMessage ?: e.toString())
                Haptics.error()
            } finally {
                isProcessingPhoto = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) processPhoto(uri)
    }

    AeroSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.shell_profile_title),
        leading = { dismiss -> SheetBarButton(stringResource(R.string.shell_cancel), dismiss) },
        trailing = { dismiss ->
            if (isSaving) {
                CircularProgressIndicator(
                    color = AeroTheme.accentCyan, strokeWidth = 2.dp,
                    modifier = Modifier.padding(horizontal = 16.dp).size(20.dp),
                )
            } else {
                SheetBarButton(
                    stringResource(R.string.shell_save),
                    onClick = {
                        if (trimmed.length < 2 || isSaving) return@SheetBarButton
                        isSaving = true
                        scope.launch {
                            try {
                                environment.updateProfile(nickname = trimmed, avatarColorHex = colorHex, avatarImage = avatarImage)
                                Haptics.success()
                                dismiss()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                errorMessage = context.getString(R.string.shell_profile_save_failed, e.localizedMessage ?: e.toString())
                                Haptics.error()
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    enabled = trimmed.length >= 2 && !isProcessingPhoto,
                    bold = true,
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // Foto profilo.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HapticButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !isProcessingPhoto,
                    contentDescription = stringResource(R.string.shell_profile_choose_photo),
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Box(contentAlignment = Alignment.Center) {
                            AvatarView(imageData = avatarImage, colorHex = colorHex, size = 120.dp)
                            if (isProcessingPhoto) {
                                Box(Modifier.size(120.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)))
                                CircularProgressIndicator(color = Color.White)
                            }
                        }
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(AeroTheme.accentCyan),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (avatarImage != null) {
                    HapticButton(onClick = { avatarImage = null; Haptics.tap() }, destructive = true) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.shell_profile_remove_photo), style = AeroTheme.footnote)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.shell_profile_nickname_header), style = AeroTheme.captionSemibold, color = AeroTheme.textSecondary)
                NicknameField(
                    value = nickname,
                    onValueChange = { nickname = it.take(MeshProtocol.maxNicknameLength) },
                )
            }

            // Con la foto profilo il colore non si vede: scelta disattivata finche' la foto non viene rimossa.
            val colorEnabled = avatarImage == null
            Column(
                Modifier.fillMaxWidth().alpha(if (colorEnabled) 1f else 0.35f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.shell_profile_color_header), style = AeroTheme.captionSemibold, color = AeroTheme.textSecondary)
                AvatarColorPicker(selected = colorHex, onSelect = { colorHex = it }, circleSize = 38, enabled = colorEnabled)
            }

            errorMessage?.let { Text(it, style = AeroTheme.footnote, color = Color.Red) }

            Text(
                stringResource(R.string.shell_profile_visibility_note),
                style = AeroTheme.caption,
                color = AeroTheme.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
