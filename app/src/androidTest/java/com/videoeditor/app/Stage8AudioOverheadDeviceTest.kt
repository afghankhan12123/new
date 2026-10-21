package com.videoeditor.app

import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.MediaMetadataExtractor
import com.videoeditor.core.media.audio.*
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Measurements, not invented pass budgets. Run alone after compilation; release
 * instrumentation records the minified R8 DSP implementation separately from debug.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Stage8AudioOverheadDeviceTest {
    @Test fun measure_pcm_cpu_allocations_memory_and_native_ducking_analysis() {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val folder = File(context.filesDir, "stage8-overhead-${SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
        val report = JSONObject().put("debuggable", context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .put("rate", 48000).put("blockFrames", 1024).put("channels", 1)
            .put("trials", 20).put("blocksPerTrial", 100).put("threshold", "No new performance threshold; report actual measured overhead")
        val input = ByteBuffer.allocateDirect(2048).order(ByteOrder.LITTLE_ENDIAN)
        repeat(1024) { input.putShort((sin(2 * PI * 440 * it / 48000) * 10000).toInt().toShort()) }; input.flip()
        val copy = ByteBuffer.allocateDirect(2048)
        val envelope = VolumeEnvelope(listOf(VolumePoint("a",0,0f), VolumePoint("b",10_000_000,-12f)))
        val curve = DuckingCurve(0, 20_000, FloatArray(500) { .25f })
        val scenarios = listOf(
            "copy-bypass" to emptyList(),
            "automation" to listOf(VolumeEnvelopeAudioProcessor(envelope,0,1f)),
            "eq-three-bands" to listOf(EqualizerAudioProcessor(EqualizerSettings(true,6f,-3f,4f))),
            "ducking" to listOf(DuckingGainAudioProcessor(curve,0)),
            "combined" to listOf(VolumeEnvelopeAudioProcessor(envelope,0,1f),
                EqualizerAudioProcessor(EqualizerSettings(true,6f,-3f,4f)), BalanceAudioProcessor(AudioBalance(-.5f)),
                DuckingGainAudioProcessor(curve,0)),
        )
        val rows = JSONArray()
        for ((name, processors) in scenarios) {
            var format = AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT)
            for (processor in processors) { format = processor.configure(format); processor.flush() }
            fun block() {
                input.position(0)
                if (processors.isEmpty()) { copy.clear(); copy.put(input); return }
                var pending = input
                for (processor in processors) {
                    processor.queueInput(pending)
                    assertFalse("Processor failed to consume PCM", pending.hasRemaining())
                    pending = processor.output
                }
                pending.position(pending.limit())
            }
            repeat(200) { block() }
            System.gc(); Thread.sleep(100)
            val statsBefore = Debug.getRuntimeStats(); val javaBefore = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            val nativeBefore = Debug.getNativeHeapAllocatedSize()
            val wall = mutableListOf<Double>(); val cpu = mutableListOf<Double>()
            repeat(20) {
                val start = SystemClock.elapsedRealtimeNanos(); val startCpu = Debug.threadCpuTimeNanos()
                repeat(100) { block() }
                cpu += (Debug.threadCpuTimeNanos() - startCpu) / 100.0
                wall += (SystemClock.elapsedRealtimeNanos() - start) / 100.0
            }
            val statsAfter = Debug.getRuntimeStats()
            val javaAfter = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }; val nativeAfter = Debug.getNativeHeapAllocatedSize()
            wall.sort(); cpu.sort()
            val wallMedian = (wall[9]+wall[10])/2; val cpuMedian = (cpu[9]+cpu[10])/2
            val bytesBefore = statsBefore["art.gc.bytes-allocated"]?.toLongOrNull(); val bytesAfter = statsAfter["art.gc.bytes-allocated"]?.toLongOrNull()
            rows.put(JSONObject().put("scenario",name).put("medianWallNsPerBlock",wallMedian).put("p95WallNsPerBlock",wall[18])
                .put("medianThreadCpuNsPerBlock",cpuMedian).put("cpuPercentRealtime",cpuMedian / (1024.0/48000*1e9)*100)
                .put("processAllocatedBytesPerBlock", if(bytesBefore != null && bytesAfter != null) (bytesAfter-bytesBefore)/2000.0 else JSONObject.NULL)
                .put("allocationScope","ART whole-process delta includes test harness/background threads; not an isolated allocation counter")
                .put("javaHeapDeltaBytes",javaAfter-javaBefore).put("nativeHeapDeltaBytes",nativeAfter-nativeBefore)
                .put("wallSamplesNs",JSONArray(wall)).put("cpuSamplesNs",JSONArray(cpu)))
            processors.forEach(AudioProcessor::reset)
        }
        report.put("pcm", rows)
        fun fixture(name: String): MediaRef {
            val file = File(folder,name)
            inst.context.assets.open("stage8/$name").use { input -> file.outputStream().use(input::copyTo) }
            return MediaMetadataExtractor().extract(context,Uri.fromFile(file)).getOrNull()!!
        }
        val music = fixture("music_tone220_48k.m4a"); val voice = fixture("voice_trigger_880_2s_4s.m4a")
        val base = ProjectCreator().create("Ducking overhead",emptyList())
        val project = base.copy(mediaRefs = listOf(music,voice), timeline = Timeline(audioClips = listOf(
            AudioClip("music",music.id,sourceEndMicros=6_000_000,ducking=DuckingSettings(enabled=true,triggerClipIds=listOf("voice"))),
            AudioClip("voice",voice.id,sourceEndMicros=6_000_000))))
        val times = JSONArray(); val cachedTimes = JSONArray()
        repeat(5) {
            val cache = DuckingAnalysisCache(); val service = DuckingAnalysisService(context,DuckingAnalyzer(),cache)
            val start = SystemClock.elapsedRealtimeNanos(); service.ensureAnalyzed(project); times.put((SystemClock.elapsedRealtimeNanos()-start)/1e6)
            assertFalse(service.analysisFailed(project,project.timeline.audioClips.first()))
            val cached = SystemClock.elapsedRealtimeNanos(); service.ensureAnalyzed(project); cachedTimes.put((SystemClock.elapsedRealtimeNanos()-cached)/1e6)
        }
        report.put("duckingFreshAnalysisMs",times).put("duckingCacheHitMs",cachedTimes)
        File(folder,"measurements.json").writeText(report.toString(2))
        android.util.Log.i("Stage8Overhead","retained=${folder.absolutePath}")
    }
}
