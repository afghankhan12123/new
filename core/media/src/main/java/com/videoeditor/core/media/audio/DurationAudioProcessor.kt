package com.videoeditor.core.media.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Bound resampler/Sonic tail samples to the edit's exact post-speed window. */
@UnstableApi
internal class DurationAudioProcessor(private val durationUs: Long) : BaseAudioProcessor(), SeekableGainAudioProcessor {
    private var framesWritten = 0L
    @Volatile private var initialPositionUs = 0L
    override fun setPositionMicros(positionUs: Long) { initialPositionUs = positionUs.coerceIn(0L, durationUs) }
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = inputAudioFormat
    override fun onFlush() { framesWritten = initialPositionUs * inputAudioFormat.sampleRate.coerceAtLeast(0) / 1_000_000L }
    override fun onReset() { framesWritten = 0L }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameBytes = inputAudioFormat.bytesPerFrame
        val maximum = durationUs * inputAudioFormat.sampleRate / 1_000_000L
        val frames = minOf(inputBuffer.remaining().toLong() / frameBytes, (maximum - framesWritten).coerceAtLeast(0))
        val bytes = (frames * frameBytes).toInt()
        val output = replaceOutputBuffer(bytes)
        val copy = inputBuffer.duplicate()
        copy.limit(copy.position() + bytes)
        output.put(copy)
        framesWritten += frames
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }
}
