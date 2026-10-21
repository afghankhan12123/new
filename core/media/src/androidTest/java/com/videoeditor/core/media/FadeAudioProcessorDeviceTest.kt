package com.videoeditor.core.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.core.media.audio.FadeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class FadeAudioProcessorDeviceTest {
    @Test fun envelope_uses_frames_not_channels_and_resets_after_seek() {
        val processor = FadeAudioProcessor(1_000_000L, 500_000L, 500_000L)
        processor.configure(AudioProcessor.AudioFormat(4, 2, C.ENCODING_PCM_16BIT))
        repeat(2) {
            processor.flush()
            val input = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)
            repeat(8) { input.putShort(10000) }; input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            listOf(0, 5000, 10000, 5000).forEach { expected -> repeat(2) { assertEquals(expected, output.short.toInt()) } }
            assertEquals(0, output.remaining())
        }
        processor.reset()
    }

    @Test fun seek_flush_starts_at_the_clip_local_envelope_position() {
        val processor = FadeAudioProcessor(2_000_000, 500_000, 500_000)
        processor.configure(AudioProcessor.AudioFormat(4, 1, C.ENCODING_PCM_16BIT))
        processor.setPositionMicros(1_500_000); processor.flush()
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).putShort(10000).putShort(10000)
        input.flip(); processor.queueInput(input)
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10000, output.short.toInt()); assertEquals(5000, output.short.toInt())
        processor.setPositionMicros(0); processor.flush()
        val replay = ByteBuffer.allocateDirect(2).order(ByteOrder.LITTLE_ENDIAN).putShort(10000)
        replay.flip(); processor.queueInput(replay)
        assertEquals(0, processor.output.order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        processor.reset()
    }
    @Test fun decoder_reconfiguration_cannot_erase_a_newer_seek_envelope() {
        val processor = FadeAudioProcessor(2_000_000, 0, 500_000)
        val format = AudioProcessor.AudioFormat(100, 1, C.ENCODING_PCM_16BIT)
        processor.configure(format); processor.flush()
        processor.setPositionMicros(1_850_000)
        // Media3 can reset/reconfigure the speed-processing chain after the
        // main-thread seek intention, before its playback-thread flush.
        processor.reset(); processor.configure(format); processor.flush()
        val input = ByteBuffer.allocateDirect(2).order(ByteOrder.LITTLE_ENDIAN).putShort(10000)
        input.flip(); processor.queueInput(input)
        val sample = processor.output.order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        org.junit.Assert.assertTrue("Seek envelope was erased: $sample", sample in 2999..3001)
        processor.reset()
    }

}
