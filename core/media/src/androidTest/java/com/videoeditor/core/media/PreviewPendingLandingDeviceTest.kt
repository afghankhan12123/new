package com.videoeditor.core.media

import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Size
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.editing.ProjectCreator
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Inject only the transient aggregate BUFFERING observation, after real video output.
 * The real decoder, graph, seek, callbacks and source-frame oracle remain in use. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreviewPendingLandingDeviceTest {
    @Test fun completed_reset_accepts_latest_target_before_intermediate_picture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, "reset-barrier-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("transition_identity_0.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val frames = LinkedBlockingQueue<Int>()
        val thread = HandlerThread("reset-barrier-frames").apply { start() }
        val reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ images -> images.acquireLatestImage()?.use { image ->
            val plane = image.planes[0]
            fun white(x: Int): Boolean {
                val offset = 92 * plane.rowStride + x * plane.pixelStride
                return (0..2).sumOf { plane.buffer.get(offset + it).toInt() and 255 } > 645
            }
            if (white(32)) frames.offer((0..7).sumOf { if (white(96 + it * 64)) 1 shl it else 0 })
        } }, Handler(thread.looper))
        lateinit var controller: CompositionPreviewController
        lateinit var player: androidx.media3.transformer.PatchedCompositionPlayer
        var created = false
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun awaitFrame(expected: Int) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < until) if (frames.poll(100, TimeUnit.MILLISECONDS) == expected) return
            fail("Missing decoded marker $expected: ${controller.state.value}")
        }
        try {
            main {
                controller = CompositionPreviewController.Factory(context, CompositionBuilder()).create(); created = true
                controller.attachHeadlessSurface(reader.surface, Size(1280, 720))
                controller.setProject(ProjectCreator().create("Reset barrier", listOf(ref)))
            }
            awaitFrame(0)
            main {
                val field = CompositionPreviewController::class.java.getDeclaredField("player").apply { isAccessible = true }
                player = field.get(controller) as androidx.media3.transformer.PatchedCompositionPlayer
                controller.seekTo(4_000_000, resumePlayback = false)
            }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!player.hasCompletedPrimaryVideoReset() && System.nanoTime() < until) Thread.sleep(1)
            assertTrue("Actual reset callback barrier did not complete", player.hasCompletedPrimaryVideoReset())
            main {
                assertEquals("Observe the barrier before the intermediate decoded picture", androidx.media3.common.C.TIME_UNSET, controller.renderedPositionMicros)
                // Inject only cached aggregate BUFFERING, preserving real reset completion.
                val cached = SimpleBasePlayer::class.java.getDeclaredField("state").apply { isAccessible = true }
                val state = cached.get(player)!!
                val builder = state.javaClass.getMethod("buildUpon").invoke(state)
                builder.javaClass.getMethod("setPlaybackState", Int::class.javaPrimitiveType).invoke(builder, Player.STATE_BUFFERING)
                cached.set(player, builder.javaClass.getMethod("build").invoke(builder))
                controller.seekForScrub(2_000_123)
                assertEquals("Pending state must publish without a polling tick", 2_000_123L, controller.state.value.positionMicros)
                assertEquals(2_000_123L, controller.state.value.requestedPositionMicros)
                controller.finishScrubSeek()
                val pending = CompositionPreviewController::class.java.getDeclaredField("pendingScrubMicros").apply { isAccessible = true }
                assertNull("Final lift must use the completed reset instead of decoding the superseded picture", pending.get(controller))
            }
            awaitFrame(60)
            main { assertEquals(1, controller.rebuildCount); assertFalse(controller.state.value.playRequested); assertNull(controller.state.value.error) }
        } finally {
            if (created) main { controller.release() }
            reader.close(); thread.quitSafely(); thread.join(5_000); source.delete()
        }
    }

    @Test fun rendered_paused_buffering_scrub_dispatches_once_and_delivers_the_source_frame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, "pending-landing-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("transition_identity_0.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }
        val ref = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val project = ProjectCreator().create("Pending landing", listOf(ref))
        val frames = LinkedBlockingQueue<Int>()
        val thread = HandlerThread("landing-frames").apply { start() }
        val reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ output -> output.acquireLatestImage()?.use { image ->
            val plane = image.planes[0]
            fun white(x: Int): Boolean {
                val offset = 92 * plane.rowStride + x * plane.pixelStride
                return (0..2).sumOf { plane.buffer.get(offset + it).toInt() and 255 } > 645
            }
            if (white(32)) frames.offer((0..7).sumOf { if (white(96 + it * 64)) 1 shl it else 0 })
        } }, Handler(thread.looper))
        lateinit var controller: CompositionPreviewController
        var created = false
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun awaitFrame(expected: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var last: Int? = null
            while (System.nanoTime() < deadline) {
                last = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (last == expected) return
            }
            fail("Expected source marker $expected, last=$last state=${controller.state.value}")
        }
        try {
            main {
                controller = CompositionPreviewController.Factory(context, CompositionBuilder()).create()
                created = true
                controller.attachHeadlessSurface(reader.surface, Size(1280, 720))
                controller.setProject(project)
            }
            awaitFrame(0)
            // Mirror a child entering BUFFERING after this generation submitted output.
            // No player callback is scheduled by the injection; flush must make progress.
            main {
                val field = CompositionPreviewController::class.java.getDeclaredField("player").apply { isAccessible = true }
                val player = field.get(controller) as SimpleBasePlayer
                assertFalse(player.playWhenReady)
                val cached = SimpleBasePlayer::class.java.getDeclaredField("state").apply { isAccessible = true }
                val state = cached.get(player)!!
                val builder = state.javaClass.getMethod("buildUpon").invoke(state)
                builder.javaClass.getMethod("setPlaybackState", Int::class.javaPrimitiveType).invoke(builder, Player.STATE_BUFFERING)
                cached.set(player, builder.javaClass.getMethod("build").invoke(builder))
                assertEquals(Player.STATE_BUFFERING, player.playbackState)
                controller.seekForScrub(2_000_000)
                controller.flushScrubSeek()
                val pending = CompositionPreviewController::class.java.getDeclaredField("pendingScrubMicros").apply { isAccessible = true }
                assertNull("A permitted landing must not be queued again by seekTo", pending.get(controller))
                val guard = CompositionPreviewController::class.java.getDeclaredField("tickInProgress").apply { isAccessible = true }
                assertFalse("Guard must clear on return", guard.getBoolean(controller))
            }
            awaitFrame(60)
            Thread.sleep(250)
            main {
                assertFalse("Paused ruler input must stay paused", controller.state.value.playRequested)
                assertFalse(controller.state.value.isPlaying)
                assertEquals(2_000_000L, controller.state.value.positionMicros)
                assertEquals(1, controller.rebuildCount)
                assertNull(controller.state.value.error)
            }
        } finally {
            if (created) main { controller.release(); assertEquals(0, controller.activePlayerCount) }
            reader.close(); thread.quitSafely(); thread.join(5_000); source.delete()
        }
    }
}
