package com.videoeditor.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videoeditor.editing.timeline.selectedAudioClipId
import com.videoeditor.editing.timeline.videoClipId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** Observe only eligibility here; the shell deliberately removes fast playhead coordinates. */
@Composable
internal fun SplitToolPanel(viewModel: EditorViewModel, onClose: () -> Unit) {
    val eligibility = remember(viewModel) { viewModel.uiState.map { state ->
        val video = state.project?.timeline?.videoClips?.firstOrNull { it.id == state.selection.videoClipId }
        val audio = state.project?.timeline?.audioClips?.firstOrNull { it.id == state.selection.selectedAudioClipId }
        val start = video?.timelineStartMicros ?: audio?.timelineStartMicros
        val end = video?.timelineEndMicros ?: audio?.let { it.timelineStartMicros + it.timelineDurationMicros }
        start != null && end != null && video?.isPhoto != true && state.playheadMicros > start && state.playheadMicros < end
    }.distinctUntilChanged() }
    val eligible by eligibility.collectAsStateWithLifecycle(initialValue = false)
    EditorToolPanel("Split clip", onClose, null, { viewModel.splitSelectedClip(); onClose() }, applyEnabled = eligible) {
        Row(Modifier.fillMaxWidth().background(EditorColor.PrimaryBlue.copy(alpha = .15f), RoundedCornerShape(8.dp))
            .border(1.dp, EditorColor.AccentCyan.copy(alpha = .8f), RoundedCornerShape(8.dp)).heightIn(min = 56.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            EditorIcon(Icons.Outlined.ContentCut, null, tint = EditorColor.AccentCyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Split at Playhead", color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(if (eligible) "Divide this clip at the playhead." else "Move the playhead inside a selected clip.",
                color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.bodySmall)
        }
        }
    }
}
