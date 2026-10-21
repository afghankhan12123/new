package com.videoeditor.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.videoeditor.core.model.VolumeEnvelope
import com.videoeditor.core.model.VolumeInterpolation
import com.videoeditor.core.model.VolumePoint
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Host tests for the volume-automation DSP processor.
 *
 * These run on the JVM against the Media3 test classpath, so they verify the
 * processor's frame-by-frame arithmetic without an emulator. The critical
 * invariants are:
 *
 * - an empty envelope passes samples through unchanged (byte-identical output),
 * - a gain point at the clip start produces the requested linear gain,
 * - a linear ramp between two points interpolates correctly in dB,
 * - a seek flush re-bases the frame counter so the envelope resumes at the
 *   correct source position,
 * - the output saturates cleanly at the PCM range instead of wrapping.
 */
class VolumeEnvelopeAudioProcessorTest {

    private fun makeProcessor(
        envelope: VolumeEnvelope,
        sourceStartMicros: Long = 0L,
        speed: Float = 1f,
    ): VolumeEnvelopeAudioProcessor {
        val processor = VolumeEnvelopeAudioProcessor(envelope, sourceStartMicros, speed)
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        return processor
    }

    /** Packs interleaved PCM16 samples into a ByteBuffer. */
    private fun pcm16(vararg samples: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    /** Reads interleaved PCM16 samples from a ByteBuffer. */
    private fun readPcm16(buffer: ByteBuffer): IntArray {
        val result = IntArray(buffer.remaining() / 2)
        for (i in result.indices) result[i] = buffer.short.toInt()
        return result
    }

    @Test fun `empty envelope passes samples through unchanged`() {
        val processor = makeProcessor(VolumeEnvelope())
        val input = pcm16(1000, -1000, 0, 32767, -32768, 100)
        processor.queueInput(input)
        val output = processor.output
        assertArrayEquals(intArrayOf(1000, -1000, 0, 32767, -32768, 100), readPcm16(output))
    }

    @Test fun `single point at clip start applies constant gain from frame zero`() {
        // -6 dB is a linear factor of ~0.5012.
        val envelope = VolumeEnvelope(listOf(VolumePoint("a", 0L, -6f)))
        val processor = makeProcessor(envelope)
        val input = pcm16(10000, 10000, -10000, -10000)
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        val expected = (10000 * 10.0.pow(-6.0 / 20.0)).toInt()
        assertEquals(expected.toDouble(), output[0].toDouble(), 2.0)
        assertEquals(expected.toDouble(), output[1].toDouble(), 2.0)
        assertEquals(-expected.toDouble(), output[2].toDouble(), 2.0)
        assertEquals(-expected.toDouble(), output[3].toDouble(), 2.0)
    }

    @Test fun `linear ramp interpolates gain across the segment`() {
        // Ramp from 0 dB at source 0 to -6 dB at source 1 s (48k frames).
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 0L, 0f),
            VolumePoint("b", 1_000_000L, -6f),
        ))
        val processor = makeProcessor(envelope)
        // Feed exactly 48_000 frames of +10000 (one second of stereo silence-free
        // content). The output should ramp linearly in dB from 1.0 to 0.5012.
        val input = pcm16(*IntArray(48_000 * 2) { 10000 })
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        // Frame 0 is at 0 dB (unity).
        assertEquals(10000L, output[0].toLong())
        // The midpoint frame (24_000) is at -3 dB, linear ~0.7079.
        val midFrame = 24_000
        val midGain = output[midFrame * 2].toDouble() / 10000.0
        val expectedMidGain = 10.0.pow(-3.0 / 20.0)
        assertEquals(expectedMidGain, midGain, 0.002)
        // The last frame is at nearly -6 dB.
        val lastGain = output[47_999 * 2].toDouble() / 10000.0
        val expectedLastGain = 10.0.pow(-6.0 / 20.0)
        assertEquals(expectedLastGain, lastGain, 0.002)
    }

    @Test fun `seek flush re-bases the envelope at the resumed source position`() {
        // Ramp from 0 dB at source 0 to -12 dB at source 1 s.
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 0L, 0f),
            VolumePoint("b", 1_000_000L, -12f),
        ))
        val processor = makeProcessor(envelope)
        // Seek to source 500 ms (the midpoint) and feed one frame.
        processor.setPositionMicros(500_000L)
        processor.flush()
        val input = pcm16(10000, 10000)
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        // The resumed frame is at the midpoint: -6 dB.
        val gain = output[0].toDouble() / 10000.0
        val expected = 10.0.pow(-6.0 / 20.0)
        assertEquals(expected, gain, 0.005)
    }

    @Test fun `hold interpolation produces an abrupt step at the next point`() {
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 0L, 0f, VolumeInterpolation.Hold),
            VolumePoint("b", 1_000_000L, -6f),
        ))
        val processor = makeProcessor(envelope)
        val input = pcm16(*IntArray(48_000 * 2) { 10000 })
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        // The last frame of a 1-second buffer at 48 kHz is at 0.999979 s,
        // still inside the hold segment. Both channels hold the earlier gain.
        assertEquals(10000.0, output[47_999 * 2].toDouble(), 2.0)
        assertEquals(10000.0, output[47_999 * 2 + 1].toDouble(), 2.0)
    }

    @Test fun `speed playback maps timeline frames to source positions correctly`() {
        // At 2x, one timeline frame covers two source frames, so the envelope
        // advances twice as fast. Ramp from 0 dB at source 0 to -6 dB at 1 s.
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 0L, 0f),
            VolumePoint("b", 1_000_000L, -6f),
        ))
        // 2x speed: the processor's frames are post-speed timeline frames.
        val processor = makeProcessor(envelope, speed = 2f)
        // Feed 24_000 timeline frames (0.5 s of timeline time = 1.0 s of source).
        val input = pcm16(*IntArray(24_000 * 2) { 10000 })
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        // Timeline frame 24_000 is source frame 48_000 = source 1.0 s = -6 dB.
        val lastGain = output[23_999 * 2].toDouble() / 10000.0
        assertEquals(10.0.pow(-6.0 / 20.0), lastGain, 0.005)
    }

    @Test fun `trim offset maps the envelope to the correct source position`() {
        // A clip trimmed to start at source 500 ms with an envelope point at
        // source 750 ms at -6 dB. The clip's first frame is source 500 ms.
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 750_000L, -6f),
        ))
        val processor = makeProcessor(envelope, sourceStartMicros = 500_000L)
        // Feed 12_000 frames (0.25 s). The clip's first frame is source 500 ms.
        // At source 500 ms the envelope holds its first point's gain (-6 dB).
        val input = pcm16(*IntArray(12_000 * 2) { 10000 })
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        val gain = output[0].toDouble() / 10000.0
        assertEquals(10.0.pow(-6.0 / 20.0), gain, 0.005)
    }

    @Test fun `output saturates at the pcm range instead of wrapping`() {
        val envelope = VolumeEnvelope(listOf(VolumePoint("a", 0L, 12f)))
        val processor = makeProcessor(envelope)
        val input = pcm16(32767, -32768)
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        // +12 dB on 32767 would be ~130_000; it must clamp, not wrap.
        assertEquals(32767.toLong(), output[0].toLong())
        assertEquals(-32768.toLong(), output[1].toLong())
    }

    @Test fun `mute produces silence regardless of envelope`() {
        // Mute is applied by the base volume stage before this processor; the
        // envelope only sees silence and must pass it through.
        val envelope = VolumeEnvelope(listOf(VolumePoint("a", 0L, 12f)))
        val processor = makeProcessor(envelope)
        val input = pcm16(0, 0, 0, 0)
        processor.queueInput(input)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), readPcm16(processor.output))
    }

    @Test fun `gain in dB is applied per frame to every channel`() {
        // Stereo input: both channels of the same frame must get the same gain.
        val envelope = VolumeEnvelope(listOf(VolumePoint("a", 0L, -6f)))
        val processor = makeProcessor(envelope)
        val input = pcm16(10000, 5000, -10000, -5000)
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        val expectedHi = (10000 * 10.0.pow(-6.0 / 20.0)).toInt()
        val expectedLo = (5000 * 10.0.pow(-6.0 / 20.0)).toInt()
        assertEquals(expectedHi.toDouble(), output[0].toDouble(), 2.0)
        assertEquals(expectedLo.toDouble(), output[1].toDouble(), 2.0)
        assertEquals(-expectedHi.toDouble(), output[2].toDouble(), 2.0)
        assertEquals(-expectedLo.toDouble(), output[3].toDouble(), 2.0)
    }
    @Test fun `fractional speeds preserve source time after trim and seek`() {
        for (speed in listOf(.5f, 1.5f, 2.5f)) {
            val processor = makeProcessor(VolumeEnvelope(listOf(
                VolumePoint("a", 0L, 0f), VolumePoint("b", 2_000_000L, -12f)
            )), sourceStartMicros = 250_000L, speed = speed)
            processor.setPositionMicros(500_000L)
            processor.flush()
            processor.queueInput(pcm16(10000, 10000))
            val actual = readPcm16(processor.output)
            val sourceUs = 250_000 + 500_000 * speed.toDouble()
            val expected = (10000 * 10.0.pow(-12 * sourceUs / 2_000_000 / 20)).toInt()
            assertEquals("speed=$speed", expected.toDouble(), actual[0].toDouble(), 2.0)
            assertEquals(actual[0], actual[1])
            processor.reset()
        }
    }
}
