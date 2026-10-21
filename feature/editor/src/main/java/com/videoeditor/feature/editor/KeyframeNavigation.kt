package com.videoeditor.feature.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.videoeditor.core.model.*

/** Selected key identity survives transport movement so Move/Delete can act on that same key. */
@Composable
internal fun KeyframeNavigation(track: KeyframeTrack, localTime: Long, start: Long,
    seek: (Long) -> Unit, update: (KeyframeTrack) -> Unit) {
    val previous = track.keyframes.filter { it.timeMicros in 0 until localTime }.maxByOrNull { it.timeMicros }
    val next = track.keyframes.filter { it.timeMicros > localTime }.minByOrNull { it.timeMicros }
    val current = track.keyframes.firstOrNull { it.timeMicros == localTime }
    var selectedId by remember(track.propertyName) { mutableStateOf<String?>(null) }
    LaunchedEffect(current?.id) { if (current != null) selectedId = current.id }
    val selected = track.keyframes.firstOrNull { it.id == selectedId } ?: current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { previous?.let { selectedId = it.id; seek(start + it.timeMicros) } }, enabled = previous != null,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous") }
        TextButton(onClick = { next?.let { selectedId = it.id; seek(start + it.timeMicros) } }, enabled = next != null,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Next") }
        if (selected != null) {
            TextButton(onClick = { update(track.copy(keyframes = track.keyframes
                .filterNot { it.id != selected.id && it.timeMicros == localTime }
                .map { if (it.id == selected.id) it.copy(timeMicros = localTime) else it }.sortedBy { it.timeMicros })) },
                enabled = localTime >= 0 && selected.timeMicros != localTime) { Text("Move here") }
            TextButton(onClick = { update(track.copy(keyframes = track.keyframes.filterNot { it.id == selected.id })); selectedId = null }) { Text("Delete key") }
        }
        if (current != null) KeyframeInterpolation.entries.forEach { interpolation ->
            EditorChoice(interpolation.name, current.interpolation == interpolation, {
                update(track.copy(keyframes = track.keyframes.map {
                    if (it.id == current.id) it.copy(interpolation = interpolation) else it
                }))
            })
        }
    }
}
