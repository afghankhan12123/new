package com.videoeditor.core.media

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.Size
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.*
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreviewPixelParityDeviceTest {
    @Test fun transformed_graded_multilingual_caption_preview_matches_decoded_export() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixtureDir = File(context.filesDir,
            "preview-parity-fixtures/run-${android.os.SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
        val source = File(fixtureDir, "preview-parity-source.mp4")
        context.assets.open("color_grade_motion.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val base = ProjectCreator().create("Pixel parity", listOf(ref), canvas = CanvasSettings(width = 1280, height = 720))
        val project = base.copy(timeline = base.timeline.copy(videoClips = base.timeline.videoClips.map {
            it.copy(sourceEndMicros = 1_000_000, playbackSpeed = 2f, transform = VisualTransform(flipHorizontal = true, scale = .9f),
                filter = FilterSettings("film", .6f), colorAdjustments = ColorAdjustments(temperature = .3f, vignette = .25f))
        }, textOverlays = listOf(TextOverlay("multilingual", "English پښتو اردو العربية\n世界 😀", fontId = "serif", fontSizeSp = 30f,
            hasOutline = true, hasShadow = true, rotationDegrees = 8f, startMicros = 200_000, durationMicros = 200_000))))
        val output = File(fixtureDir, "preview-parity-export.mp4")
        assertTrue(ExportEngine(context, CompositionBuilder()).export(project,
            ExportRequest(project.id, output, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)) {}.isSuccess)
        val untitled = File(fixtureDir, "preview-parity-plain.mp4")
        assertTrue(ExportEngine(context, CompositionBuilder()).export(project.copy(timeline = project.timeline.copy(textOverlays = emptyList())),
            ExportRequest(project.id, untitled, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)) {}.isSuccess)
        val lock = Any()
        var latest: Bitmap? = null
        var frameCount = 0
        val thread = android.os.HandlerThread("parity-frames").apply { start() }
        val reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ images ->
            val image = images.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.first()
                val paddedWidth = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                if (bitmap !== padded) padded.recycle()
                synchronized(lock) { latest?.recycle(); latest = bitmap; frameCount++ }
            } finally { image.close() }
        }, android.os.Handler(thread.looper))
        lateinit var controller: CompositionPreviewController
        var controllerCreated = false
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(predicate: () -> Boolean) {
            val until = android.os.SystemClock.elapsedRealtime() + 20000
            while (!predicate() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(25)
            assertTrue("No decoded preview frame: ${controller.state.value}", predicate())
        }
        try {
            main { controller = CompositionPreviewController.Factory(context, CompositionBuilder()).create()
                controllerCreated = true
                controller.attachHeadlessSurface(reader.surface, Size(1280,720)); controller.setProject(project) }
            await { synchronized(lock) { frameCount > 0 } }
            val before = synchronized(lock) { frameCount }
            main { controller.seekTo(300_000) }
            await { synchronized(lock) { frameCount > before } }
            val preview = synchronized(lock) { latest!!.copy(Bitmap.Config.ARGB_8888, false) }
            val metadata = MediaMetadataRetriever()
            val exported = try { metadata.setDataSource(output.absolutePath); metadata.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST)!! }
                finally { metadata.release() }
            val plainReader = MediaMetadataRetriever()
            val plain = try { plainReader.setDataSource(untitled.absolutePath); plainReader.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST)!! }
                finally { plainReader.release() }
            var error = 0L; var samples = 0
            var captionCells = 0; var matchingCaptionCells = 0
            for (y in 5 until 715 step 5) for (x in 5 until 1275 step 5) {
                val a = preview.getPixel(x,y); val b = exported.getPixel(x,y)
                val c = plain.getPixel(x,y)
                fun difference(left: Int, right: Int) = listOf(0,8,16).sumOf { kotlin.math.abs((left shr it and 255) - (right shr it and 255)) }
                if (difference(b, c) > 100) { captionCells++; if (difference(a, b) < 70) matchingCaptionCells++ }
                for (shift in listOf(0,8,16)) { error += kotlin.math.abs((a shr shift and 255) - (b shr shift and 255)); samples++ }
            }
            val meanError = error.toDouble() / samples
            android.util.Log.i("PreviewParity", "mean RGB error=$meanError samples=$samples")
            assertTrue("Rendered preview differs from decoded export: $meanError", meanError < 12.0)
            assertTrue("Exported caption missing: $captionCells", captionCells > 150)
            assertTrue("Timed caption mismatch: $matchingCaptionCells/$captionCells", matchingCaptionCells.toDouble() / captionCells > .85)
            preview.recycle(); exported.recycle(); plain.recycle()
        } finally {
            if (controllerCreated) main { controller.release() }
            reader.close(); thread.quitSafely(); thread.join()
            synchronized(lock) { latest?.recycle() }
        }
    }
    @Test fun extensionless_photo_and_late_trimmed_speed_periods_decode_at_their_model_times() =
        verifyLateSplit("color_grade_motion.mp4")

    @Test fun photo_first_audible_speed_split_exports_and_seeks_without_losing_audio() =
        verifyLateSplit("clip2_480p_5s.mp4")

    @Test fun rounded_speed_primary_with_one_audio_sequence_restores_decoded_frames() =
        verifyLateSplit("clip2_480p_5s.mp4", audioTracks = 1)

    @Test fun rounded_speed_primary_with_voiceover_and_two_audio_sequences_restores_decoded_frames() =
        verifyLateSplit("clip2_480p_5s.mp4", audioTracks = 3)

    private fun verifyLateSplit(asset: String, audioTracks: Int = 0) = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixtureDir = File(context.filesDir,
            "preview-parity-fixtures/run-${android.os.SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
        val source = File(fixtureDir, "split-speed-source.mp4")
        val photo = File(fixtureDir, "extensionless-photo")
        val output = File(fixtureDir, "split-speed-export.mp4")
        context.assets.open(asset).use { input -> source.outputStream().use(input::copyTo) }
        val metadata = MediaMetadataRetriever().apply { setDataSource(source.absolutePath) }
        try {
            val frame = metadata.getFrameAtTime(100000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            photo.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }; frame.recycle()
        } finally { metadata.release() }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val still = MediaRef("still", Uri.fromFile(photo).toString(), MediaType.Photo, "Photo", mimeType = "image/png", width = 1280, height = 720)
        val base = ProjectCreator().create("Late split speed", listOf(still, ref), canvas = CanvasSettings(width = 1280, height = 720))
        val boundary = if (ref.hasAudio == true) 3787500L else 600000L
        // Media3 rounds speed-adjusted microseconds; the model truncates.
        // 4,999,999 - 3,787,500 at 1.5x rounds one us above the model.
        val end = if (ref.hasAudio == true) (if (audioTracks > 0) 4_999_999L else 5_000_000L) else 900000L
        val secondStart = 500000L + (boundary / 1.5f).toLong()
        val clips = listOf(
            base.timeline.videoClips[0].copy(photoDurationMicros = 500000),
            base.timeline.videoClips[1].copy(id = "speed-first", sourceStartMicros = 0, sourceEndMicros = boundary, playbackSpeed = 1.5f, timelineStartMicros = 500000),
            base.timeline.videoClips[1].copy(id = "speed-second", sourceStartMicros = boundary, sourceEndMicros = end, playbackSpeed = 1.5f, timelineStartMicros = secondStart),
        )
        val music = File(fixtureDir, "split-speed-music.m4a")
        context.assets.open("music_20s.m4a").use { input -> music.outputStream().use(input::copyTo) }
        val audioRef = MediaMetadataExtractor().extract(context, Uri.fromFile(music)).getOrNull()!!
        val visual = base.copy(timeline = base.timeline.copy(videoClips = clips))
        val project = visual.copy(mediaRefs = visual.mediaRefs + audioRef,
            timeline = visual.timeline.copy(audioClips = (1..audioTracks).map { index ->
                val offset = if (index == 3) 500_000L else 0L
                com.videoeditor.core.model.AudioClip("audio-$index", audioRef.id,
                    sourceEndMicros = visual.timeline.renderedDurationMicros - offset,
                    timelineStartMicros = offset)
            }))
        val exportResult = ExportEngine(context, CompositionBuilder()).export(project,
            ExportRequest(project.id, output, ExportResolution.RES_720P, FrameRate.FPS_30, ExportQuality.High)) {}
        assertTrue(exportResult.toString(), exportResult.isSuccess)
        val lock = Any(); var latest: Bitmap? = null; var frameCount = 0
        val thread = android.os.HandlerThread("split-speed-frames").apply { start() }
        val reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ images ->
            val image = images.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.first()
                val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                if (bitmap !== padded) padded.recycle()
                synchronized(lock) { latest?.recycle(); latest = bitmap; frameCount++ }
            } finally { image.close() }
        }, android.os.Handler(thread.looper))
        lateinit var controller: CompositionPreviewController; var created = false
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(predicate: () -> Boolean) {
            val until = android.os.SystemClock.elapsedRealtime() + 20000
            while (!predicate() && controller.state.value.error == null && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(25)
            assertTrue("Decoded split preview failed: ${controller.state.value}", predicate())
            assertNull(controller.state.value.error)
        }
        val exported = MediaMetadataRetriever().apply { setDataSource(output.absolutePath) }
        try {
            main { controller = CompositionPreviewController.Factory(context, CompositionBuilder()).create(); created = true
                controller.attachHeadlessSurface(reader.surface, Size(1280,720)); controller.setProject(project) }
            await { synchronized(lock) { frameCount > 0 } }
            for (time in listOf(0L, 200000L, 700000L, 200000L, 400000L, 100000L, secondStart + 100000L, 200000L, 700000L, secondStart + 100000L)) {
                val before = synchronized(lock) { frameCount }
                if (time != 0L) {
                    main { controller.seekTo(time) }
                    await { synchronized(lock) { frameCount > before } }
                }
                val preview = synchronized(lock) { latest!!.copy(Bitmap.Config.ARGB_8888, false) }
                val frame = exported.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)!!
                var error = 0L; var brightness = 0L; var samples = 0
                for (y in 10 until 710 step 10) for (x in 10 until 1270 step 10) {
                    val a = preview.getPixel(x,y); val b = frame.getPixel(x,y)
                    for (shift in listOf(0,8,16)) { error += kotlin.math.abs((a shr shift and 255) - (b shr shift and 255)); brightness += a shr shift and 255; samples++ }
                }
                assertTrue("Black preview at $time", brightness.toDouble() / samples > 20)
                assertTrue("Preview/export split clock differs at $time: ${error.toDouble()/samples}", error.toDouble()/samples < 20)
                preview.recycle(); frame.recycle()
            }
            val oldOwner = Any(); val newOwner = Any()
            main {
                controller.bindPlaybackOwner(oldOwner)
                controller.bindPlaybackOwner(newOwner)
                controller.suspendPlayback(oldOwner)
                controller.releasePlaybackOwner(oldOwner)
            }
            assertEquals("A stale editor lifecycle released the current player", 1, controller.activePlayerCount)
            val retainedPosition = controller.state.value.positionMicros
            main { controller.suspendPlayback(newOwner) }
            assertEquals(0, controller.activePlayerCount)
            assertEquals(retainedPosition, controller.state.value.positionMicros)
            val beforeResume = synchronized(lock) { frameCount }
            main { controller.resumePlayback(oldOwner) }
            assertEquals("A stale lifecycle resumed the player", 0, controller.activePlayerCount)
            main { controller.resumePlayback(newOwner) }
            await { synchronized(lock) { frameCount > beforeResume } }
            assertEquals(1, controller.activePlayerCount)
            assertEquals(retainedPosition, controller.state.value.positionMicros)
        } finally {
            if (created) main { controller.release() }
            exported.release(); reader.close(); thread.quitSafely(); thread.join()
            synchronized(lock) { latest?.recycle() }
        }
    }

}
