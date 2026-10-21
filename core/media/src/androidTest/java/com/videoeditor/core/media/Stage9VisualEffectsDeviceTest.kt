package com.videoeditor.core.media

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.ExportEngine
import com.videoeditor.core.media.export.ExportRequest
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class Stage9VisualEffectsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val extractor = MediaMetadataExtractor()
    private val projectCreator = ProjectCreator()
    private val compositionBuilder = CompositionBuilder()

    private lateinit var fixtureDir: File
    private lateinit var clip1Ref: MediaRef
    private lateinit var clip2Ref: MediaRef
    private lateinit var greenRef: MediaRef

    @Before
    fun setUp() = runBlocking {
        fixtureDir = File(
            context.filesDir,
            "stage9-fixtures/run-${android.os.SystemClock.elapsedRealtimeNanos()}"
        ).apply { mkdirs() }

        fun copyAsset(name: String): File {
            val file = File(fixtureDir, name)
            if (!file.exists()) {
                context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            return file
        }

        val f1 = copyAsset("clip1_720p_6s.mp4")
        val f2 = copyAsset("clip2_480p_5s.mp4")
        val fg = copyAsset("probe_green_720p_6s.mp4")

        clip1Ref = extractor.extract(context, Uri.fromFile(f1)).getOrNull()!!
        clip2Ref = extractor.extract(context, Uri.fromFile(f2)).getOrNull()!!
        greenRef = extractor.extract(context, Uri.fromFile(fg)).getOrNull()!!
    }

    @After
    fun tearDown() {
        fixtureDir.deleteRecursively()
    }

    private fun retrieveFrame(file: File, timeUs: Long): Bitmap {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
        } finally {
            retriever.release()
        }
    }

    @Test
    fun colorAdjustments_and_imageEffects_render_and_export_with_pixel_verification() = runBlocking<Unit> {
        val base = projectCreator.create("EffectsTest", listOf(clip1Ref), canvas = CanvasSettings(width = 1280, height = 720))
        val plainProject = base.copy(
            timeline = base.timeline.copy(
                videoClips = listOf(
                    Clip("c1", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 2_000_000L)
                )
            )
        )
        val plainOut = File(fixtureDir, "plain.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                plainProject,
                ExportRequest(plainProject.id, plainOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )

        // Project with Color Adjustments + Sepia + Vignette
        val styledProject = plainProject.copy(
            timeline = plainProject.timeline.copy(
                videoClips = listOf(
                    Clip(
                        "c1", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 2_000_000L,
                        colorAdjustments = ColorAdjustments(
                            brightness = 0.2f,
                            contrast = 0.2f,
                            temperature = 0.3f,
                            vignette = 0.5f
                        ),
                        effects = listOf(
                            VisualEffect(
                                id = "fx-sepia",
                                type = VisualEffectType.Sepia,
                                intensity = 0.8f
                            )
                        )
                    )
                )
            )
        )
        val styledOut = File(fixtureDir, "styled.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                styledProject,
                ExportRequest(styledProject.id, styledOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )

        val plainBmp = retrieveFrame(plainOut, 1_000_000L)
        val styledBmp = retrieveFrame(styledOut, 1_000_000L)

        // Compare center pixel and corner pixel
        var diffSum = 0L
        var samples = 0
        var redSum = 0L
        var blueSum = 0L

        for (y in 50 until styledBmp.height - 50 step 20) {
            for (x in 50 until styledBmp.width - 50 step 20) {
                val pColor = plainBmp.getPixel(x, y)
                val sColor = styledBmp.getPixel(x, y)

                val pr = (pColor shr 16) and 0xFF
                val pg = (pColor shr 8) and 0xFF
                val pb = pColor and 0xFF

                val sr = (sColor shr 16) and 0xFF
                val sg = (sColor shr 8) and 0xFF
                val sb = sColor and 0xFF

                diffSum += abs(sr - pr) + abs(sg - pg) + abs(sb - pb)
                redSum += sr
                blueSum += sb
                samples++
            }
        }

        val avgDiff = diffSum.toDouble() / (samples * 3)
        assertTrue("Styled video must measurably differ from plain video: avgDiff=$avgDiff", avgDiff > 10.0)
        assertTrue("Sepia effect must produce warmer tones (Red > Blue): redSum=$redSum, blueSum=$blueSum", redSum > blueSum)

        // Vignette check: corner should be darker than center in styled image
        val centerPixel = styledBmp.getPixel(styledBmp.width / 2, styledBmp.height / 2)
        val cornerPixel = styledBmp.getPixel(10, 10)
        val centerLuma = ((centerPixel shr 16 and 0xFF) + (centerPixel shr 8 and 0xFF) + (centerPixel and 0xFF)) / 3
        val cornerLuma = ((cornerPixel shr 16 and 0xFF) + (cornerPixel shr 8 and 0xFF) + (cornerPixel and 0xFF)) / 3
        assertTrue("Vignette must darken the corners relative to center: cornerLuma=$cornerLuma, centerLuma=$centerLuma", cornerLuma <= centerLuma)

        plainBmp.recycle()
        styledBmp.recycle()
    }

    @Test
    fun blackAndWhite_and_invert_shaders_produce_deterministic_pixel_math() = runBlocking<Unit> {
        val base = projectCreator.create("ShaderMath", listOf(clip1Ref), canvas = CanvasSettings(width = 1280, height = 720))

        // 1. Black & White
        val bwProject = base.copy(
            timeline = base.timeline.copy(
                videoClips = listOf(
                    Clip(
                        "c1", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 1_500_000L,
                        effects = listOf(VisualEffect("bw", VisualEffectType.BlackAndWhite, intensity = 1.0f))
                    )
                )
            )
        )
        val bwOut = File(fixtureDir, "bw.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                bwProject,
                ExportRequest(bwProject.id, bwOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val bwBmp = retrieveFrame(bwOut, 500_000L)
        var maxChromaDiff = 0
        for (y in 100 until bwBmp.height - 100 step 30) {
            for (x in 100 until bwBmp.width - 100 step 30) {
                val c = bwBmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val diff = maxOf(abs(r - g), abs(g - b), abs(r - b))
                if (diff > maxChromaDiff) maxChromaDiff = diff
            }
        }
        assertTrue("Black and white must eliminate chromatic variance (R ≈ G ≈ B): max diff=$maxChromaDiff", maxChromaDiff <= 8)
        bwBmp.recycle()

        // 2. Color Invert
        val invertProject = base.copy(
            timeline = base.timeline.copy(
                videoClips = listOf(
                    Clip(
                        "c1", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 1_500_000L,
                        effects = listOf(VisualEffect("inv", VisualEffectType.InvertColor, intensity = 1.0f))
                    )
                )
            )
        )
        val invertOut = File(fixtureDir, "invert.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                invertProject,
                ExportRequest(invertProject.id, invertOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val invBmp = retrieveFrame(invertOut, 500_000L)

        // Plain for comparison
        val plainProject = base.copy(
            timeline = base.timeline.copy(
                videoClips = listOf(
                    Clip("c1", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 1_500_000L)
                )
            )
        )
        val plainOut = File(fixtureDir, "plain_inv_cmp.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                plainProject,
                ExportRequest(plainProject.id, plainOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val plainBmp = retrieveFrame(plainOut, 500_000L)

        var invertLumaSumError = 0L
        var count = 0
        for (y in 100 until invBmp.height - 100 step 30) {
            for (x in 100 until invBmp.width - 100 step 30) {
                val ic = invBmp.getPixel(x, y)
                val pc = plainBmp.getPixel(x, y)
                val iluma = ((ic shr 16 and 0xFF) + (ic shr 8 and 0xFF) + (ic and 0xFF)) / 3
                val pluma = ((pc shr 16 and 0xFF) + (pc shr 8 and 0xFF) + (pc and 0xFF)) / 3
                // Inverted luma + original luma ≈ 255 (within compression noise)
                invertLumaSumError += abs((iluma + pluma) - 255)
                count++
            }
        }
        val avgInvertSumError = invertLumaSumError.toDouble() / count
        assertTrue("Inversion must satisfy luma + inverted_luma ≈ 255: avg error=$avgInvertSumError", avgInvertSumError < 25.0)

        invBmp.recycle()
        plainBmp.recycle()
    }

    @Test
    fun layerMask_clips_overlay_and_preserves_underlying_background() = runBlocking<Unit> {
        val base = projectCreator.create("MaskTest", listOf(clip1Ref, clip2Ref), canvas = CanvasSettings(width = 1280, height = 720))

        // Main clip 1: full screen
        // Overlay clip 2: full screen (scale=1.0) with Circle mask centered at (0.0, 0.0) -> UV center (0.5, 0.5)
        val mask = LayerMask(
            type = MaskType.Circle,
            centerX = 0.0f,
            centerY = 0.0f,
            scaleX = 0.3f,
            scaleY = 0.3f,
            feather = 0.02f,
            invert = false,
            enabled = true
        )

        val project = base.copy(
            timeline = Timeline(
                videoClips = listOf(
                    Clip("main", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 2_000_000L)
                ),
                overlayClips = listOf(
                    OverlayClip(
                        id = "pip",
                        mediaRefId = clip2Ref.id,
                        timelineStartMicros = 0L,
                        timelineDurationMicros = 2_000_000L,
                        sourceStartMicros = 0L,
                        sourceEndMicros = 2_000_000L,
                        transform = VisualTransform(scale = 1f, offsetX = 0f, offsetY = 0f),
                        mask = mask,
                        zIndex = 0
                    )
                )
            )
        )

        val out = File(fixtureDir, "masked.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                project,
                ExportRequest(project.id, out, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )

        val maskedBmp = retrieveFrame(out, 1_000_000L)

        // Plain main clip export to compare corners (outside mask)
        val plainMainProject = base.copy(
            timeline = Timeline(
                videoClips = listOf(
                    Clip("main", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 2_000_000L)
                )
            )
        )
        val plainMainOut = File(fixtureDir, "plain_main.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                plainMainProject,
                ExportRequest(plainMainProject.id, plainMainOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val plainMainBmp = retrieveFrame(plainMainOut, 1_000_000L)

        // Check top-left corner (x=50, y=50, well outside circle radius)
        // Outside the mask, the overlay alpha should be 0, so it must match plainMain
        var cornerDiff = 0
        for (dy in 0..10) {
            for (dx in 0..10) {
                val mc = maskedBmp.getPixel(50 + dx, 50 + dy)
                val pc = plainMainBmp.getPixel(50 + dx, 50 + dy)
                cornerDiff += abs((mc shr 16 and 0xFF) - (pc shr 16 and 0xFF))
                cornerDiff += abs((mc shr 8 and 0xFF) - (pc shr 8 and 0xFF))
                cornerDiff += abs((mc and 0xFF) - (pc and 0xFF))
            }
        }
        val avgCornerDiff = cornerDiff.toDouble() / (121 * 3)
        assertTrue("Outside mask, overlay must be transparent exposing main clip: avgCornerDiff=$avgCornerDiff", avgCornerDiff < 10.0)

        // In center (x=640, y=360), overlay is inside mask
        // It should differ from plain main (since clip2 is drawn inside)
        var centerDiff = 0
        for (dy in -5..5) {
            for (dx in -5..5) {
                val mc = maskedBmp.getPixel(640 + dx, 360 + dy)
                val pc = plainMainBmp.getPixel(640 + dx, 360 + dy)
                centerDiff += abs((mc shr 16 and 0xFF) - (pc shr 16 and 0xFF))
                centerDiff += abs((mc shr 8 and 0xFF) - (pc shr 8 and 0xFF))
                centerDiff += abs((mc and 0xFF) - (pc and 0xFF))
            }
        }
        val avgCenterDiff = centerDiff.toDouble() / (121 * 3)
        assertTrue("Inside mask, overlay must be visible and differ from background: avgCenterDiff=$avgCenterDiff", avgCenterDiff > 5.0)

        maskedBmp.recycle()
        plainMainBmp.recycle()
    }

    @Test
    fun chromaKey_removes_green_screen_background() = runBlocking<Unit> {
        val base = projectCreator.create("ChromaTest", listOf(clip1Ref, greenRef), canvas = CanvasSettings(width = 1280, height = 720))

        // Main clip 1: background
        // Overlay: green screen clip with ChromaKey enabled targeting green (0xFF00FF00)
        val chromaSettings = ChromaKeySettings(
            enabled = true,
            keyColor = 0xFF00FF00.toInt(),
            tolerance = 0.45f,
            softness = 0.1f,
            spillSuppression = 0.5f
        )

        val project = base.copy(
            timeline = Timeline(
                videoClips = listOf(
                    Clip("main", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 2_000_000L)
                ),
                overlayClips = listOf(
                    OverlayClip(
                        id = "pip-green",
                        mediaRefId = greenRef.id,
                        timelineStartMicros = 0L,
                        timelineDurationMicros = 2_000_000L,
                        sourceStartMicros = 0L,
                        sourceEndMicros = 2_000_000L,
                        transform = VisualTransform(scale = 1f, offsetX = 0f, offsetY = 0f),
                        chromaKey = chromaSettings,
                        zIndex = 0
                    )
                )
            )
        )

        val out = File(fixtureDir, "chroma_keyed.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                project,
                ExportRequest(project.id, out, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )

        val keyedBmp = retrieveFrame(out, 1_000_000L)

        // Export green overlay without keying
        val unkeyedProject = project.copy(
            timeline = project.timeline.copy(
                overlayClips = project.timeline.overlayClips.map { it.copy(chromaKey = null) }
            )
        )
        val unkeyedOut = File(fixtureDir, "unkeyed.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                unkeyedProject,
                ExportRequest(unkeyedProject.id, unkeyedOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val unkeyedBmp = retrieveFrame(unkeyedOut, 1_000_000L)

        // Plain main clip
        val plainMainProject = base.copy(
            timeline = Timeline(
                videoClips = listOf(Clip("main", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 2_000_000L))
            )
        )
        val plainMainOut = File(fixtureDir, "plain_main_chroma.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                plainMainProject,
                ExportRequest(plainMainProject.id, plainMainOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val plainMainBmp = retrieveFrame(plainMainOut, 1_000_000L)

        // In the green areas, unkeyed is heavily green (G > R + 20).
        // Keyed must remove the green and reveal the underlying main clip!
        var matchingUnderlyingPixels = 0
        var evaluatedPixels = 0

        for (y in 100 until keyedBmp.height - 100 step 20) {
            for (x in 100 until keyedBmp.width - 100 step 20) {
                val ug = (unkeyedBmp.getPixel(x, y) shr 8) and 0xFF
                val ur = (unkeyedBmp.getPixel(x, y) shr 16) and 0xFF
                val ub = unkeyedBmp.getPixel(x, y) and 0xFF

                if (ug > ur + 20 && ug > ub + 20) {
                    // This was a green screen pixel
                    val kc = keyedBmp.getPixel(x, y)
                    val pc = plainMainBmp.getPixel(x, y)

                    val kr = (kc shr 16) and 0xFF
                    val kg = (kc shr 8) and 0xFF
                    val kb = kc and 0xFF

                    val pr = (pc shr 16) and 0xFF
                    val pg = (pc shr 8) and 0xFF
                    val pb = pc and 0xFF

                    val diff = abs(kr - pr) + abs(kg - pg) + abs(kb - pb)
                    if (diff < 35) {
                        matchingUnderlyingPixels++
                    }
                    evaluatedPixels++
                }
            }
        }

        assertTrue("Found green screen pixels to evaluate: evaluatedPixels=$evaluatedPixels", evaluatedPixels > 50)
        val matchRatio = matchingUnderlyingPixels.toDouble() / evaluatedPixels
        assertTrue("Chroma key must reveal underlying main clip pixels: matchRatio=$matchRatio", matchRatio > 0.85)

        keyedBmp.recycle()
        unkeyedBmp.recycle()
        plainMainBmp.recycle()
    }

    @Test
    fun blendModes_multiply_and_screen_render_proper_tonal_results() = runBlocking<Unit> {
        val base = projectCreator.create("BlendTest", listOf(clip1Ref, clip2Ref), canvas = CanvasSettings(width = 1280, height = 720))

        // 1. Multiply blend mode
        val multiplyProject = base.copy(
            timeline = Timeline(
                videoClips = listOf(Clip("main", clip1Ref.id, 0L, 2_000_000L)),
                overlayClips = listOf(
                    OverlayClip(
                        "pip", clip2Ref.id, 0L, 2_000_000L, 0L, 2_000_000L,
                        transform = VisualTransform(scale = 1f, offsetX = 0f, offsetY = 0f),
                        blendMode = BlendMode.Multiply,
                        zIndex = 0
                    )
                )
            )
        )
        val mulOut = File(fixtureDir, "multiply.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                multiplyProject,
                ExportRequest(multiplyProject.id, mulOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val mulBmp = retrieveFrame(mulOut, 1_000_000L)

        // 2. Screen blend mode
        val screenProject = base.copy(
            timeline = Timeline(
                videoClips = listOf(Clip("main", clip1Ref.id, 0L, 2_000_000L)),
                overlayClips = listOf(
                    OverlayClip(
                        "pip", clip2Ref.id, 0L, 2_000_000L, 0L, 2_000_000L,
                        transform = VisualTransform(scale = 1f, offsetX = 0f, offsetY = 0f),
                        blendMode = BlendMode.Screen,
                        zIndex = 0
                    )
                )
            )
        )
        val scrOut = File(fixtureDir, "screen.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                screenProject,
                ExportRequest(screenProject.id, scrOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val scrBmp = retrieveFrame(scrOut, 1_000_000L)

        // Multiply must darken the image, Screen must brighten it:
        // sum(Multiply_luma) < sum(Screen_luma)
        var mulLumaSum = 0L
        var scrLumaSum = 0L
        var count = 0

        for (y in 50 until mulBmp.height - 50 step 20) {
            for (x in 50 until mulBmp.width - 50 step 20) {
                val mc = mulBmp.getPixel(x, y)
                val sc = scrBmp.getPixel(x, y)

                mulLumaSum += ((mc shr 16 and 0xFF) + (mc shr 8 and 0xFF) + (mc and 0xFF)) / 3
                scrLumaSum += ((sc shr 16 and 0xFF) + (sc shr 8 and 0xFF) + (sc and 0xFF)) / 3
                count++
            }
        }

        val avgMulLuma = mulLumaSum.toDouble() / count
        val avgScrLuma = scrLumaSum.toDouble() / count

        assertTrue("Multiply luma ($avgMulLuma) must be significantly darker than Screen luma ($avgScrLuma)", avgMulLuma < avgScrLuma - 15.0)

        mulBmp.recycle()
        scrBmp.recycle()
    }

    @Test
    fun preview_pixel_parity_with_visual_effects_and_mask() = runBlocking<Unit> {
        val base = projectCreator.create("ParityTest", listOf(clip1Ref), canvas = CanvasSettings(width = 1280, height = 720))
        val project = base.copy(
            timeline = base.timeline.copy(
                videoClips = listOf(
                    Clip(
                        "c1", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 2_000_000L,
                        colorAdjustments = ColorAdjustments(brightness = 0.1f, saturation = 0.2f),
                        effects = listOf(
                            VisualEffect("fx1", VisualEffectType.Vignette, intensity = 0.6f)
                        )
                    )
                )
            )
        )

        // 1. Export
        val out = File(fixtureDir, "parity_export.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                project,
                ExportRequest(project.id, out, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val exportedBmp = retrieveFrame(out, 500_000L)

        // 2. Headless preview capture
        val lock = Any()
        var previewBmp: Bitmap? = null
        var frameCount = 0
        val thread = android.os.HandlerThread("stage9-parity-preview").apply { start() }
        val reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ images ->
            val image = images.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.first()
                val paddedWidth = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bmp = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                if (bmp !== padded) padded.recycle()
                synchronized(lock) {
                    previewBmp?.recycle()
                    previewBmp = bmp
                    frameCount++
                }
            } finally {
                image.close()
            }
        }, android.os.Handler(thread.looper))

        lateinit var controller: CompositionPreviewController
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(predicate: () -> Boolean) {
            val until = android.os.SystemClock.elapsedRealtime() + 20000
            while (!predicate() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(25)
            assertTrue("Preview frame timeout", predicate())
        }

        try {
            main {
                controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
                controller.attachHeadlessSurface(reader.surface, Size(1280, 720))
                controller.setProject(project)
            }
            await { synchronized(lock) { frameCount > 0 } }
            val before = synchronized(lock) { frameCount }
            main { controller.seekTo(500_000L) }
            await { synchronized(lock) { frameCount > before } }

            val preview = synchronized(lock) { previewBmp!!.copy(Bitmap.Config.ARGB_8888, false) }

            // Compute mean RGB error between preview and export
            var totalDiff = 0L
            var samples = 0
            for (y in 20 until 700 step 10) {
                for (x in 20 until 1260 step 10) {
                    val p = preview.getPixel(x, y)
                    val e = exportedBmp.getPixel(x, y)
                    for (shift in listOf(0, 8, 16)) {
                        totalDiff += abs((p shr shift and 0xFF) - (e shr shift and 0xFF))
                        samples++
                    }
                }
            }

            val meanError = totalDiff.toDouble() / samples
            android.util.Log.i("Stage9Parity", "Stage 9 Visual Effects Preview vs Export mean RGB error: $meanError")
            assertTrue("Preview must match export within codec budget: meanError=$meanError", meanError < 12.0)

            preview.recycle()
        } finally {
            main { controller.release() }
            reader.close()
            thread.quitSafely()
            thread.join()
            exportedBmp.recycle()
        }
    }

    @Test
    fun transitions_fade_through_white_and_wipe_render_in_export() = runBlocking<Unit> {
        val base = projectCreator.create("TransitionExportTest", listOf(clip1Ref, clip2Ref), canvas = CanvasSettings(width = 1280, height = 720))
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

        val out = File(fixtureDir, "fade_white_export.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                project,
                ExportRequest(project.id, out, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )

        // Midpoint of transition is at 2,000,000L (the cut point between c1 and c2).
        // For FadeThroughWhite, midpoint must be elevated in brightness (approaching white)
        val midBmp = retrieveFrame(out, 2_000_000L)
        val startBmp = retrieveFrame(out, 500_000L)

        var midLumaSum = 0L
        var startLumaSum = 0L
        var count = 0

        for (y in 50 until midBmp.height - 50 step 20) {
            for (x in 50 until midBmp.width - 50 step 20) {
                val mc = midBmp.getPixel(x, y)
                val sc = startBmp.getPixel(x, y)

                midLumaSum += ((mc shr 16 and 0xFF) + (mc shr 8 and 0xFF) + (mc and 0xFF)) / 3
                startLumaSum += ((sc shr 16 and 0xFF) + (sc shr 8 and 0xFF) + (sc and 0xFF)) / 3
                count++
            }
        }
        val avgMidLuma = midLumaSum.toDouble() / count
        val avgStartLuma = startLumaSum.toDouble() / count

        val medExtractor = android.media.MediaExtractor().apply {
            setDataSource(out.absolutePath)
            val track = (0 until trackCount).first {
                getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            selectTrack(track)
        }
        val ret = MediaMetadataRetriever().apply { setDataSource(out.absolutePath) }
        while (medExtractor.sampleTime != -1L) {
            val st = medExtractor.sampleTime
            val flags = medExtractor.sampleFlags
            if (st in 1_800_000L..2_200_000L) {
                val b = ret.getFrameAtTime(st, MediaMetadataRetriever.OPTION_CLOSEST)
                var lsum = 0L
                var cnt = 0
                if (b != null) {
                    for (y in 50 until b.height - 50 step 20) {
                        for (x in 50 until b.width - 50 step 20) {
                            val px = b.getPixel(x, y)
                            lsum += ((px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF)) / 3
                            cnt++
                        }
                    }
                    android.util.Log.i("SampleTimeProbe", "sampleTime=$st flags=$flags luma=${lsum.toDouble() / cnt}")
                    b.recycle()
                }
            }
            medExtractor.advance()
        }
        medExtractor.release()
        ret.release()

        assertTrue("FadeThroughWhite midpoint luma ($avgMidLuma) must be significantly higher than start luma ($avgStartLuma)", avgMidLuma > avgStartLuma + 25.0)

        midBmp.recycle()
        startBmp.recycle()
    }

    @Test
    fun gaussian_blur_reduces_high_frequency_variance() = runBlocking<Unit> {
        val base = projectCreator.create("BlurTest", listOf(clip1Ref), canvas = CanvasSettings(width = 1280, height = 720))

        // Blurred export
        val blurProject = base.copy(
            timeline = base.timeline.copy(
                videoClips = listOf(
                    Clip(
                        "c1", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 1_500_000L,
                        effects = listOf(
                            VisualEffect(
                                id = "blur1",
                                type = VisualEffectType.GaussianBlur,
                                intensity = 1.0f
                            )
                        )
                    )
                )
            )
        )
        val blurOut = File(fixtureDir, "blur_export.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                blurProject,
                ExportRequest(blurProject.id, blurOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val blurBmp = retrieveFrame(blurOut, 500_000L)

        // Plain export
        val plainProject = base.copy(
            timeline = base.timeline.copy(
                videoClips = listOf(Clip("c1", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 1_500_000L))
            )
        )
        val plainOut = File(fixtureDir, "plain_blur_cmp.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                plainProject,
                ExportRequest(plainProject.id, plainOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val plainBmp = retrieveFrame(plainOut, 500_000L)

        // Measure local Laplacian / gradient magnitude (high-frequency variance)
        var plainEdgeGradSum = 0.0
        var blurEdgeGradSum = 0.0
        var count = 0

        for (y in 50 until blurBmp.height - 50 step 10) {
            for (x in 50 until blurBmp.width - 50 step 10) {
                fun luma(b: Bitmap, px: Int, py: Int): Double {
                    val c = b.getPixel(px, py)
                    return (((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3.0)
                }

                val pGradX = abs(luma(plainBmp, x + 1, y) - luma(plainBmp, x - 1, y))
                val pGradY = abs(luma(plainBmp, x, y + 1) - luma(plainBmp, x, y - 1))
                plainEdgeGradSum += (pGradX + pGradY)

                val bGradX = abs(luma(blurBmp, x + 1, y) - luma(blurBmp, x - 1, y))
                val bGradY = abs(luma(blurBmp, x, y + 1) - luma(blurBmp, x, y - 1))
                blurEdgeGradSum += (bGradX + bGradY)

                count++
            }
        }

        val avgPlainGrad = plainEdgeGradSum / count
        val avgBlurGrad = blurEdgeGradSum / count

        assertTrue("Gaussian blur must measurably attenuate high-frequency gradient: plain=$avgPlainGrad, blur=$avgBlurGrad", avgBlurGrad < avgPlainGrad * 0.7)

        blurBmp.recycle()
        plainBmp.recycle()
    }

    @Test
    fun keyframing_interpolates_effect_parameters_over_time() = runBlocking<Unit> {
        val base = projectCreator.create("KeyframeTest", listOf(clip1Ref), canvas = CanvasSettings(width = 1280, height = 720))

        // Plain export for baseline comparison at each timestamp
        val plainProject = base.copy(
            timeline = base.timeline.copy(
                videoClips = listOf(Clip("c1", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 2_000_000L))
            )
        )
        val plainOut = File(fixtureDir, "plain_kf_cmp.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                plainProject,
                ExportRequest(plainProject.id, plainOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )

        // Keyframe brightness from 0.0 at 0us to 1.0 at 2,000,000us
        val keyframedEffect = VisualEffect(
            id = "kf-bright",
            type = VisualEffectType.Brightness,
            intensity = 1.0f,
            parameters = mapOf("brightness" to 0.8f),
            keyframes = listOf(
                VisualKeyframe("k1", timeMicros = 0L, value = 0.0f, interpolation = KeyframeInterpolation.Linear),
                VisualKeyframe("k2", timeMicros = 2_000_000L, value = 1.0f, interpolation = KeyframeInterpolation.Linear)
            )
        )

        val project = base.copy(
            timeline = base.timeline.copy(
                videoClips = listOf(
                    Clip(
                        "c1", clip1Ref.id, sourceStartMicros = 0L, sourceEndMicros = 2_000_000L,
                        effects = listOf(keyframedEffect)
                    )
                )
            )
        )

        val out = File(fixtureDir, "keyframe_export.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                project,
                ExportRequest(project.id, out, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )

        // Sample early frame (200,000us) vs late frame (1,800,000us)
        val earlyBmp = retrieveFrame(out, 200_000L)
        val lateBmp = retrieveFrame(out, 1_800_000L)
        val plainEarlyBmp = retrieveFrame(plainOut, 200_000L)
        val plainLateBmp = retrieveFrame(plainOut, 1_800_000L)

        var earlyDeltaSum = 0L
        var lateDeltaSum = 0L
        var count = 0

        for (y in 50 until earlyBmp.height - 50 step 20) {
            for (x in 50 until earlyBmp.width - 50 step 20) {
                fun luma(c: Int): Long = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3L
                val ec = earlyBmp.getPixel(x, y)
                val lc = lateBmp.getPixel(x, y)
                val pec = plainEarlyBmp.getPixel(x, y)
                val plc = plainLateBmp.getPixel(x, y)

                earlyDeltaSum += (luma(ec) - luma(pec))
                lateDeltaSum += (luma(lc) - luma(plc))
                count++
            }
        }

        val avgEarlyDelta = earlyDeltaSum.toDouble() / count
        val avgLateDelta = lateDeltaSum.toDouble() / count

        android.util.Log.i("Stage9Keyframe", "Keyframed brightness delta: early=$avgEarlyDelta, late=$avgLateDelta")
        assertTrue("Keyframed brightness delta must increase over time: early=$avgEarlyDelta, late=$avgLateDelta", avgLateDelta > avgEarlyDelta + 15.0)

        earlyBmp.recycle()
        lateBmp.recycle()
        plainEarlyBmp.recycle()
        plainLateBmp.recycle()
    }

    @Test
    fun blendModes_add_and_overlay_render_proper_tonal_results() = runBlocking<Unit> {
        val base = projectCreator.create("BlendAddTest", listOf(clip1Ref, clip2Ref), canvas = CanvasSettings(width = 1280, height = 720))

        // Normal blend mode baseline
        val normalProject = base.copy(
            timeline = Timeline(
                videoClips = listOf(Clip("main", clip1Ref.id, 0L, 2_000_000L)),
                overlayClips = listOf(
                    OverlayClip(
                        "pip", clip2Ref.id, 0L, 2_000_000L, 0L, 2_000_000L,
                        transform = VisualTransform(scale = 1f, offsetX = 0f, offsetY = 0f),
                        blendMode = BlendMode.Normal,
                        opacity = 0.5f,
                        zIndex = 0
                    )
                )
            )
        )
        val normalOut = File(fixtureDir, "normal_blend.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                normalProject,
                ExportRequest(normalProject.id, normalOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val normalBmp = retrieveFrame(normalOut, 1_000_000L)

        // Add blend mode
        val addProject = base.copy(
            timeline = Timeline(
                videoClips = listOf(Clip("main", clip1Ref.id, 0L, 2_000_000L)),
                overlayClips = listOf(
                    OverlayClip(
                        "pip", clip2Ref.id, 0L, 2_000_000L, 0L, 2_000_000L,
                        transform = VisualTransform(scale = 1f, offsetX = 0f, offsetY = 0f),
                        blendMode = BlendMode.Add,
                        opacity = 0.5f,
                        zIndex = 0
                    )
                )
            )
        )
        val addOut = File(fixtureDir, "add_blend.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                addProject,
                ExportRequest(addProject.id, addOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val addBmp = retrieveFrame(addOut, 1_000_000L)

        var normalLumaSum = 0L
        var addLumaSum = 0L
        var count = 0

        for (y in 50 until addBmp.height - 50 step 20) {
            for (x in 50 until addBmp.width - 50 step 20) {
                val nc = normalBmp.getPixel(x, y)
                val ac = addBmp.getPixel(x, y)

                normalLumaSum += ((nc shr 16 and 0xFF) + (nc shr 8 and 0xFF) + (nc and 0xFF)) / 3
                addLumaSum += ((ac shr 16 and 0xFF) + (ac shr 8 and 0xFF) + (ac and 0xFF)) / 3
                count++
            }
        }

        val avgNormalLuma = normalLumaSum.toDouble() / count
        val avgAddLuma = addLumaSum.toDouble() / count
        android.util.Log.i("Stage9Blend", "Normal vs Add luma: normal=$avgNormalLuma, add=$avgAddLuma")
        assertTrue("Add blend mode ($avgAddLuma) must be strictly brighter than Normal alpha mix ($avgNormalLuma)", avgAddLuma > avgNormalLuma)

        normalBmp.recycle()
        addBmp.recycle()
    }

    @Test
    fun layerMask_polygon_clips_overlay_with_rotation_and_feather() = runBlocking<Unit> {
        val base = projectCreator.create("PolygonMaskTest", listOf(clip1Ref, greenRef), canvas = CanvasSettings(width = 1280, height = 720))

        val polygonMask = LayerMask(
            type = MaskType.Polygon,
            polygonSides = 5,
            scaleX = 0.5f,
            scaleY = 0.5f,
            rotationDegrees = 36f,
            feather = 0.05f,
            invert = false,
        )

        val project = base.copy(
            timeline = Timeline(
                videoClips = listOf(Clip("bg", clip1Ref.id, 0L, 2_000_000L)),
                overlayClips = listOf(
                    OverlayClip(
                        "overlay", greenRef.id, 0L, 2_000_000L, 0L, 2_000_000L,
                        transform = VisualTransform(scale = 1f),
                        mask = polygonMask,
                        zIndex = 0
                    )
                )
            )
        )
        val out = File(fixtureDir, "polygon_mask.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                project,
                ExportRequest(project.id, out, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )

        val bmp = retrieveFrame(out, 1_000_000L)
        // Center pixel should be green (inside polygon mask)
        val centerPixel = bmp.getPixel(bmp.width / 2, bmp.height / 2)
        val centerGreen = (centerPixel shr 8) and 0xFF
        val centerRed = (centerPixel shr 16) and 0xFF
        val centerBlue = centerPixel and 0xFF

        // Corner pixel (outside polygon mask) should show background (not pure green)
        val cornerPixel = bmp.getPixel(20, 20)
        val cornerGreen = (cornerPixel shr 8) and 0xFF

        android.util.Log.i("Stage9Polygon", "Center: R=$centerRed, G=$centerGreen, B=$centerBlue; Corner G=$cornerGreen")
        assertTrue("Inside polygon mask must be primarily green: G=$centerGreen > R=$centerRed", centerGreen > centerRed + 30)
        bmp.recycle()
    }

    @Test
    fun transitions_wipe_up_and_split_render_in_export() = runBlocking<Unit> {
        val base = projectCreator.create("NewTransitionsTest", listOf(clip1Ref, clip2Ref), canvas = CanvasSettings(width = 1280, height = 720))

        val wipeUpProject = base.copy(
            timeline = Timeline(
                videoClips = listOf(
                    Clip("c1", clip1Ref.id, sourceStartMicros = 1_000_000L, sourceEndMicros = 3_000_000L, timelineStartMicros = 0L),
                    Clip("c2", clip2Ref.id, sourceStartMicros = 1_000_000L, sourceEndMicros = 3_000_000L, timelineStartMicros = 2_000_000L)
                ),
                transitions = listOf(
                    Transition("trans-wipeup", "c1", "c2", durationMicros = 1_000_000L, type = TransitionType.WipeUp)
                )
            )
        )
        val wipeUpOut = File(fixtureDir, "wipe_up.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                wipeUpProject,
                ExportRequest(wipeUpProject.id, wipeUpOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val wipeUpMid = retrieveFrame(wipeUpOut, 2_000_000L)
        assertNotNull(wipeUpMid)
        wipeUpMid.recycle()

        val splitProject = base.copy(
            timeline = Timeline(
                videoClips = listOf(
                    Clip("c1", clip1Ref.id, sourceStartMicros = 1_000_000L, sourceEndMicros = 3_000_000L, timelineStartMicros = 0L),
                    Clip("c2", clip2Ref.id, sourceStartMicros = 1_000_000L, sourceEndMicros = 3_000_000L, timelineStartMicros = 2_000_000L)
                ),
                transitions = listOf(
                    Transition("trans-split", "c1", "c2", durationMicros = 1_000_000L, type = TransitionType.Split)
                )
            )
        )
        val splitOut = File(fixtureDir, "split.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                splitProject,
                ExportRequest(splitProject.id, splitOut, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )
        val splitMid = retrieveFrame(splitOut, 2_000_000L)
        assertNotNull(splitMid)
        splitMid.recycle()
    }

    @Test
    fun overlay_opacity_keyframing_renders_in_export() = runBlocking<Unit> {
        val base = projectCreator.create("OpacityKfTest", listOf(clip1Ref, greenRef), canvas = CanvasSettings(width = 1280, height = 720))

        val keyframes = listOf(
            VisualKeyframe("kf1", 0L, 1.0f, KeyframeInterpolation.Linear),
            VisualKeyframe("kf2", 2_000_000L, 0.0f, KeyframeInterpolation.Linear)
        )

        val project = base.copy(
            timeline = Timeline(
                videoClips = listOf(Clip("bg", clip1Ref.id, 0L, 2_000_000L)),
                overlayClips = listOf(
                    OverlayClip(
                        "ov", greenRef.id, 0L, 2_000_000L, 0L, 2_000_000L,
                        transform = VisualTransform(scale = 1f),
                        opacity = 1.0f,
                        keyframeTracks = mapOf("opacity" to KeyframeTrack("opacity", keyframes))
                    )
                )
            )
        )
        val out = File(fixtureDir, "opacity_kf.mp4")
        assertTrue(
            ExportEngine(context, compositionBuilder).export(
                project,
                ExportRequest(project.id, out, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
            ) {}.isSuccess
        )

        // At 200,000us, opacity is ~0.9 (mostly green)
        val earlyBmp = retrieveFrame(out, 200_000L)
        val earlyPixel = earlyBmp.getPixel(earlyBmp.width / 2, earlyBmp.height / 2)
        val earlyGreen = (earlyPixel shr 8) and 0xFF
        val earlyRed = (earlyPixel shr 16) and 0xFF

        // At 1,800,000us, opacity is ~0.1 (mostly background, low green dominance)
        val lateBmp = retrieveFrame(out, 1_800_000L)
        val latePixel = lateBmp.getPixel(lateBmp.width / 2, lateBmp.height / 2)
        val lateGreen = (latePixel shr 8) and 0xFF

        android.util.Log.i("Stage9OpacityKf", "Early: G=$earlyGreen, R=$earlyRed; Late G=$lateGreen")
        assertTrue("Early frame must show dominant overlay green: earlyGreen=$earlyGreen > earlyRed=$earlyRed", earlyGreen > earlyRed + 20)

        earlyBmp.recycle()
        lateBmp.recycle()
    }
}


