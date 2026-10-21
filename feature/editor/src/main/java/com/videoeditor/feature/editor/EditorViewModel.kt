package com.videoeditor.feature.editor

import android.content.Context
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.videoeditor.core.common.di.IoDispatcher
import com.videoeditor.core.common.di.MainDispatcher
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.common.util.Ids
import com.videoeditor.core.database.repository.ProjectRepository
import com.videoeditor.core.media.MediaMetadataExtractor
import com.videoeditor.core.media.preview.CompositionPreviewController
import com.videoeditor.core.media.preview.PreviewState
import com.videoeditor.core.media.timeline.TimelineMediaRepository
import com.videoeditor.core.media.waveform.WaveformPeaks
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Transition
import com.videoeditor.core.model.resolveTransitions
import com.videoeditor.core.model.TextOverlay
import com.videoeditor.editing.commands.AssetCommands
import com.videoeditor.editing.commands.TimelineCommands
import com.videoeditor.editing.history.EditCommand
import com.videoeditor.editing.history.EditHistory
import com.videoeditor.editing.timeline.SnapEngine
import com.videoeditor.editing.timeline.TimelineContent
import com.videoeditor.editing.timeline.TimelineGesture
import com.videoeditor.editing.timeline.TimelineGestureState
import com.videoeditor.editing.timeline.TimelineHit
import com.videoeditor.editing.timeline.TimelineHitTester
import com.videoeditor.editing.timeline.TimelineNav
import com.videoeditor.editing.timeline.TimelineSelection
import com.videoeditor.editing.timeline.TimelineViewport
import com.videoeditor.editing.timeline.TrimMath
import com.videoeditor.editing.timeline.selectedAudioClipId
import com.videoeditor.editing.timeline.selectedStickerId
import com.videoeditor.editing.timeline.selectedTextOverlayId
import com.videoeditor.editing.timeline.videoClipId
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The decoded media assets the timeline renders, keyed by clip id. */
data class TimelineAssets(
    /** Video clip id → thumbnail strip covering its current source range. */
    val strips: Map<String, ClipStrip> = emptyMap(),
    /** Audio clip id → waveform peaks covering its current source range. */
    val waveforms: Map<String, WaveformPeaks> = emptyMap(),
)

/**
 * One video clip's thumbnail strip plus the source range it was decoded for,
 * so the renderer can tell a stale strip (trim changed mid-gesture) from a
 * current one and fall back to a placeholder.
 */
data class ClipStrip(
    val frames: List<Bitmap?>,
    val sourceStartMicros: Long,
    val sourceEndMicros: Long,
    val thumbHeightPx: Int,
    val sampledSourceStartMicros: Long = sourceStartMicros,
    val sampledSourceEndMicros: Long = sourceEndMicros,
) {
    // Android bitmap wrappers are stable across draw frames.
    val images = frames.map { it?.asImageBitmap() }
}

data class EditorUiState(
    val project: Project? = null,
    val selection: TimelineSelection = TimelineSelection.None,
    val playheadMicros: Long = 0L,
    val isPlaying: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isLoading: Boolean = true,
    val missingMediaIds: List<String> = emptyList(),
    val preview: PreviewState = PreviewState(),
    // Timeline viewport. zoomPixelsPerMicro == 0 means "not yet initialized";
    // it is set to fit the project once the layout reports a width.
    val zoomPixelsPerMicro: Float = 0f,
    val scrollXPx: Float = 0f,
    val viewportWidthPx: Float = 0f,
    val playheadXpx: Float = 0f,
    /** The single in-flight timeline gesture (empty when idle). */
    val gesture: TimelineGesture = TimelineGesture.Idle,
    /** A transient message for invalid operations (bad split point etc.). */
    val userMessage: String? = null,
    val isFullscreenPreview: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
) {
    val selectedOverlayId: String? get() = (selection as? TimelineSelection.Overlay)?.overlayId

    /**
     * The coordinate system for the current state, or null until a project is
     * loaded and the layout has reported a size. Both the UI and the ViewModel
     * build their viewports from these same values, so they cannot disagree.
     */
    fun viewport(): TimelineViewport? {
        val duration = project?.timeline?.renderedDurationMicros ?: return null
        if (viewportWidthPx <= 0f || zoomPixelsPerMicro <= 0f) return null
        return TimelineViewport(
            durationMicros = duration,
            pixelsPerMicro = zoomPixelsPerMicro,
            viewportWidthPx = viewportWidthPx,
            playheadXpx = playheadXpx,
        )
    }
}

