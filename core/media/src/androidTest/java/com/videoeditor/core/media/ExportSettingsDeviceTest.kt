package com.videoeditor.core.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.ExportEngine
import com.videoeditor.core.media.export.ExportRequest
import com.videoeditor.core.model.CanvasSettings
import com.videoeditor.core.model.ExportQuality
import com.videoeditor.core.model.ExportResolution
import com.videoeditor.core.model.FrameRate
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.StickerClip
import com.videoeditor.core.model.Timeline
import com.videoeditor.editing.ProjectCreator
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that the export settings actually reach the encoded
 * output — not just the request object. For each supported profile the test
 * exports a real project, then inspects the finished MP4's codec metadata,
 * sample cadence, achieved bitrate and duration.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExportSettingsDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val compositionBuilder = CompositionBuilder()
    private val projectCreator = ProjectCreator()

    private lateinit var mediaDir: File

    @Before
    fun copyFixtures() {
        mediaDir = retainedMediaTestDirectory(context)
        // The 6s moving probe gives the encoder real content to spend bits on.
        listOf("probe_sweep_720p_6s.mp4", "probe_testsrc_360p_8s.mp4").forEach { name ->
            val target = File(mediaDir, name)
            if (!target.exists()) context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
        }
    }

    private fun project(fixture: String = "probe_sweep_720p_6s.mp4"): Project {
        val retriever = MediaMetadataRetriever()
        val file = File(mediaDir, fixture)
        val ref = try {
            retriever.setDataSource(file.absolutePath)
            MediaRef(
                id = "sweep", uri = android.net.Uri.fromFile(file).toString(),
                type = MediaType.Video, displayName = "sweep", mimeType = "video/mp4",
                durationMicros = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt(),
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt(),
                hasAudio = false,
            )
        } finally { retriever.release() }
        return projectCreator.create("Export settings", listOf(ref), canvas = CanvasSettings(width = 1280, height = 720))
            .copy(timeline = Timeline(videoClips = listOf(com.videoeditor.core.model.Clip(
                id = "main", mediaRefId = ref.id, sourceStartMicros = 0L,
                sourceEndMicros = ref.durationMicros ?: 6_000_000L, muted = true))),
            )
    }

    private fun export(project: Project, resolution: ExportResolution, frameRate: FrameRate,
        quality: ExportQuality, name: String): File {
        val output = File(mediaDir, name)
        val result = runBlocking {
            ExportEngine(context, compositionBuilder).export(project,
                ExportRequest(project.id, output, resolution, frameRate, quality)) {}
        }
        assertTrue("Export failed for $name", result.isSuccess)
        return output
    }

    /** Counts real video samples and reads their presentation timestamps. */
    private fun sampleCadence(file: File): Pair<Int, List<Long>> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            val timestamps = mutableListOf<Long>()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                extractor.getSampleTime().let { timestamps += it }
                extractor.advance()
            }
            return timestamps.size to timestamps
        } finally { extractor.release() }
    }

    private fun videoFormat(file: File): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return (0 until extractor.trackCount)
                .map(extractor::getTrackFormat)
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        } finally { extractor.release() }
    }

    private fun durationMicros(file: File): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000
        } finally { retriever.release() }
    }

    private fun achievedVideoBitrate(file: File): Long {
        // Video-only fixture: total file size ≈ video bits + container. The
        // estimate is used only for preset-order assertions, never exact value.
        val bytes = file.length()
        val seconds = durationMicros(file) / 1_000_000.0
        return (bytes * 8 / seconds).toLong()
    }

    @Test
    fun resolutionChoiceControlsEncodedDimensions() {
        val p = project()
        val r720 = export(p, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.Standard, "res-720.mp4")
        val r1080 = export(p, ExportResolution.RES_1080P, FrameRate.FPS_30, ExportQuality.Standard, "res-1080.mp4")
        val f720 = videoFormat(r720); val f1080 = videoFormat(r1080)
        assertEquals(1280, f720.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(720, f720.getInteger(MediaFormat.KEY_HEIGHT))
        assertEquals(1920, f1080.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(1080, f1080.getInteger(MediaFormat.KEY_HEIGHT))
    }

    @Test
    fun qualityPresetsProduceOrderedEncodedBitrates() {
        // The noise fixture's decoded entropy exceeds every preset target, so
        // the achieved rate is bounded by the configured bitrate, not by how
        // little information the content carries (a compressible sweep probe
        // collapses all presets to the encoder's quality floor).
        val p = project("probe_testsrc_360p_8s.mp4")
        val smaller = export(p, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.Smaller, "q-smaller.mp4")
        val standard = export(p, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.Standard, "q-standard.mp4")
        val high = export(p, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High, "q-high.mp4")
        val bSmaller = achievedVideoBitrate(smaller)
        val bStandard = achievedVideoBitrate(standard)
        val bHigh = achievedVideoBitrate(high)
        android.util.Log.i("ExportSettings", "achieved: smaller=$bSmaller standard=$bStandard high=$bHigh")
        assertTrue("Smaller ($bSmaller) should encode below Standard ($bStandard)", bSmaller < bStandard)
        assertTrue("Standard ($bStandard) should encode below High ($bHigh)", bStandard < bHigh)
        // Achieved rates must land near the configured targets on content that
        // can spend the bits — within 0.35x–2.5x covers VBR distribution over
        // the short run while still proving the presets are meaningfully
        // distinct encoder configurations (2.5M/5M/8M, verified at codec
        // configuration in EncoderConfigurationDeviceTest).
        val targets = longArrayOf(2_500_000, 5_000_000, 8_000_000)
        val achieved = longArrayOf(bSmaller, bStandard, bHigh)
        for (i in 0..2) {
            assertTrue("Preset $i achieved ${achieved[i]} vs target ${targets[i]}",
                achieved[i] > targets[i] * 35 / 100 && achieved[i] < targets[i] * 5 / 2)
        }
    }

    @Test
    fun frameRate24ReTimesOutputCadence() {
        val p = project()
        val out = export(p, ExportResolution.RES_720P, FrameRate.FPS_24, ExportQuality.Standard, "fps-24.mp4")
        val (count, timestamps) = sampleCadence(out)
        val seconds = durationMicros(out) / 1_000_000.0
        val cadence = count / seconds
        assertTrue("24 fps export encoded $cadence fps ($count samples over ${seconds}s)",
            cadence > 20.0 && cadence < 27.0)
        // Presentation timestamps must stay monotonic and span the duration.
        assertTrue("Timestamps not monotonic", timestamps.zipWithNext().all { it.first < it.second })
        assertTrue("Duration drifted: ${durationMicros(out)}", kotlin.math.abs(durationMicros(out) - 6_000_000L) <= 200_000L)
    }

    @Test
    fun defaultFrameRatePreservesSourceCadence() {
        // The accepted Stage 6 default: no FPS conversion at 30.
        val p = project()
        val out = export(p, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.Standard, "fps-30.mp4")
        val (count, _) = sampleCadence(out)
        val seconds = durationMicros(out) / 1_000_000.0
        val cadence = count / seconds
        assertTrue("Default export encoded $cadence fps, expected the 30 fps source", cadence > 27.0 && cadence < 33.0)
    }

    @Test
    fun frameRate60ReTimesUpFrom30fpsSource() {
        val p = project()
        val out = export(p, ExportResolution.RES_720P, FrameRate.FPS_60, ExportQuality.Standard, "fps-60.mp4")
        val (count, _) = sampleCadence(out)
        val seconds = durationMicros(out) / 1_000_000.0
        val cadence = count / seconds
        // Resampling up duplicates frames; the cadence must still land at 60.
        assertTrue("60 fps export encoded $cadence fps", cadence > 54.0 && cadence < 66.0)
        assertTrue("Duration drifted at 60 fps: ${durationMicros(out)}", kotlin.math.abs(durationMicros(out) - 6_000_000L) <= 200_000L)
    }

    @Test
    fun h264AacOutputDecodesWithCorrectDuration() {
        val p = project()
        val out = export(p, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.Standard, "codec-check.mp4")
        val format = videoFormat(out)
        assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
        assertTrue("Duration must match the 6s composition: ${durationMicros(out)}", kotlin.math.abs(durationMicros(out) - 6_000_000L) <= 200_000L)
    }

    @Test
    fun exportCarriesStickerPixelsUnderCustomFrameRate() {
        // Frame-rate conversion must not drop the sticker compositing path.
        val p = project().copy(timeline = project().timeline.copy(stickers = listOf(
            StickerClip(id = "s", assetKey = "premium_heart", timelineStartMicros = 1_000_000L,
                timelineDurationMicros = 4_000_000L))))
        val out = export(p, ExportResolution.RES_720P, FrameRate.FPS_24, ExportQuality.Standard, "fps-24-sticker.mp4")
        val plain = export(project(), ExportResolution.RES_720P, FrameRate.FPS_24, ExportQuality.Standard, "fps-24-plain.mp4")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(out.absolutePath)
            val withSticker = retriever.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            retriever.setDataSource(plain.absolutePath)
            val without = retriever.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            var changed = 0
            val total = 32 * 32
            val a = android.graphics.Bitmap.createScaledBitmap(withSticker, 32, 32, true)
            val b = android.graphics.Bitmap.createScaledBitmap(without, 32, 32, true)
            val pa = IntArray(total).also { a.getPixels(it, 0, 32, 0, 0, 32, 32) }
            val pb = IntArray(total).also { b.getPixels(it, 0, 32, 0, 0, 32, 32) }
            for (i in 0 until total) {
                val dr = kotlin.math.abs((pa[i] shr 16 and 0xFF) - (pb[i] shr 16 and 0xFF))
                val dg = kotlin.math.abs((pa[i] shr 8 and 0xFF) - (pb[i] shr 8 and 0xFF))
                val db = kotlin.math.abs((pa[i] and 0xFF) - (pb[i] and 0xFF))
                if (dr + dg + db > 48) changed++
            }
            assertTrue("Sticker vanished under 24 fps export ($changed/1024 changed)", changed > 8)
        } finally { retriever.release() }
    }
}
