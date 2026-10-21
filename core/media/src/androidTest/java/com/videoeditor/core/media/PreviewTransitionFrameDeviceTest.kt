package com.videoeditor.core.media

import android.graphics.PixelFormat
import android.media.ImageReader
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
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Independent baked identities and alpha pilots on the real composed output surface. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreviewTransitionFrameDeviceTest {
    @Test fun before_inside_after_reverse_repeated_transition_and_layered_playback() {
        val inst=InstrumentationRegistry.getInstrumentation(); val context=inst.targetContext
        val folder=File(context.filesDir,"preview-transition-${System.nanoTime()}").apply { mkdirs() }
        val refs=(0..1).map { lane ->
            val file=File(folder,"lane-$lane.mp4")
            inst.context.assets.open("transition_identity_$lane.mp4").use { input -> file.outputStream().use(input::copyTo) }
            MediaMetadataExtractor().extract(context,Uri.fromFile(file)).getOrNull()!!
        }
        data class Frame(val ns:Long,val pilots:List<Int>,val markers:List<Int>,val pip:Int,val yellow:Int)
        val queue=LinkedBlockingQueue<Frame>(); val metrics=org.json.JSONArray()
        val thread=HandlerThread("preview-transition-pixels").apply { start() }
        val reader=ImageReader.newInstance(1280,720,PixelFormat.RGBA_8888,3)
        reader.setOnImageAvailableListener({ images -> images.acquireLatestImage()?.use { image ->
            val p=image.planes[0]
            fun rgb(x:Int,y:Int):List<Int> { val o=y*p.rowStride+x*p.pixelStride; return (0..2).map { p.buffer.get(o+it).toInt() and 255 } }
            fun value(x:Int,y:Int)=rgb(x,y).sum()/3
            val pilots=(0..1).map { value(32,92+it*64) }
            val markers=(0..1).map { lane -> if(pilots[lane]<=4) -1 else (0..7).sumOf { if(value(96+it*64,92+lane*64)>pilots[lane]/2) 1 shl it else 0 } }
            val pipPilot=value(966,499)
            val pip=if(pipPilot<200) -1 else (0..7).sumOf { if(value(960+((96+it*64)*.2f).toInt(),499)>pipPilot/2) 1 shl it else 0 }
            var yellow=0
            for(y in 540 until 690 step 3) for(x in 200 until 900 step 3) {
                val c=rgb(x,y); if(c[0]>180 && c[1]>180 && c[2]<100) yellow++
            }
            queue.offer(Frame(SystemClock.elapsedRealtimeNanos(),pilots,markers,pip,yellow))
        } },Handler(thread.looper))
        var controller:CompositionPreviewController?=null
        fun main(block:()->Unit)=inst.runOnMainSync(block)
        fun matches(frame:Frame,target:Long,layered:Boolean):Boolean {
            val inferred=if(frame.markers[0]>=0) frame.markers[0]-30 else frame.markers[1]+30
            val lower=target*30/1_000_000;val upper=(target*30+999_999)/1_000_000
            if(inferred.toLong() !in lower..upper) return false
            val actualTime=inferred*1_000_000L/30
            val alpha=((actualTime-1_500_000)/1_000_000.0).coerceIn(0.0,1.0)
            if(kotlin.math.abs(frame.pilots[0]-255*(1-alpha))>=8 || kotlin.math.abs(frame.pilots[1]-255*alpha)>=8) return false
            if(frame.markers[0]>=0 && frame.markers[0]!=inferred+30) return false
            if(frame.markers[1]>=0 && frame.markers[1]!=inferred-30) return false
            return !layered || (frame.pip==inferred && frame.yellow>100)
        }
        fun awaitFrame(label:String,target:Long,layered:Boolean,start:Long) {
            val until=SystemClock.elapsedRealtimeNanos()+TimeUnit.SECONDS.toNanos(15);var last:Frame?=null
            while(SystemClock.elapsedRealtimeNanos()<until) {
                last=queue.poll(50,TimeUnit.MILLISECONDS)?:continue
                if(last.ns>=start && matches(last,target,layered)) {
                    metrics.put(org.json.JSONObject().put("case",label).put("targetUs",target).put("requestBootNs",start).put("presentedBootNs",last.ns).put("latencyMs",(last.ns-start)/1e6))
                    return
                }
            }
            fail("$label missing target=$target last=$last state=${controller?.state?.value}")
        }
        PreviewPhaseTrace.clearFrameDeliveryTrace()
        PreviewPhaseTrace.frameDeliveryDiagnostic=true
        try {
            for(layered in listOf(false,true)) {
                val base=ProjectCreator().create("Transition preview",refs,canvas=CanvasSettings(width=1280,height=720))
                val a=Clip("a",refs[0].id,1_000_000,3_000_000)
                val b=Clip("b",refs[1].id,1_000_000,3_000_000,2_000_000)
                val project=base.copy(timeline=Timeline(videoClips=listOf(a,b),transitions=listOf(Transition("join","a","b",1_000_000)),
                    overlayClips=if(!layered) emptyList() else listOf(OverlayClip("pip",refs[1].id,sourceEndMicros=4_000_000,timelineDurationMicros=4_000_000,muted=true,transform=VisualTransform(scale=.2f,offsetX=.7f,offsetY=.5f))),
                    textOverlays=if(!layered) emptyList() else listOf(TextOverlay("caption","TOP",startMicros=0,durationMicros=4_000_000,positionY=.85f,fontSizeSp=80f,colorArgb=android.graphics.Color.YELLOW))))
                queue.clear();val start=SystemClock.elapsedRealtimeNanos()
                main { controller=CompositionPreviewController.Factory(context,CompositionBuilder()).create();controller!!.attachHeadlessSurface(reader.surface,Size(1280,720));controller!!.setProject(project) }
                awaitFrame("startup-$layered",0,layered,start)
                repeat(3) { iteration ->
                    for(target in listOf(1_200_000L,1_500_000L,1_733_334L,2_000_000L,2_233_334L,2_466_667L,2_500_000L,2_800_000L,2_000_000L,1_200_000L)) {
                        queue.clear();val request=SystemClock.elapsedRealtimeNanos()
                        main {
                            CompositionPreviewController::class.java.getDeclaredField("visualRefreshPending").apply { isAccessible=true }.setBoolean(controller,true)
                            controller!!.seekTo(target,resumePlayback=false)
                        }
                        awaitFrame("seek-$layered-$iteration",target,layered,request)
                        main { assertEquals(1,controller!!.rebuildCount);assertNull(controller!!.state.value.error) }
                    }
                }
                queue.clear();val restart=SystemClock.elapsedRealtimeNanos()
                main { controller!!.seekTo(0,resumePlayback=false) };awaitFrame("play-start-$layered",0,layered,restart)
                main { controller!!.play() }
                var overlap=false;var after=false;val until=SystemClock.elapsedRealtimeNanos()+TimeUnit.SECONDS.toNanos(10)
                while(!after && SystemClock.elapsedRealtimeNanos()<until) {
                    val frame=queue.poll(50,TimeUnit.MILLISECONDS)?:continue
                    if(frame.pilots.all { it>30 }) { val index=frame.markers[0]-30;assertTrue(matches(frame,index*1_000_000L/30,layered));overlap=true }
                    if(frame.markers[1]>=54) after=true
                }
                assertTrue("No composed overlap during playback",overlap);assertTrue("Playback did not cross transition",after)
                main { assertEquals(1,controller!!.rebuildCount);assertNull(controller!!.state.value.error);controller!!.release();controller=null }
            }
        } finally {
            File(context.getExternalFilesDir(null),"preview-transition-measurements.json").writeText(metrics.toString(2))
            main { controller?.release() }
            PreviewPhaseTrace.writeFrameDeliveryTrace(File(context.getExternalFilesDir(null),"preview-first-frame-traces/transitions-${SystemClock.elapsedRealtimeNanos()}.trace"))
            PreviewPhaseTrace.frameDeliveryDiagnostic=false
            reader.close();thread.quitSafely();thread.join(5000);folder.deleteRecursively()
        }
    }
}
