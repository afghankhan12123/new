package com.videoeditor.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.videoeditor.core.model.AudioBalance
import com.videoeditor.core.model.BalanceGains
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies stereo balance or mono-to-stereo panning to PCM as it streams.
 *
 * The behaviour depends on the input channel count, which is only known once
 * the format is configured:
 *
 * - **Mono input** becomes stereo. Each frame's single sample is routed across
 *   two channels by [BalanceGains.forMonoPan], so a centre pan reproduces the
 *   accepted dual-mono image and an off-centre pan moves it. Output is the same
 *   PCM encoding with two channels.
 * - **Stereo input** stays stereo. The opposite channel is attenuated by
 *   [BalanceGains.forStereoBalance] while the requested channel stays at unity,
 *   so the image shifts without amplifying or summing the channels.
 *
 * # Output layout contract
 *
 * A mono source with an off-centre pan outputs two channels. Because the Media3
 * audio graph takes its mix format from the first registered input, this makes
 * the whole mix stereo and the AAC encoder is then configured for two channels.
 * That is the intended Stage 8 behaviour for panning mono material: the output
 * pipeline genuinely preserves a stereo image rather than collapsing it.
 *
 * # Bypass
 *
 * A centre setting is inactive unless [forceStereo] promotes a mono input in a
 * mix containing stereo material. That promotion duplicates mono at unity in
 * both channels so an earlier mono input cannot collapse later stereo inputs.
 * An entirely mono, centered project retains the accepted Stage 7 mono path.
 */
@UnstableApi
class BalanceAudioProcessor(
    private val balance: AudioBalance,
    private val forceStereo: Boolean = false,
) : BaseAudioProcessor() {

    private var gains: BalanceGains = BalanceGains.IDENTITY
    private var outputChannels = 0
    private var inputChannels = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inputChannels = inputAudioFormat.channelCount.coerceAtLeast(1)
        gains = when (inputChannels) {
            1 -> BalanceGains.forMonoPan(balance.pan)
            2 -> BalanceGains.forStereoBalance(balance.pan)
            // More than two channels: leave them alone. Panning is defined for
            // mono and stereo only, and collapsing a multichannel mix would
            // discard channels the editor does not model.
            else -> BalanceGains.IDENTITY
        }
        outputChannels = if (inputChannels == 1 && (balance.pan != 0f || forceStereo)) 2 else inputChannels
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            outputChannels,
            inputAudioFormat.encoding,
        )
    }

    override fun isActive(): Boolean = !balance.isIdentity || (forceStereo && inputChannels == 1)

    override fun queueInput(inputBuffer: ByteBuffer) {
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2

        when {
            // Mono -> stereo pan: expand each frame into a stereo pair.
            inputChannels == 1 && outputChannels == 2 -> {
                val output = replaceOutputBuffer(inputBuffer.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (inputBuffer.remaining() >= bytesPerSample) {
                    if (isFloat) {
                        val sample = inputBuffer.float
                        output.putFloat((sample * gains.left).coerceIn(-1f, 1f))
                        output.putFloat((sample * gains.right).coerceIn(-1f, 1f))
                    } else {
                        val sample = inputBuffer.short.toInt()
                        output.putShort((sample * gains.left).toInt().coerceIn(-32768, 32767).toShort())
                        output.putShort((sample * gains.right).toInt().coerceIn(-32768, 32767).toShort())
                    }
                }
                output.flip()
            }

            // Stereo balance: attenuate the opposite channel, keep channel order.
            inputChannels == 2 -> {
                val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)
                while (inputBuffer.remaining() >= bytesPerSample * 2) {
                    if (isFloat) {
                        val leftSample = inputBuffer.float
                        val rightSample = inputBuffer.float
                        output.putFloat((leftSample * gains.left).coerceIn(-1f, 1f))
                        output.putFloat((rightSample * gains.right).coerceIn(-1f, 1f))
                    } else {
                        val leftSample = inputBuffer.short.toInt()
                        val rightSample = inputBuffer.short.toInt()
                        output.putShort((leftSample * gains.left).toInt().coerceIn(-32768, 32767).toShort())
                        output.putShort((rightSample * gains.right).toInt().coerceIn(-32768, 32767).toShort())
                    }
                }
                output.flip()
            }

            // Any other layout: pass through unchanged.
            else -> {
                val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)
                output.put(inputBuffer)
                output.flip()
            }
        }
    }
}
