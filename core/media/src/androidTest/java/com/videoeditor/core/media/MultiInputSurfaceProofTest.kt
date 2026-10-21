package com.videoeditor.core.media

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.Presentation
import android.media.MediaMetadataRetriever
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.media.composition.PreviewingMultipleInputVideoGraph
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only activity: the proof never replaces the editor's working preview path. */
class MultiInputProofActivity : Activity() {
    lateinit var surfaceView: SurfaceView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
    }
}

/**
 * Establishes real independent decoder surfaces and visible compositor output.
 * Each project-time sample recreates the graph: this deliberately does NOT claim
 * continuous playback or in-place seek support from Media3's export graph.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class MultiInputSurfaceProofTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val evidence = File(context.getExternalFilesDir(null), "overlay-evidence/multi-input-proof")

    @Test
    fun exact_timestamp_frames_render_without_future_frames_or_eos() = runProof(false, withoutLookahead = true)

    @Test
    fun two_decoder_inputs_surface_output_and_recreation() = runProof(false)

    @Test
    fun moving_source_matches_project_time_after_forward_and_backward_recreation() = runProof(true)

    @Test
    fun active_decoder_and_pending_texture_cancel_when_second_decoder_never_starts() =
        runProof(false, cancelAfterFirstDecoder = true)

    @Test
    fun two_active_decoders_cancel_without_draining_or_successful_completion() =
        runProof(false, cancelWithBothDecoders = true)

    /** A construction rollback must work before any renderer/input is available. */
    @Test
    fun initialized_graph_releases_when_renderer_initialization_never_starts() {
        rollback(emptyList())
    }

    @Test
    fun partial_registration_cancels_without_successful_completion() = rollback(listOf(0))

    @Test
    fun registered_inputs_cancel_without_end_of_stream() = rollback(listOf(0, 1))

    private fun rollback(inputs: List<Int>) {
        var ended = false
        val graph = PreviewingMultipleInputVideoGraph(
            context, DefaultVideoFrameProcessor.Factory.Builder().build(), ColorInfo.SDR_BT709_LIMITED,
            DebugViewProvider.NONE, object : VideoGraph.Listener {
                override fun onOutputSizeChanged(width: Int, height: Int) {}
                override fun onOutputFrameAvailableForRendering(framePresentationTimeUs: Long) {}
                override fun onEnded(finalFramePresentationTimeUs: Long) { ended = true }
                override fun onError(exception: VideoFrameProcessingException) { throw exception }
            }, java.util.concurrent.Executor { it.run() }, VideoCompositorSettings.DEFAULT, emptyList(), 0,
        )
        // Models failure after graph/output setup but before input/decoder creation.
        graph.setOutputSurfaceInfo(null)
        inputs.forEach(graph::registerInput)
        graph.release()
        graph.release() // Successful cleanup must also be idempotent.
        assertEquals(PreviewingMultipleInputVideoGraph.State.Released, graph.state)
        assertFalse("Cancellation must not report successful EOS", ended)
    }

    private fun runProof(moving: Boolean, cancelAfterFirstDecoder: Boolean = false, cancelWithBothDecoders: Boolean = false, withoutLookahead: Boolean = false) {
        evidence.mkdirs()
        val activity = instrumentation.startActivitySync(
            Intent(context, MultiInputProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MultiInputProofActivity
        val thread = HandlerThread("ProofPixelCopy").apply { start() }
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (!activity.surfaceView.holder.surface.isValid && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertTrue("SurfaceView unavailable", activity.surfaceView.holder.surface.isValid)
            // Forward/backward project time, with explicit resource recreation between samples.
            for (projectUs in listOf(0L, 1_000_000L, 500_000L, 2_000_000L)) {
                renderSample(activity, Handler(thread.looper), projectUs, moving, cancelAfterFirstDecoder, cancelWithBothDecoders, withoutLookahead)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            thread.quitSafely()
            thread.join(2_000)
        }
    }

    private fun renderSample(activity: MultiInputProofActivity, handler: Handler, projectUs: Long, moving: Boolean, cancelAfterFirstDecoder: Boolean, cancelWithBothDecoders: Boolean, withoutLookahead: Boolean) {
        val rendered = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        lateinit var graph: PreviewingMultipleInputVideoGraph
        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>) = Size(640, 360)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
                if (inputId == 0) OverlaySettings.Builder().setScale(0.4f, 0.4f)
                    .setBackgroundFrameAnchor(0.4f, 0f).build()
                else OverlaySettings.Builder().build()
        }
        graph = PreviewingMultipleInputVideoGraph(
            context, DefaultVideoFrameProcessor.Factory.Builder().build(), ColorInfo.SDR_BT709_LIMITED,
            DebugViewProvider.NONE, object : VideoGraph.Listener {
                override fun onOutputSizeChanged(width: Int, height: Int) {}
                override fun onOutputFrameAvailableForRendering(framePresentationTimeUs: Long) {
                    if (projectUs != framePresentationTimeUs) error.set(AssertionError("Expected $projectUs, output $framePresentationTimeUs"))
                    if (withoutLookahead) graph.renderPreviewOutputFrame(VideoFrameProcessor.RENDER_OUTPUT_FRAME_IMMEDIATELY) { rendered.countDown() }
                    else { graph.renderOutputFrame(VideoFrameProcessor.RENDER_OUTPUT_FRAME_IMMEDIATELY); rendered.countDown() }
                }
                override fun onEnded(finalFramePresentationTimeUs: Long) { ended.countDown() }
                override fun onError(exception: VideoFrameProcessingException) {
                    error.set(exception); rendered.countDown(); ended.countDown()
                }
            }, java.util.concurrent.Executor { it.run() }, settings, emptyList(), 0,
        )
        val decoders = mutableListOf<MediaCodec>()
        try {
            graph.setOutputSurfaceInfo(SurfaceInfo(activity.surfaceView.holder.surface,
                activity.surfaceView.width, activity.surfaceView.height))
            graph.registerInput(0)
            graph.registerInput(1)
            val foreground = graph.getProcessor(0)
            val background = graph.getProcessor(1)
            assertNotSame("processors must be independent", foreground, background)
            assertNotSame("decoder surfaces must be independent", foreground.inputSurface, background.inputSurface)
            // Established compositor order: input 0 is the foreground.
            val foregroundName = if (moving) "probe_sweep_720p_6s.mp4" else "probe_blue_720p_3s.mp4"
            decodeOne(foregroundName, foreground, projectUs, decoders)
            if (cancelAfterFirstDecoder) {
                // A real decoder submitted a frame; the compositor cannot advance
                // because the second source has not started. Cancellation must
                // retire this texture without EOS or a fabricated success.
                return
            }
            decodeOne("probe_red_720p_3s.mp4", background, projectUs, decoders)
            if (cancelWithBothDecoders) return
            if (!withoutLookahead) {
                foreground.signalEndOfInput()
                background.signalEndOfInput()
            }
            assertTrue("no compositor output at $projectUs", rendered.await(10, TimeUnit.SECONDS))
            assertNull("graph error", error.get())
            if (!withoutLookahead) assertTrue("graph did not drain", ended.await(10, TimeUnit.SECONDS))
            else assertEquals("Exact-match proof must not synthesize EOS", 1L, ended.count)
            // PixelCopy waits for a queued SurfaceView buffer and samples actual displayed pixels.
            val bitmap = Bitmap.createBitmap(activity.surfaceView.width, activity.surfaceView.height, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            var copyResult = -1
            PixelCopy.request(activity.surfaceView, bitmap, { copyResult = it; copied.countDown() }, handler)
            assertTrue("PixelCopy timeout", copied.await(5, TimeUnit.SECONDS))
            assertEquals("PixelCopy", PixelCopy.SUCCESS, copyResult)
            File(evidence, "${if (moving) "moving" else "solid"}-$projectUs.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Surface aspect ratio may letterbox the 16:9 canvas. Sample its center line.
            val red = bitmap.getPixel(bitmap.width / 10, bitmap.height / 2)
            val blue = bitmap.getPixel((bitmap.width * 0.7).toInt(), bitmap.height / 2)
            assertTrue("background must be red: $red", Color.red(red) > 180 && Color.blue(red) < 60)
            for (x in listOf(0.45, 0.95)) {
                val outside = bitmap.getPixel((bitmap.width * x).toInt(), bitmap.height / 2)
                assertTrue("overlay exceeds intended footprint at $x", Color.red(outside) > 180 && Color.blue(outside) < 60)
            }
            if (moving) {
                val retriever = MediaMetadataRetriever()
                val expected = try {
                    retriever.setDataSource(File(retainedMediaTestDirectory(context), foregroundName).absolutePath)
                    retriever.getFrameAtTime(projectUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
                } finally { retriever.release() }
                val wrongRetriever = MediaMetadataRetriever()
                val wrong = try {
                    wrongRetriever.setDataSource(File(retainedMediaTestDirectory(context), foregroundName).absolutePath)
                    wrongRetriever.getFrameAtTime(projectUs + 1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                } finally { wrongRetriever.release() }
                fun errorAgainst(source: Bitmap): Double {
                    val canvasHeight = bitmap.width * 360f / 640f
                    val top = (bitmap.height - canvasHeight) / 2f
                    var errorSum = 0.0
                    for (y in 0 until 32) for (x in 0 until 32) {
                        val actual = bitmap.getPixel((bitmap.width * (0.5f + (x + 0.5f) / 32f * 0.4f)).toInt(),
                            (top + canvasHeight * (0.3f + (y + 0.5f) / 32f * 0.4f)).toInt())
                        val reference = source.getPixel(((x + 0.5f) / 32f * source.width).toInt(),
                            ((y + 0.5f) / 32f * source.height).toInt())
                        fun luma(c: Int) = Color.red(c) + Color.green(c) + Color.blue(c)
                        errorSum += kotlin.math.abs(luma(actual) - luma(reference))
                    }
                    return errorSum / 1024
                }
                val expectedError = errorAgainst(expected)
                val wrongError = errorAgainst(wrong)
                File(evidence, "timing-$projectUs.txt").writeText("expected=$expectedError wrong+1s=$wrongError\n")
                assertTrue("wrong moving frame at $projectUs: $expectedError", expectedError < 30)
                assertTrue("static or mistimed frame at $projectUs: expected=$expectedError wrong=$wrongError", expectedError < wrongError)
                expected.recycle(); wrong.recycle()
            } else {
                assertTrue("foreground must be blue: $blue", Color.blue(blue) > 180 && Color.red(blue) < 60)
            }
            bitmap.recycle()
        } finally {
            // Stop all producers before disposing processors and their owned input surfaces.
            decoders.forEach { it.stop(); it.release() }
            graph.release() // No exception suppression: release is part of acceptance.
            graph.release()
            if (cancelAfterFirstDecoder || cancelWithBothDecoders) {
                assertEquals("Cancellation must not emit EOS", 1L, ended.count)
                assertNull("Cancellation graph error", error.get())
            }
        }
    }

    private fun decodeOne(name: String, processor: VideoFrameProcessor, projectUs: Long, decoders: MutableList<MediaCodec>) {
        val file = File(retainedMediaTestDirectory(context), name)
        context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
            }
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            processor.registerInputStream(VideoFrameProcessor.INPUT_TYPE_SURFACE, listOf(Presentation.createForWidthAndHeight(640, 360, Presentation.LAYOUT_SCALE_TO_FIT)),
                FrameInfo.Builder(ColorInfo.SDR_BT709_LIMITED, format.getInteger(MediaFormat.KEY_WIDTH),
                    format.getInteger(MediaFormat.KEY_HEIGHT)).build())
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoders.add(codec)
            codec.configure(format, processor.inputSurface, null, 0)
            codec.start()
            extractor.seekTo(projectUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                if (!inputEnded) {
                    val bufferIndex = codec.dequeueInputBuffer(10_000)
                    if (bufferIndex >= 0) {
                        val count = extractor.readSampleData(codec.getInputBuffer(bufferIndex)!!, 0)
                        if (count < 0) {
                            codec.queueInputBuffer(bufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(bufferIndex, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    if (info.size > 0 && info.presentationTimeUs >= projectUs) {
                        var registered = processor.registerInputFrame()
                        while (!registered && System.currentTimeMillis() < deadline) {
                            Thread.sleep(5)
                            registered = processor.registerInputFrame()
                        }
                        check(registered) { "Input registration timed out for $name" }
                        codec.releaseOutputBuffer(outputIndex, projectUs * 1000)
                        android.util.Log.i("MultiInputProof", "$name sourcePTS=${info.presentationTimeUs} projectPTS=$projectUs surface=${processor.inputSurface}")
                        return
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
            error("Decoder did not produce $name at $projectUs")
        } finally { extractor.release() }
    }
}
