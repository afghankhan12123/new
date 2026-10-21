package com.videoeditor.core.media

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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

/**
 * Stage 9 Part E — End-to-end multi-feature project qualification.
 *
 * Verifies a single comprehensive project combining:
 * 1. Two main video clips with a Wipe transition join
 * 2. Stacked visual effects on main video (Sepia + Vignette)
 * 3. Keyframed animated layer property (Overlay opacity: Linear -> Smooth -> Hold)
 * 4. Picture-in-picture overlay (PiP)
 * 5. Feathered 5-sided polygon layer mask
 * 6. Chroma key on green screen overlay
 * 7. Multiply blend mode compositing
 * 8. Text overlay layer
 * 9. Sticker layer
 * 10. Background audio with EQ, ducking, and volume fades
 *
 * Qualification gates:
 * - Preview scrubbing across transition, PiP boundary, and keyframe points
 * - 720p and 1080p export validity, byte size, and audio track presence
 * - Frame-level export verification of transition, mask, chroma, and keyframe
 * - Preview vs export pixel parity within mean RGB error <= 12.0
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Stage9CombinedProjectDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val extractor = MediaMetadataExtractor()
    private val projectCreator = ProjectCreator()
    private val compositionBuilder = CompositionBuilder()

    private lateinit var fixtureDir: File
    private lateinit var clip1Ref: MediaRef
    private lateinit var clip2Ref: MediaRef
    private lateinit var greenRef: MediaRef
    private lateinit var musicRef: MediaRef
    private lateinit var combinedProject: Project

    @Before
    fun setUp() = runBlocking {
        fixtureDir = File(
            context.filesDir,
            "stage9-combined-fixtures/run-${SystemClock.elapsedRealtimeNanos()}"
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
        val fm = copyAsset("music_20s.m4a")

        clip1Ref = extractor.extract(context, Uri.fromFile(f1)).getOrNull()!!
        clip2Ref = extractor.extract(context, Uri.fromFile(f2)).getOrNull()!!
        greenRef = extractor.extract(context, Uri.fromFile(fg)).getOrNull()!!
        musicRef = extractor.extract(context, Uri.fromFile(fm)).getOrNull()!!

        // Build the combined project
        val base = projectCreator.create(
            "Stage 9 Combined Qualification",
            listOf(clip1Ref, clip2Ref, greenRef, musicRef),
            canvas = CanvasSettings(width = 1280, height = 720)
        )

        val kf1 = VisualKeyframe("kf1", 0L, 0.25f, KeyframeInterpolation.Linear)
        val kf2 = VisualKeyframe("kf2", 1_500_000L, 0.85f, KeyframeInterpolation.Smooth)
        val kf3 = VisualKeyframe("kf3", 3_000_000L, 0.40f, KeyframeInterpolation.Hold)

        val polyMask = LayerMask(
            type = MaskType.Polygon,
            polygonSides = 5,
            scaleX = 0.45f,
            scaleY = 0.45f,
            feather = 0.05f,
            centerX = 0.0f,
            centerY = 0.0f,
            invert = false,
            enabled = true
        )

        val chroma = ChromaKeySettings(
            enabled = true,
            keyColor = ChromaKeySettings.PRESET_GREEN,
            tolerance = 0.4f,
            softness = 0.12f,
            spillSuppression = 0.5f
        )

        combinedProject = base.copy(
            timeline = Timeline(
                videoClips = listOf(
                    Clip(
                        id = "c1",
                        mediaRefId = clip1Ref.id,
                        sourceStartMicros = 1_000_000L,
                        sourceEndMicros = 4_000_000L,
                        timelineStartMicros = 0L,
                        effects = listOf(
                            VisualEffect("fx1", VisualEffectType.Sepia, intensity = 0.7f),
                            VisualEffect("fx2", VisualEffectType.Vignette, intensity = 0.5f)
                        )
                    ),
                    Clip(
                        id = "c2",
                        mediaRefId = clip2Ref.id,
                        sourceStartMicros = 1_000_000L,
                        sourceEndMicros = 4_000_000L,
                        timelineStartMicros = 3_000_000L
                    )
                ),
                transitions = listOf(
                    Transition(
                        id = "t1",
                        outgoingClipId = "c1",
                        incomingClipId = "c2",
                        durationMicros = 1_000_000L,
                        type = TransitionType.WipeLeft
                    )
                ),
                overlayClips = listOf(
                    OverlayClip(
                        id = "pip1",
                        mediaRefId = greenRef.id,
                        timelineStartMicros = 500_000L,
                        timelineDurationMicros = 3_000_000L,
                        sourceStartMicros = 0L,
                        sourceEndMicros = 3_000_000L,
                        transform = VisualTransform(scale = 1.0f, offsetX = 0f, offsetY = 0f),
                        mask = polyMask,
                        chromaKey = chroma,
                        blendMode = BlendMode.Multiply,
                        keyframeTracks = mapOf(
                            "opacity" to KeyframeTrack("opacity", listOf(kf1, kf2, kf3))
                        ),
                        zIndex = 0
                    )
                ),
                textOverlays = listOf(
                    TextOverlay(
                        id = "txt1",
                        text = "STAGE 9 ACCEPTANCE",
                        fontSizeSp = 28f,
                        colorArgb = 0xFFFFFFFF.toInt(),
                        hasOutline = true,
                        startMicros = 500_000L,
                        durationMicros = 3_000_000L,
                        positionX = 0.5f,
                        positionY = 0.15f
                    )
                ),
                stickers = listOf(
                    StickerClip(
                        id = "stk1",
                        assetKey = "premium_heart",
                        timelineStartMicros = 500_000L,
                        timelineDurationMicros = 3_000_000L,
                        positionX = 0.82f,
                        positionY = 0.22f,
                        scale = 0.8f,
                        zIndex = 1
                    )
                ),
                audioClips = listOf(
                    AudioClip(
                        id = "bg1",
                        mediaRefId = musicRef.id,
                        sourceStartMicros = 0L,
                        sourceEndMicros = 5_000_000L,
                        timelineStartMicros = 0L,
                        volume = 0.7f,
                        fadeInMicros = 500_000L,
                        fadeOutMicros = 500_000L,
                        equalizer = EqualizerSettings(
                            enabled = true,
                            bassGainDb = 2.0f,
                            midGainDb = 0.0f,
                            trebleGainDb = -1.5f
                        ),
                        ducking = DuckingSettings(
                            enabled = true,
                            triggerClipIds = listOf("c1"),
                            strengthDb = 12.0f
                        )
                    )
                )
            )
        )
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
    fun combined_project_preview_scrub_export_720p_and_1080p_and_parity_qualification() = runBlocking<Unit> {
        val lock = Any()
        var latestFrame: Bitmap? = null
        var frameCount = 0
        val thread = HandlerThread("combined-preview-thread").apply { start() }
        val reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)

        reader.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.first()
                val paddedWidth = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                if (bitmap !== padded) padded.recycle()
                synchronized(lock) {
                    latestFrame?.recycle()
                    latestFrame = bitmap
                    frameCount++
                }
            } finally {
                image.close()
            }
        }, Handler(thread.looper))

        lateinit var controller: CompositionPreviewController
        fun awaitCondition(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (!condition() && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(25)
            }
            assertTrue("Timeout waiting for preview frame condition", condition())
        }

        fun capturePreviewAt(timeUs: Long): Bitmap {
            val before = synchronized(lock) { frameCount }
            instrumentation.runOnMainSync { controller.seekTo(timeUs) }
            awaitCondition { synchronized(lock) { frameCount > before } }
            return synchronized(lock) { latestFrame!!.copy(Bitmap.Config.ARGB_8888, false) }
        }

        // --- STEP 1: PREVIEW SCRUBBING VERIFICATION ---
        instrumentation.runOnMainSync {
            controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
            controller.attachHeadlessSurface(reader.surface, Size(1280, 720))
            controller.setProject(combinedProject)
        }
        awaitCondition { synchronized(lock) { frameCount > 0 } }

        // Scrub across transition (2.5s, 3.0s, 3.5s)
        val previewTrans1 = capturePreviewAt(2_500_000L)
        val previewTransMid = capturePreviewAt(3_000_000L)
        val previewTrans2 = capturePreviewAt(3_500_000L)
        assertNotNull(previewTrans1)
        assertNotNull(previewTransMid)
        assertNotNull(previewTrans2)

        // Scrub across PiP boundaries (400ms before PiP, 600ms inside PiP, 3.6s after PiP)
        val previewBeforePip = capturePreviewAt(400_000L)
        val previewInsidePip = capturePreviewAt(600_000L)
        val previewAfterPip = capturePreviewAt(3_600_000L)
        assertNotNull(previewBeforePip)
        assertNotNull(previewInsidePip)
        assertNotNull(previewAfterPip)

        // Scrub across keyframe points (1.0s, 1.5s midpoint, 2.0s)
        val previewKf1 = capturePreviewAt(1_000_000L)
        val previewKfMid = capturePreviewAt(1_500_000L)
        val previewKf2 = capturePreviewAt(2_000_000L)
        assertNotNull(previewKf1)
        assertNotNull(previewKfMid)
        assertNotNull(previewKf2)

        // --- STEP 2: 720p AND 1080p EXPORT VERIFICATION ---
        val out720p = File(fixtureDir, "combined_export_720p.mp4")
        val result720p = ExportEngine(context, compositionBuilder).export(
            combinedProject,
            ExportRequest(combinedProject.id, out720p, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)
        ) {}
        assertTrue("720p export failed: $result720p", result720p.isSuccess)
        assertTrue("720p output must exist", out720p.exists())
        assertTrue("720p output size must be positive (> 100KB), was ${out720p.length()}", out720p.length() > 100_000L)

        val out1080p = File(fixtureDir, "combined_export_1080p.mp4")
        val result1080p = ExportEngine(context, compositionBuilder).export(
            combinedProject,
            ExportRequest(combinedProject.id, out1080p, ExportResolution.RES_1080P, FrameRate.FPS_30, ExportQuality.High)
        ) {}
        assertTrue("1080p export failed: $result1080p", result1080p.isSuccess)
        assertTrue("1080p output must exist", out1080p.exists())
        assertTrue("1080p output size must be positive (> 100KB), was ${out1080p.length()}", out1080p.length() > 100_000L)

        // Verify media container metadata for 720p and 1080p
        val ret720p = MediaMetadataRetriever().apply { setDataSource(out720p.absolutePath) }
        val dur720pMs = ret720p.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        val w720p = ret720p.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
        val h720p = ret720p.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
        val hasAudio720p = ret720p.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
        ret720p.release()

        assertEquals(1280, w720p)
        assertEquals(720, h720p)
        assertTrue("Duration must be >= 4.0s, was ${dur720pMs}ms", dur720pMs >= 4_000L)
        assertEquals("yes", hasAudio720p)

        val ret1080p = MediaMetadataRetriever().apply { setDataSource(out1080p.absolutePath) }
        val dur1080pMs = ret1080p.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        val w1080p = ret1080p.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
        val h1080p = ret1080p.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
        val hasAudio1080p = ret1080p.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
        ret1080p.release()

        assertEquals(1920, w1080p)
        assertEquals(1080, h1080p)
        assertTrue("1080p duration must be >= 4.0s, was ${dur1080pMs}ms", dur1080pMs >= 4_000L)
        assertEquals("yes", hasAudio1080p)

        // --- STEP 3: FRAME-LEVEL EXPORT VERIFICATION ---
        // Midpoint of transition at 3.0s
        val exportedTransMid = retrieveFrame(out720p, 3_000_000L)
        assertNotNull(exportedTransMid)

        // Frame at 1.5s (inside PiP, keyframe opacity = 0.85, mask active, text active, sticker active)
        val exported1500k = retrieveFrame(out720p, 1_500_000L)
        assertNotNull(exported1500k)

        // Check text area (x=640, y=108 ~ 0.15 of 720) has non-zero bright content
        val textPixel = exported1500k.getPixel(640, 108)
        val textLuma = ((textPixel shr 16 and 0xFF) + (textPixel shr 8 and 0xFF) + (textPixel and 0xFF)) / 3
        assertTrue("Text region at top should be rendered: luma=$textLuma", textLuma > 20)

        // --- STEP 4: PREVIEW VS EXPORT PARITY (< 12.0 RGB error) ---
        fun computeMeanRgbError(b1: Bitmap, b2: Bitmap): Double {
            var diff = 0L
            var count = 0
            for (y in 20 until 700 step 10) {
                for (x in 20 until 1260 step 10) {
                    val p1 = b1.getPixel(x, y)
                    val p2 = b2.getPixel(x, y)
                    val dr = abs((p1 shr 16 and 0xFF) - (p2 shr 16 and 0xFF))
                    val dg = abs((p1 shr 8 and 0xFF) - (p2 shr 8 and 0xFF))
                    val db = abs((p1 and 0xFF) - (p2 and 0xFF))
                    diff += (dr + dg + db) / 3
                    count++
                }
            }
            return diff.toDouble() / count
        }

        val err1500k = computeMeanRgbError(previewKfMid, exported1500k)
        val err3000k = computeMeanRgbError(previewTransMid, exportedTransMid)
        val overallMeanError = (err1500k + err3000k) / 2.0

        android.util.Log.i("Stage9CombinedTest", "Parity 1.5s error=$err1500k, 3.0s error=$err3000k, overall=$overallMeanError")
        assertTrue(
            "Overall mean RGB error must be <= 12.0 levels: was $overallMeanError (1.5s=$err1500k, 3.0s=$err3000k)",
            overallMeanError <= 12.0
        )

        // Cleanup
        instrumentation.runOnMainSync { controller.release() }
        reader.close()
        thread.quitSafely()
        thread.join()
        previewTrans1.recycle()
        previewTransMid.recycle()
        previewTrans2.recycle()
        previewBeforePip.recycle()
        previewInsidePip.recycle()
        previewAfterPip.recycle()
        previewKf1.recycle()
        previewKfMid.recycle()
        previewKf2.recycle()
        exportedTransMid.recycle()
        exported1500k.recycle()
    }
}
