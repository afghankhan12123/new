package com.videoeditor.core.media

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.test.platform.app.InstrumentationRegistry
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.audio.*
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
class Stage8TransportPcmDeviceTest {
    @Test fun combined_effects_survive_paused_seek_eos_and_replay_in_real_sink_pcm() {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val directory = File(context.filesDir, "stage8-transport-${SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
        fun fixture(name: String): MediaRef {
            val f = File(directory, name)
            val assets = if (name.startsWith("clip")) context.assets else inst.context.assets
            assets.open(if (name.startsWith("clip")) name else "stage8/$name").use { input -> f.outputStream().use(input::copyTo) }
            return MediaMetadataExtractor().extract(context, Uri.fromFile(f)).getOrNull()!!
        }
        val video = fixture("clip1_720p_6s.mp4"); val music = fixture("tone440_48k_mono.m4a"); val voice = fixture("voice_trigger_880_2s_4s.m4a")
        val base = ProjectCreator().create("Stage8 transport", listOf(video))
        val project = base.copy(mediaRefs = base.mediaRefs + listOf(music, voice), timeline = base.timeline.copy(
            videoClips = base.timeline.videoClips.map { it.copy(muted = true) }, audioClips = listOf(
                AudioClip("music", music.id, sourceEndMicros = 6_000_000, equalizer = EqualizerPreset.Warm.settings(), balance = AudioBalance(-1f),
                    volumeEnvelope = VolumeEnvelope(listOf(VolumePoint("a", 0, 0f, VolumeInterpolation.Hold), VolumePoint("b", 1_500_000, -12f))),
                    fadeInMicros = 200_000, fadeOutMicros = 500_000, fadeOutCurve = FadeCurve.Smooth,
                    ducking = DuckingSettings(enabled = true, triggerClipIds = listOf("voice"), strengthDb = 12f, thresholdDb = -30f, attackMillis = 50, releaseMillis = 150)),
                AudioClip("voice", voice.id, sourceEndMicros = 6_000_000, balance = AudioBalance(1f)))))
        val cache = DuckingAnalysisCache(); DuckingAnalysisService(context, DuckingAnalyzer(), cache).ensureAnalyzed(project)
        val lock = Any(); var phase = 0
        var seekRequestNs = 0L; var resumeRequestNs = 0L; var firstSeekPcmNs = 0L
        val energy = Array(4) { DoubleArray(2) }; val frames = Array(4) { IntArray(2) }
        val raw = Array(4) { java.io.ByteArrayOutputStream() }
        val csv = StringBuilder("phase,presentationUs,initialBytes,consumedBytes,rate,channels,encoding\n")
        val sink = object : ForwardingAudioSink(DefaultAudioSink.Builder(context).build()) {
            var format = Format.Builder().build()
            override fun configure(inputFormat: Format, bufferSize: Int, outputChannels: IntArray?) {
                format = inputFormat; super.configure(inputFormat, bufferSize, outputChannels)
            }
            override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                val before = buffer.position(); val copy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                synchronized(lock) {
                    val consumed = buffer.position() - before
                    if (phase == 2 && consumed > 0 && presentationTimeUs >= 2_250_000 && seekRequestNs > 0 && firstSeekPcmNs == 0L)
                        firstSeekPcmNs = SystemClock.elapsedRealtimeNanos()
                    val data = ByteArray(consumed); copy.duplicate().apply { limit(before + consumed) }.get(data); raw[phase].write(data)
                    csv.append("$phase,$presentationTimeUs,$before,$consumed,${format.sampleRate},${format.channelCount},${format.pcmEncoding}\n")
                    assertEquals(2, format.channelCount)
                    val bytes = if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
                    repeat(consumed / (bytes * 2)) { frame ->
                        // The application wrapper stamps the current consumed-frame position.
                        val time = presentationTimeUs + frame * 1_000_000L / format.sampleRate
                        val window = when(time) { in 500_000L..999_999L -> 0; in 2_500_000L..2_999_999L -> 1; else -> -1 }
                        repeat(2) { ch ->
                            val sample = if (bytes == 4) copy.float.toDouble() * 32768 else copy.short.toDouble()
                            if (ch == 0 && window >= 0) { energy[phase][window] += sample * sample; frames[phase][window]++ }
                        }
                    }
                }
                return handled
            }
        }
        lateinit var player: CompositionPreviewController
        fun onMain(action: () -> Unit) = inst.runOnMainSync(action)
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 20000
            while(!predicate() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertTrue("$message: ${player.state.value}", predicate())
        }
        onMain { player = CompositionPreviewController.Factory(context, CompositionBuilder(cache)).create(sink); player.setProject(project); player.play() }
        try {
            await("Initial EOS") { player.state.value.positionMicros >= 6_000_000 }
            synchronized(lock) { phase = 1 }
            onMain { player.play() }
            await("Replay started") { player.state.value.positionMicros in 200_000..1_999_999 }
            await("Replay PCM window") { player.state.value.positionMicros in 1_200_000..2_499_999 }
            onMain { player.pause() }
            synchronized(lock) { phase = 2 }
            onMain { synchronized(lock) { seekRequestNs = SystemClock.elapsedRealtimeNanos() }; player.seekTo(2_250_000, resumePlayback = false) }
            await("Paused seek") { player.state.value.positionMicros in 2_200_000..2_400_000 && !player.state.value.isPlaying }
            Thread.sleep(200); assertFalse(player.state.value.isPlaying)
            onMain { synchronized(lock) { resumeRequestNs = SystemClock.elapsedRealtimeNanos() }; player.play() }
            await("Seek resume") { player.state.value.positionMicros in 3_200_000..4_499_999 }
            onMain { player.pause() }
            onMain { player.seekTo(6_000_000, resumePlayback = false) }
            await("Paused EOS landing") { player.state.value.actualPositionMicros >= 5_999_000 && !player.state.value.isPlaying }
            synchronized(lock) { phase = 3 }
            Thread.sleep(300); assertFalse("EOS seek resumed playback", player.state.value.isPlaying)
            synchronized(lock) {
                fun rms(p: Int, w: Int): Double {
                    assertTrue("Missing phase$p window$w PCM: ${frames[p].toList()}", frames[p][w] > 10000)
                    return sqrt(energy[p][w] / frames[p][w])
                }
                assertEquals("Automation plus ducking active gain", .0630957, rms(0,1)/rms(0,0), .01)
                assertEquals("Replay restored authored start", 0.0, 20*log10(rms(1,0)/rms(0,0)), .15)
                assertEquals("Paused seek applied absolute source and ducking clocks", 0.0, 20*log10(rms(2,1)/rms(0,1)), .15)
                assertEquals("EOS emitted padding", 0, raw[3].size())
            }
        } finally {
            onMain { player.release() }
            synchronized(lock) {
                raw.forEachIndexed { i, data -> File(directory, "phase-$i.pcm").writeBytes(data.toByteArray()) }
                File(directory, "buffers.csv").writeText(csv.toString())
                File(directory, "metrics.json").writeText(org.json.JSONObject().put("seekRequestNs",seekRequestNs).put("resumeRequestNs",resumeRequestNs)
                    .put("firstSeekPcmNs",firstSeekPcmNs).put("seekToPcmMs",if(firstSeekPcmNs > 0) (firstSeekPcmNs-seekRequestNs)/1e6 else org.json.JSONObject.NULL)
                    .put("note","Paused seek includes intentional200ms pause; resume-to-PCM can be derived separately, prebuffer before resume is reported as such")
                    .put("frames", org.json.JSONArray(frames.map { org.json.JSONArray(it.toList()) }))
                    .put("energy", org.json.JSONArray(energy.map { org.json.JSONArray(it.toList()) })).toString(2))
            }
            android.util.Log.i("Stage8Transport", "retained=${directory.absolutePath}")
        }
    }
}
