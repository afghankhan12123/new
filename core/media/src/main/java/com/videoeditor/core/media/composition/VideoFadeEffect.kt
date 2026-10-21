package com.videoeditor.core.media.composition

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import com.videoeditor.core.model.Clip

/** Fade through black; cut timing stays unchanged. Input timestamps precede speed retiming. */
@UnstableApi
internal class VideoFadeEffect(private val clip: Clip, private val timestampSpeed: Float) : RgbMatrix {
    private val matrix = FloatArray(16).apply { this[15] = 1f }
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val elapsed = ((presentationTimeUs - clip.timelineStartMicros) / timestampSpeed).toLong()
        val fadeIn = if (clip.fadeInMicros > 0) (elapsed.toFloat() / clip.fadeInMicros).coerceIn(0f, 1f) else 1f
        val fadeOut = if (clip.fadeOutMicros > 0) ((clip.timelineDurationMicros - elapsed).toFloat() / clip.fadeOutMicros).coerceIn(0f, 1f) else 1f
        val gain = minOf(fadeIn, fadeOut)
        matrix[0] = gain; matrix[5] = gain; matrix[10] = gain
        return matrix
    }
}
