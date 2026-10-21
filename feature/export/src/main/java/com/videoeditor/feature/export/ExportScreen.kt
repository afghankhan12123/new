package com.videoeditor.feature.export

import coil.request.videoFrameMicros
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videoeditor.core.designsystem.color.VEColor
import com.videoeditor.core.designsystem.component.GradientButton
import com.videoeditor.core.designsystem.gradient.VEGradient
import com.videoeditor.core.designsystem.spacing.VERadius
import com.videoeditor.core.designsystem.spacing.VESpacing
import com.videoeditor.core.model.ExportQuality
import com.videoeditor.core.model.ExportResolution
import com.videoeditor.core.model.FrameRate
import com.videoeditor.core.model.Project

@Composable
fun ExportScreen(
    projectId: String,
    onBack: () -> Unit,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val galleryPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.saveToGallery() else viewModel.galleryPermissionDenied()
    }
    val filesDestination = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri -> viewModel.saveToFiles(uri) }

    LaunchedEffect(projectId) {
        viewModel.loadProject(projectId)
    }

    // The export flow stays in the dark editing workspace rather than jumping to
    // a light page, so it feels visually connected to the editor.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VEColor.DarkBackground),
    ) {
        TopBar(
            title = when (uiState.phase) {
                ExportPhase.Exporting -> "Exporting…"
                ExportPhase.Completed -> "Export"
                ExportPhase.Failed -> "Export Failed"
                else -> "Export"
            },
            onBack = onBack,
        )

        when {
            uiState.project == null && uiState.phase == ExportPhase.Configuring -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = VEColor.PrimaryBlue)
                }
            }
            uiState.phase == ExportPhase.Configuring -> ExportConfigContent(
                uiState = uiState,
                estimatedBytes = viewModel.estimatedSizeBytes(),
                onResolution = viewModel::setResolution,
                onQuality = viewModel::setQuality,
                onFrameRate = viewModel::setFrameRate,
                onExport = viewModel::startExport,
            )
            uiState.phase == ExportPhase.Exporting -> ExportProgressContent(
                project = uiState.project,
                progress = uiState.progress,
                stage = uiState.stage,
                onCancel = viewModel::cancelExport,
            )
            uiState.phase == ExportPhase.Completed -> ExportCompletedContent(
                uiState = uiState,
                onPlay = {
                    viewModel.shareUri()?.let { uri ->
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = android.content.ClipData.newUri(context.contentResolver, "video", uri)
                        }
                        val resolved = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                        for (info in resolved) {
                            context.grantUriPermission(info.activityInfo.packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = android.content.Intent.createChooser(intent, "Play video")
                        for (info in context.packageManager.queryIntentActivities(chooser, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)) {
                            context.grantUriPermission(info.activityInfo.packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(chooser) }.onFailure {
                            runCatching { context.startActivity(intent) }.onFailure {
                                android.widget.Toast.makeText(context, "No video player is installed. Save or share the video to open it elsewhere.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onShare = {
                    viewModel.shareUri()?.let { uri -> shareVideo(context, uri) }
                },
                onSave = {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
                        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        galleryPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else viewModel.saveToGallery()
                },
                onFiles = { filesDestination.launch(uiState.outputFile?.name ?: "video.mp4") },
                onDone = onBack,
            )
            uiState.phase == ExportPhase.Failed -> ExportFailedContent(
                message = uiState.errorMessage ?: "Something went wrong.",
                onTryAgain = viewModel::startExport,
                onSettings = viewModel::changeSettings,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
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
                tint = Color.White,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ExportConfigContent(
    uiState: ExportUiState,
    estimatedBytes: Long,
    onResolution: (ExportResolution) -> Unit,
    onQuality: (ExportQuality) -> Unit,
    onFrameRate: (FrameRate) -> Unit,
    onExport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = VESpacing.Md),
    ) {
        ProjectCoverCard(uiState.project)
        Spacer(modifier = Modifier.height(VESpacing.Lg))

        SectionLabel("Resolution")
        OptionRow(
            options = ExportResolution.entries.map { it.label },
            selected = uiState.resolution.label,
            onSelect = { label ->
                ExportResolution.entries.firstOrNull { it.label == label }?.let(onResolution)
            },
        )
        Spacer(modifier = Modifier.height(VESpacing.Md))

        SectionLabel("Frame Rate")
        val sourceFps = uiState.sourceFrameRate
        val supported = FrameRate.entries.filter { sourceFps == null || it.fps <= sourceFps || it.fps == 30 }
        if (sourceFps != null) InfoRow("Source", "$sourceFps fps")
        OptionRow(
            options = supported.map { "${it.fps} fps" },
            selected = "${uiState.frameRate.fps} fps",
            onSelect = { label ->
                supported.firstOrNull { "${it.fps} fps" == label }?.let(onFrameRate)
            },
        )
        Text(
            when {
                sourceFps == null -> "Frame rate follows source clips and speed edits."
                uiState.frameRate.fps == sourceFps -> "Matches the source frame rate; no conversion is applied."
                uiState.frameRate.fps < sourceFps -> "Frames are re-timed to ${uiState.frameRate.fps} fps during export."
                else -> "Frame rate follows source clips and speed edits."
            },
            style = MaterialTheme.typography.bodySmall, color = VEColor.DarkSecondaryText)
        Spacer(modifier = Modifier.height(VESpacing.Md))

        SectionLabel("Quality")
        OptionRow(
            options = ExportQuality.entries.map { it.label },
            selected = uiState.quality.label,
            onSelect = { label ->
                ExportQuality.entries.firstOrNull { it.label == label }?.let(onQuality)
            },
        )
        Spacer(modifier = Modifier.height(VESpacing.Md))

        // Format is fixed to MP4 for the initial release; shown for clarity.
        InfoRow("Format", "MP4 (H.264 + AAC)")
        Spacer(modifier = Modifier.height(VESpacing.Md))

        Text(
            "Estimated size: ~${formatBytes(estimatedBytes)} (approximate)",
            style = MaterialTheme.typography.bodyMedium,
            color = VEColor.DarkSecondaryText,
        )
        Spacer(modifier = Modifier.height(VESpacing.Lg))
    }
    // The primary action is pinned below the scrollable settings: adding a
    // section must never push Export off the viewport. (Stage 7 regression fix:
    // the new Frame Rate section made the in-column button unreachable on
    // smaller viewports.)
    Box(modifier = Modifier.padding(horizontal = VESpacing.Md)
        .padding(top = VESpacing.Xs, bottom = VESpacing.Md)) {
        GradientButton(text = "Export Video", onClick = onExport)
    }
    }
}

/** The project's first-clip cover plus its rendered duration and resolution. */
@Composable
private fun ProjectCoverCard(project: Project?) {
    val context = LocalContext.current
    val cover = project?.let { exportCover(it) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(VERadius.Card))
            .background(VEColor.DarkSecondarySurface),
    ) {
        cover?.let { source ->
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(context)
                    .data(source.uri)
                    .videoFrameMicros(source.sourceMicros)
                    .apply {
                        if (source.isVideo) {
                            decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                        }
                    }
                    .memoryCacheKey(source.key)
                    .diskCacheKey(source.key)
                    .build(),
                contentDescription = "Project cover",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Duration badge, bottom-left over a scrim for legibility.
        project?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(VESpacing.Sm)
                    .clip(RoundedCornerShape(VERadius.Control))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = VESpacing.Sm, vertical = VESpacing.Xxs),
            ) {
                Text(
                    formatDuration(it.timeline.renderedDurationMicros),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = VEColor.DarkSecondaryText,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = VEColor.DarkSecondaryText,
        modifier = Modifier.padding(bottom = VESpacing.Xs),
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun OptionRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(VESpacing.Xs),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(VERadius.Button))
                    .background(
                        if (isSelected) VEGradient.primaryHorizontal
                        else androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(VEColor.DarkSecondarySurface, VEColor.DarkSecondarySurface),
                        )
                    )
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Color.White else VEColor.DarkSecondaryText,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ExportProgressContent(
    project: Project?,
    progress: Float,
    stage: com.videoeditor.core.media.export.ExportStage,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = VESpacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = VESpacing.Md)) { ProjectCoverCard(project) }
        Spacer(Modifier.height(VESpacing.Lg))
        Text("${"%.1f".format(java.util.Locale.ROOT, progress.coerceIn(0f, 1f) * 100)}%",
            style = MaterialTheme.typography.displaySmall, color = Color.White, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp).height(3.dp),
            color = VEColor.AccentCyan, trackColor = VEColor.DarkSecondarySurface)
        Spacer(modifier = Modifier.height(VESpacing.Lg))
        Text(
            if (progress >= 1f) "Finishing and checking the file…" else stageLabel(stage),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )
        Text(
            "Please don't close the app.",
            style = MaterialTheme.typography.bodySmall,
            color = VEColor.DarkSecondaryText,
        )
        Spacer(modifier = Modifier.height(VESpacing.Xl))
        if (progress < 1f && stage != com.videoeditor.core.media.export.ExportStage.Saving &&
            stage != com.videoeditor.core.media.export.ExportStage.Finalizing) {
            SecondaryActionButton("Cancel", Icons.Default.Close, onCancel,
                Modifier.fillMaxWidth().padding(horizontal = VESpacing.Xxl))
        }
    }
}

