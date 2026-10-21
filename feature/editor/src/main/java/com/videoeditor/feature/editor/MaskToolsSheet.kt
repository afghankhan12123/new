package com.videoeditor.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoeditor.core.model.*
import com.videoeditor.feature.editor.EditorColor as VEColor

/**
 * Stage 9 Layer Mask Tool Sheet.
 * Provides geometric and gradient mask authoring with live preview, resolution-independent SDF feathering,
 * inversion, and keyframing for video and photo overlay layers.
 */
@Composable
internal fun MaskToolsSheet(
    viewModel: EditorViewModel,
    overlayId: String,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val project = state.project ?: return
    val layer = project.timeline.overlayClips.firstOrNull { it.id == overlayId } ?: return

    var maskDraft by remember(overlayId) {
        mutableStateOf(layer.mask ?: LayerMask(enabled = true))
    }
    val initialMask = remember(overlayId) { layer.mask }

    fun updateMask(updated: LayerMask?) {
        val time = (state.playheadMicros - layer.timelineStartMicros).coerceAtLeast(0L)
        val tracks = updated?.keyframeTracks?.mapValues { (name, track) ->
            val value = when (name) {
                "centerX" -> updated.centerX; "centerY" -> updated.centerY
                "scaleX" -> updated.scaleX; "scaleY" -> updated.scaleY
                "rotationDegrees" -> updated.rotationDegrees
                "feather" -> updated.feather; "opacity" -> updated.opacity
                else -> null
            }
            val previous = when (name) {
                "centerX" -> maskDraft.centerX; "centerY" -> maskDraft.centerY
                "scaleX" -> maskDraft.scaleX; "scaleY" -> maskDraft.scaleY
                "rotationDegrees" -> maskDraft.rotationDegrees
                "feather" -> maskDraft.feather; "opacity" -> maskDraft.opacity
                else -> null
            }
            if (value == null || value == previous) track else track.copy(keyframes = track.keyframes.map {
                if (it.timeMicros == time) it.copy(value = value) else it
            })
        }
        maskDraft = updated?.copy(keyframeTracks = tracks.orEmpty()) ?: LayerMask(enabled = false)
        viewModel.previewOverlayDraft(layer.copy(mask = if (updated == null) null else maskDraft))
    }

    DisposableEffect(overlayId) { onDispose { viewModel.restoreToolPreview() } }

    EditorToolPanel(
        title = "Layer Mask",
        onCancel = {
            viewModel.restoreToolPreview()
            onDismiss()
        },
        onReset = {
            updateMask(null)
        },
        onApply = {
            viewModel.setOverlayMask(overlayId, if (maskDraft.enabled) maskDraft else null)
            onDismiss()
        },
        modifier = Modifier.testTag("mask-tools-sheet")
    ) {
        // Mask Shape Selector
        Text(
            "MASK SHAPE",
            color = VEColor.DarkMutedText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // "None" option
            val isNone = !maskDraft.enabled
            Column(
                Modifier
                    .width(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isNone) VEColor.PrimaryBlue.copy(alpha = 0.25f) else VEColor.DarkSecondarySurface)
                    .border(1.dp, if (isNone) VEColor.AccentCyan else VEColor.DarkBorder, RoundedCornerShape(8.dp))
                    .clickable { updateMask(null) }
                    .padding(8.dp)
                    .testTag("mask-shape-none"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EditorIcon(Icons.Default.Close, "None", tint = if (isNone) VEColor.AccentCyan else Color.White, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(4.dp))
                Text("None", color = if (isNone) VEColor.AccentCyan else VEColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall)
            }

            MaskType.values().forEach { type ->
                val isSelected = maskDraft.enabled && maskDraft.type == type
                Column(
                    Modifier
                        .width(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) VEColor.PrimaryBlue.copy(alpha = 0.25f) else VEColor.DarkSecondarySurface)
                        .border(1.dp, if (isSelected) VEColor.AccentCyan else VEColor.DarkBorder, RoundedCornerShape(8.dp))
                        .clickable {
                            updateMask(maskDraft.copy(type = type, enabled = true))
                        }
                        .padding(8.dp)
                        .testTag("mask-shape-${type.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val icon = when (type) {
                        MaskType.Rectangle -> Icons.Outlined.Crop
                        MaskType.RoundedRectangle -> Icons.Outlined.Crop
                        MaskType.Circle -> Icons.Outlined.CheckCircle
                        MaskType.LinearGradient -> Icons.Outlined.Gradient
                        MaskType.Polygon -> Icons.Outlined.Star
                    }
                    EditorIcon(icon, type.displayName, tint = if (isSelected) VEColor.AccentCyan else Color.White, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        type.displayName,
                        color = if (isSelected) VEColor.AccentCyan else VEColor.DarkSecondaryText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        maxLines = 1
                    )
                }
            }
        }

        if (maskDraft.enabled) {
            Spacer(Modifier.height(8.dp))

            val currentPlayheadMicros = (state.playheadMicros - layer.timelineStartMicros).coerceAtLeast(0L)
            val scaleTrack = maskDraft.keyframeTracks["scaleX"]
            val allKf: List<VisualKeyframe> = scaleTrack?.keyframes.orEmpty()
            val hasKeyframe = allKf.any { it.timeMicros == currentPlayheadMicros }
            val currentKf: VisualKeyframe? = allKf.firstOrNull { it.timeMicros == currentPlayheadMicros }

            // Invert & Keyframe options
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = maskDraft.invert,
                        onCheckedChange = { updateMask(maskDraft.copy(invert = it)) },
                        modifier = Modifier.testTag("mask-invert-switch")
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Invert Mask", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }

                TextButton(
                    onClick = {
                        val evaluated = maskDraft.evaluatedMaskAt(currentPlayheadMicros)
                        val values = mapOf("scaleX" to evaluated.scaleX, "scaleY" to evaluated.scaleY,
                            "centerX" to evaluated.centerX, "centerY" to evaluated.centerY,
                            "rotationDegrees" to evaluated.rotationDegrees, "feather" to evaluated.feather, "opacity" to evaluated.opacity)
                        val tracks = maskDraft.keyframeTracks.toMutableMap()
                        values.forEach { (name, value) ->
                            val existing = tracks[name]?.keyframes.orEmpty().filterNot { it.timeMicros == currentPlayheadMicros }
                            tracks[name] = KeyframeTrack(name, if (hasKeyframe) existing else
                                (existing + VisualKeyframe(java.util.UUID.randomUUID().toString(), currentPlayheadMicros, value)).sortedBy { it.timeMicros })
                        }
                        updateMask(maskDraft.copy(keyframeTracks = tracks))
                    },
                    modifier = Modifier.testTag("mask-keyframe-toggle")
                ) {
                    EditorIcon(
                        if (hasKeyframe) Icons.Outlined.Diamond else Icons.Outlined.AddCircleOutline,
                        contentDescription = if (hasKeyframe) "Keyframe active" else "Add keyframe",
                        tint = if (hasKeyframe) VEColor.AccentCyan else VEColor.DarkSecondaryText,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (hasKeyframe) "Keyframed" else "+ Keyframe",
                        color = if (hasKeyframe) VEColor.AccentCyan else VEColor.DarkSecondaryText,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (scaleTrack != null && allKf.isNotEmpty()) key(overlayId) {
                KeyframeNavigation(scaleTrack, currentPlayheadMicros, layer.timelineStartMicros, viewModel::seekTo) { edited ->
                    val oldById = scaleTrack.keyframes.associateBy { it.id }
                    val retained = edited.keyframes.mapNotNull { key -> oldById[key.id]?.timeMicros }.toSet()
                    val newByTime = edited.keyframes.mapNotNull { key -> oldById[key.id]?.timeMicros?.let { it to key } }.toMap()
                    val tracks = maskDraft.keyframeTracks.mapValues { (_, track) -> track.copy(keyframes = track.keyframes
                        .filter { it.timeMicros !in allKf.map { key -> key.timeMicros } || it.timeMicros in retained }
                        .map { key -> newByTime[key.timeMicros]?.let { key.copy(timeMicros = it.timeMicros, interpolation = it.interpolation) } ?: key }
                        .sortedBy { it.timeMicros }) }
                    updateMask(maskDraft.copy(keyframeTracks = tracks))
                }
            }

                // Center X and Center Y sliders
                EditorValueSlider(
                    label = "Center X",
                    value = maskDraft.centerX,
                    range = -1.0f..1.0f,
                    display = "${(maskDraft.centerX * 100).toInt()}%",
                    onChange = { updateMask(maskDraft.copy(centerX = it)) },
                    modifier = Modifier.testTag("mask-center-x-slider")
                )

                EditorValueSlider(
                    label = "Center Y",
                    value = maskDraft.centerY,
                    range = -1.0f..1.0f,
                    display = "${(maskDraft.centerY * 100).toInt()}%",
                    onChange = { updateMask(maskDraft.copy(centerY = it)) },
                    modifier = Modifier.testTag("mask-center-y-slider")
                )

                // Feather slider
                EditorValueSlider(
                    label = "Feather",
                    value = maskDraft.feather,
                    range = 0.0f..0.5f,
                    display = "${(maskDraft.feather * 200).toInt()}%",
                    onChange = { updateMask(maskDraft.copy(feather = it)) },
                    modifier = Modifier.testTag("mask-feather-slider")
                )

                // Scale slider
                EditorValueSlider(
                    label = "Scale",
                    value = maskDraft.scaleX,
                    range = 0.1f..3.0f,
                    display = "${(maskDraft.scaleX * 100).toInt()}%",
                    onChange = { updateMask(maskDraft.copy(scaleX = it, scaleY = it)) },
                    modifier = Modifier.testTag("mask-scale-slider")
                )

            EditorValueSlider("Height", maskDraft.scaleY.coerceIn(.1f, 3f), .1f..3f,
                "${(maskDraft.scaleY * 100).toInt()}%", { updateMask(maskDraft.copy(scaleY = it)) })
            EditorValueSlider("Opacity", maskDraft.opacity.coerceIn(0f, 1f), 0f..1f,
                "${(maskDraft.opacity * 100).toInt()}%", { updateMask(maskDraft.copy(opacity = it)) })

            // Shape-specific controls
            if (maskDraft.type == MaskType.RoundedRectangle) {
                EditorValueSlider(
                    label = "Radius",
                    value = maskDraft.cornerRadius,
                    range = 0.0f..0.5f,
                    display = "${(maskDraft.cornerRadius * 200).toInt()}%",
                    onChange = { updateMask(maskDraft.copy(cornerRadius = it)) },
                    modifier = Modifier.testTag("mask-corner-radius-slider")
                )
            }

            if (maskDraft.type == MaskType.Polygon) {
                EditorValueSlider(
                    label = "Sides",
                    value = maskDraft.polygonSides.toFloat(),
                    range = 3f..8f,
                    display = "${maskDraft.polygonSides}",
                    onChange = { updateMask(maskDraft.copy(polygonSides = it.toInt())) },
                    modifier = Modifier.testTag("mask-sides-slider")
                )
            }

            // Rotation
            EditorValueSlider(
                label = "Rotate",
                value = maskDraft.rotationDegrees,
                range = -180f..180f,
                display = "${maskDraft.rotationDegrees.toInt()}°",
                onChange = { updateMask(maskDraft.copy(rotationDegrees = it)) },
                modifier = Modifier.testTag("mask-rotation-slider")
            )
        }
    }
}
