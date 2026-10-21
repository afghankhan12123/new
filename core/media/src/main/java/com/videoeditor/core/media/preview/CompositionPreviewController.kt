package com.videoeditor.core.media.preview

import android.content.Context
import android.os.Looper
import android.view.Choreographer
import android.view.SurfaceView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.PatchedCompositionPlayer
import com.videoeditor.core.media.composition.CompositionBuilder
import com.videoeditor.core.model.CanvasSettings
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Timing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Observable preview state. All times are integer microseconds on the project
 * timeline; [durationMicros] is the authoritative model duration (the player's
 * own duration is only used to cross-check in tests).
 */
enum class PreviewPhase { Uninitialized, Preparing, Ready, Playing, Paused, Seeking, Reconfiguring, Recovering, RecoverableError, FatalError, Released }

data class PreviewState(
    val phase: PreviewPhase = PreviewPhase.Uninitialized,
    /** Resolved position: latest queued seek until dispatched, then the player clock. */
    val positionMicros: Long = 0L,
    val actualPositionMicros: Long = 0L,
    val requestedPositionMicros: Long? = null,
    val durationMicros: Long = 0L,
    val isPlaying: Boolean = false,
    /** User playback intention, including decoder buffering; false after pause/end/error. */
    val playRequested: Boolean = false,
    val isBuffering: Boolean = false,
    val hasComposition: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Ids of audio clips the preview engine cannot represent (export is unaffected). */
    val omittedAudioClipIds: List<String> = emptyList(),
    val error: PreviewError? = null,
)

/** A user-presentable playback failure. */
data class PreviewError(val message: String, val errorCode: Int = 0, val cause: Throwable? = null,
    val recoverable: Boolean = true)

/**
 * Drives multi-clip composition preview playback on top of Media3's
 * [PatchedCompositionPlayer] — the experimental player that consumes the exact
 * [androidx.media3.transformer.Composition] produced by [CompositionBuilder] for
 * export, so preview and export share one interpretation of the edit.
 *
 * Engine facts this class is built around (verified against the Media3 1.5.1
 * bytecode, since the API is experimental and undocumented):
 *  - `setComposition` may be called at most once per instance, so every
 *    structural edit rebuilds the player. Position and play state survive the
 *    rebuild.
 *  - The video output must be a [SurfaceView] (a TextureView or bare Surface is
 *    rejected via the [Player] interface; the two-arg `setVideoSurface` overload
 *    is for headless use).
 *  - Compositions containing gap items are rejected; preview uses real silent
 *    PCM media to pad offset and short audio sequences.
 *  - The public [Player] API is millisecond-based; conversions to the project's
 *    integer-microsecond timeline happen only here.
 *  - All calls must happen on the main thread ([SimpleBasePlayer][androidx.media3.common.SimpleBasePlayer]
 *    verifies the application thread).
 *
 * This class is UI-free by design: the Compose layer attaches a surface and
 * calls [play]/[pause]/[seekTo]; all playback logic stays in the engine.
 */
