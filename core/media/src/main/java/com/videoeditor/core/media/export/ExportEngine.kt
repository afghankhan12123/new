package com.videoeditor.core.media.export

import android.content.Context
import android.os.Looper
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.videoeditor.core.common.result.Result
import com.videoeditor.core.media.MediaError
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.model.Project
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Renders a [Project] to an MP4 file using Media3 Transformer.
 *
 * Progress is read from the engine's real [Transformer.getProgress] on a polling
 * loop that runs concurrently with the render — it is never simulated.
 * Cancellation cancels the underlying Transformer and deletes the incomplete
 * output file.
 */
@Singleton
class ExportEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val compositionBuilder: CompositionBuilder,
    private val duckingAnalysis: com.videoeditor.core.media.audio.DuckingAnalysisService? = null,
) {
    private val sessions = kotlinx.coroutines.sync.Mutex()

    /**
     * The analyzer export runs against the *same* cache the composition builder
     * reads. When the engine is built manually (device tests), no service is
     * injected, so a default one is created bound to the builder's own cache —
     * keeping preview and export on one analysis store even outside Hilt.
     */
    private val duckingAnalysisService = duckingAnalysis
        ?: com.videoeditor.core.media.audio.DuckingAnalysisService(
            context,
            com.videoeditor.core.media.audio.DuckingAnalyzer(),
            compositionBuilder.duckingCache,
        )

    /**
     * Exports [project] to [request.outputFile], forwarding real progress to
     * [onProgress]. Returns the terminal result.
     *
     * Transformer is bound to a looper at construction and asserts it on every
     * call, so construction, `start`, `getProgress` and `cancel` all run on the
     * main thread; only file deletion stays on IO.
     */
    suspend fun export(
        project: Project,
        request: ExportRequest,
        onProgress: (ExportProgress) -> Unit,
    ): Result<java.io.File, MediaError> {
        sessions.lock()
        val sessionId = java.util.UUID.randomUUID().toString()
        try {
            require(project.id == request.projectId) { "Export request does not match the project" }
            require(!request.outputFile.exists()) { "The output file already exists; choose a fresh export destination" }
            com.videoeditor.core.model.render.ProjectRenderPlan.compile(project)
            android.util.Log.i("VideoExport", "session=$sessionId preparing project=${project.id}")
            onProgress(ExportProgress(0f, ExportStage.Preparing))
            return runSession(project, request, onProgress, sessionId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            android.util.Log.e("VideoExport", "session=$sessionId failed before completion", failure)
            return Result.Failure(classify(failure, sessionId))
        } finally { sessions.unlock() }
    }

    private suspend fun runSession(
        project: Project,
        request: ExportRequest,
        onProgress: (ExportProgress) -> Unit,
        sessionId: String,
    ): Result<java.io.File, MediaError> = withContext(Dispatchers.Main.immediate) {
        // Render at the selected resolution so the setting is a real encoder
        // input, and apply a bitrate that matches the resolution and quality
        // preset (Standard/High are distinct encoder configurations, not
        // cosmetic states).
        // Passive boundaries for shell-profiled exports. Keep encoder completion
        // separate from MP4 audio finalization and later MediaStore/UI work.
        fun timingEvent(event: String) {
            if (android.os.Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled()) {
                val now = android.os.SystemClock.elapsedRealtimeNanos()
                android.util.Log.i("ExportTiming", "output=${request.outputFile.name} event=$event bootNs=$now")
            }
        }
        // Ducking curves must exist before the composition is built, because the
        // builder reads them from the shared cache to decide whether a ducking
        // processor is inserted. Running it here, on IO, is what makes export
        // replay exactly the analysis preview used: same service, same cache,
        // same deterministic curve. Bounded and blocking, never on this thread.
        withContext(Dispatchers.IO) { duckingAnalysisService.ensureAnalyzed(project) }

        val composition = compositionBuilder.buildForExport(
            project,
            request.resolution.width,
            request.resolution.height,
            // Zero keeps the accepted Stage 6 default: source cadence untouched.
            request.frameRate.fps,
        )

        val videoSettings = androidx.media3.transformer.VideoEncoderSettings.Builder()
            .setBitrate(videoBitrateFor(request.resolution, request.quality, request.frameRate))
            // Constant bitrate: the preset is the encoder's rate, so Smaller
            // File always produces the smallest file and the on-screen size
            // estimate tracks reality. Under VBR the same configuration let
            // high-entropy content invert the preset ordering (verified on
            // device: 11.3M/21.4M/21.4M for Smaller/Standard/High).
            .setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            .build()
        val audioSettings = androidx.media3.transformer.AudioEncoderSettings.Builder()
            .setBitrate(AUDIO_BITRATE_BPS)
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(false)
            .setRequestedVideoEncoderSettings(videoSettings)
            .setRequestedAudioEncoderSettings(audioSettings)
            .build()

        val outputClock = java.util.concurrent.atomic.AtomicReference<com.videoeditor.core.media.audio.CanonicalAacEncoder?>()
        val exportEncoderFactory = object : androidx.media3.transformer.Codec.EncoderFactory by encoderFactory {
            override fun createForAudioEncoding(format: androidx.media3.common.Format): androidx.media3.transformer.Codec {
                val original = encoderFactory.createForAudioEncoding(format)
                val forced = com.videoeditor.core.media.audio.AacEncoderPrimingProbe.enabledForQualification
                val supported = com.videoeditor.core.media.audio.AacEncoderPrimingProbe.enabledForSupportedEncoders &&
                    original.name == "c2.android.aac.encoder" && format.sampleRate in listOf(44100,48000) &&
                    format.channelCount in 1..2 && format.pcmEncoding == androidx.media3.common.C.ENCODING_PCM_16BIT
                if (!forced && !supported) return original
                try {
                    val evidence = com.videoeditor.core.media.audio.AacEncoderPrimingProbe.measure(
                        original.name,format.sampleRate,format.channelCount,AUDIO_BITRATE_BPS)
                    return com.videoeditor.core.media.audio.CanonicalAacEncoder(original,evidence).also { outputClock.set(it) }
                } catch (e: Exception) {
                    original.release()
                    throw ExportException.createForAssetLoader(e,ExportException.ERROR_CODE_ENCODING_FAILED)
                }
            }
            override fun createForVideoEncoding(format: androidx.media3.common.Format): androidx.media3.transformer.Codec {
                val codec = encoderFactory.createForVideoEncoding(format)
                val observer = com.videoeditor.core.media.preview.PreviewPhaseTrace.diagnosticVideoEncoder
                return if (com.videoeditor.core.media.preview.PreviewPhaseTrace.identityDiagnostic && observer != null)
                    observer.apply(codec) else codec
            }
        }

        val transformer = Transformer.Builder(context)
            .apply {
                // Passive codec decorators are installed only by instrumentation.
                val decorator = com.videoeditor.core.media.preview.PreviewPhaseTrace.diagnosticVideoDecoder
                if (com.videoeditor.core.media.audio.SourceClockAacDecoder.isEnabled() || project.timeline.transitions.isNotEmpty() || (com.videoeditor.core.media.preview.PreviewPhaseTrace.identityDiagnostic && decorator != null)) {
                    // Transition graphs can exhaust the preferred decoder's instance budget.
                    // Let Media3 try its next declared decoder if initialization fails.
                    val original = androidx.media3.transformer.DefaultDecoderFactory.Builder(context)
                        .setEnableDecoderFallback(project.timeline.transitions.isNotEmpty())
                        .build()
                    val decoderFactory = if (decorator != null && com.videoeditor.core.media.preview.PreviewPhaseTrace.identityDiagnostic)
                        object : androidx.media3.transformer.Codec.DecoderFactory by original {
                            override fun createForVideoDecoding(format: androidx.media3.common.Format, surface: android.view.Surface, toneMap: Boolean) =
                                decorator.apply(original.createForVideoDecoding(format,surface,toneMap))
                        } else original
                    setAssetLoaderFactory(if (com.videoeditor.core.media.audio.SourceClockAacDecoder.isEnabled() || project.timeline.transitions.isNotEmpty())
                        androidx.media3.transformer.ReorderAwareAssetLoaderFactory(context,decoderFactory,project.timeline.transitions.isNotEmpty())
                        else androidx.media3.transformer.DefaultAssetLoaderFactory(context,decoderFactory,androidx.media3.common.util.Clock.DEFAULT))
                }
            }
            .setLooper(Looper.getMainLooper())
            .setEncoderFactory(exportEncoderFactory)
            .setVideoMimeType("video/avc")
            .setAudioMimeType("audio/mp4a-latm")
            // FrameworkMuxer otherwise gives the last frame a zero duration.
            // Hold that decoded frame through the model's exact visual end,
            // especially when slowed clips have widely spaced timestamps.
            .setMuxerFactory(androidx.media3.transformer.DefaultMuxer.Factory()
                .setVideoDurationUs(project.timeline.renderedDurationMicros))
            .build()

        val progressHolder = ProgressHolder()
        var cancelled = false
        var failed = false

        // Poll the engine's real progress on a loop that lives for the duration
        // of the surrounding coroutine scope. Runs on the main thread because
        // getProgress verifies the Transformer's application thread.
        val progressJob = launch {
            while (isActive) {
                val state = transformer.getProgress(progressHolder)
                if (state == androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE) {
                    val fraction = (progressHolder.progress / 100f).coerceIn(0f, .99f)
                    // Transformer processes audio/video concurrently and does
                    // not expose substage transitions. Report its actual active
                    // render rather than infer audio/finalization from percent.
                    onProgress(ExportProgress(fraction, ExportStage.Rendering))
                }
                delay(200)
            }
        }

        try {
        val terminal = suspendCancellableCoroutine<Result<java.io.File, MediaError>> { cont ->
            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                ) {
                    timingEvent("transformer-completed")
                    progressJob.cancel()
                    onProgress(ExportProgress(1f, ExportStage.Saving))
                    launch {
                        val finalized = withContext(Dispatchers.IO) {
                            try {
                                outputClock.get()?.let { clock ->
                                    com.videoeditor.core.media.audio.Mp4AudioPriming.apply(request.outputFile, clock)
                                }
                                verifyOutput(project, request)
                                Result.Success(request.outputFile)
                            } catch (failure: Exception) {
                                Result.Failure(MediaError.ExportFailed("Output finalization failed: ${failure.message}", failure,
                                    com.videoeditor.core.media.ExportFailureCategory.Finalization, sessionId))
                            }
                        }
                        if (cont.isActive) cont.resume(finalized)
                    }
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                    exportException: ExportException,
                ) {
                    progressJob.cancel()
                    android.util.Log.e("VideoExport", "Transformer export failed", exportException)
                    if (cont.isActive) cont.resume(Result.Failure(classify(exportException, sessionId)))
                }
            })

            try {
                timingEvent("transformer-start")
                transformer.start(composition, request.outputFile.absolutePath)
            } catch (error: Exception) {
                progressJob.cancel()
                if (cont.isActive) cont.resume(Result.Failure(classify(error, sessionId)))
            }

            cont.invokeOnCancellation {
                cancelled = true
                progressJob.cancel()
            }
        }

        failed = terminal is Result.Failure
        if (terminal is Result.Success) timingEvent("output-finalized")
        terminal
        } finally {
            progressJob.cancel()
            // All terminal paths retire this session on its owning application looper.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) { transformer.cancel() }
            if (cancelled || failed) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                request.outputFile.delete()
            }
        }
    }

    /**
     * Target video bitrate for the selected resolution and quality preset. These
     * are realistic H.264 rates; High roughly doubles Standard at a given
     * resolution so the two presets produce visibly different files.
     */
    private fun videoBitrateFor(
        resolution: com.videoeditor.core.model.ExportResolution,
        quality: com.videoeditor.core.model.ExportQuality,
    ): Int = videoBitrateFor(resolution, quality, com.videoeditor.core.model.FrameRate.FPS_30)

    /** Delegates to the shared [com.videoeditor.core.model.ExportProfile] ladder. */
    internal fun videoBitrateFor(
        resolution: com.videoeditor.core.model.ExportResolution,
        quality: com.videoeditor.core.model.ExportQuality,
        frameRate: com.videoeditor.core.model.FrameRate,
    ): Int = com.videoeditor.core.model.ExportProfile.videoBitrateBps(resolution, quality, frameRate)

    private fun classify(failure: Throwable, sessionId: String? = null): MediaError.ExportFailed {
        val chain = generateSequence(failure) { it.cause }.take(16).toList()
        val messages = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
        val export = chain.filterIsInstance<ExportException>().firstOrNull()
        val category = when {
            chain.any { it is java.io.FileNotFoundException || it is SecurityException } -> com.videoeditor.core.media.ExportFailureCategory.MissingAsset
            (messages.contains("ENOSPC", true) || messages.contains("no space left", true)) -> com.videoeditor.core.media.ExportFailureCategory.Storage
            messages.contains("EGL", true) -> com.videoeditor.core.media.ExportFailureCategory.Egl
            messages.contains("shader", true) || messages.contains("uniform", true) -> com.videoeditor.core.media.ExportFailureCategory.Shader
            export?.errorCode in listOf(ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FAILED) -> com.videoeditor.core.media.ExportFailureCategory.Decoder
            export?.errorCode in listOf(ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FAILED) -> com.videoeditor.core.media.ExportFailureCategory.Encoder
            failure is IllegalArgumentException -> com.videoeditor.core.media.ExportFailureCategory.InvalidPlan
            messages.contains("unsupported", true) -> com.videoeditor.core.media.ExportFailureCategory.UnsupportedMedia
            else -> com.videoeditor.core.media.ExportFailureCategory.Unknown
        }
        val detail = (chain.lastOrNull()?.message ?: failure.javaClass.simpleName).lineSequence().take(4).joinToString(" ").take(480)
        return MediaError.ExportFailed("${category.name}: $detail" + (export?.let { " (${it.errorCodeName})" } ?: ""),
            failure, category, sessionId)
    }

    /** Container checks run in production after encoder completion, never as simulated device acceptance. */
    private fun verifyOutput(project: Project, request: ExportRequest) {
        require(request.outputFile.isFile && request.outputFile.length() > 0L) { "Encoder produced no MP4 data" }
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(request.outputFile.absolutePath)
            var video: android.media.MediaFormat? = null
            var audio = false
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) { video = format; extractor.selectTrack(index) }
                if (mime.startsWith("audio/")) audio = true
            }
            if (project.timeline.videoClips.isNotEmpty()) {
                val format = requireNotNull(video) { "MP4 has no video track" }
                val short = minOf(project.canvas.width, project.canvas.height)
                fun even(value: Double) = ((value / 2).toInt() * 2).coerceAtLeast(2)
                val width = even(request.resolution.height.toDouble() * project.canvas.width / short)
                val height = even(request.resolution.height.toDouble() * project.canvas.height / short)
                val rotation = if (format.containsKey(android.media.MediaFormat.KEY_ROTATION)) format.getInteger(android.media.MediaFormat.KEY_ROTATION) else 0
                val actualWidth = format.getInteger(android.media.MediaFormat.KEY_WIDTH)
                val actualHeight = format.getInteger(android.media.MediaFormat.KEY_HEIGHT)
                require(if (rotation % 180 == 0) actualWidth == width && actualHeight == height else actualHeight == width && actualWidth == height) {
                    "Expected ${width}×$height; encoder wrote ${actualWidth}×$actualHeight (rotation $rotation)"
                }
                require(extractor.sampleTime >= 0L) { "MP4 has no video samples" }
                require(format.containsKey(android.media.MediaFormat.KEY_DURATION) && format.getLong(android.media.MediaFormat.KEY_DURATION) > 0L) { "MP4 has no valid duration" }
                val duration = format.getLong(android.media.MediaFormat.KEY_DURATION)
                val expected = project.timeline.renderedDurationMicros
                val tolerance = maxOf(100_000L, 2_000_000L / request.frameRate.fps)
                require(kotlin.math.abs(duration - expected) <= tolerance) { "MP4 duration $duration us differs from the render plan $expected us" }
            }
            val expectsAudio = project.timeline.audioClips.any { !it.muted } || project.timeline.videoClips.any {
                !it.isPhoto && !it.muted && project.mediaRef(it.mediaRefId)?.hasAudio != false } || project.timeline.overlayClips.any {
                !it.isPhoto && !it.muted && project.mediaRef(it.mediaRefId)?.hasAudio != false }
            require(!expectsAudio || audio) { "MP4 has no expected audio track" }
        } finally { extractor.release() }
    }

    private companion object {
        /** AAC audio bitrate shared by all export presets. */
        const val AUDIO_BITRATE_BPS = com.videoeditor.core.model.ExportProfile.AUDIO_BITRATE_BPS
    }
}
