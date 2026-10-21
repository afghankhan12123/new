package com.videoeditor.core.media

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Stage8FadeSeekPcmDeviceTest {
    @Test fun every_fade_curve_applies_absolute_gain_after_paused_seeks() {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val directory = File(context.filesDir,"stage8-fade-seek-${SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
        fun fixture(name: String): MediaRef {
            val f = File(directory,name)
            val assets = if(name.startsWith("clip")) context.assets else inst.context.assets
            assets.open(if(name.startsWith("clip")) name else "stage8/$name").use { input -> f.outputStream().use(input::copyTo) }
            return MediaMetadataExtractor().extract(context,Uri.fromFile(f)).getOrNull()!!
        }
        val video = fixture("clip1_720p_6s.mp4"); val tone = fixture("tone440_48k_mono.m4a")
        val sourceEnergy = DoubleArray(2); val sourceFrames = IntArray(2)
        visitAudioSamples(File(directory,"tone440_48k_mono.m4a")) { time,ch,sample ->
            val w=when(time) { in 500_000L..999_999L -> 0; in 4_500_000L..4_999_999L -> 1; else -> -1 }
            if(ch==0 && w>=0) { sourceEnergy[w]+=sample*sample;sourceFrames[w]++ }
        }
        val base=ProjectCreator().create("Fade seek",listOf(video))
        val results=org.json.JSONArray()
        for(curve in FadeCurve.entries) {
            val project=base.copy(mediaRefs=base.mediaRefs+tone,timeline=base.timeline.copy(
                videoClips=base.timeline.videoClips.map { it.copy(muted=true) },
                audioClips=listOf(AudioClip("tone",tone.id,sourceEndMicros=6_000_000,fadeInMicros=2_000_000,fadeOutMicros=2_000_000,
                    fadeInCurve=curve,fadeOutCurve=curve))))
            val lock=Any();var phase=0;val energy=DoubleArray(2);val frames=IntArray(2)
            val pcm=Array(2) { java.io.ByteArrayOutputStream() }
            val sink=object:ForwardingAudioSink(DefaultAudioSink.Builder(context).build()) {
                var format=Format.Builder().build()
                override fun configure(inputFormat:Format,bufferSize:Int,outputChannels:IntArray?) {
                    format=inputFormat;super.configure(inputFormat,bufferSize,outputChannels)
                }
                override fun handleBuffer(buffer:ByteBuffer,presentationTimeUs:Long,encodedAccessUnitCount:Int):Boolean {
                    val copy=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);val before=buffer.position()
                    val handled=super.handleBuffer(buffer,presentationTimeUs,encodedAccessUnitCount)
                    synchronized(lock) {
                        val bytes=if(format.pcmEncoding==C.ENCODING_PCM_FLOAT)4 else 2
                        val consumed=buffer.position()-before
                        val raw=ByteArray(consumed);copy.duplicate().apply { limit(before+consumed) }.get(raw);pcm[phase].write(raw)
                        repeat(consumed/(bytes*format.channelCount)) { frame ->
                            val time=presentationTimeUs+frame*1_000_000L/format.sampleRate
                            val wanted=if(phase==0)time in 500_000L..999_999L else time in 4_500_000L..4_999_999L
                            repeat(format.channelCount) { channel ->
                                val sample=if(bytes==4)copy.float.toDouble()*32768 else copy.short.toDouble()
                                if(channel==0 && wanted) {energy[phase]+=sample*sample;frames[phase]++}
                            }
                        }
                    }
                    return handled
                }
            }
            lateinit var player:CompositionPreviewController
            fun onMain(action:()->Unit)=inst.runOnMainSync(action)
            fun await(predicate:()->Boolean) {
                val end=SystemClock.elapsedRealtime()+20000
                while(!predicate() && SystemClock.elapsedRealtime()<end)Thread.sleep(20)
                assertTrue("$curve transport stalled: ${player.state.value}",predicate())
            }
            onMain { player=CompositionPreviewController.Factory(context,CompositionBuilder()).create(sink);player.setProject(project) }
            try {
                for((index,start) in listOf(250_000L,4_250_000L).withIndex()) {
                    synchronized(lock){phase=index}
                    onMain {player.seekTo(start,resumePlayback=false)}
                    await {player.state.value.positionMicros in start-1000..start+1000 && !player.state.value.isPlaying}
                    onMain {player.play()}
                    val until=if(index==0)1_100_000L else 5_100_000L
                    await {player.state.value.positionMicros>=until}
                    onMain {player.pause()}
                }
                synchronized(lock) {
                    for(index in 0..1) {
                        assertTrue("Missing $curve phase$index PCM",frames[index]>10000)
                        fun gain(x:Double):Double {
                            val v=x.coerceIn(0.0,1.0)
                            return when(curve) {
                                FadeCurve.Linear->v;FadeCurve.EaseIn->v*v;FadeCurve.EaseOut->1-(1-v)*(1-v)
                                FadeCurve.Smooth->v*v*(3-2*v);FadeCurve.Exponential->(3.0.pow(v)-1)/2
                            }
                        }
                        val start=if(index==0).5 else 4.5
                        var squares=0.0
                        for(frame in 0 until 24000) {
                            val time=start+frame/48000.0
                            val value=gain(if(index==0)time/2 else (6-time)/2)
                            squares+=value*value
                        }
                        val expected=sqrt(sourceEnergy[index]/sourceFrames[index])*sqrt(squares/24000)
                        val actual=sqrt(energy[index]/frames[index]);val error=20*log10(actual/expected)
                        results.put(org.json.JSONObject().put("curve",curve.name).put("phase",index).put("expectedRms",expected).put("actualRms",actual).put("errorDb",error).put("frames",frames[index]))
                        assertEquals("$curve paused-seek gain phase$index",0.0,error,.15)
                    }
                }
            } finally {
                onMain {player.release()}
                synchronized(lock){pcm.forEachIndexed { i,data -> File(directory,"${curve.name}-$i.pcm").writeBytes(data.toByteArray()) }}
                File(directory,"measurements.json").writeText(results.toString(2))
                android.util.Log.i("Stage8FadeSeek","retained=${directory.absolutePath}")
            }
        }
    }
}
