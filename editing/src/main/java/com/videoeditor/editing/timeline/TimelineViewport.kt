package com.videoeditor.editing.timeline

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * The time↔pixel coordinate system of the timeline, and the single source of
 * truth for every conversion the timeline UI performs: scrolling, zooming,
 * scrubbing, hit-testing, snapping, trimming, and reordering all resolve
 * positions through one instance so they can never disagree.
 *
 * Geometry: the playhead is fixed at [playheadXpx] on screen and the content
 * scrolls underneath it. The content carries a leading pad equal to
 * [playheadXpx] and a trailing pad filling the rest of the viewport, so the
 * scroll range maps exactly onto the project: at scroll 0 the playhead sits at
 * time 0, and at maximum scroll it sits at the project duration. No special
 * casing is needed at the timeline edges.
 *
 * All times are integer microseconds (the project-wide timing convention);
 * positions are pixels. The class is unit-agnostic about dp vs. px — the Compose
 * layer converts once at construction. It is pure and immutable except through
 * [copy], which keeps it trivially testable on the JVM.
 */
data class TimelineViewport(
    /** Total duration of the project in microseconds; the scrollable time span. */
    val durationMicros: Long,
    /** The zoom level: horizontal pixels per microsecond of timeline time. */
    val pixelsPerMicro: Float,
    /** Width of the visible timeline area in pixels. */
    val viewportWidthPx: Float,
    /** Fixed on-screen x of the playhead; content scrolls beneath this point. */
    val playheadXpx: Float,
) {
    init {
        require(pixelsPerMicro.isFinite() && pixelsPerMicro > 0f) { "pixelsPerMicro must be positive" }
        require(viewportWidthPx.isFinite() && viewportWidthPx > 0f) { "viewportWidthPx must be positive" }
        require(playheadXpx in 0f..viewportWidthPx) { "playhead outside the viewport" }
        require(durationMicros >= 0L) { "duration must not be negative" }
    }

    /** Content pixels before time 0, so time 0 can sit under the playhead. */
    val startPadPx: Float get() = playheadXpx

    /** Content pixels after the project end, so the end can sit under the playhead. */
    val endPadPx: Float get() = viewportWidthPx - playheadXpx

    /** Full scrollable content width in pixels. */
    val contentWidthPx: Float
        get() = startPadPx + durationMicros * pixelsPerMicro + endPadPx

    /** Largest valid scroll offset; never negative even for empty projects. */
    val maxScrollPx: Float
        get() = (durationMicros.toDouble() * pixelsPerMicro).toFloat()

    // -- time → pixel --

    /** Content-space x of a timeline time (independent of scrolling). */
    fun contentXAtTime(timeMicros: Long): Float =
        startPadPx + (timeMicros.toDouble() * pixelsPerMicro).toFloat()

    /** Screen-space x of a timeline time at the given scroll offset. */
    fun screenXAtTime(scrollXPx: Float, timeMicros: Long): Float =
        playheadXpx + ((timeMicros.toDouble() * pixelsPerMicro).toFloat() - scrollXPx)

    /** Scroll offset that places [timeMicros] exactly under the fixed playhead. */
    fun scrollXForTime(timeMicros: Long): Float =
        (timeMicros.toDouble() * pixelsPerMicro).toFloat().coerceIn(0f, maxScrollPx)

    /** Absolute mapping: playback, backwards seeks and replay share the same anchor.
     * Leading/trailing padding already allows both endpoints under the fixed line.
     * Holding an edge region would make the ruler disagree with the playback clock.
     */
    fun scrollXForFollow(timeMicros: Long): Float = scrollXForTime(timeMicros)

    // -- pixel → time --

    /**
     * Timeline time at a content-space x. Values in the pads map outside
     * 0..duration; callers that need a valid seek target clamp explicitly.
     */
    fun timeAtContentX(contentXPx: Float): Long =
        ((contentXPx.toDouble() - startPadPx) / pixelsPerMicro).roundToLong()

    /** Timeline time at a screen-space x for the given scroll offset. */
    fun timeAtScreenX(scrollXPx: Float, screenXPx: Float): Long =
        ((scrollXPx.toDouble() + screenXPx - playheadXpx) / pixelsPerMicro).roundToLong()

    /**
     * The time currently under the fixed playhead — the scrub position.
     *
     * The exact ends are returned directly rather than round-tripping through
     * the pixel math: reconstructing `duration` as `(duration·ppm)/ppm` loses a
     * fraction of a microsecond in float, so over-scrolling to the end would
     * otherwise report one microsecond short. Snapping the boundaries keeps
     * scrubbing to the end exactly at the duration.
     */
    fun playheadTimeMicros(scrollXPx: Float): Long {
        val clamped = scrollXPx.coerceIn(0f, maxScrollPx)
        if (clamped <= 0f) return 0L
        if (clamped >= maxScrollPx) return durationMicros
        return timeAtScreenX(clamped, playheadXpx).coerceIn(0L, durationMicros)
    }

    /** Clamps a scroll offset into the valid range. */
    fun clampScroll(scrollXPx: Float): Float = scrollXPx.coerceIn(0f, maxScrollPx)

    /**
     * Applies a zoom change while keeping the timeline time under the gesture's
     * focal point (a screen x) fixed, so pinching does not make the content
     * slide away from the fingers. Returns the adjusted scroll offset for
     * [newPixelsPerMicro]; the focal time itself is preserved exactly.
     */
    fun scrollXForZoom(
        newPixelsPerMicro: Float,
        focalScreenXPx: Float,
        previousScrollXPx: Float,
    ): Float {
        val focalTimeMicros = timeAtScreenX(previousScrollXPx, focalScreenXPx)
        return scrollXForZoomPan(newPixelsPerMicro, focalTimeMicros, focalScreenXPx)
    }

    /**
     * The general zoom+pan update used by the pinch gesture: places
     * [focalTimeMicros] at [targetScreenXPx] under [newPixelsPerMicro]. Two
     * fingers both zoom (scale change) and pan (the centroid moves), so the
     * focal *time* is tracked across events while its target screen position
     * is the current centroid.
     */
    fun scrollXForZoomPan(
        newPixelsPerMicro: Float,
        focalTimeMicros: Long,
        targetScreenXPx: Float,
    ): Float {
        require(newPixelsPerMicro.isFinite() && newPixelsPerMicro > 0f)
        val newMaxScroll = (durationMicros.toDouble() * newPixelsPerMicro).toFloat()
        return (playheadXpx.toDouble() + focalTimeMicros.toDouble() * newPixelsPerMicro - targetScreenXPx)
            .toFloat().coerceIn(0f, newMaxScroll)
    }

    companion object {
        /** Fit the full edit in the trailing half of the fixed-playhead viewport. */
        fun fitZoomFor(durationMicros: Long, viewportWidthPx: Float): Float =
            viewportWidthPx * 0.45f / max(durationMicros, 250_000L)

        /**
         * Zoom limits for a project. The maximum zoom shows at least
         * [minVisibleSpanMicros] across the viewport (fine trimming); the
         * minimum zoom always shows the whole project plus
         * [durationSlackMicros] of context, so an empty or tiny project still
         * gets a sane scale instead of an absurd pixels-per-second.
         */
        fun zoomLimits(
            durationMicros: Long,
            viewportWidthPx: Float,
            minVisibleSpanMicros: Long = 250_000L,
            durationSlackMicros: Long = 30_000_000L,
        ): Pair<Float, Float> {
            val maxPixelsPerMicro = viewportWidthPx / minVisibleSpanMicros
            val fullSpanMicros = max(durationMicros + durationSlackMicros, minVisibleSpanMicros)
            val minPixelsPerMicro = viewportWidthPx / fullSpanMicros
            return minPixelsPerMicro to max(minPixelsPerMicro, maxPixelsPerMicro)
        }

        /**
         * A project-aware starting zoom, distinct from the fit-all minimum.
         *
         * Short projects (≤ [initialVisibleSpanMicros]) are shown in full at a
         * comfortable scale so individual clips are recognizable and trim handles
         * are grabbable without the first pinch. Longer projects start at a
         * usable window of [initialVisibleSpanMicros] and scroll for the rest,
         * rather than being squeezed to fit every timestamp at once. The Fit
         * action still uses the fit-all minimum; this only sets the initial view.
         */
        fun initialZoomFor(
            durationMicros: Long,
            viewportWidthPx: Float,
            initialVisibleSpanMicros: Long = 10_000_000L,
        ): Float {
            if (durationMicros <= 0L || viewportWidthPx <= 0f) return 0f
            val (minZoom, maxZoom) = zoomLimits(durationMicros, viewportWidthPx)
            val zoom = if (durationMicros <= initialVisibleSpanMicros) {
                // Show the whole (short) project, comfortably.
                viewportWidthPx / durationMicros
            } else {
                // Show a usable window of a long project.
                viewportWidthPx / initialVisibleSpanMicros
            }
            return zoom.coerceIn(minZoom, maxZoom)
        }
    }
}
