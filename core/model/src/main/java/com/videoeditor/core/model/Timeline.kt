package com.videoeditor.core.model

import kotlinx.serialization.Serializable

/**
 * The editable timeline description. This is the single authoritative model
 * shared by preview and export — there is no second interpretation of the edit.
 *
 * Tracks are kept as separate ordered lists so that layering and rendering
 * order are explicit and deterministic.
 */
@Serializable
data class Timeline(
    val videoClips: List<Clip> = emptyList(),
    val audioClips: List<AudioClip> = emptyList(),
    val textOverlays: List<TextOverlay> = emptyList(),
    /** Video/photo layers above the main track; order in this list is not the
     * stacking order — [OverlayClip.zIndex] is. Empty in all v1 documents. */
    val overlayClips: List<OverlayClip> = emptyList(),
    /** Optional visual joins; absent from legacy version-1 documents. */
    val transitions: List<Transition> = emptyList(),
    /** Decorative still-image layers above all video/PiP layers and below text.
     * Absent from legacy documents; order in this list is not the stacking
     * order — [StickerClip.zIndex] is. */
    val stickers: List<StickerClip> = emptyList(),
) {
    /** Total duration of the main video track, in project microseconds. */
    val videoTrackDurationMicros: Long
        get() = videoClips.maxOfOrNull { it.timelineEndMicros } ?: 0L

    /** Visual clips (including explicit photo end cards) define the canvas end.
     * Audio-only projects use the audio end. Longer music remains saved intact.
     */
    val renderedDurationMicros: Long
        get() = if (videoClips.isNotEmpty()) videoTrackDurationMicros else
            audioClips.maxOfOrNull { it.timelineStartMicros + it.timelineDurationMicros } ?: 0L

    /** Non-destructive render window for music under a visual track. */
    fun renderedAudioClip(audio: AudioClip): AudioClip? {
        val remaining = renderedDurationMicros - audio.timelineStartMicros
        if (remaining <= 0L || audio.playbackSpeed <= 0f) return null
        val sourceEnd = minOf(audio.sourceEndMicros,
            audio.sourceStartMicros + (remaining * audio.playbackSpeed).toLong())
        return audio.copy(sourceEndMicros = sourceEnd).takeIf { it.sourceDurationMicros > 0L }
    }

    /** Total duration of the whole project across all tracks. */
    val totalDurationMicros: Long
        get() = maxOf(
            renderedDurationMicros,
            textOverlays.maxOfOrNull { it.startMicros + it.durationMicros } ?: 0L,
        )

    /**
     * Returns the video clip (and its index) that contains the given project
     * time, or null if the time falls in a gap or beyond the track.
     */
    fun clipAt(timeMicros: Long): Pair<Int, Clip>? {
        for ((index, clip) in videoClips.withIndex()) {
            if (timeMicros >= clip.timelineStartMicros && timeMicros < clip.timelineEndMicros) {
                return index to clip
            }
        }
        return null
    }

    /**
     * Maps a project time to the corresponding source time, honoring trim and
     * playback speed. Returns null if [projectTimeMicros] falls outside the
     * video track.
     *
     * This is the authoritative project-to-source conversion shared by preview
     * and export, so seeking the player and rendering the composition resolve a
     * given moment to the same frame of the same source file.
     */
    fun sourceTimeAt(projectTimeMicros: Long): Long? {
        val (_, clip) = clipAt(projectTimeMicros) ?: return null
        return clip.sourceTimeAt(projectTimeMicros)
    }

    /**
     * The inverse of [sourceTimeAt]: maps a source time within [clipId] back to
     * its project time. Returns null if the source time is outside that clip's
     * trim range.
     */
    fun projectTimeFor(clipId: String, sourceTimeMicros: Long): Long? =
        videoClips.firstOrNull { it.id == clipId }?.timelineTimeAt(sourceTimeMicros)
}
