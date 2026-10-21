package com.videoeditor.editing.timeline

import com.videoeditor.core.model.Timeline

/**
 * Playhead navigation helpers shared by the transport controls.
 */
object TimelineNav {

    /**
     * Skips past boundaries closer than this to the current position, so
     * "next boundary" from a position that already sits on one (after a
     * boundary seek, or at time 0) advances instead of re-triggering.
     */
    const val BOUNDARY_EPSILON_MICROS = 10_000L

    /**
     * All clip-boundary times of the timeline, ascending and deduplicated:
     * every video clip edge, the project start, and the project end. These are
     * the natural stopping points for boundary navigation.
     */
    fun boundaries(timeline: Timeline): List<Long> {
        val bounds = mutableSetOf(0L)
        for (clip in timeline.videoClips) {
            bounds += clip.timelineStartMicros
            bounds += clip.timelineEndMicros
        }
        val duration = timeline.totalDurationMicros
        if (duration > 0L) bounds += duration
        return bounds.sorted()
    }

    /**
     * The next boundary strictly after [fromMicros] (forward = true) or
     * strictly before it (forward = false), or null when there is none in that
     * direction.
     */
    fun adjacentBoundary(timeline: Timeline, fromMicros: Long, forward: Boolean): Long? {
        val bounds = boundaries(timeline)
        return if (forward) {
            bounds.firstOrNull { it > fromMicros + BOUNDARY_EPSILON_MICROS }
        } else {
            bounds.lastOrNull { it < fromMicros - BOUNDARY_EPSILON_MICROS }
        }
    }
}
