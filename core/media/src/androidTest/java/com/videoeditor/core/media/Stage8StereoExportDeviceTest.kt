package com.videoeditor.core.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.ExportEngine
import com.videoeditor.core.media.export.ExportRequest
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class Stage8StereoExportDeviceTest {
    @Test fun monoPanSurvivesCompositionMixerAndAacEncoder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val dir = File(context.filesDir, "stage8-stereo-${android.os.SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
        val video = File(dir, "video.mp4")
        context.assets.open("clip1_720p_6s.mp4").use { input -> video.outputStream().use(input::copyTo) }
        val audio = File(dir, "mono.m4a")
        assets.open("stage8/tone440_48k_mono.m4a").use { input -> audio.outputStream().use(input::copyTo) }
        val refs = listOf(video, audio).map { MediaMetadataExtractor().extract(context, Uri.fromFile(it)).getOrNull()!! }
        val base = ProjectCreator().create("Stage8 stereo qualification", listOf(refs[0]))
        for (pan in listOf(-1f, 1f)) {
            val project = base.copy(mediaRefs = refs, timeline = base.timeline.copy(
                videoClips = base.timeline.videoClips.map { it.copy(sourceEndMicros = 3_000_000, muted = true) },
                audioClips = listOf(AudioClip("panned", refs[1].id, sourceEndMicros = 3_000_000, balance = AudioBalance(pan), volumeEnvelope = VolumeEnvelope(listOf(
                    VolumePoint("a",0,0f,VolumeInterpolation.Hold),VolumePoint("b",1_500_000,-12f)))))))
            val output = File(dir, if (pan < 0) "left.mp4" else "right.mp4")
            val result = ExportEngine(context, CompositionBuilder()).export(project,
                ExportRequest(project.id, output, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.Standard)) {}
            assertTrue("Export failed: $result", result.isSuccess)
            android.util.Log.i("Stage8Stereo", "retained=${output.absolutePath}")
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                val format = formats.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                assertEquals("AAC mix collapsed to mono: $format", 2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            } finally { extractor.release() }
            val energy = DoubleArray(2); val counts = IntArray(2)
            val laterEnergy = DoubleArray(2); val laterCounts = IntArray(2)
            visitAudioSamples(output) { time, channel, sample ->
                if (time in 500_000L..1_499_999L) { energy[channel] += sample * sample; counts[channel]++ }
                if (time in 2_000_000L..2_499_999L) { laterEnergy[channel] += sample * sample; laterCounts[channel]++ }
            }
            assertTrue(counts.all { it > 40000 })
            val rms = energy.indices.map { sqrt(energy[it] / counts[it]) }
            val selected = if (pan < 0) 0 else 1
            assertTrue("Selected channel silent: $rms", rms[selected] > 2000)
            assertTrue("Opposite channel audible: $rms", rms[1-selected] < rms[selected] * .01)
            assertTrue(laterCounts.all { it > 10000 })
            val laterRms=sqrt(laterEnergy[selected]/laterCounts[selected])
            android.util.Log.i("Stage8Stereo", "pan=$pan before=${rms[selected]} after=$laterRms ratio=${laterRms/rms[selected]}")
            assertEquals("Encoded automation gain",.2511886,laterRms/rms[selected],.02)
        }
    }
}
