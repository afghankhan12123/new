package com.videoeditor.core.model

import kotlinx.serialization.Serializable

/**
 * A visual layer placed on top of the main video track: a video or photo
 * overlay (picture-in-picture). Overlays are non-destructive — the source file
 * is never modified, and the layer records only what is needed to re-render it.
 *
 * Timing: [timelineStartMicros]/[timelineDurationMicros] place the overlay on
 * the project timeline independently of the contiguous main track; overlays may
 * start anywhere and never shift other items.
 *
 * Source window: [sourceStartMicros]/[sourceEndMicros] trim the referenced
 * video. Photo overlays ignore the source window and use their timeline
 * duration, mirroring [Clip.photoDurationMicros] on the main track.
 *
 * Placement: [transform] is resolved against the project canvas. The source
 * frame is first scaled to *fit* the canvas, then the transform applies —
 * [VisualTransform.scale] (1 = fit-canvas size), [VisualTransform.offsetX]/
 * [VisualTransform.offsetY] in normalized canvas space (-1..1, positive Y
 * down), [VisualTransform.rotationDegrees], flips and source [CropRect].
 *
 * Stacking: [zIndex] orders layers; higher draws on top. Text overlays always
 * render above every overlay layer, preserving their existing behavior.
 */
@Serializable
data class OverlayClip(
    val id: String,
    val mediaRefId: String,
    val timelineStartMicros: Long = 0L,
    val timelineDurationMicros: Long = 0L,
    val sourceStartMicros: Long = 0L,
    val sourceEndMicros: Long = 0L,
    val playbackSpeed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val opacity: Float = 1f,
    val zIndex: Int = 0,
    val transform: VisualTransform = VisualTransform(),
    /** True when the referenced media is a still image. */
    val isPhoto: Boolean = false,
    val effects: List<VisualEffect> = emptyList(),
    val mask: LayerMask? = null,
    val chromaKey: ChromaKeySettings? = null,
    val blendMode: BlendMode = BlendMode.Normal,
    val keyframeTracks: Map<String, KeyframeTrack> = emptyMap(),
    /** Additive v1 field: legacy layers retain their existing fit behavior. */
    val scaleMode: ScaleMode = ScaleMode.Fit,
) {
    val sourceDurationMicros: Long get() = sourceEndMicros - sourceStartMicros
    val timelineEndMicros: Long get() = timelineStartMicros + timelineDurationMicros

    /**
     * Maps a project time inside this overlay to its source time, honoring the
     * trim offset and playback speed — the same conversion [Clip.sourceTimeAt]
     * provides for main-track clips. Undefined for photos (single frame).
     */
    fun sourceTimeAt(projectTimeMicros: Long): Long {
        val offset = projectTimeMicros - timelineStartMicros
        return sourceStartMicros + (offset.toDouble() * playbackSpeed).toLong()
    }

    /**
     * Evaluates effective opacity at a project timestamp, accounting for keyframes.
     */
    fun evaluateOpacityAt(projectTimeMicros: Long): Float {
        val track = keyframeTracks["opacity"] ?: return opacity
        val offset = (projectTimeMicros - timelineStartMicros).coerceAtLeast(0L)
        return track.evaluate(offset, opacity).coerceIn(0f, 1f)
    }
}
