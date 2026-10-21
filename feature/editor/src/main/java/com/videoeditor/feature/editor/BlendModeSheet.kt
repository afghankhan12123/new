package com.videoeditor.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.videoeditor.core.model.BlendMode
import com.videoeditor.feature.editor.EditorColor as VEColor

/**
 * Stage 9 Blend Mode Tool Sheet.
 * Direct selection of creative GPU blend modes: Normal, Multiply, Screen, Overlay, Darken, Lighten, Add.
 */
@Composable
internal fun BlendModeSheet(
    viewModel: EditorViewModel,
    overlayId: String,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val project = state.project ?: return
    val layer = project.timeline.overlayClips.firstOrNull { it.id == overlayId } ?: return

    var selectedMode by remember(overlayId) { mutableStateOf(layer.blendMode) }
    var opacity by remember(overlayId) { mutableStateOf(layer.opacity) }
    val initialMode = remember(overlayId) { layer.blendMode }
    val initialOpacity = remember(overlayId) { layer.opacity }

    fun updateBlend(mode: BlendMode, op: Float) {
        selectedMode = mode
        opacity = op
        viewModel.previewOverlayDraft(layer.copy(blendMode = mode, opacity = op))
    }

    DisposableEffect(overlayId) { onDispose { viewModel.restoreToolPreview() } }

    EditorToolPanel(
        title = "Blend Mode",
        onCancel = {
            viewModel.restoreToolPreview()
            onDismiss()
        },
        onReset = {
            updateBlend(BlendMode.Normal, 1.0f)
        },
        onApply = {
            viewModel.updateOverlay(layer.copy(blendMode = selectedMode, opacity = opacity))
            onDismiss()
        },
        modifier = Modifier.testTag("blend-mode-sheet")
    ) {
        Text("CREATIVE BLEND MODES", color = VEColor.DarkMutedText, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))

        // Grid of blend modes
        val modes = BlendMode.values().toList()
        modes.chunked(4).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { mode ->
                    val isSelected = mode == selectedMode
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) VEColor.PrimaryBlue.copy(alpha = 0.25f) else VEColor.DarkSecondarySurface)
                            .border(1.dp, if (isSelected) VEColor.AccentCyan else VEColor.DarkBorder, RoundedCornerShape(8.dp))
                            .clickable { updateBlend(mode, opacity) }
                            .testTag("blend-mode-${mode.name}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            mode.displayName,
                            color = if (isSelected) VEColor.AccentCyan else Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Opacity slider
        EditorValueSlider(
            label = "Opacity",
            value = opacity,
            range = 0f..1f,
            display = "${(opacity * 100).toInt()}%",
            onChange = { updateBlend(selectedMode, it) },
            modifier = Modifier.testTag("blend-opacity-slider")
        )
    }
}
