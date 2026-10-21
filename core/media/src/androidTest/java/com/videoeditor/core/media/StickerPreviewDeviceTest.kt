package com.videoeditor.core.media

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import androidx.media3.common.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.model.CanvasSettings
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.StickerClip
import com.videoeditor.core.model.Timeline
import com.videoeditor.editing.ProjectCreator
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves stickers render in the live preview pipeline, not only in export:
 * the same project is rendered headless through CompositionPreviewController
 * with and without a sticker, and the decoded preview frames are compared at
 * the sticker's model footprint.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class StickerPreviewDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var mediaDir: File

    @Before fun fixture() {
        mediaDir = retainedMediaTestDirectory(context)
        val target = File(mediaDir, "probe_blue_720p_3s.mp4")
        if (!target.exists()) context.assets.open("probe_blue_720p_3s.mp4").use { i -> target.outputStream().use(i::copyTo) }
    }

    private fun project(withSticker: Boolean): Project {
        val file = File(mediaDir, "probe_blue_720p_3s.mp4")
        val retriever = MediaMetadataRetriever()
        val ref = try {
            retriever.setDataSource(file.absolutePath)
            MediaRef("blue", android.net.Uri.fromFile(file).toString(), MediaType.Video, "blue",
                "video/mp4", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000,
                1280, 720, hasAudio = false)
        } finally { retriever.release() }
        return ProjectCreator().create("preview-sticker", listOf(ref), canvas = CanvasSettings(width = 1280, height = 720))
            .copy(timeline = Timeline(
                videoClips = listOf(com.videoeditor.core.model.Clip(
                    "main", "blue", 0L, 3_000_000L, muted = true)),
                stickers = if (withSticker) listOf(StickerClip(
                    id = "s", assetKey = "premium_heart",
                    timelineStartMicros = 0L, timelineDurationMicros = 3_000_000L)) else emptyList(),
            ))
    }

    private fun renderFrame(project: Project, atMicros: Long): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val lock = Any()
        var latest: Bitmap? = null
        var frames = 0
        val thread = android.os.HandlerThread("sticker-preview").apply { start() }
        val reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ images ->
            val image = images.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.first()
                val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                if (bitmap !== padded) padded.recycle()
                synchronized(lock) { latest?.recycle(); latest = bitmap; frames++ }
            } finally { image.close() }
        }, android.os.Handler(thread.looper))
        lateinit var controller: CompositionPreviewController
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(predicate: () -> Boolean) {
            val until = android.os.SystemClock.elapsedRealtime() + 20000
            while (!predicate() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(25)
            assertTrue("No preview frame rendered: ${controller.state.value}", predicate())
        }
        try {
            main {
                controller = CompositionPreviewController.Factory(context, CompositionBuilder()).create()
                controller.attachHeadlessSurface(reader.surface, Size(1280, 720))
                controller.setProject(project)
            }
            await { synchronized(lock) { frames > 0 } }
            val before = synchronized(lock) { frames }
            main { controller.seekTo(atMicros) }
            await { synchronized(lock) { frames > before } }
            Thread.sleep(300)
            return synchronized(lock) { latest!!.copy(Bitmap.Config.ARGB_8888, false) }
        } finally {
            main { controller.release() }
            reader.close()
            thread.quitSafely()
        }
    }

    @Test fun stickerAppearsInPreviewAtModelFootprint() {
        val withSticker = renderFrame(project(withSticker = true), 1_500_000)
        val plain = renderFrame(project(withSticker = false), 1_500_000)
        // Sticker footprint: 25% of the 720 short edge at canvas center.
        var changed = 0
        val x0 = 1280 / 2 - 100; val x1 = 1280 / 2 + 100
        val y0 = 720 / 2 - 100; val y1 = 720 / 2 + 100
        var total = 0
        for (y in y0 until y1 step 8) for (x in x0 until x1 step 8) {
            total++
            val a = withSticker.getPixel(x, y); val b = plain.getPixel(x, y)
            val dr = kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF))
            val dg = kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
            val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
            if (dr + dg + db > 48) changed++
        }
        assertTrue("Sticker missing from preview: $changed/$total pixels changed", changed > total / 8)
        // A region clear of the sticker must be untouched.
        var outsideChanged = 0
        var outsideTotal = 0
        for (y in 40 until 140 step 8) for (x in 40 until 200 step 8) {
            outsideTotal++
            val a = withSticker.getPixel(x, y); val b = plain.getPixel(x, y)
            val dr = kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF))
            val dg = kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
            val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
            if (dr + dg + db > 48) outsideChanged++
        }
        assertTrue("Sticker bled outside its footprint in preview: $outsideChanged/$outsideTotal",
            outsideChanged < outsideTotal / 50)
        withSticker.recycle(); plain.recycle()
    }
}
