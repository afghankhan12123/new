package com.videoeditor.core.media.audio

import androidx.media3.common.util.UnstableApi

/**
 * A gain-shaping processor whose envelope is defined over time and must be
 * re-based when the player seeks.
 *
 * The preview player tracks the fade, volume-automation and ducking processors
 * through [com.videoeditor.core.media.composition.CompositionBuilder.PreviewEnvelope].
 * On every seek it calls [setPositionMicros] with the target project time before
 * the player's own flush, so the next PCM buffer resumes its envelope at the
 * correct position instead of continuing from a stale frame count.
 *
 * All three Stage 8/7 time-based processors implement this so the seek path
 * repositions them through one contract. Export does not seek mid-render, so
 * this hook is a preview-only concern, but implementing it uniformly keeps the
 * processors interchangeable and their timing semantics identical.
 */
@UnstableApi
interface SeekableGainAudioProcessor {
    /**
     * Sets the project-relative position the next flush should resume from, in
     * microseconds measured from the clip's own timeline start.
     */
    fun setPositionMicros(positionUs: Long)
}
