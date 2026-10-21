package com.videoeditor.editing.timeline

import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.Timeline

/**
 * Transient, gesture-time layouts of the video track. While a reorder or trim
 * gesture is in flight, the model must not change — but the renderer needs to
 * show exactly where clips *would* land. These simulations compute that layout
 * with the same packing rules [com.videoeditor.editing.commands.TimelineCommands]
 * applies when the gesture commits, so the preview and the committed result
 * always agree.
 */
object TimelineSimulation {

    /** One clip's provisional placement during a gesture. */
    data class PlacedClip(
        val clipId: String,
        val startMicros: Long,
        val endMicros: Long,
        /** True while this clip is the one the user is dragging or trimming. */
        val isActive: Boolean,
    ) {
        val durationMicros: Long get() = endMicros - startMicros
    }

    /**
     * The video track as it looks when [draggedClipId] would be inserted at
     * [proposedIndex] of the remaining clips. The dragged clip is returned
     * *last* in the list regardless of slot order so the renderer can draw it
     * on top of the others; its start/end reflect its provisional slot.
     */
    fun reorderPreview(
        timeline: Timeline,
        draggedClipId: String,
        proposedIndex: Int,
    ): List<PlacedClip> {
        val dragged = timeline.videoClips.firstOrNull { it.id == draggedClipId }
            ?: return placeAll(timeline.videoClips, activeId = null)
        val others = timeline.videoClips.filterNot { it.id == draggedClipId }
        val reordered = others.toMutableList().apply {
            add(proposedIndex.coerceIn(0, size), dragged)
        }
        return placeAll(reordered, activeId = draggedClipId).let { placed ->
            // Draw order: non-active first (shifted aside), dragged last (on top).
            placed.sortedBy { if (it.isActive) 1 else 0 }
        }
    }

    /**
     * The video track while [clipId] is trimmed to [proposedRange]
     * (source microseconds). The trimmed clip's timeline duration changes
     * (speed applied), and following clips re-pack around it — the same
     * contiguous packing the TrimClip command performs on commit.
     */
    fun trimPreview(
        timeline: Timeline,
        clipId: String,
        proposedRange: TrimMath.SourceRange,
    ): List<PlacedClip> {
        val clips = timeline.videoClips.map { clip ->
            if (clip.id == clipId) {
                clip.copy(
                    sourceStartMicros = proposedRange.startMicros,
                    sourceEndMicros = proposedRange.endMicros,
                )
            } else {
                clip
            }
        }
        return placeAll(clips, activeId = clipId)
    }

    /** Packs clips contiguously from 0 and returns their placements. */
    private fun placeAll(clips: List<Clip>, activeId: String?): List<PlacedClip> {
        var cursor = 0L
        return clips.map { clip ->
            PlacedClip(
                clipId = clip.id,
                startMicros = cursor,
                endMicros = cursor + clip.timelineDurationMicros,
                isActive = clip.id == activeId,
            ).also { cursor += clip.timelineDurationMicros }
        }
    }
}
