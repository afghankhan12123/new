package com.videoeditor.core.media.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scales PCM audio samples by a volume factor. Supports 16-bit integer and
 * 32-bit float encodings; other encodings pass through unchanged.
 *
 * Media3 1.5.x has no built-in per-clip volume audio processor, so this provides
 * the real volume control used for music, voiceovers, and lowered original audio.
 */
@UnstableApi
class VolumeAudioProcessor(private val volume: Float) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        val outputBuffer = replaceOutputBuffer(size)

        when (inputAudioFormat.encoding) {
            androidx.media3.common.C.ENCODING_PCM_16BIT -> scalePcm16(inputBuffer, outputBuffer, size)
            androidx.media3.common.C.ENCODING_PCM_FLOAT -> scaleFloat(inputBuffer, outputBuffer, size)
            else -> {
                // Pass through unsupported encodings unmodified.
                for (i in 0 until size) {
                    outputBuffer.put(inputBuffer.get())
                }
            }
        }

        inputBuffer.position(limit)
        outputBuffer.flip()
    }

    private fun scalePcm16(input: ByteBuffer, output: ByteBuffer, size: Int) {
        input.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < size) {
            val sample = input.short
            val scaled = (sample * volume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(scaled.toShort())
            i += 2
        }
    }

    private fun scaleFloat(input: ByteBuffer, output: ByteBuffer, size: Int) {
        input.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < size) {
            val sample = input.float
            output.putFloat((sample * volume).coerceIn(-1f, 1f))
            i += 4
        }
    }
}
