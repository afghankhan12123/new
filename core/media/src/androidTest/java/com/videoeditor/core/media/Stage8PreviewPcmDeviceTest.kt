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
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Stage8PreviewPcmDeviceTest {
    @Test fun realPreviewPreservesPanAndAuthoredAutomation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dir = File(context.filesDir, "stage8-preview-${android.os.SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
        val video = File(dir, "video.mp4")
        context.assets.open("clip1_720p_6s.mp4").use { input -> video.outputStream().use(input::copyTo) }
        val audio = File(dir, "tone.m4a")
        instrumentation.context.assets.open("stage8/tone440_48k_mono.m4a").use { input -> audio.outputStream().use(input::copyTo) }
        val refs = listOf(video, audio).map { MediaMetadataExtractor().extract(context, Uri.fromFile(it)).getOrNull()!! }
        val base = ProjectCreator().create("Stage8 preview PCM", listOf(refs[0]))
        val project = base.copy(mediaRefs = refs, timeline = base.timeline.copy(
            videoClips = base.timeline.videoClips.map { it.copy(sourceEndMicros = 3_000_000, muted = true) },
            audioClips = listOf(AudioClip("tone", refs[1].id, sourceEndMicros = 3_000_000,
                balance = AudioBalance(-1f), volumeEnvelope = VolumeEnvelope(listOf(
                    VolumePoint("a", 0, 0f, VolumeInterpolation.Hold), VolumePoint("b", 1_500_000, -12f)))))))
        val energy = Array(2) { DoubleArray(2) }; val count = Array(2) { IntArray(2) }
        val lock = Any()
        val captured = java.io.ByteArrayOutputStream()
        val captureRecords = StringBuilder("presentationUs,initialBytes,consumedBytes,sampleRate,channels,pcmEncoding\n")
        val sink = object : ForwardingAudioSink(DefaultAudioSink.Builder(context).build()) {
            private var format = Format.Builder().build()
            override fun configure(inputFormat: Format, bufferSize: Int, outputChannels: IntArray?) {
                format = inputFormat; super.configure(inputFormat, bufferSize, outputChannels)
            }
            override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                val copy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                val initial = buffer.position()
                val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                val bytes = if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
                val channels = format.channelCount.coerceAtLeast(1)
                synchronized(lock) {
                    val consumed = buffer.position() - initial
                    val raw = copy.duplicate().apply { limit(initial + consumed) }
                    val data = ByteArray(consumed); raw.get(data); captured.write(data)
                    captureRecords.append("$presentationTimeUs,$initial,$consumed,${format.sampleRate},$channels,${format.pcmEncoding}\n")
                    repeat(consumed / (bytes * channels)) { frame ->
                        val time = presentationTimeUs + (initial / (bytes * channels) + frame) * 1_000_000L / format.sampleRate
                        val window = when(time) { in 500_000L..999_999L -> 0; in 2_000_000L..2_499_999L -> 1; else -> -1 }
                        repeat(channels) { channel ->
                            val sample = if (bytes == 4) copy.float.toDouble() * 32768 else copy.short.toDouble()
                            if (window >= 0) {
                                assertEquals("Preview collapsed stereo", 2, channels)
                                energy[window][channel] += sample * sample; count[window][channel]++
                            }
                        }
                    }
                }
                return handled
            }
        }
        lateinit var controller: CompositionPreviewController
        instrumentation.runOnMainSync {
            controller = CompositionPreviewController.Factory(context, CompositionBuilder()).create(sink)
            controller.setProject(project); controller.play()
        }
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (controller.state.value.positionMicros < 3_000_000 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertTrue("Preview stalled: ${controller.state.value}", controller.state.value.positionMicros >= 3_000_000)
            synchronized(lock) {
                File(dir,"preview-consumed.pcm").writeBytes(captured.toByteArray())
                File(dir,"preview-buffers.csv").writeText(captureRecords.toString())
                android.util.Log.i("Stage8Preview", "retained=${dir.absolutePath}")
                assertTrue(count.all { row -> row.all { it > 10000 } })
                val before = sqrt(energy[0][0] / count[0][0]); val after = sqrt(energy[1][0] / count[1][0])
                android.util.Log.i("Stage8Preview", "before=$before after=$after ratio=${after/before}")
                assertEquals(.2511886, after / before, .01)
                assertTrue(energy.all { it[1] < it[0] * .0001 })
            }
        } finally { instrumentation.runOnMainSync { controller.release() } }
    }
}
