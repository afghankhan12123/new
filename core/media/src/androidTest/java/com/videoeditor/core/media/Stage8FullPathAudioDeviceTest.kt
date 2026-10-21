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
import com.videoeditor.core.media.export.*
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.model.*
import com.videoeditor.editing.ProjectCreator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Media3 sink PCM and Transformer AAC, independently decoded on Android.
 * Raw captures are retained for an independent FFmpeg verification campaign.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Stage8FullPathAudioDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val windows = listOf(500_000L..999_999L, 2_500_000L..2_999_999L, 4_500_000L..4_999_999L)
    private val directory = File(context.filesDir, "stage8-fullpath-${SystemClock.elapsedRealtimeNanos()}").apply { mkdirs() }
    private val files = mutableMapOf<String, File>()

    private fun fixture(name: String): MediaRef {
        val file = files.getOrPut(name) {
            File(directory, name).also { file ->
                val assets = if (name.startsWith("clip")) context.assets else instrumentation.context.assets
                val path = if (name.startsWith("clip")) name else "stage8/$name"
                assets.open(path).use { input -> file.outputStream().use(input::copyTo) }
            }
        }
        return MediaMetadataExtractor().extract(context, Uri.fromFile(file)).getOrNull()!!
    }

    private fun project(name: String, source: String = "tone440_48k_mono.m4a", transform: (AudioClip) -> AudioClip): Project {
        val video = fixture("clip1_720p_6s.mp4"); val audio = fixture(source)
        val base = ProjectCreator().create(name, listOf(video))
        return base.copy(mediaRefs = listOf(video, audio), timeline = base.timeline.copy(
            videoClips = base.timeline.videoClips.map { it.copy(muted = true) },
            audioClips = listOf(transform(AudioClip("music", audio.id, sourceEndMicros = 6_000_000)))))
    }

    @Test fun allFadeCurvesThroughPreviewAndAac() = runBlocking<Unit> {
        for (curve in FadeCurve.entries) qualify("fade-${curve.name}", project("Fade ${curve.name}") {
            it.copy(fadeInMicros = 2_000_000, fadeOutMicros = 2_000_000, fadeInCurve = curve, fadeOutCurve = curve)
        })
    }

    @Test fun allEqPresetsThroughPreviewAndAac() = runBlocking<Unit> {
        for (preset in EqualizerPreset.entries) qualify("eq-${preset.name}", project("EQ ${preset.name}", "eq_three_tone_48k.m4a") {
            it.copy(equalizer = preset.settings())
        })
        qualify("eq-44k", project("EQ 44.1k", "tone440_44k_mono.m4a") { it.copy(equalizer = EqualizerPreset.Warm.settings()) })
    }

    @Test fun partialPanStereoAndFractionalAutomationThroughPreviewAndAac() = runBlocking<Unit> {
        for (pan in listOf(-.5f, 0f, .5f)) qualify("pan-$pan", project("Pan $pan", "tone440_48k_stereo.m4a") { it.copy(balance = AudioBalance(pan)) })
        qualify("fractional-automation", project("Fractional automation") {
            it.copy(sourceEndMicros = 3_000_000, playbackSpeed = .5f, volumeEnvelope = VolumeEnvelope(listOf(
                VolumePoint("a", 0, 0f), VolumePoint("b", 3_000_000, -12f))))
        })
    }

    @Test fun duckingAndCombinedMultitrackThroughPreviewAndAac() = runBlocking<Unit> {
        val voiceRef = fixture("voice_trigger_880_2s_4s.m4a")
        for (combined in listOf(false, true)) {
            val base = project("Ducking", "music_tone220_48k.m4a") {
                it.copy(balance = AudioBalance(-1f), ducking = DuckingSettings(enabled = true,
                    triggerClipIds = listOf("voice"), strengthDb = 12f, thresholdDb = -30f, attackMillis = 50, releaseMillis = 150),
                    equalizer = if (combined) EqualizerPreset.Warm.settings() else EqualizerSettings(),
                    volumeEnvelope = if (combined) VolumeEnvelope(listOf(VolumePoint("a", 0, -3f, VolumeInterpolation.Hold), VolumePoint("b", 4_000_000, -6f))) else VolumeEnvelope(),
                    fadeInMicros = if (combined) 200_000 else 0,
                    fadeOutMicros = if (combined) 500_000 else 0, fadeOutCurve = FadeCurve.Smooth)
            }
            val project = base.copy(mediaRefs = base.mediaRefs + voiceRef, timeline = base.timeline.copy(audioClips = base.timeline.audioClips +
                AudioClip("voice", voiceRef.id, sourceEndMicros = 6_000_000, balance = AudioBalance(1f))))
            qualify(if (combined) "combined-multitrack" else "ducking", project)
        }
    }

    @Test fun mixedVideoPipPhotoTextStickerTransitionAndAllAudioEffects() = runBlocking<Unit> {
        val base = project("Stage8 full mixed-content", "music_tone220_48k.m4a") {
            it.copy(balance = AudioBalance(-1f), equalizer = EqualizerPreset.Warm.settings(),
                fadeInMicros = 200_000, fadeOutMicros = 500_000, fadeInCurve = FadeCurve.EaseIn, fadeOutCurve = FadeCurve.Smooth,
                volumeEnvelope = VolumeEnvelope(listOf(VolumePoint("a", 0, -3f, VolumeInterpolation.Hold), VolumePoint("b", 4_000_000, -6f))),
                ducking = DuckingSettings(enabled = true, triggerClipIds = listOf("voice"), strengthDb = 12f, thresholdDb = -30f))
        }
        val voice = fixture("voice_trigger_880_2s_4s.m4a")
        val pip = fixture("clip2_480p_5s.mp4")
        val second = fixture("clip3_1080p_4s.mp4")
        val photoFile = File(directory, "photo.png")
        android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            photoFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val photo = MediaMetadataExtractor().extract(context, Uri.fromFile(photoFile)).getOrNull()!!
        val firstClip = base.timeline.videoClips.single().copy(sourceStartMicros = 500_000, sourceEndMicros = 3_500_000)
        val secondClip = firstClip.copy(id = "second", mediaRefId = second.id, timelineStartMicros = 3_000_000)
        val authored = base.copy(mediaRefs = base.mediaRefs + listOf(voice, pip, second, photo), timeline = base.timeline.copy(
            videoClips = listOf(firstClip, secondClip),
            transitions = listOf(Transition("dissolve", firstClip.id, secondClip.id, 500_000)),
            overlayClips = listOf(OverlayClip("pip", pip.id, timelineStartMicros = 1_000_000, timelineDurationMicros = 4_000_000,
                sourceEndMicros = 4_000_000, muted = true, transform = VisualTransform(scale = .3f, offsetX = .5f, offsetY = .5f)),
                OverlayClip("photo", photo.id, timelineDurationMicros = 6_000_000, isPhoto = true,
                    transform = VisualTransform(scale = .2f, offsetX = -.6f, offsetY = -.5f))),
            textOverlays = listOf(TextOverlay("title", "Stage 8 mixed-content", durationMicros = 6_000_000, positionY = .85f)),
            stickers = listOf(StickerClip("star", "star", timelineDurationMicros = 6_000_000, positionX = .8f, positionY = .2f)),
            audioClips = base.timeline.audioClips + AudioClip("voice", voice.id, sourceEndMicros = 6_000_000, balance = AudioBalance(1f))))
        // Exercise the actual serialized model boundary without requiring a temporary analysis cache.
        val json = kotlinx.serialization.json.Json
        val reopened = json.decodeFromString(Project.serializer(), json.encodeToString(Project.serializer(), authored))
        assertEquals(authored, reopened)
        qualify("mixed-720", reopened)
        qualify("mixed-1080", reopened, ExportResolution.RES_1080P)
    }

    @Test fun mixedMonoStereoKeepsChannelIdentityRegardlessOfTrackOrder() = runBlocking<Unit> {
        val stereo = fixture("left_only_48k.m4a")
        val base = project("Mixed mono/stereo", "music_tone220_48k.m4a") { it }
        val monoClip = base.timeline.audioClips.single()
        val stereoClip = AudioClip("stereo", stereo.id, sourceEndMicros = 6_000_000)
        for ((label, clips) in listOf("mono-first" to listOf(monoClip, stereoClip), "stereo-first" to listOf(stereoClip, monoClip))) {
            val folder = qualify(label, base.copy(mediaRefs = base.mediaRefs + stereo, timeline = base.timeline.copy(audioClips = clips)), expectedChannels = 2)
            val re = DoubleArray(2); val im = DoubleArray(2); val count = IntArray(2)
            visitAudioSamples(File(folder, "export.mp4")) { time, channel, sample ->
                if (time in 1_000_000L..1_999_999L) {
                    val angle = 2 * PI * 440 * time / 1_000_000
                    re[channel] += sample * cos(angle); im[channel] += sample * sin(angle); count[channel]++
                }
            }
            val magnitudes = (0..1).map { hypot(re[it],im[it])*2/count[it] }
            assertTrue("Stereo left identity lost $label $magnitudes",magnitudes[0]>2000)
            assertTrue("Stereo left leaked/swapped $label $magnitudes",magnitudes[1]<magnitudes[0]*.01)
        }
    }

    private class Energy(private val windows: List<LongRange>) {
        val squares = Array(windows.size) { DoubleArray(2) }; val counts = Array(windows.size) { IntArray(2) }
        fun add(time: Long, channel: Int, sample: Double) {
            if (channel > 1) return
            windows.forEachIndexed { i, range -> if (time in range) { squares[i][channel] += sample * sample; counts[i][channel]++ } }
        }
        fun rms(window: Int, channel: Int) = sqrt(squares[window][channel] / counts[window][channel].coerceAtLeast(1))
    }

    private suspend fun qualify(name: String, project: Project, resolution: ExportResolution = ExportResolution.RES_720P, expectedChannels: Int? = null): File {
        val folder = File(directory, name).apply { mkdirs() }
        File(folder, "project.json").writeText(kotlinx.serialization.json.Json.encodeToString(Project.serializer(), project))
        val cache = DuckingAnalysisCache(); val builder = CompositionBuilder(cache)
        val analysis = DuckingAnalysisService(context, DuckingAnalyzer(), cache)
        analysis.ensureAnalyzed(project)
        project.timeline.audioClips.filter { it.ducking.enabled }.forEach { assertFalse("analysis failed", analysis.analysisFailed(project, it)) }
        val preview = Energy(windows); val encoded = Energy(windows)
        val lock = Any(); val raw = java.io.ByteArrayOutputStream()
        val records = StringBuilder("presentationUs,initialBytes,consumedBytes,sampleRate,channels,pcmEncoding,bufferBaseUs\n")
        var channelCount = 0; var sampleRate = 0; var encoding = 0
        val sink = object : ForwardingAudioSink(DefaultAudioSink.Builder(context).build()) {
            private var format = Format.Builder().build()
            private var pendingBuffer: ByteBuffer? = null
            private var baseTimeUs = 0L
            override fun configure(inputFormat: Format, bufferSize: Int, outputChannels: IntArray?) {
                format = inputFormat; super.configure(inputFormat, bufferSize, outputChannels)
            }
            override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                val copy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN); val initial = buffer.position()
                val frameBytes = (if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2) * format.channelCount
                if (pendingBuffer !== buffer) {
                    pendingBuffer = buffer
                    baseTimeUs = presentationTimeUs - initial / frameBytes * 1_000_000L / format.sampleRate
                }
                val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                synchronized(lock) {
                    channelCount = format.channelCount; sampleRate = format.sampleRate; encoding = format.pcmEncoding
                    val bytes = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
                    val consumed = buffer.position() - initial
                    val data = ByteArray(consumed); copy.duplicate().apply { limit(initial + consumed) }.get(data); raw.write(data)
                    records.append("$presentationTimeUs,$initial,$consumed,$sampleRate,$channelCount,$encoding,$baseTimeUs\n")
                    repeat(consumed / (bytes * channelCount)) { frame ->
                        val time = baseTimeUs + (initial / (bytes * channelCount) + frame) * 1_000_000L / sampleRate
                        repeat(channelCount) { channel -> preview.add(time, channel,
                            if (bytes == 4) copy.float.toDouble() * 32768 else copy.short.toDouble()) }
                    }
                }
                if (handled) pendingBuffer = null
                return handled
            }
        }
        lateinit var controller: CompositionPreviewController
        instrumentation.runOnMainSync { controller = CompositionPreviewController.Factory(context, builder).create(sink); controller.setProject(project); controller.play() }
        try {
            val deadline = SystemClock.elapsedRealtime() + 25000
            while (controller.state.value.positionMicros < 6_000_000 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertTrue("Preview stalled $name ${controller.state.value}", controller.state.value.positionMicros >= 6_000_000)
        } finally {
            instrumentation.runOnMainSync { controller.release() }
            synchronized(lock) { File(folder, "preview.pcm").writeBytes(raw.toByteArray()); File(folder, "preview-buffers.csv").writeText(records.toString()) }
        }
        if (expectedChannels != null) assertEquals("Preview collapsed mixed mono/stereo",expectedChannels.toInt(),channelCount)
        val previewFrameBytes = (if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2) * channelCount
        assertTrue("Preview emitted tail outside authored window $name", kotlin.math.abs(raw.size().toLong() / previewFrameBytes - sampleRate * 6L) <= 1L)
        val output = File(folder, "export.mp4")
        val result = ExportEngine(context, builder).export(project, ExportRequest(project.id, output,
            resolution, FrameRate.FPS_30, ExportQuality.Standard)) {}
        assertTrue("Export failed $name: $result", result.isSuccess)
        val reader = android.media.MediaMetadataRetriever()
        try {
            reader.setDataSource(output.absolutePath)
            assertEquals(resolution.height, reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt())
            assertEquals(6000.0, reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble(), 100.0)
            for (time in listOf(1_500_000L, 3_000_000L, 4_500_000L)) {
                val frame = reader.getFrameAtTime(time, android.media.MediaMetadataRetriever.OPTION_CLOSEST)!!
                File(folder, "frame-$time.png").outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                var bright = 0
                for (y in 0 until frame.height step 20) for (x in 0 until frame.width step 20) {
                    val color = frame.getPixel(x, y)
                    if (android.graphics.Color.red(color) + android.graphics.Color.green(color) + android.graphics.Color.blue(color) > 120) bright++
                }
                frame.recycle(); assertTrue("Black rendered video $name", bright > 100)
            }
        } finally { reader.release() }
        val extractor = android.media.MediaExtractor()
        val nativeRate: Int
        val audibleStartSample: Long
        try {
            extractor.setDataSource(output.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString("mime")!!.startsWith("audio/") }
            extractor.selectTrack(track)
            val audioFormat = extractor.getTrackFormat(track)
            nativeRate = audioFormat.getInteger("sample-rate")
            val delay = if (audioFormat.containsKey("encoder-delay")) audioFormat.getInteger("encoder-delay") else 0
            val firstPacketSample = (extractor.sampleTime * nativeRate / 1_000_000.0).roundToLong()
            audibleStartSample = firstPacketSample + delay
            assertEquals("Unqualified native gapless origin", 0L, audibleStartSample)
            File(folder, "native-export-format.txt").writeText("$audioFormat\nfirstPacketUs=${extractor.sampleTime} firstPacketSample=$firstPacketSample configuredDelay=$delay audibleStartSample=$audibleStartSample\n")
        } finally { extractor.release() }
        var exportedChannels = 0
        var firstNativeUs = Long.MAX_VALUE; var lastNativeUs = Long.MIN_VALUE; var nativeFrames = 0L
        val nativeRaw = java.io.ByteArrayOutputStream()
        visitAudioSamples(output) { time, channel, sample ->
            exportedChannels = maxOf(exportedChannels, channel + 1)
            if (channel == 0) { firstNativeUs = minOf(firstNativeUs, time); lastNativeUs = maxOf(lastNativeUs, time); nativeFrames++ }
            // MediaCodec prunes encoder delay/padding from data, while individual
            // output PTS retain packet-clock discontinuities at pruned buffers.
            // Anchor the sequential audible frames to original packet + delay,
            // as in the frozen SourceClock contract; never correlate/shift data.
            encoded.add((audibleStartSample + nativeFrames - 1) * 1_000_000L / nativeRate, channel, sample)
            val value = sample.toInt(); nativeRaw.write(value and 255); nativeRaw.write((value shr 8) and 255)
        }
        File(folder, "native-export.pcm").writeBytes(nativeRaw.toByteArray())
        assertEquals("Gapless decoded frame span", nativeRate * 6L, nativeFrames)
        if (exportedChannels == 1 && channelCount == 2) {
            assertTrue("Unexpected stereo collapse", project.timeline.audioClips.all { it.balance.pan == 0f })
            for (window in windows.indices) {
                encoded.squares[window][1] = encoded.squares[window][0]
                encoded.counts[window][1] = encoded.counts[window][0]
            }
        }
        val rows = org.json.JSONArray()
        for (window in windows.indices) for (channel in 0 until channelCount) {
            val a = preview.rms(window, channel); val b = encoded.rms(window, channel)
            rows.put(JSONObject().put("window", window).put("channel", channel).put("previewRms", a).put("exportRms", b)
                .put("previewFrames", preview.counts[window][channel]).put("exportFrames", encoded.counts[window][channel]))
        }
        File(folder, "metrics.json").writeText(JSONObject().put("name", name).put("sampleRate", sampleRate).put("channels", channelCount)
            .put("encoding", encoding).put("nativeFirstUs", firstNativeUs).put("nativeLastUs", lastNativeUs).put("nativeFrames", nativeFrames).put("windows", rows).toString(2))
        android.util.Log.i("Stage8FullPath", "retained=${folder.absolutePath}")
        for (window in windows.indices) for (channel in 0 until channelCount) {
            assertTrue("Missing preview PCM $name window$window ch$channel", preview.counts[window][channel] > 10000)
            assertTrue("Missing exported PCM $name window$window ch$channel", encoded.counts[window][channel] > 10000)
            val a = preview.rms(window, channel); val b = encoded.rms(window, channel)
            if (a > 100) assertEquals("AAC amplitude parity $name window$window ch$channel", 0.0, 20 * log10(b / a), .15)
            else assertTrue("Silent channel leakage $name", b < 100)
        }
        if (name == "ducking") {
            assertTrue(preview.rms(0, 0) > 1000)
            assertTrue("Ducking attack/sustain", preview.rms(1, 0) / preview.rms(0, 0) in .24.. .28)
            assertTrue("Ducking recovery", preview.rms(2, 0) / preview.rms(0, 0) > .99)
        }
        if (expectedChannels != null) assertEquals("Export collapsed mixed mono/stereo",expectedChannels.toInt(),exportedChannels)
        return folder
    }
}
