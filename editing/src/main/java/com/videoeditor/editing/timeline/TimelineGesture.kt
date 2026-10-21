package com.videoeditor.editing.timeline

/**
 * The timeline's gesture state machine. Exactly one gesture may be active at a
 * time; [TimelineGestureState.tryBegin] refuses overlapping starts so a second
 * pointer stream or a stray tap cannot corrupt an in-flight drag, and every
 * gesture either completes with a commit or is cancelled — there is no path
 * that leaves the timeline stuck mid-gesture.
 *
 * Gestures carry only transient information (the moving clip's proposed slot,
 * the trimmed edge). The authoritative data stays in the project model; a
 * gesture never edits the model until it commits through the command layer.
 */
sealed interface TimelineGesture {

    /** No gesture in progress. */
    data object Idle : TimelineGesture

    /**
     * The user is dragging the timeline content horizontally (one finger, not
     * on an interactive element). Scrubbing is driven by where the fixed
     * playhead lands; [isScrubbing] distinguishes a deliberate scrub from
     * inertial scrolling for the preview-follow policy.
     */
    data class Scrolling(val isScrubbing: Boolean) : TimelineGesture

    /** Two-finger pinch in progress. */
    data class Zooming(
        val startPixelsPerMicro: Float,
        val focalScreenXPx: Float,
    ) : TimelineGesture

    /** A trim handle is being dragged. */
    data class Trimming(
        val clipId: String,
        val edge: TrimMath.Edge,
        /** Source range at gesture start, for cancellation. */
        val originalRangeMicros: Pair<Long, Long>,
        /** The live proposed range while dragging; committed on release. */
        val proposedRangeMicros: Pair<Long, Long>? = null,
    ) : TimelineGesture

    /** A video clip is being long-press dragged to a new position. */
    data class Reordering(
        val clipId: String,
        /** Index the clip would land at if released now. */
        val proposedIndex: Int,
        /** Whether a snap is currently engaged, for visual feedback. */
        val isSnapped: Boolean = false,
    ) : TimelineGesture

    /**
     * An audio or text clip is being dragged along its free-positioned track.
     * Unlike the video track (contiguous, index-ordered), these clips move to
     * any timeline position with magnetic snapping.
     */
    data class MovingFree(
        val track: Track,
        val itemId: String,
        val originalStartMicros: Long,
        val proposedStartMicros: Long,
        val isSnapped: Boolean = false,
    ) : TimelineGesture {
        enum class Track { Audio, Text }
    }
}

/**
 * Immutable holder enforcing the one-gesture-at-a-time invariant.
 */
class TimelineGestureState(val gesture: TimelineGesture = TimelineGesture.Idle) {

    val isIdle: Boolean get() = gesture is TimelineGesture.Idle

    /**
     * Attempts to begin [next]; refuses when another gesture is already active.
     * Returns the new state (possibly unchanged on refusal).
     */
    fun tryBegin(next: TimelineGesture): TimelineGestureState =
        if (isIdle) TimelineGestureState(next) else this

    /** Replaces the active gesture's transient data; no-op when idle. */
    fun update(transform: (TimelineGesture) -> TimelineGesture): TimelineGestureState =
        if (isIdle) this else TimelineGestureState(transform(gesture))

    /** Ends the gesture, returning to idle. */
    fun end(): TimelineGestureState = TimelineGestureState(TimelineGesture.Idle)

    /** Cancels the gesture. Semantically identical to [end]; named for intent. */
    fun cancel(): TimelineGestureState = end()

    override fun equals(other: Any?): Boolean =
        other is TimelineGestureState && other.gesture == gesture

    override fun hashCode(): Int = gesture.hashCode()

    override fun toString(): String = "TimelineGestureState($gesture)"
}
