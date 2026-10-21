package com.videoeditor.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.videoeditor.core.model.FadeCurve
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Linear envelope in post-speed timeline time. Interleaved channels share one gain.
 *
 * Stage 8 adds selectable curve shapes. [FadeCurve.Linear] for both fades
 * reproduces the accepted Stage 7 behaviour exactly, sample for sample, and is
 * the default so existing call sites and documents are unchanged.
 *
 * A fade-in ramps `0 -> 1` over its elapsed progress. A fade-out is evaluated on
 * the *remaining* fraction of its window, so it travels `1 -> 0` along the same
 * shape without needing a mirrored curve table. Where the two windows overlap on
 * a short clip, the lower of the two gains wins, which is the same combination
 * rule Stage 7 used and keeps the result continuous with no gain applied twice.
 */
@UnstableApi
class FadeAudioProcessor(
    private val durationUs: Long,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val fadeInCurve: FadeCurve = FadeCurve.Linear,
    private val fadeOutCurve: FadeCurve = FadeCurve.Linear,
) : BaseAudioProcessor(), SeekableGainAudioProcessor {
    private var frames = 0L
    @Volatile private var initialPositionUs = 0L
    /** Set before the player's seek flush; subsequent PCM starts at this edit time. */
    override fun setPositionMicros(positionUs: Long) { initialPositionUs = positionUs.coerceIn(0L, durationUs) }
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun onFlush() { frames = initialPositionUs * inputAudioFormat.sampleRate.coerceAtLeast(0) / 1_000_000L }
    // Decoder/speed-chain reconfiguration may follow setPositionMicros on another
    // thread. Reset processing buffers, retaining the newest explicit seek intent.
    // Replay explicitly requests zero; a new processor starts at zero naturally.
    override fun onReset() { frames = 0L }
    override fun queueInput(inputBuffer: ByteBuffer) {
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        val channels = inputAudioFormat.channelCount
        val bytes = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        while (inputBuffer.remaining() >= bytes * channels) {
            val t = frames * 1_000_000L / inputAudioFormat.sampleRate
            val fadeIn = if (fadeInUs > 0) fadeInCurve.amplitudeAt((t.toFloat() / fadeInUs)) else 1f
            val fadeOut = if (fadeOutUs > 0) fadeOutCurve.amplitudeAt(((durationUs - t).toFloat() / fadeOutUs)) else 1f
            val gain = minOf(fadeIn, fadeOut)
            repeat(channels) {
                if (bytes == 4) output.putFloat(inputBuffer.float * gain)
                else output.putShort((inputBuffer.short * gain).toInt().toShort())
            }
            frames++
        }
        output.flip()
    }
}
