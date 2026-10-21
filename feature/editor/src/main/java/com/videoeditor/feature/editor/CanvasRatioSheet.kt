package com.videoeditor.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.videoeditor.core.model.*

@Composable
internal fun CanvasRatioSheet(current: CanvasSettings, onApply: (AspectRatioPreset, ScaleMode, Int) -> Unit,
    onDismiss: () -> Unit, onPreview: (AspectRatioPreset, ScaleMode, Int) -> Unit, title: String = "Crop & Ratio") {
    var selected by remember(current) { mutableStateOf(current.aspectRatio) }
    var mode by remember { mutableStateOf(current.scaleMode) }
    var background by remember { mutableStateOf(current.backgroundColorArgb) }
    LaunchedEffect(selected, mode, background) { onPreview(selected, mode, background) }
    EditorToolPanel(title, onDismiss, { selected = current.aspectRatio; mode = current.scaleMode; background = current.backgroundColorArgb },
        { onApply(selected, mode, background); onDismiss() }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(AspectRatioPreset.Original, AspectRatioPreset.Landscape16x9, AspectRatioPreset.Portrait9x16,
                AspectRatioPreset.Square1x1, AspectRatioPreset.Portrait4x5, AspectRatioPreset.Portrait3x4).forEach { preset ->
                EditorChoice(preset.label, selected == preset, { selected = preset })
            }
        }
        EditorSegments(listOf("Fit", "Fill"), mode.name, { mode = ScaleMode.valueOf(it) })
        Text("Background", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0xFF000000.toInt() to "Black", 0xFFFFFFFF.toInt() to "White", 0xFF14283B.toInt() to "Navy", 0xFF64748B.toInt() to "Slate").forEach { (argb, name) ->
                Box(Modifier.size(48.dp).border(if (background == argb) 2.dp else 1.dp,
                    if (background == argb) EditorColor.AccentCyan else EditorColor.DarkBorder, RoundedCornerShape(8.dp))
                    .padding(7.dp).background(Color(argb), RoundedCornerShape(6.dp))
                    .semantics { contentDescription = "$name canvas background" }.clickable { background = argb })
            }
        }
        Text("Select background color for letterboxed canvas. Source crop is available under Rotate & Transform.",
            color = EditorColor.DarkMutedText, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}
