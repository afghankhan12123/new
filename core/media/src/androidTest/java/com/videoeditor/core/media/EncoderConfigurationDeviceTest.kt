package com.videoeditor.core.media

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.ExportEngine
import com.videoeditor.core.media.export.ExportRequest
import com.videoeditor.core.media.preview.PreviewPhaseTrace
import com.videoeditor.core.model.CanvasSettings
import com.videoeditor.core.model.ExportQuality
import com.videoeditor.core.model.ExportResolution
import com.videoeditor.core.model.ExportProfile
import com.videoeditor.core.model.FrameRate
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Timeline
import com.videoeditor.editing.ProjectCreator
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the encoder's ACTUAL configured bitrate for each quality preset,
 * captured at codec creation through the instrumentation-only diagnostic hook.
 * This is the honest proof that a preset reaches the encoder; achieved output
 * bitrate is measured separately against real content.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class EncoderConfigurationDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val compositionBuilder = CompositionBuilder()
    private val projectCreator = ProjectCreator()
    private lateinit var mediaDir: File

    @Before fun fixture() {
        mediaDir = retainedMediaTestDirectory(context)
        listOf("probe_sweep_720p_6s.mp4").forEach { name ->
            val target = File(mediaDir, name)
            if (!target.exists()) context.assets.open(name).use { i -> target.outputStream().use(i::copyTo) }
        }
    }

    private fun project(): Project {
        val file = File(mediaDir, "probe_sweep_720p_6s.mp4")
        val retriever = MediaMetadataRetriever()
        val ref = try {
            retriever.setDataSource(file.absolutePath)
            MediaRef("sweep", android.net.Uri.fromFile(file).toString(), MediaType.Video, "sweep",
                "video/mp4", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt(), hasAudio = false)
        } finally { retriever.release() }
        return projectCreator.create("cfg", listOf(ref), canvas = CanvasSettings(width = 1280, height = 720))
            .copy(timeline = Timeline(videoClips = listOf(com.videoeditor.core.model.Clip(
                "main", "sweep", 0L, 6_000_000L, muted = true))))
    }

    private fun captureEncoderBitrate(quality: ExportQuality): Pair<Int, Long> {
        val configured = AtomicInteger(-1)
        PreviewPhaseTrace.identityDiagnostic = true
        PreviewPhaseTrace.diagnosticVideoEncoder = java.util.function.Function { codec ->
            val format = codec.configurationFormat
            android.util.Log.i("EncoderConfig", "HOOK codec=${codec.name} bitrate=${format?.averageBitrate} frameRate=${format?.frameRate} size=${format?.width}x${format?.height}")
            if (format != null && format.averageBitrate > 0) configured.set(format.averageBitrate)
            codec
        }
        try {
            val output = File(mediaDir, "cfg-$quality.mp4")
            val result = runBlocking {
                ExportEngine(context, compositionBuilder).export(project(),
                    ExportRequest("cfg", output, ExportResolution.RES_720P, FrameRate.FPS_30, quality)) {}
            }
            assertTrue("Export failed for $quality", result.isSuccess)
            return configured.get() to output.length()
        } finally {
            PreviewPhaseTrace.setIdentityDiagnostic(false)
        }
    }

    @Test
    fun qualityPresetsReachEncoderConfiguration() {
        val (smallerBits, _) = captureEncoderBitrate(ExportQuality.Smaller)
        val (standardBits, _) = captureEncoderBitrate(ExportQuality.Standard)
        val (highBits, _) = captureEncoderBitrate(ExportQuality.High)
        val expectedSmaller = ExportProfile.videoBitrateBps(ExportResolution.RES_720P, ExportQuality.Smaller, FrameRate.FPS_30)
        val expectedStandard = ExportProfile.videoBitrateBps(ExportResolution.RES_720P, ExportQuality.Standard, FrameRate.FPS_30)
        val expectedHigh = ExportProfile.videoBitrateBps(ExportResolution.RES_720P, ExportQuality.High, FrameRate.FPS_30)
        android.util.Log.i("EncoderConfig", "configured: smaller=$smallerBits standard=$standardBits high=$highBits")
        assertEquals("Smaller preset did not reach the encoder", expectedSmaller, smallerBits)
        assertEquals("Standard preset did not reach the encoder", expectedStandard, standardBits)
        assertEquals("High preset did not reach the encoder", expectedHigh, highBits)
        assertTrue(smallerBits < standardBits && standardBits < highBits)
    }
}
