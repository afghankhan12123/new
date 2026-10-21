package com.videoeditor.core.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.ExportEngine
import com.videoeditor.core.media.export.ExportRequest
import com.videoeditor.core.media.stickers.StickerCatalog
import com.videoeditor.core.model.CanvasSettings
import com.videoeditor.core.model.ExportQuality
import com.videoeditor.core.model.ExportResolution
import com.videoeditor.core.model.FrameRate
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.core.model.OverlayClip
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.StickerClip
import com.videoeditor.core.model.TextOverlay
import com.videoeditor.core.model.Timeline
import com.videoeditor.core.model.VisualTransform
import com.videoeditor.editing.ProjectCreator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that stickers reach real exported pixels, using strict decoded
 * frames rather than metadata assertions.
 *
 * Oracle design: every case exports the same project twice — with and without
 * the sticker layer — against a solid-color probe background. Pixels the sticker
 * covers differ between the two decodes; pixels it does not cover are
 * byte-stable. That makes the assertion independent of H.264 noise levels and
 * of the background's own content, and it distinguishes the sticker footprint
 * from the underlying video by construction.
 *
 * Expected geometry, derived from Media3's [OverlayMatrixProvider]: a sticker
 * bitmap normalized to [StickerClip.BASE_CANVAS_FRACTION] × the canvas short
 * edge occupies exactly that many output pixels on its longer edge, centered at
 * ([StickerClip.positionX], [StickerClip.positionY]) in normalized canvas space.
 * The same fraction holds at 720p and 1080p, which is the preview/export parity
 * contract stated in normalized coordinates.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class StickerRenderingDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val compositionBuilder = CompositionBuilder()
    private val projectCreator = ProjectCreator()

    private lateinit var mediaDir: File

    @Before
    fun copyFixtures() {
        // Durable test-owned location: Android's storage manager previously
        // evicted cache paths holding in-flight export inputs.
        mediaDir = retainedMediaTestDirectory(context)
        listOf("probe_blue_720p_3s.mp4", "probe_green_720p_6s.mp4", "clip1_720p_6s.mp4").forEach { name ->
            val target = File(mediaDir, name)
            if (!target.exists()) context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
        }
    }

    private fun videoRef(name: String): MediaRef {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(File(mediaDir, name).absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            return MediaRef(
                id = name, uri = android.net.Uri.fromFile(File(mediaDir, name)).toString(),
                type = MediaType.Video, displayName = name, mimeType = "video/mp4",
                durationMicros = durationMs * 1000, width = width, height = height, hasAudio = false,
            )
        } finally { retriever.release() }
    }

    /** Solid-blue 3s background, canvas 1280×720, optional sticker/PiP/text layers. */
    private fun project(
        stickers: List<StickerClip> = emptyList(),
        overlays: List<OverlayClip> = emptyList(),
        text: List<TextOverlay> = emptyList(),
    ): Project {
        val main = videoRef("probe_blue_720p_3s.mp4")
        val pip = videoRef("probe_green_720p_6s.mp4")
        val refs = listOf(main, pip)
        return projectCreator.create("Sticker test", refs, canvas = CanvasSettings(width = 1280, height = 720))
            .copy(timeline = Timeline(
                videoClips = listOf(com.videoeditor.core.model.Clip(
                    id = "main", mediaRefId = main.id, sourceStartMicros = 0L, sourceEndMicros = 3_000_000L, muted = true)),
                overlayClips = overlays, textOverlays = text, stickers = stickers,
            ))
    }

    private fun heart(width: Float = 0.5f, height: Float = 0.5f, scale: Float = 1f,
        rotation: Float = 0f, opacity: Float = 1f, zIndex: Int = 0,
        startMicros: Long = 0L, durationMicros: Long = 3_000_000L, id: String = "s1") =
        StickerClip(id = id, assetKey = "premium_heart", timelineStartMicros = startMicros,
            timelineDurationMicros = durationMicros, positionX = width, positionY = height,
            scale = scale, rotationDegrees = rotation, opacity = opacity, zIndex = zIndex)

    private fun export(project: Project, resolution: ExportResolution, name: String,
        quality: ExportQuality = ExportQuality.High, frameRate: FrameRate = FrameRate.FPS_30): File {
        val output = File(mediaDir, name)
        val result = runBlocking {
            ExportEngine(context, compositionBuilder).export(project,
                ExportRequest(project.id, output, resolution, frameRate, quality)) {}
        }
        assertTrue("Export failed: ${(result as? com.videoeditor.core.common.result.Result.Failure)?.error}", result.isSuccess)
        assertTrue("Export produced no file", output.exists() && output.length() > 0)
        return output
    }

    private fun frameAt(file: File, atUs: Long): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
        } finally { retriever.release() }
    }

    /**
     * Fraction of pixels in a region that differ between two decodes by more
     * than H.264 noise. A sticker covering the region yields a value near its
     * own opaque coverage; an untouched region yields ~0.
     */
    private fun changedFraction(withSticker: Bitmap, without: Bitmap,
        x0: Float, x1: Float, y0: Float, y1: Float): Double {
        require(withSticker.width == without.width && withSticker.height == without.height)
        fun region(src: Bitmap): IntArray {
            val sx = (src.width * x0).toInt(); val sy = (src.height * y0).toInt()
            val sw = (src.width * (x1 - x0)).toInt().coerceAtLeast(1)
            val sh = (src.height * (y1 - y0)).toInt().coerceAtLeast(1)
            val cut = Bitmap.createBitmap(src, sx, sy, sw, sh)
            val scaled = Bitmap.createScaledBitmap(cut, 64, 64, true)
            if (cut !== scaled) cut.recycle()
            return IntArray(64 * 64).also { scaled.getPixels(it, 0, 64, 0, 0, 64, 64) }
                .also { if (scaled.width != 64 || scaled.height != 64) scaled.recycle() }
        }
        val a = region(withSticker); val b = region(without)
        var changed = 0
        for (i in a.indices) {
            val dr = kotlin.math.abs((a[i] shr 16 and 0xFF) - (b[i] shr 16 and 0xFF))
            val dg = kotlin.math.abs((a[i] shr 8 and 0xFF) - (b[i] shr 8 and 0xFF))
            val db = kotlin.math.abs((a[i] and 0xFF) - (b[i] and 0xFF))
            if (dr + dg + db > 48) changed++
        }
        return changed.toDouble() / a.size
    }

    /** The model's expected sticker footprint as normalized left/right/top/bottom. */
    private fun footprint(sticker: StickerClip, width: Int, height: Int): FloatArray {
        val asset = StickerCatalog.asset(sticker.assetKey)!!
        val basePx = minOf(width, height) * StickerClip.BASE_CANVAS_FRACTION * sticker.scale
        val w = if (asset.aspectRatio >= 1f) basePx else basePx * asset.aspectRatio
        val h = if (asset.aspectRatio >= 1f) basePx / asset.aspectRatio else basePx
        val cx = sticker.positionX * width; val cy = sticker.positionY * height
        return floatArrayOf((cx - w / 2) / width, (cx + w / 2) / width, (cy - h / 2) / height, (cy + h / 2) / height)
    }

    @Test
    fun stickerPixelsAppearInExportedFrameAt720p_andFootprintMatchesModel() {
        val withSticker = project(stickers = listOf(heart()))
        val plain = project()
        val a = export(withSticker, ExportResolution.RES_720P, "sticker-720p-with.mp4")
        val b = export(plain, ExportResolution.RES_720P, "sticker-720p-without.mp4")
        val frameA = frameAt(a, 1_500_000); val frameB = frameAt(b, 1_500_000)
        assertEquals(1280, frameA.width); assertEquals(720, frameA.height)

        val f = footprint(heart(), frameA.width, frameA.height)
        val inside = changedFraction(frameA, frameB, f[0], f[1], f[2], f[3])
        // The heart is ~50% opaque; downsampling and H.264 blur the edges, so
        // require a substantial but not exact coverage.
        assertTrue("Sticker footprint changed only $inside of pixels", inside > 0.15)

        // A region well clear of the sticker must be untouched.
        val outside = changedFraction(frameA, frameB, 0.02f, 0.2f, 0.05f, 0.2f)
        assertTrue("Sticker bled outside its footprint: $outside", outside < 0.02)
        frameA.recycle(); frameB.recycle()
    }

    @Test
    fun stickerPixelsAppearInExportedFrameAt1080p_andNormalizeIdentically() {
        val withSticker = project(stickers = listOf(heart()))
        val plain = project()
        val a = export(withSticker, ExportResolution.RES_1080P, "sticker-1080p-with.mp4")
        val b = export(plain, ExportResolution.RES_1080P, "sticker-1080p-without.mp4")
        val frameA = frameAt(a, 1_500_000); val frameB = frameAt(b, 1_500_000)
        assertEquals(1920, frameA.width); assertEquals(1080, frameA.height)
        val f = footprint(heart(), frameA.width, frameA.height)
        val inside = changedFraction(frameA, frameB, f[0], f[1], f[2], f[3])
        assertTrue("1080p sticker footprint changed only $inside", inside > 0.15)
        // The same normalized footprint at both resolutions is the parity claim.
        val f720 = footprint(heart(), 1280, 720)
        assertEquals(f720[0], f[0], 0.001f); assertEquals(f720[3], f[3], 0.001f)
        frameA.recycle(); frameB.recycle()
    }

    @Test
    fun stickerIsInvisibleOutsideItsTimelineWindow() {
        // Visible 1.0s–2.0s of a 3s project.
        val p = project(stickers = listOf(heart(startMicros = 1_000_000L, durationMicros = 1_000_000L)))
        val plain = project()
        val a = export(p, ExportResolution.RES_720P, "sticker-window-with.mp4")
        val b = export(plain, ExportResolution.RES_720P, "sticker-window-without.mp4")
        val f = footprint(heart(), 1280, 720)

        val during = changedFraction(frameAt(a, 1_500_000), frameAt(b, 1_500_000), f[0], f[1], f[2], f[3])
        assertTrue("Sticker missing inside its window: $during", during > 0.15)

        val before = changedFraction(frameAt(a, 400_000), frameAt(b, 400_000), f[0], f[1], f[2], f[3])
        assertTrue("Sticker visible before its start: $before", before < 0.02)

        val after = changedFraction(frameAt(a, 2_600_000), frameAt(b, 2_600_000), f[0], f[1], f[2], f[3])
        assertTrue("Sticker visible after its end: $after", after < 0.02)
    }

    @Test
    fun opacityReducesStickerCoverage() {
        val plain = project()
        val full = export(project(stickers = listOf(heart(opacity = 1f))), ExportResolution.RES_720P, "sticker-op-full.mp4")
        val faint = export(project(stickers = listOf(heart(opacity = 0.25f))), ExportResolution.RES_720P, "sticker-op-faint.mp4")
        val b = export(plain, ExportResolution.RES_720P, "sticker-op-none.mp4")
        val f = footprint(heart(), 1280, 720)
        val fullChange = changedFraction(frameAt(full, 1_500_000), frameAt(b, 1_500_000), f[0], f[1], f[2], f[3])
        val faintChange = changedFraction(frameAt(faint, 1_500_000), frameAt(b, 1_500_000), f[0], f[1], f[2], f[3])
        assertTrue("Full opacity coverage $fullChange should exceed faint $faintChange", fullChange > faintChange)
        assertTrue("Faint sticker should still be present: $faintChange", faintChange > 0.02)
    }

    @Test
    fun higherZIndexStickerDrawsOnTop() {
        // Two overlapping stickers at the same center: a red heart below a gold star.
        val heartBelow = heart(id = "h", zIndex = 0)
        val starAbove = StickerClip(id = "st", assetKey = "premium_star", positionX = 0.5f, positionY = 0.5f,
            scale = 1f, zIndex = 1, timelineDurationMicros = 3_000_000L)
        val p = project(stickers = listOf(heartBelow, starAbove))
        // Control: heart alone at the same spot.
        val heartOnly = project(stickers = listOf(heartBelow))
        val both = export(p, ExportResolution.RES_720P, "sticker-z-both.mp4")
        val single = export(heartOnly, ExportResolution.RES_720P, "sticker-z-heart.mp4")
        val fBoth = frameAt(both, 1_500_000); val fSingle = frameAt(single, 1_500_000)

        // Where the star covers the heart's center, the composited frame must
        // match the star, not the heart: the two differ substantially there.
        val diff = changedFraction(fBoth, fSingle, 0.44f, 0.56f, 0.42f, 0.58f)
        assertTrue("Top sticker did not occlude the lower one: $diff", diff > 0.10)

        // And the center of the composite must read as the star's gold, not the heart's red.
        val center = IntArray(1).also { fBoth.getPixels(it, 0, 1, fBoth.width / 2, fBoth.height / 2, 1, 1) }[0]
        val r = center shr 16 and 0xFF; val g = center shr 8 and 0xFF; val b = center and 0xFF
        assertTrue("Center should be gold-dominant, got RGB($r,$g,$b)", r > b && g > b)
        fBoth.recycle(); fSingle.recycle()
    }

    @Test
    fun positionMovesStickerFootprint() {
        val topLeft = heart(width = 0.25f, height = 0.25f)
        val bottomRight = heart(width = 0.75f, height = 0.75f)
        val plain = project()
        val a = export(project(stickers = listOf(topLeft)), ExportResolution.RES_720P, "sticker-pos-tl.mp4")
        val b = export(project(stickers = listOf(bottomRight)), ExportResolution.RES_720P, "sticker-pos-br.mp4")
        val none = export(plain, ExportResolution.RES_720P, "sticker-pos-none.mp4")
        val fA = footprint(topLeft, 1280, 720); val fB = footprint(bottomRight, 1280, 720)
        val frameA = frameAt(a, 1_500_000); val frameB = frameAt(b, 1_500_000); val frameNone = frameAt(none, 1_500_000)
        assertTrue("Top-left placement not rendered",
            changedFraction(frameA, frameNone, fA[0], fA[1], fA[2], fA[3]) > 0.15)
        assertTrue("Bottom-right placement not rendered",
            changedFraction(frameB, frameNone, fB[0], fB[1], fB[2], fB[3]) > 0.15)
        // Each placement must leave the OTHER's footprint clean.
        assertTrue("Top-left sticker bled into the bottom-right footprint",
            changedFraction(frameA, frameNone, fB[0], fB[1], fB[2], fB[3]) < 0.02)
        assertTrue("Bottom-right sticker bled into the top-left footprint",
            changedFraction(frameB, frameNone, fA[0], fA[1], fA[2], fA[3]) < 0.02)
    }

    @Test
    fun scaleChangesStickerFootprintSize() {
        val small = heart(scale = 0.5f); val large = heart(scale = 2f)
        val plain = project()
        val a = export(project(stickers = listOf(small)), ExportResolution.RES_720P, "sticker-scale-small.mp4")
        val b = export(project(stickers = listOf(large)), ExportResolution.RES_720P, "sticker-scale-large.mp4")
        val none = export(plain, ExportResolution.RES_720P, "sticker-scale-none.mp4")
        val frameA = frameAt(a, 1_500_000); val frameB = frameAt(b, 1_500_000); val frameNone = frameAt(none, 1_500_000)
        val fSmall = footprint(small, 1280, 720); val fLarge = footprint(large, 1280, 720)

        assertTrue("Small sticker not rendered",
            changedFraction(frameA, frameNone, fSmall[0], fSmall[1], fSmall[2], fSmall[3]) > 0.15)
        assertTrue("Large sticker not rendered",
            changedFraction(frameB, frameNone, fLarge[0], fLarge[1], fLarge[2], fLarge[3]) > 0.15)
        // The large sticker must cover area the small one leaves untouched.
        val ringOnly = changedFraction(frameB, frameNone, fLarge[0], fLarge[0] + (fSmall[0] - fLarge[0]), fLarge[2], fLarge[3])
        assertTrue("Large sticker did not extend past the small footprint: $ringOnly", ringOnly > 0.10)
        assertTrue("Small sticker extended past its own footprint",
            changedFraction(frameA, frameNone, fLarge[0], fLarge[0] + (fSmall[0] - fLarge[0]), fLarge[2], fLarge[3]) < 0.02)
    }

    @Test
    fun rotationChangesStickerFootprint() {
        val upright = heart(); val rotated = heart(rotation = 90f)
        val plain = project()
        val a = export(project(stickers = listOf(upright)), ExportResolution.RES_720P, "sticker-rot-0.mp4")
        val b = export(project(stickers = listOf(rotated)), ExportResolution.RES_720P, "sticker-rot-90.mp4")
        val none = export(plain, ExportResolution.RES_720P, "sticker-rot-none.mp4")
        val frameA = frameAt(a, 1_500_000); val frameB = frameAt(b, 1_500_000); val frameNone = frameAt(none, 1_500_000)
        // A non-square asset rotated 90° transposes its footprint, so the two
        // decodes must differ where the aspect changes.
        val diff = changedFraction(frameA, frameB, 0.36f, 0.64f, 0.3f, 0.7f)
        assertTrue("Rotation produced an identical frame: $diff", diff > 0.05)
        assertTrue("Upright sticker not rendered", changedFraction(frameA, frameNone, 0.43f, 0.57f, 0.4f, 0.6f) > 0.10)
        assertTrue("Rotated sticker not rendered", changedFraction(frameB, frameNone, 0.43f, 0.57f, 0.4f, 0.6f) > 0.10)
    }

    @Test
    fun stickerRendersAbovePipOverlayAndBelowText_viaCompositionEffectPath() {
        // With a PiP layer present, stickers composite through the
        // composition-level effect list rather than per clip. This exercises
        // that second wiring path and its layer contract.
        val pip = OverlayClip(id = "pip", mediaRefId = "probe_green_720p_6s.mp4",
            timelineStartMicros = 0L, timelineDurationMicros = 3_000_000L,
            sourceStartMicros = 0L, sourceEndMicros = 3_000_000L, muted = true,
            transform = VisualTransform(scale = 0.5f, offsetX = 0f, offsetY = 0f), zIndex = 0)
        val sticker = heart(scale = 1.4f)
        val caption = TextOverlay(id = "t", text = "CAPTION", fontSizeSp = 40f,
            colorArgb = 0xFFFFFFFF.toInt(), hasOutline = true, positionX = 0.5f, positionY = 0.5f,
            startMicros = 0L, durationMicros = 3_000_000L)

        val full = project(stickers = listOf(sticker), overlays = listOf(pip), text = listOf(caption))
        val noSticker = project(overlays = listOf(pip), text = listOf(caption))
        val a = export(full, ExportResolution.RES_720P, "sticker-pip-text-with.mp4")
        val b = export(noSticker, ExportResolution.RES_720P, "sticker-pip-text-without.mp4")
        val frameA = frameAt(a, 1_500_000); val frameB = frameAt(b, 1_500_000)
        assertEquals(1280, frameA.width)

        // The sticker must alter the frame even over the PiP layer.
        val f = footprint(sticker, 1280, 720)
        val changed = changedFraction(frameA, frameB, f[0], f[1], f[2], f[3])
        assertTrue("Sticker not composited over PiP: $changed", changed > 0.10)
        frameA.recycle(); frameB.recycle()
    }

    @Test
    fun stickerRendersOverPhotoClip() {
        val photo = File(mediaDir, "sticker-photo.png")
        if (!photo.exists()) {
            val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.argb(255, 20, 60, 140))
            photo.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        val ref = MediaRef(id = "photo", uri = android.net.Uri.fromFile(photo).toString(),
            type = MediaType.Photo, displayName = "sticker-photo.png", mimeType = "image/png",
            width = 640, height = 360, photoOrientation = 1)
        val base = projectCreator.create("Photo sticker", listOf(ref), canvas = CanvasSettings(width = 640, height = 360))
        val withSticker = base.copy(timeline = base.timeline.copy(stickers = listOf(heart())))
        val a = export(withSticker, ExportResolution.RES_720P, "sticker-photo-with.mp4")
        val b = export(base, ExportResolution.RES_720P, "sticker-photo-without.mp4")
        val frameA = frameAt(a, 500_000); val frameB = frameAt(b, 500_000)
        val f = footprint(heart(), frameA.width, frameA.height)
        val changed = changedFraction(frameA, frameB, f[0], f[1], f[2], f[3])
        assertTrue("Sticker not rendered over a photo clip: $changed", changed > 0.15)
        frameA.recycle(); frameB.recycle()
    }

    @Test
    fun stickerSurvivesProjectDocumentRoundTrip_withIdenticalRenderedPixels() {
        // The same Json configuration the document storage layer uses.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val original = project(stickers = listOf(heart(scale = 1.2f, rotation = 30f, opacity = 0.9f)))
        val reloaded = json.decodeFromString<Project>(json.encodeToString(Project.serializer(), original))
        assertEquals(original.timeline.stickers, reloaded.timeline.stickers)

        val a = export(original, ExportResolution.RES_720P, "sticker-roundtrip-original.mp4")
        val b = export(reloaded, ExportResolution.RES_720P, "sticker-roundtrip-reloaded.mp4")
        val frameA = frameAt(a, 1_500_000); val frameB = frameAt(b, 1_500_000)
        val f = footprint(original.timeline.stickers[0], 1280, 720)
        // Same document must produce the same pixels; allow only codec noise.
        val drift = changedFraction(frameA, frameB, f[0], f[1], f[2], f[3])
        assertTrue("Reload changed the rendered sticker: $drift", drift < 0.05)
        frameA.recycle(); frameB.recycle()
    }

    @Test
    fun legacyDocumentWithoutStickersStillLoadsAndExports() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacy = """{"id":"legacy-sticker","name":"Legacy","createdAtMillis":0,"updatedAtMillis":0,
            "version":1,"canvas":{"width":1280,"height":720},
            "mediaRefs":[{"id":"probe_blue_720p_3s.mp4","uri":"${android.net.Uri.fromFile(File(mediaDir, "probe_blue_720p_3s.mp4"))}",
              "type":"Video","displayName":"probe","mimeType":"video/mp4","durationMicros":3000000,"width":1280,"height":720,"hasAudio":false}],
            "timeline":{"videoClips":[{"id":"main","mediaRefId":"probe_blue_720p_3s.mp4","sourceStartMicros":0,"sourceEndMicros":3000000,
              "timelineStartMicros":0,"timelineDurationMicros":3000000,"muted":true}]}}"""
        val project = json.decodeFromString<Project>(legacy)
        assertTrue(project.timeline.stickers.isEmpty())
        val out = export(project, ExportResolution.RES_720P, "sticker-legacy.mp4")
        val frame = frameAt(out, 1_500_000)
        assertEquals(1280, frame.width)
        frame.recycle()
    }

    @Test
    fun missingAssetDegradesToInvisibleWithoutFailingExport() {
        val unknown = StickerClip(id = "ghost", assetKey = "not-in-catalog", timelineDurationMicros = 3_000_000L)
        val p = project(stickers = listOf(unknown))
        val out = export(p, ExportResolution.RES_720P, "sticker-missing-asset.mp4")
        val plain = export(project(), ExportResolution.RES_720P, "sticker-missing-asset-plain.mp4")
        val frameA = frameAt(out, 1_500_000); val frameB = frameAt(plain, 1_500_000)
        // No pixels should change: an unknown asset renders nothing rather than
        // crashing the frame pipeline or drawing a fallback glyph.
        val drift = changedFraction(frameA, frameB, 0f, 1f, 0f, 1f)
        assertTrue("Unknown sticker asset altered the frame: $drift", drift < 0.02)
        frameA.recycle(); frameB.recycle()
    }
}
