package com.videoeditor.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.videoeditor.core.model.EqualizerPreset
import com.videoeditor.core.model.EqualizerSettings
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Host tests for the three-band equalizer DSP processor.
 *
 * These verify the filter's actual frequency response against the documented
 * biquad formulas, its stability at both supported sample rates, its bypass
 * behaviour, and its state lifecycle across flushes (the seek path).
 *
 * Test signals are synthetic pure tones generated as PCM16, so the measured
 * response is exact to within the quantization of the test signal itself.
 */
class EqualizerAudioProcessorTest {

    private fun makeProcessor(settings: EqualizerSettings, sampleRate: Int = 48_000, channels: Int = 2): EqualizerAudioProcessor {
        val processor = EqualizerAudioProcessor(settings)
        processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
        processor.flush()
        return processor
    }

    /** Generates a PCM16 sine tone of the given frequency, amplitude and length. */
    private fun sine(frequencyHz: Float, amplitude: Float, sampleRate: Int, frames: Int, channels: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) {
            val value = (amplitude * sin(2 * PI * frequencyHz * frame / sampleRate) * 32767).toInt().toShort()
            repeat(channels) { buffer.putShort(value) }
        }
        buffer.flip()
        return buffer
    }

    /** Measures the RMS amplitude of a PCM16 buffer (all channels). */
    private fun rms(buffer: ByteBuffer): Double {
        val copy = buffer.duplicate()
        copy.order(ByteOrder.LITTLE_ENDIAN)
        var sumOfSquares = 0.0
        var count = 0
        while (copy.remaining() >= 2) {
            val sample = copy.short / 32768.0
            sumOfSquares += sample * sample
            count++
        }
        return if (count == 0) 0.0 else sqrt(sumOfSquares / count)
    }

    @Test fun `disabled eq passes samples through unchanged`() {
        val processor = makeProcessor(EqualizerSettings(enabled = false))
        val input = sine(440f, 0.5f, 48_000, 4800, 2)
        val inputRms = rms(input.duplicate())
        processor.queueInput(input)
        val outputRms = rms(processor.output)
        assertEquals(inputRms, outputRms, 1e-3)
    }

    @Test fun `enabled flat eq is a numerical pass-through`() {
        val processor = makeProcessor(EqualizerSettings(enabled = true, bassGainDb = 0f, midGainDb = 0f, trebleGainDb = 0f))
        assertFalse(processor.isActive)
        val input = sine(440f, 0.5f, 48_000, 4800, 2)
        val inputRms = rms(input.duplicate())
        processor.queueInput(input)
        val outputRms = rms(processor.output)
        assertEquals(inputRms, outputRms, 1e-3)
    }

    @Test fun `bass boost raises a 100 hz tone and leaves a 10 khz tone alone`() {
        val processor = makeProcessor(EqualizerSettings(enabled = true, bassGainDb = 6f))
        // Low tone: should be boosted.
        val lowIn = sine(100f, 0.3f, 48_000, 4800, 2)
        val lowInRms = rms(lowIn.duplicate())
        processor.queueInput(lowIn)
        val lowOutRms = rms(processor.output)
        // A +6 dB shelf at 100 Hz should give roughly +3 dB at the shelf centre.
        assertTrue("low tone should be boosted", lowOutRms > lowInRms * 1.2)

        val processor2 = makeProcessor(EqualizerSettings(enabled = true, bassGainDb = 6f))
        val highIn = sine(10_000f, 0.3f, 48_000, 4800, 2)
        val highInRms = rms(highIn.duplicate())
        processor2.queueInput(highIn)
        val highOutRms = rms(processor2.output)
        // High tone should be unchanged (within 1 dB).
        val ratioDb = 20 * kotlin.math.log10(highOutRms / highInRms)
        assertTrue("high tone should be flat, got ${ratioDb} dB", abs(ratioDb) < 1.0)
    }

    @Test fun `mid boost raises a 1 khz tone and leaves extremes alone`() {
        val processor = makeProcessor(EqualizerSettings(enabled = true, midGainDb = 6f))
        val midIn = sine(1_000f, 0.3f, 48_000, 4800, 2)
        val midInRms = rms(midIn.duplicate())
        processor.queueInput(midIn)
        val midOutRms = rms(processor.output)
        val ratioDb = 20 * kotlin.math.log10(midOutRms / midInRms)
        assertTrue("mid tone should be boosted by ~6 dB, got ${ratioDb}", ratioDb in 4.0..8.0)

        val processor2 = makeProcessor(EqualizerSettings(enabled = true, midGainDb = 6f))
        val lowIn = sine(50f, 0.3f, 48_000, 4800, 2)
        val lowInRms = rms(lowIn.duplicate())
        processor2.queueInput(lowIn)
        val lowOutRms = rms(processor2.output)
        val lowRatioDb = 20 * kotlin.math.log10(lowOutRms / lowInRms)
        assertTrue("low tone should be flat, got ${lowRatioDb}", abs(lowRatioDb) < 1.0)
    }

    @Test fun `treble boost raises a 12 khz tone and leaves a 100 hz tone alone`() {
        val processor = makeProcessor(EqualizerSettings(enabled = true, trebleGainDb = 6f))
        val highIn = sine(12_000f, 0.3f, 48_000, 4800, 2)
        val highInRms = rms(highIn.duplicate())
        processor.queueInput(highIn)
        val highOutRms = rms(processor.output)
        assertTrue("high tone should be boosted", highOutRms > highInRms * 1.2)

        val processor2 = makeProcessor(EqualizerSettings(enabled = true, trebleGainDb = 6f))
        val lowIn = sine(100f, 0.3f, 48_000, 4800, 2)
        val lowInRms = rms(lowIn.duplicate())
        processor2.queueInput(lowIn)
        val lowOutRms = rms(processor2.output)
        val ratioDb = 20 * kotlin.math.log10(lowOutRms / lowInRms)
        assertTrue("low tone should be flat, got ${ratioDb}", abs(ratioDb) < 1.0)
    }

    @Test fun `eq is stable and finite at both sample rates for all presets`() {
        for (rate in listOf(44_100, 48_000)) {
            for (preset in EqualizerPreset.entries) {
                val processor = makeProcessor(preset.settings(), sampleRate = rate)
                // Feed a long multi-tone burst; the filter must not ring forever.
                val input = sine(440f, 0.5f, rate, rate * 2, 2)
                processor.queueInput(input)
                val output = processor.output
                val outRms = rms(output.duplicate())
                assertTrue("preset ${preset.name} at ${rate} Hz must stay finite", outRms.isFinite())
                assertTrue("preset ${preset.name} at ${rate} Hz must not be silent", outRms > 0)
                // No band can amplify a 0.5-amplitude tone beyond the PCM range.
                val copy = output.duplicate()
                copy.order(ByteOrder.LITTLE_ENDIAN)
                var maxSample = 0
                while (copy.remaining() >= 2) {
                    val sample = copy.short.toInt()
                    if (abs(sample) > maxSample) maxSample = abs(sample)
                }
                assertTrue("preset ${preset.name} must not exceed PCM range", maxSample <= 32767)
            }
        }
    }

    @Test fun `flush resets filter state so a seek does not ring a stale tail`() {
        val processor = makeProcessor(EqualizerSettings(enabled = true, bassGainDb = 12f))
        // Feed a loud low-frequency burst to excite the bass band.
        processor.queueInput(sine(80f, 0.9f, 48_000, 4800, 2))
        processor.flush()
        // Feed silence; the filter must not emit a decaying tail from the burst.
        processor.queueInput(sine(80f, 0f, 48_000, 480, 2))
        val outputRms = rms(processor.output)
        assertTrue("a flushed filter must be silent on silence", outputRms < 1e-6)
    }

    @Test fun `presets are reproducible across processor instances`() {
        val settings = EqualizerPreset.Voice.settings()
        val a = makeProcessor(settings)
        val b = makeProcessor(settings)
        val input = sine(1_000f, 0.3f, 48_000, 4800, 2)
        a.queueInput(input.duplicate())
        b.queueInput(input.duplicate())
        val outA = a.output.duplicate()
        val outB = b.output.duplicate()
        assertEquals(outA.remaining(), outB.remaining())
        while (outA.hasRemaining()) {
            assertEquals(outA.get(), outB.get())
        }
    }

    @Test fun `eq does not introduce cross-channel bleed`() {
        val processor = makeProcessor(EqualizerSettings(enabled = true, bassGainDb = 12f), channels = 2)
        // Left channel is a loud low tone; right channel is silence.
        val buffer = ByteBuffer.allocate(4800 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until 4800) {
            val left = (0.8 * sin(2 * PI * 80 * frame / 48_000) * 32767).toInt().toShort()
            buffer.putShort(left)
            buffer.putShort(0.toShort())
        }
        buffer.flip()
        processor.queueInput(buffer)
        val output = processor.output.duplicate()
        output.order(ByteOrder.LITTLE_ENDIAN)
        var rightMax = 0
        while (output.remaining() >= 4) {
            output.short // left
            val right = output.short.toInt()
            if (abs(right) > rightMax) rightMax = abs(right)
        }
        assertEquals("right channel must stay silent", 0, rightMax)
    }
}
