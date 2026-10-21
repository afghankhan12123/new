package com.videoeditor.feature.editor.timeline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.VolumeEnvelope
import com.videoeditor.core.model.VolumePoint
import com.videoeditor.editing.timeline.SnapEngine
import com.videoeditor.editing.timeline.TimelineGeometry
import com.videoeditor.editing.timeline.TimelineLayout
import com.videoeditor.editing.timeline.TimelineSelection
import com.videoeditor.editing.timeline.TrackLanes
import com.videoeditor.feature.editor.EditorUiState
import kotlin.math.hypot

/** Shared drawing/hit geometry, in unclipped viewport coordinates. */
internal data class AutomationGeometry(val clip: AudioClip, val bounds: Rect) {
    fun position(point: VolumePoint): Offset = Offset(
        bounds.left + (point.sourcePositionMicros - clip.sourceStartMicros).toFloat() /
            clip.sourceDurationMicros.coerceAtLeast(1) * bounds.width,
        bounds.bottom - (point.gainDb - VolumeEnvelope.MIN_GAIN_DB) /
            (VolumeEnvelope.MAX_GAIN_DB - VolumeEnvelope.MIN_GAIN_DB) * bounds.height,
    )

    fun pointAt(point: VolumePoint, offset: Offset, state: EditorUiState, snapPx: Float): VolumePoint {
        val viewport = state.viewport() ?: return point
        val proposed = viewport.timeAtScreenX(state.scrollXPx, offset.x)
        val candidates = listOf(
            clip.timelineStartMicros to SnapEngine.SnapTarget.TimelineStart,
            state.playheadMicros to SnapEngine.SnapTarget.Playhead,
            (clip.timelineStartMicros + clip.timelineDurationMicros) to SnapEngine.SnapTarget.TimelineEnd,
        )
        val time = SnapEngine.snap(proposed, state.zoomPixelsPerMicro, snapPx, candidates).timeMicros
        val source = (clip.sourceStartMicros + (time - clip.timelineStartMicros) * clip.playbackSpeed.toDouble())
            .toLong().coerceIn(clip.sourceStartMicros, clip.sourceEndMicros)
        val gain = VolumeEnvelope.MIN_GAIN_DB + (1f - (offset.y - bounds.top) / bounds.height.coerceAtLeast(1f)) *
            (VolumeEnvelope.MAX_GAIN_DB - VolumeEnvelope.MIN_GAIN_DB)
        return point.copy(sourcePositionMicros = source, gainDb = gain.coerceIn(VolumeEnvelope.MIN_GAIN_DB, VolumeEnvelope.MAX_GAIN_DB))
    }
}

internal fun automationGeometry(state: EditorUiState, pxPerUnit: Float, density: Float, hiddenRows: Set<Int>): AutomationGeometry? {
    if (TimelineLayout.ROW_AUDIO in hiddenRows) return null
    val id = (state.selection as? TimelineSelection.Audio)?.audioClipId ?: return null
    val timeline = state.project?.timeline ?: return null
    val clip = timeline.audioClips.firstOrNull { it.id == id && it.sourceDurationMicros > 0 && it.playbackSpeed > 0 } ?: return null
    val viewport = state.viewport() ?: return null
    val geometry = TimelineGeometry.from(timeline)
    val lanes = TrackLanes.audio(timeline)
    val top = (0 until TimelineLayout.ROW_AUDIO).filterNot { it in hiddenRows }.sumOf { geometry.rowHeight(it).toDouble() }.toFloat() * pxPerUnit
    val laneHeight = geometry.rowHeight(TimelineLayout.ROW_AUDIO) * pxPerUnit / TrackLanes.count(lanes)
    val lane = lanes[id] ?: 0
    return AutomationGeometry(clip, Rect(
        viewport.screenXAtTime(state.scrollXPx, clip.timelineStartMicros),
        top + lane * laneHeight + 4 * density,
        viewport.screenXAtTime(state.scrollXPx, clip.timelineStartMicros + clip.timelineDurationMicros),
        top + (lane + 1) * laneHeight - 4 * density,
    ))
}

/** A point owns the pointer stream; empty waveform space retains timeline navigation.
 * Drafts never enter the project. Lift commits once; cancellation, disposal and a
 * second finger discard the draft. Clip-edge strips stay reserved for trimming.
 */
internal suspend fun AwaitPointerEventScope.editAutomationPoint(
    down: PointerInputChange,
    geometry: AutomationGeometry,
    state: EditorUiState,
    density: Float,
    longPressMillis: Long,
    onSelect: (String?) -> Unit,
    onDraft: (VolumeEnvelope?) -> Unit,
    onCommit: (String, VolumeEnvelope) -> Unit,
): Boolean {
    val bounds = geometry.bounds
    if (down.position.x <= bounds.left + 14 * density || down.position.x >= bounds.right - 14 * density) return false
    if (down.position.y < bounds.top - 4 * density || down.position.y > bounds.bottom + 4 * density) return false
    val point = geometry.clip.volumeEnvelope.points.filter { it.sourcePositionMicros in geometry.clip.sourceStartMicros..geometry.clip.sourceEndMicros }
        .minByOrNull { val p = geometry.position(it); hypot(p.x - down.position.x, p.y - down.position.y) } ?: return false
    val p = geometry.position(point)
    if (hypot(p.x - down.position.x, p.y - down.position.y) > 24 * density) return false
    onSelect(point.id)
    down.consume()
    val original = geometry.clip.volumeEnvelope
    var draft = original
    var moved = false
    try {
        while (true) {
            val remaining = longPressMillis - (currentEvent.changes.maxOf { it.uptimeMillis } - down.uptimeMillis)
            val event = if (!moved && remaining > 0) withTimeoutOrNull(remaining) { awaitPointerEvent() } else if (!moved) null else awaitPointerEvent()
            if (event == null) {
                onCommit(geometry.clip.id, original.withoutPoint(point.id))
                onSelect(null)
                return true
            }
            if (event.changes.size > 1) return true
            val change = event.changes.first()
            if (change.isConsumed) return true
            if (!change.pressed) {
                if (moved && draft != original) onCommit(geometry.clip.id, draft)
                return true
            }
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (moved || hypot(dx, dy) > 4 * density) {
                moved = true
                // Preserve the grab offset so a point never jumps under the finger.
                val updated = geometry.pointAt(point, Offset(p.x + dx, p.y + dy), state, 8 * density)
                draft = original.withPoint(updated).normalized()
                onDraft(draft)
            }
            change.consume()
        }
    } finally {
        onDraft(null)
    }
}
