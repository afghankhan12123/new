package com.videoeditor.feature.mediaimport

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videoeditor.core.designsystem.component.GradientButton
import com.videoeditor.core.designsystem.gradient.VEGradient
import com.videoeditor.core.designsystem.spacing.VERadius
import com.videoeditor.core.designsystem.spacing.VESpacing
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType

@Composable
fun MediaImportScreen(
    onMediaSelected: (String) -> Unit,
    onBack: () -> Unit,
    captureOnStart: Boolean = false,
    viewModel: MediaImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val cameraUriString = androidx.compose.runtime.saveable.rememberSaveable {
        val file = java.io.File(java.io.File(context.filesDir, "captures").apply { mkdirs() }, "capture-${System.currentTimeMillis()}.mp4")
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
    }
    val cameraUri = Uri.parse(cameraUriString)
    var cameraStarted by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success) viewModel.onMediaPicked(listOf(cameraUri))
        else context.contentResolver.delete(cameraUri, null, null)
    }
    androidx.compose.runtime.LaunchedEffect(captureOnStart) {
        if (captureOnStart && !cameraStarted) try { cameraStarted = true; camera.launch(cameraUri) }
        catch (_: android.content.ActivityNotFoundException) { viewModel.reportError("No camera application is available. Import an existing video instead.") }
    }

    // Android Photo Picker: privacy-preserving multi-select, no storage permission.
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris: List<Uri> ->
        viewModel.onMediaPicked(uris)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopBar(onBack = onBack)

        uiState.error?.let { Text(it, color = com.videoeditor.core.designsystem.color.VEColor.Error, modifier = Modifier.padding(VESpacing.Md)) }
        if (uiState.selectedMedia.isEmpty()) {
            EmptyPickerState(
                isProcessing = uiState.isProcessing,
                onPick = {
                    pickerLauncher.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageAndVideo
                        )
                    )
                },
            )
        } else {
            SelectionGrid(
                modifier = Modifier.weight(1f),
                media = uiState.selectedMedia,
                onRemove = viewModel::removeMedia,
                onAddMore = {
                    pickerLauncher.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageAndVideo
                        )
                    )
                },
            )
            Spacer(modifier = Modifier.height(VESpacing.Md))
            Column(modifier = Modifier.padding(horizontal = VESpacing.Md)) {
                Text(
                    if (uiState.selectedMedia.size == 1) "1 item selected"
                    else "${uiState.selectedMedia.size} items selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                uiState.error?.let {
                    Spacer(modifier = Modifier.height(VESpacing.Xs))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = com.videoeditor.core.designsystem.color.VEColor.Error,
                    )
                }
                Spacer(modifier = Modifier.height(VESpacing.Md))
                GradientButton(
                    text = "Next",
                    onClick = { viewModel.createProject(onCreated = onMediaSelected) },
                )
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VESpacing.Xs, vertical = VESpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            "Import Media",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyPickerState(isProcessing: Boolean, onPick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (isProcessing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(VESpacing.Md))
                Text(
                    "Importing media...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(VESpacing.Xl),
            ) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(modifier = Modifier.height(VESpacing.Md))
                Text(
                    "Select videos and photos",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(VESpacing.Xs))
                Text(
                    "Choose media from your device to start editing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(VESpacing.Lg))
                GradientButton(text = "Choose Media", onClick = onPick)
            }
        }
    }
}

@Composable
private fun SelectionGrid(
    modifier: Modifier = Modifier,
    media: List<MediaRef>,
    onRemove: (String) -> Unit,
    onAddMore: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier
            .padding(horizontal = VESpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(VESpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(VESpacing.Xs),
    ) {
        items(media, key = { it.id }) { ref ->
            MediaThumb(ref = ref, onRemove = { onRemove(ref.id) })
        }
        item {
            Box(
                modifier = Modifier
                    .height(96.dp)
                    .clip(RoundedCornerShape(VERadius.Control))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onAddMore),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+ Add",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MediaThumb(ref: MediaRef, onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .height(96.dp)
            .clip(RoundedCornerShape(VERadius.Control))
            .background(VEGradient.primary),
    ) {
        coil.compose.AsyncImage(model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data(Uri.parse(ref.uri)).apply {
                if (ref.type == MediaType.Video) decoderFactory(coil.decode.VideoFrameDecoder.Factory())
            }.build(), contentDescription = ref.displayName,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(VESpacing.Xs)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                if (ref.type == MediaType.Photo) "Photo" else {
                    val seconds = (ref.durationMicros ?: 0L) / 1_000_000L
                    String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
                },
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
