package com.videoeditor.core.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.media.audio.VolumeEnvelopeAudioProcessor
import com.videoeditor.core.media.audio.BalanceAudioProcessor
import com.videoeditor.core.model.AudioBalance
import com.videoeditor.core.model.VolumeEnvelope
import com.videoeditor.core.model.VolumePoint
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device gates: native AAC decode and independent source-clock arithmetic. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Stage8AudioPcmDeviceTest {
    @Test fun nativeVoiceTriggerHasSilentAndActiveInteriorWindows() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val file = File(context.cacheDir, "stage8-voice-trigger.m4a")
        context.assets.open("stage8/voice_trigger_880_2s_4s.m4a").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        try {
            val rms = audioRms(file, listOf(500_000L..999_999L, 2_500_000L..2_999_999L,
                3_000_000L..3_499_999L, 4_500_000L..4_999_999L, 6_000_000L..6_499_999L))
            assertTrue("Interior silence: $rms", listOf(0, 3, 4).all { rms[it] < 2.0 })
            assertTrue("Interior trigger: $rms", listOf(1, 2).all { rms[it] > 2000.0 })
            assertEquals(880.0, audioToneFrequency(file, 2_500_000L..3_499_999L), 8.0)
        } finally { file.delete() }
    }

    @Test fun fractionalSpeedsAdvanceAutomationInSourceTime() {
        for (rate in listOf(44100, 48000)) for (speed in listOf(.5f, 1.5f, 2.5f)) {
            val processor = VolumeEnvelopeAudioProcessor(VolumeEnvelope(listOf(
                VolumePoint("a", 0, 0f), VolumePoint("b", 2_000_000, -12f))), 250_000, speed)
            processor.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
            processor.setPositionMicros(500_000); processor.flush()
            val input = ByteBuffer.allocateDirect(2).order(ByteOrder.LITTLE_ENDIAN).putShort(10000)
            input.flip(); processor.queueInput(input)
            val actual = processor.output.order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            val sourceUs = 250_000 + 500_000 * speed.toDouble()
            val expected = (10000 * 10.0.pow((-12 * sourceUs / 2_000_000) / 20)).toInt()
            assertEquals("rate=$rate speed=$speed", expected.toDouble(), actual.toDouble(), 2.0)
            processor.reset()
        }
    }

    @Test fun monoPanDeclaresStereoAndPreservesSelectedChannel() {
        for (pan in listOf(-1f, 1f)) {
            val processor = BalanceAudioProcessor(AudioBalance(pan))
            val format = processor.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
            assertEquals(2, format.channelCount); processor.flush()
            val input = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).putShort(10000).putShort(-10000)
            input.flip(); processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            for (sample in listOf(10000, -10000)) {
                assertEquals(if (pan < 0) (sample * kotlin.math.sqrt(2.0)).toInt() else 0, output.short.toInt())
                assertEquals(if (pan > 0) (sample * kotlin.math.sqrt(2.0)).toInt() else 0, output.short.toInt())
            }
            assertFalse(output.hasRemaining()); processor.reset()
        }
    }
    @Test fun seekIntentSurvivesDecoderReconfigurationForStage8Envelopes() {
        val envelope = VolumeEnvelope(listOf(VolumePoint("a",0,0f), VolumePoint("b",1_000_000,-12f)))
        val automation = VolumeEnvelopeAudioProcessor(envelope,0,1f)
        val ducking = com.videoeditor.core.media.audio.DuckingGainAudioProcessor(
            com.videoeditor.core.media.audio.DuckingCurve(0,500_000,floatArrayOf(1f,.25f,.25f)),0)
        for ((processor, seekable, expected) in listOf(
            Triple(automation as AudioProcessor, automation as com.videoeditor.core.media.audio.SeekableGainAudioProcessor, 5011),
            Triple(ducking as AudioProcessor, ducking as com.videoeditor.core.media.audio.SeekableGainAudioProcessor, 2500))) {
            val format = AudioProcessor.AudioFormat(48000,1,C.ENCODING_PCM_16BIT)
            processor.configure(format); processor.flush()
            seekable.setPositionMicros(500_000)
            processor.reset(); processor.configure(format); processor.flush()
            val input = ByteBuffer.allocateDirect(2).order(ByteOrder.LITTLE_ENDIAN).putShort(10000)
            input.flip(); processor.queueInput(input)
            assertEquals("Seek intent erased by reset",expected.toDouble(),processor.output.order(ByteOrder.LITTLE_ENDIAN).short.toDouble(),2.0)
            processor.reset()
        }
    }
}
