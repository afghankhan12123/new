package com.videoeditor.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DurationAudioProcessorTest {
    private fun configure(p: DurationAudioProcessor) {
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); p.flush()
    }
    private fun queue(p: DurationAudioProcessor, frames: Int): Int {
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { input.putShort(1234) }; input.flip(); p.queueInput(input)
        assertFalse(input.hasRemaining())
        val output = p.output.order(ByteOrder.LITTLE_ENDIAN); val count = output.remaining() / 4
        while(output.hasRemaining()) assertEquals(1234.toShort(), output.short)
        return count
    }
    @Test fun bounds_the_total_window_across_multiple_input_buffers() {
        val p = DurationAudioProcessor(1_000_000); configure(p)
        assertEquals(30000, queue(p, 30000)); assertEquals(18000, queue(p, 30000)); assertEquals(0, queue(p, 1024))
        p.queueEndOfStream(); assertTrue(p.isEnded); p.reset()
    }
    @Test fun paused_seek_allows_only_the_remaining_authored_window() {
        val p = DurationAudioProcessor(6_000_000); configure(p)
        p.setPositionMicros(5_500_000); p.flush()
        assertEquals(24000, queue(p, 48000)); assertEquals(0, queue(p, 1024)); p.reset()
    }
    @Test fun decoder_reset_retains_latest_seek_intent_and_replay_requests_zero() {
        val p = DurationAudioProcessor(6_000_000); p.setPositionMicros(5_750_000)
        p.reset(); configure(p); assertEquals(12000, queue(p, 48000))
        p.setPositionMicros(0); p.flush(); assertEquals(48000, queue(p, 48000)); p.reset()
    }
    @Test fun seek_to_end_cannot_emit_padding() {
        val p = DurationAudioProcessor(6_000_000); configure(p)
        p.setPositionMicros(6_000_000); p.flush(); assertEquals(0, queue(p, 1024)); p.reset()
    }
}
