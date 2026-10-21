package com.videoeditor.core.media

import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.PreviewingVideoGraph
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.Size
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.composition.PreviewingMultipleInputVideoGraph
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.model.OverlayClip
import com.videoeditor.core.model.VisualTransform
import com.videoeditor.editing.ProjectCreator
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Withhold real graph output notifications, leaving decoder and GL ownership intact.
 * This tests recovery from missing output, not the cause of the retained native stall. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreviewGraphStartupRecoveryDeviceTest {
    @Test fun no_output_replacement_recovers_latest_paused_target_on_all_three_surfaces() = verify(false, false)
    @Test fun explicit_play_survives_no_output_replacement() = verify(true, false)
    @Test fun second_no_output_generation_fails_releases_and_can_reopen() = verify(false, true)
    @Test fun cancelled_startup_cannot_retry_into_the_reopened_project() = verify(false, false, true)
    @Test fun finished_scrub_cancels_superseded_startup_before_output() = verify(false, false, finish = true)
    @Test fun decoder_error_releases_owner_and_stale_error_cannot_release_reopen() = verify(false, false, decoderError = true)
    @Test fun original_target_missing_output_retires_stale_marker9_and_delivers118() = verify(false, false, originalTarget = true)
    @Test fun explicit_play_after_paused_recovery_landing_advances_real_output() = verify(false, false, playAfterLanding = true)

    private fun verify(play: Boolean, terminal: Boolean, cancel: Boolean = false, finish: Boolean = false, decoderError: Boolean = false, originalTarget: Boolean = false, playAfterLanding: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, "startup-recovery-${System.nanoTime()}.mp4")
        context.assets.open("pip_main_frame_identity_audio.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val overlaySource = File(context.filesDir, "startup-recovery-overlay-${System.nanoTime()}.mp4")
        context.assets.open("pip_overlay_frame_identity_audio.mp4").use { input -> overlaySource.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val overlayRef = MediaMetadataExtractor().extract(context, Uri.fromFile(overlaySource)).getOrNull()!!
        val base = ProjectCreator().create("Startup recovery", listOf(ref))
        val project = base.copy(mediaRefs = base.mediaRefs + overlayRef, timeline = base.timeline.copy(overlayClips = listOf(
            OverlayClip("right", overlayRef.id, sourceEndMicros = 6_000_000, timelineDurationMicros = 6_000_000,
                muted = true, transform = VisualTransform(scale = .3f, offsetX = .5f, offsetY = .5f)),
            OverlayClip("left", overlayRef.id, sourceEndMicros = 6_000_000, timelineDurationMicros = 6_000_000,
                muted = true, zIndex = 1, transform = VisualTransform(scale = .3f, offsetX = -.5f, offsetY = .5f)),
        )))
        val references = MediaMetadataRetriever().let { retriever ->
            try { retriever.setDataSource(source.path); listOf(0, 9, 118).associateWith { retriever.getFrameAtIndex(it)!! } }
            finally { retriever.release() }
        }
        val overlayReferences = MediaMetadataRetriever().let { retriever ->
            try { retriever.setDataSource(overlaySource.path); listOf(0, 9, 118).associateWith { retriever.getFrameAtIndex(it)!! } }
            finally { retriever.release() }
        }
        data class Frame(val markers: List<Int>, val errors: List<Double>)
        val frames = LinkedBlockingQueue<Frame>()
        val thread = HandlerThread("startup-recovery-pixels").apply { start() }
        val reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ images -> images.acquireLatestImage()?.use { image ->
            val plane = image.planes[0]
            fun rgb(x: Int, y: Int): List<Int> {
                val offset = y * plane.rowStride + x * plane.pixelStride
                return (0..2).map { plane.buffer.get(offset + it).toInt() and 255 }
            }
            val regions = listOf(listOf(0, 0, 1280, 720), listOf(768, 432, 384, 216), listOf(128, 432, 384, 216))
            val markers = regions.map { (x, y, w, h) ->
                fun bit(sx: Int): Int {
                    val sum = rgb(x + sx * w / 1280, y + 22 * h / 720).sum()
                    return if (sum < 120) 0 else if (sum > 645) 1 else -1
                }
                val bits = (0..7).map { bit(58 + 36 * it) }
                if (bit(22) != 1 || bits.any { it < 0 }) -1 else bits.withIndex().sumOf { (index, value) -> value shl index }
            }
            val expected = references[markers.first()]
            val errors = if (expected == null) emptyList() else regions.mapIndexed { index, (x, y, w, h) ->
                val referenceBitmap = if (index == 0) expected else checkNotNull(overlayReferences[markers.first()])
                var sum = 0L; var count = 0
                for (sy in 80 until (if (index == 0) 390 else 680) step 60) for (sx in 40 until 1240 step 80) {
                    val actual = rgb(x + sx * w / 1280, y + sy * h / 720)
                    val reference = referenceBitmap.getPixel(sx, sy)
                    actual.forEachIndexed { channel, value ->
                        sum += kotlin.math.abs(value - (reference shr (16 - channel * 8) and 255))
                    }
                    count++
                }
                sum.toDouble() / count
            }
            frames.offer(Frame(markers, errors))
        } }, Handler(thread.looper))
        val suppress = AtomicInteger()
        lateinit var controller: CompositionPreviewController
        var created = false
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!predicate() && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue("$message: ${controller.state.value}", predicate())
        }
        fun awaitFrame(marker: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var last: Frame? = null
            while (System.nanoTime() < deadline) {
                last = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (last.markers == listOf(marker, marker, marker)) {
                    assertEquals(3, last.errors.size)
                    assertTrue("Decoded source pixels differ: $last", last.errors.all { it < 30 })
                    android.util.Log.i("GraphStartupRecoveryTest", "verifiedMarker=$marker markers=${last.markers} meanRgbErrors=${last.errors} generation=${controller.rebuildCount}")
                    return
                }
            }
            fail("Expected three source markers $marker; last=$last state=${controller.state.value}")
        }
        try {
            main {
                controller = CompositionPreviewController.Factory(context, CompositionBuilder()).create()
                created = true
                controller.verificationGraphFactory = { settings ->
                    val delegate = PreviewingMultipleInputVideoGraph.Factory(settings)
                    // Bind the injected fault to the selected generation, even
                    // when cancellation retires it before native graph creation.
                    val drop = suppress.getAndUpdate { if (it > 0) it - 1 else 0 } > 0
                    PreviewingVideoGraph.Factory { ctx, color, debug, listener, executor, effects, offset ->
                        val observed = if (!drop) listener else object : VideoGraph.Listener by listener {
                            override fun onOutputFrameAvailableForRendering(presentationTimeUs: Long) = Unit
                        }
                        delegate.create(ctx, color, debug, observed, executor, effects, offset)
                    }
                }
                controller.attachHeadlessSurface(reader.surface, Size(1280, 720)); controller.setProject(project)
            }
            awaitFrame(0)
            if (decoderError) {
                lateinit var retired: androidx.media3.common.Player.Listener
                val failure = androidx.media3.common.PlaybackException("injected decoder initialization failure", null,
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
                main {
                    val field = CompositionPreviewController::class.java.getDeclaredField("playerListener").apply { isAccessible = true }
                    retired = field.get(controller) as androidx.media3.common.Player.Listener
                    retired.onPlayerError(failure)
                }
                await("Failed decoder owner was not released") { controller.state.value.error != null && controller.activePlayerCount == 0 }
                main {
                    assertEquals(failure.errorCode, controller.state.value.error?.errorCode)
                    controller.setProject(null); controller.setProject(project)
                    retired.onPlayerError(failure)
                }
                await("Stale error released reopened owner") { controller.renderedPositionMicros >= 0 }
                awaitFrame(0)
                main { assertNull(controller.state.value.error); assertEquals(1, controller.activePlayerCount) }
                return
            }
            if (originalTarget) {
                main { controller.seekTo(287_459, resumePlayback = false) }
                awaitFrame(9)
            }
            val initial = controller.rebuildCount
            main {
                suppress.set(if (terminal) 2 else 1)
                if (!originalTarget) controller.seekTo(287_459, resumePlayback = false)
                // Original-target mode reproduces the retained 9 -> no output
                // at 3,933,341 -> recovered 118 sequence. Other modes also
                // exercise a newer pending target during failed preparation.
                controller.seekForScrub(3_933_341)
                if (finish) {
                    controller.finishScrubSeek()
                    assertEquals("Pointer lift must retire the superseded owner immediately", initial + 2, controller.rebuildCount)
                } else controller.flushScrubSeek()
                if (play) controller.play()
                if (cancel) {
                    controller.setProject(null); suppress.set(0); controller.setProject(project)
                }
            }
            if (cancel) {
                awaitFrame(0)
                Thread.sleep(4_250)
                main { assertEquals(initial + 2, controller.rebuildCount); assertNull(controller.state.value.error) }
            } else if (terminal) {
                await("Second no-output generation did not fail") { controller.state.value.error != null }
                main {
                    assertEquals(initial + 2, controller.rebuildCount)
                    assertEquals(0, controller.activePlayerCount)
                    assertFalse(controller.state.value.playRequested)
                    controller.setProject(null); controller.setProject(project)
                }
                awaitFrame(0)
            } else {
                awaitFrame(118)
                main {
                    assertEquals("Recovery must be bounded to one replacement", initial + 2, controller.rebuildCount)
                    assertNull(controller.state.value.error)
                    assertEquals(play, controller.state.value.playRequested)
                }
                if (play) await("Explicit Play was lost") { controller.renderedPositionMicros > 4_000_000 }
                else {
                    Thread.sleep(250)
                    if (playAfterLanding) {
                        main {
                            android.util.Log.i("GraphStartupRecoveryTest", "Paused landing state=${controller.state.value}")
                            assertFalse(controller.state.value.isPlaying)
                            controller.play()
                        }
                        await("Play after paused recovery did not advance real output") { controller.renderedPositionMicros > 4_000_000 }
                        return
                    }
                    main { assertFalse(controller.state.value.isPlaying); assertEquals(3_933_000L, controller.state.value.positionMicros) }
                }
            }
        } finally {
            if (created) main { controller.release(); assertEquals(0, controller.activePlayerCount) }
            reader.close(); thread.quitSafely(); thread.join(5_000)
            references.values.forEach { it.recycle() }; overlayReferences.values.forEach { it.recycle() }
            source.delete(); overlaySource.delete()
        }
    }
}
