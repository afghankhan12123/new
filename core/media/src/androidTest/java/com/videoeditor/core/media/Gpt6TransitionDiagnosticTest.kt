package com.videoeditor.core.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.composition.TransitionProbe
import com.videoeditor.core.media.export.ExportEngine
import com.videoeditor.core.media.export.ExportRequest
import com.videoeditor.core.model.CanvasSettings
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.ExportQuality
import com.videoeditor.core.model.ExportResolution
import com.videoeditor.core.model.FrameRate
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.Timeline
import com.videoeditor.core.model.Transition
import com.videoeditor.core.model.TransitionType
import com.videoeditor.editing.ProjectCreator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Temporary diagnostic for the NE2211 Fade Through White export failure.
 *
 * It performs the same export as
 * `Stage9VisualEffectsDeviceTest#transitions_fade_through_white_and_wipe_render_in_export`,
 * but additionally:
 *  - enables the instrumentation probe in the compositor and transition effect,
 *  - copies the exported video into the device's external files directory so it can be
 *    pulled and analysed independently of the test oracle,
 *  - dumps the decoded luma of every exported frame.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Gpt6TransitionDiagnosticTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val extractor = MediaMetadataExtractor()
    private val projectCreator = ProjectCreator()
    private val compositionBuilder = CompositionBuilder()

    private lateinit var fixtureDir: File
    private lateinit var clip1Ref: MediaRef
    private lateinit var clip2Ref: MediaRef

    @Before
    fun setUp() = runBlocking {
        fixtureDir = File(
            context.getExternalFilesDir(null),
            "gpt6-diagnostic/run-${android.os.SystemClock.elapsedRealtimeNanos()}"
        ).apply { mkdirs() }

        fun copyAsset(name: String): File {
            val file = File(fixtureDir, name)
            context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            return file
        }

        val f1 = copyAsset("clip1_720p_6s.mp4")
        val f2 = copyAsset("clip2_480p_5s.mp4")
        clip1Ref = extractor.extract(context, Uri.fromFile(f1)).getOrNull()!!
        clip2Ref = extractor.extract(context, Uri.fromFile(f2)).getOrNull()!!
    }

    @After
    fun tearDown() {
        TransitionProbe.enabled = false
    }

    /**
     * Writes a text artifact into the shared Downloads collection, so it survives the
     * uninstall of the instrumented APK and can be pulled afterwards.
     */
    private fun writeEvidence(name: String, content: String): String? {
        return try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } catch (error: Exception) {
            android.util.Log.e("Gpt6Diag", "evidence write failed: ${error.message}")
            null
        }
    }

    /** Copies a file into the shared Downloads collection. */
    private fun writeEvidenceFile(name: String, file: File): String? {
        return try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { sink -> file.inputStream().use { it.copyTo(sink) } }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } catch (error: Exception) {
            android.util.Log.e("Gpt6Diag", "evidence file write failed: ${error.message}")
            null
        }
    }

    @Test
    fun diagnostic_fade_through_white_export_frames_and_probe() = runBlocking<Unit> {
        // The instrumented APK is uninstalled after the run, so the evidence is written
        // into the shared Downloads collection where it can be pulled afterwards.
        val probeFile = File(fixtureDir, "probe.txt")
        TransitionProbe.reset(probeFile)
        TransitionProbe.enabled = true

        val base = projectCreator.create(
            "Gpt6TransitionDiagnostic",
            listOf(clip1Ref, clip2Ref),
            canvas = CanvasSettings(width = 1280, height = 720)
        )
        val transition = Transition(
            id = "t-fade-white",
            outgoingClipId = "c1",
            incomingClipId = "c2",
            type = TransitionType.FadeThroughWhite,
            durationMicros = 1_000_000L
        )
        val project = base.copy(
            timeline = Timeline(
                videoClips = listOf(
                    Clip("c1", clip1Ref.id, sourceStartMicros = 1_000_000L, sourceEndMicros = 3_000_000L, timelineStartMicros = 0L),
                    Clip("c2", clip2Ref.id, sourceStartMicros = 1_000_000L, sourceEndMicros = 3_000_000L, timelineStartMicros = 2_000_000L)
                ),
                transitions = listOf(transition)
            )
        )

        val out = File(fixtureDir, "gpt6_fade_white_export.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                project,
                ExportRequest(project.id, out, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )

        // Decode every frame of the export and record its luma, so the result can be
        // compared against an independent expectation rather than a single sample.
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(out.absolutePath)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        android.util.Log.i("Gpt6Diag", "exportPath=${out.absolutePath} durationMs=$durationMs sizeBytes=${out.length()}")

        val sweep = StringBuilder()
        var t = 0L
        while (t <= durationMs) {
            val bmp = retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
            if (bmp != null) {
                var sum = 0L
                var count = 0
                for (y in 50 until bmp.height - 50 step 20) {
                    for (x in 50 until bmp.width - 50 step 20) {
                        val px = bmp.getPixel(x, y)
                        sum += ((px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF)) / 3
                        count++
                    }
                }
                sweep.append(t).append(' ').append(sum.toDouble() / count).append('\n')
                bmp.recycle()
            }
            t += 100
        }
        retriever.release()
        val framesUri = writeEvidence("gpt6_frames_${android.os.Build.MODEL.replace(' ', '_')}.txt", sweep.toString())
        val probeUri = writeEvidence("gpt6_probe_${android.os.Build.MODEL.replace(' ', '_')}.txt", probeFile.readText())
        // Copy the exported video out of the app sandbox so it can be analysed
        // independently of the test's own oracle.
        val videoUri = writeEvidenceFile("gpt6_fade_white_${android.os.Build.MODEL.replace(' ', '_')}.mp4", out)
        android.util.Log.i("Gpt6Diag", "frames=$framesUri probe=$probeUri video=$videoUri")

        assertTrue("diagnostic export must exist", out.exists() && out.length() > 0)
    }
}
