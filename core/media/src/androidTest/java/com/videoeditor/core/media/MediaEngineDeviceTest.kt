package com.videoeditor.core.media

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.Result
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.export.ExportEngine
import com.videoeditor.core.media.export.ExportRequest
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.CanvasSettings
import com.videoeditor.core.model.ExportQuality
import com.videoeditor.core.model.ExportResolution
import com.videoeditor.core.model.FrameRate
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Timing
import com.videoeditor.editing.ProjectCreator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the media pipeline with real media: metadata
 * extraction units, composition structure for preview and export, real
 * Transformer export, and real CompositionPlayer playback across clip
 * boundaries. Requires an emulator or physical device — none of this can be
 * verified on a JVM host.
 *
 * Test assets (bundled, generated with ffmpeg):
 *  - clip1_720p_6s.mp4  1280x720, 30fps, 6s, 440Hz tone
 *  - clip3_1080p_4s.mp4 1920x1080, 30fps, 4s, 220Hz tone
 *  - music_20s.m4a      660Hz tone
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class MediaEngineDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val extractor = MediaMetadataExtractor()
    private val compositionBuilder = CompositionBuilder()
    private val projectCreator = ProjectCreator()

    private lateinit var mediaDir: File

    @Before
    fun copyAssetsToFiles() {
        // MediaMetadataRetriever cannot read android_asset URIs; the app itself
        // works on real file/content URIs, so mirror that here.
        // Decoders reopen these inputs across seeks and structural edits.
        // Cache eviction must not remove a running test's source media.
        mediaDir = File(context.filesDir, "device-test-media").apply { mkdirs() }
        listOf("clip1_720p_6s.mp4", "clip3_1080p_4s.mp4", "music_20s.m4a", "color_grade_motion.mp4").forEach { name ->
            val target = File(mediaDir, name)
            if (!target.exists()) {
                context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
            }
        }
    }

    private fun fileUri(name: String): Uri = Uri.fromFile(File(mediaDir, name))

    private fun extractRef(name: String): MediaRef {
        val result = extractor.extract(context, fileUri(name))
        assertTrue("metadata extraction failed for $name: $result", result.isSuccess)
        return (result as Result.Success).value
    }

    @Test fun playback_toggle_cancels_pending_play_before_decoder_readiness() {
        val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
        val reader = ImageReader.newInstance(1280, 720, android.graphics.PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, android.os.Handler(android.os.Looper.getMainLooper()))
        try {
            val project = threeClipMixedResolutionProject()
            runOnMain {
                controller.attachHeadlessSurface(reader.surface, Size(1280, 720))
                controller.setProject(project)
                controller.play()
                assertFalse("decoder cannot be READY before returning to its event loop", controller.state.value.isPlaying)
                assertTrue(controller.state.value.playRequested)
                controller.togglePlayPause()
                assertFalse(controller.state.value.playRequested)
            }
            assertTrue(pollUntil(15_000) { controller.state.value.hasComposition && !controller.state.value.isBuffering })
            Thread.sleep(800)
            assertFalse("READY must not restart the cancelled play request", controller.state.value.isPlaying)
            assertTrue(controller.state.value.positionMicros < 100_000)
            assertNull(controller.state.value.error)
        } finally { runOnMain { controller.release() }; reader.close() }
    }

    @Test
    fun decoded_color_grades_and_transforms_change_real_export_pixels() = runBlocking<Unit> {
        val ref = extractRef("color_grade_motion.mp4")
        val base = projectCreator.create("Pixel effects", listOf(ref), canvas = CanvasSettings(width = 1280, height = 720))
        val short = base.copy(timeline = base.timeline.copy(videoClips = base.timeline.videoClips.map { it.copy(sourceEndMicros = 1_000_000L) }))
        fun decode(file: File): android.graphics.Bitmap {
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(file.absolutePath)
                return reader.getFrameAtTime(300_000L, MediaMetadataRetriever.OPTION_CLOSEST)!!
            } finally { reader.release() }
        }
        val plainFile = exportToFile(short, "grade-plain")
        val plain = decode(plainFile)
        val variants = listOf("vivid", "warm", "cool", "bw", "film", "vintage").map { id ->
            id to short.timeline.videoClips.first().copy(filter = com.videoeditor.core.model.FilterSettings(id))
        } + listOf(
            "adjust" to short.timeline.videoClips.first().copy(colorAdjustments = com.videoeditor.core.model.ColorAdjustments(brightness = 0.5f, temperature = 0.5f, vignette = 0.6f)),
            "transform" to short.timeline.videoClips.first().copy(transform = com.videoeditor.core.model.VisualTransform(rotationDegrees = 90, flipHorizontal = true, crop = com.videoeditor.core.model.CropRect(0.1f, 0.1f, 0.9f, 0.9f))),
        )
        try {
            for ((label, clip) in variants) {
                val output = exportToFile(short.copy(timeline = short.timeline.copy(videoClips = listOf(clip))), label)
                try {
                    val frame = decode(output)
                    try {
                        var difference = 0L
                        var samples = 0
                        for (y in 20 until minOf(plain.height, frame.height) step 20) {
                            for (x in 20 until minOf(plain.width, frame.width) step 20) {
                                val before = plain.getPixel(x, y); val after = frame.getPixel(x, y)
                                for (shift in listOf(0, 8, 16)) difference += kotlin.math.abs((before shr shift and 255) - (after shr shift and 255))
                                samples += 3
                            }
                        }
                        assertTrue("$label did not visibly change decoded pixels: $difference/$samples", difference.toDouble() / samples > 2.0)
                        assertEquals(1000.0, containerDurationMs(output).toDouble(), 150.0)
                    } finally { frame.recycle() }
                } finally { output.delete() }
            }
        } finally { plain.recycle(); plainFile.delete() }
    }

    @Test
    fun preview_plays_short_offset_music_with_real_silence_padding() {
        val base = twoClipProject()
        val music = extractRef("music_20s.m4a")
        val project = base.copy(mediaRefs = base.mediaRefs + music,
            timeline = base.timeline.copy(audioClips = listOf(AudioClip("offset", music.id,
                sourceEndMicros = 3_000_000L, timelineStartMicros = 2_000_000L,
                fadeInMicros = 500_000L, fadeOutMicros = 500_000L))))
        val variant = compositionBuilder.buildForPreview(project)
        assertTrue(variant.omittedAudioClipIds.isEmpty())
        assertEquals(2, variant.composition.sequences.size)
        assertPreviewBuilds(project, 8_000_000L)
    }

    @Test fun each_adjustment_and_filter_strength_affect_decoded_output() = runBlocking<Unit> {
        val ref = extractRef("color_grade_motion.mp4")
        val base = projectCreator.create("Individual grades", listOf(ref))
        val clip = base.timeline.videoClips.single().copy(sourceEndMicros = 600_000)
        val project = base.copy(timeline = base.timeline.copy(videoClips = listOf(clip)))
        fun frame(file: File): android.graphics.Bitmap {
            val reader = MediaMetadataRetriever()
            try { reader.setDataSource(file.absolutePath); return reader.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST)!! }
            finally { reader.release() }
        }
        val plainFile = exportToFile(project, "individual-plain")
        val plain = frame(plainFile)
        val adjustments = listOf(
            "brightness" to com.videoeditor.core.model.ColorAdjustments(brightness = .6f),
            "contrast" to com.videoeditor.core.model.ColorAdjustments(contrast = .6f),
            "saturation" to com.videoeditor.core.model.ColorAdjustments(saturation = -.6f),
            "temperature" to com.videoeditor.core.model.ColorAdjustments(temperature = .8f),
            "highlights" to com.videoeditor.core.model.ColorAdjustments(highlights = -.8f),
            "shadows" to com.videoeditor.core.model.ColorAdjustments(shadows = .8f),
            "sharpness" to com.videoeditor.core.model.ColorAdjustments(sharpen = 1f),
            "vignette" to com.videoeditor.core.model.ColorAdjustments(vignette = .8f),
        )
        try {
            for ((name, grade) in adjustments) {
                val output = exportToFile(project.copy(timeline = project.timeline.copy(videoClips = listOf(clip.copy(colorAdjustments = grade)))), name)
                val rendered = frame(output)
                var changed = 0
                for (y in 0 until plain.height step 3) for (x in 0 until plain.width step 3) {
                    val a = plain.getPixel(x, y); val b = rendered.getPixel(x, y)
                    val difference = listOf(0, 8, 16).sumOf { kotlin.math.abs((a shr it and 255) - (b shr it and 255)) }
                    if (difference > 20) changed++
                }
                assertTrue("$name affected too few decoded cells: $changed", changed > 150)
                rendered.recycle(); output.delete()
            }
            val identity = exportToFile(project.copy(timeline = project.timeline.copy(videoClips = listOf(clip.copy(filter = com.videoeditor.core.model.FilterSettings("vintage", 0f))))), "strength-zero")
            val rendered = frame(identity)
            var error = 0L; var count = 0
            for (y in 0 until plain.height step 10) for (x in 0 until plain.width step 10) {
                val a = plain.getPixel(x, y); val b = rendered.getPixel(x, y)
                for (shift in listOf(0, 8, 16)) { error += kotlin.math.abs((a shr shift and 255) - (b shr shift and 255)); count++ }
            }
            assertTrue("Zero-strength filter is not neutral: $error/$count", error.toDouble() / count < 1.5)
            rendered.recycle(); identity.delete()
        } finally { plain.recycle(); plainFile.delete() }
    }

    // -- metadata --

    @Test fun decoded_clip_fades_use_post_speed_time_without_extending_the_cut() = runBlocking<Unit> {
        val ref = extractRef("clip1_720p_6s.mp4")
        val base = projectCreator.create("Measured fade", listOf(ref))
        val project = base.copy(timeline = base.timeline.copy(videoClips = base.timeline.videoClips.map {
            it.copy(sourceEndMicros = 2_000_000, playbackSpeed = 2f, fadeInMicros = 400_000, fadeOutMicros = 400_000)
        }))
        val output = exportToFile(project, "timed-fade")
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(output.absolutePath)
            fun luminance(time: Long): Double {
                val frame = reader.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)!!
                var total = 0L; var count = 0
                for (y in 20 until frame.height - 20 step 20) for (x in 20 until frame.width - 20 step 20) {
                    val color = frame.getPixel(x, y)
                    total += android.graphics.Color.red(color) + android.graphics.Color.green(color) + android.graphics.Color.blue(color)
                    count++
                }
                frame.recycle(); return total.toDouble() / count
            }
            val middle = luminance(500_000)
            val incoming = luminance(100_000); val outgoing = luminance(900_000)
            assertTrue("Fade midpoint is black", middle > 100)
            assertTrue("Fade in uses wrong clock: $incoming/$middle", incoming in middle * .05..middle * .5)
            assertTrue("Fade out uses wrong clock: $outgoing/$middle", outgoing in middle * .05..middle * .5)
            assertEquals(1000.0, containerDurationMs(output).toDouble(), 100.0)
        } finally { reader.release(); output.delete() }
    }

    @Test fun decoded_offset_audio_fades_and_mute_follow_the_export_clock() = runBlocking<Unit> {
        val video = extractRef("clip1_720p_6s.mp4")
        val music = extractRef("music_20s.m4a")
        val base = projectCreator.create("Measured audio", listOf(video))
        val project = base.copy(mediaRefs = base.mediaRefs + music, timeline = base.timeline.copy(
            videoClips = base.timeline.videoClips.map { it.copy(sourceEndMicros = 4_000_000, muted = true) },
            audioClips = listOf(
                AudioClip("faded", music.id, sourceEndMicros = 2_000_000, timelineStartMicros = 1_000_000,
                    fadeInMicros = 500_000, fadeOutMicros = 500_000),
                AudioClip("muted", music.id, sourceEndMicros = 4_000_000, volume = 2f, muted = true),
            )))
        val output = exportToFile(project, "measured-audio")
        try {
            val rms = audioRms(output, listOf(200_000L..600_000L, 1_030_000L..1_130_000L,
                1_700_000L..2_200_000L, 2_850_000L..2_950_000L, 3_400_000L..3_800_000L))
            assertTrue("Audio leaked before offset: $rms", rms[0] < 5)
            assertTrue("Music absent: $rms", rms[2] > 500)
            assertTrue("Fade-in not applied: $rms", rms[1] < rms[2] * .4)
            assertTrue("Fade-out not applied: $rms", rms[3] < rms[2] * .4)
            assertTrue("Audio leaked after end / muted track audible: $rms", rms[4] < 5)
            assertEquals(4000.0, containerDurationMs(output).toDouble(), 150.0)
        } finally { output.delete() }
    }

    @Test
    fun metadata_duration_is_microseconds() {
        val ref = extractRef("clip1_720p_6s.mp4")
        val durationMicros = ref.durationMicros
        assertNotNull(durationMicros)
        // The file is 6 seconds; before the ms→µs fix this failed by a factor
        // of 1000 (6_000 instead of 6_000_000).
        assertTrue(
            "durationMicros was $durationMicros, expected ~6_000_000",
            durationMicros!! in 5_500_000L..6_500_000L,
        )
        assertEquals(1280, ref.width)
        assertEquals(720, ref.height)
    }

    // -- composition structure --

    @Test
    fun export_composition_contains_every_sequence() {
        val project = twoClipProject()
        val composition = compositionBuilder.build(project)
        assertEquals(1, composition.sequences.size)
        assertEquals(2, composition.sequences[0].editedMediaItems.size)
    }

    @Test
    fun preview_variant_retains_offset_audio_with_full_length_silence_padding() {
        val music = extractRef("music_20s.m4a")
        val base = twoClipProject()
        val offsetAudio = base.copy(
            mediaRefs = base.mediaRefs + music,
            timeline = base.timeline.copy(
                audioClips = listOf(
                    AudioClip(
                        id = "audio-offset",
                        mediaRefId = music.id,
                        sourceStartMicros = 0L,
                        sourceEndMicros = 5_000_000L,
                        timelineStartMicros = 2_000_000L,
                    ),
                    AudioClip(
                        id = "audio-immediate",
                        mediaRefId = music.id,
                        sourceStartMicros = 5_000_000L,
                        sourceEndMicros = 15_000_000L,
                        timelineStartMicros = 0L,
                    ),
                ),
            ),
        )

        val export = compositionBuilder.build(offsetAudio)
        assertEquals(3, export.sequences.size)
        // Both ends are padded so decoded audio spans the visual canvas.
        val exportedItems = export.sequences[1].editedMediaItems
        assertEquals(3, exportedItems.size)
        assertEquals(2_000_000L, exportedItems.first().durationUs)
        assertEquals(offsetAudio.timeline.renderedDurationMicros - 7_000_000L, exportedItems.last().durationUs)

        val preview = compositionBuilder.buildForPreview(offsetAudio)
        assertEquals(3, preview.composition.sequences.size)
        assertTrue(preview.omittedAudioClipIds.isEmpty())
        assertEquals(6, preview.composition.sequences[1].editedMediaItems.size)
        // Omitted offset audio and unrendered text must not cause additional
        // immediate audio omissions: the actual preview still spans 10s.
        val extendedModel = offsetAudio.copy(timeline = offsetAudio.timeline.copy(
            audioClips = offsetAudio.timeline.audioClips.map {
                if (it.id == "audio-offset") it.copy(sourceEndMicros = 20_000_000) else it
            },
            textOverlays = listOf(com.videoeditor.core.model.TextOverlay("long-text", "Title", durationMicros = 40_000_000)),
        ))
        val extendedPreview = compositionBuilder.buildForPreview(extendedModel)
        assertEquals(3, extendedPreview.composition.sequences.size)
        assertTrue(extendedPreview.omittedAudioClipIds.isEmpty())

    }

    @Test
    fun short_background_audio_is_padded_and_preview_still_plays() {
        val base = twoClipProject()
        val music = extractRef("music_20s.m4a")
        val project = base.copy(mediaRefs = base.mediaRefs + music, timeline = base.timeline.copy(
            audioClips = listOf(AudioClip("short-music", music.id, sourceEndMicros = 3_000_000)),
        ))
        val preview = compositionBuilder.buildForPreview(project)
        assertTrue(preview.omittedAudioClipIds.isEmpty())
        assertEquals(2, preview.composition.sequences.size)
        assertEquals(8, preview.composition.sequences[1].editedMediaItems.size)
        assertEquals("export must retain the short audio", 2, compositionBuilder.build(project).sequences.size)
        assertPreviewBuilds(project)
    }

    // -- real export --

    @Test
    fun export_produces_playable_mp4_with_model_duration() = runBlocking<Unit> {
        val project = twoClipProject()
        val output = File(retainedMediaTestDirectory(context), "export-test-${System.currentTimeMillis()}.mp4")
        val engine = ExportEngine(context, compositionBuilder)

        var sawProgress = false
        val result = engine.export(
            project,
            ExportRequest(
                projectId = project.id,
                outputFile = output,
                resolution = ExportResolution.RES_720P,
                frameRate = FrameRate.FPS_30,
                quality = ExportQuality.Standard,
            ),
        ) { progress -> if (progress.fraction > 0f) sawProgress = true }

        assertTrue(
            "export failed: ${(result as? Result.Failure)?.error}",
            result.isSuccess,
        )
        assertTrue("no real progress reported", sawProgress)
        assertTrue("output suspiciously small (${output.length()} bytes)", output.length() > 20_000)
        assertHasNonBlankVideoFrames(output)

        // The exported file must match the model duration: clip1 6s + clip3 4s.
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                .toLong()
            assertEquals(10_000.0, durationMs.toDouble(), 600.0)
        } finally {
            retriever.release()
            output.delete()
        }
    }

    // -- real preview playback --

    @Test
    fun preview_plays_across_clip_boundary_and_seeks() {
        val project = twoClipProject()
        val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
        val imageThread = android.os.HandlerThread("test-image-thread").apply { start() }
        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 2).apply {
            setOnImageAvailableListener(
                { reader -> reader.acquireLatestImage()?.close() },
                android.os.Handler(imageThread.looper),
            )
        }

        try {
            runOnMain {
                controller.attachHeadlessSurface(imageReader.surface, Size(1280, 720))
                controller.setProject(project)
                controller.play()
            }

            val boundaryMicros = 6_000_000L
            val reachedBoundary = pollUntil(timeoutMs = 90_000L) {
                controller.state.value.positionMicros >= boundaryMicros
            }
            val state = controller.state.value
            assertNull("preview error: ${state.error}", state.error)
            assertTrue("hasComposition was false", state.hasComposition)
            assertEquals(project.timeline.totalDurationMicros, state.durationMicros)
            assertTrue(
                "playback stalled before the clip boundary (position ${state.positionMicros})",
                reachedBoundary,
            )

            // Seek backwards across the boundary, then verify playback resumes.
            runOnMain { controller.pause() }
            runOnMain { controller.seekTo(3_000_000L) }
            val seeked = pollUntil(timeoutMs = 30_000L) {
                controller.state.value.positionMicros in 2_500_000L..4_000_000L
            }
            assertTrue("seek did not land near 3s: ${controller.state.value.positionMicros}", seeked)

            runOnMain { controller.play() }
            val resumed = pollUntil(timeoutMs = 60_000L) {
                controller.state.value.positionMicros > 3_500_000L
            }
            assertTrue("playback did not resume after seek", resumed)
        } finally {
            runOnMain { controller.release() }
            imageReader.close()
            imageThread.quitSafely()
        }
    }

    /** Actual output-buffer arrival, independent of optimistic controller position. */
    @Test fun decoded_output_seek_and_structural_edit_latency() {
        val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
        val thread = android.os.HandlerThread("output-latency").apply { start() }
        var project = threeClipMixedResolutionProject()
        data class Output(val timestamp: Long, val arrival: Long, val pixels: IntArray)
        val arrivals = java.util.concurrent.LinkedBlockingQueue<Output>()
        val reader = ImageReader.newInstance(1280, 720, android.graphics.PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ output ->
                output.acquireLatestImage()?.use { image ->
                    val arrived = android.os.SystemClock.elapsedRealtimeNanos()
                    val plane = image.planes[0]
                    val pixels = IntArray(36)
                    for (y in 0 until 6) for (x in 0 until 6) {
                        val offset = ((image.height * (y + .5f) / 6).toInt() * plane.rowStride +
                            (image.width * (x + .5f) / 6).toInt() * plane.pixelStride)
                        pixels[y * 6 + x] = android.graphics.Color.rgb(plane.buffer.get(offset).toInt() and 255,
                            plane.buffer.get(offset+1).toInt() and 255, plane.buffer.get(offset+2).toInt() and 255)
                    }
                    arrivals.offer(Output(image.timestamp, arrived, pixels))
                }
            }, android.os.Handler(thread.looper))
        }
        // Decode reference pixels before dispatch, so the observer does not contend
        // with the preview decoder inside the measured seek/rebuild interval.
        fun expectedPixelsAt(targetMicros: Long): IntArray {
            val clip = project.timeline.clipAt(targetMicros)!!.second
            val retriever = MediaMetadataRetriever()
            val expected = android.graphics.Bitmap.createBitmap(1280,720,android.graphics.Bitmap.Config.ARGB_8888)
            try {
                retriever.setDataSource(context, Uri.parse(project.mediaRef(clip.mediaRefId)!!.uri))
                val frame = retriever.getFrameAtTime(clip.sourceTimeAt(targetMicros),MediaMetadataRetriever.OPTION_CLOSEST)!!
                val scale = minOf(1280f/frame.width,720f/frame.height)
                val left = (1280-frame.width*scale)/2
                val top = (720-frame.height*scale)/2
                android.graphics.Canvas(expected).drawBitmap(frame,null,android.graphics.RectF(left,top,1280-left,720-top),null)
                frame.recycle()
            } finally { retriever.release() }
            val expectedPixels = IntArray(36) { index -> expected.getPixel((1280*(index%6+.5f)/6).toInt(),(720*(index/6+.5f)/6).toInt()) }
            expected.recycle()
            return expectedPixels
        }
        fun awaitOutput(label: String, start: Long, targetMicros: Long, expectedPixels: IntArray): Long {
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline) {
                val arrival = arrivals.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                if (arrival.arrival < start) continue
                val error = arrival.pixels.indices.sumOf { index ->
                    val actual = arrival.pixels[index]; val wanted = expectedPixels[index]
                    kotlin.math.abs(android.graphics.Color.red(actual)-android.graphics.Color.red(wanted)) +
                        kotlin.math.abs(android.graphics.Color.green(actual)-android.graphics.Color.green(wanted)) +
                        kotlin.math.abs(android.graphics.Color.blue(actual)-android.graphics.Color.blue(wanted))
                } / 108.0
                if (error > 5) continue
                android.util.Log.i("DecodedPerf", "$label targetUs=$targetMicros outputTimestamp=${arrival.timestamp} outputArrivalMs=${(arrival.arrival-start)/1e6} coarsePixelError=$error")
                return arrival.arrival
            }
            throw AssertionError("No actual output after $label; ${controller.state.value.error}")
        }
        try {
            val initialPixels = expectedPixelsAt(0)
            val start = android.os.SystemClock.elapsedRealtimeNanos()
            runOnMain { controller.attachHeadlessSurface(reader.surface, Size(1280,720)); controller.setProject(project) }
            awaitOutput("initial", start, 0, initialPixels)
            for (target in listOf(2_000_000L,4_000_000L,1_000_000L,7_000_000L,12_000_000L,3_000_000L)) {
                val expected = expectedPixelsAt(target)
                arrivals.clear()
                val input = android.os.SystemClock.elapsedRealtimeNanos()
                var dispatch = 0L
                runOnMain { dispatch = android.os.SystemClock.elapsedRealtimeNanos(); controller.seekTo(target) }
                val output = awaitOutput("seek", dispatch, target, expected)
                android.util.Log.i("DecodedPerf", "inputDispatchMs=${(dispatch-input)/1e6} seekOutputMs=${(output-dispatch)/1e6}")
            }
            val first = project.timeline.videoClips.first()
            val commands = listOf(
                com.videoeditor.editing.commands.TimelineCommands.SplitClip(first.id, 2_000_000),
                com.videoeditor.editing.commands.TimelineCommands.TrimClip(first.id, 200_000, 1_800_000, 0, 2_000_000),
                com.videoeditor.editing.commands.TimelineCommands.MoveClip(first.id, 2),
                com.videoeditor.editing.commands.TimelineCommands.DuplicateClip(first.id),
                com.videoeditor.editing.commands.TimelineCommands.DeleteClip(first.id),
            )
            for (command in commands) {
                for (mode in listOf("apply", "undo", "redo")) {
                    val modelStart = android.os.SystemClock.elapsedRealtimeNanos()
                    project = if (mode == "undo") command.undo(project) else command.apply(project)
                    val modelNanos = android.os.SystemClock.elapsedRealtimeNanos() - modelStart
                    val resumeTarget = controller.state.value.positionMicros
                    val expected = expectedPixelsAt(resumeTarget)
                    arrivals.clear()
                    val startEdit = android.os.SystemClock.elapsedRealtimeNanos()
                    runOnMain { controller.setProject(project) }
                    val output = awaitOutput("${command.description} $mode", startEdit, resumeTarget, expected)
                    assertFalse(controller.state.value.isPlaying)
                    assertEquals(1, controller.activePlayerCount)
                    android.util.Log.i("DecodedPerf", "${command.description} $mode modelMs=${modelNanos/1e6} constructionMs=${controller.lastRebuildTiming!!.constructionNanos/1e6} initializationMs=${controller.lastRebuildTiming!!.initializationNanos/1e6} editOutputMs=${(output-startEdit)/1e6}")
                    // Verify a backwards seek on the edited composition, not only initialization.
                    val editedTarget = if (controller.state.value.positionMicros == 1_000_000L) 1_200_000L else 1_000_000L
                    val editedPixels = expectedPixelsAt(editedTarget)
                    arrivals.clear()
                    val seekStart = android.os.SystemClock.elapsedRealtimeNanos()
                    runOnMain { controller.seekTo(editedTarget) }
                    awaitOutput("edited seek", seekStart, editedTarget, editedPixels)
                }
            }
        } finally {
            runOnMain { controller.release(); assertEquals(0, controller.activePlayerCount) }
            reader.close(); thread.quitSafely(); thread.join(5_000)
        }
    }

    @Test fun cold_scrub_burst_produces_latest_output_without_rebuilding() = verifyColdSeekBurst(false)

    @Test fun cold_boundary_seek_burst_produces_latest_decoded_output_without_rebuilding() = verifyColdSeekBurst(true)

    private fun verifyColdSeekBurst(boundarySeek: Boolean) {
        val controller = CompositionPreviewController.Factory(context,compositionBuilder).create()
        val thread = android.os.HandlerThread("cold-scrub-output").apply { start() }
        val rendered = java.util.concurrent.atomic.AtomicLong()
        val reader = ImageReader.newInstance(1280,720,android.graphics.PixelFormat.RGBA_8888,3).apply {
            setOnImageAvailableListener({ output -> output.acquireLatestImage()?.use { rendered.incrementAndGet() } },android.os.Handler(thread.looper))
        }
        try {
            runOnMain {
                controller.attachHeadlessSurface(reader.surface,Size(1280,720))
                controller.setProject(threeClipMixedResolutionProject())
                repeat(120) {
                    val target = (it % 3) * 6_000_000L
                    if (boundarySeek) controller.seekTo(target) else controller.seekForScrub(target)
                }
                controller.flushScrubSeek()
                assertEquals("pending target must survive an immediate structural edit",12_000_000L,controller.state.value.positionMicros)
            }
            assertTrue("initial frame and latest scrub frame must both arrive",pollUntil(30_000) { rendered.get() >= 2 })
            assertEquals(12_000_000L,controller.state.value.positionMicros)
            assertEquals(1,controller.rebuildCount)
            assertNull(controller.state.value.error)
        } finally {runOnMain {controller.release()};reader.close();thread.quitSafely();thread.join(5_000)}
    }

    @Test fun sustained_large_project_player_cleanup() {
        val base = threeClipMixedResolutionProject()
        val worker = android.os.HandlerThread("memory-output").apply { start() }
        val rendered = java.util.concurrent.atomic.AtomicLong()
        val reader = ImageReader.newInstance(1280,720,android.graphics.PixelFormat.RGBA_8888,3).apply {
            setOnImageAvailableListener({ output -> output.acquireLatestImage()?.use { rendered.incrementAndGet() } }, android.os.Handler(worker.looper))
        }
        try {
            for (count in listOf(3,10,30,50)) {
                val clips = (0 until count).map { index ->
                    base.timeline.videoClips[index % 3].copy(id = "stress-$index", timelineStartMicros = index * 1_000_000L,
                        sourceStartMicros = 0, sourceEndMicros = 1_000_000)
                }
                val project = base.copy(timeline = base.timeline.copy(videoClips = clips))
                repeat(3) { cycle ->
                    val beforeOpen = rendered.get()
                    val controller = CompositionPreviewController.Factory(context,compositionBuilder).create()
                    try {
                        runOnMain { controller.attachHeadlessSurface(reader.surface,Size(1280,720));controller.setProject(project);controller.play() }
                        assertTrue("initial preview must decode before warm stress",pollUntil(30_000) { rendered.get() > beforeOpen })
                        assertTrue(pollUntil(30_000) { controller.state.value.positionMicros > 200_000 })
                        runOnMain { controller.pause() }
                        // Requests across real display frames, in addition to the burst-coalescing regression.
                        repeat(24) { index ->
                            runOnMain { controller.seekForScrub((index % count) * 1_000_000L + 200_000) }
                            Thread.sleep(50)
                        }
                        val beforeFinal = rendered.get()
                        runOnMain {
                            repeat(120) { controller.seekForScrub((it % count) * 1_000_000L + 300_000) }
                            controller.flushScrubSeek()
                        }
                        assertTrue("decoder must supply output after the final stress seek", pollUntil(30_000) { rendered.get() > beforeFinal })
                        assertEquals((119 % count) * 1_000_000L + 300_000,controller.state.value.positionMicros)
                        assertEquals(1,controller.activePlayerCount)
                        assertEquals(1,controller.rebuildCount)
                        assertNull(controller.state.value.error)
                        val memory = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo)
                        android.util.Log.i("MemoryPerf", "clips=$count cycle=$cycle openPssKb=${memory.totalPss} managedAllocatedBytes=${Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()} nativeAllocatedBytes=${android.os.Debug.getNativeHeapAllocatedSize()}")
                    } finally { runOnMain {controller.release();assertEquals(0,controller.activePlayerCount)} }
                    val closed = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo)
                    android.util.Log.i("MemoryPerf","clips=$count cycle=$cycle closedPssKb=${closed.totalPss}")
                }
            }
        } finally {reader.close();worker.quitSafely();worker.join(5_000)}
    }

    // -- trim and speed: export must match the model --

    @Test
    fun export_honors_trim_range() = runBlocking<Unit> {
        // Use only the middle 3s of clip1 (6s file).
        val clip1 = extractRef("clip1_720p_6s.mp4")
        val clipModel = com.videoeditor.core.model.Clip(
            id = "trimmed-clip",
            mediaRefId = clip1.id,
            sourceStartMicros = 1_500_000L,
            sourceEndMicros = 4_500_000L,
            timelineStartMicros = 0L,
        )
        val project = projectCreator.create(
            name = "Trim test",
            mediaRefs = listOf(clip1),
            canvas = CanvasSettings(width = 1280, height = 720),
        ).copy(
            timeline = com.videoeditor.core.model.Timeline(videoClips = listOf(clipModel)),
        )
        assertEquals(3_000_000L, project.timeline.totalDurationMicros)

        val durationMs = exportAndMeasureDuration(project)
        assertEquals(3_000.0, durationMs.toDouble(), 400.0)
    }

    @Test
    fun export_honors_playback_speed() = runBlocking<Unit> {
        // clip1 is 6s; at 2x it must render as 3s.
        val clip1 = extractRef("clip1_720p_6s.mp4")
        val clipModel = com.videoeditor.core.model.Clip(
            id = "fast-clip",
            mediaRefId = clip1.id,
            sourceStartMicros = 0L,
            sourceEndMicros = 6_000_000L,
            timelineStartMicros = 0L,
            playbackSpeed = 2f,
        )
        val project = projectCreator.create(
            name = "Speed test",
            mediaRefs = listOf(clip1),
            canvas = CanvasSettings(width = 1280, height = 720),
        ).copy(timeline = com.videoeditor.core.model.Timeline(videoClips = listOf(clipModel)))
        assertEquals(3_000_000L, project.timeline.totalDurationMicros)

        val durationMs = exportAndMeasureDuration(project)
        assertEquals(3_000.0, durationMs.toDouble(), 400.0)
    }

    @Test
    fun preview_plays_speed_adjusted_composition_to_its_end() {
        // Speed in preview goes through CompositionPlayer's SpeedProviderMediaPeriod
        // path: a 6s source at 2x must finish in ~3s of composition time.
        val clip1 = extractRef("clip1_720p_6s.mp4")
        val clipModel = com.videoeditor.core.model.Clip(
            id = "fast-clip",
            mediaRefId = clip1.id,
            sourceStartMicros = 0L,
            sourceEndMicros = 6_000_000L,
            timelineStartMicros = 0L,
            playbackSpeed = 2f,
        )
        val project = projectCreator.create(
            name = "Speed preview",
            mediaRefs = listOf(clip1),
            canvas = CanvasSettings(width = 1280, height = 720),
        ).copy(timeline = com.videoeditor.core.model.Timeline(videoClips = listOf(clipModel)))

        val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
        val imageThread = android.os.HandlerThread("speed-test-image-thread").apply { start() }
        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 2).apply {
            setOnImageAvailableListener(
                { reader -> reader.acquireLatestImage()?.close() },
                android.os.Handler(imageThread.looper),
            )
        }
        try {
            runOnMain {
                controller.attachHeadlessSurface(imageReader.surface, Size(1280, 720))
                controller.setProject(project)
                controller.play()
            }
            assertEquals(3_000_000L, controller.state.value.durationMicros)
            assertNull("preview error: ${controller.state.value.error}", controller.state.value.error)

            val ended = pollUntil(timeoutMs = 90_000L) {
                controller.state.value.positionMicros >= 2_900_000L
            }
            assertTrue(
                "2x preview did not reach its 3s duration (position ${controller.state.value.positionMicros})",
                ended,
            )
        } finally {
            runOnMain { controller.release() }
            imageReader.close()
            imageThread.quitSafely()
        }
    }

    @Test
    fun export_speed_change_scales_audio_and_video_together() = runBlocking<Unit> {
        // The core A/V-sync guarantee for speed. A 6s source at 2x must produce a
        // 3s video track AND a 3s audio track. The previous SpeedChangeEffect
        // implementation time-scaled only the video, leaving audio at 6s — the
        // two tracks drift apart, which is exactly what this asserts against.
        val clip1 = extractRef("clip1_720p_6s.mp4")
        val clipModel = com.videoeditor.core.model.Clip(
            id = "fast-clip",
            mediaRefId = clip1.id,
            sourceStartMicros = 0L,
            sourceEndMicros = 6_000_000L,
            timelineStartMicros = 0L,
            playbackSpeed = 2f,
        )
        val project = projectCreator.create(
            name = "AV sync test",
            mediaRefs = listOf(clip1),
            canvas = CanvasSettings(width = 1280, height = 720),
        ).copy(timeline = com.videoeditor.core.model.Timeline(videoClips = listOf(clipModel)))

        val output = exportToFile(project, "av-sync")
        val trackDurationsMs = trackDurationsMs(output)
        val videoMs = trackDurationsMs.filterKeys { it.startsWith("video/") }.values.firstOrNull()
        val audioMs = trackDurationsMs.filterKeys { it.startsWith("audio/") }.values.firstOrNull()

        assertNotNull("export has no video track: $trackDurationsMs", videoMs)
        assertNotNull("export has no audio track: $trackDurationsMs", audioMs)
        assertEquals(3_000.0, videoMs!!.toDouble(), 500.0)
        assertEquals(3_000.0, audioMs!!.toDouble(), 500.0)
        // Tracks must not drift apart — the audible failure mode of the old code.
        assertEquals(0.0, videoMs.toDouble() - audioMs.toDouble(), 700.0)
        output.delete()
    }

    // -- structure survives edits --

    @Test fun sped_offset_music_keeps_its_window_and_natural_pitch() = runBlocking<Unit> {
        val video = extractRef("clip1_720p_6s.mp4")
        val music = extractRef("music_20s.m4a")
        for (speed in listOf(.5f, 1.5f, 2f)) {
            val base = projectCreator.create("Music speed $speed", listOf(video))
            val project = base.copy(mediaRefs = base.mediaRefs + music, timeline = base.timeline.copy(
                videoClips = base.timeline.videoClips.map { it.copy(muted = true) },
                audioClips = listOf(AudioClip("music", music.id, sourceStartMicros = 1_000_000,
                    sourceEndMicros = 3_000_000, timelineStartMicros = 1_000_000, playbackSpeed = speed))))
            val output = exportToFile(project, "music-speed-$speed")
            try {
                val end = 1_000_000L + (2_000_000 / speed).toLong()
                val rms = audioRms(output, listOf(200_000L..600_000L, 1_200_000L..1_700_000L,
                    (end + 250_000)..(end + 650_000)))
                assertTrue("Music started before its offset: $rms", rms[0] < 5)
                assertTrue("Sped music absent: $rms", rms[1] > 500)
                assertTrue("Music exceeded its scaled duration: $rms", rms[2] < 5)
                assertEquals("Music pitch changed at $speed", 660.0,
                    audioToneFrequency(output, 1_200_000L..1_700_000L), 12.0)
            } finally { output.delete() }
        }
    }

    @Test fun supported_speeds_preserve_pitch_and_track_clock_after_trim() = runBlocking<Unit> {
        val ref = extractRef("clip1_720p_6s.mp4")
        for (speed in listOf(.5f, 1f, 1.5f, 2f)) {
            val base = projectCreator.create("Speed $speed", listOf(ref))
            val project = base.copy(timeline = base.timeline.copy(videoClips = base.timeline.videoClips.map {
                it.copy(sourceStartMicros = 1_000_000, sourceEndMicros = 2_000_000, playbackSpeed = speed)
            }))
            val output = exportToFile(project, "pitch-$speed")
            try {
                val tracks = trackDurationsMs(output)
                val video = tracks.entries.first { it.key.startsWith("video/") }.value
                val audio = tracks.entries.first { it.key.startsWith("audio/") }.value
                val expected = 1000.0 / speed
                assertEquals("Video clock at $speed: $tracks", expected, video.toDouble(), 100.0)
                assertEquals("Audio clock at $speed: $tracks", expected, audio.toDouble(), 100.0)
                assertTrue("A/V end drift at $speed: $tracks", kotlin.math.abs(video - audio) < 100)
                val frequency = audioToneFrequency(output, 200_000L..450_000L)
                assertEquals("Pitch must stay natural at $speed", 440.0, frequency, 12.0)
                android.util.Log.i("SpeedSync", "speed=$speed videoMs=$video audioMs=$audio toneHz=$frequency")
            } finally { output.delete() }
        }
    }

    @Test
    fun preview_rebuilds_after_split_delete_and_reorder() {
        // Simulate the edit loop: play, apply structural commands, keep playing.
        val project = twoClipProject()
        val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
        val imageThread = android.os.HandlerThread("edit-test-image-thread").apply { start() }
        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 2).apply {
            setOnImageAvailableListener(
                { reader -> reader.acquireLatestImage()?.close() },
                android.os.Handler(imageThread.looper),
            )
        }
        try {
            runOnMain {
                controller.attachHeadlessSurface(imageReader.surface, Size(1280, 720))
                controller.setProject(project)
                controller.play()
            }
            assertTrue(
                "initial playback stalled",
                pollUntil(timeoutMs = 60_000L) { controller.state.value.positionMicros > 500_000L },
            )

            // Split the first clip at 3s.
            val firstClipId = project.timeline.videoClips[0].id
            val afterSplit = com.videoeditor.editing.history.EditHistory().let { history ->
                history.apply(
                    project,
                    com.videoeditor.editing.commands.TimelineCommands.SplitClip(firstClipId, 3_000_000L),
                )
            }
            assertEquals(3, afterSplit.timeline.videoClips.size)
            runOnMain { controller.setProject(afterSplit) }
            assertNull("split broke preview: ${controller.state.value.error}", controller.state.value.error)
            assertEquals(afterSplit.timeline.totalDurationMicros, controller.state.value.durationMicros)

            // Delete the last clip (10s -> 6s).
            val lastId = afterSplit.timeline.videoClips.last().id
            val afterDelete = com.videoeditor.editing.history.EditHistory().let { history ->
                history.apply(afterSplit, com.videoeditor.editing.commands.TimelineCommands.DeleteClip(lastId))
            }
            runOnMain { controller.setProject(afterDelete) }
            assertNull("delete broke preview: ${controller.state.value.error}", controller.state.value.error)
            assertEquals(6_000_000L, controller.state.value.durationMicros)

            // Duplicate one half of the split clip: two 3s clips become three.
            val history = com.videoeditor.editing.history.EditHistory()
            val soloId = afterDelete.timeline.videoClips[0].id
            val afterDuplicate = history.apply(
                afterDelete,
                com.videoeditor.editing.commands.TimelineCommands.DuplicateClip(soloId),
            )
            assertEquals(3, afterDuplicate.timeline.videoClips.size)
            val moved = history.apply(
                afterDuplicate,
                com.videoeditor.editing.commands.TimelineCommands.MoveClip(
                    afterDuplicate.timeline.videoClips[0].id,
                    1,
                ),
            )
            runOnMain { controller.setProject(moved) }
            assertNull("reorder broke preview: ${controller.state.value.error}", controller.state.value.error)
            assertEquals(9_000_000L, controller.state.value.durationMicros)

            // Playback resumes across the rebuilt composition.
            runOnMain { controller.play() }
            assertTrue(
                "playback did not resume after edits",
                pollUntil(timeoutMs = 60_000L) { controller.state.value.positionMicros > 500_000L },
            )
        } finally {
            runOnMain { controller.release() }
            imageReader.close()
            imageThread.quitSafely()
        }
    }

    @Test
    fun preview_plays_project_with_background_music() {
        // Requires real playback, not just a successful setComposition: the
        // audio sequence is where the unset-durationUs bug hid.
        assertPreviewBuilds(videoPlusMusicProject())
    }

    @Test
    fun preview_music_trimmed_to_video_length() {
        // Diagnostic: same project, but the music is trimmed to the video track
        // length. If this plays while the 20s version OOMs, the cost scales
        // with the audio sequence duration.
        assertPreviewBuilds(videoPlusMusicProject(musicEndMicros = 3_000_000L))
    }

    @Test
    fun preview_music_full_length_no_speed_change() {
        // Diagnostic: 20s music with the video at 1x. If this OOMs too, the
        // speed effect is not the trigger.
        assertPreviewBuilds(videoPlusMusicProject(playbackSpeed = 1f))
    }

    private fun assertPreviewBuilds(project: Project, mustReachMicros: Long = 500_000L) {
        val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
        val imageThread = android.os.HandlerThread("diag-thread").apply { start() }
        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 2).apply {
            setOnImageAvailableListener(
                { reader -> reader.acquireLatestImage()?.close() },
                android.os.Handler(imageThread.looper),
            )
        }
        try {
            runOnMain {
                controller.attachHeadlessSurface(imageReader.surface, Size(1280, 720))
                controller.setProject(project)
                controller.play()
            }
            val state = controller.state.value
            assertNull("preview error: ${state.error}", state.error)
            assertEquals(project.timeline.totalDurationMicros, state.durationMicros)
            // Construction succeeding is not enough — confirm it actually plays.
            val advanced = pollUntil(timeoutMs = 60_000L) {
                controller.state.value.positionMicros > mustReachMicros
            }
            assertTrue(
                "playback did not advance with the audio track present " +
                    "(position ${controller.state.value.positionMicros}, " +
                    "error ${controller.state.value.error})",
                advanced,
            )
        } finally {
            runOnMain { controller.release() }
            imageReader.close()
            imageThread.quitSafely()
        }
    }

    private fun videoPlusMusicProject(
        musicEndMicros: Long = 20_000_000L,
        playbackSpeed: Float = 2f,
    ): Project {
        val clip1 = extractRef("clip1_720p_6s.mp4")
        val music = extractRef("music_20s.m4a")
        val clipModel = com.videoeditor.core.model.Clip(
            id = "fast-clip",
            mediaRefId = clip1.id,
            sourceStartMicros = 0L,
            sourceEndMicros = 6_000_000L,
            timelineStartMicros = 0L,
            playbackSpeed = playbackSpeed,
        )
        val audioModel = AudioClip(
            id = "music",
            mediaRefId = music.id,
            sourceStartMicros = 0L,
            sourceEndMicros = musicEndMicros,
            timelineStartMicros = 0L,
            volume = 0.5f,
        )
        return projectCreator.create(
            name = "Audio mix test",
            mediaRefs = listOf(clip1, music),
            canvas = CanvasSettings(width = 1280, height = 720),
        ).copy(
            timeline = com.videoeditor.core.model.Timeline(
                videoClips = listOf(clipModel),
                audioClips = listOf(audioModel),
            ),
        )
    }

    @Test
    fun preview_position_never_exceeds_model_duration() {
        // Regression probe: the editor showed a playhead far past the project's
        // own duration. Asserts the player's duration matches the model and that
        // the playhead never runs beyond it while playing to the end.
        val project = threeClipMixedResolutionProject()
        val modelDuration = project.timeline.totalDurationMicros
        assertEquals(15_000_000L, modelDuration)

        val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
        val imageThread = android.os.HandlerThread("overrun-probe-thread").apply { start() }
        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 2).apply {
            setOnImageAvailableListener(
                { reader -> reader.acquireLatestImage()?.close() },
                android.os.Handler(imageThread.looper),
            )
        }
        val samples = mutableListOf<Long>()
        try {
            runOnMain {
                controller.attachHeadlessSurface(imageReader.surface, Size(1280, 720))
                controller.setProject(project)
                controller.play()
            }
            assertEquals(modelDuration, controller.state.value.durationMicros)

            // Sample until playback ends, recording every position seen.
            val deadline = System.currentTimeMillis() + 90_000L
            while (System.currentTimeMillis() < deadline) {
                val state = controller.state.value
                samples += state.positionMicros
                if (!state.isPlaying && state.positionMicros > 0L) break
                Thread.sleep(150)
            }

            val maxPosition = samples.maxOrNull() ?: 0L
            assertTrue(
                "playhead overran the model duration: max ${maxPosition}us vs $modelDuration us " +
                    "(${samples.size} samples, error ${controller.state.value.error})",
                maxPosition <= modelDuration + 500_000L,
            )
        } finally {
            runOnMain { controller.release() }
            imageReader.close()
            imageThread.quitSafely()
        }
    }

    private fun threeClipMixedResolutionProject(): Project {
        val refs = listOf(
            extractRef("clip1_720p_6s.mp4"),
            extractRef("clip3_1080p_4s.mp4"),
            extractRef("clip1_720p_6s.mp4"),
        )
        // Rebuild with distinct ids and pack to 6+4+6 = 16s... use 5s trim on the
        // third so the total is exactly 15s, matching the app's imported clips.
        var cursor = 0L
        val clips = listOf(
            com.videoeditor.core.model.Clip(
                id = "c1", mediaRefId = refs[0].id,
                sourceStartMicros = 0L, sourceEndMicros = 6_000_000L,
                timelineStartMicros = cursor,
            ).also { cursor += it.timelineDurationMicros },
            com.videoeditor.core.model.Clip(
                id = "c2", mediaRefId = refs[1].id,
                sourceStartMicros = 0L, sourceEndMicros = 4_000_000L,
                timelineStartMicros = cursor,
            ).also { cursor += it.timelineDurationMicros },
            com.videoeditor.core.model.Clip(
                id = "c3", mediaRefId = refs[2].id,
                sourceStartMicros = 0L, sourceEndMicros = 5_000_000L,
                timelineStartMicros = cursor,
            ).also { cursor += it.timelineDurationMicros },
        )
        return projectCreator.create(
            name = "Overrun probe",
            mediaRefs = refs,
            canvas = CanvasSettings(width = 1280, height = 720),
        ).copy(timeline = com.videoeditor.core.model.Timeline(videoClips = clips))
    }

    // -- audio --

    @Test
    fun export_mixes_background_audio_and_keeps_a_video_in_sync() = runBlocking<Unit> {
        // 6s video clip at 2x (renders 3s) plus 20s of background music. The
        // export must contain both a video and an audio track, and must not
        // grow to the music's length — otherwise the tail is black with sound.
        val clip1 = extractRef("clip1_720p_6s.mp4")
        val music = extractRef("music_20s.m4a")
        val clipModel = com.videoeditor.core.model.Clip(
            id = "fast-clip",
            mediaRefId = clip1.id,
            sourceStartMicros = 0L,
            sourceEndMicros = 6_000_000L,
            timelineStartMicros = 0L,
            playbackSpeed = 2f,
        )
        val audioModel = AudioClip(
            id = "music",
            mediaRefId = music.id,
            sourceStartMicros = 0L,
            sourceEndMicros = 20_000_000L,
            timelineStartMicros = 0L,
            volume = 0.5f,
        )
        val project = projectCreator.create(
            name = "Audio mix test",
            mediaRefs = listOf(clip1, music),
            canvas = CanvasSettings(width = 1280, height = 720),
        ).copy(
            timeline = com.videoeditor.core.model.Timeline(
                videoClips = listOf(clipModel),
                audioClips = listOf(audioModel),
            ),
        )

        val output = exportToFile(project, "audio-mix")
        // Both tracks stop at the explicit visual canvas end.
        assertHasNonBlankVideoFrames(output, sampleWithinUs = 3_000_000L)
        val mimeTypes = mutableListOf<String>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            for (i in 0 until extractor.trackCount) {
                mimeTypes += extractor.getTrackFormat(i)
                    .getString(android.media.MediaFormat.KEY_MIME)
                    .orEmpty()
            }
        } finally {
            extractor.release()
        }
        val hasVideo = mimeTypes.any { it.startsWith("video/") }
        val hasAudio = mimeTypes.any { it.startsWith("audio/") }
        assertTrue("export has no video track", hasVideo)
        assertTrue("export has no audio track — background music was dropped", hasAudio)

        // The invariant that matters here: preview and export must agree about
        // how long this project is. Timeline.totalDurationMicros is the max
        // across tracks, so a 20s music clip makes the project 20s even though
        // the video track is 3s — both engines must report the same number.
        val exportDurationMs = containerDurationMs(output)
        assertEquals(
            project.timeline.renderedDurationMicros.toDouble(),
            Timing.millisToMicros(exportDurationMs).toDouble(),
            700_000.0,
        )

        // And the preview controller must independently report that duration.
        val controller =
            CompositionPreviewController.Factory(context, compositionBuilder).create()
        val imageThread = android.os.HandlerThread("audio-test-image-thread").apply { start() }
        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 2).apply {
            setOnImageAvailableListener(
                { reader -> reader.acquireLatestImage()?.close() },
                android.os.Handler(imageThread.looper),
            )
        }
        try {
            runOnMain {
                controller.attachHeadlessSurface(imageReader.surface, Size(1280, 720))
                controller.setProject(project)
            }
            assertNull("preview error: ${controller.state.value.error}", controller.state.value.error)
            assertEquals(
                project.timeline.totalDurationMicros,
                controller.state.value.durationMicros,
            )
        } finally {
            runOnMain { controller.release() }
            imageReader.close()
            imageThread.quitSafely()
        }
        output.delete()
    }

    @Test
    fun preview_replays_from_start_after_reaching_end() {
        // Regression: CompositionPlayer never reports STATE_ENDED, so restart is
        // driven by model position. The first attempt sought to 0 but tick()
        // still saw the stale at-end position and immediately paused and sought
        // back, leaving the playhead pinned at the end.
        val project = twoClipProject()
        val durationMicros = project.timeline.totalDurationMicros
        val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
        val imageThread = android.os.HandlerThread("replay-thread").apply { start() }
        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 2).apply {
            setOnImageAvailableListener(
                { reader -> reader.acquireLatestImage()?.close() },
                android.os.Handler(imageThread.looper),
            )
        }
        try {
            runOnMain {
                controller.attachHeadlessSurface(imageReader.surface, Size(1280, 720))
                controller.setProject(project)
                controller.play()
            }

            // Play to the end and confirm the controller stops there rather than
            // running away. A seek to exactly durationMicros can settle a few ms
            // under it, so allow a small tolerance below the boundary.
            val endThreshold = durationMicros - 500_000L
            val reachedEnd = pollUntil(timeoutMs = 90_000L) {
                controller.state.value.positionMicros >= endThreshold
            }
            assertTrue(
                "never reached near the composition end " +
                    "(position ${controller.state.value.positionMicros} of $durationMicros)",
                reachedEnd,
            )
            // Position must be clamped at or below the model duration — never the
            // runaway value CompositionPlayer itself reports past the end.
            assertTrue(
                "position ${controller.state.value.positionMicros} exceeded model duration",
                controller.state.value.positionMicros <= durationMicros,
            )
            assertTrue(
                "isPlaying stayed true at the end",
                pollUntil(timeoutMs = 5_000L) { !controller.state.value.isPlaying },
            )

            // Replay: must restart near zero and advance again.
            runOnMain { controller.play() }
            val restarted = pollUntil(timeoutMs = 30_000L) {
                controller.state.value.positionMicros < durationMicros / 2
            }
            assertTrue(
                "replay did not restart (position ${controller.state.value.positionMicros})",
                restarted,
            )
            assertTrue(
                "replay did not resume playback",
                pollUntil(timeoutMs = 30_000L) {
                    controller.state.value.isPlaying &&
                        controller.state.value.positionMicros > 500_000L
                },
            )
        } finally {
            runOnMain { controller.release() }
            imageReader.close()
            imageThread.quitSafely()
        }
    }

    @Test
    fun long_music_does_not_extend_visual_export_or_preview() = runBlocking<Unit> {
        val video = extractRef("clip1_720p_6s.mp4")
        val music = extractRef("music_20s.m4a")
        val base = projectCreator.create(name = "Long music", mediaRefs = listOf(video))
        val project = base.copy(mediaRefs = base.mediaRefs + music, timeline = base.timeline.copy(
            audioClips = listOf(AudioClip("music", music.id, sourceEndMicros = 20_000_000)),
        ))
        assertEquals(6_000_000L, project.timeline.renderedDurationMicros)
        assertEquals(20_000_000L, project.timeline.audioClips.single().sourceEndMicros)
        assertEquals(6_000.0, exportAndMeasureDuration(project).toDouble(), 150.0)
        val controller = CompositionPreviewController.Factory(context, compositionBuilder).create()
        try {
            runOnMain { controller.setProject(project) }
            assertTrue(pollUntil(10_000) { controller.state.value.hasComposition || controller.state.value.error != null })
            assertNull(controller.state.value.error)
            assertEquals(6_000_000L, controller.state.value.durationMicros)
            assertTrue(controller.state.value.omittedAudioClipIds.isEmpty())
        } finally { runOnMain { controller.release() } }
    }

    @Test
    fun decoded_captions_cross_reordered_trimmed_speed_clips_in_both_output_sizes() = runBlocking<Unit> {
        val first = extractRef("clip1_720p_6s.mp4")
        val second = extractRef("clip3_1080p_4s.mp4")
        val clips = listOf(
            com.videoeditor.core.model.Clip("second", second.id, 1_000_000, 2_000_000, 0),
            com.videoeditor.core.model.Clip("fast", first.id, 1_000_000, 3_000_000, 1_000_000, playbackSpeed = 2f),
            com.videoeditor.core.model.Clip("split-a", first.id, 3_000_000, 4_000_000, 2_000_000),
            com.videoeditor.core.model.Clip("split-b", first.id, 4_000_000, 5_000_000, 3_000_000),
        )
        for (resolution in ExportResolution.entries) for (portrait in listOf(false, true)) {
            val base = projectCreator.create("Caption matrix", listOf(first, second),
                canvas = CanvasSettings(width = if (portrait) 720 else 1280, height = if (portrait) 1280 else 720))
                .copy(timeline = com.videoeditor.core.model.Timeline(videoClips = clips))
            val caption = com.videoeditor.core.model.TextOverlay("matrix", "Adventure\nمرحبا 世界",
                startMicros = 500_000, durationMicros = 3_000_000, fontSizeSp = 60f,
                opacity = 0.8f, scale = 0.9f, rotationDegrees = 12f, positionX = 0.55f, positionY = 0.4f)
            val withText = base.copy(timeline = base.timeline.copy(textOverlays = listOf(caption)))
            val plain = exportToFile(base, "plain-matrix", resolution)
            val titled = exportToFile(withText, "text-matrix", resolution)
            val reference = MediaMetadataRetriever()
            val rendered = MediaMetadataRetriever()
            try {
                reference.setDataSource(plain.absolutePath)
                rendered.setDataSource(titled.absolutePath)
                val rotation = rendered.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                val width = rendered.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                val height = rendered.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                assertEquals(if (portrait) resolution.height else resolution.width, if (rotation % 180 == 0) width else height)
                assertEquals(if (portrait) resolution.width else resolution.height, if (rotation % 180 == 0) height else width)
                for (time in listOf(0L, 750_000L, 1_500_000L, 2_500_000L, 3_250_000L, 3_500_000L, 3_750_000L)) {
                    val expected = reference.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)!!
                    val actual = rendered.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)!!
                    var changed = 0
                    for (y in 0 until actual.height step 4) for (x in 0 until actual.width step 4) {
                        val a = actual.getPixel(x, y)
                        val b = expected.getPixel(x, y)
                        val difference = kotlin.math.abs((a shr 16 and 255) - (b shr 16 and 255)) +
                            kotlin.math.abs((a shr 8 and 255) - (b shr 8 and 255)) +
                            kotlin.math.abs((a and 255) - (b and 255))
                        if (difference > 100) changed++
                    }
                    expected.recycle(); actual.recycle()
                    if (time in 500_000L until 3_500_000L) {
                        assertTrue("Missing caption at $time, $resolution portrait=$portrait ($changed changed cells)", changed > 100)
                    } else {
                        assertTrue("Caption persisted outside its window at $time ($changed changed cells)", changed < 100)
                    }
                }
            } finally {
                reference.release(); rendered.release(); plain.delete(); titled.delete()
            }
        }
    }

    @Test
    fun explicit_photo_end_card_extends_canvas_and_exports_decoded_frames() = runBlocking<Unit> {
        val video = extractRef("clip1_720p_6s.mp4")
        val retriever = MediaMetadataRetriever()
        val photo = File(mediaDir, "end-card.jpg")
        try {
            retriever.setDataSource(context, Uri.parse(video.uri))
            val frame = retriever.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            photo.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            frame.recycle()
        } finally { retriever.release() }
        val still = (extractor.extract(context, Uri.fromFile(photo)) as Result.Success).value
        val base = twoClipProject()
        val music = extractRef("music_20s.m4a")
        val project = base.copy(mediaRefs = base.mediaRefs + still + music, timeline = base.timeline.copy(
            videoClips = base.timeline.videoClips + com.videoeditor.core.model.Clip("card", still.id,
                timelineStartMicros = 10_000_000, photoDurationMicros = 1_000_000),
            audioClips = listOf(AudioClip("card-music", music.id, sourceEndMicros = 20_000_000)),
            textOverlays = listOf(com.videoeditor.core.model.TextOverlay("end", "THE END", startMicros = 10_000_000, durationMicros = 1_000_000)),
        ))
        val output = exportToFile(project, "photo-card")
        try {
            assertEquals(11_000.0, containerDurationMs(output).toDouble(), 150.0)
            val durations = trackDurationsMs(output)
            assertTrue(durations.keys.any { it.startsWith("audio/") })
            assertTrue(durations.keys.any { it.startsWith("video/") })
            durations.values.forEach { assertEquals(11_000.0, it.toDouble(), 150.0) }
            val decoded = MediaMetadataRetriever()
            try {
                decoded.setDataSource(output.absolutePath)
                val frame = decoded.getFrameAtTime(10_500_000, MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull("Photo end card did not decode", frame)
                frame?.recycle()
            } finally { decoded.release() }
        } finally { output.delete(); photo.delete() }
    }

    // -- text overlays: preview/export parity and the duration policy --

    @Test
    fun text_overlay_is_rendered_into_exported_mp4() = runBlocking<Unit> {
        // A caption spanning the whole 6s clip must appear in the exported
        // frames. This is the release-blocking "invisible text" bug: previously
        // textOverlays were never composited, so the MP4 carried no text at all.
        val clip1 = extractRef("clip1_720p_6s.mp4")
        val project = projectCreator.create(
            name = "Text parity",
            mediaRefs = listOf(clip1),
            canvas = CanvasSettings(width = 1280, height = 720),
        ).copy(
            timeline = com.videoeditor.core.model.Timeline(
                videoClips = listOf(
                    com.videoeditor.core.model.Clip(
                        id = "clip",
                        mediaRefId = clip1.id,
                        sourceStartMicros = 0L,
                        sourceEndMicros = 6_000_000L,
                        timelineStartMicros = 0L,
                    ),
                ),
                textOverlays = listOf(
                    com.videoeditor.core.model.TextOverlay(
                        id = "caption",
                        text = "ADVENTURE",
                        fontSizeSp = 60f,
                        colorArgb = 0xFFFFFFFF.toInt(),
                        startMicros = 0L,
                        durationMicros = 6_000_000L,
                    ),
                ),
            ),
        )

        val output = exportToFile(project, "text-parity")
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.absolutePath)
                // Sample the middle of the clip where the caption is present.
                val frame = retriever.getFrameAtTime(
                    3_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
                assertNotNull("no decodable frame at 3s", frame)
                assertTrue(
                    "caption did not render into the export (frame has no bright text pixels)",
                    frame!!.hasBrightTextPixels(),
                )
            } finally {
                retriever.release()
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun text_at_video_end_does_not_extend_exported_duration() = runBlocking<Unit> {
        // The release-blocking black-tail bug: a caption added at the 6s end must
        // not stretch the project (and the export) into unsupported black frames.
        val clip1 = extractRef("clip1_720p_6s.mp4")
        val project = projectCreator.create(
            name = "Text tail",
            mediaRefs = listOf(clip1),
            canvas = CanvasSettings(width = 1280, height = 720),
        ).copy(
            timeline = com.videoeditor.core.model.Timeline(
                videoClips = listOf(
                    com.videoeditor.core.model.Clip(
                        id = "clip",
                        mediaRefId = clip1.id,
                        sourceStartMicros = 0L,
                        sourceEndMicros = 6_000_000L,
                        timelineStartMicros = 0L,
                    ),
                ),
                // A caption that would otherwise run past the 6s video end.
                textOverlays = listOf(
                    com.videoeditor.core.model.TextOverlay(
                        id = "end-caption",
                        text = "THE END",
                        startMicros = 5_000_000L,
                        durationMicros = 3_000_000L,
                    ),
                ),
            ),
        )

        // The rendered (picture+audio) length is the video's 6s; the text must
        // not inflate it. totalDurationMicros still reports 8s (model intact).
        assertEquals(6_000_000L, project.timeline.renderedDurationMicros)
        assertEquals(8_000_000L, project.timeline.totalDurationMicros)

        val durationMs = exportAndMeasureDuration(project)
        // Export must match the rendered 6s, not the text-inflated 8s.
        assertEquals(6_000.0, durationMs.toDouble(), 600.0)
    }

    @Test
    fun preview_duration_excludes_text_overlays() {
        // The preview enforces the rendered span, so a caption past the video end
        // cannot leave the playhead in a black region (the black-after-export bug).
        val clip1 = extractRef("clip1_720p_6s.mp4")
        val project = projectCreator.create(
            name = "Preview text duration",
            mediaRefs = listOf(clip1),
            canvas = CanvasSettings(width = 1280, height = 720),
        ).copy(
            timeline = com.videoeditor.core.model.Timeline(
                videoClips = listOf(
                    com.videoeditor.core.model.Clip(
                        id = "clip",
                        mediaRefId = clip1.id,
                        sourceStartMicros = 0L,
                        sourceEndMicros = 6_000_000L,
                        timelineStartMicros = 0L,
                    ),
                ),
                textOverlays = listOf(
                    com.videoeditor.core.model.TextOverlay(
                        id = "t",
                        text = "X",
                        startMicros = 5_000_000L,
                        durationMicros = 3_000_000L,
                    ),
                ),
            ),
        )

        val controller =
            CompositionPreviewController.Factory(context, compositionBuilder).create()
        val imageThread = android.os.HandlerThread("text-dur-thread").apply { start() }
        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 2).apply {
            setOnImageAvailableListener(
                { reader -> reader.acquireLatestImage()?.close() },
                android.os.Handler(imageThread.looper),
            )
        }
        try {
            runOnMain {
                controller.attachHeadlessSurface(imageReader.surface, Size(1280, 720))
                controller.setProject(project)
            }
            assertNull("preview error: ${controller.state.value.error}", controller.state.value.error)
            assertEquals(6_000_000L, controller.state.value.durationMicros)
        } finally {
            runOnMain { controller.release() }
            imageReader.close()
            imageThread.quitSafely()
        }
    }

    /**
     * True when the frame contains a run of bright (near-white) pixels — the
     * signature of light caption text over the darker test footage. Coarse on
     * purpose: it distinguishes "text present" from "no text" without depending
     * on exact glyph positions.
     */
    private fun android.graphics.Bitmap.hasBrightTextPixels(): Boolean {
        val scaled = android.graphics.Bitmap.createScaledBitmap(this, 64, 64, true)
        val pixels = IntArray(64 * 64)
        scaled.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        var brightCount = 0
        for (pixel in pixels) {
            val r = pixel shr 16 and 0xFF
            val g = pixel shr 8 and 0xFF
            val b = pixel and 0xFF
            if (r > 200 && g > 200 && b > 200) brightCount++
        }
        // A 60sp caption over 64x64 covers a handful of bright cells; require a
        // small but non-trivial count so a flat bright frame isn't a false pass.
        return brightCount in 4..2000
    }

    // -- helpers --

    private suspend fun exportToFile(project: Project, label: String, resolution: ExportResolution = ExportResolution.RES_720P): File {
        val output = File(retainedMediaTestDirectory(context), "$label-${System.currentTimeMillis()}.mp4")
        val result = ExportEngine(context, compositionBuilder).export(
            project,
            ExportRequest(
                projectId = project.id,
                outputFile = output,
                resolution = resolution,
                frameRate = FrameRate.FPS_30,
                quality = ExportQuality.Standard,
            ),
        ) { }
        assertTrue(
            "export failed: ${(result as? Result.Failure)?.error}",
            result.isSuccess,
        )
        // A real render, not a stub: a few seconds of 720p H.264+AAC is tens of
        // kilobytes at minimum, and the container must expose a decodable video
        // track whose own duration matches the file.
        assertTrue("output suspiciously small (${output.length()} bytes)", output.length() > 20_000)
        return output
    }

    /**
     * Decodes real frames from an export and proves they are rendered content
     * rather than a stub: each sampled frame must vary internally (not a flat
     * colour), and frames from different parts of the timeline must differ from
     * one another (not one frozen frame repeated). Export timing alone is not
     * evidence of a real render, so this is.
     */
    /**
     * Decodes real frames from an export and proves they are rendered content
     * rather than a stub: each sampled frame must vary internally (not a flat
     * colour), and frames from different parts of the timeline must differ from
     * one another (not one frozen frame repeated). Export timing alone is not
     * evidence of a real render, so this is.
     *
     * [sampleWithinUs] bounds sampling to the span that actually carries video.
     * When background audio outlasts the video track the container is longer
     * than the picture, and the tail is a frozen frame; sampling there would
     * wrongly report "nothing rendered". The audio-mix test passes its video
     * track length so the check measures real frames.
     */
    private fun assertHasNonBlankVideoFrames(file: File, sampleWithinUs: Long? = null) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val containerUs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000L
            val windowUs = sampleWithinUs?.coerceAtMost(containerUs) ?: containerUs
            val signatures = listOf(0.2, 0.5, 0.8).mapNotNull { fraction ->
                retriever.getFrameAtTime(
                    (windowUs * fraction).toLong(),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
            }.map { it.frameSignature() }

            assertEquals("expected 3 decodable frames, got ${signatures.size}", 3, signatures.size)
            assertTrue(
                "every sampled frame is a flat colour — nothing was rendered",
                signatures.all { it.hasVariation },
            )
            assertTrue(
                "all sampled frames are identical — the timeline did not advance",
                signatures.map { it.checksum }.distinct().size > 1,
            )
        } finally {
            retriever.release()
        }
    }

    /** A coarse content signature for a decoded frame. */
    private data class FrameSignature(val hasVariation: Boolean, val checksum: Long)

    private fun android.graphics.Bitmap.frameSignature(): FrameSignature {
        // Downsample so the signature is cheap and immune to encoder noise.
        val scaled = android.graphics.Bitmap.createScaledBitmap(this, 32, 32, true)
        val pixels = IntArray(32 * 32)
        scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        var sum = 0L
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (pixel in pixels) {
            val luminance = (pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)
            sum += luminance
            if (luminance < min) min = luminance
            if (luminance > max) max = luminance
        }
        return FrameSignature(hasVariation = max - min > 8, checksum = sum)
    }

    /** Duration in milliseconds of each track, keyed by MIME type prefix. */
    private fun trackDurationsMs(file: File): Map<String, Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val result = mutableMapOf<String, Long>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                val durationUs =
                    if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                        format.getLong(android.media.MediaFormat.KEY_DURATION)
                    } else {
                        -1L
                    }
                result[mime] = durationUs / 1000L
            }
            return result
        } finally {
            extractor.release()
        }
    }

    private fun containerDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                .toLong()
        } finally {
            retriever.release()
        }
    }

    /**
     * Exports [project] through the real engine and returns the duration the
     * container reports for the output, verifying success and cleanup.
     */
    private suspend fun exportAndMeasureDuration(project: Project): Long {
        val output = exportToFile(project, "export-duration")
        return try {
            containerDurationMs(output)
        } finally {
            output.delete()
        }
    }

    private fun twoClipProject(): Project {
        val clip1 = extractRef("clip1_720p_6s.mp4")
        val clip3 = extractRef("clip3_1080p_4s.mp4")
        return projectCreator.create(
            name = "Device test",
            mediaRefs = listOf(clip1, clip3),
            canvas = CanvasSettings(width = 1280, height = 720),
        )
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
}
