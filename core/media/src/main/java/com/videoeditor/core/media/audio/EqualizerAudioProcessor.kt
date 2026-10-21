package com.videoeditor.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.videoeditor.core.model.EqualizerBands
import com.videoeditor.core.model.EqualizerSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies a three-band equalizer to PCM as it streams.
 *
 * Each band is a Direct Form II Transposed biquad built from
 * [com.videoeditor.core.model.BiquadCoefficients]. DF2T keeps the state vector
 * small (two values per band per channel), keeps the input and output in the
 * same buffer layout so no extra PCM copy is made, and behaves well in float
 * arithmetic.
 *
 * # Channel handling
 *
 * State is held per band per channel, so left and right are filtered
 * independently and no cross-channel bleed is introduced. Coefficients are
 * shared across channels because a stereo clip hears the same filter on both
 * sides.
 *
 * # State lifecycle
 *
 * Coefficients are recomputed when the sample rate changes (so the same
 * [EqualizerSettings] produces the same response at 44.1 kHz and 48 kHz) and
 * state is cleared on flush, which is also the seek path. Resetting state on
 * flush means a seek does not ring a stale filter tail into the new position,
 * which would be audible as a transient.
 *
 * # Bypass
 *
 * The identity state reports itself inactive, so Media3 removes the processor
 * from the chain entirely and the accepted Stage 7 audio path is reproduced
 * exactly. An enabled-but-all-zero settings object is also a no-op here because
 * all three bands reduce to unity coefficients.
 *
 * # Stability
 *
 * Coefficients are built from the documented RBJ formulas and are checked for
 * stability by [com.videoeditor.core.model.BiquadCoefficients.isStable]. Output
 * is saturated in the float domain before PCM conversion so a boosted band can
 * never wrap or produce NaN/Infinity.
 */
@UnstableApi
class EqualizerAudioProcessor(
    private val settings: EqualizerSettings,
) : BaseAudioProcessor() {

    private var bands: EqualizerBands = EqualizerBands.IDENTITY

    // Per-band, per-channel DF2T state. Allocated on configure, reused for the
    // whole configuration; never allocated per buffer.
    private var bassX1 = FloatArray(0); private var bassX2 = FloatArray(0)
    private var bassY1 = FloatArray(0); private var bassY2 = FloatArray(0)
    private var midX1 = FloatArray(0); private var midX2 = FloatArray(0)
    private var midY1 = FloatArray(0); private var midY2 = FloatArray(0)
    private var trebleX1 = FloatArray(0); private var trebleX2 = FloatArray(0)
    private var trebleY1 = FloatArray(0); private var trebleY2 = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        bands = EqualizerBands.from(settings, inputAudioFormat.sampleRate)
        allocateState(inputAudioFormat.channelCount.coerceAtLeast(1))
        return inputAudioFormat
    }

    override fun isActive(): Boolean = settings.isActive && !bands.isUnity

    private fun allocateState(channels: Int) {
        bassX1 = FloatArray(channels); bassX2 = FloatArray(channels)
        bassY1 = FloatArray(channels); bassY2 = FloatArray(channels)
        midX1 = FloatArray(channels); midX2 = FloatArray(channels)
        midY1 = FloatArray(channels); midY2 = FloatArray(channels)
        trebleX1 = FloatArray(channels); trebleX2 = FloatArray(channels)
        trebleY1 = FloatArray(channels); trebleY2 = FloatArray(channels)
    }

    private fun clearState() {
        bassX1.fill(0f); bassX2.fill(0f); bassY1.fill(0f); bassY2.fill(0f)
        midX1.fill(0f); midX2.fill(0f); midY1.fill(0f); midY2.fill(0f)
        trebleX1.fill(0f); trebleX2.fill(0f); trebleY1.fill(0f); trebleY2.fill(0f)
    }

    override fun onFlush() {
        clearState()
    }

    override fun onReset() {
        clearState()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameBytes = bytesPerSample * channels
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)

        val bass = bands.bass
        val mid = bands.mid
        val treble = bands.treble
        val doBass = !bass.isUnity
        val doMid = !mid.isUnity
        val doTreble = !treble.isUnity

        while (inputBuffer.remaining() >= frameBytes) {
            for (channel in 0 until channels) {
                var sample = if (isFloat) inputBuffer.float else inputBuffer.short / 32768f
                if (doBass) sample = runBiquad(sample, bass, bassX1, bassX2, bassY1, bassY2, channel)
                if (doMid) sample = runBiquad(sample, mid, midX1, midX2, midY1, midY2, channel)
                if (doTreble) sample = runBiquad(sample, treble, trebleX1, trebleX2, trebleY1, trebleY2, channel)
                // Saturate before conversion so a boosted combination can never
                // wrap the PCM range or emit NaN/Infinity.
                if (!sample.isFinite()) sample = 0f
                sample = sample.coerceIn(-1f, 1f)
                if (isFloat) {
                    output.putFloat(sample)
                } else {
                    output.putShort((sample * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }
        output.flip()
    }

    private fun runBiquad(
        input: Float,
        coefficients: com.videoeditor.core.model.BiquadCoefficients,
        x1: FloatArray, x2: FloatArray, y1: FloatArray, y2: FloatArray,
        channel: Int,
    ): Float {
        val result = coefficients.b0 * input +
            coefficients.b1 * x1[channel] +
            coefficients.b2 * x2[channel] -
            coefficients.a1 * y1[channel] -
            coefficients.a2 * y2[channel]
        x2[channel] = x1[channel]
        x1[channel] = input
        y2[channel] = y1[channel]
        y1[channel] = result
        return result
    }
}
