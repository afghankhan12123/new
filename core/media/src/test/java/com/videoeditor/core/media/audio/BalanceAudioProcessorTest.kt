package com.videoeditor.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.videoeditor.core.model.AudioBalance
import com.videoeditor.core.model.BalanceGains
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Host tests for the balance/pan DSP processor.
 *
 * The critical invariants are:
 *
 * - a centre setting passes both mono and stereo through unchanged,
 * - a mono source with an off-centre pan becomes stereo and routes energy
 *   to the requested side,
 * - a stereo source with an off-centre balance keeps its channel identity and
 *   attenuates the opposite side,
 * - the constant-power pan law holds across the full range.
 */
class BalanceAudioProcessorTest {

    @Test fun `mixed centered mono expands with unity on both channels`() {
        val processor = BalanceAudioProcessor(AudioBalance(), forceStereo = true)
        val outputFormat = processor.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        assertTrue(processor.isActive)
        assertEquals(2, outputFormat.channelCount)
        processor.queueInput(pcm16(1000, -2000))
        assertArrayEquals(intArrayOf(1000,1000,-2000,-2000),readPcm16(processor.output))
    }

    @Test fun `mixed stereo retains identity and bypasses centered balance`() {
        val processor = BalanceAudioProcessor(AudioBalance(), forceStereo = true)
        val format = processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        // BaseAudioProcessor reports NOT_SET when the configured processor bypasses.
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, format)
        assertFalse(processor.isActive)
    }

    private fun makeProcessor(pan: Float, channels: Int, sampleRate: Int = 48_000): BalanceAudioProcessor {
        val processor = BalanceAudioProcessor(AudioBalance(pan))
        processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
        processor.flush()
        return processor
    }

    private fun pcm16(vararg samples: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    private fun readPcm16(buffer: ByteBuffer): IntArray {
        val result = IntArray(buffer.remaining() / 2)
        for (i in result.indices) result[i] = buffer.short.toInt()
        return result
    }

    @Test fun `centre pan passes mono through unchanged`() {
        val processor = makeProcessor(0f, channels = 1)
        val input = pcm16(1000, -1000, 0, 32767)
        processor.queueInput(input)
        val output = processor.output
        assertArrayEquals(intArrayOf(1000, -1000, 0, 32767), readPcm16(output))
    }

    @Test fun `centre pan passes stereo through unchanged`() {
        val processor = makeProcessor(0f, channels = 2)
        val input = pcm16(1000, -1000, 0, 32767, -32768, 100)
        processor.queueInput(input)
        val output = processor.output
        assertArrayEquals(intArrayOf(1000, -1000, 0, 32767, -32768, 100), readPcm16(output))
    }

    @Test fun `full left pan on mono puts all energy in the left channel`() {
        val processor = makeProcessor(-1f, channels = 1)
        val input = pcm16(10000, 10000)
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        // Stereo output: left = sqrt(2) * 10000, right = 0.
        assertEquals((10000 * sqrt(2.0)), output[0].toDouble(), 3.0)
        assertEquals(0.0, output[1].toDouble(), 0.0)
        assertEquals((10000 * sqrt(2.0)), output[2].toDouble(), 3.0)
        assertEquals(0.0, output[3].toDouble(), 0.0)
    }

    @Test fun `full right pan on mono puts all energy in the right channel`() {
        val processor = makeProcessor(1f, channels = 1)
        val input = pcm16(10000, 10000)
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        assertEquals(0.0, output[0].toDouble(), 0.0)
        assertEquals((10000 * sqrt(2.0)), output[1].toDouble(), 3.0)
        assertEquals(0.0, output[2].toDouble(), 0.0)
        assertEquals((10000 * sqrt(2.0)), output[3].toDouble(), 3.0)
    }

    @Test fun `mono pan centre passes mono through unchanged`() {
        // Centre pan is the identity state: the processor is inactive and the
        // mono input passes through without channel expansion.
        val processor = makeProcessor(0f, channels = 1)
        assertFalse(processor.isActive)
        val input = pcm16(10000, 10000)
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        assertArrayEquals(intArrayOf(10000, 10000), output)
    }

    @Test fun `stereo balance full left attenuates the right channel`() {
        val processor = makeProcessor(-1f, channels = 2)
        val input = pcm16(10000, 10000, 10000, 10000)
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        assertEquals(10000L, output[0].toLong())
        assertEquals(0.toLong(), output[1].toLong())
        assertEquals(10000L, output[2].toLong())
        assertEquals(0.toLong(), output[3].toLong())
    }

    @Test fun `stereo balance full right attenuates the left channel`() {
        val processor = makeProcessor(1f, channels = 2)
        val input = pcm16(10000, 10000, 10000, 10000)
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        assertEquals(0.toLong(), output[0].toLong())
        assertEquals(10000L, output[1].toLong())
        assertEquals(0.toLong(), output[2].toLong())
        assertEquals(10000L, output[3].toLong())
    }

    @Test fun `stereo balance keeps channel identity at partial settings`() {
        val processor = makeProcessor(0.5f, channels = 2)
        val input = pcm16(10000, 10000)
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        // Left attenuated to cos(pi/4) * 10000 ≈ 7071, right stays at 10000.
        assertEquals(10000 * cos(Math.PI / 4), output[0].toDouble(), 5.0)
        assertEquals(10000L, output[1].toLong())
    }

    @Test fun `mono pan constant power holds across the range`() {
        for (pan in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            val gains = BalanceGains.forMonoPan(pan)
            val power = gains.left * gains.left + gains.right * gains.right
            assertEquals(2f, power, 1e-3f)
        }
    }

    @Test fun `pan output saturates at the pcm range`() {
        val processor = makeProcessor(1f, channels = 1)
        val input = pcm16(32767, -32768)
        processor.queueInput(input)
        val output = readPcm16(processor.output)
        assertEquals(32767.toLong(), output[1].toLong())
        assertEquals(-32768.toLong(), output[3].toLong())
    }

    @Test fun `inactive centre pan is not active`() {
        val processor = BalanceAudioProcessor(AudioBalance(0f))
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertFalse(processor.isActive)
    }

    @Test fun `off-centre pan is active`() {
        val processor = BalanceAudioProcessor(AudioBalance(0.5f))
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertTrue(processor.isActive)
    }
}
