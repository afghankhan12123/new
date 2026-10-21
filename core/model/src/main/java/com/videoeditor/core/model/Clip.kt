package com.videoeditor.core.model

import kotlinx.serialization.Serializable

/**
 * A single clip on the main video track. A clip may reference a video or a
 * photo. Source in/out are microseconds within the source file; timeline
 * placement is in project microseconds.
 *
 * The effective timeline duration is the source duration divided by playback
 * speed, so a 2x clip occupies half the timeline of its source segment.
 */
@Serializable
data class Clip(
    val id: String,
    val mediaRefId: String,
    val sourceStartMicros: Long = 0L,
    val sourceEndMicros: Long = 0L,
    val timelineStartMicros: Long = 0L,
    val playbackSpeed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val transform: VisualTransform = VisualTransform(),
    val filter: FilterSettings = FilterSettings(),
    val colorAdjustments: ColorAdjustments = ColorAdjustments(),
    /** For photo clips: how long the still image is shown, in project micros. */
    val photoDurationMicros: Long? = null,
    val fadeInMicros: Long = 0L,
    val fadeOutMicros: Long = 0L,
    /** Curve shapes for the clip's audio fade windows. Linear reproduces the
     *  Stage 7 fade exactly, and absent fields decode to Linear, so existing
     *  documents keep their accepted behavior. */
    val fadeInCurve: FadeCurve = FadeCurve.Linear,
    val fadeOutCurve: FadeCurve = FadeCurve.Linear,
    val effects: List<VisualEffect> = emptyList(),
    val keyframeTracks: Map<String, KeyframeTrack> = emptyMap(),
) {
    val sourceDurationMicros: Long get() = sourceEndMicros - sourceStartMicros

    val isPhoto: Boolean get() = photoDurationMicros != null

    /** Duration this clip occupies on the timeline, accounting for speed. */
    val timelineDurationMicros: Long
        get() = when {
            isPhoto -> photoDurationMicros ?: 0L
            playbackSpeed > 0f -> (sourceDurationMicros.toDouble() / playbackSpeed).toLong()
            else -> 0L
        }

    val timelineEndMicros: Long get() = timelineStartMicros + timelineDurationMicros

    /**
     * Maps a project time inside this clip to the corresponding source time,
     * honoring the trim offset and playback speed. Times before the clip's
     * timeline start map below [sourceStartMicros] and times after its end map
     * above [sourceEndMicros]; callers that need containment check it themselves.
     *
     * This is the single project→source conversion, shared by preview seeking,
     * export rendering, and timeline thumbnail sampling so that a given moment
     * resolves to the same frame of the same source file everywhere.
     */
    fun sourceTimeAt(timelineTimeMicros: Long): Long {
        val offsetInClip = timelineTimeMicros - timelineStartMicros
        return sourceStartMicros + (offsetInClip.toDouble() * playbackSpeed).toLong()
    }

    /**
     * The inverse of [sourceTimeAt]: maps a source time within this clip's trim
     * range back to its project time. Returns null if [sourceTimeMicros] is
     * outside [sourceStartMicros] until [sourceEndMicros].
     */
    fun timelineTimeAt(sourceTimeMicros: Long): Long? {
        if (sourceTimeMicros < sourceStartMicros || sourceTimeMicros >= sourceEndMicros) {
            return null
        }
        val offsetInSource = sourceTimeMicros - sourceStartMicros
        val offsetInClip =
            if (playbackSpeed > 0f) (offsetInSource.toDouble() / playbackSpeed).toLong() else 0L
        return timelineStartMicros + offsetInClip
    }
}
