package com.videoeditor.core.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Temporary diagnostic that characterises how [MediaMetadataRetriever] resolves a frame
 * at an exact keyframe timestamp on this device, for the exported Fade Through White
 * video whose content is already known to be correct.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Gpt6RetrieverDiagnosticTest {

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
            "gpt6-retriever/run-${android.os.SystemClock.elapsedRealtimeNanos()}"
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

    private fun sampledLuma(bitmap: Bitmap): Double {
        var sum = 0L
        var count = 0
        for (y in 50 until bitmap.height - 50 step 20) {
            for (x in 50 until bitmap.width - 50 step 20) {
                val px = bitmap.getPixel(x, y)
                sum += ((px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF)) / 3
                count++
            }
        }
        return sum.toDouble() / count
    }

    /** A cheap content fingerprint that identifies which decoded frame was returned. */
    private fun fingerprint(bitmap: Bitmap): String {
        val sb = StringBuilder()
        for (y in 0 until bitmap.height step 120) {
            for (x in 0 until bitmap.width step 120) {
                val px = bitmap.getPixel(x, y)
                sb.append(((px shr 16 and 0xFF) / 16).toString(16))
                sb.append(((px shr 8 and 0xFF) / 16).toString(16))
                sb.append(((px and 0xFF) / 16).toString(16))
            }
        }
        return sb.toString()
    }

    @Test
    fun diagnostic_retriever_keyframe_resolution() = runBlocking<Unit> {
        val base = projectCreator.create(
            "Gpt6Retriever",
            listOf(clip1Ref, clip2Ref),
            canvas = CanvasSettings(width = 1280, height = 720)
        )
        val project = base.copy(
            timeline = Timeline(
                videoClips = listOf(
                    Clip("c1", clip1Ref.id, sourceStartMicros = 1_000_000L, sourceEndMicros = 3_000_000L, timelineStartMicros = 0L),
                    Clip("c2", clip2Ref.id, sourceStartMicros = 1_000_000L, sourceEndMicros = 3_000_000L, timelineStartMicros = 2_000_000L)
                ),
                transitions = listOf(
                    Transition("t-fade-white", "c1", "c2", type = TransitionType.FadeThroughWhite, durationMicros = 1_000_000L)
                )
            )
        )
        val out = File(fixtureDir, "out.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                project,
                ExportRequest(project.id, out, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )

        // Timestamps under test: exact keyframe boundaries and their neighbours.
        val queries = listOf(
            500_000L, 999_000L, 1_000_000L, 1_001_000L, 1_500_000L,
            1_999_000L, 2_000_000L, 2_001_000L, 2_500_000L, 2_999_000L, 3_000_000L
        )

        val report = StringBuilder()
        report.append("device=").append(android.os.Build.MODEL).append('\n')

        for (option in listOf("CLOSEST", "CLOSEST_SYNC", "PREVIOUS_SYNC")) {
            val optionValue = when (option) {
                "CLOSEST" -> MediaMetadataRetriever.OPTION_CLOSEST
                "CLOSEST_SYNC" -> MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                else -> MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
            }
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(out.absolutePath)
            for (t in queries) {
                val bmp = retriever.getFrameAtTime(t, optionValue)
                if (bmp == null) {
                    report.append(option).append(" t=").append(t).append(" -> null\n")
                } else {
                    report.append(option).append(" t=").append(t)
                        .append(" luma=").append(sampledLuma(bmp))
                        .append(" fp=").append(fingerprint(bmp).hashCode())
                        .append('\n')
                    bmp.recycle()
                }
            }
            retriever.release()
        }

        // Cross-check: which frame does each query actually correspond to?
        // The same fingerprint must appear for two different queries only if the
        // retriever returned the same decoded frame for both.
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(out.absolutePath)
        for (t in queries) {
            val bmp = retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
            if (bmp != null) {
                report.append("identity t=").append(t)
                    .append(" fp=").append(fingerprint(bmp).hashCode())
                    .append(" luma=").append(sampledLuma(bmp))
                    .append('\n')
                bmp.recycle()
            }
        }
        retriever.release()

        val text = report.toString()
        android.util.Log.i("Gpt6Retriever", text)

        // Persist the report into the shared Downloads collection, which survives the
        // uninstall of the instrumented APK.
        try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "gpt6_retriever_${android.os.Build.MODEL.replace(' ', '_')}.txt")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (error: Exception) {
            android.util.Log.e("Gpt6Retriever", "evidence write failed: ${error.message}")
        }
    }
}
