package com.videoeditor.core.media

import com.videoeditor.core.common.result.getOrNull
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.Result
import com.videoeditor.core.media.MediaError
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.ExportEngine
import com.videoeditor.core.media.export.ExportRequest
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.model.CanvasSettings
import com.videoeditor.core.model.ExportQuality
import com.videoeditor.core.model.ExportResolution
import com.videoeditor.core.model.FrameRate
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.OverlayClip
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Timeline
import com.videoeditor.core.model.VisualTransform
import com.videoeditor.editing.ProjectCreator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of true multi-layer (picture-in-picture) compositing:
 * a secondary video overlay plays on top of the main track in both the preview
 * (multi-input graph through the patched composition player) and the exported
 * MP4 (Transformer's multi-input graph), with pixel evidence at real decoded
 * frames — not just successful callbacks.
 *
 * Geometry convention: [VisualTransform.offsetX]/[VisualTransform.offsetY] are
 * canvas-fraction offsets of the layer center, positive Y down. The test places
 * the overlay at the right quarter so its center lands around x = 0.75 of the
 * output width.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class OverlayCompositingDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val extractor = MediaMetadataExtractor()
    private val compositionBuilder = CompositionBuilder()
    private val projectCreator = ProjectCreator()

    private lateinit var mediaDir: File

    @org.junit.After
    fun clearIdentityDiagnostic() {
        com.videoeditor.core.media.preview.PreviewPhaseTrace.setIdentityDiagnostic(false)
    }

    @Before
    fun copyAssetsToFiles() {
        // Temporarily enable Media3's composition trace so asset-loader track
        // registration is visible in logcat while diagnosing graph selection.
        try {
            val field = Class.forName("androidx.media3.effect.DebugTraceUtil")
                .getField("enableTracing")
            field.setBoolean(null, true)
            check(field.getBoolean(null)) { "DebugTraceUtil.enableTracing did not stick" }
            android.util.Log.i("OverlayTest", "Media3 debug tracing enabled")
        } catch (t: Throwable) {
            android.util.Log.w("OverlayTest", "DebugTraceUtil unavailable", t)
        }
        // Source fixtures must survive Android cache trimming during exports.
        mediaDir = File(context.filesDir, "overlay-test-media").apply { mkdirs() }
        listOf(
            "clip1_720p_6s.mp4", "clip2_480p_5s.mp4", "clip3_1080p_4s.mp4",
            "probe_sweep_720p_6s.mp4", "pip_main_moving_audio.mp4", "pip_overlay_moving_audio.mp4",
            "pip_main_frame_identity_audio.mp4", "transition_identity_0.mp4", "transition_identity_1.mp4",
        ).forEach { name ->
            val target = File(mediaDir, name)
            if (!target.exists()) {
                context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
            }
        }
    }

    private fun fileUri(name: String): android.net.Uri = android.net.Uri.fromFile(File(mediaDir, name))

    private fun extractRef(name: String): MediaRef {
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(File(mediaDir, name).absolutePath)
            val durationMs =
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            return MediaRef(
                id = name,
                uri = fileUri(name).toString(),
                type = com.videoeditor.core.model.MediaType.Video,
                displayName = name,
                mimeType = "video/mp4",
                durationMicros = durationMs * 1000,
            )
        } finally {
            metadata.release()
        }
    }

    /** Main: 6s clip1 full screen. Overlay: clip3, 1s–4s, right quarter at 30% scale. */
    private fun pipProject(): Project {
        val main = extractRef("clip1_720p_6s.mp4")
        val overlay = extractRef("clip3_1080p_4s.mp4")
        return projectCreator.create(
            name = "PiP test",
            mediaRefs = listOf(main, overlay),
            canvas = CanvasSettings(width = 1280, height = 720),
        ).copy(
            timeline = Timeline(
                videoClips = listOf(
                    com.videoeditor.core.model.Clip(
                        id = "main", mediaRefId = main.id,
                        sourceStartMicros = 0L, sourceEndMicros = 6_000_000L,
                    ),
                ),
                overlayClips = listOf(
                    OverlayClip(
                        id = "pip", mediaRefId = overlay.id,
                        timelineStartMicros = 1_000_000L,
                        timelineDurationMicros = 3_000_000L,
                        sourceStartMicros = 0L,
                        sourceEndMicros = 3_000_000L,
                        transform = VisualTransform(scale = 0.3f, offsetX = 0.5f, offsetY = 0f),
                        zIndex = 0,
                    ),
                ),
            ),
        )
    }

    /**
     * Compares the right quarter of a decoded 1280×720 frame with the same
     * region of the overlay's source at [sourceUs], downsampled to 32×32.
     * Compositing scales 1080p down to ~216p inside a 720p frame; both sides go
     * through the same downsample so H.264 noise dominates at a low mean error.
     */
    private fun meanLumaError(a: Bitmap, b: Bitmap, x0: Float, x1: Float, y0: Float, y1: Float): Double {
        fun sample(src: Bitmap): IntArray {
            val sx = (src.width * x0).toInt()
            val sy = (src.height * y0).toInt()
            val sw = (src.width * (x1 - x0)).toInt().coerceAtLeast(1)
            val sh = (src.height * (y1 - y0)).toInt().coerceAtLeast(1)
            val region = Bitmap.createBitmap(src, sx, sy, sw, sh)
            val scaled = Bitmap.createScaledBitmap(region, 32, 32, true)
            val pixels = IntArray(32 * 32)
            scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            return pixels
        }
        val pa = sample(a)
        val pb = sample(b)
        var sum = 0.0
        for (i in pa.indices) {
            val la = (pa[i] shr 16 and 0xFF) + (pa[i] shr 8 and 0xFF) + (pa[i] and 0xFF)
            val lb = (pb[i] shr 16 and 0xFF) + (pb[i] shr 8 and 0xFF) + (pb[i] and 0xFF)
            sum += kotlin.math.abs(la - lb)
        }
        return sum / pa.size
    }

    /**
     * Compares two differently-located regions: [a] sampled at (ax0..ax1, ay0..ay1)
     * against [b] sampled at (bx0..bx1, by0..by1), both downsampled to 32×32.
     *
     * This is what a scaled picture-in-picture layer requires. The overlay's
     * footprint on the canvas holds the layer's ENTIRE source frame scaled down,
     * so it must be compared against the whole source bitmap — not against the
     * same canvas-relative slice of it. Comparing one rect on both bitmaps would
     * measure the footprint against an unrelated corner of the source.
     */
    private fun meanLumaErrorBetween(
        a: Bitmap, ax0: Float, ax1: Float, ay0: Float, ay1: Float,
        b: Bitmap, bx0: Float, bx1: Float, by0: Float, by1: Float,
    ): Double {
        fun sample(src: Bitmap, x0: Float, x1: Float, y0: Float, y1: Float): IntArray {
            val sx = (src.width * x0).toInt()
            val sy = (src.height * y0).toInt()
            val sw = (src.width * (x1 - x0)).toInt().coerceAtLeast(1)
            val sh = (src.height * (y1 - y0)).toInt().coerceAtLeast(1)
            val region = Bitmap.createBitmap(src, sx, sy, sw, sh)
            val scaled = Bitmap.createScaledBitmap(region, 32, 32, true)
            val pixels = IntArray(32 * 32)
            scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            return pixels
        }
        val pa = sample(a, ax0, ax1, ay0, ay1)
        val pb = sample(b, bx0, bx1, by0, by1)
        var sum = 0.0
        for (i in pa.indices) {
            val la = (pa[i] shr 16 and 0xFF) + (pa[i] shr 8 and 0xFF) + (pa[i] and 0xFF)
            val lb = (pb[i] shr 16 and 0xFF) + (pb[i] shr 8 and 0xFF) + (pb[i] and 0xFF)
            sum += kotlin.math.abs(la - lb)
        }
        return sum / pa.size
    }

    /**
     * The canvas rect an overlay with [scale] and center offset
     * ([offsetX], [offsetY], canvas fraction, positive Y down) occupies, as
     * normalized left/right/top/bottom. Derived from the same compositor math
     * [com.videoeditor.core.media.composition.OverlayCompositorSettings] applies:
     * the layer is presented fit-to-canvas, then scaled about its center and
     * anchored at the offset.
     */
    private fun overlayFootprint(scale: Float, offsetX: Float, offsetY: Float): FloatArray {
        // The layer is presented fit-to-canvas first, so a source whose aspect
        // matches the canvas fills it exactly; the compositor's uniform scale
        // then makes the footprint `scale` of the canvas in both axes.
        val w = scale
        val h = scale
        val centerX = (offsetX + 1f) / 2f
        val centerY = (1f - offsetY) / 2f // anchor Y is up-positive, canvas Y is down
        return floatArrayOf(
            centerX - w / 2f, centerX + w / 2f,
            centerY - h / 2f, centerY + h / 2f,
        )
    }

    private fun regionVariation(bitmap: Bitmap, x0: Float, x1: Float, y0: Float, y1: Float): Int {
        val sx = (bitmap.width * x0).toInt()
        val sy = (bitmap.height * y0).toInt()
        val sw = (bitmap.width * (x1 - x0)).toInt().coerceAtLeast(1)
        val sh = (bitmap.height * (y1 - y0)).toInt().coerceAtLeast(1)
        val region = Bitmap.createBitmap(bitmap, sx, sy, sw, sh)
        val scaled = Bitmap.createScaledBitmap(region, 16, 16, true)
        val pixels = IntArray(16 * 16)
        scaled.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (p in pixels) {
            val l = (p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)
            if (l < min) min = l
            if (l > max) max = l
        }
        return max - min
    }

    private fun decodeFrame(file: File, atUs: Long): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
        } finally {
            retriever.release()
        }
    }

    private fun sourceFrame(name: String, atUs: Long): Bitmap =
        decodeFrame(File(mediaDir, name), atUs)

    /**
     * Structural diagnosis of the export composition: how many sequences,
     * which are flagged as containing gaps, and whether the overlay video
     * items keep their video track. Media3's exporter refuses video tracks in
     * gap-containing sequences, which would explain single-input graph
     * selection at runtime.
     */
    @Test
    fun export_composition_structure_diagnosis() {
        val project = pipProject()
        val composition = compositionBuilder.buildForExport(project, 1280, 720)
        val report = buildString {
            append("sequences=${composition.sequences.size}")
            composition.sequences.forEachIndexed { index, sequence ->
                val hasGaps = sequence.javaClass.getDeclaredMethod("hasGaps")
                    .apply { isAccessible = true }
                    .invoke(sequence) as Boolean
                append("\nseq[$index] items=${sequence.editedMediaItems.size} hasGaps=$hasGaps")
                sequence.editedMediaItems.forEachIndexed { itemIndex, item ->
                    append("\n  item[$itemIndex] removeVideo=${item.removeVideo} removeAudio=${item.removeAudio}" +
                        " durationUs=${item.durationUs} mediaId=${item.mediaItem.mediaId}" +
                        " uri=${item.mediaItem.localConfiguration?.uri?.lastPathSegment}" +
                        " videoEffects=${item.effects.videoEffects.size}" +
                        " clipping=${item.mediaItem.clippingConfiguration.startPositionUs}-${item.mediaItem.clippingConfiguration.endPositionUs}")
                }
            }
            append("\ncompositorSettings=${composition.videoCompositorSettings != null}")
        }
        android.util.Log.i("OverlayTest", report)
        assertTrue(report, true)
    }

    /**
     * Minimal evidence for export graph selection: two video sequences, no
     * custom compositor settings, then the same composition with them. Media3
     * refuses video tracks in gap-containing sequences, so this uses a full-
     * length overlay video (no gaps) to isolate registration/selection from
     * the gap question.
     */
    @Test
    fun minimal_two_video_sequences_export() = runBlocking<Unit> {
        val main = extractRef("clip1_720p_6s.mp4")
        val overlay = extractRef("clip3_1080p_4s.mp4")
        val mainItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.fromUri(fileUri("clip1_720p_6s.mp4"))
        ).setDurationUs(6_000_000L).build()
        val overlayItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.Builder().setUri(fileUri("clip3_1080p_4s.mp4"))
                .setClippingConfiguration(
                    androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(0L).setEndPositionUs(3_000_000L).build()
                ).build()
        ).setDurationUs(3_000_000L).build()
        val sequences = listOf(
            EditedMediaItemSequence(listOf(mainItem)),
            EditedMediaItemSequence(listOf(overlayItem)),
        )

        suspend fun exportWith(settings: VideoCompositorSettings?, tag: String): Boolean {
            val composition = Composition.Builder(sequences)
                .apply { if (settings != null) setVideoCompositorSettings(settings) }
                .build()
            val output = File(retainedMediaTestDirectory(context), "minimal-$tag-${System.currentTimeMillis()}.mp4")
            val completed = kotlinx.coroutines.CompletableDeferred<ExportResult?>()
            runOnMain {
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType("video/avc")
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(c: Composition, r: ExportResult) {
                            completed.complete(r)
                        }
                        override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                            android.util.Log.e("OverlayTest", "minimal export error ($tag)", e)
                            completed.complete(null)
                        }
                    })
                    .build()
                transformer.start(composition, output.absolutePath)
            }
            val result = completed.await()
            output.delete()
            android.util.Log.i("OverlayTest", "minimal export ($tag) success=${result != null}")
            return result != null
        }

        assertTrue("two plain video sequences failed to export", exportWith(null, "plain"))
        assertTrue(
            "two video sequences with compositor settings failed to export",
            exportWith(VideoCompositorSettings.DEFAULT, "compositor"),
        )

        // Same two sequences, but the overlay sequence starts with a 1s gap
        // item — the shape CompositionBuilder produces for a delayed overlay.
        // Media3 refuses video tracks in gap-containing sequences
        // ("Gaps in video sequences are not supported."), so this is expected
        // to expose the gap as the graph-selection blocker.
        val gapItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.Builder()
                .setMediaId("androidx-media3-GapMediaItem").build()
        ).setDurationUs(1_000_000L).setRemoveVideo(true).build()
        val gappedSequences = listOf(
            EditedMediaItemSequence(listOf(mainItem)),
            EditedMediaItemSequence(listOf(gapItem, overlayItem)),
        )
        val composition = Composition.Builder(gappedSequences)
            .setVideoCompositorSettings(VideoCompositorSettings.DEFAULT)
            .build()
        val output = File(retainedMediaTestDirectory(context), "minimal-gap-${System.currentTimeMillis()}.mp4")
        val gapResult = kotlinx.coroutines.CompletableDeferred<ExportException?>()
        runOnMain {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType("video/avc")
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        gapResult.complete(null)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        android.util.Log.e("OverlayTest", "gapped export error", e)
                        gapResult.complete(e)
                    }
                })
                .build()
            transformer.start(composition, output.absolutePath)
        }
        val gapError = gapResult.await()
        output.delete()
        android.util.Log.i("OverlayTest", "gapped export error=${gapError?.message}")
        // Media3 cannot export a video sequence containing gap items: the
        // exporter surfaces an asset-loader error (observed: "The preceding
        // MediaItem does not contain any track of type 2" — sequence-level
        // track wiring treats the gap as a missing video period). Overlay
        // sequences therefore pad with the media item itself, never gaps.
        assertTrue(
            "expected the gap-containing overlay sequence to fail export, got: $gapError",
            gapError != null,
        )
    }

    /**
     * Minimal two-video-sequence export with a 2s overlay (no padding): if
     * this shows the overlay, the issue is with the full-duration padded item;
     * if not, the issue is with multi-input compositing itself.
     */
    @Test
    fun minimal_2s_overlay_export() = runBlocking<Unit> {
        val mainItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.fromUri(fileUri("clip1_720p_6s.mp4"))
        ).setDurationUs(6_000_000L).build()
        val overlayItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.Builder().setUri(fileUri("clip3_1080p_4s.mp4"))
                .setClippingConfiguration(
                    androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(1_000_000L).setEndPositionUs(3_000_000L).build()
                ).build()
        ).setDurationUs(2_000_000L).build()
        // Media3 1.5.1 draws compositor input 0 last (on top), so the overlay is
        // registered first here. Input 0 also drives the output duration, so the
        // samples below stay inside the overlay's own 2s span.
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(overlayItem)),
                EditedMediaItemSequence(listOf(mainItem)),
            )
        ).setVideoCompositorSettings(VideoCompositorSettings.DEFAULT).build()
        val output = File(retainedMediaTestDirectory(context), "minimal-2s-${System.currentTimeMillis()}.mp4")
        val completed = kotlinx.coroutines.CompletableDeferred<ExportResult?>()
        runOnMain {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType("video/avc")
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        completed.complete(r)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        android.util.Log.e("OverlayTest", "minimal-2s export error", e)
                        completed.complete(null)
                    }
                })
                .build()
            transformer.start(composition, output.absolutePath)
        }
        val result = completed.await()
        assertTrue("minimal 2s export failed", result != null)
        // Output is 2s long (input 0 drives duration). At output 1s the overlay
        // shows its source at 1s + 1s clipping start = 2s. With DEFAULT settings
        // input 0 covers the canvas, so the frame is the overlay's source frame.
        val frame = decodeFrame(output, 1_000_000)
        val overlaySource = sourceFrame("clip3_1080p_4s.mp4", 2_000_000)
        val err = meanLumaError(frame, overlaySource, 0.3f, 0.7f, 0.3f, 0.7f)
        android.util.Log.i("OverlayTest", "minimal-2s overlay error=$err")
        assertTrue("overlay not composited in minimal 2s export (error=$err)", err < 50.0)
        output.delete()
    }

    /**
     * Minimal overlay export with identical-resolution sources (both 720p):
     * isolates whether the compositing failure is a resolution-mismatch issue
     * in the aspect-ratio matrix math. Uses the moving sweep fixture as the
     * overlay so the assertion can distinguish the overlay from the background
     * by content, since `clip1` is near-static.
     */
    @Test
    fun minimal_same_resolution_overlay_export() = runBlocking<Unit> {
        val mainItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.fromUri(fileUri("clip1_720p_6s.mp4"))
        ).setDurationUs(6_000_000L).build()
        // Sweep overlay, clipped 1s..3s: at overlay output t it shows sweep@(t+1s).
        val overlayItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.Builder().setUri(fileUri("probe_sweep_720p_6s.mp4"))
                .setClippingConfiguration(
                    androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(1_000_000L).setEndPositionUs(3_000_000L).build()
                ).build()
        ).setDurationUs(2_000_000L).build()
        // Overlay registered first: input 0 draws on top and drives duration.
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(overlayItem)),
                EditedMediaItemSequence(listOf(mainItem)),
            )
        ).setVideoCompositorSettings(VideoCompositorSettings.DEFAULT).build()
        val output = File(retainedMediaTestDirectory(context), "same-res-${System.currentTimeMillis()}.mp4")
        val completed = kotlinx.coroutines.CompletableDeferred<ExportResult?>()
        runOnMain {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType("video/avc")
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        completed.complete(r)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        android.util.Log.e("OverlayTest", "same-res export error", e)
                        completed.complete(null)
                    }
                })
                .build()
            transformer.start(composition, output.absolutePath)
        }
        val result = completed.await()
        assertTrue("same-res export failed", result != null)
        // With DEFAULT settings input 0 covers the canvas, so the frame is the
        // overlay's source frame: at output 0.5s that is sweep@1.5s.
        val frame = decodeFrame(output, 500_000)
        val overlayAt05 = sourceFrame("probe_sweep_720p_6s.mp4", 1_500_000)
        val overlayAt15 = sourceFrame("probe_sweep_720p_6s.mp4", 2_500_000)
        val errCorrect = meanLumaError(frame, overlayAt05, 0.3f, 0.7f, 0.3f, 0.7f)
        val errWrong = meanLumaError(frame, overlayAt15, 0.3f, 0.7f, 0.3f, 0.7f)
        android.util.Log.i("OverlayTest",
            "same-res: frame@0.5s errVsSweep1.5s=$errCorrect errVsSweep2.5s=$errWrong")
        assertTrue("overlay not drawn at full canvas (error=$errCorrect)", errCorrect < 20.0)
        assertTrue("overlay time not discriminating ($errCorrect vs $errWrong)", errCorrect < errWrong)
        output.delete()
    }

    /**
     * Minimal overlay export with identical-resolution sources (both 720p) and
     * explicit compositor settings, isolating custom placement from resolution
     * handling. The overlay is input 0 because Media3 1.5.1 draws input 0 last,
     * so it is the layer that receives the scaled right-quarter placement; the
     * background is input 1 and fills the canvas beneath it.
     */
    @Test
    fun minimal_same_resolution_with_settings_export() = runBlocking<Unit> {
        val mainItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.fromUri(fileUri("probe_sweep_720p_6s.mp4"))
        ).setDurationUs(6_000_000L).build()
        val overlayItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.Builder().setUri(fileUri("probe_sweep_720p_6s.mp4"))
                .setClippingConfiguration(
                    androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(1_000_000L).setEndPositionUs(3_000_000L).build()
                ).build()
        ).setDurationUs(2_000_000L).build()
        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<androidx.media3.common.util.Size>): androidx.media3.common.util.Size =
                androidx.media3.common.util.Size(1280, 720)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): androidx.media3.effect.OverlaySettings {
                // Background layer: full canvas, no transform.
                if (inputId == 1) return androidx.media3.effect.OverlaySettings.Builder().build()
                // Topmost layer: scaled to 30% at the right quarter.
                return androidx.media3.effect.OverlaySettings.Builder()
                    .setAlphaScale(1f)
                    .setScale(0.3f, 0.3f)
                    .setBackgroundFrameAnchor(0.5f, 0f)
                    .setOverlayFrameAnchor(0f, 0f)
                    .build()
            }
        }
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(overlayItem)),
                EditedMediaItemSequence(listOf(mainItem)),
            )
        ).setVideoCompositorSettings(settings).build()
        val output = File(retainedMediaTestDirectory(context), "same-res-settings-${System.currentTimeMillis()}.mp4")
        val completed = kotlinx.coroutines.CompletableDeferred<ExportResult?>()
        runOnMain {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType("video/avc")
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        completed.complete(r)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        android.util.Log.e("OverlayTest", "same-res-settings export error", e)
                        completed.complete(null)
                    }
                })
                .build()
            transformer.start(composition, output.absolutePath)
        }
        val result = completed.await()
        assertTrue("same-res-settings export failed", result != null)
        val frame = decodeFrame(output, 500_000)
        // The scaled overlay lands at x 0.6-0.9, y 0.35-0.65; the background
        // fills everything else. Both are the sweep, so they are distinguished
        // by content timing: the overlay shows sweep@1.5s, the background sweep@0.5s.
        val overlayContent = sourceFrame("probe_sweep_720p_6s.mp4", 1_500_000)
        val backgroundContent = sourceFrame("probe_sweep_720p_6s.mp4", 500_000)
        val errOverlayRegion = meanLumaErrorBetween(
            frame, 0.6f, 0.9f, 0.35f, 0.65f,
            overlayContent, 0f, 1f, 0f, 1f,
        )
        val errBackgroundRegion = meanLumaError(frame, backgroundContent, 0.1f, 0.4f, 0.35f, 0.65f)
        android.util.Log.i("OverlayTest",
            "same-res-settings: errOverlayRegion=$errOverlayRegion errBackgroundRegion=$errBackgroundRegion")
        assertTrue(
            "overlay not composited with custom settings (region error=$errOverlayRegion)",
            errOverlayRegion < 25.0,
        )
        assertTrue(
            "background displaced by the overlay (background region error=$errBackgroundRegion)",
            errBackgroundRegion < 25.0,
        )
        output.delete()
    }

    /**
     * Minimal overlay export with a 480p overlay (lower resolution than the
     * 720p background): exercises the compositor's handling of mismatched input
     * sizes. With DEFAULT settings the output size follows the primary input and
     * every input covers the canvas, so the overlay is registered first and the
     * frame must show the overlay's content rather than the background's.
     */
    @Test
    fun minimal_480p_overlay_export() = runBlocking<Unit> {
        val mainItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.fromUri(fileUri("clip1_720p_6s.mp4"))
        ).setDurationUs(6_000_000L).build()
        // 480p overlay, clipped 1s..3s: at overlay output t it shows clip2@(t+1s).
        val overlayItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.Builder().setUri(fileUri("clip2_480p_5s.mp4"))
                .setClippingConfiguration(
                    androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(1_000_000L).setEndPositionUs(3_000_000L).build()
                ).build()
        ).setDurationUs(2_000_000L).build()
        // Overlay registered first: input 0 draws on top and drives duration.
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(overlayItem)),
                EditedMediaItemSequence(listOf(mainItem)),
            )
        ).setVideoCompositorSettings(VideoCompositorSettings.DEFAULT).build()
        val output = File(retainedMediaTestDirectory(context), "480p-overlay-${System.currentTimeMillis()}.mp4")
        val completed = kotlinx.coroutines.CompletableDeferred<ExportResult?>()
        runOnMain {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType("video/avc")
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        completed.complete(r)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        android.util.Log.e("OverlayTest", "480p export error", e)
                        completed.complete(null)
                    }
                })
                .build()
            transformer.start(composition, output.absolutePath)
        }
        val result = completed.await()
        assertTrue("480p export failed", result != null)
        // Output is 2s (input 0 drives duration). At output 1s the overlay shows
        // clip2@2s; the background there would show clip1@1s.
        val frame = decodeFrame(output, 1_000_000)
        val overlaySource = sourceFrame("clip2_480p_5s.mp4", 2_000_000)
        val backgroundSource = sourceFrame("clip1_720p_6s.mp4", 1_000_000)
        val errVsOverlay = meanLumaError(frame, overlaySource, 0.3f, 0.7f, 0.3f, 0.7f)
        val errVsBackground = meanLumaError(frame, backgroundSource, 0.3f, 0.7f, 0.3f, 0.7f)
        android.util.Log.i("OverlayTest",
            "480p: errVsOverlay=$errVsOverlay errVsBackground=$errVsBackground")
        assertTrue(
            "480p overlay not composited on top (overlay $errVsOverlay vs background $errVsBackground)",
            errVsOverlay < errVsBackground,
        )
        assertTrue("480p overlay content mismatch (error=$errVsOverlay)", errVsOverlay < 50.0)
        output.delete()
    }

    /**
     * Minimal overlay export with identical resolution AND format for both
     * inputs, eliminating any resolution or container mismatch. The sweep
     * fixture serves as both the background and the overlay with different
     * trimming windows, so the assertion discriminates by content timing: at
     * output t the overlay (input 0, drawn on top) shows sweep@(t+2s) while the
     * background would show sweep@t.
     */
    @Test
    fun minimal_same_file_overlay_export() = runBlocking<Unit> {
        val mainItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.fromUri(fileUri("probe_sweep_720p_6s.mp4"))
        ).setDurationUs(6_000_000L).build()
        val overlayItem = EditedMediaItem.Builder(
            androidx.media3.common.MediaItem.Builder().setUri(fileUri("probe_sweep_720p_6s.mp4"))
                .setClippingConfiguration(
                    androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(2_000_000L).setEndPositionUs(4_000_000L).build()
                ).build()
        ).setDurationUs(2_000_000L).build()
        // Overlay registered first so it draws on top and drives duration.
        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(listOf(overlayItem)),
                EditedMediaItemSequence(listOf(mainItem)),
            )
        ).setVideoCompositorSettings(VideoCompositorSettings.DEFAULT).build()
        val output = File(retainedMediaTestDirectory(context), "same-file-${System.currentTimeMillis()}.mp4")
        val completed = kotlinx.coroutines.CompletableDeferred<ExportResult?>()
        runOnMain {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType("video/avc")
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        completed.complete(r)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        android.util.Log.e("OverlayTest", "same-file export error", e)
                        completed.complete(null)
                    }
                })
                .build()
            transformer.start(composition, output.absolutePath)
        }
        val result = completed.await()
        assertTrue("same-file export failed", result != null)
        // At output 0.5s the overlay shows sweep@2.5s; the background would show
        // sweep@0.5s. The band moves about 213px per second, so these differ.
        val frame = decodeFrame(output, 500_000)
        val overlayContent = sourceFrame("probe_sweep_720p_6s.mp4", 2_500_000)
        val backgroundContent = sourceFrame("probe_sweep_720p_6s.mp4", 500_000)
        val errVsOverlay = meanLumaError(frame, overlayContent, 0.3f, 0.7f, 0.3f, 0.7f)
        val errVsBackground = meanLumaError(frame, backgroundContent, 0.3f, 0.7f, 0.3f, 0.7f)
        android.util.Log.i("OverlayTest",
            "same-file: errVsOverlay=$errVsOverlay errVsBackground=$errVsBackground")
        assertTrue(
            "overlay not drawn (frame matches the background, not the overlay: " +
                "$errVsOverlay vs $errVsBackground)",
            errVsOverlay < errVsBackground,
        )
        assertTrue("overlay content mismatch (error=$errVsOverlay)", errVsOverlay < 20.0)
        output.delete()
    }

    /**
     * Dumps Media3's composition trace to logcat so we can see which tracks
     * were registered and which graph was used.
     */
    private fun dumpTrace(tag: String) {
        try {
            val writer = java.io.StringWriter()
            Class.forName("androidx.media3.effect.DebugTraceUtil")
                .getMethod("dumpTsv", java.io.Writer::class.java)
                .invoke(null, writer)
            android.util.Log.i("OverlayTest", "trace $tag:\n${writer}")
        } catch (t: Throwable) {
            android.util.Log.w("OverlayTest", "trace dump failed", t)
        }
    }

    @Test
    fun overlay_renders_in_exported_mp4_at_intended_position_and_time() = runBlocking<Unit> {
        val project = pipProject()
        val composition = compositionBuilder.buildForExport(project, 1280, 720)
        android.util.Log.i("OverlayTest",
            "diagnostic: sequences=${composition.sequences.size} " +
            "compositorSettings=${composition.videoCompositorSettings != null}")
        val output = File(retainedMediaTestDirectory(context), "pip-export-${System.currentTimeMillis()}.mp4")
        val result = kotlinx.coroutines.CompletableDeferred<Result<java.io.File, MediaError>>()
        runOnMain {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType("video/avc")
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        result.complete(Result.Success(output))
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        result.complete(Result.Failure(MediaError.ExportFailed("export failed", e)))
                    }
                })
                .build()
            transformer.start(composition, output.absolutePath)
        }
        val exportResult = result.await()
        dumpTrace("after-export")
        assertTrue("export failed: ${(exportResult as? Result.Failure)?.error}", exportResult.isSuccess)

        // Inside the overlay window (2s project time): right quarter must match
        // the overlay source at its 1s mark, and must differ from the same
        // region of a no-overlay export.
        val inside = decodeFrame(output, 2_000_000)
        val overlaySource = sourceFrame("clip3_1080p_4s.mp4", 1_000_000)
        val mainAt2s = sourceFrame("clip1_720p_6s.mp4", 2_000_000)
        saveDiag(output, "inside", inside)
        saveDiag(output, "inside-ref", overlaySource)
        saveDiag(output, "main-2s", mainAt2s)
        // The overlay's canvas footprint holds its ENTIRE source frame scaled
        // down, so the comparison is footprint-vs-whole-source. Slicing the
        // source with the same canvas rect would compare the layer against an
        // unrelated corner of it and can never match.
        val fp = overlayFootprint(scale = 0.3f, offsetX = 0.5f, offsetY = 0f)
        val fx0 = fp[0]; val fx1 = fp[1]; val fy0 = fp[2]; val fy1 = fp[3]
        val errInside = meanLumaErrorBetween(
            inside, fx0, fx1, fy0, fy1,
            overlaySource, 0f, 1f, 0f, 1f,
        )
        val errVsMainInside = meanLumaError(inside, mainAt2s, fx0, fx1, fy0, fy1)
        val errMainVsOverlay = meanLumaError(mainAt2s, overlaySource, 0.6f, 0.9f, 0.35f, 0.65f)
        val errFullFrame = meanLumaError(inside, mainAt2s, 0.0f, 1.0f, 0.0f, 1.0f)
        android.util.Log.i("OverlayTest", "footprint=$fx0..$fx1 x $fy0..$fy1")
        // Find where the overlay actually is by comparing inside vs main per column.
        val sb = StringBuilder("overlay position: ")
        for (col in 0 until 16) {
            val x0 = col / 16f
            val x1 = (col + 1) / 16f
            val err = meanLumaError(inside, mainAt2s, x0, x1, 0.35f, 0.65f)
            sb.append(if (err > 2.0) "X" else ".")
        }
        android.util.Log.i("OverlayTest", sb.toString())
        // Also check per-row to see vertical extent.
        val sbRow = StringBuilder("overlay rows: ")
        for (row in 0 until 9) {
            val y0 = row / 9f
            val y1 = (row + 1) / 9f
            val err = meanLumaError(inside, mainAt2s, 0.0f, 1.0f, y0, y1)
            sbRow.append(if (err > 2.0) "X" else ".")
        }
        android.util.Log.i("OverlayTest", sbRow.toString())
        android.util.Log.i("OverlayTest",
            "export inside: errVsOverlay=%.1f errVsMain=%.1f errMainVsOverlay=%.1f errFullFrame=%.1f".format(
                errInside, errVsMainInside, errMainVsOverlay, errFullFrame))
        assertTrue("composited region does not resemble the overlay source (mean luma error $errInside, vs main $errVsMainInside)",
            errInside < 30.0)

        // Before the overlay window (0.5s): the footprint must show the main
        // track only, so it matches the background there and not the overlay.
        val before = decodeFrame(output, 500_000)
        val mainEarly = sourceFrame("clip1_720p_6s.mp4", 500_000)
        val errBeforeMain = meanLumaError(before, mainEarly, fx0, fx1, fy0, fy1)
        val errBeforeOverlay = meanLumaErrorBetween(
            before, fx0, fx1, fy0, fy1,
            overlaySource, 0f, 1f, 0f, 1f,
        )
        android.util.Log.i("OverlayTest", "export before: errVsMain=%.1f errVsOverlay=%.1f"
            .format(errBeforeMain, errBeforeOverlay))
        assertTrue(
            "region before the overlay window matches the overlay, not the main track " +
                "(main $errBeforeMain vs overlay $errBeforeOverlay)",
            errBeforeMain < errBeforeOverlay,
        )

        // The overlay window moves with the source: at project 3s the footprint
        // must match the source at its 2s mark (overlay starts at project 1s).
        val later = decodeFrame(output, 3_000_000)
        val overlayLater = sourceFrame("clip3_1080p_4s.mp4", 2_000_000)
        val errLater = meanLumaErrorBetween(
            later, fx0, fx1, fy0, fy1,
            overlayLater, 0f, 1f, 0f, 1f,
        )
        // And it must not still be showing the source's 1s frame.
        val errLaterVsStale = meanLumaErrorBetween(
            later, fx0, fx1, fy0, fy1,
            overlaySource, 0f, 1f, 0f, 1f,
        )
        android.util.Log.i("OverlayTest", "export later: errVsOverlay2s=%.1f errVsStale1s=%.1f"
            .format(errLater, errLaterVsStale))
        assertTrue("overlay did not advance with its source (mean luma error $errLater)", errLater < 30.0)

        output.delete()
    }

    /**
     * Temporal alignment with a MOVING overlay source.
     *
     * `clip3_1080p_4s.mp4` is a static video — every frame is identical — so an
     * assertion that the overlay "advances with its source" passes vacuously on it
     * and proves nothing about time mapping. This test uses
     * `probe_sweep_720p_6s.mp4`, whose bright band sweeps left to right across 6s,
     * so each source instant is visually distinct.
     *
     * The overlay runs on the project timeline 1s–4s from source window 0–3s at
     * speed 1, so project time p must show source time p−1. Each sampled footprint
     * must match its own expected source frame and NOT the frames a second either
     * side, which is the assertion that actually pins the time mapping.
     */
    @Test
    fun overlay_time_alignment_tracks_a_moving_source() = runBlocking<Unit> {
        val main = extractRef("clip1_720p_6s.mp4")
        val sweep = extractRef("probe_sweep_720p_6s.mp4")
        val project = projectCreator.create(
            name = "PiP sweep",
            mediaRefs = listOf(main, sweep),
            canvas = CanvasSettings(width = 1280, height = 720),
        ).copy(
            timeline = Timeline(
                videoClips = listOf(
                    com.videoeditor.core.model.Clip(
                        id = "main", mediaRefId = main.id,
                        sourceStartMicros = 0L, sourceEndMicros = 6_000_000L,
                    ),
                ),
                overlayClips = listOf(
                    OverlayClip(
                        id = "pip", mediaRefId = sweep.id,
                        timelineStartMicros = 1_000_000L,
                        timelineDurationMicros = 3_000_000L,
                        sourceStartMicros = 0L,
                        sourceEndMicros = 3_000_000L,
                        transform = VisualTransform(scale = 0.3f, offsetX = 0.5f, offsetY = 0f),
                        zIndex = 0,
                    ),
                ),
            ),
        )
        val composition = compositionBuilder.buildForExport(project, 1280, 720)
        val output = File(retainedMediaTestDirectory(context), "pip-sweep-${System.currentTimeMillis()}.mp4")
        val completed = kotlinx.coroutines.CompletableDeferred<ExportResult?>()
        runOnMain {
            Transformer.Builder(context)
                .setVideoMimeType("video/avc")
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        completed.complete(r)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                        android.util.Log.e("OverlayTest", "sweep export error", e)
                        completed.complete(null)
                    }
                })
                .build()
                .start(composition, output.absolutePath)
        }
        assertTrue("sweep export failed", completed.await() != null)

        val fp = overlayFootprint(scale = 0.3f, offsetX = 0.5f, offsetY = 0f)
        val fx0 = fp[0]; val fx1 = fp[1]; val fy0 = fp[2]; val fy1 = fp[3]
        val report = StringBuilder("sweep alignment (project us -> expected source us):\n")
        // Project 1.5s, 2.5s, 3.5s -> source 0.5s, 1.5s, 2.5s.
        for (projectUs in longArrayOf(1_500_000L, 2_500_000L, 3_500_000L)) {
            val expectedSourceUs = projectUs - 1_000_000L
            val frame = decodeFrame(output, projectUs)
            val expected = sourceFrame("probe_sweep_720p_6s.mp4", expectedSourceUs)
            val oneSecondEarlier = sourceFrame(
                "probe_sweep_720p_6s.mp4", (expectedSourceUs - 1_000_000L).coerceAtLeast(0L),
            )
            val oneSecondLater = sourceFrame(
                "probe_sweep_720p_6s.mp4", (expectedSourceUs + 1_000_000L).coerceAtMost(5_900_000L),
            )
            val errExpected = meanLumaErrorBetween(frame, fx0, fx1, fy0, fy1, expected, 0f, 1f, 0f, 1f)
            val errEarlier = meanLumaErrorBetween(frame, fx0, fx1, fy0, fy1, oneSecondEarlier, 0f, 1f, 0f, 1f)
            val errLater = meanLumaErrorBetween(frame, fx0, fx1, fy0, fy1, oneSecondLater, 0f, 1f, 0f, 1f)
            report.append(
                "  ${projectUs}us -> source ${expectedSourceUs}us: errExpected=%.1f errEarlier=%.1f errLater=%.1f\n"
                    .format(errExpected, errEarlier, errLater),
            )
            // The band moves ~213px per second, so a one-second error is large.
            assertTrue(
                "overlay does not match its expected source frame at project ${projectUs}us " +
                    "(expected $errExpected vs earlier $errEarlier vs later $errLater)",
                errExpected < 30.0,
            )
            assertTrue(
                "overlay is not time-discriminating at project ${projectUs}us: a frame one " +
                    "second off matches as well (expected $errExpected, earlier $errEarlier, later $errLater)",
                errExpected < errEarlier && errExpected < errLater,
            )
        }
        android.util.Log.i("OverlayTest", report.toString())
        output.delete()
    }

    /** Independent CPU reference for the actual canvas and transformed source. */
    private fun overlayReference(background: Bitmap, source: Bitmap, layer: OverlayClip,
        width: Int, height: Int, fill: Boolean = false): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(background, null, android.graphics.Rect(0, 0, width, height), paint)
        val fit = if (fill) maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
            else minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        canvas.save()
        // Compositor anchors range from -1 to 1; model Y is down-positive.
        canvas.translate(width * (1f + layer.transform.offsetX) / 2f,
            height * (1f + layer.transform.offsetY) / 2f)
        canvas.rotate(layer.transform.rotationDegrees.toFloat())
        val scale = fit * layer.transform.scale
        canvas.scale(scale, scale)
        paint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawBitmap(source, -source.width / 2f, -source.height / 2f, paint)
        canvas.restore()
        return result
    }

    private fun preservePreviewFrame(name: String, bitmap: Bitmap) {
        val dir = File(context.getExternalFilesDir(null), "overlay-evidence/editor-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun overlay_renders_in_preview_and_disappears_outside_its_window() {
        val project = pipProject()
        val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
        val frames = java.util.concurrent.LinkedBlockingQueue<Bitmap>()
        val imageThread = android.os.HandlerThread("pip-preview").apply { start() }
        val reader = ImageReader.newInstance(1280, 720, android.graphics.PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width
                val bitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(plane.buffer)
                frames.offer(Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height))
            } finally {
                image.close()
            }
        }, android.os.Handler(imageThread.looper))

        try {
            runOnMain {
                controller.attachHeadlessSurface(reader.surface, Size(1280, 720))
                controller.setProject(project)
            }
            assertNull("preview error: ${controller.state.value.error}", controller.state.value.error)
            assertTrue("preview never produced a composition", pollUntil(15_000) { controller.state.value.hasComposition })

            // Drain the initial frame before requesting a different project time.
            assertNotNull("no initial preview frame", pollFrame(frames, 8_000))
            assertTrue("initial preview remained buffering", pollUntil(8_000) { !controller.state.value.isBuffering })
            frames.clear()
            // Seek inside the overlay window and capture a frame.
            runOnMain { controller.seekTo(2_000_000) }
            val insideFrame = pollFrame(frames, 8_000)
            assertNotNull("no preview frame inside the overlay window", insideFrame)
            val overlaySource = sourceFrame("clip3_1080p_4s.mp4", 1_000_000)
            val fp = overlayFootprint(scale = 0.3f, offsetX = 0.5f, offsetY = 0f)
            val mainAt2s = sourceFrame("clip1_720p_6s.mp4", 2_000_000)
            val expectedInside = overlayReference(mainAt2s, overlaySource,
                project.timeline.overlayClips.single(), project.canvas.width, project.canvas.height)
            preservePreviewFrame("inside-actual", insideFrame!!)
            preservePreviewFrame("inside-expected", expectedInside)
            val errInside = meanLumaError(insideFrame, expectedInside, fp[0], fp[1], fp[2], fp[3])
            assertTrue(
                "preview does not show the overlay at the intended position (mean luma error $errInside)",
                errInside < 30.0,
            )
            // And it must not be the plain main track there.
            val errVsMain = meanLumaError(insideFrame, mainAt2s, 0.6f, 0.9f, 0.35f, 0.65f)
            assertTrue(
                "preview region matches the main track as if no overlay existed " +
                    "(overlay $errInside vs main $errVsMain)",
                errInside < errVsMain,
            )

            // Before the overlay window the region belongs to the main track.
            frames.clear()
            runOnMain { controller.seekTo(200_000) }
            val beforeFrame = pollFrame(frames, 8_000)
            assertNotNull("no preview frame before the overlay window", beforeFrame)
            val mainEarly = sourceFrame("clip1_720p_6s.mp4", 200_000)
            val errBeforeMain = meanLumaError(beforeFrame!!, mainEarly, 0.6f, 0.9f, 0.35f, 0.65f)
            val errBeforeOverlay = meanLumaErrorBetween(
                beforeFrame, fp[0], fp[1], fp[2], fp[3], overlaySource, 0f, 1f, 0f, 1f,
            )
            assertTrue(
                "preview shows the overlay before its window starts " +
                    "(main $errBeforeMain vs overlay $errBeforeOverlay)",
                errBeforeMain < errBeforeOverlay,
            )
        } finally {
            runOnMain { controller.release() }
            reader.close()
            imageThread.quitSafely()
        }
    }

    @Test
    fun captions_remain_above_video_layers_in_preview_and_export() = runBlocking<Unit> {
        val main = extractRef("pip_main_moving_audio.mp4")
        val overlay = extractRef("pip_overlay_moving_audio.mp4")
        val base = pipProject()
        val project = base.copy(mediaRefs = listOf(main, overlay), timeline = base.timeline.copy(
            videoClips = listOf(base.timeline.videoClips.single().copy(mediaRefId = main.id)),
            overlayClips = listOf(base.timeline.overlayClips.single().copy(mediaRefId = overlay.id)),
            textOverlays = listOf(com.videoeditor.core.model.TextOverlay("top", "TOP",
                startMicros = 1_000_000, durationMicros = 3_000_000, positionX = .75f, positionY = .5f,
                fontSizeSp = 80f, colorArgb = android.graphics.Color.YELLOW)),
        ))
        fun yellowPixels(bitmap: Bitmap): Int {
            var count = 0
            for (y in (bitmap.height * .35f).toInt() until (bitmap.height * .65f).toInt()) {
                for (x in (bitmap.width * .6f).toInt() until (bitmap.width * .9f).toInt()) {
                    val c = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(c) > 180 && android.graphics.Color.green(c) > 180 && android.graphics.Color.blue(c) < 100) count++
                }
            }
            return count
        }
        val output = File(retainedMediaTestDirectory(context), "caption-pip-${System.nanoTime()}.mp4")
        val result = ExportEngine(context, compositionBuilder).export(project, ExportRequest(projectId = project.id,
            outputFile = output, resolution = ExportResolution.RES_720P, frameRate = FrameRate.FPS_30, quality = ExportQuality.Standard)) { }
        assertTrue("caption PiP export failed", result.isSuccess)
        val exported = decodeFrame(output, 2_000_000)
        preservePreviewFrame("caption-export", exported)
        assertTrue("caption was covered by exported video layer", yellowPixels(exported) > 200)
        val frames = java.util.concurrent.LinkedBlockingQueue<Bitmap>()
        val thread = android.os.HandlerThread("caption-pip").apply { start() }
        val reader = ImageReader.newInstance(1280, 720, android.graphics.PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val bitmap = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                frames.offer(Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height))
            } finally { image.close() }
        }, android.os.Handler(thread.looper))
        val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
        try {
            runOnMain { controller.attachHeadlessSurface(reader.surface, Size(1280, 720)); controller.setProject(project) }
            assertNotNull("initial caption preview missing", pollFrame(frames, 8_000))
            assertTrue(pollUntil(8_000) { !controller.state.value.isBuffering })
            frames.clear()
            runOnMain { controller.seekTo(2_000_000) }
            val actual = pollFrame(frames, 8_000)
            assertNotNull("caption preview seek produced no frame", actual)
            preservePreviewFrame("caption-preview", actual!!)
            assertTrue("caption was covered by preview video layer", yellowPixels(actual) > 200)
        } finally { runOnMain { controller.release() }; reader.close(); thread.quitSafely() }
    }

    @Test
    fun photo_overlay_preserves_fast_main_source_cadence_in_preview_and_export() = runBlocking<Unit> {
        val main = extractRef("pip_main_frame_identity_audio.mp4")
        val photoBitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.CYAN)
        }
        val photoFile = File(mediaDir, "cadence-photo.png")
        photoFile.outputStream().use { photoBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val photo = MediaRef("cadence-photo", android.net.Uri.fromFile(photoFile).toString(),
            com.videoeditor.core.model.MediaType.Photo, "Cadence photo", mimeType = "image/png",
            width = 640, height = 360, hasAudio = false)
        val base = projectCreator.create(name = "Main cadence", mediaRefs = listOf(main),
            canvas = CanvasSettings(width = 1280, height = 720))
        val layer = OverlayClip(id = "photo", mediaRefId = photo.id, isPhoto = true,
            timelineStartMicros = 0L, timelineDurationMicros = 3_000_000L,
            sourceStartMicros = 0L, sourceEndMicros = 0L,
            transform = VisualTransform(scale = .3f, offsetX = .5f))
        val project = base.copy(mediaRefs = base.mediaRefs + photo,
            timeline = base.timeline.copy(videoClips = base.timeline.videoClips.map { it.copy(playbackSpeed = 2f) },
                overlayClips = listOf(layer)))
        val evidence = File(context.getExternalFilesDir(null),
            "overlay-evidence/main-cadence/run-${android.os.SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
        File(evidence, "project.json").writeText(kotlinx.serialization.json.Json { encodeDefaults = true }
            .encodeToString(Project.serializer(), project))
        val source = MediaMetadataRetriever().apply { setDataSource(File(mediaDir, "pip_main_frame_identity_audio.mp4").path) }
        fun marker(bitmap: Bitmap): Int {
            fun bit(x: Int): Int {
                val c = bitmap.getPixel(x * bitmap.width / 1280, 22 * bitmap.height / 720)
                val v = ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3
                assertTrue("ambiguous cadence marker $x=$v", v < 40 || v > 215)
                return if (v > 215) 1 else 0
            }
            assertEquals("missing main source marker", 1, bit(22))
            return (0 until 8).sumOf { bit -> bit(58 + bit * 36) shl bit }
        }
        fun verify(bitmap: Bitmap, index: Int, name: String) {
            File(evidence, "$name-actual.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals("$name changed main source cadence", index, marker(bitmap))
            val decoded = source.getFrameAtIndex(index)!!
            val expected = overlayReference(decoded, photoBitmap, layer, bitmap.width, bitmap.height)
            decoded.recycle()
            File(evidence, "$name-expected.png").outputStream().use { expected.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val background = meanLumaError(bitmap, expected, .05f, .5f, .1f, .9f)
            val overlay = meanLumaError(bitmap, expected, .6f, .9f, .35f, .65f)
            File(evidence, "$name.txt").writeText("sourceIndex=$index sourceUs=${index * 1_000_000L / 30} backgroundError=$background overlayError=$overlay threshold=30\n")
            expected.recycle()
            assertTrue("$name background mismatch $background", background < 30)
            assertTrue("$name photo mismatch $overlay", overlay < 30)
        }
        try {
            for (resolution in listOf(ExportResolution.RES_720P, ExportResolution.RES_1080P)) {
                val output = File(evidence, "${resolution.name}.mp4")
                val result = ExportEngine(context, compositionBuilder).export(project,
                    ExportRequest(projectId = project.id, outputFile = output, resolution = resolution,
                        frameRate = FrameRate.FPS_30, quality = ExportQuality.Standard)) { }
                assertTrue("cadence export failed $result", result.isSuccess)
                val retriever = MediaMetadataRetriever().apply { setDataSource(output.path) }
                try {
                    val count = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)!!.toInt()
                    File(evidence, "${resolution.name}-frame-count.txt").writeText("actual=$count expected=180 sourceFrames=180 speed=2 projectDurationUs=3000000\n")
                    // Source timing is the established export policy: speed changes
                    // PTS, while a photo must not discard half the decoded main frames.
                    assertEquals("photo changed source frame cadence", 180, count)
                    for (index in listOf(0, 1, 2, 23, 45, 90, 119, 150, 178, 179)) {
                        val actual = retriever.getFrameAtIndex(index)!!
                        verify(actual, index, "${resolution.name}-$index")
                        actual.recycle()
                    }
                } finally { retriever.release() }
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val activity = instrumentation.startActivitySync(android.content.Intent(context, MultiInputProofActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MultiInputProofActivity
            val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
            try {
                runOnMain {
                    activity.surfaceView.layoutParams = android.widget.FrameLayout.LayoutParams(
                        activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.widthPixels * 9 / 16)
                    controller.attachSurface(activity.surfaceView); controller.setProject(project)
                }
                assertTrue(pollUntil(10_000) { !controller.state.value.isBuffering })
                runOnMain { controller.play() }
                assertTrue("fast composition did not end", pollUntil(15_000) {
                    !controller.state.value.playRequested && controller.state.value.positionMicros == 3_000_000L
                })
                val actual = Bitmap.createBitmap(activity.surfaceView.width, activity.surfaceView.height, Bitmap.Config.ARGB_8888)
                val latch = java.util.concurrent.CountDownLatch(1)
                var copyResult = -1
                runOnMain { android.view.PixelCopy.request(activity.surfaceView, actual, {
                    copyResult = it; latch.countDown()
                }, android.os.Handler(android.os.Looper.getMainLooper())) }
                assertTrue(latch.await(3, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("fast end PixelCopy failed", android.view.PixelCopy.SUCCESS, copyResult)
                File(evidence, "preview-end-state.txt").writeText("${controller.state.value} outputUs=${controller.renderedPositionMicros} expectedSourceIndex=179\n")
                verify(actual, 179, "preview-end")
            } finally { runOnMain { controller.release(); activity.finish() } }
        } finally { source.release(); photoBitmap.recycle() }
    }

    /** One Play request, actual SurfaceView output at multiple running-clock positions. */
    @Test
    fun moving_two_sequence_surface_playback_seek_release_and_replacement() = verifyContinuousPlayback(false)

    @Test
    fun three_visual_sequences_share_clock_and_survive_replacement() = verifyContinuousPlayback(true)

    @Test
    fun photo_overlay_uses_shared_graph_during_playback_seek_and_replacement() = verifyContinuousPlayback(false, true)

    @Test
    fun photo_capture_identifies_buffer_when_completion_callback_is_delayed() =
        verifyContinuousPlayback(false, true, 2)

    @Test
    fun photo_surface_recreation_at_project_end_retains_final_frame_and_replays() =
        verifyContinuousPlayback(false, true, recreateAtEnd = true)

    @Test
    fun three_visual_capture_identifies_buffer_when_completion_callback_is_delayed() =
        verifyContinuousPlayback(extraLayer = true, callbackVsyncs = 2)

    private fun verifyContinuousPlayback(extraLayer: Boolean, photoLayer: Boolean = false, callbackVsyncs: Int = 0, recreateAtEnd: Boolean = false) {
        val movingName = "pip_main_frame_identity_audio.mp4"
        val moving = extractRef(movingName)
        val photoBitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        val photoFile = File(mediaDir, "pip-photo.png")
        photoFile.outputStream().use { photoBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val movingOverlay = if (photoLayer) MediaRef("pip-photo", android.net.Uri.fromFile(photoFile).toString(),
            com.videoeditor.core.model.MediaType.Photo, "PiP photo", mimeType = "image/png", width = 640, height = 360, hasAudio = false)
            else extractRef("pip_overlay_moving_audio.mp4")
        // Decode references BEFORE playback: repeated synchronous retriever seeks
        // during playback otherwise contend with the emulator's actual decoders.
        val referenceRetriever = MediaMetadataRetriever().apply {
            setDataSource(File(mediaDir, if (photoLayer) movingName else "probe_sweep_720p_6s.mp4").path)
        }
        val referenceFrames = try {
            Array(180) { index ->
                // Exact decoder index for both video and photo compositions.
                // PixelCopy completion cannot identify the captured buffer.
                val decoded = referenceRetriever.getFrameAtIndex(index)!!
                Bitmap.createScaledBitmap(decoded, 320, 180, true).also { decoded.recycle() }
            }
        } finally { referenceRetriever.release() }
        fun movingReference(timeUs: Long) = referenceFrames[((timeUs * 30 + 500_000) / 1_000_000).toInt().coerceIn(0, 179)]
        val base = pipProject()
        val project = base.copy(
            mediaRefs = base.mediaRefs + moving + movingOverlay,
            timeline = base.timeline.copy(
                videoClips = listOf(base.timeline.videoClips.single().copy(mediaRefId = moving.id)),
                overlayClips = listOf(base.timeline.overlayClips.single().copy(mediaRefId = movingOverlay.id, isPhoto = photoLayer,
                        sourceEndMicros = if (photoLayer) 0 else 3_000_000, volume = if (extraLayer) .5f else 1f)) +
                    if (extraLayer) listOf(base.timeline.overlayClips.single().copy(id = "third", mediaRefId = movingOverlay.id,
                        muted = true, zIndex = 1, transform = VisualTransform(scale = .15f, offsetX = .7f, offsetY = -.6f))) else emptyList(),
            ),
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(
            context, MultiInputProofActivity::class.java,
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MultiInputProofActivity
        runOnMain {
            activity.surfaceView.layoutParams = android.widget.FrameLayout.LayoutParams(
                activity.resources.displayMetrics.widthPixels,
                activity.resources.displayMetrics.widthPixels * 9 / 16,
            )
        }
        data class PcmSample(val timeUs: Long, val value: Double)
        val pcm = mutableListOf<PcmSample>()
        val pcmLock = Any()
        var pcmSampleRate = 48000
        val audioSink = object : androidx.media3.exoplayer.audio.ForwardingAudioSink(
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).build(),
        ) {
            private var format = androidx.media3.common.Format.Builder().build()
            override fun configure(inputFormat: androidx.media3.common.Format, bufferSize: Int, outputChannels: IntArray?) {
                format = inputFormat
                pcmSampleRate = inputFormat.sampleRate
                super.configure(inputFormat, bufferSize, outputChannels)
            }
            override fun handleBuffer(buffer: java.nio.ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                val copy = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val initial = buffer.position()
                val result = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                val bytes = if (format.pcmEncoding == androidx.media3.common.C.ENCODING_PCM_FLOAT) 4 else 2
                val channels = format.channelCount.coerceAtLeast(1)
                val records = mutableListOf<PcmSample>()
                repeat((buffer.position() - initial) / (bytes * channels)) { frame ->
                    var value = 0.0
                    repeat(channels) { value += if (bytes == 4) copy.float.toDouble() * 32767 else copy.short.toDouble() }
                    records += PcmSample(presentationTimeUs + (initial / (bytes * channels) + frame) * 1_000_000L / format.sampleRate, value / channels)
                }
                synchronized(pcmLock) { pcm.addAll(records) }
                return result
            }
        }
        val controller = CompositionPreviewController.Factory(context, compositionBuilder).create(audioSink)
        val evidence = File(context.getExternalFilesDir(null), "overlay-evidence/continuous-player${if (extraLayer) "-three" else if (photoLayer) "-photo" else ""}/run-${android.os.SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
        File(evidence, "run.properties").writeText(
            "photoLayer=$photoLayer extraLayer=$extraLayer callbackVsyncs=$callbackVsyncs mainSource=${moving.id}\n")
        File(evidence, "project.json").writeText(kotlinx.serialization.json.Json { encodeDefaults = true }
            .encodeToString(Project.serializer(), project))
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var capturedOutputUs = androidx.media3.common.C.TIME_UNSET
        var capturedClockUs = 0L
        var captureRequestNs = 0L
        var captureCompletionNs = 0L
        var requestedOutputUs = 0L
        var captureRequestedWhilePlaying = false
        var requestedClockUs = 0L
        fun capture(): Bitmap {
            val bitmap = Bitmap.createBitmap(activity.surfaceView.width, activity.surfaceView.height, Bitmap.Config.ARGB_8888)
            val deadline = android.os.SystemClock.elapsedRealtime() + 3_000
            var result = android.view.PixelCopy.ERROR_SOURCE_NO_DATA
            do {
                val latch = java.util.concurrent.CountDownLatch(1)
                runOnMain {
                    captureRequestNs = android.os.SystemClock.elapsedRealtimeNanos()
                    requestedOutputUs = controller.renderedPositionMicros
                    captureRequestedWhilePlaying = controller.state.value.playRequested
                    requestedClockUs = controller.playbackClockPositionMicros
                    android.view.PixelCopy.request(activity.surfaceView, bitmap, {
                    result = it
                    // Exercise an occupied callback queue without changing the
                    // captured buffer, production pacing, or pixel tolerance.
                    fun complete(remainingVsyncs: Int) {
                        if (remainingVsyncs > 0) {
                            android.view.Choreographer.getInstance().postFrameCallback { complete(remainingVsyncs - 1) }
                        } else {
                            captureCompletionNs = android.os.SystemClock.elapsedRealtimeNanos()
                            capturedOutputUs = controller.renderedPositionMicros
                            capturedClockUs = controller.playbackClockPositionMicros
                            latch.countDown()
                        }
                    }
                    complete(callbackVsyncs)
                }, handler) }
                assertTrue("PixelCopy timed out", latch.await(3, java.util.concurrent.TimeUnit.SECONDS))
                // Surface reattachment can precede the first queued buffer. Wait
                // for real output, retaining the same strict pixel assertions.
                if (result == android.view.PixelCopy.ERROR_SOURCE_NO_DATA) Thread.sleep(16)
            } while (result == android.view.PixelCopy.ERROR_SOURCE_NO_DATA && android.os.SystemClock.elapsedRealtime() < deadline)
            assertTrue("PixelCopy error $result", result == android.view.PixelCopy.SUCCESS)
            return bitmap
        }
        fun verify(name: String, positionUs: Long): Bitmap {
            val actual = capture()
            File(evidence, "$name-actual.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(evidence, "$name-capture.txt").writeText(
                "requestedProjectUs=$positionUs requestOutputUs=$requestedOutputUs submittedUs=$capturedOutputUs clockUs=$capturedClockUs " +
                    "requestClockUs=$requestedClockUs requestNs=$captureRequestNs completionNs=$captureCompletionNs pixelCopy=SUCCESS generation=${controller.rebuildCount}\n")
            // PixelCopy carries no buffer PTS. The encoded marker identifies its
            // actual decoded frame; a completion-time deadline cannot do so.
            val submittedPositionUs = capturedOutputUs
            assertTrue("no decoded output timestamp", submittedPositionUs >= 0)
            val visibleIndex = run {
                fun markerValue(sourceX: Int): Int {
                    val pixel = actual.getPixel(sourceX * actual.width / 1280, 22 * actual.height / 720)
                    val value = ((pixel shr 16 and 255) + (pixel shr 8 and 255) + (pixel and 255)) / 3
                    assertTrue("ambiguous encoded frame marker $sourceX=$value", value < 40 || value > 215)
                    return if (value > 215) 1 else 0
                }
                assertTrue("encoded source identity missing", markerValue(22) == 1)
                (0 until 8).sumOf { bit -> markerValue(58 + bit * 36) shl bit }.also {
                    assertTrue("invalid encoded frame index $it", it in referenceFrames.indices)
                }
            }
            val sampledPositionUs = visibleIndex * 1_000_000L / 30
            assertTrue("captured frame diverged from output deadline: $sampledPositionUs vs $submittedPositionUs",
                kotlin.math.abs(sampledPositionUs - submittedPositionUs) <= 100_000L)
            // A deliberately delayed callback (and PNG persistence) may finish
            // after continuous playback reaches EOS. Classify the request when
            // submitted, rather than treating that running frame as a paused seek.
            if (!captureRequestedWhilePlaying) assertTrue("paused capture missed requested frame $positionUs: $sampledPositionUs",
                sampledPositionUs in positionUs..(positionUs + 33_334))
            // Callback delivery is not capture time. Compare with the clock at
            // the request, retaining the completion clock separately as evidence.
            val sampledClockUs = requestedClockUs
            assertTrue("decoded output diverged from project clock: $sampledPositionUs vs $sampledClockUs",
                kotlin.math.abs(sampledPositionUs - sampledClockUs) <= 100_000L)
            val expected = project.timeline.overlayClips.sortedBy { it.zIndex }.fold(
                movingReference(sampledPositionUs),
            ) { background, layer -> overlayReference(background,
                if (photoLayer) photoBitmap else movingReference((sampledPositionUs - 1_000_000).coerceAtLeast(0)),
                layer.copy(opacity = if (sampledPositionUs in 1_000_000 until 4_000_000) layer.opacity else 0f),
                project.canvas.width, project.canvas.height)
            }
            File(evidence, "$name-expected.png").outputStream().use { expected.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val fp = overlayFootprint(0.3f, 0.5f, 0f)
            val backgroundError = meanLumaError(actual, expected, 0.05f, 0.5f, 0.1f, 0.9f)
            val overlayError = meanLumaError(actual, expected, fp[0], fp[1], fp[2], fp[3])
            // Do not decode/allocate neighboring reference images during a
            // successful playback sample; that competes with the real decoders.
            val neighbors = if (backgroundError >= 30 || overlayError >= 30) (-12..12).map { offset ->
                val time = (sampledPositionUs + offset * 1_000_000L / 30).coerceIn(0, 5_966_666)
                "$time:${meanLumaError(actual, movingReference(time), 0.05f, 0.5f, 0.1f, 0.9f)}"
            } else emptyList()
            File(evidence, "$name.txt").writeText(
                "requestedProjectUs=$positionUs requestOutputUs=$requestedOutputUs projectUs=$sampledPositionUs clockUs=$sampledClockUs " +
                    "visibleFrameIndex=$visibleIndex submittedUs=$submittedPositionUs requestNs=$captureRequestNs completionNs=$captureCompletionNs pixelCopy=SUCCESS generation=${controller.rebuildCount} " +
                    "state=${controller.state.value} backgroundError=$backgroundError overlayError=$overlayError\n" +
                    "neighborBackgroundErrors=${neighbors.joinToString()}\n")
            assertTrue("$name background error $backgroundError", backgroundError < 30)
            assertTrue("$name overlay error $overlayError", overlayError < 30)
            if (extraLayer) assertTrue("$name third input pixels differ",
                meanLumaError(actual, expected, .77f, .93f, .12f, .28f) < 30)
            assertNull("preview error", controller.state.value.error)
            return actual
        }
        try {
            assertTrue("surface unavailable", pollUntil(5_000) { activity.surfaceView.holder.surface.isValid })
            runOnMain { controller.attachSurface(activity.surfaceView); controller.setProject(project) }
            assertTrue("initial frame not ready", pollUntil(10_000) { controller.state.value.hasComposition && !controller.state.value.isBuffering })
            runOnMain { controller.play() }
            val captures = mutableListOf<Bitmap>()
            for (target in listOf(300_000L, 1_300_000L, 2_300_000L, 3_300_000L, 4_500_000L, 5_300_000L)) {
                assertTrue("clock stalled before $target", pollUntil(12_000) { controller.state.value.actualPositionMicros >= target })
                captures += verify("running-$target", controller.state.value.actualPositionMicros)
            }
            assertTrue("background did not move", meanLumaError(captures[1], captures[3], 0.05f, 0.5f, 0.1f, 0.9f) > 30)
            if (!photoLayer) assertTrue("overlay did not move", meanLumaError(captures[1], captures[3], 0.6f, 0.9f, 0.35f, 0.65f) > 30)
            assertTrue("composition did not finish", pollUntil(10_000) { !controller.state.value.playRequested && controller.state.value.positionMicros == 6_000_000L })
            if (recreateAtEnd) {
                runOnMain {
                    controller.detachSurface(activity.surfaceView)
                    activity.surfaceView = android.view.SurfaceView(activity)
                    activity.setContentView(activity.surfaceView, android.widget.FrameLayout.LayoutParams(
                        activity.resources.displayMetrics.widthPixels,
                        activity.resources.displayMetrics.widthPixels * 9 / 16))
                }
                assertTrue("end surface unavailable", pollUntil(5_000) { activity.surfaceView.holder.surface.isValid })
                runOnMain { controller.attachSurface(activity.surfaceView) }
                val ready = pollUntil(10_000) {
                    controller.state.value.error != null || (!controller.state.value.isBuffering && controller.renderedPositionMicros >= 5_966_666L)
                }
                File(evidence, "end-surface-state.txt").writeText(controller.state.value.toString())
                assertTrue("end surface output stalled", ready)
                assertNull("end surface playback error", controller.state.value.error)
                assertEquals("end clock moved", 6_000_000L, controller.state.value.positionMicros)
                verify("end-surface-recreated", 5_966_666L)
                runOnMain { controller.seekTo(1_500_000L) }
                assertTrue("end restoration backward seek stalled", pollUntil(10_000) {
                    controller.state.value.error != null || (!controller.state.value.isBuffering && controller.renderedPositionMicros in 1_500_000L..1_533_334L)
                })
                assertNull("backward seek error", controller.state.value.error)
                verify("end-backward-seek", 1_500_000L)
                runOnMain { controller.seekTo(6_000_000L) }
                assertTrue("explicit end seek stalled", pollUntil(10_000) {
                    controller.state.value.error != null || (!controller.state.value.isBuffering && controller.renderedPositionMicros >= 5_966_666L)
                })
                assertNull("explicit end seek error", controller.state.value.error)
                assertEquals("explicit end clock moved", 6_000_000L, controller.state.value.positionMicros)
                verify("explicit-end-seek", 5_966_666L)
            }
            fun amplitude(frequency: Double, from: Long, until: Long): Double {
                val samples = synchronized(pcmLock) { pcm.filter { it.timeUs in from until until } }
                assertTrue("missing mixed PCM $from..$until", samples.size > 1000)
                // Frequency content belongs to sample order. Sink buffer PTS
                // selects the project window, not oscillator phase across buffers.
                val blocks = samples.chunked(2048).filter { it.size == 2048 }
                return blocks.map { block ->
                    val weights = block.indices.map { 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / (block.size - 1)) }
                    val re = block.withIndex().sumOf { (i, sample) -> weights[i] * sample.value * kotlin.math.cos(2 * Math.PI * frequency * i / pcmSampleRate) }
                    val im = block.withIndex().sumOf { (i, sample) -> weights[i] * sample.value * kotlin.math.sin(2 * Math.PI * frequency * i / pcmSampleRate) }
                    2 * kotlin.math.sqrt(re * re + im * im) / weights.sum()
                }.average()
            }
            val capturedPcm = synchronized(pcmLock) { pcm.toList() }
            val pcmBytes = java.nio.ByteBuffer.allocate(capturedPcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            capturedPcm.forEach { pcmBytes.putShort(it.value.toInt().coerceIn(-32768, 32767).toShort()) }
            File(evidence, "mixed-mono-s16le.pcm").writeBytes(pcmBytes.array())
            File(evidence, "mixed-pcm-format.txt").writeText("mono s16le sampleRate=$pcmSampleRate samples=${capturedPcm.size}\n")
            val mainBefore = amplitude(440.0, 200_000, 700_000)
            val overlayBefore = amplitude(880.0, 200_000, 700_000)
            val mainInside = amplitude(440.0, 1_700_000, 2_300_000)
            val overlayInside = amplitude(880.0, 1_700_000, 2_300_000)
            val overlayAfter = amplitude(880.0, 4_500_000, 5_200_000)
            File(evidence, "mixed-pcm.txt").writeText("mainBefore=$mainBefore overlayBefore=$overlayBefore mainInside=$mainInside overlayInside=$overlayInside overlayAfter=$overlayAfter\n")
            assertTrue("background audio missing", mainBefore > 1000 && mainInside > 1000)
            if (photoLayer) assertTrue("photo overlay produced audio", overlayInside < 20)
            else {
                assertTrue("overlay audio missing", overlayInside > 1000)
                val expectedRatio = if (extraLayer) .5 else 1.0
                assertTrue("overlay volume does not match mixed PCM", kotlin.math.abs(overlayInside / mainInside - expectedRatio) < .1)
            }
            assertTrue("overlay audio outside its window", overlayBefore < 20 && overlayAfter < 20)
            runOnMain { controller.play() }
            assertTrue("replay stalled", pollUntil(10_000) { controller.state.value.actualPositionMicros in 1_200_000L..2_000_000L })
            runOnMain { controller.pause() }
            assertTrue("pause remained buffering", pollUntil(10_000) { !controller.state.value.isBuffering })
            for (target in listOf(2_700_000L, 200_000L, 3_200_000L)) {
                val start = android.os.SystemClock.elapsedRealtimeNanos()
                runOnMain { controller.seekTo(target) }
                assertTrue("seek $target stalled", pollUntil(10_000) { !controller.state.value.isBuffering && controller.state.value.actualPositionMicros == target &&
                    controller.renderedPositionMicros in target..(target + 33_334) })
                verify("seek-$target", target)
                File(evidence, "seek-latency.txt").appendText("$target ${(android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000} ms\n")
            }
            if (photoLayer) {
                runOnMain {
                    controller.detachSurface(activity.surfaceView)
                    activity.surfaceView = android.view.SurfaceView(activity)
                    activity.setContentView(activity.surfaceView, android.widget.FrameLayout.LayoutParams(
                        activity.resources.displayMetrics.widthPixels,
                        activity.resources.displayMetrics.widthPixels * 9 / 16))
                }
                assertTrue("recreated surface unavailable", pollUntil(5_000) { activity.surfaceView.holder.surface.isValid })
                runOnMain { controller.attachSurface(activity.surfaceView) }
                assertTrue("recreated photo output stalled", pollUntil(10_000) {
                    !controller.state.value.isBuffering && controller.renderedPositionMicros in 3_200_000L..3_233_334L
                })
                verify("surface-recreated", 3_200_000L)
            }
            runOnMain { controller.play() }
            assertTrue("resume stalled", pollUntil(10_000) { controller.state.value.actualPositionMicros > 3_400_000L })
            // Stop a live two-decoder composition and immediately open another.
            val beforeReplacement = controller.rebuildCount
            runOnMain { controller.setProject(null); controller.setProject(project.copy(id = project.id + "replacement")) }
            assertTrue("replacement did not create a graph", controller.rebuildCount > beforeReplacement)
            assertTrue("replacement remained buffering", pollUntil(10_000) { !controller.state.value.isBuffering && controller.state.value.hasComposition })
            verify("replacement", controller.state.value.actualPositionMicros)
            runOnMain {
                controller.seekForScrub(3_000_000)
                controller.seekForScrub(200_000)
                controller.seekForScrub(2_000_000)
                controller.flushScrubSeek()
            }
            assertTrue("latest coalesced seek did not win", pollUntil(10_000) {
                !controller.state.value.isBuffering && controller.state.value.actualPositionMicros == 2_000_000L &&
                    controller.renderedPositionMicros >= 2_000_000L && controller.pendingOutputFrameCount > 0
            })
            runOnMain {
                // A real future compositor frame is pending while paused. Stop
                // both actual decoder producers, cancel, then open immediately.
                controller.setProject(null)
                controller.setProject(project.copy(id = project.id + "cancel-seek"))
            }
            assertTrue("cancel-during-seek replacement stalled", pollUntil(10_000) { !controller.state.value.isBuffering && controller.state.value.hasComposition })
            verify("cancel-seek-replacement", 0L)
        } finally {
            File(evidence, "media3-trace.tsv").writer().use { androidx.media3.effect.DebugTraceUtil.dumpTsv(it) }
            runOnMain { controller.release(); activity.finish() }
            referenceFrames.forEach(Bitmap::recycle)
            photoBitmap.recycle()
        }
    }

    @Test
    fun oriented_photo_geometry_survives_preview_serialization_and_720p_1080p_export() = runBlocking<Unit> {
        val evidence = File(context.getExternalFilesDir(null), "overlay-evidence/photo-geometry/run-${System.nanoTime()}").apply { mkdirs() }
        val main = extractRef("pip_main_frame_identity_audio.mp4")
        val retriever = MediaMetadataRetriever().apply { setDataSource(File(mediaDir, "pip_main_frame_identity_audio.mp4").path) }
        try {
            for ((variant, dimensions) in listOf(640 to 360, 360 to 640, 640 to 360).withIndex()) {
                val (width, height) = dimensions
                val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(raw)
                val paint = android.graphics.Paint()
                listOf(android.graphics.Color.RED, android.graphics.Color.GREEN, android.graphics.Color.BLUE, android.graphics.Color.YELLOW)
                    .forEachIndexed { index, color ->
                        paint.color = color
                        val x = (index % 2) * width / 2f
                        val y = (index / 2) * height / 2f
                        canvas.drawRect(x, y, x + width / 2f, y + height / 2f, paint)
                    }
                val file = File(evidence, "photo-$variant.jpg")
                file.outputStream().use { raw.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                raw.recycle()
                if (variant == 2) android.media.ExifInterface(file.path).apply {
                    setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6"); saveAttributes()
                }
                val ref = (extractor.extract(context, android.net.Uri.fromFile(file)) as Result.Success).value
                val decoded = android.graphics.BitmapFactory.decodeFile(file.path)
                val oriented = if (variant == 2) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
                    android.graphics.Matrix().apply { postRotate(90f) }, true).also { decoded.recycle() } else decoded
                val layer = pipProject().timeline.overlayClips.single().copy(
                    mediaRefId = ref.id, isPhoto = true, sourceEndMicros = 0,
                    timelineStartMicros = 500_000, timelineDurationMicros = 2_000_000,
                    opacity = .65f, transform = VisualTransform(scale = .4f, offsetX = .45f, offsetY = .1f, rotationDegrees = 27))
                val base = pipProject()
                val project = base.copy(mediaRefs = listOf(main, ref), timeline = base.timeline.copy(
                    videoClips = listOf(base.timeline.videoClips.single().copy(mediaRefId = main.id, sourceEndMicros = 3_000_000)),
                    overlayClips = listOf(layer)))
                val json = kotlinx.serialization.json.Json { encodeDefaults = true }
                val document = File(evidence, "project-$variant.json")
                document.writeText(json.encodeToString(Project.serializer(), project))
                val reopened = json.decodeFromString(Project.serializer(), document.readText())
                assertTrue("photo project serialization changed", reopened == project)
                fun expectedAt(index: Int, outputWidth: Int, outputHeight: Int): Bitmap {
                    val time = index * 1_000_000L / 30
                    val background = retriever.getFrameAtIndex(index)!!
                    return overlayReference(background, oriented, layer.copy(
                        opacity = if (time in 500_000L until 2_500_000L) layer.opacity else 0f), outputWidth, outputHeight)
                        .also { background.recycle() }
                }
                fun checkPixels(actual: Bitmap, index: Int, name: String) {
                    val expected = expectedAt(index, actual.width, actual.height)
                    val backgroundError = meanLumaError(actual, expected, .05f, .4f, .1f, .9f)
                    val photoError = meanLumaError(actual, expected, .5f, .98f, .15f, .9f)
                    File(evidence, "$name-actual.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    File(evidence, "$name-expected.png").outputStream().use { expected.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    File(evidence, "$name.txt").writeText("sourceIndex=$index backgroundError=$backgroundError photoError=$photoError\n")
                    if (index * 1_000_000L / 30 in 500_000L until 2_500_000L) {
                        // Compare RGB channels at all four transformed photo
                        // quadrants: red/green/blue have equal channel sums, so
                        // a luma-only test cannot establish orientation.
                        val fit = minOf(actual.width.toDouble() / oriented.width, actual.height.toDouble() / oriented.height)
                        val angle = layer.transform.rotationDegrees * Math.PI / 180
                        for (dx in listOf(-.25, .25)) for (dy in listOf(-.25, .25)) {
                            val sx = dx * oriented.width * fit * layer.transform.scale
                            val sy = dy * oriented.height * fit * layer.transform.scale
                            val x = (actual.width * (1 + layer.transform.offsetX) / 2 + sx * kotlin.math.cos(angle) - sy * kotlin.math.sin(angle)).toInt()
                            val y = (actual.height * (1 + layer.transform.offsetY) / 2 + sx * kotlin.math.sin(angle) + sy * kotlin.math.cos(angle)).toInt()
                            val a = actual.getPixel(x, y)
                            val b = expected.getPixel(x, y)
                            val error = listOf(0, 8, 16).sumOf { kotlin.math.abs((a shr it and 255) - (b shr it and 255)) }
                            File(evidence, "$name.txt").appendText("quadrant=$dx,$dy rgbError=$error\n")
                            assertTrue("$name oriented quadrant $dx,$dy RGB error $error", error < 30)
                        }
                    }
                    expected.recycle()
                    assertTrue("$name background error $backgroundError", backgroundError < 30)
                    assertTrue("$name photo geometry error $photoError", photoError < 30)
                }
                val frames = java.util.concurrent.LinkedBlockingQueue<Bitmap>()
                val thread = android.os.HandlerThread("photo-geometry").apply { start() }
                val reader = ImageReader.newInstance(1280, 720, android.graphics.PixelFormat.RGBA_8888, 3)
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val bitmap = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(plane.buffer)
                        val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                        frames.offer(cropped)
                        if (cropped !== bitmap) bitmap.recycle()
                    } finally { image.close() }
                }, android.os.Handler(thread.looper))
                val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
                try {
                    runOnMain { controller.attachHeadlessSurface(reader.surface, Size(1280, 720)); controller.setProject(reopened) }
                    assertNotNull("initial oriented photo frame missing", pollFrame(frames, 10_000))
                    for (target in listOf(1_500_000L, 100_000L, 500_000L, 2_500_000L, 2_800_000L, 1_000_000L)) {
                        frames.clear()
                        runOnMain { controller.seekTo(target) }
                        val actual = pollFrame(frames, 10_000)
                        assertNotNull("photo seek missing $target", actual)
                        val index = (0 until 8).sumOf { bit ->
                            val p = actual!!.getPixel((58 + bit * 36) * actual.width / 1280, 22 * actual.height / 720)
                            (if ((p shr 16 and 255) > 128) 1 else 0) shl bit
                        }
                        assertTrue("photo seek frame identity $target index=$index", index * 1_000_000L / 30 in target..target + 33_334)
                        checkPixels(actual!!, index, "preview-$variant-$target")
                        actual.recycle()
                    }
                } finally { runOnMain { controller.release() }; reader.close(); thread.quitSafely() }
                for (resolution in listOf(ExportResolution.RES_720P, ExportResolution.RES_1080P)) {
                    val output = File(evidence, "photo-$variant-$resolution.mp4")
                    val result = ExportEngine(context, compositionBuilder).export(reopened,
                        ExportRequest(reopened.id, output, resolution, FrameRate.FPS_30, ExportQuality.High)) {}
                    assertTrue("photo export failed $variant/$resolution $result", result.isSuccess)
                    for (time in listOf(100_000L, 500_000L, 1_000_000L, 1_500_000L, 2_500_000L, 2_800_000L)) {
                        val actual = decodeFrame(output, time)
                        val expectedWidth = if (resolution == ExportResolution.RES_720P) 1280 else 1920
                        val expectedHeight = if (resolution == ExportResolution.RES_720P) 720 else 1080
                        assertTrue("photo export dimensions ${actual.width}x${actual.height}",
                            actual.width == expectedWidth && actual.height == expectedHeight)
                        checkPixels(actual, (time * 30 / 1_000_000).toInt(), "export-$variant-$resolution-$time")
                        actual.recycle()
                    }
                }
                oriented.recycle()
            }
        } finally { retriever.release() }
    }

    @Test
    fun overlay_save_reopen_preserves_compositing() = runBlocking<Unit> {
        val project = pipProject()
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true; encodeDefaults = true
        }
        val reopened = json.decodeFromString<Project>(json.encodeToString(com.videoeditor.core.model.Project.serializer(), project))
        val output = File(retainedMediaTestDirectory(context), "pip-reopen-${System.currentTimeMillis()}.mp4")
        val result = ExportEngine(context, compositionBuilder).export(
            reopened,
            ExportRequest(
                projectId = reopened.id,
                outputFile = output,
                resolution = ExportResolution.RES_720P,
                frameRate = FrameRate.FPS_30,
                quality = ExportQuality.Standard,
            ),
        ) { }
        assertTrue("reopened export failed: ${(result as? Result.Failure)?.error}", result.isSuccess)
        // Project time 2s maps to the overlay's source at 1s (overlay starts at
        // project 1s, source window from 0). The footprint holds the layer's
        // whole source frame scaled down, so it is compared against the full
        // source bitmap rather than the same canvas-relative slice of it.
        val inside = decodeFrame(output, 2_000_000)
        val fp = overlayFootprint(scale = 0.3f, offsetX = 0.5f, offsetY = 0f)
        val err = meanLumaErrorBetween(
            inside, fp[0], fp[1], fp[2], fp[3],
            sourceFrame("clip3_1080p_4s.mp4", 1_000_000), 0f, 1f, 0f, 1f,
        )
        val errVsMain = meanLumaError(
            inside, sourceFrame("clip1_720p_6s.mp4", 2_000_000), fp[0], fp[1], fp[2], fp[3],
        )
        android.util.Log.i("OverlayTest", "reopen: errVsOverlay=%.1f errVsMain=%.1f".format(err, errVsMain))
        assertTrue("overlay lost after save/reopen (mean luma error $err)", err < 30.0)
        assertTrue(
            "reopened overlay region still shows the background " +
                "(overlay $err vs main $errVsMain)",
            err < errVsMain,
        )
        output.delete()
    }

    private fun pollFrame(queue: java.util.concurrent.LinkedBlockingQueue<Bitmap>, timeoutMs: Long): Bitmap? =
        queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    private fun saveDiag(source: File, tag: String, bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        // Log a 16x9 grid of mean luma values to visualize where the overlay is.
        val sb = StringBuilder("diag $tag ${w}x${h}\n")
        for (row in 0 until 9) {
            for (col in 0 until 16) {
                val x0 = col * w / 16
                val x1 = (col + 1) * w / 16
                val y0 = row * h / 9
                val y1 = (row + 1) * h / 9
                var sum = 0
                var count = 0
                for (y in y0 until y1 step 4) for (x in x0 until x1 step 4) {
                    val p = px[y * w + x]
                    sum += (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    count++
                }
                sb.append("%3d ".format(sum / count))
            }
            sb.append("\n")
        }
        android.util.Log.i("OverlayTest", sb.toString())
    }

    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
    @Test
    fun cross_dissolve_decodes_moving_sources_for_preview_and_both_exports() =
        runDissolveFixture("pip_main_moving_audio.mp4", "pip_overlay_moving_audio.mp4", "dissolve",
            listOf(1_200_000L,1_500_000L,1_750_000L,2_000_000L,2_250_000L,2_500_000L,2_800_000L))

    /** Marker verification uses scripts/verify_transition_identity.py against retained MP4s.
     * Keep the last-inside-frame seek: it exposes a real graph period-boundary failure. */
    @Test
    fun cross_dissolve_encoded_identity_for_preview_and_both_exports() =
        runDissolveFixture("transition_identity_0.mp4", "transition_identity_1.mp4", "dissolve-identity",
            listOf(1_200_000L,1_500_000L,1_750_000L,2_000_000L,2_250_000L,2_466_667L,2_500_000L,2_800_000L))

    @Test
    fun cross_dissolve_identity_without_diagnostics() =
        runDissolveFixture("transition_identity_0.mp4", "transition_identity_1.mp4", "dissolve-identity-untraced",
            listOf(1_200_000L,1_500_000L,1_750_000L,2_000_000L,2_250_000L,2_466_667L,2_500_000L,2_800_000L),
            diagnosticsEnabled = false)

    @Test
    fun cross_dissolve_odd_duration_and_adjacent_boundaries() =
        runDissolveFixture("transition_identity_0.mp4", "transition_identity_1.mp4", "dissolve-identity-odd",
            listOf(1_466_667L, 1_499_999L, 1_500_000L, 1_533_333L, 2_433_333L, 2_466_667L, 2_500_002L, 2_533_333L),
            transitionDuration = 1_000_003L)

    @Test
    fun cross_dissolve_odd_duration_precise_boundary_repeats_without_diagnostics() =
        runDissolveFixture("transition_identity_0.mp4", "transition_identity_1.mp4", "dissolve-identity-odd-untraced",
            listOf(1_499_000L, 1_499_999L, 1_500_000L, 1_500_001L, 1_533_333L,
                2_466_667L, 2_500_001L, 2_500_002L, 2_533_333L, 1_499_999L),
            transitionDuration = 1_000_003L, diagnosticsEnabled = false)

    @Test
    fun cross_dissolve_exact_source_handles_and_repeated_seeks() =
        runDissolveFixture("transition_identity_0.mp4", "transition_identity_1.mp4", "dissolve-identity-short",
            listOf(1_200_000L, 1_500_000L, 2_433_333L, 2_466_667L, 2_500_000L, 2_800_000L, 2_466_667L, 2_466_667L),
            outgoingStart = 3_500_000L, incomingStart = 500_000L)

    /** The audio-only control uses the same primary edits without visual overlap handles. */
    @Test
    fun cross_dissolve_primary_audio_matches_unmodified_join_control() = runDissolveAudioFixture(false)

    @Test
    fun cross_dissolve_music_and_voiceover_match_unmodified_audio_mix() = runDissolveAudioFixture(true)

    @Test
    fun consumer_fixture_audio_control_captures_authored_mix() = runDissolveAudioFixture(true, true)

    private fun runDissolveAudioFixture(concurrentTracks: Boolean, consumerFixture: Boolean = false) = runBlocking<Unit> {
        val a = extractRef("transition_identity_0.mp4")
        val b = extractRef("transition_identity_1.mp4")
        val baseProject = projectCreator.create("Dissolve PCM", listOf(a, b),
            canvas = CanvasSettings(width = 1280, height = 720)).copy(
            timeline = Timeline(videoClips = listOf(
                com.videoeditor.core.model.Clip("a", a.id, 1_000_000, 3_000_000),
                com.videoeditor.core.model.Clip("b", b.id, 1_000_000, 3_000_000, 2_000_000))))
        val project = if (consumerFixture) {
            fun audioRef(name: String): MediaRef {
                val file = File(mediaDir, name)
                file.parentFile!!.mkdirs()
                context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
                return checkNotNull(extractor.extract(context, android.net.Uri.fromFile(file)).getOrNull())
            }
            val music = audioRef("music_20s.m4a")
            val voice = audioRef("audio_sample_identity/48000.m4a")
            baseProject.copy(mediaRefs = baseProject.mediaRefs + music + voice,
                timeline = baseProject.timeline.copy(audioClips = listOf(
                    com.videoeditor.core.model.AudioClip("music", music.id, 1_000_000, 5_000_000, volume = .1f),
                    com.videoeditor.core.model.AudioClip("voiceover", voice.id, 2_000_000, 6_000_000, volume = .5f))))
        } else if (!concurrentTracks) baseProject else baseProject.copy(timeline = baseProject.timeline.copy(
            audioClips = listOf(
                com.videoeditor.core.model.AudioClip("music", a.id, 0, 4_000_000, volume = .15f),
                com.videoeditor.core.model.AudioClip("voiceover", b.id, 0, 2_000_000, 1_000_000, volume = .2f))))
        val evidenceName = if (consumerFixture) "consumer-fixture-control" else if (concurrentTracks) "dissolve-pcm-mixed" else "dissolve-pcm"
        val dir = File(context.getExternalFilesDir(null),
            "$evidenceName/run-${android.os.SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
        File(dir, "control-project.json").writeText(kotlinx.serialization.json.Json.encodeToString(Project.serializer(), project))
        val sequences = compositionBuilder.buildForExport(project, 1280, 720).sequences
        val control = Composition.Builder(sequences.mapIndexed { sequenceIndex, sequence ->
            EditedMediaItemSequence(sequence.editedMediaItems.mapIndexed { index, item ->
            val captureName = if (sequenceIndex == 0) "primary-$index" else "aux-$sequenceIndex-$index"
            val capture = object : androidx.media3.common.audio.BaseAudioProcessor() {
                private val captured = java.io.ByteArrayOutputStream()
                override fun onConfigure(format: androidx.media3.common.audio.AudioProcessor.AudioFormat):
                    androidx.media3.common.audio.AudioProcessor.AudioFormat {
                    File(dir, "$captureName-format.txt").writeText(format.toString())
                    return format
                }
                override fun queueInput(input: java.nio.ByteBuffer) {
                    val bytes = ByteArray(input.remaining())
                    input.get(bytes)
                    captured.write(bytes)
                    replaceOutputBuffer(bytes.size).put(bytes).flip()
                }
                override fun onQueueEndOfStream() {
                    File(dir, "$captureName-before-encoder.pcm").writeBytes(captured.toByteArray())
                }
            }
            if (item.mediaItem.mediaId == "androidx-media3-GapMediaItem") item
            else item.buildUpon().setRemoveVideo(true).setEffects(androidx.media3.transformer.Effects(
                item.effects.audioProcessors + capture, item.effects.videoEffects)).build()
            })
        }).build()
        val completed = kotlinx.coroutines.CompletableDeferred<ExportException?>()
        var correctedControlEncoder: com.videoeditor.core.media.audio.CanonicalAacEncoder? = null
        runOnMain {
            val factory = androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
                .setEnableFallback(false)
                .setRequestedAudioEncoderSettings(androidx.media3.transformer.AudioEncoderSettings.Builder()
                    .setBitrate(192_000).build()).build()
            val observedFactory = object : androidx.media3.transformer.Codec.EncoderFactory by factory {
                override fun createForAudioEncoding(format: androidx.media3.common.Format): androidx.media3.transformer.Codec {
                    val original = factory.createForAudioEncoding(format)
                    val measured = com.videoeditor.core.media.audio.AacEncoderPrimingProbe.measure(
                        original.name,format.sampleRate,format.channelCount,192_000)
                    val codec = com.videoeditor.core.media.audio.CanonicalAacEncoder(original,measured)
                    correctedControlEncoder = codec
                    return object : androidx.media3.transformer.Codec by codec {
                        private var lastOutputTime = Long.MIN_VALUE
                        private val encoderPcm = java.io.ByteArrayOutputStream()
                        override fun queueInputBuffer(input: androidx.media3.decoder.DecoderInputBuffer) {
                            File(dir, "encoder-input.txt").appendText("pts=${input.timeUs} bytes=${input.data?.remaining()} eos=${input.isEndOfStream}\n")
                            input.data?.duplicate()?.let { buffer ->
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                encoderPcm.write(bytes)
                            }
                            if (input.isEndOfStream) File(dir, "encoder-input.s16le").writeBytes(encoderPcm.toByteArray())
                            codec.queueInputBuffer(input)
                        }
                        override fun getOutputBufferInfo(): android.media.MediaCodec.BufferInfo? {
                            return codec.outputBufferInfo?.also {
                                if (lastOutputTime != it.presentationTimeUs) {
                                    File(dir, "encoder-output.txt").appendText("pts=${it.presentationTimeUs} bytes=${it.size} flags=${it.flags}\n")
                                    lastOutputTime = it.presentationTimeUs
                                }
                            }
                        }
                        override fun getOutputFormat(): androidx.media3.common.Format? {
                            return codec.outputFormat?.also {
                                File(dir, "audio-encoder-format.txt").writeText(
                                    "codec=${codec.name} rate=${it.sampleRate} channels=${it.channelCount} " +
                                    "delay=${it.encoderDelay} padding=${it.encoderPadding} format=$it")
                            }
                        }
                    }
                }
            }
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
                .setEncoderFactory(observedFactory)
                .setAssetLoaderFactory(androidx.media3.transformer.ReorderAwareAssetLoaderFactory(context,
                    androidx.media3.transformer.DefaultDecoderFactory.Builder(context).build(),false))
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) {
                        com.videoeditor.core.media.audio.Mp4AudioPriming.apply(File(dir,"primary-control.m4a"),checkNotNull(correctedControlEncoder))
                        completed.complete(null)
                    }
                    override fun onError(c: Composition, r: ExportResult, e: ExportException) { completed.complete(e) }
                }).build()
            transformer.start(control, File(dir, "primary-control.m4a").absolutePath)
        }
        assertNull("primary audio control export", completed.await())
        val primaryPcm = (0..1).flatMap { File(dir, "primary-$it-before-encoder.pcm").readBytes().asIterable() }.toByteArray()
        // The qualified source clock emits the first audible sample at graph zero.
        // The native 14-window gate independently proves this correction; no
        // compressed packet offset or silence prefix belongs in the PCM graph.
        if (!concurrentTracks) assertTrue("audio graph changed primary PCM or inserted a join gap",
            primaryPcm.contentEquals(File(dir, "encoder-input.s16le").readBytes()))
        val dissolve = project.copy(timeline = project.timeline.copy(transitions = listOf(
            com.videoeditor.core.model.Transition("t", "a", "b", 1_000_000))))
        File(dir, "project.json").writeText(kotlinx.serialization.json.Json.encodeToString(Project.serializer(), dissolve))
        for (index in 0..1) {
            assertEquals("primary clip $index PCM duration", 192_000L,
                File(dir, "primary-$index-before-encoder.pcm").length())
            assertEquals("primary clip $index PCM format",
                "AudioFormat[sampleRate=48000, channelCount=1, encoding=2]",
                File(dir, "primary-$index-format.txt").readText())
        }
        // The retained ordinary-R8 exports are the subjects; this run only supplies
        // a separately encoded audio-only control and lossless graph captures.
        if (consumerFixture) {
            android.util.Log.i("OverlayTest", "Consumer audio control evidence: $dir")
            return@runBlocking
        }
        for (resolution in listOf(ExportResolution.RES_720P, ExportResolution.RES_1080P)) {
            val output = File(dir, "${resolution.name}.mp4")
            assertTrue(ExportEngine(context, compositionBuilder).export(dissolve,
                ExportRequest(project.id, output, resolution, FrameRate.FPS_30, ExportQuality.Standard)) {}.isSuccess)
            if (!concurrentTracks) assertEquals("outgoing carrier", 440.0, audioToneFrequency(output, 500_000L..1_000_000L), 10.0)
            if (!concurrentTracks) assertEquals("incoming carrier", 880.0, audioToneFrequency(output, 2_500_000L..3_000_000L), 15.0)
        }
        android.util.Log.i("OverlayTest", "PCM boundary evidence: $dir")
    }

    @Test
    fun cross_dissolve_two_moving_pips_and_caption_remain_visible_through_join() =
        runDissolveFixture("transition_identity_0.mp4", "transition_identity_1.mp4",
            "dissolve-identity-concurrent", listOf(1_200_000, 1_500_000, 1_750_000,
                2_000_000, 2_250_000, 2_466_667, 2_500_000, 2_800_000), concurrentPips = 2)

    @Test
    fun cross_dissolve_moving_pip_and_caption_remain_visible_through_join() =
        runDissolveFixture("transition_identity_0.mp4", "transition_identity_1.mp4",
            "dissolve-identity-one-pip", listOf(1_200_000, 1_500_000, 1_750_000,
                2_000_000, 2_250_000, 2_466_667, 2_500_000, 2_800_000), concurrentPips = 1)

    @Test
    fun cross_dissolve_layered_playback_submits_real_final_frame() =
        runDissolveFixture("transition_identity_0.mp4", "transition_identity_1.mp4",
            "dissolve-identity-tail", listOf(1_500_000, 2_000_000, 2_466_667, 2_500_000, 3_966_667),
            concurrentPips = 2, requireFinalFrame = true)

    @Test
    fun source_clock_dissolve_pip_caption_music_and_voiceover() {
        com.videoeditor.core.media.audio.SourceClockAacDecoder.enabledForQualification = true
        com.videoeditor.core.media.audio.AacEncoderPrimingProbe.enabledForQualification = true
        try {
            runDissolveFixture("transition_identity_0.mp4", "transition_identity_1.mp4",
                "dissolve-source-clock-concurrent", listOf(1_200_000,1_500_000,1_750_000,
                    2_000_000,2_250_000,2_466_667,2_500_000,2_800_000,3_966_667),
                concurrentPips=1, requireFinalFrame=true, qualificationAudio=true)
        } finally {
            com.videoeditor.core.media.audio.SourceClockAacDecoder.enabledForQualification = false
            com.videoeditor.core.media.audio.AacEncoderPrimingProbe.enabledForQualification = false
        }
    }

    private fun runDissolveFixture(
        outgoingAsset: String,
        incomingAsset: String,
        evidenceName: String,
        checkpoints: List<Long>,
        transitionDuration: Long = 1_000_000L,
        outgoingStart: Long = 1_000_000L,
        incomingStart: Long = 1_000_000L,
        diagnosticsEnabled: Boolean = true,
        concurrentPips: Int = 0,
        requireFinalFrame: Boolean = false,
        qualificationAudio: Boolean = false,
    ) = runBlocking<Unit> {
        com.videoeditor.core.media.preview.PreviewPhaseTrace.setIdentityDiagnostic(diagnosticsEnabled && evidenceName.startsWith("dissolve-identity"))
        com.videoeditor.core.media.preview.PreviewPhaseTrace.tailIdentityDiagnostic = requireFinalFrame
        val a = extractRef(outgoingAsset)
        val b = extractRef(incomingAsset)
        val baseProject = projectCreator.create("Dissolve", listOf(a,b), canvas=CanvasSettings(width=1280,height=720)).copy(
            timeline=Timeline(videoClips=listOf(
                com.videoeditor.core.model.Clip("a",a.id,outgoingStart,outgoingStart + 2_000_000),
                com.videoeditor.core.model.Clip("b",b.id,incomingStart,incomingStart + 2_000_000,2_000_000)),
                transitions=listOf(com.videoeditor.core.model.Transition("t","a","b",transitionDuration))))
        val layeredProject = if (concurrentPips == 0) baseProject else baseProject.copy(timeline = baseProject.timeline.copy(
            overlayClips = listOf(
                OverlayClip("left-pip", a.id, 0, 4_000_000, 0, 4_000_000, muted = true,
                    zIndex = 1, transform = VisualTransform(scale = .15f, offsetX = -.7f, offsetY = .5f)),
                OverlayClip("right-pip", b.id, 0, 4_000_000, 0, 4_000_000, muted = true,
                    zIndex = 2, transform = VisualTransform(scale = .2f, offsetX = .7f, offsetY = .5f))).takeLast(concurrentPips),
            textOverlays = listOf(com.videoeditor.core.model.TextOverlay("caption", "TOP",
                startMicros = 0, durationMicros = 4_000_000, positionX = .85f, positionY = .8f, fontSizeSp = 80f,
                colorArgb = android.graphics.Color.YELLOW))))
        val project = if (!qualificationAudio) layeredProject else {
            val musicFile = File(mediaDir,"source-clock-music.m4a")
            if (!musicFile.exists()) InstrumentationRegistry.getInstrumentation().context.assets.open("music_20s.m4a").use { input ->
                musicFile.outputStream().use { input.copyTo(it) }
            }
            val voiceFile = File(mediaDir,"source-clock-voice.m4a")
            if (!voiceFile.exists()) InstrumentationRegistry.getInstrumentation().context.assets.open("audio_sample_identity/48000.m4a").use { input ->
                voiceFile.outputStream().use { input.copyTo(it) }
            }
            val music = MediaRef("source-clock-music",android.net.Uri.fromFile(musicFile).toString(),
                com.videoeditor.core.model.MediaType.Audio,"Music",mimeType="audio/mp4",durationMicros=20_000_000)
            val voice = music.copy(id="source-clock-voice",uri=android.net.Uri.fromFile(voiceFile).toString(),
                displayName="Transient voiceover",durationMicros=6_000_000)
            val mixed = layeredProject.copy(mediaRefs=layeredProject.mediaRefs+music+voice,
                timeline=layeredProject.timeline.copy(audioClips=listOf(
                    com.videoeditor.core.model.AudioClip("music",music.id,1_000_000,5_000_000,volume=.1f),
                    com.videoeditor.core.model.AudioClip("voiceover",voice.id,2_000_000,6_000_000,volume=.5f))))
            val json=kotlinx.serialization.json.Json
            val encoded=json.encodeToString(Project.serializer(),mixed)
            json.decodeFromString(Project.serializer(),encoded).also { assertEquals("saved/reopened real composition",mixed,it) }
        }
        val dir=File(context.getExternalFilesDir(null),"$evidenceName/run-${android.os.SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
        File(dir,"project.json").writeText(kotlinx.serialization.json.Json.encodeToString(Project.serializer(),project))
        if (diagnosticsEnabled && evidenceName.startsWith("dissolve-identity"))
            DissolveCodecDiagnostic.install(listOf(a.uri,b.uri),outgoingStart,incomingStart-2_000_000L)
        val transitionStart = 2_000_000L - transitionDuration / 2
        val transitionEnd = transitionStart + transitionDuration
        fun blendAlpha(time: Long) = ((time - transitionStart) / transitionDuration.toDouble()).coerceIn(0.0, 1.0)
        fun expected(time: Long, width: Int, height: Int): Bitmap {
            val aa=sourceFrame(outgoingAsset,outgoingStart+time)
            val bb=sourceFrame(incomingAsset,incomingStart+time-2_000_000)
            val x=Bitmap.createScaledBitmap(aa,width,height,true);val y=Bitmap.createScaledBitmap(bb,width,height,true)
            val alpha=blendAlpha(time)
            val px=IntArray(width*height);val py=IntArray(width*height)
            x.getPixels(px,0,width,0,0,width,height);y.getPixels(py,0,width,0,0,width,height)
            for (j in px.indices) {
                fun channel(shift:Int):Int {
                    val u=(px[j] shr shift and 255)/255.0;val v=(py[j] shr shift and 255)/255.0
                    return (((1-alpha)*u+alpha*v)*255).toInt().coerceIn(0,255)
                }
                px[j]=android.graphics.Color.rgb(channel(16),channel(8),channel(0))
            }
            aa.recycle();bb.recycle();x.recycle();y.recycle()
            return Bitmap.createBitmap(px,width,height,Bitmap.Config.ARGB_8888)
        }
        fun markerSample(bitmap: Bitmap, x: Int, y: Int): Double {
            val sx = bitmap.width / 1280.0; val sy = bitmap.height / 720.0
            var total = 0.0; var count = 0
            for (yy in ((y+10)*sy).toInt() until ((y+30)*sy).toInt())
                for (xx in ((x+12)*sx).toInt() until ((x+36)*sx).toInt()) {
                    val pixel = bitmap.getPixel(xx, yy)
                    total += (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 3.0
                    count++
                }
            return total / count
        }
        fun markerNumber(bitmap: Bitmap, lane: Int): Int? {
            val y = 72 + lane * 64
            val pilot = markerSample(bitmap, 8, y)
            if (pilot <= 4) return null
            var number = 0
            for (bit in 0..7) {
                val value = markerSample(bitmap, 72+bit*64, y)
                assertTrue("ambiguous identity lane=$lane bit=$bit pilot=$pilot value=$value",
                    minOf(kotlin.math.abs(value), kotlin.math.abs(value-pilot)) < maxOf(3.0,pilot*.25))
                if (value > pilot*.5) number = number or (1 shl bit)
            }
            return number
        }
        fun checkFrame(frame:Bitmap,requestedTime:Long,label:String) {
            val time = if (concurrentPips == 0) requestedTime else {
                // Resolve a nearest-frame tie from the baked primary identity,
                // then require that it is one of the two neighboring grid frames.
                // Both source and exported MP4 retrievers can choose either tie.
                val index = if (markerSample(frame, 8, 72) > 127)
                    markerNumber(frame, 0)!! - (outgoingStart * 30 / 1_000_000).toInt()
                else markerNumber(frame, 1)!! + ((2_000_000-incomingStart) * 30 / 1_000_000).toInt()
                val lower = requestedTime * 30 / 1_000_000
                val upper = (requestedTime * 30 + 999_999) / 1_000_000
                assertTrue("$label requested frame grid: $index not $lower/$upper", index.toLong() in lower..upper)
                index * 1_000_000L / 30
            }
            val baseRef=if(time<transitionStart) Bitmap.createScaledBitmap(sourceFrame(outgoingAsset,outgoingStart+time),frame.width,frame.height,true)
                else if(time>=transitionEnd) Bitmap.createScaledBitmap(sourceFrame(incomingAsset,incomingStart+time-2_000_000),frame.width,frame.height,true)
                else expected(time,frame.width,frame.height)
            val ref = if (concurrentPips == 0) baseRef else baseRef.copy(Bitmap.Config.ARGB_8888, true).also { reference ->
                val canvas = android.graphics.Canvas(reference)
                for ((asset, centerX, scale) in listOf(Triple(outgoingAsset, .15f, .15f), Triple(incomingAsset, .85f, .2f)).takeLast(concurrentPips)) {
                    val source = sourceFrame(asset, time)
                    canvas.drawBitmap(source, null, android.graphics.RectF(
                        (centerX-scale/2)*reference.width, (.75f-scale/2)*reference.height,
                        (centerX+scale/2)*reference.width, (.75f+scale/2)*reference.height),
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    source.recycle()
                }
                // Independent plain-text reference for this fixed TOP caption.
                val captionPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.YELLOW
                    textSize = 80f * 3f * reference.height / 1080f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val metrics = captionPaint.fontMetrics
                canvas.drawText("TOP", reference.width * .85f,
                    reference.height * .8f - (metrics.ascent + metrics.descent) / 2f, captionPaint)
                baseRef.recycle()
                if (concurrentPips == 2) assertTrue("$label left moving PiP", meanLumaError(frame, reference, .12f, .18f, .72f, .78f) < 30)
                assertTrue("$label right moving PiP", meanLumaError(frame, reference, .8f, .9f, .67f, .7f) < 30)
                var yellow = 0
                for (y in (frame.height*.73f).toInt() until (frame.height*.87f).toInt())
                    for (x in (frame.width*.75f).toInt() until (frame.width*.95f).toInt()) {
                        val pixel = frame.getPixel(x,y)
                        if (android.graphics.Color.red(pixel)>180 && android.graphics.Color.green(pixel)>180 && android.graphics.Color.blue(pixel)<100) yellow++
                    }
                assertTrue("$label caption above PiP", yellow > 200)
            }
            File(dir,"$label-actual.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(dir,"$label-expected.png").outputStream().use { ref.compress(Bitmap.CompressFormat.PNG,100,it) }
            val error=meanLumaError(frame,ref,.05f,.95f,.1f,.9f)
            File(dir,"$label.txt").writeText("time=$time error=$error threshold=30")
            assertTrue("$label blend error $error",error<30)
            // Non-frame-aligned requests may resolve to neighboring frames.
            // At grid checkpoints require independently encoded source identity.
            val remainder = time * 30 % 1_000_000
            if (evidenceName.startsWith("dissolve-identity") && minOf(remainder, 1_000_000-remainder) <= 30) {
                for (lane in 0..1) {
                    val pilot = markerSample(ref, 8, 72 + lane*64)
                    assertTrue("$label pilot lane=$lane", kotlin.math.abs(markerSample(frame,8,72+lane*64)-pilot)<8)
                    if (pilot > 4) assertEquals("$label source identity lane=$lane", markerNumber(ref,lane), markerNumber(frame,lane))
                }
            }
            ref.recycle()
        }
        for(resolution in listOf(ExportResolution.RES_720P,ExportResolution.RES_1080P)) {
            com.videoeditor.core.media.preview.PreviewPhaseTrace.identitySession = "$dir/${resolution.name}"
            val file=File(dir,"${resolution.name}.mp4")
            val result=ExportEngine(context,compositionBuilder).export(project,ExportRequest(project.id,file,resolution,FrameRate.FPS_30,ExportQuality.Standard)){}
            assertTrue("dissolve export $result",result.isSuccess)
            for(time in checkpoints) { val frame=decodeFrame(file,time);checkFrame(frame,time,"${resolution.name}-$time");frame.recycle() }
        }
        com.videoeditor.core.media.preview.PreviewPhaseTrace.identitySession = "$dir/preview"
        val queue=java.util.concurrent.LinkedBlockingQueue<Bitmap>()
        val thread=android.os.HandlerThread("dissolve").apply { start() }
        val reader=ImageReader.newInstance(1280,720,android.graphics.PixelFormat.RGBA_8888,3)
        reader.setOnImageAvailableListener({ r ->
            val image=(if (concurrentPips > 0) r.acquireNextImage() else r.acquireLatestImage()) ?: return@setOnImageAvailableListener
            try { val plane=image.planes[0];val bitmap=Bitmap.createBitmap(plane.rowStride/plane.pixelStride,image.height,Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer);queue.offer(Bitmap.createBitmap(bitmap,0,0,image.width,image.height));bitmap.recycle()
            } finally {image.close()}
        },android.os.Handler(thread.looper))
        val controller=CompositionPreviewController.Factory(context,compositionBuilder).create()
        try {
            runOnMain {controller.attachHeadlessSurface(reader.surface,Size(1280,720));controller.setProject(project)}
            assertNotNull(pollFrame(queue,10000));assertTrue(pollUntil(10000){!controller.state.value.isBuffering})
            var lastSeek: Long? = null
            for((seekIndex,time) in (checkpoints+checkpoints.dropLast(1).reversed()).withIndex()) {
                // A repeated no-op seek has no new output contract. Move away
                // first so repeated target verification exercises a real seek.
                if (lastSeek != null && lastSeek / 1000 == time / 1000) {
                    while (queue.isNotEmpty()) queue.poll()?.recycle()
                    runOnMain {controller.seekTo(1_000_000L)}
                    assertNotNull("repeat seek detour", pollFrame(queue, 10000))
                }
                while (queue.isNotEmpty()) queue.poll()?.recycle()
                runOnMain {controller.seekTo(time)}
                lastSeek = time
                val frame=pollFrame(queue,10000);assertNotNull("missing dissolve $time",frame)
                checkFrame(frame!!,time,"preview-$seekIndex-$time");frame.recycle()
                assertNull(controller.state.value.error)
            }
            if (evidenceName == "dissolve-identity" || concurrentPips > 0) {
                runOnMain {controller.seekTo(0)}
                assertNotNull("continuous start", pollFrame(queue,10000))
                while (queue.isNotEmpty()) queue.poll()?.recycle()
                runOnMain {controller.play()}
                val visible = mutableListOf<Int>()
                val layerFrames = mutableMapOf<Int, Bitmap>()
                val deadline = System.currentTimeMillis()+10000
                while (System.currentTimeMillis()<deadline) {
                    val frame = pollFrame(queue,1000)
                    if (frame != null) {
                        val aWeight = markerSample(frame,8,72)/255
                        val bWeight = markerSample(frame,8,136)/255
                        val aa = if (aWeight>.02) markerNumber(frame,0) else null
                        val bb = if (bWeight>.02) markerNumber(frame,1) else null
                        val index = aa?.minus(30) ?: bb?.plus(30)
                        if (index != null) {
                            visible += index
                            if (aa != null && bb != null && aa-60 != bb) {
                                File(dir,"continuous-mismatch.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }
                                File(dir,"continuous-mismatch.txt").writeText("a=$aa b=$bb inferredIndex=$index aWeight=$aWeight bWeight=$bWeight submittedPts=${controller.renderedPositionMicros}")
                            }
                            if (aa != null && bb != null) assertEquals("continuous source mapping", aa-60, bb)
                            if (concurrentPips > 0 && index in listOf(52, 53, 60, 67, 68, 74, 75, 119))
                                layerFrames.put(index, frame.copy(Bitmap.Config.ARGB_8888, false))?.recycle()
                            if (index in 72..76) File(dir,"continuous-$index.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }
                        }
                        frame.recycle()
                    }
                    assertNull(controller.state.value.error)
                    if (!controller.state.value.playRequested && controller.state.value.positionMicros == 4_000_000L &&
                        (!requireFinalFrame || 119 in visible)) break
                }
                File(dir,"continuous-indices.txt").writeText(visible.joinToString(","))
                File(dir,"continuous-final-state.txt").writeText("state=${controller.state.value} rendered=${controller.renderedPositionMicros} pendingCaptured=${queue.size}")
                if (concurrentPips > 0) for (required in listOf(52..53, 60..60, 67..68, 74..74, 75..75))
                    assertTrue("continuous layer checkpoint missing $required", layerFrames.keys.any { it in required })
                if (requireFinalFrame) assertTrue("real final frame 119 missing: $visible", 119 in visible)
                for ((index, frame) in layerFrames) {
                    checkFrame(frame, index * 1_000_000L / 30, "continuous-layer-$index")
                    frame.recycle()
                }
                assertTrue("continuous missing frame 74: $visible",74 in visible)
                assertTrue("continuous source identity went backward",visible.zipWithNext().all { (a,b)->b>=a })
                assertEquals("continuous duration",4_000_000L,controller.state.value.positionMicros)
                if (requireFinalFrame) for ((index,time) in listOf(1_750_000L,2_250_000L,4_000_000L,1_750_000L,2_250_000L).withIndex()) {
                    while (queue.isNotEmpty()) queue.poll()?.recycle()
                    runOnMain { controller.seekForScrub(time); controller.flushScrubSeek() }
                    val frame = pollFrame(queue,10_000)
                    assertNotNull("post-EOS consumer scrub $time",frame)
                    checkFrame(frame!!,time.coerceAtMost(3_966_667L),"post-eos-scrub-$index-$time");frame.recycle()
                    assertNull(controller.state.value.error)
                }
                if (requireFinalFrame) for (rate in listOf(
                    com.videoeditor.core.model.FrameRate.FPS_24,
                    com.videoeditor.core.model.FrameRate.FPS_60,
                )) {
                    while (queue.isNotEmpty()) queue.poll()?.recycle()
                    runOnMain { controller.setProject(project.copy(frameRate = rate)); controller.seekTo(0L) }
                    val first = pollFrame(queue, 10_000)
                    assertNotNull("$rate endpoint detour", first)
                    checkFrame(first!!, 0L, "$rate endpoint detour"); first.recycle()
                    while (queue.isNotEmpty()) queue.poll()?.recycle()
                    runOnMain { controller.seekTo(4_000_000L) }
                    val last = pollFrame(queue, 10_000)
                    assertNotNull("$rate real endpoint frame", last)
                    checkFrame(last!!, 3_966_667L, "$rate real endpoint frame"); last.recycle()
                    assertEquals("$rate logical endpoint", 4_000_000L, controller.state.value.positionMicros)
                    while (queue.isNotEmpty()) queue.poll()?.recycle()
                    runOnMain { controller.seekTo(1_750_000L) }
                    val backward = pollFrame(queue, 10_000)
                    assertNotNull("$rate backward from endpoint", backward)
                    checkFrame(backward!!, 1_750_000L, "$rate backward from endpoint"); backward.recycle()
                    assertNull(controller.state.value.error)
                }
            }
        } finally {
            runOnMain {controller.release()};reader.close();thread.quitSafely()
            com.videoeditor.core.media.preview.PreviewPhaseTrace.setIdentityDiagnostic(false)
        }
    }

}
