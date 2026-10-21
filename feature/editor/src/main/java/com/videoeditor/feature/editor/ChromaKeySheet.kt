package com.videoeditor.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.videoeditor.core.model.ChromaKeySettings
import com.videoeditor.feature.editor.EditorColor as VEColor

/**
 * Stage 9 Chroma Key Tool Sheet.
 * Real-time GPU green screen / blue screen background keying with spill suppression.
 */
@Composable
internal fun ChromaKeySheet(
    viewModel: EditorViewModel,
    overlayId: String,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val project = state.project ?: return
    val layer = project.timeline.overlayClips.firstOrNull { it.id == overlayId } ?: return

    var chromaDraft by remember(overlayId) {
        mutableStateOf(layer.chromaKey ?: ChromaKeySettings(enabled = true))
    }
    val initialChroma = remember(overlayId) { layer.chromaKey }

    fun updateChroma(updated: ChromaKeySettings?) {
        chromaDraft = updated ?: ChromaKeySettings(enabled = false)
        viewModel.previewOverlayDraft(layer.copy(chromaKey = updated))
    }

    DisposableEffect(overlayId) { onDispose { viewModel.restoreToolPreview() } }

    EditorToolPanel(
        title = "Chroma Key",
        onCancel = {
            viewModel.restoreToolPreview()
            onDismiss()
        },
        onReset = {
            updateChroma(null)
        },
        onApply = {
            viewModel.setOverlayChromaKey(overlayId, if (chromaDraft.enabled) chromaDraft else null)
            onDismiss()
        },
        modifier = Modifier.testTag("chroma-key-sheet")
    ) {
        // Enable toggle
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable Chroma Key", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = chromaDraft.enabled,
                onCheckedChange = { updateChroma(chromaDraft.copy(enabled = it)) },
                modifier = Modifier.testTag("chroma-enable-switch")
            )
        }

        if (chromaDraft.enabled) {
            Spacer(Modifier.height(8.dp))

            // Key color selector presets: Green, Blue, Custom
            Text("KEY COLOR", color = VEColor.DarkMutedText, style = MaterialTheme.typography.labelSmall)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Green screen preset
                val isGreen = chromaDraft.keyColor == ChromaKeySettings.PRESET_GREEN
                Column(
                    Modifier
                        .clickable { updateChroma(chromaDraft.copy(keyColor = ChromaKeySettings.PRESET_GREEN)) }
                        .testTag("chroma-color-green"),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00FF00))
                            .border(2.dp, if (isGreen) Color.White else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isGreen) EditorIcon(Icons.Outlined.Check, "Selected", tint = Color.Black, modifier = Modifier.size(20.dp))
                    }
                    Text("Green", color = if (isGreen) VEColor.AccentCyan else VEColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall)
                }

                // Blue screen preset
                val isBlue = chromaDraft.keyColor == ChromaKeySettings.PRESET_BLUE
                Column(
                    Modifier
                        .clickable { updateChroma(chromaDraft.copy(keyColor = ChromaKeySettings.PRESET_BLUE)) }
                        .testTag("chroma-color-blue"),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0000FF))
                            .border(2.dp, if (isBlue) Color.White else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBlue) EditorIcon(Icons.Outlined.Check, "Selected", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Text("Blue", color = if (isBlue) VEColor.AccentCyan else VEColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(4.dp))

            // Tolerance slider
            EditorValueSlider(
                label = "Tolerance",
                value = chromaDraft.tolerance,
                range = 0.05f..0.8f,
                display = "${(chromaDraft.tolerance * 100).toInt()}%",
                onChange = { updateChroma(chromaDraft.copy(tolerance = it)) },
                modifier = Modifier.testTag("chroma-tolerance-slider")
            )

            // Softness slider
            EditorValueSlider(
                label = "Softness",
                value = chromaDraft.softness,
                range = 0.01f..0.5f,
                display = "${(chromaDraft.softness * 200).toInt()}%",
                onChange = { updateChroma(chromaDraft.copy(softness = it)) },
                modifier = Modifier.testTag("chroma-softness-slider")
            )

            // Spill suppression slider
            EditorValueSlider(
                label = "Spill",
                value = chromaDraft.spillSuppression,
                range = 0.0f..1.0f,
                display = "${(chromaDraft.spillSuppression * 100).toInt()}%",
                onChange = { updateChroma(chromaDraft.copy(spillSuppression = it)) },
                modifier = Modifier.testTag("chroma-spill-slider")
            )

            // Overall opacity slider
            EditorValueSlider(
                label = "Opacity",
                value = chromaDraft.opacity,
                range = 0.0f..1.0f,
                display = "${(chromaDraft.opacity * 100).toInt()}%",
                onChange = { updateChroma(chromaDraft.copy(opacity = it)) },
                modifier = Modifier.testTag("chroma-opacity-slider")
            )
        }
    }
}