private fun stageLabel(stage: com.videoeditor.core.media.export.ExportStage): String = when (stage) {
    com.videoeditor.core.media.export.ExportStage.Preparing -> "Preparing project..."
    com.videoeditor.core.media.export.ExportStage.Rendering -> "Rendering video..."
    com.videoeditor.core.media.export.ExportStage.ProcessingAudio -> "Processing audio..."
    com.videoeditor.core.media.export.ExportStage.Finalizing -> "Finalizing output..."
    com.videoeditor.core.media.export.ExportStage.Saving -> "Saving video..."
}

@Composable
private fun ExportCompletedContent(
    uiState: ExportUiState,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onFiles: () -> Unit,
    onDone: () -> Unit,
) {
    val project = uiState.project
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = VESpacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(VESpacing.Lg))
        // Finished-video cover so the user can see what was exported.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clickable(onClick = onPlay)
                .clip(RoundedCornerShape(VERadius.Card))
                .background(VEColor.DarkSecondarySurface),
        ) {
            val cover = uiState.outputFile?.let {
                ExportCover(it.absolutePath, 500_000L, "output:${it.absolutePath}:${it.lastModified()}", true)
            } ?: project?.let { exportCover(it) }
            cover?.let { source ->
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(context)
                        .data(source.uri)
                        .videoFrameMicros(source.sourceMicros)
                        .apply {
                            if (source.isVideo) {
                                decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                            }
                        }
                        .memoryCacheKey(source.key)
                        .diskCacheKey(source.key)
                        .build(),
                    contentDescription = "Exported video",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(VESpacing.Lg))

        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(VEColor.Success.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = VEColor.Success, modifier = Modifier.size(32.dp))
        }
        Spacer(modifier = Modifier.height(VESpacing.Sm))
        Text(
            "Export Complete",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        // Wording reflects reality: the file was exported to app storage. It is
        // only described as saved to Photos once that copy actually succeeded.
        Text(
            if (uiState.savedToGallery) "Saved to Photos" else "Exported and ready to share",
            style = MaterialTheme.typography.bodyMedium,
            color = VEColor.DarkSecondaryText,
            textAlign = TextAlign.Center,
        )
        if (!uiState.savedToGallery) {
            uiState.saveMessage?.let {
                Spacer(modifier = Modifier.height(VESpacing.Xxs))
                Text(it, style = MaterialTheme.typography.bodySmall, color = VEColor.DarkMutedText)
            }
        }

        // Actual output metadata.
        Spacer(modifier = Modifier.height(VESpacing.Md))
        project?.let {
            InfoRow("Duration", uiState.outputDurationMicros?.let(::formatDuration) ?: "Unavailable")
            Spacer(modifier = Modifier.height(VESpacing.Xs))
            InfoRow("Resolution", uiState.outputDimensions ?: "Unavailable")
        }
        InfoRow("Frame timing", "Source timing (variable)")
        uiState.outputFrameRate?.let { InfoRow("Container rate", it) }
        uiState.outputFile?.let {
            Spacer(modifier = Modifier.height(VESpacing.Xs))
            InfoRow("Size", formatBytes(it.length()))
        }

        Spacer(modifier = Modifier.height(VESpacing.Lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VESpacing.Sm),
        ) {
            SecondaryActionButton("Share", Icons.Default.Share, onClick = onShare, modifier = Modifier.weight(1f))
            SecondaryActionButton(
                if (uiState.savedToGallery) "Saved to Photos" else "Save to Photos",
                Icons.Default.Check,
                onClick = onSave,
                enabled = !uiState.savedToGallery && !uiState.isSaving,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(VESpacing.Md))
        SecondaryActionButton("Save to Files", Icons.Default.Check, onClick = onFiles, enabled = !uiState.isSaving, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(VESpacing.Md))
        GradientButton(text = "Done", onClick = onDone)
        Spacer(modifier = Modifier.height(VESpacing.Xxl))
    }
}