@UnstableApi
class CompositionPreviewController private constructor(
    private val context: Context,
    private val compositionBuilder: CompositionBuilder,
    private val verificationAudioSink: androidx.media3.exoplayer.audio.AudioSink? = null,
) {

    /** Creates controllers bound to the application context. */
    @javax.inject.Singleton
    class Factory @javax.inject.Inject constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
        private val compositionBuilder: CompositionBuilder,
    ) {
        fun create(audioSink: androidx.media3.exoplayer.audio.AudioSink? = null): CompositionPreviewController =
            CompositionPreviewController(context.applicationContext, compositionBuilder, audioSink)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickerJob: Job? = null
    private var pendingScrubMicros: Long? = null
    private var pendingSeekMayResume = false
    private var pendingScrubRequestedNanos = 0L
    private var scrubFramePosted = false
    private var initialFrameReady = true
    private var graphOutputDeadline: GraphOutputDeadline? = null
    private val playbackOutputWatchdog = PlaybackOutputWatchdog()
    private val seekDispatchGate = SeekDispatchGate()
    private var scrubSettling = true
    private val previewBitmapCache = PreviewBitmapCache(context.filesDir)
    /** Instrumentation can withhold graph output while retaining real decoders and cleanup. */
    internal var verificationGraphFactory: ((androidx.media3.effect.VideoCompositorSettings) -> androidx.media3.common.PreviewingVideoGraph.Factory)? = null
    // A replacement's requested position remains authoritative until its own
    // decoded output arrives. Surface callbacks may run during initialization.
    private var graphRestoreTargetMicros: Long? = null
    private fun retainedPositionMicros(): Long = pendingScrubMicros ?: graphRestoreTargetMicros ?: _state.value.positionMicros
    private fun tracePosition(event: String) {
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.util.Log.d("PreviewSeekOwnership", "$event generation=$rebuildCount pending=$pendingScrubMicros restore=$graphRestoreTargetMicros published=${_state.value.positionMicros} raw=${player?.currentPosition}")
        }
    }
    private val scrubFrame = android.view.Choreographer.FrameCallback {
        scrubFramePosted = false
        flushScrubSeek()
    }

    /** Latest pointer target per display frame; the final target is flushed on lift. */
    fun seekForScrub(positionMicros: Long) {
        assertMainThread()
        PreviewPhaseTrace.request(positionMicros, player, rebuildCount)
        scrubSettling = false
        val target = positionMicros.coerceIn(0L, _state.value.durationMicros)
        pendingScrubMicros = target
        pendingSeekMayResume = false
        tracePosition("scrub $positionMicros")
        pendingScrubRequestedNanos = android.os.SystemClock.elapsedRealtimeNanos()
        // Publish actual pending state here, not only from the next polling
        // tick. A completed reset may dispatch before that tick, and the player
        // reports milliseconds while this requested target retains microseconds.
        _state.update { it.copy(positionMicros = target, requestedPositionMicros = target) }
        PreviewPhaseTrace.counter("publishedPositionUs", _state.value.positionMicros)
        if (!scrubFramePosted) {
            scrubFramePosted = true
            android.view.Choreographer.getInstance().postFrameCallback(scrubFrame)
        }
    }

    fun flushScrubSeek() {
        assertMainThread()
        if (scrubFramePosted) {
            android.view.Choreographer.getInstance().removeFrameCallback(scrubFrame)
            scrubFramePosted = false
        }
        val target = pendingScrubMicros ?: return
        if (android.os.Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled()) {
            val active = player
            android.os.Trace.setCounter("Preview.seekWaitMask", (if(initialFrameReady) 1L else 0L) +
                (if(reachedEnd) 2L else 0L) + (if(active?.hasRenderedPrimaryFinalOutput()==true) 4L else 0L))
            android.os.Trace.setCounter("Preview.seekWaitPlayerState",active?.playbackState?.toLong() ?: -1L)
        }
        // Overlapping seeks while the video graph initializes/buffers can stall
        // paused output. Retain only the latest input until the graph can safely
        // reposition; a rendered, paused generation flushes it directly rather than
        // waiting on a STATE_READY edge that a paused patched graph never re-emits.
        if ((!initialFrameReady || player?.playbackState == Player.STATE_BUFFERING) &&
            !canDispatchPendingLanding()) {
            // Structural edits must resume at the latest requested position,
            // even when the decoder has not caught up. This remains optimistic state.
            startTicker()
            tick()
            return
        }
        val resumePlayback = pendingSeekMayResume
        pendingScrubMicros = null
        pendingSeekMayResume = false
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.os.Trace.setCounter("Preview.scrubDispatchDelayUs",
                (android.os.SystemClock.elapsedRealtimeNanos() - pendingScrubRequestedNanos) / 1_000)
        }
        // Ruler input requests a paused landing. Programmatic seeks and a newer
        // explicit Play retain their transport intent while preparation coalesces.
        seekTo(target, resumePlayback = resumePlayback)
    }

    /** Pointer lift requests the latest target; the in-flight frame owns its completion barrier. */
    fun finishScrubSeek() {
        assertMainThread()
        scrubSettling = true
        flushScrubSeek()
    }

    /** Explicit user recovery never changes the authored project/history. */
    fun retryPreview() {
        assertMainThread()
        val project = currentProject ?: return
        failureRecoveryCount = 0
        if (!suspended) rebuild(project, retainedPositionMicros(), false)
    }

    private fun cancelScrubSeek() {
        android.view.Choreographer.getInstance().removeFrameCallback(scrubFrame)
        scrubFramePosted = false
        pendingScrubMicros = null
        pendingSeekMayResume = false
    }

    /** One seek in flight; the latest target waits for a real frame/reset completion. */
    private fun canDispatchPendingLanding(): Boolean {
        val active = player ?: return false
        if (active.playerError != null || _state.value.error != null) return false
        if (active.playbackState == Player.STATE_READY) return true
        if (!active.playWhenReady && active.lastRenderedProjectTimeUs != androidx.media3.common.C.TIME_UNSET) return true
        return active.hasCompletedPrimaryVideoReset() &&
            seekDispatchGate.canSupersede(android.os.SystemClock.elapsedRealtime(), scrubSettling)
    }

    data class RebuildTiming(val constructionNanos: Long, val initializationNanos: Long)
    var lastRebuildTiming: RebuildTiming? = null
        private set
    var activePlayerCount: Int = 0
        private set
    var rebuildCount: Int = 0
        private set

    private var player: PatchedCompositionPlayer? = null
    private var suspended = false
    // tick() may be re-entered synchronously: onEvents -> tick -> flushScrubSeek
    // -> seekTo -> tick. Guard so a single logical flush runs to completion
    // instead of recursing until a StackOverflowError crashes the instrument
    // process (which cascades into later tests). The re-entrant call returns
    // immediately; pending work remains owned by the display ticker started by
    // seekTo/onEvents, which observes the updated player on its next frame.
    private var tickInProgress = false
    private var playbackOwner: Any? = null
    private var surfaceView: SurfaceView? = null
    private var surfaceCallback: android.view.SurfaceHolder.Callback? = null
    private var surfaceRestoreJob: Job? = null
    private var headlessOutput: Pair<android.view.Surface, androidx.media3.common.util.Size>? = null
    private var lastSignature: CompositionSignature? = null
    private var currentProject: Project? = null
    private var activeRenderState: com.videoeditor.core.media.composition.RenderPlanState? = null
    private var visualRefreshPending = false
    private var failureRecoveryCount = 0
    private var recoveryOutputAnchorUs: Long? = null

    /** Submitted compositor output whose deadline has arrived; not proof of display presentation. */
    val renderedPositionMicros: Long
        get() = player?.lastRenderedProjectTimeUs ?: androidx.media3.common.C.TIME_UNSET

    val pendingOutputFrameCount: Int get() = player?.pendingPreviewOutputFrameCount ?: 0

    val playbackClockPositionMicros: Long
        get() {
            assertMainThread()
            return player?.currentPosition?.let(Timing::millisToMicros) ?: 0L
        }
    private var audioEnvelopes: List<CompositionBuilder.PreviewEnvelope> = emptyList()

    /**
     * Set when play() seeks back to the start from the composition end. While
     * true, tick() skips end-enforcement so the asynchronous seek is not undone
     * by the still-stale at-end position; it clears once the reported position
     * drops back inside the composition.
     */
    private var restartPending = false

    /**
     * True once playback has hit the composition end. Seeking to exactly
     * durationMicros can settle a few milliseconds under it, so "at the end"
     * cannot be re-derived from the position alone; this flag remembers that we
     * got there and is cleared by any seek or restart back inside the timeline.
     */
    private var reachedEnd = false
    private var finalVideoDrainStartedMs: Long? = null

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var playerListener: Player.Listener? = null

    private fun listenerFor(instance: Player) = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player !== this@CompositionPreviewController.player) return
            // First-frame events may belong to a previous asynchronous landing.
            // Only current-generation submitted output can acknowledge its deadline.
            if (player.playbackState == Player.STATE_READY) flushScrubSeek()
            tick()
            startTicker()
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (player !== instance) return
            _state.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (player !== instance) return
            android.util.Log.e(TAG, "Active preview failed at ${_state.value.positionMicros}us", error)
            val target = retainedPositionMicros()
            val resume = _state.value.playRequested || player?.playWhenReady == true
            val recoverable = error.errorCode !in setOf(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
            _state.update { it.copy(error = PreviewError(describe(error), error.errorCode, error, recoverable),
                phase = if (recoverable) PreviewPhase.RecoverableError else PreviewPhase.FatalError,
                isPlaying = false, playRequested = false) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (player !== instance) return@post
                val project = currentProject
                try {
                    if (recoverable && failureRecoveryCount < 1 && project != null && !suspended) {
                        failureRecoveryCount++
                        rebuild(project, target, resume, recoveryAttempt = 1)
                    } else releasePlayer()
                } catch (failure: RuntimeException) {
                    android.util.Log.e(TAG, "Preview recovery cleanup failed", failure)
                    _state.update { it.copy(phase = PreviewPhase.FatalError,
                        error = PreviewError("Preview resources could not be released. Save your project and return to the editor.",
                            cause = failure, recoverable = false)) }
                }
            }
        }
    }

    /**
     * Points the preview at [project] (or clears it when null). Rebuilds the
     * underlying player only when something the composition depends on changed,
     * preserving the playhead position and play state across the rebuild.
     */
    fun setProject(project: Project?) {
        assertMainThread()
        if (project == null) {
            teardown()
            return
        }
        val plan = try { com.videoeditor.core.model.render.ProjectRenderPlan.compile(project) }
        catch (failure: Exception) {
            player?.pause()
            currentProject = project
            _state.update { it.copy(phase = PreviewPhase.FatalError, isPlaying = false, playRequested = false,
                error = PreviewError("The edit cannot be rendered: ${failure.message}", cause = failure, recoverable = false)) }
            return
        }
        val signature = CompositionSignature.of(project)
        currentProject = project
        if (signature == lastSignature && _state.value.error == null) return
        lastSignature = signature
        val active = activeRenderState
        if (player != null && active != null && active.plan.hasSameTopology(plan) && _state.value.error == null) {
            if (active.plan != plan) {
                active.update(plan)
                // A paused source replays the same frame through updated uniforms/canvas state.
                // This is an in-place seek with an explicit texture barrier, not a decoder rebuild.
                if (player?.playWhenReady != true) {
                    visualRefreshPending = true
                    seekForScrub(retainedPositionMicros())
                }
            }
            return
        }
        failureRecoveryCount = 0
        val resumeAtMicros = retainedPositionMicros()
        val resumePlaying = _state.value.playRequested
        if (!suspended) rebuild(project, resumeAtMicros, resumePlaying)
    }

    /**
     * Rebuilds the composition from the current project even when its signature
     * is unchanged.
     *
     * Ducking analysis is computed off the main thread and lands in a shared
     * cache *after* the edit that enabled it already triggered a rebuild. The
     * cached curve is not part of the project document, so no signature change
     * follows and [setProject] would return early. This is the explicit second
     * rebuild that picks the curve up, and it preserves position and play state
     * the same way a signature-driven rebuild does.
     *
     * Only needed when a non-document input to the composition has changed.
     * Callers must not use it to work around a missing signature field.
     */
    fun rebuildFromCachedAudioAnalysis() {
        assertMainThread()
        val project = currentProject ?: return
        if (suspended) return
        rebuild(project, retainedPositionMicros(), _state.value.isPlaying)
    }

    /** Binds the video output. Safe to call before or after a composition exists. */
    fun attachSurface(view: SurfaceView) {
        assertMainThread()
        if (surfaceView === view) return
        detachSurface()
        surfaceView = view
        var recreated = false
        var width = view.holder.surfaceFrame.width()
        var height = view.holder.surfaceFrame.height()
        surfaceCallback = object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                // Initial photo preparation may finish before the holder has a
                // usable output. Creation needs the same decode as replacement.
                restoreSurfaceFrame(view)
            }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, newWidth: Int, newHeight: Int) {
                if (newWidth > 0 && newHeight > 0 && (recreated || width != newWidth || height != newHeight)) restoreSurfaceFrame(view)
                width = newWidth; height = newHeight
                if (newWidth > 0 && newHeight > 0) recreated = false
            }
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                recreated = true
                if (surfaceView === view) { surfaceRestoreJob?.cancel(); surfaceRestoreJob = null }
            }
        }.also(view.holder::addCallback)
        // The output surface is replaceable; the render session keeps source/decoder ownership.
        // A paused PatchedCompositionPlayer 1.5.1 does not reliably send its retained
        // frame to a replacement SurfaceView. Surface callbacks request an in-place refresh
        // at the retained playhead when Compose changes its layout.
        val project = currentProject
        if (!suspended && project != null && player == null) rebuild(project, retainedPositionMicros(), false)
        else player?.setVideoSurfaceView(view)
    }

    fun detachSurface(expectedView: SurfaceView? = null) {
        assertMainThread()
        val view = surfaceView ?: return
        // AndroidView may release the old view after its replacement has bound.
        // That release owns only the old surface, never the new preview output.
        if (expectedView != null && view !== expectedView) return
        surfaceRestoreJob?.cancel(); surfaceRestoreJob = null
        surfaceCallback?.let(view.holder::removeCallback); surfaceCallback = null
        surfaceView = null
        runCatching { player?.clearVideoSurfaceView(view) }
    }

    private fun restoreSurfaceFrame(view: SurfaceView) {
        tracePosition("surface restore")
        surfaceRestoreJob?.cancel()
        val needsRefresh = graphRestoreTargetMicros == null
        surfaceRestoreJob = scope.launch {
            // Let all holder callbacks finish before removing the old Media3
            // callback. Coalesce creation/size callbacks into one real decode.
            awaitDisplayFrame()
            if (surfaceView !== view || suspended || !view.holder.surface.isValid) return@launch
            val project = currentProject ?: return@launch
            tick()
            val active = player
            if (active == null) rebuild(project, retainedPositionMicros(), _state.value.playRequested)
            else {
                active.setVideoSurfaceView(view)
                if (!active.playWhenReady && needsRefresh) {
                    visualRefreshPending = true
                    seekForScrub(retainedPositionMicros())
                }
            }
        }
    }

    /**
     * Binds a headless video output (e.g. an ImageReader surface) for
     * on-device verification without UI. Mutually exclusive with
     * [attachSurface].
     */
    fun attachHeadlessSurface(surface: android.view.Surface, size: androidx.media3.common.util.Size) {
        assertMainThread()
        headlessOutput = surface to size
        runCatching { player?.setVideoSurface(surface, size) }
    }

    fun detachHeadlessSurface() {
        assertMainThread()
        val (surface, _) = headlessOutput ?: return
        headlessOutput = null
        runCatching { player?.clearVideoSurface(surface) }
    }

    fun play() {
        assertMainThread()
        val p = player ?: return
        // Explicit transport input is newer than a deferred paused landing.
        scrubSettling = true
        if (pendingScrubMicros != null) pendingSeekMayResume = true
        val durationMicros = _state.value.durationMicros
        // PatchedCompositionPlayer never reaches STATE_ENDED (see tick), so restarting
        // from the end is driven by the model position instead: if the playhead
        // is at or past the composition end, start over.
        val requested = pendingScrubMicros
        val atEnd = if (requested != null) {
            durationMicros > 0L && requested >= durationMicros
        } else {
            p.playbackState == Player.STATE_ENDED || reachedEnd ||
                (durationMicros > 0L && _state.value.positionMicros >= durationMicros)
        }
        if (atEnd) {
            // Replay is a newer seek intention than any deferred end scrub.
            // Clear it before the player can synchronously publish seek events.
            cancelScrubSeek()
            restartPending = true
            reachedEnd = false
            audioEnvelopes.forEach { it.seekTo(0L) }
            seekTo(0L, resumePlayback = true)
            restartPending = true
            reachedEnd = false
            // seekTo is asynchronous, so the player still reports the old at-end
            // position until it processes the seek. Suppress end-enforcement
            // until the reported position moves back inside the composition, or
            // tick() would immediately pause and seek to the end again.
        }
        p.play()
        tick()
        startTicker()
    }

    fun pause() {
        assertMainThread()
        val p = player ?: return
        pendingSeekMayResume = false
        val positionMillis = p.currentPosition.coerceAtLeast(0L)
        val wasPlaying = p.playWhenReady
        p.pause()
        if (wasPlaying) {
            // Audio sink pause is asynchronous and can report a later position
            // when buffered samples settle. Pin the actual player to the frame
            // requested by the user, rather than only freezing the UI clock.
            visualRefreshPending = true
            seekTo(Timing.millisToMicros(positionMillis), resumePlayback = false)
        }
        tick()
    }

    fun togglePlayPause() {
        assertMainThread()
        val p = player ?: return
        // The transport icon can race the controller's end clamp: Media3 may
        // still report playWhenReady while the model is already at logical EOS.
        // Let play() own replay detection instead of routing that tap to pause().
        val durationMicros = _state.value.durationMicros
        val requestedAtEnd = pendingScrubMicros?.let { durationMicros > 0L && it >= durationMicros } == true
        val logicalAtEnd = requestedAtEnd || reachedEnd ||
            (durationMicros > 0L && _state.value.positionMicros >= durationMicros) ||
            p.playbackState == Player.STATE_ENDED
        if (logicalAtEnd) play() else if (p.playWhenReady) pause() else play()
    }

    /**
     * Seeks to [positionMicros], clamped to the composition, preserving the
     * transport's play request unless [resumePlayback] overrides it.
     *
     * The Media3 patch does not reach STATE_ENDED and keeps reporting positions
     * past the composition end while [Player.getPlayWhenReady] stays true. A
     * rebuild therefore cannot inherit that flag as a faithful play state at
     * logical EOS, or a paused post-EOS seek would resume playback and advance
     * off the requested frame. Scrub dispatch passes false explicitly.
     */
    @JvmOverloads
    fun seekTo(positionMicros: Long, resumePlayback: Boolean = true) {
        assertMainThread()
        val p = player ?: return
        val clamped = positionMicros.coerceIn(0L, _state.value.durationMicros)
        PreviewPhaseTrace.counter("acceptedTargetUs", clamped)
        PreviewPhaseTrace.counter("seekPlayerIdentity", System.identityHashCode(p).toLong())
        tracePosition("seek $clamped")
        // Boundary buttons, text jumps and accessibility actions can arrive
        // before the replacement graph has decoded its first frame, just like
        // scrub input. Repeated resetPosition calls during initialization can
        // strand the paused graph. Dispatch only the latest target when ready.
        // Use the same readiness contract as flushScrubSeek. Otherwise a permitted
        // paused landing re-enters here and queues itself forever while BUFFERING.
        if ((!initialFrameReady || p.playbackState == Player.STATE_BUFFERING) && !canDispatchPendingLanding()) {
            pendingScrubMicros = clamped
            _state.update { it.copy(positionMicros = clamped, requestedPositionMicros = clamped) }
            pendingSeekMayResume = resumePlayback
            pendingScrubRequestedNanos = android.os.SystemClock.elapsedRealtimeNanos()
            startTicker()
            tick()
            return
        }
        if (!resumePlayback) p.pause()
        // Cross-period child preparation is asynchronous. Input reset acknowledgments
        // gate that child's frames; the shared graph, GL context and players stay owned.
        cancelScrubSeek()
        restartPending = false
        reachedEnd = _state.value.durationMicros > 0L && clamped >= _state.value.durationMicros
        val targetMillis = Timing.microsToMillis(currentProject?.let { decodeTargetMicros(it, clamped) } ?: clamped)
        if (p.currentPosition != targetMillis || visualRefreshPending) {
            val isVisualRefresh = visualRefreshPending && p.currentPosition == targetMillis
            visualRefreshPending = false
            // Audio/headless-without-output callers have no surface picture to acknowledge.
            // Waiting for one pins the published position even while PCM playback advances.
            initialFrameReady = currentProject?.timeline?.videoClips.isNullOrEmpty() || !hasValidVideoOutput()
            graphRestoreTargetMicros = clamped
            if (hasValidVideoOutput()) graphOutputDeadline = GraphOutputDeadline(p, targetMillis * 1000,
                failureRecoveryCount, android.os.SystemClock.elapsedRealtime(), toleranceUs = GraphOutputDeadline.DEFAULT_FRAME_TOLERANCE_US)
            PreviewPhaseTrace.counter("dispatchedTargetUs", clamped)
            if (!isVisualRefresh) {
                _state.update { it.copy(phase = PreviewPhase.Seeking) }
            }
            audioEnvelopes.forEach { it.seekTo(clamped) }
            seekDispatchGate.dispatched(android.os.SystemClock.elapsedRealtime())
            p.seekTo(targetMillis)
        }
        startTicker()
        tick()
    }

    /** Each composed editor instance owns its lifecycle callbacks independently. */
    fun bindPlaybackOwner(owner: Any) {
        assertMainThread()
        playbackOwner = owner
    }

    fun releasePlaybackOwner(owner: Any) {
        assertMainThread()
        if (playbackOwner !== owner) return
        suspendPlayback(owner)
        playbackOwner = null
    }

    /** Release codecs/GL while another screen or native provider owns the UI. */
    fun suspendPlayback(owner: Any? = null) {
        assertMainThread()
        if (owner != null && playbackOwner !== owner) return
        if (suspended) return
        player?.pause(); tick()
        suspended = true
        previewBitmapCache.clear()
        sourceTailJobs.values.forEach { it.cancel() }; sourceTailJobs.clear()
        tickerJob?.cancel(); tickerJob = null
        releasePlayer()
    }

    /** Restore the same project/playhead, paused, when its screen returns. */
    fun resumePlayback(owner: Any? = null) {
        assertMainThread()
        if (owner != null && playbackOwner !== owner) return
        if (!suspended) return
        suspended = false
        val project = currentProject ?: return
        if (surfaceView != null || headlessOutput != null) {
            rebuild(project, _state.value.positionMicros, false)
        }
    }

    fun release() {
        assertMainThread()
        previewBitmapCache.clear()
        sourceTailJobs.values.forEach { it.cancel() }; sourceTailJobs.clear()
        tickerJob?.cancel()
        tickerJob = null
        detachSurface()
        releasePlayer()
        surfaceView = null
        headlessOutput = null
        lastSignature = null
        currentProject = null
        playbackOwner = null
        suspended = false
        restartPending = false
        reachedEnd = false
        _state.value = PreviewState(phase = PreviewPhase.Released)
    }

    // -- internals --

    // The duration is end-exclusive: there is no picture at that timestamp.
    // Decode the last output-frame interval while retaining the logical endpoint
    // as the user's timeline position. Seeking an empty EOS period cannot emit
    // the first-frame callback that releases queued consumer scrub requests.
    private data class TailKey(val uri: String, val startUs: Long, val endUs: Long)
    private val sourceTailTimes = linkedMapOf<TailKey, Long?>()
    private val sourceTailJobs = mutableMapOf<TailKey, Job>()

    private fun decodeTargetMicros(project: Project, target: Long): Long {
        val duration = project.timeline.renderedDurationMicros
        if (duration <= 0L || target < duration) return target
        val outputFrame = Timing.frameToMicros(Timing.microsToFrame(duration - 1L, project.frameRate), project.frameRate)
        val clip = project.timeline.videoClips.lastOrNull { duration - 1L in it.timelineStartMicros until it.timelineEndMicros }
            ?.takeUnless { it.isPhoto } ?: return outputFrame
        val media = project.mediaRefs.firstOrNull { it.id == clip.mediaRefId } ?: return outputFrame
        val key = TailKey(media.uri, clip.sourceStartMicros, clip.sourceEndMicros)
        if (!sourceTailTimes.containsKey(key) && sourceTailJobs[key] == null) {
            sourceTailJobs[key] = scope.launch {
                val tail = kotlinx.coroutines.withContext(Dispatchers.IO) { probeSourceTail(key) }
                sourceTailTimes[key] = tail
                if (sourceTailTimes.size > 32) sourceTailTimes.remove(sourceTailTimes.keys.first())
                sourceTailJobs.remove(key)
                // Endpoint refinement belongs only to this same source/range and logical end.
                val current = currentProject
                if (current?.timeline?.videoClips?.any { it.id == clip.id && it.mediaRefId == clip.mediaRefId &&
                        it.sourceStartMicros == key.startUs && it.sourceEndMicros == key.endUs } == true &&
                    retainedPositionMicros() >= current.timeline.renderedDurationMicros && tail != null && player != null) {
                    visualRefreshPending = true
                    seekForScrub(current.timeline.renderedDurationMicros)
                }
            }
        }
        // A 60 fps project can contain a 30 fps source: its last output interval
        // may contain no new source sample. Start no later than the actual tail.
        val sourceFrame = sourceTailTimes[key]?.let(clip::timelineTimeAt) ?: return outputFrame
        return minOf(outputFrame, sourceFrame)
    }

    /** Bounded metadata scan on IO; timeline gestures never open/scan media on the main thread. */
    private fun probeSourceTail(key: TailKey): Long? {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(context, android.net.Uri.parse(key.uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            if (track == null) null else {
                extractor.selectTrack(track)
                extractor.seekTo((key.endUs - 1L).coerceAtLeast(0L), android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var latest: Long? = null
                var pastEnd = 0
                var scanned = 0
                while (extractor.sampleTime >= 0L && pastEnd < 32 && scanned < 4096) {
                    val pts = extractor.sampleTime
                    if (pts in key.startUs until key.endUs) {
                        latest = maxOf(latest ?: pts, pts)
                        pastEnd = 0
                    } else if (pts >= key.endUs) pastEnd++
                    scanned++
                    extractor.advance()
                }
                PreviewPhaseTrace.counter("endpointTailScannedSamples", scanned.toLong())
                if (scanned >= 4096) null else latest
            }
        } catch (_: Exception) {
            PreviewPhaseTrace.counter("endpointTailProbeFailed", 1L)
            null // The normal decoder still owns reporting media failures.
        } finally { extractor.release() }
    }


    private fun rebuild(project: Project, resumeAtMicros: Long, resumePlaying: Boolean, recoveryAttempt: Int = 0) {
        PreviewPhaseTrace.counter("graphBuildTargetUs", resumeAtMicros)
        tracePosition("rebuild target=$resumeAtMicros playing=$resumePlaying overlays=${project.timeline.overlayClips.size}")
        try { releasePlayer() } catch (failure: RuntimeException) {
            _state.update { it.copy(phase = PreviewPhase.FatalError,
                error = PreviewError("Could not release preview resources safely.", cause = failure, recoverable = false)) }
            return
        }
        restartPending = false
        reachedEnd = false
        _state.update { it.copy(error = null, phase = if (recoveryAttempt > 0) PreviewPhase.Recovering else if (rebuildCount == 0) PreviewPhase.Preparing else PreviewPhase.Reconfiguring) }
        lastRebuildTiming = null
        // The picture-and-audio length, not totalDurationMicros: a text overlay
        // placed past the last video frame must not stretch the enforced end and
        // leave a black tail (the release-blocking duration bug).
        val trackDurationMicros = project.timeline.renderedDurationMicros
        if (trackDurationMicros <= 0L) {
            _state.value = PreviewState()
            return
        }

        PreviewPhaseTrace.identity(this, "graphBuildStart", -1, resumeAtMicros, "project=" + project.id.hashCode())
        val buildStart = android.os.SystemClock.elapsedRealtimeNanos()
        val variant = try {
            compositionBuilder.buildForPreview(project)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Preview composition build failed", t)
            _state.update {
                it.copy(
                    hasComposition = false,
                    isPlaying = false,
                    playRequested = false,
                    phase = PreviewPhase.FatalError,
                    error = PreviewError("Could not build the preview: ${t.message}", cause = t, recoverable = false),
                )
            }
            return
        }

        if (variant.composition.sequences.isEmpty() || trackDurationMicros <= 0L) {
            _state.update {
                PreviewState(
                    durationMicros = trackDurationMicros,
                    omittedAudioClipIds = variant.omittedAudioClipIds,
                )
            }
            return
        }

        val buildEnd = android.os.SystemClock.elapsedRealtimeNanos()
        PreviewPhaseTrace.identity(this, "renderPlanBuilt", -1, resumeAtMicros, "buildNs=" + (buildEnd-buildStart) + " plan=" + variant.renderState?.plan.hashCode())
        PreviewPhaseTrace.counter("compositionBuildDurationNs", buildEnd - buildStart)
        activeRenderState = variant.renderState
        audioEnvelopes = variant.audioEnvelopes
        audioEnvelopes.forEach { it.seekTo(resumeAtMicros) }
        var initializingPlayer: PatchedCompositionPlayer? = null
        val newPlayer = try {
            PatchedCompositionPlayer.Builder(context)
            .setTransitionClippingEnabled(project.timeline.transitions.isNotEmpty()).apply {
                verificationAudioSink?.let(::setAudioSink)
                val bitmaps = androidx.media3.datasource.DataSourceBitmapLoader(context)
                setExternalImageLoader { com.google.common.util.concurrent.Futures.immediateVoidFuture() }
                setImageDecoderFactory(androidx.media3.exoplayer.image.ExternallyLoadedImageDecoder.Factory { request ->
                    val tracing = android.os.Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled()
                    val started = if (tracing) android.os.SystemClock.elapsedRealtimeNanos() else 0L
                    val prefix = "image.$buildStart"
                    if (tracing) PreviewPhaseTrace.counter("$prefix.requestedBootNs", started)
                    val future = previewBitmapCache.load(bitmaps, request.uri)
                    if (tracing) {
                        PreviewPhaseTrace.counter("$prefix.resourceIdentity", request.uri.hashCode().toLong())
                        future.addListener({
                            val completed = android.os.SystemClock.elapsedRealtimeNanos()
                            // Completion alone does not establish successful decoding;
                            // the graph's accepted bitmap observation does that.
                            PreviewPhaseTrace.counter("$prefix.completedBootNs", completed)
                            PreviewPhaseTrace.counter("$prefix.loadDurationNs", completed - started)
                        }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
                    }
                    future
                })
                val previewSettings = variant.overlayCompositorSettings
                    ?: androidx.media3.effect.VideoCompositorSettings.DEFAULT
                run { val settings = previewSettings
                    // Use the preview cancellation/GL completion contract for every
                    // visual session, including one input. Export keeps its own graph.
                    setPreviewingVideoGraphFactory(
                        variant.overlayCompositorSettings?.let { verificationGraphFactory?.invoke(it) }
                            ?: com.videoeditor.core.media.composition.PreviewingMultipleInputVideoGraph.Factory(settings),
                    )
                }
            }.build().also { cp ->
                initializingPlayer = cp
                PreviewPhaseTrace.counter("player.${System.identityHashCode(cp)}.imageGeneration", buildStart)
                playerListener = listenerFor(cp).also(cp::addListener)
                surfaceView?.let { cp.setVideoSurfaceView(it) }
                headlessOutput?.let { (surface, size) -> cp.setVideoSurface(surface, size) }
                cp.setComposition(variant.composition)
            }
        } catch (t: Throwable) {
            initializingPlayer?.let { failed ->
                try { playerListener?.let(failed::removeListener); failed.release() }
                catch (cleanup: RuntimeException) {
                    t.addSuppressed(cleanup)
                    android.util.Log.e(TAG, "Failed preview construction cleanup failed", cleanup)
                }
            }
            android.util.Log.e(TAG, "PatchedCompositionPlayer construction failed", t)
            _state.update {
                it.copy(
                    hasComposition = false,
                    phase = PreviewPhase.FatalError,
                    error = PreviewError(
                        "Could not start the preview: ${t.message}", cause = t,
                    ),
                )
            }
            return
        }
        player = newPlayer
        PreviewPhaseTrace.counter("playerIdentity", System.identityHashCode(newPlayer).toLong())
        activePlayerCount = 1
        rebuildCount++
        if (android.os.Build.VERSION.SDK_INT >= 29) android.os.Trace.setCounter("Preview.rebuildCount", rebuildCount.toLong())

        recoveryOutputAnchorUs = resumeAtMicros.takeIf { recoveryAttempt > 0 }
        initialFrameReady = project.timeline.videoClips.isEmpty() || (surfaceView == null && headlessOutput == null)
        val resumeAt = resumeAtMicros.coerceIn(0L, trackDurationMicros)
        graphRestoreTargetMicros = resumeAt
        // Publish composition and its requested position together. Main-thread
        // collectors may synchronously feed edits back into this controller.
        _state.update {
            it.copy(
                hasComposition = true,
                positionMicros = resumeAt,
                durationMicros = trackDurationMicros,
                omittedAudioClipIds = variant.omittedAudioClipIds,
            )
        }

        val decodeAt = decodeTargetMicros(project, resumeAt)
        reachedEnd = resumeAt >= trackDurationMicros
        PreviewPhaseTrace.counter("graphDecodeTargetUs", decodeAt)
        // All graphs require real submitted output, including a surface that
        // becomes valid after preparation. Optimistic time and READY are not frame evidence.
        if (hasValidVideoOutput()) {
            graphOutputDeadline = GraphOutputDeadline(newPlayer,
                Timing.millisToMicros(Timing.microsToMillis(decodeAt)), recoveryAttempt,
                android.os.SystemClock.elapsedRealtime())
        }
        if (decodeAt > 0L) newPlayer.seekTo(Timing.microsToMillis(decodeAt))
        newPlayer.prepare()
        if (resumePlaying && !reachedEnd) newPlayer.play()
        lastRebuildTiming = RebuildTiming(buildEnd - buildStart, android.os.SystemClock.elapsedRealtimeNanos() - buildEnd)
        startTicker()
        tick()
    }

    private fun releasePlayer() {
        graphOutputDeadline = null
        playbackOutputWatchdog.reset()
        seekDispatchGate.reset()
        activeRenderState = null
        audioEnvelopes = emptyList()
        cancelScrubSeek()
        initialFrameReady = true
        graphRestoreTargetMicros = null
        val p = player ?: return
        PreviewPhaseTrace.counter("releasedPlayerIdentity", System.identityHashCode(p).toLong())
        PreviewPhaseTrace.counter("releaseGeneration", rebuildCount.toLong())
        player = null
        activePlayerCount = 0
        try {
            playerListener?.let(p::removeListener)
            playerListener = null
            p.release()
        } catch (failure: RuntimeException) {
            android.util.Log.e(TAG, "Retired preview failed to release", failure)
            _state.update { it.copy(error = PreviewError("Could not release the preview safely.")) }
            throw failure
        }
        _state.update { it.copy(isPlaying = false, playRequested = false, isBuffering = false, hasComposition = false) }
    }

    private fun teardown() {
        previewBitmapCache.clear()
        releasePlayer()
        lastSignature = null
        currentProject = null
        _state.value = PreviewState()
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            var lastSampleFrameNanos = 0L
            while (isActive) {
                val frameNanos = awaitDisplayFrame()
                if (lastSampleFrameNanos == 0L || frameNanos - lastSampleFrameNanos >= TICK_INTERVAL_MS * 1_000_000L) {
                    // Read the real player at most ~30Hz, at the beginning of a
                    // display frame. A free-running delay can publish late in
                    // the frame and consume its remaining rendering deadline.
                    tick()
                    lastSampleFrameNanos = frameNanos
                }
                val p = player ?: break
                if (!p.playWhenReady && p.playbackState != Player.STATE_BUFFERING && pendingScrubMicros == null && graphOutputDeadline == null) break
            }
        }
    }

    private suspend fun awaitDisplayFrame(): Long = suspendCancellableCoroutine { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { frameNanos ->
            if (continuation.isActive) continuation.resume(frameNanos)
        }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }

    private fun hasValidVideoOutput(): Boolean = surfaceView?.holder?.surface?.isValid == true ||
        headlessOutput?.first?.isValid == true

    /** Returns true when the tick's original player was retired. Never retry recursively. */
    private fun checkGraphOutputDeadline(): Boolean {
        val deadline = graphOutputDeadline ?: return false
        val active = player
        if (!hasValidVideoOutput() || active?.playerError != null || _state.value.error != null) {
            graphOutputDeadline = null
            return false
        }
        when (deadline.observe(active, active?.lastRenderedProjectTimeUs ?: androidx.media3.common.C.TIME_UNSET,
            android.os.SystemClock.elapsedRealtime())) {
            GraphOutputDeadline.Result.Waiting -> return false
            GraphOutputDeadline.Result.Delivered -> {
                // Real output from the current seek generation is stronger than
                // EVENT_RENDERED_FIRST_FRAME, which need not re-fire for a paused refresh.
                initialFrameReady = true
                PreviewPhaseTrace.identity(this, "previewReady", -1, deadline.targetUs, "submittedUs=" + active?.lastRenderedProjectTimeUs)
                // One replacement picture does not prove a playback stall recovered.
                // Keep the incident budget until real output advances through half a second.
                if (active?.playWhenReady != true) { failureRecoveryCount = 0; recoveryOutputAnchorUs = null }
                graphOutputDeadline = null
                return false
            }
            GraphOutputDeadline.Result.Retired -> { graphOutputDeadline = null; return false }
            GraphOutputDeadline.Result.Retry -> {
                val project = currentProject ?: return false
                val target = retainedPositionMicros()
                val resume = if (pendingScrubMicros != null) pendingSeekMayResume else active?.playWhenReady == true
                graphOutputDeadline = null
                failureRecoveryCount++
                PreviewPhaseTrace.counter("graphStartupRecovery", rebuildCount.toLong())
                PreviewPhaseTrace.counter("acceptedTargetUs", target)
                PreviewPhaseTrace.counter("seekPlayerIdentity", System.identityHashCode(active).toLong())
                PreviewPhaseTrace.counter("dispatchedTargetUs", target)
                android.util.Log.w(TAG, "No submitted graph output; retiring generation $rebuildCount, retrying target $target; ${active?.frameDiagnostics}")
                // rebuild synchronously releases only the active owner before
                // allocating its replacement. The pending target and transport
                // intent have been captured before release cancels queued seeks.
                rebuild(project, target, resume, recoveryAttempt = 1)
                return true
            }
            GraphOutputDeadline.Result.Failed -> {
                val target = retainedPositionMicros()
                val diagnostic = active?.frameDiagnostics ?: "graph unavailable"
                graphOutputDeadline = null
                releasePlayer()
                _state.update { it.copy(positionMicros = target, isPlaying = false, playRequested = false,
                    phase = PreviewPhase.RecoverableError,
                    error = PreviewError("Preview produced no frame after one recovery attempt. Retry preview to restore the current edit.",
                        cause = IllegalStateException("No graph output for target $target us after bounded recovery; $diagnostic"))) }
                return true
            }
        }
    }

    private fun tick() {
        if (tickInProgress) return
        tickInProgress = true
        try {
            val active = player
            if (active != null && !initialFrameReady && graphOutputDeadline == null && hasValidVideoOutput()) {
                val target = graphRestoreTargetMicros ?: retainedPositionMicros()
                val decodeAt = currentProject?.let { decodeTargetMicros(it, target) } ?: target
                graphOutputDeadline = GraphOutputDeadline(active, Timing.millisToMicros(Timing.microsToMillis(decodeAt)),
                    failureRecoveryCount, android.os.SystemClock.elapsedRealtime())
            }
            recoveryOutputAnchorUs?.let { anchor ->
                if (active != null && active.lastRenderedProjectTimeUs >= anchor + 500_000L) {
                    failureRecoveryCount = 0; recoveryOutputAnchorUs = null
                }
            }
            if (active != null && active.playWhenReady && hasValidVideoOutput() && initialFrameReady &&
                graphOutputDeadline == null && pendingScrubMicros == null && !reachedEnd && _state.value.error == null) {
                val nowMs = android.os.SystemClock.elapsedRealtime()
                val outputUs = active.lastRenderedProjectTimeUs
                if (playbackOutputWatchdog.stalled(active, outputUs, nowMs)) {
                    // Startup success must not disable protection against a later transition/EOS stall.
                    graphOutputDeadline = GraphOutputDeadline(active, (outputUs + 1L).coerceAtLeast(0L),
                        failureRecoveryCount, nowMs - 4_000L)
                }
            } else playbackOutputWatchdog.reset()
            if (graphOutputDeadline != null) active?.pumpPendingVideoFrame()
            if (checkGraphOutputDeadline()) return
            if (pendingScrubMicros != null && canDispatchPendingLanding()) {
                flushScrubSeek()
                return // Consume at most one coalesced seek in this display tick.
            }
            val p = player ?: return
            val durationMicros = _state.value.durationMicros
            val rawPositionMicros = Timing.millisToMicros(p.currentPosition.coerceAtLeast(0L))
            graphRestoreTargetMicros?.let { target ->
                val decodeTarget = currentProject?.let { decodeTargetMicros(it, target) } ?: target
                val outputArrived = (surfaceView == null && headlessOutput == null) ||
                    p.lastRenderedProjectTimeUs >= (decodeTarget - 100_000).coerceAtLeast(0)
                if (initialFrameReady && p.playbackState == Player.STATE_READY &&
                    rawPositionMicros >= Timing.millisToMicros(Timing.microsToMillis(decodeTarget)) && outputArrived) {
                    graphRestoreTargetMicros = null
                }
            }

            // Media3 1.5.1's PatchedCompositionPlayer does not enforce the end of the
            // composition: once playback runs past the last clip it keeps reporting
            // ever-increasing positions and buffered times, never fires
            // STATE_ENDED, and keeps isPlaying true indefinitely (verified on-device:
            // a 15s composition reported 44s and climbing). The project model is
            // authoritative for duration, so the controller enforces the end itself:
            // clamp the reported position and stop playback at the composition end.
            if (restartPending) {
                // The seek-to-start has landed once the position is back inside the
                // composition; resume normal end-enforcement from then on.
                if (durationMicros <= 0L || rawPositionMicros < durationMicros) restartPending = false
            }
            val clockAtEnd = !restartPending && pendingScrubMicros == null && durationMicros > 0L &&
                (reachedEnd || rawPositionMicros >= durationMicros || p.playbackState == Player.STATE_ENDED)
            // A multi-input clock can reach the edit end while the GPU still owns
            // due frames. Keep rendering until the primary renderer confirms its
            // actual final frame, rather than pausing upstream work prematurely.
            val needsFinalVideoDrain = clockAtEnd && p.playWhenReady &&
                currentProject?.timeline?.let { it.transitions.isNotEmpty() || it.overlayClips.isNotEmpty() } == true &&
                (surfaceView != null || headlessOutput != null) &&
                !p.hasRenderedPrimaryFinalOutput() && p.playbackState != Player.STATE_ENDED
            val drainNowMs = android.os.SystemClock.elapsedRealtime()
            if (needsFinalVideoDrain && finalVideoDrainStartedMs == null) finalVideoDrainStartedMs = drainNowMs
            val drainTimedOut = needsFinalVideoDrain && drainNowMs - (finalVideoDrainStartedMs ?: drainNowMs) >= 2_000
            if (!needsFinalVideoDrain) finalVideoDrainStartedMs = null
            val atEnd = clockAtEnd && (!needsFinalVideoDrain || drainTimedOut)
            if (atEnd) {
                reachedEnd = true
                if (p.isPlaying) {
                    p.pause()
                    // Multi-input EOS retains the last displayed frame. Seeking its
                    // live upstream processors would violate compositor ownership.
                    if (currentProject?.timeline?.let { it.overlayClips.isEmpty() && it.transitions.isEmpty() } != false) {
                        p.seekTo(Timing.microsToMillis(durationMicros))
                    }
                }
            }
            val reportedPosition = pendingScrubMicros ?: graphRestoreTargetMicros ?: if (atEnd) durationMicros else rawPositionMicros
            val positionMicros =
                if (durationMicros > 0L) reportedPosition.coerceIn(0L, durationMicros) else reportedPosition

            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.os.Trace.setCounter("Preview.playerPositionUs", rawPositionMicros)
                android.os.Trace.setCounter("Preview.publishedPositionUs", positionMicros)
            }
            _state.update {
                it.copy(
                    phase = when {
                        it.error != null -> if (it.error.recoverable) PreviewPhase.RecoverableError else PreviewPhase.FatalError
                        !initialFrameReady && it.phase == PreviewPhase.Recovering -> PreviewPhase.Recovering
                        pendingScrubMicros != null || !initialFrameReady -> PreviewPhase.Seeking
                        p.playbackState == Player.STATE_BUFFERING -> PreviewPhase.Preparing
                        p.isPlaying && !atEnd -> PreviewPhase.Playing
                        p.playWhenReady && !atEnd -> PreviewPhase.Ready
                        else -> PreviewPhase.Paused
                    },
                    actualPositionMicros = rawPositionMicros.coerceIn(0L, durationMicros.coerceAtLeast(0L)),
                    requestedPositionMicros = pendingScrubMicros,
                    positionMicros = positionMicros,
                    isPlaying = p.isPlaying && initialFrameReady && !atEnd,
                    playRequested = p.playWhenReady && !atEnd && it.error == null,
                    isBuffering = p.playbackState == Player.STATE_BUFFERING,
                    error = if (drainTimedOut) PreviewError("Preview could not render its final video frame.") else it.error,
                )
            }
        } finally {
            tickInProgress = false
        }
    }

    /** Renders a throwable as "Class: message (cause: ...)" for diagnostics. */
    private fun describe(t: Throwable): String = buildString {
        append(t.javaClass.simpleName)
        t.message?.let { append(": ").append(it) }
        t.cause?.let { append(" (cause: ").append(describe(it)).append(")") }
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        -> "A media file could not be read. It may have been moved or deleted."

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        -> "This device cannot decode one of the clips."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> "A media file is corrupted or uses an unsupported format."

        else -> "Preview stopped (${error.errorCodeName}). Retry preview to restore the current edit."
    }

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CompositionPreviewController must be used on the main thread"
        }
    }

    /** The project fields the composition is derived from, for rebuild dedup. */
    private data class CompositionSignature(
        val videoClips: List<Clip>,
        val audioClips: List<AudioClip>,
        val textOverlays: List<com.videoeditor.core.model.TextOverlay>,
        val stickers: List<com.videoeditor.core.model.StickerClip>,
        val overlayClips: List<com.videoeditor.core.model.OverlayClip>,
        val transitions: List<com.videoeditor.core.model.Transition>,
        val canvas: CanvasSettings,
        val mediaRefs: List<com.videoeditor.core.model.MediaRef>,
    ) {
        companion object {
            fun of(project: Project) = CompositionSignature(
                videoClips = project.timeline.videoClips,
                audioClips = project.timeline.audioClips,
                textOverlays = project.timeline.textOverlays,
                stickers = project.timeline.stickers,
                overlayClips = project.timeline.overlayClips,
                transitions = project.timeline.transitions,
                canvas = project.canvas,
                mediaRefs = project.mediaRefs,
            )
        }
    }

    private companion object {
        const val TAG = "CompositionPreview"

        /**
         * Position polling interval. PatchedCompositionPlayer reports position in
         * milliseconds and does not push continuous position events, so the
         * engine samples at ~30Hz while playback/buffering/queued seeks are active.
         * Paused idle players rely on listener events; release/suspend cancels the job.
         */
        const val TICK_INTERVAL_MS = 33L
    }
}
