package com.videoeditor.feature.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.videoeditor.core.model.Clip
import com.videoeditor.editing.timeline.RulerScale
import java.math.BigDecimal

/** Source values remain integer microseconds; project placement is explicitly read-only. */
@Composable
internal fun TrimToolPanel(clip: Clip, hasTransitions: Boolean, playhead: () -> Long, onCancel: () -> Unit, onApply: (Long, Long) -> Unit) {
    fun seconds(value: Long): String {
        val minutes = value / 60_000_000
        val remainder = BigDecimal.valueOf(value % 60_000_000, 6).stripTrailingZeros().toPlainString()
        val whole = remainder.substringBefore('.').padStart(2, '0')
        val fraction = remainder.substringAfter('.', "0")
        return "${minutes.toString().padStart(2, '0')}:$whole.$fraction"
    }
    var start by remember(clip.id) { mutableStateOf(seconds(clip.sourceStartMicros)) }
    var end by remember(clip.id) { mutableStateOf(seconds(clip.sourceEndMicros)) }
    fun micros(value: String): Long? = runCatching {
        val parts = value.split(':')
        require(parts.size in 1..2)
        val seconds = if (parts.size == 2) {
            require(parts[0].toLong() >= 0 && BigDecimal(parts[1]) >= BigDecimal.ZERO && BigDecimal(parts[1]) < BigDecimal(60))
            BigDecimal(parts[0]).multiply(BigDecimal(60)).add(BigDecimal(parts[1]))
        } else BigDecimal(value)
        seconds.movePointRight(6).longValueExact()
    }.getOrNull()
    val startUs = micros(start)
    val endUs = micros(end)
    val valid = startUs != null && endUs != null && startUs >= 0 && endUs > startUs && endUs - startUs >= 100_000
    EditorToolPanel("Trim video", onCancel,
        { start = seconds(clip.sourceStartMicros); end = seconds(clip.sourceEndMicros) },
        { if (valid) onApply(startUs!!, endUs!!) }, applyEnabled = valid) {
        EditorSegments(listOf("Project Trim", "Source Range"), "Source Range", {}, setOf("Project Trim"))
        if (hasTransitions) Text("Joins without enough footage are removed with the trim; Undo restores them.",
            color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorNumberField(start, { start = it }, Modifier.weight(1f), "Start", keyboardType = androidx.compose.ui.text.input.KeyboardType.Text)
            EditorNumberField(if (valid) seconds(((endUs!! - startUs!!) / clip.playbackSpeed).toLong()) else "—", {},
                Modifier.weight(1f), "Duration", readOnly = true, selected = true)
            EditorNumberField(end, { end = it }, Modifier.weight(1f), "End", keyboardType = androidx.compose.ui.text.input.KeyboardType.Text)
        }
        Text("Source range · project ${formatEditorTimecode(clip.timelineStartMicros)}",
            color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(vertical = 4.dp))
        if (!valid) Text("Enter a valid source range of at least 0.1 seconds.",
            color = EditorColor.Error, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { start = seconds(clip.sourceTimeAt(playhead().coerceIn(clip.timelineStartMicros, clip.timelineEndMicros))) },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Set start to Playhead")
        }
    }
}
