package com.videoeditor.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.videoeditor.core.model.VolumeEnvelope
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies a [VolumeEnvelope] to PCM as it streams.
 *
 * # Position tracking
 *
 * The envelope is authored in **source microseconds**. The processor runs after
 * the Sonic speed stage in the chain, so its input frames are already in
 * post-speed (timeline) order at the source sample rate. It therefore converts
 * each frame index back to a source position using the clip's trim start and
 * playback speed:
 *
 * ```
 * sourcePositionMicros = sourceStartMicros + frameIndex * speed * 1_000_000 / sampleRate
 * ```
 *
 * This is the same mapping [com.videoeditor.core.model.Clip.sourceTimeAt] uses
 * for video, so a volume point the user places at a playhead lands on the same
 * audio content in preview and in export.
 *
 * Like [FadeAudioProcessor], the frame counter is re-based on flush so a seek
 * resumes the envelope at the correct source position rather than continuing
 * from a stale count. [setPositionMicros] takes the clip-relative *timeline*
 * offset of the seek and converts it back through the speed factor, so the
 * resume point tracks the same source position the seek targeted.
 *
 * # Gain combination policy
 *
 * Automation multiplies against the base volume stage that precedes it in the
 * chain; it never replaces it. Fades multiply on after it. The result is
 *
 * ```
 * sample * baseVolume * automationGain * fadeGain
 * ```
 *
 * Each factor is computed once and applied once, so no envelope is applied
 * twice. Saturation happens in the float domain before the final PCM
 * conversion, keeping the arithmetic finite.
 *
 * # Bypass
 *
 * An empty envelope leaves this processor inactive, so Media3 removes it from
 * the chain and the accepted Stage 7 audio path is reproduced exactly.
 */
@UnstableApi
class VolumeEnvelopeAudioProcessor(
    private val envelope: VolumeEnvelope,
    private val sourceStartMicros: Long,
    private val playbackSpeed: Float,
) : BaseAudioProcessor(), SeekableGainAudioProcessor {

    private var frames = 0L

    @Volatile
    private var initialPositionUs = 0L

    /**
     * Sets the timeline position a subsequent seek lands on, in microseconds
     * relative to the clip's timeline start. The next flush re-bases [frames]
     * from it, mirroring [FadeAudioProcessor.setPositionMicros].
     */
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

    override fun isActive(): Boolean = envelope.points.isNotEmpty()

    override fun onFlush() {
        // initialPositionUs is clip-relative timeline time, and this processor
        // runs after the Sonic speed stage, so its frames advance at timeline
        // rate. Identical re-basing to FadeAudioProcessor.
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
        val speed = if (playbackSpeed > 0f) playbackSpeed else 1f

        // Evaluate the envelope once per frame and apply it to every channel of
        // that frame, so interleaved channels always share one gain and no
        // cross-channel imbalance is introduced.
        while (inputBuffer.remaining() >= frameBytes) {
            // frames is a post-speed timeline count from the clip's first frame.
            // Source time advances speed times faster than timeline time, and the
            // clip's audible window begins at its trim start.
            val sourcePositionMicros =
                sourceStartMicros + (frames.toDouble() * speed.toDouble() * 1_000_000.0 / sampleRate).toLong()
            val gain = envelope.gainAt(sourcePositionMicros)
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
}
