package com.videoeditor.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies a precomputed ducking gain curve to a background clip's PCM.
 *
 * The curve is computed offline by [DuckingAnalyzer] from the trigger clips'
 * decoded levels and handed to this processor as an array of linear gains, one
 * per fixed window, in **timeline** (project) time. The processor therefore does
 * no detection itself: preview and export both replay the same analysis result,
 * which is what makes their ducking behaviour identical by construction.
 *
 * # Position tracking
 *
 * Each frame's timeline position is derived from the clip's timeline start plus
 * the running frame count at the post-speed sample rate, converted to a window
 * index. Like [FadeAudioProcessor], a seek flush re-bases the frame counter from
 * [setPositionMicros], so the resumed stream lands on the correct window.
 *
 * # Interpolation
 *
 * Window gains are linearly interpolated across the window boundary so the gain
 * never steps at a window edge. The attack and release smoothing was already
 * applied when the curve was built; the interpolation here only removes the
 * staircase that fixed windows would otherwise produce.
 *
 * # Bypass
 *
 * A null or empty curve leaves the processor inactive, so Media3 removes it from
 * the chain and the audio passes through untouched.
 */
@UnstableApi
class DuckingGainAudioProcessor(
    private val curve: DuckingCurve?,
    private val clipTimelineStartMicros: Long,
) : BaseAudioProcessor(), SeekableGainAudioProcessor {

    private var frames = 0L

    @Volatile
    private var initialPositionUs = 0L

    /** Clip-relative timeline offset the next flush should resume from. */
    override fun setPositionMicros(positionUs: Long) {
        initialPositionUs = positionUs.coerceAtLeast(0L)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = curve != null && curve.gains.isNotEmpty()

    override fun onFlush() {
        frames = initialPositionUs * inputAudioFormat.sampleRate.coerceAtLeast(1) / 1_000_000L
    }

    // Match the accepted fade seek contract: decoder reconfiguration can reset
    // this processor after the main-thread seek intent was set. Retain that
    // intent across reset/flush; replay explicitly supplies position zero.
    override fun onReset() {
        frames = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameBytes = bytesPerSample * channels
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        val activeCurve = curve
        val gains = activeCurve?.gains ?: FloatArray(0)
        val windowMicros = activeCurve?.windowMicros ?: 0L
        val gridStartMicros = activeCurve?.timelineStartMicros ?: 0L

        while (inputBuffer.remaining() >= frameBytes) {
            val gain = if (gains.isEmpty() || windowMicros <= 0L) {
                1f
            } else {
                // frames counts from this clip's first post-speed frame, so the
                // absolute project time of the current frame is the clip's
                // timeline start plus the elapsed stream time.
                val timelineMicros = clipTimelineStartMicros + frames * 1_000_000L / sampleRate
                sampleCurve(gains, windowMicros, gridStartMicros, timelineMicros)
            }
            if (isFloat) {
                repeat(channels) { output.putFloat((inputBuffer.float * gain).coerceIn(-1f, 1f)) }
            } else {
                repeat(channels) {
                    val scaled = (inputBuffer.short.toInt() * gain).toInt()
                    output.putShort(scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }
            frames++
        }
        output.flip()
    }

    private fun sampleCurve(
        gains: FloatArray,
        windowMicros: Long,
        curveStartMicros: Long,
        timelineMicros: Long,
    ): Float {
        val offsetMicros = timelineMicros - curveStartMicros
        if (offsetMicros <= 0L) return gains[0]
        val position = offsetMicros.toDouble() / windowMicros.toDouble()
        val index = position.toInt()
        if (index >= gains.lastIndex) return gains[gains.lastIndex]
        val fraction = (position - index).toFloat()
        return gains[index] + (gains[index + 1] - gains[index]) * fraction
    }
}

/**
 * A ducking gain curve in timeline time: one linear gain per fixed window,
 * starting at [timelineStartMicros]. Gains are in 0..1; 1 means no reduction.
 */
data class DuckingCurve(
    val timelineStartMicros: Long,
    val windowMicros: Long,
    val gains: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DuckingCurve) return false
        return timelineStartMicros == other.timelineStartMicros &&
            windowMicros == other.windowMicros &&
            gains.contentEquals(other.gains)
    }

    override fun hashCode(): Int {
        var result = timelineStartMicros.hashCode()
        result = 31 * result + windowMicros.hashCode()
        result = 31 * result + gains.contentHashCode()
        return result
    }
}
