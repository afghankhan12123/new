package com.videoeditor.core.media

import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.Size
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.PreviewingSingleInputVideoGraph
import androidx.media3.transformer.PatchedCompositionPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.editing.ProjectCreator
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Inject the exact interruptible flush wait found in the captured release deadlock. */
class PreviewReleaseRecoveryDeviceTest {
    @Test fun retired_image_player_release_interrupts_stalled_flush_and_returns_to_ui() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val photo = File(retainedMediaTestDirectory(context), "release-photo-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        try { photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        val project = ProjectCreator().create("Release recovery", listOf(MediaRef("photo", android.net.Uri.fromFile(photo).toString(),
            MediaType.Photo, "Release photo", mimeType = "image/png", width = 64, height = 64)))
        val arm = AtomicBoolean()
        val enteredFlush = CountDownLatch(1)
        val forcedUnblock = CountDownLatch(1)
        val interruptedFlush = CountDownLatch(1)
        val released = CountDownLatch(1)
        val firstFrame = CountDownLatch(1)
        val defaultFactory = DefaultVideoFrameProcessor.Factory.Builder().build()
        val factory = VideoFrameProcessor.Factory { ctx, debug, color, automatic, executor, listener ->
            val delegate = defaultFactory.create(ctx, debug, color, automatic, executor, listener)
            Proxy.newProxyInstance(VideoFrameProcessor::class.java.classLoader, arrayOf(VideoFrameProcessor::class.java)) { _, method, args ->
                if (method.name == "flush" && arm.get()) {
                    enteredFlush.countDown()
                    try { forcedUnblock.await() } catch (_: InterruptedException) {
                        interruptedFlush.countDown()
                        Thread.currentThread().interrupt()
                    }
                    null
                } else try { method.invoke(delegate, *(args ?: emptyArray())) }
                catch (error: InvocationTargetException) { throw error.cause ?: error }
            } as VideoFrameProcessor
        }
        val reader = ImageReader.newInstance(64, 64, android.graphics.PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ output -> output.acquireLatestImage()?.use { firstFrame.countDown() } }, Handler(Looper.getMainLooper()))
        lateinit var player: PatchedCompositionPlayer
        var created = false
        try {
            instrumentation.runOnMainSync {
                val bitmaps = androidx.media3.datasource.DataSourceBitmapLoader(context)
                player = PatchedCompositionPlayer.Builder(context)
                    .setPreviewingVideoGraphFactory(PreviewingSingleInputVideoGraph.Factory(factory))
                    .setExternalImageLoader { com.google.common.util.concurrent.Futures.immediateVoidFuture() }
                    .setImageDecoderFactory(androidx.media3.exoplayer.image.ExternallyLoadedImageDecoder.Factory { request -> bitmaps.loadBitmap(request.uri) })
                    .build()
                created = true
                player.setVideoSurface(reader.surface, Size(64, 64))
                player.setComposition(CompositionBuilder().buildForPreview(project).composition)
                player.prepare()
            }
            assertTrue("Initial real image must render before fault injection", firstFrame.await(20, TimeUnit.SECONDS))
            arm.set(true)
            instrumentation.runOnMainSync { player.seekTo(200) }
            assertTrue("Seek must reach the renderer flush", enteredFlush.await(5, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post { try { player.release() } finally { released.countDown() } }
            val returnedWithoutTestRescue = released.await(3, TimeUnit.SECONDS)
            // Even a failing baseline can be cleaned up without leaving the test UI deadlocked.
            forcedUnblock.countDown()
            assertTrue("Release cleanup must complete", released.await(5, TimeUnit.SECONDS))
            assertTrue("A timed-out retired player must not leave the UI blocked", returnedWithoutTestRescue)
            assertEquals("Production recovery must interrupt the observed flush wait", 0L, interruptedFlush.count)
        } finally {
            forcedUnblock.countDown()
            if (created && released.count > 0) instrumentation.runOnMainSync { player.release() }
            reader.close(); photo.delete()
        }
    }
}
