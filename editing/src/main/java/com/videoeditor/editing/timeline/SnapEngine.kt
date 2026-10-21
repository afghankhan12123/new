package com.videoeditor.editing.timeline

/**
 * Magnetic snapping for timeline gestures. All candidates are timeline times
 * (microseconds); the threshold is expressed in *pixels* and converted to time
 * at the current zoom, so snapping feels identical at every zoom level: the
 * same on-screen distance triggers a snap whether zoomed in or out.
 */
object SnapEngine {

    /** Outcome of a snap attempt: the (possibly adjusted) time and what it hit. */
    data class SnapResult(
        val timeMicros: Long,
        val target: SnapTarget?,
    ) {
        val isSnapped: Boolean get() = target != null
    }

    /** What a snap landed on, for visual feedback and haptics. */
    sealed interface SnapTarget {
        data object TimelineStart : SnapTarget
        data object Playhead : SnapTarget
        data object TimelineEnd : SnapTarget

        /** A boundary of another clip: its start or end, in timeline order. */
        data class ClipBoundary(val clipId: String, val timeMicros: Long, val isStart: Boolean) :
            SnapTarget
    }

    /**
     * Snaps [timeMicros] to the nearest candidate within [thresholdPx] at the
     * given zoom. Candidates are supplied by the caller (timeline start/end,
     * playhead, adjacent clip boundaries); the nearest one within the threshold
     * wins, ties going to whichever appears first in [candidates].
     */
    fun snap(
        timeMicros: Long,
        pixelsPerMicro: Float,
        thresholdPx: Float,
        candidates: List<Pair<Long, SnapTarget>>,
    ): SnapResult {
        if (candidates.isEmpty() || pixelsPerMicro <= 0f) {
            return SnapResult(timeMicros, null)
        }
        val thresholdMicros = (thresholdPx / pixelsPerMicro).toLong()
        if (thresholdMicros <= 0L) return SnapResult(timeMicros, null)

        var bestTarget: SnapTarget? = null
        var bestDistance = Long.MAX_VALUE
        for ((candidateTime, target) in candidates) {
            val distance = kotlin.math.abs(candidateTime - timeMicros)
            if (distance <= thresholdMicros && distance < bestDistance) {
                bestDistance = distance
                bestTarget = target
                if (distance == 0L) break
            }
        }
        return if (bestTarget == null) {
            SnapResult(timeMicros, null)
        } else {
            val snappedTime = candidates.first { it.second == bestTarget }.first
            SnapResult(snappedTime, bestTarget)
        }
    }

    /**
     * Builds the standard candidate set for dragging a clip whose tentative
     * placement is [draggedStartMicros]..[draggedEndMicros]: the timeline
     * start and end, the playhead, and every boundary of every *other* clip.
     *
     * Both edges of the dragged clip are considered — a snap is offered for
     * whichever edge lands within the threshold — by returning candidates keyed
     * to the position that should be snapped. The caller applies
     * [snapEdge] with the matching edge time.
     */
    fun candidatesForMove(
        clipBoundaries: List<Triple<String, Long, Long>>, // id, start, end
        draggedClipId: String,
        playheadMicros: Long,
        durationMicros: Long,
    ): List<Pair<Long, SnapTarget>> = buildList {
        add(0L to SnapTarget.TimelineStart)
        if (durationMicros > 0L) add(durationMicros to SnapTarget.TimelineEnd)
        add(playheadMicros to SnapTarget.Playhead)
        for ((id, start, end) in clipBoundaries) {
            if (id == draggedClipId) continue
            add(start to SnapTarget.ClipBoundary(id, start, isStart = true))
            add(end to SnapTarget.ClipBoundary(id, end, isStart = false))
        }
    }

    /**
     * Snaps a moving clip: tries the clip's leading and trailing edges against
     * [candidates] and returns the adjusted *start* time. The edge that snaps
     * is the one reported as the target so the UI can highlight it.
     */
    fun snapMovedClip(
        proposedStartMicros: Long,
        clipDurationMicros: Long,
        pixelsPerMicro: Float,
        thresholdPx: Float,
        candidates: List<Pair<Long, SnapTarget>>,
    ): SnapResult {
        val startSnap = snap(proposedStartMicros, pixelsPerMicro, thresholdPx, candidates)
        val endSnap =
            snap(proposedStartMicros + clipDurationMicros, pixelsPerMicro, thresholdPx, candidates)

        // Prefer whichever edge came closer to its candidate.
        val startDistance = kotlin.math.abs(startSnap.timeMicros - proposedStartMicros)
        val endDistance = kotlin.math.abs(
            endSnap.timeMicros - (proposedStartMicros + clipDurationMicros),
        )
        return when {
            startSnap.isSnapped && (!endSnap.isSnapped || startDistance <= endDistance) ->
                SnapResult(startSnap.timeMicros, startSnap.target)

            endSnap.isSnapped ->
                SnapResult(endSnap.timeMicros - clipDurationMicros, endSnap.target)

            else -> SnapResult(proposedStartMicros, null)
        }
    }
}