/**
 * Owns the editor's state: the project document, the timeline viewport
 * (zoom/scroll/playhead), the gesture state machine, and the preview engine.
 *
 * Design rules this class enforces:
 *  - The project model is authoritative; gestures mutate transient state and
 *    only commit an [EditCommand] through [EditHistory] when they complete.
 *  - Viewport changes (zoom, scroll, selection) never rebuild the composition
 *    or the player — only structural commits call
 *    [CompositionPreviewController.setProject], which itself skips no-op
 *    rebuilds.
 *  - All coordinate math goes through [TimelineViewport]/[TrimMath]/
 *    [SnapEngine]/[TimelineNav] — the same pure functions the unit tests
 *    cover — never ad-hoc arithmetic here or in composables.
 *  - While the user scrubs or drags, playback updates are ignored for
 *    playhead/scroll purposes so the engine cannot fight the gesture.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val projectRepository: ProjectRepository,
    private val metadataExtractor: MediaMetadataExtractor,
    private val timelineMedia: TimelineMediaRepository,
    previewControllerFactory: CompositionPreviewController.Factory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @com.videoeditor.core.common.di.ApplicationScope
    private val applicationScope: kotlinx.coroutines.CoroutineScope,
    private val preferencesRepository: com.videoeditor.core.datastore.UserPreferencesRepository? = null,
    private val duckingAnalysis: com.videoeditor.core.media.audio.DuckingAnalysisService,
) : ViewModel() {

    /**
     * Autosave jobs, tracked so tests can await pending writes. Production
     * needs no await: saves run on the application scope and outlive the
     * screen, so closing the editor right after an edit cannot lose it.
     */
    private var autosaveEnabled = true
    private val saveMutex = kotlinx.coroutines.sync.Mutex()
    private val pendingSaves = mutableListOf<kotlinx.coroutines.Job>()

    /**
     * A pending coalesced preview sync. Text edits fire on every keystroke and
     * each would rebuild the whole CompositionPlayer (expensive); they are
     * debounced into a single rebuild after the user pauses. Structural edits
     * cancel any pending sync and rebuild immediately.
     */
    private var previewSyncJob: kotlinx.coroutines.Job? = null

    private val history = EditHistory()
    private var transitionPreviewReturnUs: Long? = null
    private var transitionPreviewJob: kotlinx.coroutines.Job? = null
    private val hitTester = TimelineHitTester()

    /**
     * The composition preview engine. Owned here (not by Compose) so playback
     * logic stays out of the UI layer; the screen attaches a surface and calls
     * the transport methods below.
     */
    val previewController: CompositionPreviewController = previewControllerFactory.create()

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()
    // Screen chrome observes structural/transport changes. Fast coordinates are read
    // by Canvas in its draw phase; the timer composes only when its text changes.
    val screenState = uiState.map { state ->
        state.copy(playheadMicros = 0L, scrollXPx = 0f, preview = state.preview.copy(
            positionMicros = 0L, actualPositionMicros = 0L, requestedPositionMicros = null))
    }.distinctUntilChanged()
    val playbackTime = uiState.map {
        formatEditorTimecode(it.playheadMicros)
    }.distinctUntilChanged()

    private val _assets = MutableStateFlow(TimelineAssets())
    val assets: StateFlow<TimelineAssets> = _assets.asStateFlow()

    /** One-gesture-at-a-time state; every begin goes through [tryBeginGesture]. */
    private var gestureState = TimelineGestureState()

    init {
        preferencesRepository?.let { repository ->
            viewModelScope.launch { repository.preferences.collect { autosaveEnabled = it.autosaveEnabled } }
        }
        // Mirror engine state into the UI state; viewModelScope is Main-only so
        // the controller's main-thread contract holds.
        viewModelScope.launch(mainDispatcher) {
            previewController.state.collect { preview ->
                _uiState.update { state ->
                    // While the user owns the playhead (scrubbing) or a drag is
                    // in flight, the engine's position must not move the UI —
                    // it is still catching up to the gesture's seeks.
                    val userOwnsPlayhead = state.gesture !is TimelineGesture.Idle
                    val updated = state.copy(
                        preview = preview,
                        isPlaying = preview.isPlaying,
                        playheadMicros = if (preview.hasComposition && !userOwnsPlayhead) {
                            preview.positionMicros
                        } else {
                            state.playheadMicros
                        },
                    )
                    val viewport = updated.viewport()
                    // Paused metadata/buffering callbacks must not undo intentional
                    // viewport framing (for example focusOnSelectedClip for trimming).
                    val followClock = preview.positionMicros != state.preview.positionMicros ||
                        preview.isPlaying || state.preview.isPlaying || !state.preview.hasComposition ||
                        preview.durationMicros != state.preview.durationMicros
                    if (preview.hasComposition && !userOwnsPlayhead && followClock && viewport != null) {
                        val scroll = viewport.scrollXForFollow(updated.playheadMicros)
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            android.os.Trace.setCounter("Timeline.displayedPositionUs", updated.playheadMicros)
                            android.os.Trace.setCounter("Timeline.anchorPositionUs", viewport.playheadTimeMicros(scroll))
                        }
                        updated.copy(scrollXPx = scroll)
                    } else updated
                }
            }
        }
    }

    fun loadProject(projectId: String) {
        // Configuration/fullscreen recreation can restart the screen effect
        // while retaining this ViewModel. Reloading the same document would
        // clear history/assets/preview, but the unchanged controller signature
        // would emit no replacement state. A newly opened editor has no project.
        if (_uiState.value.project?.id == projectId) return
        viewModelScope.launch {
            com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "projectLoadStart", -1, 0, "project=" + projectId.hashCode())
            val doc = withContext(ioDispatcher) {
                projectRepository.loadDocument(projectId).getOrNull()?.let { metadataExtractor.resolveSpeedAudio(appContext, it) }
            }
            val missing = withContext(ioDispatcher) { doc?.let(::findMissingMedia).orEmpty() }
            com.videoeditor.core.media.preview.PreviewPhaseTrace.identity(this, "projectMediaResolved", -1, 0, "project=" + projectId.hashCode())
            history.clear()
            _uiState.update { current ->
                // Keep the layout-reported viewport size: onSizeChanged does
                // not re-fire when only the project changes, and a reset here
                // would leave the viewport null until the next layout pass.
                EditorUiState(
                    project = doc,
                    missingMediaIds = missing,
                    isLoading = doc == null,
                    viewportWidthPx = current.viewportWidthPx,
                    playheadXpx = current.playheadXpx,
                    zoomPixelsPerMicro = if (doc != null && current.viewportWidthPx > 0f)
                        TimelineViewport.initialZoomFor(doc.timeline.renderedDurationMicros, current.viewportWidthPx)
                    else 0f,
                )
            }
            gestureState = TimelineGestureState()
            _assets.value = TimelineAssets()
            previewController.setProject(doc.takeIf { missing.isEmpty() })
            initializeZoomIfReady()
        }
    }

    private fun findMissingMedia(project: Project): List<String> {
        val used = (project.timeline.videoClips.map { it.mediaRefId } +
            project.timeline.audioClips.map { it.mediaRefId } + project.timeline.overlayClips.map { it.mediaRefId }).toSet()
        return project.mediaRefs.filter { it.id in used }.filter { ref ->
        runCatching {
            appContext.contentResolver.openAssetFileDescriptor(Uri.parse(ref.uri), "r")
                ?.use { true } ?: false
        }.getOrDefault(false).not()
    }.map { it.id }
    }

    fun relinkFile(mediaRefId: String, rawUri: Uri) {
        viewModelScope.launch {
            val project = _uiState.value.project ?: return@launch
            val original = project.mediaRef(mediaRefId) ?: return@launch
            val uri = withContext(ioDispatcher) { ensureDurableUri(rawUri) }
                ?: run { showMessage("The source could not be retained. Choose a readable file and try again."); return@launch }
            val replacement = withContext(ioDispatcher) {
                metadataExtractor.extract(appContext, uri).getOrNull()
            }
            val requiredEnd = maxOf(
                project.timeline.videoClips.filter { it.mediaRefId == mediaRefId && !it.isPhoto }
                    .maxOfOrNull { it.sourceEndMicros } ?: 0L,
                project.timeline.audioClips.filter { it.mediaRefId == mediaRefId }
                    .maxOfOrNull { it.sourceEndMicros } ?: 0L,
                project.timeline.overlayClips.filter { it.mediaRefId == mediaRefId && !it.isPhoto }
                    .maxOfOrNull { it.sourceEndMicros } ?: 0L,
            )
            if (replacement == null || replacement.type != original.type ||
                (requiredEnd > 0L && (replacement.durationMicros ?: 0L) < requiredEnd)) {
                _uiState.update { it.copy(userMessage = "Choose a readable file of the same type that covers the original trims.") }
                return@launch
            }
            val updated = history.apply(project, AssetCommands.RelinkMedia(mediaRefId, replacement))
            val missing = withContext(ioDispatcher) { findMissingMedia(updated) }
            _uiState.update { it.copy(missingMediaIds = missing) }
            commit(updated)
        }
    }

    /** Called by the layout once the timeline knows its size. */
    fun onViewportSize(widthPx: Float, playheadXpx: Float) {
        _uiState.update { it.copy(viewportWidthPx = widthPx, playheadXpx = playheadXpx) }
        initializeZoomIfReady()
    }

    private fun initializeZoomIfReady() {
        _uiState.update { state ->
            val project = state.project ?: return@update state
            if (state.viewportWidthPx <= 0f) return@update state
            if (state.zoomPixelsPerMicro > 0f) {
                // Keep an existing zoom legal after duration/size changes.
                clampViewport(state)
            } else {
                // A project-aware starting zoom: short projects show in full at a
                // comfortable scale so clips are recognizable without the first
                // pinch; the Fit action still uses the fit-all minimum.
                val initialZoom = TimelineViewport.initialZoomFor(
                    project.timeline.renderedDurationMicros,
                    state.viewportWidthPx,
                )
                clampViewport(state.copy(zoomPixelsPerMicro = initialZoom))
            }
        }
    }

    // -- selection --

    fun select(selection: TimelineSelection) {
        _uiState.update { it.copy(selection = selection) }
    }

    fun clearSelection() = select(TimelineSelection.None)

    /** Routes a resolved timeline tap to selection. */
    fun onTap(hit: TimelineHit) {
        val selection = when (hit) {
            is TimelineHit.VideoClip -> TimelineSelection.Video(hit.clipId)
            is TimelineHit.AudioClip -> TimelineSelection.Audio(hit.audioClipId)
            is TimelineHit.TextClip -> TimelineSelection.Text(hit.overlayId)
            // Taps on the ruler or empty space clear the selection; taps that
            // land on a trim handle do not change it.
            is TimelineHit.Ruler, TimelineHit.Empty -> TimelineSelection.None
        }
        select(selection)
    }

    // -- transport --

    fun togglePlayPause() = previewController.togglePlayPause()

    /** Seeks and centers the playhead on screen. */
    fun seekTo(micros: Long) {
        val state = _uiState.value
        val duration = state.project?.timeline?.renderedDurationMicros ?: 0L
        val clamped = micros.coerceIn(0L, duration)
        previewController.seekTo(clamped)
        _uiState.update { current ->
            val viewport = current.viewport()
            val scroll = viewport?.scrollXForTime(clamped) ?: current.scrollXPx
            current.copy(playheadMicros = clamped, scrollXPx = scroll)
        }
    }

    fun skipToPreviousBoundary() {
        val state = _uiState.value
        val timeline = state.project?.timeline ?: return
        TimelineNav.adjacentBoundary(timeline, state.playheadMicros, forward = false)
            ?.let { seekTo(it) }
    }

    fun skipToNextBoundary() {
        val state = _uiState.value
        val timeline = state.project?.timeline ?: return
        TimelineNav.adjacentBoundary(timeline, state.playheadMicros, forward = true)
            ?.let { seekTo(it) }
    }

    /** Reconcile every idle player update, including pause, seek, end and replay. */
    private fun applyPlaybackFollow() {
        _uiState.update { state ->
            if (!state.preview.hasComposition || state.gesture !is TimelineGesture.Idle) return@update state
            val viewport = state.viewport() ?: return@update state
            // StateFlow collection can lag the synchronous final scrub seek.
            // Read its latest publication before surrendering gesture ownership.
            val position = previewController.state.value.positionMicros
            val scroll = viewport.scrollXForFollow(position)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.os.Trace.setCounter("Timeline.displayedPositionUs", position)
                android.os.Trace.setCounter("Timeline.anchorPositionUs", viewport.playheadTimeMicros(scroll))
            }
            if (scroll == state.scrollXPx && position == state.playheadMicros) state
            else state.copy(playheadMicros = position, scrollXPx = scroll)
        }
    }

    // -- gestures: the UI reports raw pixel motion; all math is centralized --

    private var flingJob: kotlinx.coroutines.Job? = null

    /**
     * Begins a gesture if none is active. Returns false when refused (another
     * gesture owns the timeline), so the UI can stop tracking that pointer
     * stream instead of sending motion into a gesture that never started.
     */
    private fun tryBeginGesture(next: TimelineGesture): Boolean {
        stopFling()
        val updated = gestureState.tryBegin(next)
        if (updated === gestureState && !gestureState.isIdle) return false
        gestureState = updated
        _uiState.update { it.copy(gesture = updated.gesture) }
        return !updated.isIdle
    }

    private fun updateGesture(transform: (TimelineGesture) -> TimelineGesture) {
        if (gestureState.isIdle) return
        gestureState = gestureState.update(transform)
        _uiState.update { it.copy(gesture = gestureState.gesture) }
    }

    private fun endGesture() {
        gestureState = gestureState.end()
        _uiState.update { it.copy(gesture = TimelineGesture.Idle) }
    }

    /** Pauses playback for the duration of an editing gesture. */
    private fun pauseForGesture() {
        // Buffering may have an active play request without isPlaying yet.
        // Every editing gesture cancels it, so READY cannot resume a scrub.
        previewController.pause()
    }

    // -- scrub & pan --

    fun beginScrub(): Boolean {
        stopFling()
        if (!tryBeginGesture(TimelineGesture.Scrolling(isScrubbing = true))) return false
        pauseForGesture()
        return true
    }

    /**
     * Moves the content by [dxPx] screen pixels (positive = finger right) and
     * seeks the playhead — now under the fixed playhead line — accordingly.
     */
    fun scrubBy(dxPx: Float) {
        if (gestureState.gesture !is TimelineGesture.Scrolling) return
        _uiState.update { state ->
            val viewport = state.viewport() ?: return@update state
            val scroll = viewport.clampScroll(state.scrollXPx - dxPx)
            val playhead = viewport.playheadTimeMicros(scroll)
            state.copy(scrollXPx = scroll, playheadMicros = playhead)
        }
        previewController.seekForScrub(_uiState.value.playheadMicros)
    }

    fun beginPan(): Boolean = tryBeginGesture(TimelineGesture.Scrolling(isScrubbing = false))

    fun panBy(dxPx: Float) {
        if (gestureState.gesture !is TimelineGesture.Scrolling) return
        _uiState.update { state ->
            val viewport = state.viewport() ?: return@update state
            state.copy(scrollXPx = viewport.clampScroll(state.scrollXPx - dxPx))
        }
    }

    fun endScroll() {
        previewController.finishScrubSeek()
        endGesture()
        applyPlaybackFollow()
    }

    /** Continues scrolling smoothly after the user flings the timeline. */
    private fun stopFling() {
        val job = flingJob ?: return
        flingJob = null
        job.cancel()
        if (gestureState.gesture is TimelineGesture.Scrolling) endScroll()
    }

    fun fling(velocityPxPerSec: Float) {
        stopFling()
        if (gestureState.isIdle && !beginScrub()) return
        if (kotlin.math.abs(velocityPxPerSec) < 150f) {
            endScroll()
            return
        }
        flingJob = viewModelScope.launch(mainDispatcher) {
            var lastTime = android.os.SystemClock.uptimeMillis()
            var currentVelocity = velocityPxPerSec.coerceIn(-8000f, 8000f)
            val friction = 0.94f
            val owner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            try {
                while (kotlin.math.abs(currentVelocity) > 60f) {
                    kotlinx.coroutines.delay(16)
                    val now = android.os.SystemClock.uptimeMillis()
                    val dt = ((now - lastTime).coerceIn(8L, 32L) / 1000f)
                    lastTime = now
                    val dx = currentVelocity * dt
                    val previousScroll = _uiState.value.scrollXPx
                    scrubBy(dx)
                    if (_uiState.value.scrollXPx == previousScroll) break
                    currentVelocity *= Math.pow(friction.toDouble(), (dt * 60f).toDouble()).toFloat()
                }
            } finally {
                // A canceled fling must never end a newer pointer gesture.
                if (flingJob === owner) { flingJob = null; endScroll() }
            }
        }
    }

    // -- zoom --
    private var zoomFocalTimeMicros = 0L

    fun beginZoom(focalScreenXPx: Float): Boolean {
        if (!tryBeginGesture(TimelineGesture.Zooming(0f, focalScreenXPx))) return false
        pauseForGesture()
        val state = _uiState.value
        zoomFocalTimeMicros = state.viewport()?.timeAtScreenX(state.scrollXPx, focalScreenXPx) ?: state.playheadMicros
        return true
    }

    /**
     * Applies one pinch event: scales the zoom around the current centroid,
     * keeping the timeline instant under the centroid fixed. Zoom never
     * touches the composition or the player.
     */
    fun zoomBy(scale: Float, centroidScreenXPx: Float) {
        if (gestureState.gesture !is TimelineGesture.Zooming || !scale.isFinite() || scale <= 0f) return
        _uiState.update { state ->
            val viewport = state.viewport() ?: return@update state
            val (minZoom, maxZoom) = zoomLimitsFor(state)
            val newZoom = (state.zoomPixelsPerMicro * scale).coerceIn(minZoom, maxZoom)
            val newScroll = viewport.scrollXForZoomPan(newZoom, zoomFocalTimeMicros, centroidScreenXPx)
            val next = viewport.copy(pixelsPerMicro = newZoom)
            state.copy(zoomPixelsPerMicro = newZoom, scrollXPx = newScroll,
                playheadMicros = next.playheadTimeMicros(newScroll))
        }
    }

    fun endZoom() {
        if (gestureState.gesture !is TimelineGesture.Zooming) return
        previewController.seekForScrub(_uiState.value.playheadMicros)
        previewController.finishScrubSeek()
        endGesture()
        applyPlaybackFollow()
    }

    /** Zooms so the whole project fits the viewport. */
    /** Navigation zoom shares pinch math and keeps the current playhead anchored. */
    fun stepZoom(scale: Float) {
        val state = _uiState.value
        if (beginZoom(state.playheadXpx)) {
            zoomBy(scale, state.playheadXpx)
            endZoom()
        }
    }

    fun zoomToFit() {
        _uiState.update { state ->
            val (minZoom, maxZoom) = zoomLimitsFor(state)
            val duration = state.project?.timeline?.renderedDurationMicros ?: 0L
            val fitZoom = TimelineViewport.fitZoomFor(duration, state.viewportWidthPx)
                .coerceIn(minZoom, maxZoom)
            val scroll = state.viewport()?.let {
                TimelineViewport(
                    state.project?.timeline?.renderedDurationMicros ?: 0L,
                    fitZoom,
                    state.viewportWidthPx,
                    state.playheadXpx,
                ).scrollXForTime(state.playheadMicros)
            } ?: 0f
            state.copy(zoomPixelsPerMicro = fitZoom, scrollXPx = scroll)
        }
    }

    /**
     * Zooms in on the selected clip so its trim handles are comfortably
     * grabbable — the contextual toolbar's Trim action.
     */
    fun focusOnSelectedClip() {
        val state = _uiState.value
        val clipId = state.selection.videoClipId ?: state.selection.selectedAudioClipId
        val timeline = state.project?.timeline ?: return
        val (startMicros, endMicros) = when {
            state.selection.videoClipId != null ->
                timeline.videoClips.firstOrNull { it.id == clipId }
                    ?.let { it.timelineStartMicros to it.timelineEndMicros }

            else -> timeline.audioClips.firstOrNull { it.id == clipId }
                ?.let { it.timelineStartMicros to (it.timelineStartMicros + it.timelineDurationMicros) }
        } ?: return

        val spanMicros = (endMicros - startMicros).coerceAtLeast(1L)
        val focusMicros = (startMicros + endMicros) / 2
        previewController.pause()
        val (_, maxZoom) = zoomLimitsFor(state)
        // Fit the clip into the middle 60% of the viewport.
        val targetZoom = (state.viewportWidthPx * 0.6f / spanMicros)
            .coerceIn(zoomLimitsFor(state).first, maxZoom)
        _uiState.update { current ->
            val viewport = TimelineViewport(
                current.project?.timeline?.renderedDurationMicros ?: 0L,
                targetZoom,
                current.viewportWidthPx,
                current.playheadXpx,
            )
            current.copy(
                zoomPixelsPerMicro = targetZoom,
                scrollXPx = viewport.scrollXForTime(focusMicros),
            )
        }
        // Focusing trim handles changes the time beneath the fixed line. Seek
        // the real preview too, so paused trim framing cannot leave the timer
        // and preview at a different time from the ruler/playhead.
        seekTo(focusMicros)
    }

    private fun zoomLimitsFor(state: EditorUiState): Pair<Float, Float> {
        val width = state.viewportWidthPx
        if (width <= 0f) return 0f to 1f
        return TimelineViewport.zoomLimits(
            durationMicros = state.project?.timeline?.renderedDurationMicros ?: 0L,
            viewportWidthPx = width,
        )
    }

    /** Clamps zoom and scroll into the legal range for the current state. */
    private fun clampViewport(state: EditorUiState): EditorUiState {
        val (minZoom, maxZoom) = zoomLimitsFor(state)
        val zoom = state.zoomPixelsPerMicro.coerceIn(minZoom, maxZoom)
        val viewport = TimelineViewport(
            state.project?.timeline?.renderedDurationMicros ?: 0L,
            zoom,
            state.viewportWidthPx,
            state.playheadXpx,
        )
        return state.copy(
            zoomPixelsPerMicro = zoom,
            scrollXPx = viewport.clampScroll(state.scrollXPx),
        )
    }

    // -- trim --

    fun beginTrim(clipId: String, edge: TrimMath.Edge, isAudio: Boolean): Boolean {
        val state = _uiState.value
        val timeline = state.project?.timeline ?: return false
        val (sourceStart, sourceEnd) = if (isAudio) {
            timeline.audioClips.firstOrNull { it.id == clipId }
                ?.let { it.sourceStartMicros to it.sourceEndMicros }
        } else {
            timeline.videoClips.firstOrNull { it.id == clipId }
                ?.let { it.sourceStartMicros to it.sourceEndMicros }
        } ?: return false
        if (!tryBeginGesture(
                TimelineGesture.Trimming(clipId, edge, sourceStart to sourceEnd),
            )
        ) {
            return false
        }
        pauseForGesture()
        return true
    }

    /**
     * Updates an in-flight trim. [totalDxPx] is the accumulated drag distance
     * since the gesture began (not a per-event delta), so the proposed range is
     * always derived from the original — no drift over a long drag.
     */
    fun trimBy(totalDxPx: Float, isAudio: Boolean) {
        val trimming = gestureState.gesture as? TimelineGesture.Trimming ?: return
        val state = _uiState.value
        val viewport = state.viewport() ?: return
        val timeline = state.project?.timeline ?: return
        val deltaMicros = (totalDxPx / viewport.pixelsPerMicro).roundToLong()

        val proposed = if (isAudio) {
            val clip = timeline.audioClips.firstOrNull { it.id == trimming.clipId } ?: return
            val sourceDuration = sourceDurationFor(clip.mediaRefId, clip.sourceEndMicros)
            TrimMath.proposeTrim(clip, trimming.edge, deltaMicros, sourceDuration)
        } else {
            val clip = timeline.videoClips.firstOrNull { it.id == trimming.clipId } ?: return
            val sourceDuration = sourceDurationFor(clip.mediaRefId, clip.sourceEndMicros)
            TrimMath.proposeTrim(clip, trimming.edge, deltaMicros, sourceDuration)
        }
        updateGesture {
            (it as TimelineGesture.Trimming)
                .copy(proposedRangeMicros = proposed.startMicros to proposed.endMicros)
        }
    }

    /** Commits the in-flight trim, or discards it when [commit] is false. */
    fun endTrim(commit: Boolean, isAudio: Boolean) {
        val trimming = gestureState.gesture as? TimelineGesture.Trimming
        endGesture()
        if (!commit || trimming == null) return
        val proposed = trimming.proposedRangeMicros ?: return
        val (originalStart, originalEnd) = trimming.originalRangeMicros
        if (proposed.first == originalStart && proposed.second == originalEnd) return

        val command = if (isAudio) {
            AssetCommands.TrimAudioClip(trimming.clipId, proposed.first, proposed.second)
        } else {
            TimelineCommands.TrimClip(
                clipId = trimming.clipId,
                newSourceStartMicros = proposed.first,
                newSourceEndMicros = proposed.second,
                prevSourceStartMicros = originalStart,
                prevSourceEndMicros = originalEnd,
            )
        }
        applyCommand(command)
    }

    /**
     * The source duration bounding a trim: the media's real duration when
     * known, otherwise the clip's current source end (a trim may then not
     * extend the clip, but can always shorten it — never guessing past the
     * verified end of the file).
     */
    private fun sourceDurationFor(mediaRefId: String, fallbackEnd: Long): Long {
        val refDuration = _uiState.value.project?.mediaRef(mediaRefId)?.durationMicros
        return maxOf(refDuration ?: 0L, fallbackEnd)
    }

    // -- reorder (video track) --

    fun beginReorder(clipId: String): Boolean {
        val timeline = _uiState.value.project?.timeline ?: return false
        val index = timeline.videoClips.indexOfFirst { it.id == clipId }
        if (index < 0) return false
        if (!tryBeginGesture(TimelineGesture.Reordering(clipId, index))) return false
        pauseForGesture()
        return true
    }

    /**
     * Updates the drop slot for an in-flight reorder. [dragCenterScreenXPx] is
     * where the dragged clip's center currently is on screen.
     */
    fun reorderTo(dragCenterScreenXPx: Float) {
        val reordering = gestureState.gesture as? TimelineGesture.Reordering ?: return
        val state = _uiState.value
        val viewport = state.viewport() ?: return
        val timeline = state.project?.timeline ?: return
        val dropCenterMicros = viewport.timeAtScreenX(state.scrollXPx, dragCenterScreenXPx)
        val proposedIndex = hitTester.reorderIndexAt(timeline, reordering.clipId, dropCenterMicros)
        if (proposedIndex == reordering.proposedIndex) return
        updateGesture {
            (it as TimelineGesture.Reordering).copy(
                proposedIndex = proposedIndex,
                // Slot changes are the reorder's snap moments; the UI triggers
                // haptic feedback on this transition.
                isSnapped = true,
            )
        }
    }

    fun endReorder(commit: Boolean) {
        val reordering = gestureState.gesture as? TimelineGesture.Reordering
        endGesture()
        if (!commit || reordering == null) return
        val timeline = _uiState.value.project?.timeline ?: return
        val originalIndex = timeline.videoClips.indexOfFirst { it.id == reordering.clipId }
        if (originalIndex < 0 || reordering.proposedIndex == originalIndex) return
        applyCommand(TimelineCommands.MoveClip(reordering.clipId, reordering.proposedIndex))
    }

    // -- free move (audio/text tracks) --

    fun beginFreeMove(track: TimelineGesture.MovingFree.Track, itemId: String): Boolean {
        val timeline = _uiState.value.project?.timeline ?: return false
        val start = when (track) {
            TimelineGesture.MovingFree.Track.Audio ->
                timeline.audioClips.firstOrNull { it.id == itemId }?.timelineStartMicros

            TimelineGesture.MovingFree.Track.Text ->
                timeline.textOverlays.firstOrNull { it.id == itemId }?.startMicros
        } ?: return false
        if (!tryBeginGesture(
                TimelineGesture.MovingFree(track, itemId, start, start),
            )
        ) {
            return false
        }
        pauseForGesture()
        return true
    }

    /**
     * Updates an in-flight free move. The proposed start is the original start
     * plus the accumulated drag, magnetically snapped against the timeline
     * start, the playhead, and video clip boundaries (so music can be aligned
     * to picture cuts).
     */
    fun freeMoveBy(totalDxPx: Float) {
        val moving = gestureState.gesture as? TimelineGesture.MovingFree ?: return
        val state = _uiState.value
        val viewport = state.viewport() ?: return
        val timeline = state.project?.timeline ?: return

        val itemDuration = when (moving.track) {
            TimelineGesture.MovingFree.Track.Audio ->
                timeline.audioClips.firstOrNull { it.id == moving.itemId }
                    ?.timelineDurationMicros

            TimelineGesture.MovingFree.Track.Text ->
                timeline.textOverlays.firstOrNull { it.id == moving.itemId }?.durationMicros
        } ?: return

        val deltaMicros = (totalDxPx / viewport.pixelsPerMicro).roundToLong()
        val proposed = (moving.originalStartMicros + deltaMicros).coerceAtLeast(0L)

        val candidates = buildList {
            add(0L to SnapEngine.SnapTarget.TimelineStart)
            add(state.playheadMicros to SnapEngine.SnapTarget.Playhead)
            for (clip in timeline.videoClips) {
                add(clip.timelineStartMicros to
                    SnapEngine.SnapTarget.ClipBoundary(clip.id, clip.timelineStartMicros, true))
                add(clip.timelineEndMicros to
                    SnapEngine.SnapTarget.ClipBoundary(clip.id, clip.timelineEndMicros, false))
            }
        }
        val snap = SnapEngine.snapMovedClip(
            proposedStartMicros = proposed,
            clipDurationMicros = itemDuration,
            pixelsPerMicro = viewport.pixelsPerMicro,
            thresholdPx = SNAP_THRESHOLD_PX,
            candidates = candidates,
        )
        updateGesture {
            (it as TimelineGesture.MovingFree).copy(
                proposedStartMicros = snap.timeMicros.coerceAtLeast(0L),
                isSnapped = snap.isSnapped,
            )
        }
    }

    fun endFreeMove(commit: Boolean) {
        val moving = gestureState.gesture as? TimelineGesture.MovingFree
        endGesture()
        if (!commit || moving == null) return
        if (moving.proposedStartMicros == moving.originalStartMicros) return
        val command = when (moving.track) {
            TimelineGesture.MovingFree.Track.Audio ->
                AssetCommands.MoveAudioClip(moving.itemId, moving.proposedStartMicros)

            TimelineGesture.MovingFree.Track.Text ->
                AssetCommands.MoveTextOverlay(moving.itemId, moving.proposedStartMicros)
        }
        applyCommand(command)
    }

    /** Draft preview never enters history or persistence. Apply uses the existing command. */
    fun transitionValidation(value: Transition): String? {
        val project = _uiState.value.project ?: return "Open a project first."
        if(_uiState.value.missingMediaIds.isNotEmpty()) return "Relink the missing media before adding a dissolve."
        return runCatching {
            val candidate = transitionCandidate(project,value)
            com.videoeditor.core.model.render.ProjectRenderPlan.compile(candidate)
        }.exceptionOrNull()?.let { error ->
            when (error.message) {
                "Not enough source handles for this duration", "Transition exceeds adjacent clip duration" ->
                    "Shorten the dissolve, or leave more footage after the first trim and before the next trim."
                else -> error.message ?: "This join cannot use a dissolve."
            }
        }
    }

    /** Monotonic search uses the same integer validator as preview/export, including neighboring joins. */
    fun transitionDurationLimit(template: Transition): Long {
        val clips = _uiState.value.project?.timeline?.videoClips ?: return 0L
        val outgoing = clips.firstOrNull { it.id == template.outgoingClipId } ?: return 0L
        val incoming = clips.firstOrNull { it.id == template.incomingClipId } ?: return 0L
        if (transitionValidation(template.copy(durationMicros = 1L)) != null) return 0L
        var low = 1L
        var high = minOf(outgoing.timelineDurationMicros, incoming.timelineDurationMicros)
            .coerceIn(0L, (Long.MAX_VALUE - 1) / 2) * 2 + 1
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (transitionValidation(template.copy(durationMicros = middle)) == null) low = middle
            else high = middle - 1
        }
        return low
    }

    fun transitionDurationWithHandlesLimit(template: Transition): Long {
        val project = _uiState.value.project ?: return 0L
        val a = project.timeline.videoClips.firstOrNull { it.id == template.outgoingClipId } ?: return 0L
        val b = project.timeline.videoClips.firstOrNull { it.id == template.incomingClipId } ?: return 0L
        fun possible(duration: Long) = runCatching {
            com.videoeditor.editing.commands.TransitionHandlePlanner.plan(project, template.copy(durationMicros = duration))
        }.isSuccess
        if (!possible(100_000L)) return 0L
        var low = 100_000L
        var high = minOf(a.timelineDurationMicros, b.timelineDurationMicros).coerceIn(0L, (Long.MAX_VALUE - 1) / 2) * 2
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (possible(middle)) low = middle else high = middle - 1
        }
        return low
    }

    data class TransitionHandlePlan(
        val possible: Boolean,
        val outgoingTrimMicros: Long,
        val incomingTrimMicros: Long,
        val resultingDurationMicros: Long,
        val explanation: String,
        val buttonLabel: String,
    )

    fun calculateTransitionHandlePlan(outgoingId: String, incomingId: String,
        requestedDurationMicros: Long = 1_000_000L): TransitionHandlePlan? {
        val project = _uiState.value.project ?: return null
        val transition = project.timeline.transitions.firstOrNull { it.outgoingClipId == outgoingId && it.incomingClipId == incomingId }
            ?.copy(durationMicros = requestedDurationMicros) ?: Transition("handle-proposal", outgoingId, incomingId, requestedDurationMicros)
        return try {
            val proposal = com.videoeditor.editing.commands.TransitionHandlePlanner.plan(project, transition)
            fun seconds(us: Long) = "%.2f".format(java.util.Locale.ROOT, us / 1_000_000.0)
            TransitionHandlePlan(true, proposal.outgoingTrimMicros, proposal.incomingTrimMicros,
                requestedDurationMicros,
                "Remove ${seconds((proposal.outgoingTrimMicros.toDouble() / proposal.before.timeline.videoClips.first { it.id == outgoingId }.playbackSpeed).toLong())} s from the visible end of the first clip " +
                    "(${seconds(proposal.outgoingTrimMicros)} s of source), and ${seconds((proposal.incomingTrimMicros.toDouble() / proposal.before.timeline.videoClips.first { it.id == incomingId }.playbackSpeed).toLong())} s from the visible start of the next " +
                    "(${seconds(proposal.incomingTrimMicros)} s of source). " +
                    "Timeline shortens by ${seconds(proposal.timelineReductionMicros)} s. Music and overlays keep their positions. Apply makes one undoable edit; original media stays intact.",
                "Apply with source handle adjustments")
        } catch (failure: Exception) {
            TransitionHandlePlan(false, 0L, 0L, requestedDurationMicros,
                failure.message ?: "This transition cannot be applied", "Insufficient footage")
        }
    }

    fun transitionDiagnostics(outgoingId: String, incomingId: String): String? =
        calculateTransitionHandlePlan(outgoingId, incomingId)?.explanation

    /** Trims clips to create handles without applying transition in the same step. */
    fun createTransitionHandles(outgoingId: String, incomingId: String, neededPerSideMicros: Long = 500_000L): Long {
        val project = _uiState.value.project ?: return 0L
        val outgoing = project.timeline.videoClips.firstOrNull { it.id == outgoingId } ?: return 0L
        val incoming = project.timeline.videoClips.firstOrNull { it.id == incomingId } ?: return 0L
        val aDuration = project.mediaRef(outgoing.mediaRefId)?.durationMicros ?: return 0L
        val bDuration = project.mediaRef(incoming.mediaRefId)?.durationMicros ?: return 0L

        val outgoingTail = (aDuration - outgoing.sourceEndMicros).coerceAtLeast(0L)
        val incomingHead = incoming.sourceStartMicros.coerceAtLeast(0L)

        val commands = mutableListOf<com.videoeditor.editing.history.EditCommand>()
        if (outgoingTail < neededPerSideMicros) {
            val shortage = neededPerSideMicros - outgoingTail
            val newEnd = (outgoing.sourceEndMicros - shortage).coerceAtLeast(outgoing.sourceStartMicros + 200_000L)
            commands.add(com.videoeditor.editing.commands.TimelineCommands.TrimClip(
                clipId = outgoing.id,
                newSourceStartMicros = outgoing.sourceStartMicros,
                newSourceEndMicros = newEnd,
                prevSourceStartMicros = outgoing.sourceStartMicros,
                prevSourceEndMicros = outgoing.sourceEndMicros,
            ))
        }
        if (incomingHead < neededPerSideMicros) {
            val shortage = neededPerSideMicros - incomingHead
            val newStart = (incoming.sourceStartMicros + shortage).coerceAtMost(incoming.sourceEndMicros - 200_000L)
            commands.add(com.videoeditor.editing.commands.TimelineCommands.TrimClip(
                clipId = incoming.id,
                newSourceStartMicros = newStart,
                newSourceEndMicros = incoming.sourceEndMicros,
                prevSourceStartMicros = incoming.sourceStartMicros,
                prevSourceEndMicros = incoming.sourceEndMicros,
            ))
        }
        if (commands.isNotEmpty()) {
            applyCommand(com.videoeditor.editing.history.CompositeEditCommand("Create transition handles", commands))
            showMessage("Created handles for transition")
            return neededPerSideMicros * 2
        }
        return 0L
    }

    fun applyTransitionWithHandles(value: Transition): Boolean {
        val project = _uiState.value.project ?: return false
        return try {
            val proposal = com.videoeditor.editing.commands.TransitionHandlePlanner.plan(project, value)
            cancelTransitionPreview()
            applyCommand(com.videoeditor.editing.commands.TransitionHandlePlanner.Apply(proposal))
            true
        } catch (failure: Exception) { showMessage(failure.message ?: "Could not apply transition"); false }
    }

    fun setExportResolution(resolution: com.videoeditor.core.model.ExportResolution) {
        val project = _uiState.value.project ?: return
        val current = project.exportPreferences
        if (current.resolution == resolution) return
        val updated = project.copy(
            exportPreferences = current.copy(resolution = resolution),
            updatedAtMillis = System.currentTimeMillis(),
        )
        _uiState.update { it.copy(project = updated) }
        saveProject()
    }

    private fun transitionCandidate(project:Project,value:Transition):Project = project.copy(
        timeline=project.timeline.copy(transitions=project.timeline.transitions.filterNot {
            it.id==value.id || it.outgoingClipId==value.outgoingClipId && it.incomingClipId==value.incomingClipId
        }+value))

    fun previewTransition(value:Transition):Boolean {
        val project = _uiState.value.project ?: return false
        val candidate = if (transitionValidation(value) == null) transitionCandidate(project, value) else try {
            com.videoeditor.editing.commands.TransitionHandlePlanner.plan(project, value).after
        } catch (failure: Exception) { showMessage(failure.message ?: "Cannot preview this join"); return false }
        val window=candidate.resolveTransitions().single { it.transition.id==value.id }
        if(transitionPreviewReturnUs==null) transitionPreviewReturnUs=_uiState.value.playheadMicros
        transitionPreviewJob?.cancel()
        previewController.pause()
        previewController.setProject(candidate)
        previewController.seekTo(window.startMicros)
        previewController.play()
        transitionPreviewJob=viewModelScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                previewController.state.first {
                    it.error!=null || it.requestedPositionMicros==null &&
                        it.actualPositionMicros>=window.endMicros && previewController.renderedPositionMicros>=window.startMicros
                }
            }
            previewController.pause()
        }
        return true
    }

    fun cancelTransitionPreview() {
        transitionPreviewJob?.cancel();transitionPreviewJob=null
        val position=transitionPreviewReturnUs ?: return
        transitionPreviewReturnUs=null
        previewController.pause()
        previewController.setProject(_uiState.value.project)
        previewController.seekTo(position)
    }

    fun applyTransition(value:Transition):Boolean {
        transitionValidation(value)?.let { showMessage(it);return false }
        cancelTransitionPreview()
        applyCommand(com.videoeditor.editing.commands.TransitionCommands.Set(value))
        return true
    }

    fun removeTransition(id:String) {
        cancelTransitionPreview()
        applyCommand(com.videoeditor.editing.commands.TransitionCommands.Remove(id))
    }

    // -- contextual editing actions --

    /** Exact source-range entry uses the same bounds and command path as handle trimming. */
    fun trimSelectedSource(startMicros: Long, endMicros: Long): Boolean {
        val state = _uiState.value
        val clip = state.project?.timeline?.videoClips?.firstOrNull { it.id == state.selection.videoClipId }
            ?: return false
        if (clip.isPhoto) return false
        val maximum = sourceDurationFor(clip.mediaRefId, clip.sourceEndMicros)
        if (startMicros < 0 || endMicros <= startMicros || endMicros > maximum ||
            endMicros - startMicros < TrimMath.MIN_SOURCE_SPAN_MICROS) {
            showMessage("Keep the source range within the media and at least 0.1 seconds long")
            return false
        }
        if (startMicros != clip.sourceStartMicros || endMicros != clip.sourceEndMicros) {
            val command = TimelineCommands.TrimClip(clip.id, startMicros, endMicros,
                clip.sourceStartMicros, clip.sourceEndMicros)
            applyCommand(command)
        }
        return true
    }

    /** Splits the selected video clip at the playhead; rejects invalid points. */
    fun splitSelectedClip() {
        val state = _uiState.value
        state.selection.selectedAudioClipId?.let { id ->
            applyCommand(AssetCommands.SplitAudioClip(id, state.playheadMicros)); return
        }
        val clipId = state.selection.videoClipId ?: return
        val clip = state.project?.timeline?.videoClips?.firstOrNull { it.id == clipId } ?: return
        val playhead = state.playheadMicros
        if (playhead <= clip.timelineStartMicros || playhead >= clip.timelineEndMicros) {
            showMessage("Move the playhead inside the clip to split it")
            return
        }
        applyCommand(TimelineCommands.SplitClip(clipId, playhead))
    }

    /** Deletes whatever is selected, on any track. */
    fun deleteSelected() {
        val state = _uiState.value
        val command = when (val selection = state.selection) {
            is TimelineSelection.Video -> TimelineCommands.DeleteClip(selection.clipId)
            is TimelineSelection.Audio -> AssetCommands.DeleteAudioClip(selection.audioClipId)
            is TimelineSelection.Text -> AssetCommands.DeleteTextOverlay(selection.overlayId)
            is TimelineSelection.Sticker -> com.videoeditor.editing.commands.StickerCommands.DeleteSticker(selection.stickerId)
            is TimelineSelection.Overlay -> com.videoeditor.editing.commands.OverlayCommands.DeleteOverlay(selection.overlayId)
            is TimelineSelection.Transition -> com.videoeditor.editing.commands.TransitionCommands.Remove(selection.transitionId)
            is TimelineSelection.Effect -> null
            TimelineSelection.None -> null
        } ?: return
        applyCommand(command)
        _uiState.update { it.copy(selection = TimelineSelection.None) }
    }

    /** Duplicates the selected video clip and selects the new copy. */
    fun duplicateSelectedClip() {
        val state = _uiState.value
        state.selection.selectedAudioClipId?.let { id ->
            val original = state.project?.timeline?.audioClips?.firstOrNull { it.id == id } ?: return
            val ref = state.project.mediaRef(original.mediaRefId) ?: return
            val copy = original.copy(
                id = Ids.newId(),
                timelineStartMicros = original.timelineStartMicros + original.timelineDurationMicros,
                volumeEnvelope = original.volumeEnvelope.remapPointIds { Ids.newId() },
            )
            applyCommand(AssetCommands.AddAudioClip(ref, copy))
            select(TimelineSelection.Audio(copy.id)); return
        }
        state.selection.selectedTextOverlayId?.let { id ->
            val original = state.project?.timeline?.textOverlays?.firstOrNull { it.id == id } ?: return
            val copy = original.copy(id = Ids.newId(), positionY = (original.positionY + 0.1f).coerceAtMost(1f))
            applyCommand(AssetCommands.AddTextOverlay(copy))
            select(TimelineSelection.Text(copy.id)); return
        }
        state.selection.selectedStickerId?.let { id -> duplicateSticker(id); return }
        state.selectedOverlayId?.let { id -> duplicateOverlay(id); return }
        val clipId = state.selection.videoClipId ?: return
        val clips = state.project?.timeline?.videoClips ?: return
        val index = clips.indexOfFirst { it.id == clipId }
        if (index < 0) return
        applyCommand(TimelineCommands.DuplicateClip(clipId))
        // DuplicateClip inserts the copy immediately after the original.
        val newId = _uiState.value.project?.timeline?.videoClips
            ?.getOrNull(index + 1)?.id
        if (newId != null) select(TimelineSelection.Video(newId))
    }

    /** Sets the selected video clip’s qualified constant speed (0.5x – 2x). */
    fun setSpeed(speed: Float, shortenTransitions: Boolean = false) {
        val project = _uiState.value.project ?: return
        val clipId = _uiState.value.selection.videoClipId ?: return
        val proposal = try { com.videoeditor.editing.commands.SpeedChangePlanner.plan(project, clipId, speed) }
        catch (failure: IllegalArgumentException) { showMessage(failure.message ?: "This speed is unavailable"); return }
        catch (_: ArithmeticException) { showMessage("This speed exceeds the supported timeline range."); return }
        if (proposal.needsTransitionAdjustment && !shortenTransitions) {
            showMessage("Review the shorter transition durations in the speed panel."); return
        }
        val sourceId = project.timeline.videoClips.firstOrNull { it.id == clipId }?.mediaRefId ?: return
        val source = project.mediaRef(sourceId) ?: return
        viewModelScope.launch {
            if (source.hasAudio == null) {
                val audio = withContext(ioDispatcher) { metadataExtractor.extract(appContext, Uri.parse(source.uri)).getOrNull()?.hasAudio }
                if (_uiState.value.project?.id != project.id) return@launch
                if (audio != null) _uiState.update { state -> state.copy(project = state.project?.let { doc ->
                    doc.copy(mediaRefs = doc.mediaRefs.map { if (it.id == sourceId) it.copy(hasAudio = audio) else it })
                }) }
            }
            applyCommand(com.videoeditor.editing.commands.SpeedChangePlanner.Apply(proposal))
        }
    }

    fun toggleSelectedFade() {
        val state = _uiState.value
        val id = state.selection.videoClipId ?: return
        val clip = state.project?.timeline?.videoClips?.firstOrNull { it.id == id } ?: return
        val duration = if (clip.fadeInMicros > 0 || clip.fadeOutMicros > 0) 0L else 300_000L
        applyCommand(AssetCommands.SetClipFade(id, duration, duration))
    }

    fun setPhotoDuration(durationMicros: Long) {
        val id = _uiState.value.selection.videoClipId ?: return
        applyCommand(TimelineCommands.SetPhotoDuration(id, durationMicros))
    }

    fun setColorGrade(filter: com.videoeditor.core.model.FilterSettings, adjustments: com.videoeditor.core.model.ColorAdjustments) {
        val id = _uiState.value.selection.videoClipId ?: return
        applyCommand(AssetCommands.SetColorGrade(id, filter, adjustments))
    }

    fun previewEffects(id: String, effects: List<com.videoeditor.core.model.VisualEffect>, overlay: Boolean) {
        val project = _uiState.value.project ?: return
        previewController.setProject(project.copy(timeline = if (overlay) project.timeline.copy(
            overlayClips = project.timeline.overlayClips.map { if (it.id == id) it.copy(effects = effects) else it })
        else project.timeline.copy(videoClips = project.timeline.videoClips.map { if (it.id == id) it.copy(effects = effects) else it })))
    }

    fun previewOverlayDraft(value: com.videoeditor.core.model.OverlayClip) {
        val project = _uiState.value.project ?: return
        previewController.setProject(project.copy(timeline = project.timeline.copy(overlayClips =
            project.timeline.overlayClips.map { if (it.id == value.id) value else it })))
    }

    // Stage 9 Visual Effects
    fun addClipEffect(clipId: String, effect: com.videoeditor.core.model.VisualEffect) {
        applyCommand(AssetCommands.AddClipEffect(clipId, effect))
    }

    fun removeClipEffect(clipId: String, effectId: String) {
        applyCommand(AssetCommands.RemoveClipEffect(clipId, effectId))
    }

    fun updateClipEffect(clipId: String, effect: com.videoeditor.core.model.VisualEffect, commitImmediately: Boolean = true) {
        val cmd = AssetCommands.UpdateClipEffect(clipId, effect)
        if (commitImmediately) applyCommand(cmd) else applyCommandCoalesced(cmd)
    }

    fun setClipEffects(clipId: String, effects: List<com.videoeditor.core.model.VisualEffect>) {
        applyCommand(AssetCommands.SetClipEffects(clipId, effects))
    }

    fun reorderClipEffects(clipId: String, fromIndex: Int, toIndex: Int) {
        applyCommand(AssetCommands.ReorderClipEffects(clipId, fromIndex, toIndex))
    }

    fun setClipKeyframe(clipId: String, propertyName: String, keyframe: com.videoeditor.core.model.VisualKeyframe) {
        applyCommand(AssetCommands.SetClipKeyframe(clipId, propertyName, keyframe))
    }

    fun removeClipKeyframe(clipId: String, propertyName: String, keyframeId: String) {
        applyCommand(AssetCommands.RemoveClipKeyframe(clipId, propertyName, keyframeId))
    }

    // Stage 9 Overlay Enhancements: Mask, Chroma Key, Blend Mode, Effects, Keyframes
    fun setOverlayBlendMode(overlayId: String, blendMode: com.videoeditor.core.model.BlendMode) {
        applyCommand(com.videoeditor.editing.commands.OverlayCommands.SetOverlayBlendMode(overlayId, blendMode))
    }

    fun setOverlayMask(overlayId: String, mask: com.videoeditor.core.model.LayerMask?) {
        applyCommand(com.videoeditor.editing.commands.OverlayCommands.SetOverlayMask(overlayId, mask))
    }

    fun setOverlayChromaKey(overlayId: String, chromaKey: com.videoeditor.core.model.ChromaKeySettings?) {
        applyCommand(com.videoeditor.editing.commands.OverlayCommands.SetOverlayChromaKey(overlayId, chromaKey))
    }

    fun addOverlayEffect(overlayId: String, effect: com.videoeditor.core.model.VisualEffect) {
        applyCommand(com.videoeditor.editing.commands.OverlayCommands.AddOverlayEffect(overlayId, effect))
    }

    fun removeOverlayEffect(overlayId: String, effectId: String) {
        applyCommand(com.videoeditor.editing.commands.OverlayCommands.RemoveOverlayEffect(overlayId, effectId))
    }

    fun updateOverlayEffect(overlayId: String, effect: com.videoeditor.core.model.VisualEffect, commitImmediately: Boolean = true) {
        val cmd = com.videoeditor.editing.commands.OverlayCommands.UpdateOverlayEffect(overlayId, effect)
        if (commitImmediately) applyCommand(cmd) else applyCommandCoalesced(cmd)
    }

    fun setOverlayEffects(overlayId: String, effects: List<com.videoeditor.core.model.VisualEffect>) {
        applyCommand(com.videoeditor.editing.commands.OverlayCommands.SetOverlayEffects(overlayId, effects))
    }

    fun setOverlayKeyframe(overlayId: String, propertyName: String, keyframe: com.videoeditor.core.model.VisualKeyframe) {
        applyCommand(com.videoeditor.editing.commands.OverlayCommands.SetOverlayKeyframe(overlayId, propertyName, keyframe))
    }

    fun removeOverlayKeyframe(overlayId: String, propertyName: String, keyframeId: String) {
        applyCommand(com.videoeditor.editing.commands.OverlayCommands.RemoveOverlayKeyframe(overlayId, propertyName, keyframeId))
    }


    suspend fun effectThumbnailSource(id: String, overlay: Boolean): Bitmap? {
        val project = _uiState.value.project ?: return null
        if (!overlay) _assets.value.strips[id]?.frames?.firstOrNull { it != null }?.let { return it }
        val layer = if (overlay) project.timeline.overlayClips.firstOrNull { it.id == id } else null
        val clip = if (!overlay) project.timeline.videoClips.firstOrNull { it.id == id } else null
        val mediaId = layer?.mediaRefId ?: clip?.mediaRefId ?: return null
        val media = project.mediaRef(mediaId) ?: return null
        return timelineMedia.singleThumbnail(appContext, Uri.parse(media.uri), layer?.sourceStartMicros ?: clip?.sourceStartMicros ?: 0L, 192)
    }

    /** Transient panel previews never mutate the document or command history. */
    fun previewVisualTransform(value: com.videoeditor.core.model.VisualTransform, mode: com.videoeditor.core.model.ScaleMode) {
        val state = _uiState.value
        val project = state.project ?: return
        previewController.pause()
        previewController.setProject(project.copy(canvas = project.canvas.copy(scaleMode = mode), timeline = project.timeline.copy(videoClips = project.timeline.videoClips.map {
            if (it.id == state.selection.videoClipId) it.copy(transform = value) else it
        })))
        previewController.seekTo(state.playheadMicros)
    }

    fun restoreToolPreview() {
        previewController.pause()
        previewController.setProject(_uiState.value.project)
        previewController.seekTo(_uiState.value.playheadMicros)
    }

    fun previewCanvas(preset: com.videoeditor.core.model.AspectRatioPreset, mode: com.videoeditor.core.model.ScaleMode? = null, background: Int? = null): com.videoeditor.core.model.CanvasSettings? {
        val project = _uiState.value.project ?: return null
        val canvas = canvasForRatio(project, preset).let { it.copy(scaleMode = mode ?: it.scaleMode, backgroundColorArgb = background ?: it.backgroundColorArgb) }
        previewController.pause()
        previewController.setProject(project.copy(canvas = canvas))
        previewController.seekTo(_uiState.value.playheadMicros)
        return canvas
    }

    fun setCanvasDesign(preset: com.videoeditor.core.model.AspectRatioPreset, mode: com.videoeditor.core.model.ScaleMode, background: Int) {
        val project = _uiState.value.project ?: return
        applyCommand(AssetCommands.SetCanvas(canvasForRatio(project, preset).copy(scaleMode = mode, backgroundColorArgb = background)))
    }

    private fun canvasForRatio(project: Project, preset: com.videoeditor.core.model.AspectRatioPreset): com.videoeditor.core.model.CanvasSettings {
        val source = project.timeline.videoClips.firstOrNull()?.let { project.mediaRef(it.mediaRefId) }
        val rotated = ((source?.rotationDegrees ?: 0) % 180) != 0
        val w = if (preset == com.videoeditor.core.model.AspectRatioPreset.Original) (if (rotated) source?.height else source?.width) ?: 1920 else preset.widthRatio * 120
        val h = if (preset == com.videoeditor.core.model.AspectRatioPreset.Original) (if (rotated) source?.width else source?.height) ?: 1080 else preset.heightRatio * 120
        return project.canvas.copy(aspectRatio = preset, width = w, height = h)
    }

    fun setVisualTransformAndCanvasMode(value: com.videoeditor.core.model.VisualTransform, mode: com.videoeditor.core.model.ScaleMode) {
        val state = _uiState.value
        val project = state.project ?: return
        val id = state.selection.videoClipId ?: return
        applyCommand(com.videoeditor.editing.history.CompositeEditCommand("Rotate & Transform", listOf(
            AssetCommands.SetVisualTransform(id, value), AssetCommands.SetCanvas(project.canvas.copy(scaleMode = mode)))))
    }

    fun previewColorGrade(filter: com.videoeditor.core.model.FilterSettings, adjustments: com.videoeditor.core.model.ColorAdjustments) {
        val state = _uiState.value
        val project = state.project ?: return
        previewController.pause()
        previewController.setProject(project.copy(timeline = project.timeline.copy(videoClips = project.timeline.videoClips.map {
            if (it.id == state.selection.videoClipId) it.copy(filter = filter, colorAdjustments = adjustments) else it
        })))
        previewController.seekTo(state.playheadMicros)
    }

    fun setVisualTransform(value: com.videoeditor.core.model.VisualTransform) {
        val id = _uiState.value.selection.videoClipId ?: return
        applyCommand(AssetCommands.SetVisualTransform(id, value))
    }

    fun setAspectRatio(preset: com.videoeditor.core.model.AspectRatioPreset) {
        val project = _uiState.value.project ?: return
        applyCommand(AssetCommands.SetCanvas(canvasForRatio(project, preset)))
    }

    fun setCanvasScaleMode(mode: com.videoeditor.core.model.ScaleMode) {
        val canvas = _uiState.value.project?.canvas ?: return
        applyCommand(AssetCommands.SetCanvas(canvas.copy(scaleMode = mode)))
    }

    /** Sets the selected clip's volume (video or audio track). */
    fun setClipAudioVolume(volume: Float, muted: Boolean) {
        val id = _uiState.value.selection.videoClipId ?: return
        applyCommand(com.videoeditor.editing.history.CompositeEditCommand("Clip volume", listOf(
            AssetCommands.SetClipVolume(id, volume), AssetCommands.SetClipMuted(id, muted))))
    }

    fun setVolume(volume: Float) {
        val state = _uiState.value
        val command = when (val selection = state.selection) {
            is TimelineSelection.Video ->
                AssetCommands.SetClipVolume(selection.clipId, volume)

            is TimelineSelection.Audio ->
                AssetCommands.SetAudioVolume(selection.audioClipId, volume)

            else -> null
        } ?: return
        applyCommand(command)
    }

    fun extractSelectedAudio() {
        val state = _uiState.value
        val clip = state.project?.timeline?.videoClips?.firstOrNull { it.id == state.selection.videoClipId } ?: return
        if (clip.isPhoto) { showMessage("Photos do not contain audio"); return }
        val ref = state.project.mediaRef(clip.mediaRefId) ?: return
        val audio = AudioClip(Ids.newId(), ref.id, clip.sourceStartMicros, clip.sourceEndMicros,
            clip.timelineStartMicros, clip.volume, playbackSpeed = clip.playbackSpeed,
            fadeInMicros = clip.fadeInMicros, fadeOutMicros = clip.fadeOutMicros,
            fadeInCurve = clip.fadeInCurve, fadeOutCurve = clip.fadeOutCurve)
        applyCommand(com.videoeditor.editing.history.CompositeEditCommand(
            "Extract audio",
            listOf(AssetCommands.AddAudioClip(ref, audio), AssetCommands.SetClipMuted(clip.id, true)),
        ))
        select(TimelineSelection.Audio(audio.id))
    }

    fun updateAudioEnvelope(clipId: String, envelope: com.videoeditor.core.model.VolumeEnvelope) {
        val original = _uiState.value.project?.timeline?.audioClips?.firstOrNull { it.id == clipId } ?: return
        if (original.volumeEnvelope != envelope) applyCommand(AssetCommands.UpdateAudioClip(clipId,
            original.copy(volumeEnvelope = envelope.normalized())))
    }

    fun updateSelectedAudio(transform: (AudioClip) -> AudioClip) {
        val id = _uiState.value.selection.selectedAudioClipId ?: return
        val original = _uiState.value.project?.timeline?.audioClips?.firstOrNull { it.id == id } ?: return
        applyCommand(AssetCommands.UpdateAudioClip(id, transform(original)))
    }

    /** True when the cached ducking analysis for a clip is a recorded failure,
     *  so the ducking panel can surface it instead of silently applying nothing. */
    fun duckingAnalysisFailed(clipId: String): Boolean {
        val project = _uiState.value.project ?: return false
        val clip = project.timeline.audioClips.firstOrNull { it.id == clipId } ?: return false
        return duckingAnalysis.analysisFailed(project, clip)
    }

    fun setSelectedMuted(muted: Boolean) {
        if (_uiState.value.selection.selectedAudioClipId != null) {
            updateSelectedAudio { it.copy(muted = muted) }; return
        }
        val clipId = _uiState.value.selection.videoClipId ?: return
        applyCommand(AssetCommands.SetClipMuted(clipId, muted))
    }

    /**
     * Imports a picked audio file onto the audio track at the playhead. The
     * clip defaults to the shorter of the source and the video remaining, so
     * adding music does not silently extend the project with a frozen visual
     * tail (the documented audio-milestone policy); the end trim handle can
     * extend it deliberately.
     */
    fun addAudioFromUri(rawUri: Uri) {
        viewModelScope.launch {
            val uri = withContext(ioDispatcher) { ensureDurableUri(rawUri) }
                ?: run { showMessage("The source could not be retained. Choose a readable file and try again."); return@launch }
            val ref = withContext(ioDispatcher) {
                metadataExtractor.extract(appContext, uri).getOrNull()
            }
            val sourceDurationMicros = ref?.durationMicros
            if (ref == null || sourceDurationMicros == null) {
                showMessage("That audio file could not be read")
                return@launch
            }
            val state = _uiState.value
            val timeline = state.project?.timeline ?: return@launch
            val start = state.playheadMicros
            val videoRemaining =
                (timeline.videoTrackDurationMicros - start).coerceAtLeast(0L)
            val defaultDuration = minOf(
                sourceDurationMicros,
                if (videoRemaining > 0L) videoRemaining else DEFAULT_AUDIO_DURATION_MICROS,
            )
            val audioClip = AudioClip(
                id = Ids.newId(),
                mediaRefId = ref.id,
                sourceStartMicros = 0L,
                sourceEndMicros = defaultDuration,
                timelineStartMicros = start,
            )
            applyCommand(AssetCommands.AddAudioClip(ref, audioClip))
            select(TimelineSelection.Audio(audioClip.id))
        }
    }

    /** Reuse durable imported audio without another picker, copy, or duplicate source reference. */
    fun addProjectAudio(mediaId: String) {
        val state = _uiState.value
        val project = state.project ?: return
        val media = project.mediaRef(mediaId)?.takeIf { it.type == MediaType.Audio } ?: return
        val remaining = project.timeline.videoTrackDurationMicros - state.playheadMicros
        if (remaining <= 0L) { showMessage("Move the playhead before the end to add audio."); return }
        val duration = minOf(media.durationMicros ?: return, remaining)
        if (duration <= 0L) return
        val clip = AudioClip(Ids.newId(), media.id, sourceEndMicros = duration, timelineStartMicros = state.playheadMicros)
        applyCommand(AssetCommands.AddAudioClip(media, clip))
        select(TimelineSelection.Audio(clip.id))
    }

    fun selectOverlay(id: String?) {
        _uiState.update { it.copy(selection = id?.let(TimelineSelection::Overlay) ?: TimelineSelection.None) }
    }

    private fun ensureDurableUri(uri: Uri): Uri? =
        com.videoeditor.core.media.DurableMediaImport.retain(appContext, uri)

    fun addOverlayFromUri(rawUri: Uri) {
        viewModelScope.launch {
            val uri = withContext(ioDispatcher) { ensureDurableUri(rawUri) }
                ?: run { showMessage("The source could not be retained. Choose a readable file and try again."); return@launch }
            val ref = withContext(ioDispatcher) { metadataExtractor.extract(appContext, uri).getOrNull() }
            val project = _uiState.value.project ?: return@launch
            if (ref == null || ref.type == com.videoeditor.core.model.MediaType.Audio) {
                showMessage("Choose a video or photo for PiP")
                return@launch
            }
            if (project.timeline.videoClips.isEmpty()) {
                showMessage("Add background footage before PiP"); return@launch
            }
            if (project.timeline.overlayClips.size >= (if (project.timeline.transitions.isEmpty()) 7 else 5)) {
                showMessage("The render plan has no free PiP inputs. Remove a layer or transition first.")
                return@launch
            }
            val start = _uiState.value.playheadMicros.coerceAtMost((project.timeline.renderedDurationMicros - 100_000).coerceAtLeast(0))
            val photo = ref.type == com.videoeditor.core.model.MediaType.Photo
            val duration = minOf(if (photo) 3_000_000 else ref.durationMicros ?: 0,
                project.timeline.renderedDurationMicros - start)
            if (duration <= 0) { showMessage("Add background footage before PiP"); return@launch }
            val layer = com.videoeditor.core.model.OverlayClip(
                id = Ids.newId(), mediaRefId = ref.id, timelineStartMicros = start,
                timelineDurationMicros = duration, sourceEndMicros = if (photo) 0 else duration,
                isPhoto = photo, transform = com.videoeditor.core.model.VisualTransform(scale = 0.5f),
                zIndex = (project.timeline.overlayClips.maxOfOrNull { it.zIndex } ?: -1) + 1,
            )
            applyCommand(com.videoeditor.editing.commands.OverlayCommands.AddOverlay(ref, layer))
            selectOverlay(layer.id)
        }
    }

    fun trimOverlaySource(id: String, startUs: Long, endUs: Long) {
        applyCommandCoalesced(com.videoeditor.editing.commands.OverlayCommands.TrimOverlay(id, startUs, endUs))
    }

    fun updateOverlay(value: com.videoeditor.core.model.OverlayClip) {
        applyCommandCoalesced(com.videoeditor.editing.commands.OverlayCommands.UpdateOverlay(value.id, value))
    }

    fun deleteOverlay(id: String) {
        applyCommand(com.videoeditor.editing.commands.OverlayCommands.DeleteOverlay(id))
        selectOverlay(null)
    }

    fun duplicateOverlay(id: String) {
        if ((_uiState.value.project?.timeline?.overlayClips?.size ?: 0) >= (if (_uiState.value.project?.timeline?.transitions.isNullOrEmpty()) 7 else 5)) {
            showMessage("The render plan has no free PiP inputs. Remove a layer or transition first."); return
        }
        val newId = Ids.newId()
        applyCommand(com.videoeditor.editing.commands.OverlayCommands.DuplicateOverlay(id, newId))
        selectOverlay(newId)
    }

    /**
     * Appends picked visual media (videos/photos) to the end of the primary video track.
     */
    fun appendMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val refs: List<MediaRef> = withContext(ioDispatcher) {
                uris.mapNotNull { rawUri ->
                    val uri = ensureDurableUri(rawUri) ?: return@mapNotNull null
                    metadataExtractor.extract(appContext, uri).getOrNull()
                }
            }
            if (refs.isEmpty()) {
                showMessage("No supported video or photo files could be read")
                return@launch
            }
            val validVisualRefs: List<MediaRef> = refs.filter {
                it.type == MediaType.Video || it.type == MediaType.Photo
            }
            if (validVisualRefs.isEmpty()) {
                showMessage("Audio files must be added via the audio tool")
                return@launch
            }
            val newClips: List<Clip> = validVisualRefs.map { ref: MediaRef ->
                val isPhoto = ref.type == MediaType.Photo
                val sourceDuration = ref.durationMicros ?: 3_000_000L
                Clip(
                    id = Ids.newId(),
                    mediaRefId = ref.id,
                    sourceStartMicros = 0L,
                    sourceEndMicros = sourceDuration,
                    timelineStartMicros = 0L,
                    photoDurationMicros = if (isPhoto) 3_000_000L else null,
                )
            }
            applyCommand(TimelineCommands.AppendClips(validVisualRefs, newClips))
            newClips.firstOrNull()?.let { select(TimelineSelection.Video(it.id)) }
            showMessage("Added ${newClips.size} ${if (newClips.size == 1) "clip" else "clips"}")
        }
    }

    private fun scheduleVisualPreview() {
        if (previewSyncJob?.isActive == true) return
        previewSyncJob = viewModelScope.launch {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                val choreographer = android.view.Choreographer.getInstance()
                val callback = android.view.Choreographer.FrameCallback {
                    if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(Unit))
                }
                choreographer.postFrameCallback(callback)
                continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
            }
            previewController.setProject(_uiState.value.project)
        }
    }

    private var overlayTransformSnapshot: com.videoeditor.core.model.OverlayClip? = null
    fun beginOverlayTransform() {
        previewController.pause()
        overlayTransformSnapshot = _uiState.value.project?.timeline?.overlayClips?.firstOrNull { it.id == _uiState.value.selectedOverlayId }
    }
    fun previewOverlayTransform(transform: com.videoeditor.core.model.VisualTransform) {
        val original = overlayTransformSnapshot ?: return
        _uiState.update { state -> state.copy(project = state.project?.let { project ->
            project.copy(timeline = project.timeline.copy(overlayClips = project.timeline.overlayClips.map {
                if (it.id == original.id) it.copy(transform = transform) else it
            }))
        }) }
        scheduleVisualPreview()
    }
    fun endOverlayTransform(commitChange: Boolean) {
        previewSyncJob?.cancel()
        val original = overlayTransformSnapshot ?: return
        overlayTransformSnapshot = null
        val project = _uiState.value.project ?: return
        val final = project.timeline.overlayClips.firstOrNull { it.id == original.id } ?: return
        _uiState.update { it.copy(project = project.copy(timeline = project.timeline.copy(overlayClips =
            project.timeline.overlayClips.map { layer -> if (layer.id == original.id) original else layer }))) }
        if (commitChange && original != final) updateOverlay(final)
        else scheduleVisualPreview()
    }

    /**
     * Adds a text overlay at the playhead with the default 3s duration, clamped
     * to the rendered (video+audio) span so a caption near the end can never
     * silently stretch the project into black frames. If the playhead is at the
     * very end there is no visible room, so the operation is refused with a
     * message rather than producing an out-of-range overlay.
     */
    fun addText(text: String) {
        val state = _uiState.value
        val timeline = state.project?.timeline ?: return
        if (text.isBlank()) return
        val rendered = timeline.renderedDurationMicros
        val defaultDur = DEFAULT_TEXT_DURATION_MICROS
        val (start, duration) = if (rendered <= 0L) {
            0L to defaultDur
        } else if (state.playheadMicros >= rendered - 200_000L) {
            val s = (rendered - defaultDur).coerceAtLeast(0L)
            s to minOf(defaultDur, rendered - s)
        } else {
            val s = state.playheadMicros.coerceIn(0L, rendered)
            s to minOf(defaultDur, rendered - s)
        }
        val overlay = TextOverlay(
            id = Ids.newId(),
            text = text.trim(),
            maxWidthFraction = .9f,
            hasShadow = true,
            startMicros = start,
            durationMicros = duration,
        )
        applyCommand(AssetCommands.AddTextOverlay(overlay))
        select(TimelineSelection.Text(overlay.id))
        seekTo(start)
    }

    private val _canvasEditRequests = kotlinx.coroutines.flow.MutableSharedFlow<TimelineSelection>(extraBufferCapacity = 1)
    val canvasEditRequests: kotlinx.coroutines.flow.SharedFlow<TimelineSelection> = _canvasEditRequests
    fun requestCanvasEditing(selection: TimelineSelection) { _canvasEditRequests.tryEmit(selection) }
    private var textPanelId: String? = null
    private var textPanelOriginal: TextOverlay? = null

    fun beginTextEditing(id: String) {
        if (textPanelId == id) return
        if (textPanelId != null) finishTextEditing(false)
        textPanelOriginal = _uiState.value.project?.timeline?.textOverlays?.firstOrNull { it.id == id } ?: return
        textPanelId = id
    }

    private fun previewTextPanel(id: String, update: (TextOverlay) -> TextOverlay) {
        _uiState.update { state -> state.copy(project = state.project?.let { project ->
            project.copy(timeline = project.timeline.copy(textOverlays = project.timeline.textOverlays.map {
                if (it.id == id) update(it) else it
            }))
        }) }
        scheduleVisualPreview()
    }

    /** One Apply entry, or exact rollback with no canceled draft in history. */
    fun finishTextEditing(apply: Boolean) {
        val id = textPanelId ?: return
        val original = textPanelOriginal
        textPanelId = null; textPanelOriginal = null
        previewSyncJob?.cancel()
        val project = _uiState.value.project ?: return
        val final = project.timeline.textOverlays.firstOrNull { it.id == id }
        val restored = project.copy(timeline = project.timeline.copy(textOverlays =
            if (original == null) project.timeline.textOverlays.filterNot { it.id == id }
            else project.timeline.textOverlays.map { if (it.id == id) original else it }))
        _uiState.update { it.copy(project = restored,
            selection = if (!apply && original == null) TimelineSelection.None else it.selection) }
        if (apply && final != null && final != original) {
            applyCommand(if (original == null) AssetCommands.AddTextOverlay(final) else AssetCommands.UpdateTextOverlay(id, final))
        } else previewController.setProject(restored)
    }

    private var textTransformSnapshot: TextOverlay? = null

    fun beginTextTransform(id: String) {
        previewController.pause()
        textTransformSnapshot = _uiState.value.project?.timeline?.textOverlays?.firstOrNull { it.id == id }
    }

    fun previewTextTransform(value: TextOverlay) {
        if (textTransformSnapshot?.id != value.id) return
        _uiState.update { state -> state.copy(project = state.project?.let { project ->
            project.copy(timeline = project.timeline.copy(textOverlays = project.timeline.textOverlays.map { if (it.id == value.id) value else it }))
        }) }
        scheduleVisualPreview()
    }

    fun endTextTransform(commitChange: Boolean) {
        val original = textTransformSnapshot ?: return
        textTransformSnapshot = null
        val project = _uiState.value.project ?: return
        val final = project.timeline.textOverlays.firstOrNull { it.id == original.id } ?: return
        if (textPanelId == original.id) {
            if (!commitChange) previewTextPanel(original.id) { original }
            return
        }
        _uiState.update { it.copy(project = project.copy(timeline = project.timeline.copy(textOverlays =
            project.timeline.textOverlays.map { text -> if (text.id == original.id) original else text }))) }
        if (commitChange && original != final) applyCommand(AssetCommands.UpdateTextOverlay(original.id, final))
        else { previewSyncJob?.cancel(); previewController.setProject(_uiState.value.project) }
    }

    private var textTrimEdge: TrimMath.Edge? = null
    private var textTrimZoom = 1f

    fun beginTextTrim(id: String, edge: TrimMath.Edge): Boolean {
        if (_uiState.value.gesture !is TimelineGesture.Idle || textPanelId != null) return false
        select(TimelineSelection.Text(id))
        beginTextTransform(id)
        textTrimEdge = edge
        textTrimZoom = _uiState.value.zoomPixelsPerMicro
        return textTransformSnapshot != null && textTrimZoom > 0f
    }

    fun textTrimBy(totalDxPx: Float) {
        val original = textTransformSnapshot ?: return
        val duration = _uiState.value.project?.timeline?.renderedDurationMicros ?: return
        val minimum = minOf(100_000L, duration - original.startMicros)
        if (minimum <= 0) return
        val delta = (totalDxPx / textTrimZoom).toLong()
        val end = (original.startMicros + original.durationMicros).coerceAtMost(duration)
        val updated = if (textTrimEdge == TrimMath.Edge.Start) {
            val start = (original.startMicros + delta).coerceIn(0L, (end - minimum).coerceAtLeast(0))
            original.copy(startMicros = start, durationMicros = end - start)
        } else {
            val nextEnd = (end + delta).coerceIn(original.startMicros + minimum, duration)
            original.copy(durationMicros = nextEnd - original.startMicros)
        }
        previewTextTransform(updated)
    }

    fun endTextTrim(commit: Boolean) { textTrimEdge = null; endTextTransform(commit) }

    /** Updates the selected text overlay's content. */
    fun setSelectionText(text: String) {
        val overlayId = _uiState.value.selection.selectedTextOverlayId ?: return
        if (textPanelId == overlayId) previewTextPanel(overlayId) { it.copy(text = text) }
        else applyCommandCoalesced(AssetCommands.SetText(overlayId, text))
    }

    /** Applies a style change to the selected text overlay via a reversible copy. */
    fun updateSelectedText(transform: (TextOverlay) -> TextOverlay) {
        val overlayId = _uiState.value.selection.selectedTextOverlayId ?: return
        val current = _uiState.value.project?.timeline?.textOverlays
            ?.firstOrNull { it.id == overlayId } ?: return
        if (textPanelId == overlayId) previewTextPanel(overlayId, transform)
        else applyCommandCoalesced(AssetCommands.UpdateTextOverlay(overlayId, transform(current)))
    }

    fun setSelectionFontSize(sizeSp: Float) =
        updateSelectedText { it.copy(fontSizeSp = sizeSp.coerceIn(8f, 200f)) }

    fun setSelectionTextColor(argb: Int) =
        updateSelectedText { it.copy(colorArgb = argb) }

    fun setSelectionAlignment(alignment: com.videoeditor.core.model.TextAlignment) =
        updateSelectedText { it.copy(alignment = alignment) }

    fun setSelectionOpacity(opacity: Float) =
        updateSelectedText { it.copy(opacity = opacity.coerceIn(0f, 1f)) }

    fun setSelectionBackground(enabled: Boolean) =
        updateSelectedText { it.copy(hasBackground = enabled) }

    fun setSelectionPosition(positionX: Float, positionY: Float) =
        updateSelectedText {
            it.copy(positionX = positionX.coerceIn(0f, 1f), positionY = positionY.coerceIn(0f, 1f))
        }

    /**
     * Creates a draft text overlay at the playhead so the text editor can edit
     * it live in the preview before the user commits. Returns the new overlay's
     * id, or null when there is no visible room (playhead at the very end).
     * The caller deletes it via [deleteSelected] if the user cancels.
     */
    fun addTextDraft(): String? {
        if (textPanelId != null) finishTextEditing(false)
        val state = _uiState.value
        val timeline = state.project?.timeline ?: return null
        val rendered = timeline.renderedDurationMicros
        val defaultDur = DEFAULT_TEXT_DURATION_MICROS
        val (start, duration) = if (rendered <= 0L) {
            0L to defaultDur
        } else if (state.playheadMicros >= rendered - 200_000L) {
            val s = (rendered - defaultDur).coerceAtLeast(0L)
            s to minOf(defaultDur, rendered - s)
        } else {
            val s = state.playheadMicros.coerceIn(0L, rendered)
            s to minOf(defaultDur, rendered - s)
        }
        val overlay = TextOverlay(
            id = Ids.newId(),
            text = "Text",
            maxWidthFraction = .9f,
            hasShadow = true,
            startMicros = start,
            durationMicros = duration,
        )
        textPanelId = overlay.id; textPanelOriginal = null
        _uiState.update { it.copy(project = it.project?.let { project -> project.copy(
            timeline = project.timeline.copy(textOverlays = project.timeline.textOverlays + overlay)) }) }
        previewController.setProject(_uiState.value.project)
        select(TimelineSelection.Text(overlay.id))
        seekTo(start)
        return overlay.id
    }

    /** Restores a text overlay to a prior snapshot (used to cancel an edit). */
    fun restoreTextOverlay(overlay: TextOverlay) {
        applyCommand(AssetCommands.UpdateTextOverlay(overlay.id, overlay))
        select(TimelineSelection.Text(overlay.id))
    }

    // -- history --

    fun undo() {
        val project = _uiState.value.project ?: return
        commitHistoryResult(history.undo(project))
    }

    fun redo() {
        val project = _uiState.value.project ?: return
        commitHistoryResult(history.redo(project))
    }

    private fun commitHistoryResult(project: Project) {
        if (project.mediaRefs == _uiState.value.project?.mediaRefs) {
            commit(project)
        } else viewModelScope.launch {
            val missing = withContext(ioDispatcher) { findMissingMedia(project) }
            _uiState.update { it.copy(missingMediaIds = missing) }
            commit(project)
        }
    }

    // -- stickers --

    /**
     * Adds a bundled sticker at the playhead, stacking it above any existing
     * sticker. The default window runs from the playhead to the earlier of
     * three seconds and the project end, mirroring how PiP placement clamps.
     */
    fun addSticker(assetKey: String) {
        val project = _uiState.value.project ?: return
        if (project.timeline.videoClips.isEmpty() && project.timeline.audioClips.isEmpty()) {
            showMessage("Add a clip before placing stickers")
            return
        }
        if (com.videoeditor.core.media.stickers.StickerCatalog.asset(assetKey) == null) {
            showMessage("That sticker is not available")
            return
        }
        val total = project.timeline.renderedDurationMicros
        if (total <= 0L) { showMessage("Add a clip before placing stickers"); return }
        val start = _uiState.value.playheadMicros.coerceIn(0L, (total - com.videoeditor.core.model.StickerClip.MIN_DURATION_MICROS).coerceAtLeast(0L))
        val duration = minOf(com.videoeditor.core.model.StickerClip.DEFAULT_DURATION_MICROS, total - start)
        if (duration <= 0L) { showMessage("Add a clip before placing stickers"); return }
        val sticker = com.videoeditor.core.model.StickerClip(
            id = Ids.newId(), assetKey = assetKey,
            timelineStartMicros = start, timelineDurationMicros = duration,
            zIndex = (project.timeline.stickers.maxOfOrNull { it.zIndex } ?: -1) + 1,
        )
        applyCommand(com.videoeditor.editing.commands.StickerCommands.AddSticker(sticker))
        select(TimelineSelection.Sticker(sticker.id))
    }

    fun selectSticker(id: String?) {
        if (id == null) { select(TimelineSelection.None); return }
        select(TimelineSelection.Sticker(id))
    }

    fun updateSticker(value: com.videoeditor.core.model.StickerClip) {
        if (com.videoeditor.core.media.stickers.StickerCatalog.asset(value.assetKey) == null) {
            showMessage("That sticker is not available"); return
        }
        applyCommand(com.videoeditor.editing.commands.StickerCommands.UpdateSticker(value.id, value))
    }

    fun deleteSticker(id: String) {
        applyCommand(com.videoeditor.editing.commands.StickerCommands.DeleteSticker(id))
    }

    fun duplicateSticker(id: String) {
        val project = _uiState.value.project ?: return
        val original = project.timeline.stickers.firstOrNull { it.id == id } ?: return
        val newId = Ids.newId()
        // One user action, one history entry: the copy and its down-right nudge
        // undo together rather than as two steps.
        val nudged = original.copy(id = newId,
            zIndex = (project.timeline.stickers.maxOfOrNull { it.zIndex } ?: -1) + 1,
            positionX = (original.positionX + 0.06f).coerceIn(0f, 1f),
            positionY = (original.positionY + 0.06f).coerceIn(0f, 1f))
        applyCommand(com.videoeditor.editing.history.CompositeEditCommand("Duplicate sticker", listOf(
            com.videoeditor.editing.commands.StickerCommands.DuplicateSticker(id, newId),
            com.videoeditor.editing.commands.StickerCommands.UpdateSticker(newId, nudged),
        )))
        select(TimelineSelection.Sticker(newId))
    }

    /** Restores the selected sticker's default transform, timing untouched. */
    fun resetStickerTransform(id: String) {
        val sticker = _uiState.value.project?.timeline?.stickers?.firstOrNull { it.id == id } ?: return
        updateSticker(sticker.copy(positionX = 0.5f, positionY = 0.5f, scale = 1f,
            rotationDegrees = 0f, opacity = 1f))
    }

    /** Moves the sticker one layer up/down among stickers. Deterministic: it
     * swaps z-index with the nearest neighbor rather than renumbering. */
    fun reorderSticker(id: String, forward: Boolean) {
        val project = _uiState.value.project ?: return
        val ordered = project.timeline.stickers.sortedBy { it.zIndex }
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return
        val neighborIndex = if (forward) index + 1 else index - 1
        if (neighborIndex !in ordered.indices) return
        val a = ordered[index]
        val b = ordered[neighborIndex]
        applyCommand(com.videoeditor.editing.history.CompositeEditCommand(
            if (forward) "Bring sticker forward" else "Send sticker backward",
            listOf(
                com.videoeditor.editing.commands.StickerCommands.UpdateSticker(a.id, a.copy(zIndex = b.zIndex)),
                com.videoeditor.editing.commands.StickerCommands.UpdateSticker(b.id, b.copy(zIndex = a.zIndex)),
            ),
        ))
    }

    // Sticker gestures stream transient transforms into the project copy without
    // history, then commit one undoable edit on lift — the same contract as PiP.
    private var stickerSnapshot: com.videoeditor.core.model.StickerClip? = null
    fun beginStickerTransform() {
        previewController.pause()
        stickerSnapshot = _uiState.value.project?.timeline?.stickers?.firstOrNull {
            it.id == _uiState.value.selection.selectedStickerId
        }
    }

    fun previewStickerTransform(update: (com.videoeditor.core.model.StickerClip) -> com.videoeditor.core.model.StickerClip) {
        val original = stickerSnapshot ?: return
        _uiState.update { state -> state.copy(project = state.project?.let { project ->
            project.copy(timeline = project.timeline.copy(stickers = project.timeline.stickers.map {
                if (it.id == original.id) update(it) else it
            }))
        }) }
        scheduleVisualPreview()
    }

    fun endStickerTransform(commitChange: Boolean) {
        previewSyncJob?.cancel()
        val original = stickerSnapshot ?: return
        stickerSnapshot = null
        val project = _uiState.value.project ?: return
        val final = project.timeline.stickers.firstOrNull { it.id == original.id } ?: return
        _uiState.update { it.copy(project = project.copy(timeline = project.timeline.copy(stickers =
            project.timeline.stickers.map { sticker -> if (sticker.id == original.id) original else sticker }))) }
        if (commitChange && original != final) updateSticker(final)
        else previewController.setProject(_uiState.value.project)
    }

    private fun applyCommand(command: EditCommand) {
        val project = _uiState.value.project ?: return
        val next = try { history.apply(project, command) }
        catch (failure: IllegalArgumentException) { showMessage(failure.message ?: "This edit is unavailable."); return }
        catch (failure: ArithmeticException) { showMessage("This edit exceeds the supported timeline range."); return }
        if (next != project) commit(next)
    }

    /**
     * Ensures every audio clip with active ducking has a cached analysis, then
     * asks the preview to rebuild once the curves are available.
     *
     * Analysis decodes trigger audio, so it is bounded and blocking and must
     * never run on the main thread. It runs on the IO dispatcher; on completion
     * the preview is rebuilt on the main thread even though the project's
     * composition signature is unchanged, because the ducking curve is a
     * non-document input to the composition that only lands in the cache now.
     *
     * Enabling ducking therefore previews in two passes: the first rebuild (from
     * the edit) plays the audio un-ducked, and the second — triggered here, a
     * moment later once the trigger levels are known — applies the attenuation.
     * Export does not need this second pass because it resolves analysis before
     * building its composition.
     */
    private fun ensureDuckingAnalysis() {
        val project = _uiState.value.project ?: return
        if (project.timeline.audioClips.none { it.ducking.isActive }) return
        viewModelScope.launch(ioDispatcher) {
            duckingAnalysis.ensureAnalyzed(project)
            withContext(mainDispatcher) {
                if (_uiState.value.project === project) {
                    previewController.rebuildFromCachedAudioAnalysis()
                }
            }
        }
    }

    /** Like [applyCommand] but coalesces the preview rebuild (for text edits). */
    private fun applyCommandCoalesced(command: EditCommand) {
        val project = _uiState.value.project ?: return
        val next = try { history.apply(project, command) }
        catch (failure: IllegalArgumentException) { showMessage(failure.message ?: "This edit is unavailable."); return }
        catch (failure: ArithmeticException) { showMessage("This edit exceeds the supported timeline range."); return }
        if (next != project) commit(next, coalescePreview = true)
    }

    private fun commit(project: Project, coalescePreview: Boolean = false) {
        if(transitionPreviewReturnUs!=null) {
            transitionPreviewJob?.cancel();transitionPreviewJob=null;transitionPreviewReturnUs=null
            previewController.pause()
        }
        _uiState.update { state ->
            val duration = project.timeline.renderedDurationMicros
            val selection = state.selection
            // Drop selections whose element no longer exists (delete, undo).
            val validSelection = when (selection) {
                is TimelineSelection.Video ->
                    if (project.timeline.videoClips.any { it.id == selection.clipId }) {
                        selection
                    } else {
                        TimelineSelection.None
                    }

                is TimelineSelection.Audio ->
                    if (project.timeline.audioClips.any { it.id == selection.audioClipId }) {
                        selection
                    } else {
                        TimelineSelection.None
                    }

                is TimelineSelection.Text ->
                    if (project.timeline.textOverlays.any { it.id == selection.overlayId }) {
                        selection
                    } else {
                        TimelineSelection.None
                    }

                is TimelineSelection.Sticker ->
                    if (project.timeline.stickers.any { it.id == selection.stickerId }) {
                        selection
                    } else {
                        TimelineSelection.None
                    }

                is TimelineSelection.Overlay -> selection.takeIf { value -> project.timeline.overlayClips.any { it.id == value.overlayId } } ?: TimelineSelection.None
                is TimelineSelection.Transition -> selection.takeIf { value -> project.timeline.transitions.any { it.id == value.transitionId } } ?: TimelineSelection.None
                is TimelineSelection.Effect -> selection.takeIf { value ->
                    project.timeline.videoClips.any { it.id == value.clipId && it.effects.any { effect -> effect.id == value.effectId } } ||
                    project.timeline.overlayClips.any { it.id == value.clipId && it.effects.any { effect -> effect.id == value.effectId } }
                } ?: TimelineSelection.None
                TimelineSelection.None -> TimelineSelection.None
            }
            val next = state.copy(
                project = project,
                hasUnsavedChanges = true,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                selection = validSelection,
                // Clamp the playhead when the timeline shrank under it.
                playheadMicros = state.playheadMicros.coerceIn(0L, duration),
            )
            clampViewport(next)
        }
        // Feed the new model to the preview engine; it rebuilds only if the
        // composition-relevant parts actually changed, preserving play state.
        // High-frequency text edits are coalesced into one rebuild after the
        // user pauses, so typing doesn't rebuild the player per keystroke.
        if (coalescePreview) scheduleVisualPreview()
        else {
            previewSyncJob?.cancel()
            val snapshot = _uiState.value
            previewController.setProject(project.takeIf { snapshot.missingMediaIds.isEmpty() })
        }
        if (autosaveEnabled) saveProject()
        ensureDuckingAnalysis()
    }

    fun saveProject(onSaved: (() -> Unit)? = null) {
        val save = applicationScope.launch {
            saveMutex.lock()
            try {
                val visibleProject = _uiState.value.project ?: return@launch
                val draftId = textPanelId
                val draftOriginal = textPanelOriginal
                val project = if (draftId == null) visibleProject else visibleProject.copy(timeline = visibleProject.timeline.copy(
                    textOverlays = if (draftOriginal == null) visibleProject.timeline.textOverlays.filterNot { it.id == draftId }
                    else visibleProject.timeline.textOverlays.map { if (it.id == draftId) draftOriginal!! else it }))
                val result = projectRepository.save(project.copy(updatedAtMillis = System.currentTimeMillis()))
                if (result.isSuccess) {
                    _uiState.update { if (it.project == project) it.copy(hasUnsavedChanges = false) else it }
                    if (onSaved != null) withContext(mainDispatcher) { onSaved() }
                } else showMessage("Project could not be saved. Free storage and try again.")
            } finally { saveMutex.unlock() }
        }
        synchronized(pendingSaves) {
            pendingSaves.removeAll { it.isCompleted }
            pendingSaves += save
        }
    }

    /** Awaits in-flight autosaves; exposed for instrumented-test teardown. */
    @androidx.annotation.VisibleForTesting
    suspend fun awaitPendingSavesForTest() {
        val jobs = synchronized(pendingSaves) { pendingSaves.toList() }
        jobs.forEach { it.join() }
        synchronized(pendingSaves) { pendingSaves.clear() }
    }

    // -- assets --

    /**
     * Loads thumbnail strips and waveforms for the given content at the
     * current zoom. Called from a LaunchedEffect keyed on the timeline
     * signature and zoom bucket, so it re-runs only when the media or the
     * required strip density actually changes — never per scroll pixel.
     * In-flight loads are cancelled with the calling coroutine when a newer
     * request supersedes them.
     */
    suspend fun refreshAssets(content: TimelineContent, thumbHeightPx: Int) {
        val state = _uiState.value
        val project = state.project ?: return
        val viewport = state.viewport() ?: return
        val context = appContext

        // Drop deleted/replaced assets; the active model must not retain every historical trim.
        _assets.update { old -> old.copy(
            strips = old.strips.filterKeys { id -> content.videoItems.any { it.clipId == id } },
            waveforms = old.waveforms.filterKeys { id -> content.audioItems.any { it.audioClipId == id } },
        ) }
        // Video strips ordered so on-screen clips decode first.
        // Stable half-viewport windows give repeat scrolling identical cache keys.
        val windowWidth = maxOf(1f, state.viewportWidthPx / 2f)
        val windowScroll = kotlin.math.floor(state.scrollXPx / windowWidth) * windowWidth
        val visibleStart = viewport.timeAtScreenX(windowScroll, 0f)
        val visibleEnd = viewport.timeAtScreenX(windowScroll + windowWidth, state.viewportWidthPx)
        val ordered = content.videoItems.sortedBy { item ->
            if (item.endMicros >= visibleStart && item.startMicros <= visibleEnd) 0 else 1
        }
        val overscan = (visibleEnd - visibleStart).coerceAtLeast(1L)
        val nearby = ordered.filter { it.endMicros >= visibleStart - overscan && it.startMicros <= visibleEnd + overscan }
        _assets.update { old -> old.copy(strips = old.strips.filterKeys { id -> nearby.any { it.clipId == id } }) }
        for (item in nearby) {
            val mediaRef = project.mediaRef(item.mediaRefId) ?: continue
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val clip = project.timeline.videoClips.firstOrNull { it.id == item.clipId } ?: continue
            val sampleStart = maxOf(item.startMicros, visibleStart - overscan)
            val sampleEnd = minOf(item.endMicros, visibleEnd + overscan)
            if (sampleEnd <= sampleStart) continue
            val sampleSourceStart = clip.sourceTimeAt(sampleStart).coerceIn(clip.sourceStartMicros, clip.sourceEndMicros)
            val sampleSourceEnd = clip.sourceTimeAt(sampleEnd).coerceIn(clip.sourceStartMicros, clip.sourceEndMicros)
            val clipWidthPx = (sampleEnd - sampleStart) * viewport.pixelsPerMicro
            val stripCount = ceil(clipWidthPx / thumbHeightPx.coerceAtLeast(1)).toInt().coerceIn(1, 32)
            val frames = if (mediaRef.type == com.videoeditor.core.model.MediaType.Photo) {
                listOf(timelineMedia.singleThumbnail(context, Uri.parse(mediaRef.uri), 0L, thumbHeightPx))
            } else timelineMedia.thumbnailStrip(
                context,
                Uri.parse(mediaRef.uri),
                // Sample within the clip's source trim range so the strip
                // matches what the composition will play.
                sourceStartMicros = sampleSourceStart,
                sourceEndMicros = sampleSourceEnd,
                requestedCount = stripCount,
                thumbHeightPx = thumbHeightPx,
            )
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (_uiState.value.project?.timeline?.videoClips?.firstOrNull { it.id == clip.id } != clip) continue
            _assets.update {
                val candidates = it.strips + (item.clipId to ClipStrip(
                    frames = frames,
                    sourceStartMicros = sourceStartOf(project, item.clipId) ?: 0L,
                    sourceEndMicros = sourceEndOf(project, item.clipId) ?: 0L,
                    thumbHeightPx = thumbHeightPx,
                    sampledSourceStartMicros = sampleSourceStart,
                    sampledSourceEndMicros = sampleSourceEnd,
                ))
                val retained = linkedMapOf<String, ClipStrip>()
                val bitmaps = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
                var bytes = 0L
                for (candidate in nearby) {
                    val strip = candidates[candidate.clipId] ?: continue
                    val additional = strip.frames.filterNotNull().filter { bitmap -> bitmap !in bitmaps }
                    val extraBytes = additional.sumOf { bitmap -> bitmap.allocationByteCount.toLong() }
                    if (bytes + extraBytes > 12L * 1024 * 1024) continue
                    retained[candidate.clipId] = strip
                    bitmaps.addAll(additional)
                    bytes += extraBytes
                }
                it.copy(strips = retained)
            }
        }
        // Visible/overscan state is bounded too; a bounded repository alone cannot evict UI references.
        val nearbyAudio = content.audioItems.filter { it.endMicros >= visibleStart - overscan && it.startMicros <= visibleEnd + overscan }.take(16)
        _assets.update { it.copy(waveforms = it.waveforms.filterKeys { id -> nearbyAudio.any { it.audioClipId == id } }) }
        for (item in nearbyAudio) {
            val uri = project.mediaRef(item.mediaRefId)?.uri ?: continue
            val peaks = timelineMedia.waveform(
                context,
                Uri.parse(uri),
                item.sourceStartMicros,
                item.sourceEndMicros,
            )
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (_uiState.value.project?.id == project.id &&
                _uiState.value.project?.timeline?.audioClips?.any { it.id == item.audioClipId &&
                    it.sourceStartMicros == item.sourceStartMicros && it.sourceEndMicros == item.sourceEndMicros } == true) {
                _assets.update { it.copy(waveforms = it.waveforms + (item.audioClipId to peaks)) }
            }
        }

    }

    private fun sourceStartOf(project: Project, clipId: String): Long? =
        project.timeline.videoClips.firstOrNull { it.id == clipId }?.sourceStartMicros

    private fun sourceEndOf(project: Project, clipId: String): Long? =
        project.timeline.videoClips.firstOrNull { it.id == clipId }?.sourceEndMicros

    // -- misc UI --

    fun toggleFullscreenPreview() {
        _uiState.update { it.copy(isFullscreenPreview = !it.isFullscreenPreview) }
    }

    fun consumeUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(userMessage = message) }
    }

    override fun onCleared() {
        super.onCleared()
        transitionPreviewJob?.cancel();transitionPreviewJob=null;transitionPreviewReturnUs=null
        _assets.value = TimelineAssets()
        previewController.release()
    }

    /**
     * Deterministic teardown for instrumented tests: cancels [viewModelScope]
     * so no in-flight autosave can touch a database the test closes right
     * after, then releases the preview player and its decoders. Production
     * cleanup goes through [onCleared] via the normal ViewModel lifecycle.
     */
    @androidx.annotation.VisibleForTesting
    fun releaseForTest() {
        viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        transitionPreviewJob?.cancel();transitionPreviewJob=null;transitionPreviewReturnUs=null
        _assets.value = TimelineAssets()
        previewController.release()
    }

    private companion object {
        /** Snap proximity in pixels — a constant screen distance at any zoom. */
        const val SNAP_THRESHOLD_PX = 24f
        const val DEFAULT_AUDIO_DURATION_MICROS = 5_000_000L
        const val DEFAULT_TEXT_DURATION_MICROS = 3_000_000L

        /** Debounce for coalescing rapid text edits into one preview rebuild. */

    }
}
