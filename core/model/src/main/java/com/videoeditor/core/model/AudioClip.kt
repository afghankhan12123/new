package com.videoeditor.core.model

import kotlinx.serialization.Serializable

/**
 * An audio element on the audio track: imported music, an extracted clip, or a
 * recorded voiceover. Source in/out are in microseconds of the source file;
 * timeline placement is in project microseconds.
 */
@Serializable
data class AudioClip(
    val id: String,
    val mediaRefId: String,
    val sourceStartMicros: Long = 0L,
    val sourceEndMicros: Long = 0L,
    val timelineStartMicros: Long = 0L,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInMicros: Long = 0L,
    val fadeOutMicros: Long = 0L,
    val playbackSpeed: Float = 1f,
    /**
     * Editable gain automation over the clip's own source time. Empty means the
     * Stage 7 behavior is reproduced exactly: no automation stage is inserted.
     * Absent in Stage 6/7 documents and deserializes to the empty envelope.
     */
    val volumeEnvelope: VolumeEnvelope = VolumeEnvelope(),
    /** Curve shapes for the existing fade windows. Linear reproduces Stage 7. */
    val fadeInCurve: FadeCurve = FadeCurve.Linear,
    val fadeOutCurve: FadeCurve = FadeCurve.Linear,
    /** Three-band equalizer. Disabled by default; a disabled EQ never reaches
     *  the DSP path, so existing projects decode and mix unchanged. */
    val equalizer: EqualizerSettings = EqualizerSettings(),
    /** Stereo placement. Centre is the identity state and changes nothing. */
    val balance: AudioBalance = AudioBalance(),
    /** Automatic ducking configuration for a background clip. Disabled by
     *  default; disabled ducking never inserts an analysis or gain stage. */
    val ducking: DuckingSettings = DuckingSettings(),
) {
    val sourceDurationMicros: Long get() = sourceEndMicros - sourceStartMicros

    /** Duration this clip occupies on the timeline, accounting for speed. */
    val timelineDurationMicros: Long
        get() = if (playbackSpeed > 0f) (sourceDurationMicros.toDouble() / playbackSpeed).toLong() else 0L
}
