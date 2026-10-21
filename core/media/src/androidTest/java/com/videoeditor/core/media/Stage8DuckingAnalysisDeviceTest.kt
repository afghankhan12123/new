package com.videoeditor.core.media

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.audio.*
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import java.io.File
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Stage8DuckingAnalysisDeviceTest {
    @Test fun nativeTriggerAnalysisAttenuatesOnlyActivityAndMutedTriggerIsNotFailure() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val dir=File(context.filesDir,"stage8-duck-${android.os.SystemClock.elapsedRealtimeNanos()}").apply{mkdirs()}
        val files=listOf("music_tone220_48k.m4a","voice_trigger_880_2s_4s.m4a").map { name ->
            File(dir,name).also { f -> instrumentation.context.assets.open("stage8/$name").use { input -> f.outputStream().use(input::copyTo) } }
        }
        val refs=files.map { MediaMetadataExtractor().extract(context,Uri.fromFile(it)).getOrNull()!! }
        val target=AudioClip("music",refs[0].id,sourceEndMicros=8_000_000,ducking=DuckingSettings(
            enabled=true,triggerClipIds=listOf("voice"),strengthDb=12f,thresholdDb=-30f,attackMillis=50,releaseMillis=150))
        val trigger=AudioClip("voice",refs[1].id,sourceEndMicros=8_000_000)
        val project=ProjectCreator().create("Stage8 ducking", emptyList()).copy(mediaRefs=refs,timeline=Timeline(audioClips=listOf(target,trigger)))
        val cache=DuckingAnalysisCache(); val service=DuckingAnalysisService(context,DuckingAnalyzer(),cache)
        val curve=service.analyzeOne(project,target)!!
        fun gainAt(time:Long):Float {
            val index=((time-curve.timelineStartMicros)/curve.windowMicros).toInt()
            assertTrue(index in curve.gains.indices); return curve.gains[index]
        }
        assertEquals(1f,gainAt(1_000_000),.001f)
        assertTrue("Voice did not duck",gainAt(3_000_000) in .24f.. .28f)
        assertTrue("Music did not recover",gainAt(6_000_000)>.99f)
        assertNotNull("Renderer-shortened target must share authored cache",cache.curveFor(project,target.copy(sourceEndMicros=4_000_000)))
        val longerTarget=target.copy(playbackSpeed=.5f)
        val longerProject=project.copy(timeline=project.timeline.copy(audioClips=listOf(longerTarget,trigger)))
        assertNull("Extending project must invalidate curve tail",cache.curveFor(longerProject,longerTarget))
        val longerCurve=service.analyzeOne(longerProject,longerTarget)!!
        assertTrue("Extended tail missing",longerCurve.gains.size>curve.gains.size)
        val mutedProject=project.copy(timeline=project.timeline.copy(audioClips=listOf(target,trigger.copy(muted=true))))
        service.analyzeOne(mutedProject,target)
        assertFalse("Muted trigger incorrectly reported as decode failure",service.analysisFailed(mutedProject,target))
        assertFalse("Mute must invalidate prior active curve",cache.curveFor(mutedProject,target)?.gains?.any { it<.999f } == true)
        val brokenProject=project.copy(mediaRefs=refs.map { if(it.id==refs[1].id) it.copy(uri="file:///stage8-intentionally-missing.m4a") else it })
        assertNull(service.analyzeOne(brokenProject,target))
        assertTrue("Undecodable source must remain a failure",service.analysisFailed(brokenProject,target))
    }
    @Test fun staggeredTriggersRetainTheLaterTimelineActivity() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val dir=File(context.filesDir,"stage8-staggered-${android.os.SystemClock.elapsedRealtimeNanos()}").apply{mkdirs()}
        val files=listOf("music_tone220_48k.m4a","voice_trigger_880_2s_4s.m4a").map { name ->
            File(dir,name).also { f -> instrumentation.context.assets.open("stage8/$name").use { input -> f.outputStream().use(input::copyTo) } }
        }
        val refs=files.map { MediaMetadataExtractor().extract(context,Uri.fromFile(it)).getOrNull()!! }
        val target=AudioClip("music",refs[0].id,sourceEndMicros=8_000_000,playbackSpeed=.5f,ducking=DuckingSettings(
            enabled=true,triggerClipIds=listOf("early","late"),strengthDb=12f,thresholdDb=-30f,attackMillis=50,releaseMillis=150))
        val early=AudioClip("early",refs[1].id,sourceEndMicros=8_000_000)
        val late=early.copy(id="late",timelineStartMicros=8_000_000)
        val project=ProjectCreator().create("Stage8 staggered triggers",emptyList()).copy(mediaRefs=refs,timeline=Timeline(audioClips=listOf(target,early,late)))
        val curve=DuckingAnalysisService(context,DuckingAnalyzer(),DuckingAnalysisCache()).analyzeOne(project,target)!!
        val index=((11_000_000-curve.timelineStartMicros)/curve.windowMicros).toInt()
        assertTrue("Ducking grid truncated later trigger: size=${curve.gains.size} index=$index",index in curve.gains.indices)
        assertTrue("Later trigger did not duck",curve.gains[index] in .24f.. .28f)
    }
    @Test fun trimmedTriggerReleasesAfterItsAudibleEnd() = verifyInactiveTriggerWindow(0,4_000_000,0,6_000_000)
    @Test fun delayedActiveTriggerDoesNotDuckBeforeItStarts() = verifyInactiveTriggerWindow(2_500_000,3_500_000,2_000_000,1_000_000)
    private fun verifyInactiveTriggerWindow(start:Long,end:Long,timelineStart:Long,query:Long) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val dir=File(context.filesDir,"stage8-duck-window-${android.os.SystemClock.elapsedRealtimeNanos()}").apply{mkdirs()}
        val refs=listOf("music_tone220_48k.m4a","voice_trigger_880_2s_4s.m4a").map { name ->
            val file=File(dir,name)
            instrumentation.context.assets.open("stage8/$name").use { input -> file.outputStream().use(input::copyTo) }
            MediaMetadataExtractor().extract(context,Uri.fromFile(file)).getOrNull()!!
        }
        val target=AudioClip("music",refs[0].id,sourceEndMicros=8_000_000,ducking=DuckingSettings(
            enabled=true,triggerClipIds=listOf("voice"),strengthDb=12f,thresholdDb=-30f,attackMillis=50,releaseMillis=150))
        val trigger=AudioClip("voice",refs[1].id,sourceStartMicros=start,sourceEndMicros=end,timelineStartMicros=timelineStart)
        val project=ProjectCreator().create("Stage8 ducking windows",emptyList()).copy(mediaRefs=refs,timeline=Timeline(audioClips=listOf(target,trigger)))
        val curve=DuckingAnalysisService(context,DuckingAnalyzer(),DuckingAnalysisCache()).analyzeOne(project,target)!!
        val processor=DuckingGainAudioProcessor(curve,0)
        processor.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(48000,1,androidx.media3.common.C.ENCODING_PCM_16BIT))
        processor.setPositionMicros(query);processor.flush()
        val buffer=java.nio.ByteBuffer.allocateDirect(2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(10000)
        buffer.flip();processor.queueInput(buffer)
        assertEquals("Ducking outside trigger at $query",10000.0,processor.output.order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toDouble(),20.0)
        processor.reset()
    }
}