@Composable
private fun SecondaryActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(VERadius.Button))
            .background(if (enabled) VEColor.DarkSecondarySurface else VEColor.DarkSecondarySurface.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) Color.White else VEColor.DarkMutedText, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(VESpacing.Xs))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else VEColor.DarkMutedText)
    }
}

@Composable
private fun ExportFailedContent(
    message: String,
    onTryAgain: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = VESpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(VEColor.Error.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, contentDescription = null, tint = VEColor.Error, modifier = Modifier.size(40.dp))
        }
        Spacer(modifier = Modifier.height(VESpacing.Md))
        Text(
            "Export Failed",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = VEColor.DarkSecondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = VESpacing.Xl),
        )
        Spacer(modifier = Modifier.height(VESpacing.Xl))
        GradientButton(
            text = "Try Again",
            onClick = onTryAgain,
            modifier = Modifier.padding(horizontal = VESpacing.Xxl),
        )
        Spacer(modifier = Modifier.height(VESpacing.Sm))
        SecondaryActionButton("Change Settings", Icons.Default.Tune, onSettings,
            Modifier.fillMaxWidth().padding(horizontal = VESpacing.Xxl))
        Spacer(Modifier.height(VESpacing.Sm))
        SecondaryActionButton("Return to Editor", Icons.AutoMirrored.Filled.ArrowBack, onBack,
            Modifier.fillMaxWidth().padding(horizontal = VESpacing.Xxl))
    }
}

private fun shareVideo(context: android.content.Context, uri: android.net.Uri) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newUri(context.contentResolver, "video", uri)
    }
    val chooser = android.content.Intent.createChooser(intent, "Share video")
    val resolved = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
    for (info in resolved) {
        context.grantUriPermission(info.activityInfo.packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    for (info in context.packageManager.queryIntentActivities(chooser, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)) {
        context.grantUriPermission(info.activityInfo.packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(chooser)
}

/** Cover identity for the export screens, mirroring the home project cover. */
private data class ExportCover(val uri: String, val sourceMicros: Long, val key: String, val isVideo: Boolean)

private fun exportCover(project: Project): ExportCover? {
    val clip = project.timeline.videoClips.firstOrNull() ?: return null
    val media = project.mediaRef(clip.mediaRefId) ?: return null
    val time = clip.sourceStartMicros + clip.sourceDurationMicros / 4
    return ExportCover(
        media.uri,
        time,
        "${media.uri}|${clip.sourceStartMicros}|${clip.sourceEndMicros}|${project.updatedAtMillis}",
        media.type == com.videoeditor.core.model.MediaType.Video,
    )
}

private fun formatDuration(micros: Long): String {
    val totalSeconds = micros / 1_000_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
