package com.videoeditor.core.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Independent decoder observation, deliberately outside the application clipping path. */
@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class AacSourceIdentityDeviceTest {
    @Test fun retain_full_source_decoder_samples_and_buffer_clocks() { collectFullSourceSamples() }

    private fun collectFullSourceSamples(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val out = File(instrumentation.targetContext.getExternalFilesDir(null),
            "aac-source-identity/run-${SystemClock.elapsedRealtimeNanos()}")
        check(out.mkdirs())
        val root = InstrumentationRegistry.getArguments().getString("audioIdentityRoot")?.let(::File) ?: out
        if (root == out) for (rate in listOf(44100, 48000)) {
            instrumentation.context.assets.open("audio_sample_identity/$rate.m4a").use { input ->
                File(root, "$rate.m4a").outputStream().use { input.copyTo(it) }
            }
        }
        for (rate in listOf(44100, 48000)) {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            try {
                extractor.setDataSource(File(root, "$rate.m4a").absolutePath)
                val track = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString("mime")!!.startsWith("audio/")
                }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val codec = MediaCodec.createDecoderByType(format.getString("mime")!!)
                decoder = codec
                File(out, "$rate-format.txt").writeText("codec=${codec.name} input=$format\n")
                codec.configure(format, null, null, 0)
                codec.start()
                var inputEnded = false
                var outputEnded = false
                val info = MediaCodec.BufferInfo()
                val deadline = SystemClock.elapsedRealtime() + 30000
                File(out, "$rate.android.pcm").outputStream().use { pcm ->
                    File(out, "$rate-buffers.txt").bufferedWriter().use { log ->
                        while (!outputEnded) {
                            check(SystemClock.elapsedRealtime() < deadline) { "Decoder timeout" }
                            if (!inputEnded) {
                                val index = codec.dequeueInputBuffer(1000)
                                if (index >= 0) {
                                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                                    inputEnded = size < 0
                                    val pts = if (inputEnded) 0 else extractor.sampleTime
                                    log.write("input pts=$pts bytes=$size flags=${extractor.sampleFlags}\n")
                                    codec.queueInputBuffer(index, 0, maxOf(size, 0), pts,
                                        if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                    if (!inputEnded) extractor.advance()
                                }
                            }
                            val index = codec.dequeueOutputBuffer(info, 1000)
                            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                File(out, "$rate-format.txt").appendText("output=${codec.outputFormat}\n")
                            } else if (index >= 0) {
                                log.write("output pts=${info.presentationTimeUs} bytes=${info.size} flags=${info.flags}\n")
                                val buffer = codec.getOutputBuffer(index)!!.duplicate()
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                pcm.write(bytes)
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(index, false)
                            }
                        }
                    }
                }
                assertTrue("No decoded PCM", File(out, "$rate.android.pcm").length() > rate * 2L)
            } finally {
                decoder?.let { runCatching { it.stop() }; it.release() }
                extractor.release()
            }
        }
        android.util.Log.i("AacSourceIdentityTest", "Independent decoder evidence: $out")
        return out
    }
    @Test fun retain_application_clipped_pcm_at_packet_and_sample_boundaries() = collectApplicationClips(false)

    @Test fun verify_source_clock_in_actual_export_graph() {
        com.videoeditor.core.media.audio.SourceClockAacDecoder.enabledForQualification = true
        try { collectApplicationClips(true) } finally {
            com.videoeditor.core.media.audio.SourceClockAacDecoder.enabledForQualification = false
        }
    }

    private fun collectApplicationClips(sourceClock: Boolean) {
        val reference = if (sourceClock) collectFullSourceSamples() else null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val out = File(context.getExternalFilesDir(null),
            "aac-source-clips/run-${SystemClock.elapsedRealtimeNanos()}")
        check(out.mkdirs())
        for (rate in listOf(44100, 48000)) {
            val source = File(out, "$rate.m4a")
            instrumentation.context.assets.open("audio_sample_identity/$rate.m4a").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val ref = com.videoeditor.core.model.MediaRef(id = "source-$rate",
                uri = android.net.Uri.fromFile(source).toString(),
                type = com.videoeditor.core.model.MediaType.Video, displayName = source.name,
                mimeType = "audio/mp4", durationMicros = 6_000_000)
            for (startSample in listOf(0, rate, 50*1024-1, 50*1024, 50*1024+1, rate+47, 6*rate-3073)) {
                // The application model stores microseconds: retain the rational request and
                // its explicit floor conversion, never silently call it a sample-exact API.
                val startUs = startSample * 1_000_000L / rate
                val endUs = minOf(startUs + 2_000_000L, 6_000_000L)
                val prefix = "$rate-$startSample"
                val project = com.videoeditor.editing.ProjectCreator().create("AAC source identity", listOf(ref))
                    .copy(timeline = com.videoeditor.core.model.Timeline(videoClips = listOf(
                        com.videoeditor.core.model.Clip("clip", ref.id, startUs, endUs))))
                File(out, "$prefix-project.json").writeText(kotlinx.serialization.json.Json.encodeToString(
                    com.videoeditor.core.model.Project.serializer(), project))
                File(out, "$prefix-request.txt").writeText("rate=$rate sourceSample=$startSample startUs=$startUs endUs=$endUs\n")
                val capture = object : androidx.media3.common.audio.BaseAudioProcessor() {
                    private val bytes = java.io.ByteArrayOutputStream()
                    override fun onConfigure(format: androidx.media3.common.audio.AudioProcessor.AudioFormat):
                        androidx.media3.common.audio.AudioProcessor.AudioFormat {
                        File(out, "$prefix-pcm-format.txt").writeText(format.toString())
                        return format
                    }
                    override fun queueInput(input: java.nio.ByteBuffer) {
                        val chunk = ByteArray(input.remaining())
                        input.get(chunk)
                        bytes.write(chunk)
                        replaceOutputBuffer(chunk.size).put(chunk).flip()
                    }
                    override fun onQueueEndOfStream() { File(out, "$prefix-primary.pcm").writeBytes(bytes.toByteArray()) }
                }
                val built = com.videoeditor.core.media.composition.CompositionBuilder().build(project)
                val composition = androidx.media3.transformer.Composition.Builder(built.sequences.map { sequence ->
                    androidx.media3.transformer.EditedMediaItemSequence(sequence.editedMediaItems.map { item ->
                        item.buildUpon().setRemoveVideo(true).setEffects(androidx.media3.transformer.Effects(
                            item.effects.audioProcessors + capture, emptyList())).build()
                    })
                }).build()
                val done = java.util.concurrent.CountDownLatch(1)
                var failure: androidx.media3.transformer.ExportException? = null
                var transformer: androidx.media3.transformer.Transformer? = null
                instrumentation.runOnMainSync {
                    fun observe(codec: androidx.media3.transformer.Codec, name: String): androidx.media3.transformer.Codec =
                        object : androidx.media3.transformer.Codec by codec {
                            private var capturedOutput = false
                            override fun queueInputBuffer(input: androidx.media3.decoder.DecoderInputBuffer) {
                                File(out, "$prefix-$name-input.txt").appendText(
                                    "pts=${input.timeUs} bytes=${input.data?.remaining()} eos=${input.isEndOfStream}\n")
                                if (name == "decoder") input.data?.duplicate()?.let { buffer ->
                                    val chunk = ByteArray(buffer.remaining()); buffer.get(chunk)
                                    File(out, "$prefix-decoder-input.raw").appendBytes(chunk)
                                    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(chunk)
                                        .joinToString("") { "%02x".format(it) }
                                    File(out, "$prefix-decoder-packets.txt").appendText(
                                        "pts=${input.timeUs} bytes=${chunk.size} sha256=$hash eos=${input.isEndOfStream}\n")
                                }
                                if (name == "encoder") input.data?.duplicate()?.let { buffer ->
                                    val chunk = ByteArray(buffer.remaining())
                                    buffer.get(chunk)
                                    File(out, "$prefix-mixer.pcm").appendBytes(chunk)
                                }
                                codec.queueInputBuffer(input)
                            }
                            override fun getOutputBuffer(): java.nio.ByteBuffer? = codec.outputBuffer?.also { buffer ->
                                if (!capturedOutput) {
                                    val copy = buffer.duplicate()
                                    val bytes = ByteArray(copy.remaining())
                                    copy.get(bytes)
                                    File(out, "$prefix-$name-output.raw").appendBytes(bytes)
                                    val info = codec.outputBufferInfo
                                    File(out, "$prefix-$name-output.txt").appendText(
                                        "pts=${info?.presentationTimeUs} bytes=${info?.size} flags=${info?.flags}\n")
                                    capturedOutput = true
                                }
                            }
                            override fun releaseOutputBuffer(render: Boolean) {
                                codec.releaseOutputBuffer(render)
                                capturedOutput = false
                            }
                            override fun getOutputFormat(): androidx.media3.common.Format? = codec.outputFormat?.also {
                                File(out, "$prefix-$name-format.txt").writeText(
                                    "codec=${codec.name} delay=${it.encoderDelay} padding=${it.encoderPadding} format=$it")
                            }
                        }
                    val decoders = androidx.media3.transformer.DefaultDecoderFactory.Builder(context).build()
                    val observedDecoders = object : androidx.media3.transformer.Codec.DecoderFactory by decoders {
                        override fun createForAudioDecoding(format: androidx.media3.common.Format): androidx.media3.transformer.Codec {
                            File(out, "$prefix-decoder-input-format.txt").writeText(format.toString() +
                                " delay=${format.encoderDelay} padding=${format.encoderPadding}")
                            return observe(decoders.createForAudioDecoding(format), "decoder")
                        }
                    }
                    val encoders = androidx.media3.transformer.DefaultEncoderFactory.Builder(context).build()
                    val observedEncoders = object : androidx.media3.transformer.Codec.EncoderFactory by encoders {
                        override fun createForAudioEncoding(format: androidx.media3.common.Format) =
                            observe(encoders.createForAudioEncoding(format), "encoder")
                    }
                    transformer = androidx.media3.transformer.Transformer.Builder(context)
                        .setAudioMimeType("audio/mp4a-latm")
                        .setEncoderFactory(observedEncoders)
                        .setAssetLoaderFactory(if (sourceClock)
                            androidx.media3.transformer.ReorderAwareAssetLoaderFactory(context, observedDecoders)
                            else androidx.media3.transformer.DefaultAssetLoaderFactory(
                            context, observedDecoders, androidx.media3.common.util.Clock.DEFAULT))
                        .addListener(object : androidx.media3.transformer.Transformer.Listener {
                            override fun onCompleted(c: androidx.media3.transformer.Composition,
                                r: androidx.media3.transformer.ExportResult) { done.countDown() }
                            override fun onError(c: androidx.media3.transformer.Composition,
                                r: androidx.media3.transformer.ExportResult,
                                e: androidx.media3.transformer.ExportException) { android.util.Log.e("AacSourceIdentityTest", "Export failure $prefix", e); failure = e; done.countDown() }
                        }).build()
                    transformer!!.start(composition, File(out, "$prefix-export.m4a").absolutePath)
                }
                try {
                    check(done.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "Export timed out: $prefix" }
                    check(failure == null) { "Export failed: $prefix $failure" }
                    assertTrue("No primary PCM: $prefix", File(out, "$prefix-primary.pcm").length() > 0)
                    if (reference != null) {
                        val full = File(reference,"$rate.android.pcm").readBytes()
                        val from = ((startUs*rate+999999)/1000000).toInt()
                        val until = ((endUs*rate+999999)/1000000).toInt()
                        val primary = File(out,"$prefix-primary.pcm").readBytes()
                        org.junit.Assert.assertArrayEquals("$prefix complete native window",full.copyOfRange(from*2,until*2),primary)
                        org.junit.Assert.assertArrayEquals("$prefix graph sample identity",primary,File(out,"$prefix-mixer.pcm").readBytes())
                    }
                } finally { instrumentation.runOnMainSync { transformer?.cancel() } }
            }
        }
        android.util.Log.i("AacSourceIdentityTest", "Application clipping evidence: $out")
    }

    @Test fun verify_source_clock_export_engine_mp4_both_resolutions() = collectEngineMp4(false)
    @Test fun verify_measured_output_clock_actual_mp4_both_resolutions() {
        com.videoeditor.core.media.audio.AacEncoderPrimingProbe.enabledForQualification = true
        try { collectEngineMp4(true) } finally {
            com.videoeditor.core.media.audio.AacEncoderPrimingProbe.enabledForQualification = false
        }
    }

    @Test fun verify_measured_output_boundary_matrix_both_resolutions() {
        com.videoeditor.core.media.audio.AacEncoderPrimingProbe.enabledForQualification = true
        try { collectEngineMp4(true,true) } finally {
            com.videoeditor.core.media.audio.AacEncoderPrimingProbe.enabledForQualification = false
        }
    }

    private fun collectEngineMp4(measuredOutput: Boolean, boundaries: Boolean = false) {
        val reference = if (measuredOutput) collectFullSourceSamples() else null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val out = File(context.getExternalFilesDir(null), "aac-runtime-mp4${if (measuredOutput) "-measured" else ""}${if (boundaries) "-matrix" else ""}/run-${SystemClock.elapsedRealtimeNanos()}")
        check(out.mkdirs())
        com.videoeditor.core.media.audio.SourceClockAacDecoder.enabledForQualification = true
        try {
            for (rate in listOf(44100, 48000)) {
                val source = File(context.getExternalFilesDir(null), "aac-runtime-fixtures/$rate.mp4")
                copyRuntimeVideoFixture(rate, source)
                val ref = com.videoeditor.core.model.MediaRef(id = "source-$rate",
                    uri = android.net.Uri.fromFile(source).toString(), type = com.videoeditor.core.model.MediaType.Video,
                    displayName = source.name, mimeType = "video/mp4", durationMicros = 6_000_000,
                    width = 1280, height = 720)
                for (startSample in if (boundaries) listOf(0,rate,rate+47,51199,51200,51201,6*rate-3073) else listOf(rate)) {
                val startUs = startSample*1_000_000L/rate
                val endUs = if (boundaries) minOf(startUs+2_000_000,6_000_000) else 6_000_000
                val prefix = if (boundaries) "$rate-$startSample" else "$rate"
                val project = com.videoeditor.editing.ProjectCreator().create("AAC actual MP4", listOf(ref))
                    .copy(timeline = com.videoeditor.core.model.Timeline(videoClips = listOf(
                        com.videoeditor.core.model.Clip("clip", ref.id, startUs, endUs))))
                File(out, "$prefix-project.json").writeText(kotlinx.serialization.json.Json.encodeToString(
                    com.videoeditor.core.model.Project.serializer(), project))
                for (resolution in com.videoeditor.core.model.ExportResolution.entries) {
                    val output = File(out, "$prefix-${resolution.height}.mp4")
                    val result = kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeout(90_000) { com.videoeditor.core.media.export.ExportEngine(context,
                            com.videoeditor.core.media.composition.CompositionBuilder()).export(project,
                            com.videoeditor.core.media.export.ExportRequest(project.id, output, resolution,
                                com.videoeditor.core.model.FrameRate.FPS_30,
                                com.videoeditor.core.model.ExportQuality.High)) {} }
                    }
                    check(result.isSuccess) { "Actual ExportEngine failed: $result" }
                    check(output.length() > 0)
                    if (reference != null) {
                        val decoded = decodeNativeOutput(output,File(out,"$prefix-${resolution.height}.android.pcm"))
                        val from=((startUs*rate+999999)/1000000).toInt()
                        val until=((endUs*rate+999999)/1000000).toInt()
                        check(decoded.size == (until-from)*2) { "Actual output PCM duration ${decoded.size/2}/${until-from}" }
                        verifyOutputEvents(rate,File(reference,"$rate.android.pcm").readBytes(),decoded,
                            File(out,"$prefix-${resolution.height}-events.txt"),from,until)
                    }
                }
            }
                }
        } finally { com.videoeditor.core.media.audio.SourceClockAacDecoder.enabledForQualification = false }
        android.util.Log.i("AacSourceIdentityTest", "Actual MP4 evidence: $out")
    }

    @Test fun verify_source_clock_actual_preview_play_seek_and_replay() = collectPreviewSeeks(false)
    @Test fun verify_source_clock_preview_multiple_items_and_recreation() = collectPreviewSeeks(true)
    @Test fun verify_source_clock_photo_composed_paused_seek_then_play() = collectPreviewSeeks(false, photoLayer = true)
    @Test fun verify_source_clock_multi_item_photo_recreation_and_paused_seek() = collectPreviewSeeks(true, photoLayer = true)

    @Test fun characterize_existing_preview_seek_latency_same_project() = collectPreviewSeeks(false,false)

    private fun collectPreviewSeeks(multipleItems: Boolean, corrected: Boolean = true, photoLayer: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val out = File(context.getExternalFilesDir(null), "aac-runtime-preview${if (corrected) "" else "-baseline"}/run-${SystemClock.elapsedRealtimeNanos()}")
        check(out.mkdirs())
        com.videoeditor.core.media.audio.SourceClockAacDecoder.preparationObserver = java.util.function.Consumer { sample ->
            File(out,"source-preparation.txt").appendText(sample.toString()+"\n")
        }
        com.videoeditor.core.media.audio.SourceClockAacDecoder.enabledForQualification = corrected
        com.videoeditor.core.media.audio.SourceClockAacDecoder.legacyForCharacterization = !corrected
        try {
            for (rate in listOf(44100, 48000)) {
                val source = File(context.getExternalFilesDir(null), "aac-runtime-fixtures/$rate.mp4")
                copyRuntimeVideoFixture(rate, source)
                val ref = com.videoeditor.core.model.MediaRef(id = "source-$rate",
                    uri = android.net.Uri.fromFile(source).toString(), type = com.videoeditor.core.model.MediaType.Video,
                    displayName = source.name, mimeType = "video/mp4", durationMicros = 6_000_000,
                    width = 1280, height = 720)
                var project = com.videoeditor.editing.ProjectCreator().create("AAC actual preview", listOf(ref))
                    .copy(timeline = com.videoeditor.core.model.Timeline(videoClips = listOf(
                        com.videoeditor.core.model.Clip("clip", ref.id, 1_000_000, if (multipleItems) 3_000_000 else 6_000_000)) +
                        if (multipleItems) listOf(com.videoeditor.core.model.Clip("second",ref.id,4_000_000,6_000_000,timelineStartMicros=2_000_000)) else emptyList()))
                if (photoLayer) {
                    val photoDuration = if (multipleItems) 4_000_000L else 5_000_000L
                    val photo = File(out, "photo-$rate.png")
                    android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888).let { bitmap ->
                        bitmap.eraseColor(android.graphics.Color.CYAN)
                        photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    val photoRef = ref.copy(id = "photo-$rate", uri = android.net.Uri.fromFile(photo).toString(),
                        mimeType = "image/png", type = com.videoeditor.core.model.MediaType.Photo,
                        displayName = photo.name, width = 120, height = 120, hasAudio = false)
                    project = project.copy(mediaRefs = project.mediaRefs + photoRef,
                        timeline = project.timeline.copy(overlayClips = listOf(com.videoeditor.core.model.OverlayClip(
                            "photo", photoRef.id, sourceEndMicros = photoDuration, timelineDurationMicros = photoDuration,
                            isPhoto = true, muted = true, transform = com.videoeditor.core.model.VisualTransform(scale = .3f)))))
                }
                val thread = android.os.HandlerThread("aac-preview-frames").apply { start() }
                val reader = android.media.ImageReader.newInstance(1280,720,android.graphics.PixelFormat.RGBA_8888,2)
                val frames = java.util.concurrent.atomic.AtomicInteger()
                reader.setOnImageAvailableListener({ images -> images.acquireLatestImage()?.let { frames.incrementAndGet(); it.close() } },
                    android.os.Handler(thread.looper))
                lateinit var controller: com.videoeditor.core.media.preview.CompositionPreviewController
                var phase = "initial"
                val lock = Any()
                com.videoeditor.core.media.audio.SourceClockAacDecoder.previewPcmObserver = java.util.function.BiConsumer { pts, buffer ->
                    synchronized(lock) {
                        val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                        File(out, "$rate-$phase.pcm").appendBytes(bytes)
                        File(out, "$rate-$phase-buffers.txt").appendText("pts=$pts bytes=${bytes.size}\n")
                    }
                }
                fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
                fun await(label: String, predicate: () -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime()+15000
                    while (!predicate() && controller.state.value.error == null && SystemClock.elapsedRealtime()<deadline)
                        Thread.sleep(20)
                    check(controller.state.value.error == null && predicate()) { "$label: ${controller.state.value}" }
                }
                try {
                    main {
                        controller = com.videoeditor.core.media.preview.CompositionPreviewController.Factory(context,
                            com.videoeditor.core.media.composition.CompositionBuilder()).create()
                        controller.attachHeadlessSurface(reader.surface, androidx.media3.common.util.Size(1280,720))
                        controller.setProject(project)
                    }
                    await("initial") { frames.get()>0 && !controller.state.value.isBuffering }
                    main { controller.play() }
                    await("continuous playback") { controller.state.value.actualPositionMicros > 800_000 }
                    main { controller.pause() }
                    val duration = if (multipleItems) 4_000_000L else 5_000_000L
                    for ((index, seek) in listOf(2_000_000L, 100_000L, 3_500_000L, 300_000L, duration-70_000L).withIndex()) {
                        val before=frames.get()
                        synchronized(lock) { phase="seek-$index-$seek" }
                        val seekStarted=SystemClock.elapsedRealtimeNanos()
                        main { controller.seekTo(seek) }
                        await("seek $seek") { frames.get()>before && !controller.state.value.isBuffering &&
                            controller.renderedPositionMicros in seek..seek+33_334 }
                        File(out,"$rate-seek-latency.txt").appendText("seek=$seek nanos=${SystemClock.elapsedRealtimeNanos()-seekStarted}\n")
                        main { controller.play() }
                        if (seek<duration-100_000) await("play after seek $seek") { controller.state.value.actualPositionMicros > seek+60_000 }
                        else await("final EOS") { !controller.state.value.playRequested && controller.state.value.actualPositionMicros>=duration-40_000 }
                        main { controller.pause() }
                    }
                    synchronized(lock) { phase="replay" }
                    main { controller.seekTo(0); controller.play() }
                    await("replay") { controller.state.value.actualPositionMicros in 100_000L..800_000L }
                    if (multipleItems) {
                        main { controller.release() }
                        synchronized(lock) { phase="recreated" }
                        val before = frames.get()
                        main {
                            controller = com.videoeditor.core.media.preview.CompositionPreviewController.Factory(context,
                                com.videoeditor.core.media.composition.CompositionBuilder()).create()
                            controller.attachHeadlessSurface(reader.surface, androidx.media3.common.util.Size(1280,720))
                            val json = kotlinx.serialization.json.Json
                            val reopened = json.decodeFromString(com.videoeditor.core.model.Project.serializer(),
                                json.encodeToString(com.videoeditor.core.model.Project.serializer(),project))
                            controller.setProject(reopened);controller.play()
                        }
                        await("recreated/reopened playback") { frames.get()>before && controller.state.value.actualPositionMicros>100_000 }
                    }
                    File(out,"$rate-state.txt").writeText(controller.state.value.toString()+" frames="+frames.get())
                } finally {
                    main { controller.release() }
                    com.videoeditor.core.media.audio.SourceClockAacDecoder.previewPcmObserver = null
                    reader.close();thread.quitSafely();thread.join()
                }
            }
        } finally {
            com.videoeditor.core.media.audio.SourceClockAacDecoder.enabledForQualification = false
            com.videoeditor.core.media.audio.SourceClockAacDecoder.previewPcmObserver = null
            com.videoeditor.core.media.audio.SourceClockAacDecoder.preparationObserver = null
            com.videoeditor.core.media.audio.SourceClockAacDecoder.legacyForCharacterization = false
        }
        android.util.Log.i("AacSourceIdentityTest", "Actual preview evidence: $out")
    }

    @Test fun verify_source_clock_preview_complete_boundary_matrix() {
        val reference = collectFullSourceSamples()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val out = File(context.getExternalFilesDir(null), "aac-runtime-preview-matrix/run-${SystemClock.elapsedRealtimeNanos()}")
        check(out.mkdirs())
        com.videoeditor.core.media.audio.SourceClockAacDecoder.enabledForQualification = true
        try {
            for (rate in listOf(44100,48000)) for (startSample in listOf(0,rate,rate+47,51199,51200,51201,6*rate-3073)) {
                val source = File(context.getExternalFilesDir(null),"aac-runtime-fixtures/$rate.mp4")
                copyRuntimeVideoFixture(rate,source)
                val ref = com.videoeditor.core.model.MediaRef(id="source-$rate", uri=android.net.Uri.fromFile(source).toString(),
                    type=com.videoeditor.core.model.MediaType.Video, displayName=source.name, mimeType="video/mp4",
                    durationMicros=6_000_000,width=1280,height=720)
                val startUs=startSample*1_000_000L/rate
                val endUs=minOf(startUs+2_000_000,6_000_000)
                val project=com.videoeditor.editing.ProjectCreator().create("AAC preview matrix",listOf(ref))
                    .copy(timeline=com.videoeditor.core.model.Timeline(videoClips=listOf(
                        com.videoeditor.core.model.Clip("clip",ref.id,startUs,endUs))))
                val prefix="$rate-$startSample"
                val bytes=java.io.ByteArrayOutputStream()
                val stamps=StringBuilder()
                com.videoeditor.core.media.audio.SourceClockAacDecoder.previewPcmObserver=java.util.function.BiConsumer { pts, buffer ->
                    val chunk=ByteArray(buffer.remaining());buffer.get(chunk);bytes.write(chunk)
                    stamps.append("pts=$pts bytes=${chunk.size}\n")
                }
                val thread=android.os.HandlerThread("aac-preview-matrix").apply { start() }
                val reader=android.media.ImageReader.newInstance(1280,720,android.graphics.PixelFormat.RGBA_8888,2)
                val frames=java.util.concurrent.atomic.AtomicInteger()
                reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.let { frames.incrementAndGet();it.close() } },android.os.Handler(thread.looper))
                lateinit var controller:com.videoeditor.core.media.preview.CompositionPreviewController
                try {
                    instrumentation.runOnMainSync {
                        controller=com.videoeditor.core.media.preview.CompositionPreviewController.Factory(context,
                            com.videoeditor.core.media.composition.CompositionBuilder()).create()
                        controller.attachHeadlessSurface(reader.surface,androidx.media3.common.util.Size(1280,720))
                        controller.setProject(project);controller.play()
                    }
                    val deadline=SystemClock.elapsedRealtime()+15000
                    while (SystemClock.elapsedRealtime()<deadline && controller.state.value.error==null &&
                        (controller.state.value.playRequested || frames.get()==0)) Thread.sleep(20)
                    check(controller.state.value.error==null && !controller.state.value.playRequested && frames.get()>0) {
                        "$prefix preview EOS ${controller.state.value}"
                    }
                } finally {
                    instrumentation.runOnMainSync { controller.release() }
                    com.videoeditor.core.media.audio.SourceClockAacDecoder.previewPcmObserver=null
                    reader.close();thread.quitSafely();thread.join()
                    File(out,"$prefix.pcm").writeBytes(bytes.toByteArray())
                    File(out,"$prefix-buffers.txt").writeText(stamps.toString())
                }
                val expected=(endUs*rate+999999)/1000000-(startUs*rate+999999)/1000000
                check(bytes.size().toLong()==expected*2L) { "$prefix PCM count ${bytes.size()/2}/$expected" }
                val full=File(reference,"$rate.android.pcm").readBytes()
                val from=((startUs*rate+999999)/1000000).toInt()
                val until=((endUs*rate+999999)/1000000).toInt()
                org.junit.Assert.assertArrayEquals("$prefix complete preview sample identity",
                    full.copyOfRange(from*2,until*2),bytes.toByteArray())
            }
        } finally {
            com.videoeditor.core.media.audio.SourceClockAacDecoder.enabledForQualification=false
            com.videoeditor.core.media.audio.SourceClockAacDecoder.previewPcmObserver=null
        }
        android.util.Log.i("AacSourceIdentityTest","Preview boundary evidence: $out")
    }

    private fun decodeNativeOutput(file: File, destination: File): ByteArray {
        val extractor=MediaExtractor();var codec:MediaCodec?=null
        val pcm=java.io.ByteArrayOutputStream()
        try {
            extractor.setDataSource(file.absolutePath)
            val track=(0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString("mime")!!.startsWith("audio/") }
            extractor.selectTrack(track)
            val format=extractor.getTrackFormat(track)
            File(destination.parentFile,destination.name+"-format.txt").writeText(format.toString())
            val active=MediaCodec.createDecoderByType(format.getString("mime")!!);codec=active
            active.configure(format,null,null,0);active.start()
            var inputEnded=false;var outputEnded=false
            val info=MediaCodec.BufferInfo();val deadline=SystemClock.elapsedRealtime()+15000
            while(!outputEnded) {
                check(SystemClock.elapsedRealtime()<deadline) { "Actual MP4 native decode timeout" }
                if(!inputEnded) {
                    val index=active.dequeueInputBuffer(1000)
                    if(index>=0) {
                        val count=extractor.readSampleData(active.getInputBuffer(index)!!,0)
                        inputEnded=count<0
                        active.queueInputBuffer(index,0,maxOf(0,count),if(inputEnded)0 else extractor.sampleTime,
                            if(inputEnded)MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if(!inputEnded)extractor.advance()
                    }
                }
                val index=active.dequeueOutputBuffer(info,1000)
                if(index>=0) {
                    val b=active.getOutputBuffer(index)!!.duplicate();b.position(info.offset);b.limit(info.offset+info.size)
                    val bytes=ByteArray(info.size);b.get(bytes);pcm.write(bytes)
                    active.releaseOutputBuffer(index,false)
                    outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                }
            }
        } finally {
            codec?.let { runCatching { it.stop() };it.release() };extractor.release()
            destination.writeBytes(pcm.toByteArray())
        }
        return pcm.toByteArray()
    }

    private fun verifyOutputEvents(rate:Int, full:ByteArray, encoded:ByteArray, report:File, from:Int, until:Int) {
        fun samples(raw:ByteArray):ShortArray {
            val b=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return ShortArray(raw.size/2) { b.short }
        }
        val source=samples(full);val actual=samples(encoded)
        val manifest=org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("audio_sample_identity/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("rates")
        val record=(0 until manifest.length()).map { manifest.getJSONObject(it) }.single { it.getInt("sample_rate")==rate }
        val events=record.getJSONArray("events")
        for(index in 0 until events.length()) {
            val event=events.getJSONObject(index);val start=event.getInt("source_start_sample");val length=event.getInt("length_samples")
            if(start<from || start+length>until)continue
            val expected=start-from
            var best=-1.0;var position=-1
            for(at in maxOf(0,expected-4096)..minOf(actual.size-length,expected+4096)) {
                var xy=0.0;var xx=0.0;var yy=0.0
                for(i in 0 until length) {
                    val x=source[start+i].toDouble();val y=actual[at+i].toDouble();xy+=x*y;xx+=x*x;yy+=y*y
                }
                val score=if(yy==0.0)-1.0 else xy/kotlin.math.sqrt(xx*yy)
                if(score>best) { best=score;position=at }
            }
            report.appendText("event=${event.getInt("id")} expected=$expected actual=$position correlation=$best\n")
            check(best>=.75 && position==expected) { "Output event ${event.getInt("id")}: $position/$expected correlation=$best" }
        }
    }

    @Test fun qualified_long_source_seeks_remain_bounded_and_replay_zero_exactly() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val out=File(context.getExternalFilesDir(null),"aac-long-source/run-${SystemClock.elapsedRealtimeNanos()}").apply { check(mkdirs()) }
        val source=File(out,"48000-long.m4a")
        instrumentation.context.assets.open("audio_sample_identity/48000-long.m4a").use { input -> source.outputStream().use(input::copyTo) }
        val extractor=MediaExtractor()
        val csd=try {
            extractor.setDataSource(source.absolutePath)
            val bytes=extractor.getTrackFormat(0).getByteBuffer("csd-0")!!.duplicate()
            ByteArray(bytes.remaining()).also { bytes.get(it) }
        } finally { extractor.release() }
        val format=androidx.media3.common.Format.Builder().setSampleMimeType("audio/mp4a-latm")
            .setSampleRate(48000).setChannelCount(1).setInitializationData(listOf(csd)).build()
        var reusable:com.videoeditor.core.media.audio.SourceClockAacDecoder.ReusableCodec?=null
        var active:com.videoeditor.core.media.audio.SourceClockAacDecoder?=null
        var zeroReference:ByteArray?=null
        com.videoeditor.core.media.audio.SourceClockAacDecoder.packetObserver=java.util.function.Consumer { packet ->
            File(out,"packets.txt").appendText(packet.toString()+"\n")
        }
        try {
            for((index,start) in listOf(0L,598_000_000L,2_000_000L,0L,599_500_000L).withIndex()) {
                val item=androidx.media3.transformer.EditedMediaItem.Builder(androidx.media3.common.MediaItem.Builder()
                    .setUri(android.net.Uri.fromFile(source)).setClippingConfiguration(androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(start).setEndPositionUs(start+500_000).build()).build())
                    .setDurationUs(600_000_000).setRemoveVideo(true).build()
                val began=SystemClock.elapsedRealtimeNanos()
                val codec=checkNotNull(com.videoeditor.core.media.audio.SourceClockAacDecoder.create(context,item,format,reusable))
                    as com.videoeditor.core.media.audio.SourceClockAacDecoder
                active=codec;reusable=null
                val ready=SystemClock.elapsedRealtimeNanos()
                val pcm=java.io.ByteArrayOutputStream()
                val deadline=SystemClock.elapsedRealtime()+15000
                while(!codec.isEnded()) {
                    check(SystemClock.elapsedRealtime()<deadline)
                    codec.getOutputBuffer()?.let { buffer ->
                        val bytes=ByteArray(buffer.remaining());buffer.get(bytes);pcm.write(bytes);codec.releaseOutputBuffer(false)
                    } ?: Thread.sleep(1)
                }
                val raw=pcm.toByteArray()
                File(out,"$index-$start.pcm").writeBytes(raw)
                check(raw.size==48000) { "Long-source audible sample count ${raw.size/2}" }
                check(codec.queuedPacketCount<=32) { "Long source decoded ${codec.queuedPacketCount} packets for half a second" }
                File(out,"metrics.txt").appendText("start=$start preparationNs=${ready-began} totalNs=${SystemClock.elapsedRealtimeNanos()-began} packets=${codec.queuedPacketCount} samples=${raw.size/2}\n")
                if(start==0L) {
                    if(zeroReference==null)zeroReference=raw else org.junit.Assert.assertArrayEquals("Flushed zero-start replay",zeroReference,raw)
                }
                reusable=codec.detachReusableCodec();active=null
            }
        } finally {
            active?.release();reusable?.release()
            com.videoeditor.core.media.audio.SourceClockAacDecoder.packetObserver=null
        }
    }

    private fun copyRuntimeVideoFixture(rate: Int, source: File) {
        if (source.exists()) return
        check(source.parentFile!!.isDirectory || source.parentFile!!.mkdirs())
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio_sample_identity/$rate-video.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
    }

    /** Test-only causal matrix: packet selection and configured delay vary independently.
     * No production renderer, clip bound, graph or codec is changed.
     */
    @Test fun retain_preroll_delay_causal_matrix() = collectPrerollMatrix(false)

    @Test fun verify_experimental_source_clock_adapter() = collectPrerollMatrix(true)

    private fun collectPrerollMatrix(adapterGate: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val out = File(instrumentation.targetContext.getExternalFilesDir(null),
            "aac-preroll-matrix/run-${SystemClock.elapsedRealtimeNanos()}")
        check(out.mkdirs())
        data class Packet(val pts: Long, val flags: Int, val data: ByteArray)
        for (rate in listOf(44100, 48000)) {
            val source = File(out, "$rate.m4a")
            instrumentation.context.assets.open("audio_sample_identity/$rate.m4a").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val extractor = MediaExtractor()
            val packets = mutableListOf<Packet>()
            val format: android.media.MediaFormat
            try {
                extractor.setDataSource(source.absolutePath)
                extractor.selectTrack(0)
                format = extractor.getTrackFormat(0)
                val buffer = java.nio.ByteBuffer.allocate(65536)
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val bytes = ByteArray(size)
                    buffer.position(0); buffer.get(bytes)
                    packets += Packet(extractor.sampleTime, extractor.sampleFlags, bytes)
                    extractor.advance()
                }
            } finally { extractor.release() }
            File(out, "$rate-packets.txt").writeText(packets.mapIndexed { i, p ->
                "packet=$i pts=${p.pts} flags=${p.flags} bytes=${p.data.size}"
            }.joinToString("\n"))
            val originalDelay = format.getInteger(android.media.MediaFormat.KEY_ENCODER_DELAY)
            File(out, "$rate-original-format.txt").writeText(format.toString())
            for (start in listOf(0, rate, 51199, 51200, 51201, rate+47, 6*rate-3073)) {
                val startUs = start*1_000_000L/rate
                val next = packets.indexOfFirst { it.pts >= startUs }
                for (mode in if (adapterGate) listOf("full", "preroll2") else listOf("full", "next", "preroll1", "preroll2")) {
                    val first = when (mode) {
                        "full" -> 0
                        "next" -> next
                        "preroll1" -> maxOf(0, next-1)
                        else -> if (adapterGate) {
                            com.videoeditor.core.media.audio.ExperimentalAacPreroll(2, 1024).firstPacket(
                                packets.map { (it.pts * rate + if (it.pts >= 0) 500_000 else -500_000) / 1_000_000 },
                                start.toLong())
                        } else maxOf(0, next-2)
                    }
                    for (delay in if (adapterGate) listOf(if (mode == "full") originalDelay else 0) else listOf(0, originalDelay)) {
                        val prefix = "$rate-$start-$mode-delay$delay"
                        val endUs = minOf(startUs + 2_000_000, 6_000_000)
                        val adapter = if (adapterGate && mode == "preroll2") {
                            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                                .joinToString("") { "%02x".format(it) }
                            val provenance = com.videoeditor.core.media.audio.AacSourceProvenance(
                                hash, 0, rate, 1, originalDelay,
                                if (format.containsKey("encoder-padding")) format.getInteger("encoder-padding") else null,
                                -originalDelay.toLong())
                            val packetSample = (packets[first].pts * rate +
                                if (packets[first].pts >= 0) 500_000 else -500_000) / 1_000_000
                            com.videoeditor.core.media.audio.AacSourceClock.experimental(
                                provenance, startUs, endUs, packetSample, delay)
                        } else null
                        val corrected = java.io.ByteArrayOutputStream()
                        format.setInteger(android.media.MediaFormat.KEY_ENCODER_DELAY, delay)
                        val codec = MediaCodec.createDecoderByType(format.getString("mime")!!)
                        try {
                            codec.configure(format, null, null, 0); codec.start()
                            File(out, "$prefix-config.txt").writeText(
                                "rate=$rate requestedSample=$start startUs=$startUs endUs=${minOf(startUs+2_000_000,6_000_000)} firstPacket=$first nextPacket=$next originalDelay=$originalDelay configuredDelay=$delay codec=${codec.name}\n")
                            var packetIndex = first
                            var inputEnded = false
                            var outputEnded = false
                            var sampleIndex = 0L
                            val info = MediaCodec.BufferInfo()
                            val deadline = SystemClock.elapsedRealtime()+30000
                            File(out, "$prefix.pcm").outputStream().use { pcm ->
                                File(out, "$prefix-buffers.txt").bufferedWriter().use { log ->
                                    while (!outputEnded) {
                                        check(SystemClock.elapsedRealtime() < deadline)
                                        if (!inputEnded) {
                                            val index = codec.dequeueInputBuffer(1000)
                                            if (index >= 0) {
                                                inputEnded = packetIndex == packets.size
                                                val packet = packets.getOrNull(packetIndex)
                                                if (packet != null) codec.getInputBuffer(index)!!.put(packet.data)
                                                log.write("input packet=$packetIndex pts=${packet?.pts} bytes=${packet?.data?.size} flags=${packet?.flags} eos=$inputEnded\n")
                                                codec.queueInputBuffer(index,0,packet?.data?.size ?: 0,packet?.pts ?: 6_100_000,
                                                    if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                                packetIndex++
                                            }
                                        }
                                        val index = codec.dequeueOutputBuffer(info,1000)
                                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                            File(out, "$prefix-output-format.txt").writeText(codec.outputFormat.toString())
                                        } else if (index >= 0) {
                                            log.write("output sample=$sampleIndex pts=${info.presentationTimeUs} bytes=${info.size} flags=${info.flags}\n")
                                            val buffer = codec.getOutputBuffer(index)!!.duplicate()
                                            buffer.position(info.offset); buffer.limit(info.offset+info.size)
                                            adapter?.trim(buffer)?.let { slice ->
                                                val selected = ByteArray(slice.pcm.remaining())
                                                slice.pcm.get(selected); corrected.write(selected)
                                            }
                                            val bytes = ByteArray(info.size); buffer.get(bytes); pcm.write(bytes)
                                            sampleIndex += info.size/2
                                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                            codec.releaseOutputBuffer(index,false)
                                        }
                                    }
                                }
                            }
                            assertTrue("No PCM $prefix", sampleIndex > 0)
                            adapter?.let {
                                it.finish()
                                val actual = corrected.toByteArray()
                                File(out, "$prefix-corrected.pcm").writeBytes(actual)
                                val full = File(out, "$rate-$start-full-delay$originalDelay.pcm").readBytes()
                                val expected = full.copyOfRange((it.startSample * 2).toInt(), (it.endSample * 2).toInt())
                                org.junit.Assert.assertArrayEquals(prefix, expected, actual)
                            }
                        } finally { adapter?.close(); runCatching { codec.stop() }; codec.release() }
                    }
                }
            }
        }
        android.util.Log.i("AacSourceIdentityTest", "Preroll matrix evidence: $out")
    }

}
