package com.videoeditor.core.media

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.model.CanvasSettings
import com.videoeditor.core.model.FrameRate
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Timeline
import com.videoeditor.editing.ProjectCreator
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic: inspect the built composition's effect chains for a 24 fps export. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class FrameRateDiagnosticDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var mediaDir: File

    @Before fun fixture() {
        mediaDir = retainedMediaTestDirectory(context)
        val target = File(mediaDir, "probe_sweep_720p_6s.mp4")
        if (!target.exists()) context.assets.open("probe_sweep_720p_6s.mp4").use { i -> target.outputStream().use(i::copyTo) }
    }

    @Test fun inspectCompositionEffectsFor24Fps() {
        val file = File(mediaDir, "probe_sweep_720p_6s.mp4")
        val retriever = MediaMetadataRetriever()
        val ref = try {
            retriever.setDataSource(file.absolutePath)
            MediaRef("sweep", android.net.Uri.fromFile(file).toString(), MediaType.Video, "sweep",
                "video/mp4", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt(), hasAudio = false)
        } finally { retriever.release() }
        val project = ProjectCreator().create("diag", listOf(ref), canvas = CanvasSettings(width = 1280, height = 720))
            .copy(timeline = Timeline(videoClips = listOf(com.videoeditor.core.model.Clip(
                "main", "sweep", 0L, 6_000_000L, muted = true))))

        val composition = CompositionBuilder().buildForExport(project, 1280, 720, targetFrameRate = 24)
        val report = buildString {
            append("sequences=${composition.sequences.size}")
            composition.sequences.forEachIndexed { si, seq ->
                seq.editedMediaItems.forEachIndexed { ii, item ->
                    append("\nseq[$si] item[$ii] videoEffects=${item.effects.videoEffects.map { it.javaClass.simpleName }}")
                }
            }
            append("\ncompositionEffects=${composition.effects.videoEffects.map { it.javaClass.simpleName }}")
            append("\ncompositorSettings=${composition.videoCompositorSettings?.javaClass?.simpleName}")
        }
        android.util.Log.i("FrameRateDiag", report)
        println(report)
        // The composition-level list must carry the grid resampler; item-level
        // chains carry the force-transcode identity effect so the conversion
        // always encodes.
        assertTrue("No FrameRateResampleEffect anywhere: $report",
            report.contains("FrameRateResampleEffect"))
    }
}
