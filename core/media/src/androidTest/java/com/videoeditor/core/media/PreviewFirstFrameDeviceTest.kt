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
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.media.preview.PreviewPhaseTrace
import com.videoeditor.editing.ProjectCreator
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** A frame marker is decoded from the actual composed surface, never the player clock. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreviewFirstFrameDeviceTest {
    @Test fun marked_video_photo_startup_reopen_and_repeated_exact_seeks() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val folder = File(context.filesDir, "first-frame-${System.nanoTime()}").apply { mkdirs() }
        val source = File(folder, "marked.mp4")
        inst.context.assets.open("transition_identity_0.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val photo = File(folder, "photo.png")
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(source.path)
        val bitmap = retriever.getFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle(); retriever.release()
        val videoRef = MediaMetadataExtractor().extract(context, Uri.fromFile(source)).getOrNull()!!
        val photoRef = MediaMetadataExtractor().extract(context, Uri.fromFile(photo)).getOrNull()!!
        data class Frame(val marker: Int, val ns: Long)
        val frames = LinkedBlockingQueue<Frame>()
        val thread = HandlerThread("first-frame-pixels").apply { start() }
        val reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ images -> images.acquireLatestImage()?.use { image ->
            val plane = image.planes[0]
            fun white(x: Int): Boolean {
                val offset = 92 * plane.rowStride + x * plane.pixelStride
                return (0..2).sumOf { plane.buffer.get(offset + it).toInt() and 255 } > 645
            }
            if (white(32)) frames.offer(Frame((0..7).sumOf { if (white(96 + it * 64)) 1 shl it else 0 }, SystemClock.elapsedRealtimeNanos()))
        } }, Handler(thread.looper))
        val measurements = org.json.JSONArray()
        var controller: CompositionPreviewController? = null
        fun main(block: () -> Unit) = inst.runOnMainSync(block)
        fun awaitFrame(label: String, expected: Int, started: Long) {
            val until = SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(15)
            var last: Frame? = null
            while (SystemClock.elapsedRealtimeNanos() < until) {
                last = frames.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (last.marker == expected && last.ns >= started) {
                    measurements.put(org.json.JSONObject().put("case",label).put("marker",expected).put("requestBootNs",started).put("presentedBootNs",last.ns).put("latencyMs",(last.ns-started)/1e6))
                    android.util.Log.i("FirstFramePerf", "case=$label marker=$expected requestBootNs=$started presentedBootNs=${last.ns} latencyMs=${(last.ns-started)/1e6}")
                    return
                }
            }
            fail("$label missing marker=$expected last=$last state=${controller?.state?.value}")
        }
        PreviewPhaseTrace.clearFrameDeliveryTrace()
        PreviewPhaseTrace.frameDeliveryDiagnostic = true
        try {
            for (opening in 0..3) {
                val refs = if (opening == 3) listOf(photoRef, videoRef) else if (opening == 2) listOf(videoRef, photoRef, videoRef) else listOf(videoRef)
                val base = ProjectCreator().create("First frame $opening", refs)
                var cursor = 0L
                val clips = base.timeline.videoClips.mapIndexed { index, clip ->
                    (if (opening == 2 && index == 0) clip.copy(sourceStartMicros=500_000, sourceEndMicros=2_500_000, playbackSpeed=1.5f) else clip)
                        .copy(timelineStartMicros=cursor).also { cursor += it.timelineDurationMicros }
                }
                val project = base.copy(timeline=base.timeline.copy(videoClips=clips))
                frames.clear()
                val started = SystemClock.elapsedRealtimeNanos()
                main {
                    controller = CompositionPreviewController.Factory(context, CompositionBuilder()).create()
                    controller!!.attachHeadlessSurface(reader.surface, Size(1280,720))
                    controller!!.setProject(project)
                }
                awaitFrame("startup-$opening", if (opening == 3) 90 else if (opening == 2) 15 else 0, started)
                val targets = if (opening == 2) listOf(600_000L to 42, 2_000_000L to 90, 5_033_333L to 21, 600_000L to 42)
                    else if (opening == 3) listOf(3_600_000L to 18, 600_000L to 90)
                    else listOf(2_000_000L to 60, 0L to 0, 4_000_000L to 120, 1_000_000L to 30, 1_000_000L to 30, 0L to 0, cursor to 179)
                repeat(3) { iteration ->
                    for ((target, marker) in targets) {
                        frames.clear()
                        val request = SystemClock.elapsedRealtimeNanos()
                        // Repeating the same timestamp must refresh the surface too.
                        main {
                            val p = CompositionPreviewController::class.java.getDeclaredField("visualRefreshPending").apply { isAccessible=true }
                            p.setBoolean(controller, true)
                            controller!!.seekTo(target, resumePlayback=false)
                        }
                        awaitFrame("seek-$opening-$iteration-$target", marker, request)
                        main { assertEquals("Ordinary seeks cannot rebuild",1,controller!!.rebuildCount); assertNull(controller!!.state.value.error) }
                    }
                }
                if (opening == 2) {
                    var next = 0L
                    val changed = project.copy(timeline=project.timeline.copy(videoClips=clips.mapIndexed { index,clip ->
                        (if(index==0) clip.copy(playbackSpeed=.5f) else clip).copy(timelineStartMicros=next).also { next+=it.timelineDurationMicros }
                    }))
                    frames.clear();val changedStart=SystemClock.elapsedRealtimeNanos()
                    main { controller!!.setProject(changed) }
                    awaitFrame("speed-change",24,changedStart)
                    frames.clear();val back=SystemClock.elapsedRealtimeNanos()
                    main { controller!!.seekTo(0,resumePlayback=false) };awaitFrame("speed-change-backward",15,back)
                    main { assertEquals("Only the explicit speed edit rebuilds",2,controller!!.rebuildCount) }
                }
                main { controller!!.release(); controller=null }
            }
        } finally {
            File(context.getExternalFilesDir(null),"preview-first-frame-measurements.json").writeText(measurements.toString(2))
            main { controller?.release() }
            PreviewPhaseTrace.writeFrameDeliveryTrace(File(context.getExternalFilesDir(null),"preview-first-frame-traces/markers-${SystemClock.elapsedRealtimeNanos()}.trace"))
            PreviewPhaseTrace.frameDeliveryDiagnostic=false
            reader.close(); thread.quitSafely(); thread.join(5000); folder.deleteRecursively()
        }
    }
}
