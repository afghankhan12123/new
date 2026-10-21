package com.videoeditor.core.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.model.AudioClip
import com.videoeditor.editing.ProjectCreator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreviewAudioClockDeviceTest {
    @Test fun decoded_mixed_preview_obeys_offset_fades_seek_and_pause() {
        verifyPreviewAudio(1f)
    }
    @Test fun sped_music_preview_obeys_the_same_offset_and_fade_clock() {
        verifyPreviewAudio(2f)
    }
    private fun verifyPreviewAudio(speed: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Active media must survive cache eviction while the player reopens
        // its source during a seek. Keep uniquely owned test inputs retained.
        val fixtureDir = File(context.filesDir,
            "clock-fixtures/run-${android.os.SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
        val sources = listOf("clip1_720p_6s.mp4", "music_20s.m4a").map { name ->
            File(fixtureDir, name).also { file ->
                context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
            }
        }
        val refs = sources.map { MediaMetadataExtractor().extract(context, Uri.fromFile(it)).getOrNull()!! }
        val base = ProjectCreator().create("Preview audio clock", listOf(refs[0]))
        val project = base.copy(mediaRefs = refs, timeline = base.timeline.copy(
            videoClips = base.timeline.videoClips.map { it.copy(sourceEndMicros = 4_000_000, muted = true) },
            audioClips = listOf(AudioClip("offset", refs[1].id, sourceEndMicros = (2_000_000 * speed).toLong(),
                playbackSpeed = speed, timelineStartMicros = 1_000_000, fadeInMicros = 500_000, fadeOutMicros = 500_000))))
        data class Sample(val time: Long, val value: Double)
        val samples = mutableListOf<Sample>()
        val lock = Any()
        val sink = object : ForwardingAudioSink(DefaultAudioSink.Builder(context).build()) {
            private var format = Format.Builder().build()
            override fun configure(inputFormat: Format, bufferSize: Int, outputChannels: IntArray?) {
                format = inputFormat
                super.configure(inputFormat, bufferSize, outputChannels)
            }
            override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                val copy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                val initial = buffer.position()
                val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                val consumed = buffer.position() - initial
                val bytes = if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
                val channels = format.channelCount.coerceAtLeast(1)
                val records = mutableListOf<Sample>()
                repeat(consumed / (bytes * channels)) { frame ->
                    repeat(channels) {
                        val value = if (bytes == 4) copy.float.toDouble() * 32767 else copy.short.toDouble()
                        records += Sample(presentationTimeUs + frame * 1_000_000L / format.sampleRate, value)
                    }
                }
                synchronized(lock) { samples += records }
                return handled
            }
        }
        lateinit var controller: CompositionPreviewController
        var created = false
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (!predicate() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertTrue("$message: ${controller.state.value}", predicate())
        }
        fun rms(from: Long, until: Long): Double = synchronized(lock) {
            val window = samples.filter { it.time in from..until }
            assertTrue("No PCM in $from..$until; range=${samples.firstOrNull()?.time}..${samples.lastOrNull()?.time}", window.size > 100)
            sqrt(window.sumOf { it.value * it.value } / window.size)
        }
        try {
            main { controller = CompositionPreviewController.Factory(context, CompositionBuilder()).create(sink)
                created = true; controller.setProject(project); controller.play() }
            await("Preview did not finish") { controller.state.value.positionMicros >= 4_000_000 }
            val before = rms(200_000, 600_000)
            val incoming = rms(1_030_000, 1_130_000)
            val full = rms(1_700_000, 2_200_000)
            val outgoing = rms(2_850_000, 2_950_000)
            val after = rms(3_400_000, 3_800_000)
            android.util.Log.i("PreviewAudioClock", "rms=$before,$incoming,$full,$outgoing,$after")
            assertTrue(before < 5); assertTrue(full > 500); assertTrue(incoming < full * .4)
            assertTrue(outgoing < full * .4); assertTrue(after < 5)
            main { controller.seekTo(1_700_000); controller.play() }
            await("Seek playback stalled") { controller.state.value.positionMicros >= 2_050_000 }
            main { controller.pause() }
            val requestedPause = controller.state.value.positionMicros
            Thread.sleep(150) // Player pause is dispatched to the playback thread.
            val paused = controller.state.value.positionMicros
            android.util.Log.i("PreviewAudioClock", "pause requested=$requestedPause settled=$paused drift=${paused-requestedPause}")
            assertTrue("Pause took more than one audio buffer: ${paused-requestedPause}us", paused - requestedPause <= 50_000)
            Thread.sleep(250)
            assertEquals("Pause advanced the shared clock", paused, controller.state.value.positionMicros)
            synchronized(lock) { samples.clear() }
            main { controller.seekTo(2_850_000); controller.play() }
            await("Fade seek stalled") { controller.state.value.positionMicros >= 3_200_000 }
            main { controller.pause() }
            val seekFade = rms(2_850_000, 2_950_000)
            android.util.Log.i("PreviewAudioClock", "seekFade=$seekFade full=$full")
            assertTrue("Seek restarted the fade envelope", seekFade < full * .4)
        } finally {
            main { if (created) controller.release() }
        }
    }
}
