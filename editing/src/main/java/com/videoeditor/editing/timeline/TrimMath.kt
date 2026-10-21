package com.videoeditor.editing.timeline

import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.Clip

/**
 * Converts trim-handle drag distances into valid source ranges. Pure math, no
 * gesture or Compose types, so every boundary rule is host-testable.
 *
 * Convention: the drag delta arrives as *timeline* microseconds (the handle
 * moved this far along the ruler). Source time advances [Clip.playbackSpeed]
 * times faster than timeline time, so the delta is scaled by speed before it is
 * applied to the source range. All results are clamped to the source media
 * bounds and to [MIN_SOURCE_SPAN_MICROS], guaranteeing durations are positive
 * and trims never leave the source file.
 */
object TrimMath {

    /**
     * The shortest source span a clip may be trimmed to. 100 ms keeps clips
     * usable while allowing frame-accurate trimming at high zoom.
     */
    const val MIN_SOURCE_SPAN_MICROS = 100_000L

    /** Which end of the clip a trim gesture is adjusting. */
    enum class Edge { Start, End }

    /**
     * The proposed source range after dragging [edge] by [deltaTimelineMicros]
     * (positive = later in time / rightward), starting from [clip]'s current
     * range and bounded by [sourceDurationMicros].
     *
     * The other end of the range is never touched, so a trim gesture cannot
     * move the clip's untouched boundary — subsequent clips re-pack around the
     * new duration when the command commits.
     */
    fun proposeTrim(
        clip: Clip,
        edge: Edge,
        deltaTimelineMicros: Long,
        sourceDurationMicros: Long,
    ): SourceRange = proposeRange(
        sourceStartMicros = clip.sourceStartMicros,
        sourceEndMicros = clip.sourceEndMicros,
        playbackSpeed = clip.playbackSpeed,
        edge = edge,
        deltaTimelineMicros = deltaTimelineMicros,
        sourceDurationMicros = sourceDurationMicros,
    )

    /** The same boundary rules for a clip on the free-positioned audio track. */
    fun proposeTrim(
        clip: AudioClip,
        edge: Edge,
        deltaTimelineMicros: Long,
        sourceDurationMicros: Long,
    ): SourceRange = proposeRange(
        sourceStartMicros = clip.sourceStartMicros,
        sourceEndMicros = clip.sourceEndMicros,
        playbackSpeed = clip.playbackSpeed,
        edge = edge,
        deltaTimelineMicros = deltaTimelineMicros,
        sourceDurationMicros = sourceDurationMicros,
    )

    private fun proposeRange(
        sourceStartMicros: Long,
        sourceEndMicros: Long,
        playbackSpeed: Float,
        edge: Edge,
        deltaTimelineMicros: Long,
        sourceDurationMicros: Long,
    ): SourceRange {
        val speed = if (playbackSpeed > 0f) playbackSpeed else 1f
        val deltaSourceMicros = clampedSourceDelta(deltaTimelineMicros, speed, sourceDurationMicros)
        return when (edge) {
            Edge.Start -> {
                val latestAllowedStart =
                    (sourceEndMicros - MIN_SOURCE_SPAN_MICROS)
                        .coerceAtLeast(0L)
                        .coerceAtMost(sourceDurationMicros)
                val newStart = (sourceStartMicros + deltaSourceMicros)
                    .coerceIn(0L, latestAllowedStart)
                SourceRange(newStart, sourceEndMicros)
            }

            Edge.End -> {
                val earliestAllowedEnd = sourceStartMicros + MIN_SOURCE_SPAN_MICROS
                val newEnd = (sourceEndMicros + deltaSourceMicros)
                    .coerceIn(
                        earliestAllowedEnd.coerceAtMost(sourceDurationMicros),
                        sourceDurationMicros,
                    )
                SourceRange(sourceStartMicros, newEnd)
            }
        }
    }

    /**
     * Whether [range] is a legal trim result for a clip whose source is
     * [sourceDurationMicros] long. The committed command must always satisfy
     * this; proposeTrim clamps so it does.
     */
    fun isValid(range: SourceRange, sourceDurationMicros: Long): Boolean =
        range.startMicros >= 0L &&
            range.endMicros <= sourceDurationMicros &&
            range.endMicros - range.startMicros >= MIN_SOURCE_SPAN_MICROS

    /** A validated source in/out pair in microseconds. */
    data class SourceRange(val startMicros: Long, val endMicros: Long) {
        val durationMicros: Long get() = endMicros - startMicros
    }

    /**
     * Converts a timeline drag delta to source microseconds, scaled by speed
     * and pre-clamped to ±one source duration. The clamp makes the subsequent
     * additions overflow-proof even for pathological deltas (a Long.MIN_VALUE
     * drag cannot wrap around into a "valid" range), and loses nothing: any
     * delta larger than the source is clamped to the boundary anyway.
     */
    private fun clampedSourceDelta(
        deltaTimelineMicros: Long,
        speed: Float,
        sourceDurationMicros: Long,
    ): Long {
        val limit = sourceDurationMicros.toDouble()
        val scaled = deltaTimelineMicros.toDouble() * speed
        return scaled.coerceIn(-limit, limit).toLong()
    }
}
