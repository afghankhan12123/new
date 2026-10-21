package com.videoeditor.feature.editor.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.videoeditor.feature.editor.EditorColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.videoeditor.core.model.VolumeEnvelope
import com.videoeditor.core.model.VolumePoint
import com.videoeditor.core.model.VolumeInterpolation
import com.videoeditor.core.common.util.Ids
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import com.videoeditor.editing.timeline.TimelineSelection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.videoeditor.editing.timeline.TimelineContent
import com.videoeditor.editing.timeline.TimelineGesture
import com.videoeditor.editing.timeline.TimelineHit
import com.videoeditor.editing.timeline.TimelineHitTester
import com.videoeditor.editing.timeline.TimelineLayout
import com.videoeditor.editing.timeline.TrimMath
import com.videoeditor.feature.editor.TimelineAssets
import com.videoeditor.feature.editor.EditorUiState
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.roundToInt

/** Total timeline height; the four rows divide it by [TimelineLayout] weights. */
private val TIMELINE_HEIGHT = 184.dp

/** Trim-handle hit slop, widened well beyond the drawn handle for reliable touch. */
private val HANDLE_HIT_SLOP = 14.dp

/**
 * The interactive, multi-track timeline. One custom-drawn [Canvas] renders the
 * ruler, video thumbnails, purple text clips, and blue audio waveforms; a single
 * structured gesture machine on top resolves taps, scrubbing, pinch-zoom,
 * long-press reorder/free-move, and trim-handle drags.
 *
 * All coordinate math is delegated to the pure [com.videoeditor.editing.timeline.TimelineViewport],
 * [TrimMath], and [com.videoeditor.editing.timeline.TimelineSimulation] layers,
 * so what is drawn and what is hit-tested share one definition and match the
 * committed edit exactly. Gestures mutate only transient ViewModel state; the
 * project changes when a gesture commits through the command layer.
 */
@Composable
fun TimelineView(
    state: EditorUiState,
    assets: TimelineAssets,
    onRefreshAssets: suspend (TimelineContent, thumbHeightPx: Int) -> Unit,
    callbacks: TimelineCallbacks,
    modifier: Modifier = Modifier,
    liveState: State<EditorUiState>? = null,
    onEditTransition: ((String,String)->Unit)? = null,
    onAddMedia: (() -> Unit)? = null,
    expanded: Boolean = false,
    compact: Boolean = false,
    reserveOverlayLane: Boolean = false,
    compactRows: Set<Int> = setOf(TimelineLayout.ROW_VIDEO),
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val viewConfig = LocalViewConfiguration.current
    val hitTester = remember { TimelineHitTester() }
    val textMeasurer = rememberTextMeasurer()

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val timelineHeight = when {
        compact -> if (compactRows.size > 1) 126.dp else 100.dp
        expanded -> 260.dp
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE -> 140.dp
        else -> TIMELINE_HEIGHT
    }
    val hiddenRows = if (compact) setOf(TimelineLayout.ROW_TEXT, TimelineLayout.ROW_AUDIO) - compactRows else emptySet()
    val baseHeight = if (compact) 184.dp else TIMELINE_HEIGHT
    val geometry = remember(state.project?.timeline) { state.project?.timeline?.let(com.videoeditor.editing.timeline.TimelineGeometry::from) ?: com.videoeditor.editing.timeline.TimelineGeometry() }
    val visibleHeight = (0..3).filterNot { it in hiddenRows }.sumOf { geometry.rowHeight(it).toDouble() }.toFloat()
    val contentHeight = if (compact) maxOf(timelineHeight, baseHeight * (visibleHeight / TimelineLayout.TOTAL_HEIGHT)) else baseHeight * (geometry.totalHeight / TimelineLayout.TOTAL_HEIGHT)
    val heightPx = with(density) { baseHeight.toPx() }
    val pxPerUnit = heightPx / TimelineLayout.TOTAL_HEIGHT
    val handleSlopPx = with(density) { HANDLE_HIT_SLOP.toPx() }
    val touchSlop = viewConfig.touchSlop
    val longPressMillis = viewConfig.longPressTimeoutMillis

    val project = state.project
    // Derived from the model only — recomputed on structural edits, never
    // during scroll or zoom (those are pure viewport transforms).
    val content = remember(project) {
        project?.let { TimelineContent.from(it) } ?: TimelineContent.EMPTY
    }

    var automationDraft by remember(state.selection, project) { mutableStateOf<VolumeEnvelope?>(null) }
    var selectedPointId by remember(state.selection) { mutableStateOf<String?>(null) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val videoRowHeightPx =
        (heightPx * TimelineLayout.VIDEO_HEIGHT / TimelineLayout.TOTAL_HEIGHT).toInt()

    // Strips reload only when the media or the required thumbnail density
    // changes across a zoom bucket — not on every scroll pixel.
    val assetZoomBucket = zoomBucket(state.zoomPixelsPerMicro)
    val assetSignature = remember(content, assetZoomBucket) {
        buildString {
            append(assetZoomBucket)
            for (v in content.videoItems) {
                append("|v").append(v.clipId).append(':')
                    .append(v.startMicros).append(':').append(v.endMicros)
            }
            for (a in content.audioItems) {
                append("|a").append(a.audioClipId).append(':')
                    .append(a.sourceStartMicros).append(':').append(a.sourceEndMicros)
            }
        }
    }
    // Refresh at half-screen boundaries with a full-screen overscan, rather than retaining
    // decoded strips for an arbitrarily long project. Pixel movement only redraws Canvas.
    val assetWindow = (state.scrollXPx / maxOf(1f, widthPx / 2f)).toInt()
    LaunchedEffect(assetSignature, project?.timeline?.videoClips, if (liveState == null) assetWindow else null, videoRowHeightPx, widthPx) {
        if (project != null && widthPx > 0f && state.zoomPixelsPerMicro > 0f) {
            if (liveState == null) onRefreshAssets(content, videoRowHeightPx)
            else snapshotFlow { (liveState.value.scrollXPx / maxOf(1f, widthPx / 2f)).toInt() }
                .distinctUntilChanged().collectLatest { onRefreshAssets(content, videoRowHeightPx) }
        }
    }

    // Latest callbacks/state for the long-lived gesture loop without restarting
    // it (restarting pointerInput would cancel an in-flight gesture).
    val currentState by rememberUpdatedState(state)
    val currentContent by rememberUpdatedState(content)
    val currentCallbacks by rememberUpdatedState(callbacks)

    val accessibleActions = remember(state.project?.timeline, state.selection, selectedPointId, state.playheadMicros, compact) {
        val snapshot = state
        buildList {
            val selection = snapshot.selection
            val clipId = when (selection) {
                is TimelineSelection.Video -> selection.clipId
                is TimelineSelection.Audio -> selection.audioClipId
                else -> null
            }
            if (selection is TimelineSelection.Audio && !compact) {
                val clip = snapshot.project?.timeline?.audioClips?.firstOrNull { it.id == selection.audioClipId }
                if (clip != null) {
                    add(CustomAccessibilityAction("Add volume point at playhead") {
                        val livePlayhead = (liveState?.value ?: currentState).playheadMicros
                        val source = (clip.sourceStartMicros + (livePlayhead - clip.timelineStartMicros) * clip.playbackSpeed.toDouble())
                            .toLong().coerceIn(clip.sourceStartMicros, clip.sourceEndMicros)
                        val point = clip.volumeEnvelope.points.firstOrNull { it.sourcePositionMicros == source }
                            ?: VolumePoint(Ids.newId(), source, clip.volumeEnvelope.gainDbAt(source))
                        selectedPointId = point.id
                        if (clip.volumeEnvelope.pointById(point.id) == null) currentCallbacks.onAudioEnvelope(clip.id,
                            VolumeEnvelope(clip.volumeEnvelope.points + point).normalized())
                        true
                    })
                    val point = selectedPointId?.let(clip.volumeEnvelope::pointById)
                    if (point != null) {
                        add(CustomAccessibilityAction("Delete selected volume point") {
                            currentCallbacks.onAudioEnvelope(clip.id, clip.volumeEnvelope.withoutPoint(point.id)); selectedPointId = null; true
                        })
                        VolumeInterpolation.entries.forEach { curve ->
                            add(CustomAccessibilityAction("Volume interpolation ${curve.name}") {
                                currentCallbacks.onAudioEnvelope(clip.id, clip.volumeEnvelope.withPoint(point.copy(interpolation = curve))); true
                            })
                        }
                    }
                }
            }
            val audio = selection is TimelineSelection.Audio
            val photo = snapshot.project?.timeline?.videoClips?.firstOrNull { it.id == clipId }?.isPhoto == true
            if (clipId != null && !photo) {
                for (edge in TrimMath.Edge.entries) for (direction in listOf(-1, 1)) {
                    add(CustomAccessibilityAction("Trim ${edge.name.lowercase()} ${if (direction < 0) "earlier" else "later"} by 0.1 seconds") {
                        val current = liveState?.value ?: currentState
                        if (currentCallbacks.onTrimBegin(clipId, edge, audio)) {
                            currentCallbacks.onTrimBy(direction * 100_000f * current.zoomPixelsPerMicro, audio)
                            currentCallbacks.onTrimEnd(true, audio)
                            true
                        } else false
                    })
                }
            }
            val timeline = snapshot.project?.timeline
            val itemCount = (timeline?.videoClips?.size ?: 0) +
                (timeline?.textOverlays?.size ?: 0) + (timeline?.audioClips?.size ?: 0)
            // Compose has 32 custom-action IDs per widget. Reserve ten for trim and automation.
            // Large documents use track navigation, keeping every clip reachable.
            if (itemCount > 22) {
                val selectedId = when (selection) {
                    is TimelineSelection.Video -> selection.clipId
                    is TimelineSelection.Audio -> selection.audioClipId
                    is TimelineSelection.Text -> selection.overlayId
                    else -> null
                }
                fun navigate(label: String, items: List<Pair<String, TimelineHit>>) {
                    if (items.isEmpty()) return
                    for (direction in listOf(-1, 1)) {
                        add(CustomAccessibilityAction("Select ${if (direction < 0) "previous" else "next"} $label") {
                            val index = items.indexOfFirst { it.first == selectedId }
                            val next = if (index < 0) {
                                if (direction < 0) items.lastIndex else 0
                            } else Math.floorMod(index + direction, items.size)
                            currentCallbacks.onTap(items[next].second)
                            true
                        })
                    }
                }
                navigate("visual clip", timeline?.videoClips.orEmpty().map { it.id to TimelineHit.VideoClip(it.id, null) })
                navigate("title", timeline?.textOverlays.orEmpty().map { it.id to TimelineHit.TextClip(it.id) })
                navigate("audio clip", timeline?.audioClips.orEmpty().map { it.id to TimelineHit.AudioClip(it.id) })
            } else {
                snapshot.project?.timeline?.videoClips?.forEachIndexed { index, clip ->
                    add(CustomAccessibilityAction("Select visual clip ${index + 1}") {
                        currentCallbacks.onTap(TimelineHit.VideoClip(clip.id, null)); true
                    })
                }
                snapshot.project?.timeline?.textOverlays?.forEachIndexed { index, text ->
                    add(CustomAccessibilityAction("Select title ${index + 1}: ${text.text}") {
                        currentCallbacks.onTap(TimelineHit.TextClip(text.id)); true
                    })
                }
                snapshot.project?.timeline?.audioClips?.forEachIndexed { index, audioClip ->
                    add(CustomAccessibilityAction("Select audio clip ${index + 1}") {
                        currentCallbacks.onTap(TimelineHit.AudioClip(audioClip.id)); true
                    })
                }
            }
        }
    }

    Box(modifier = modifier.testTag("timeline-viewport").fillMaxWidth().height(timelineHeight).verticalScroll(rememberScrollState())) {
    Canvas(
        modifier = Modifier
            .testTag("timeline")
            .semantics {
                fun snapshotValues(): List<String> {
                    val snapshot = liveState?.value ?: currentState
                    return listOf(
                        snapshot.preview.actualPositionMicros.toString(),
                        snapshot.playheadMicros.toString(),
                        (snapshot.viewport()?.playheadTimeMicros(snapshot.scrollXPx) ?: 0L).toString(),
                        snapshot.scrollXPx.toString(), snapshot.zoomPixelsPerMicro.toString(),
                        snapshot.preview.durationMicros.toString(), snapshot.isPlaying.toString(),
                    )
                }
                contentDescription = "Editing timeline. Drag to seek, pinch to zoom. Select a clip to trim; long press to reorder."
                // Canvas handles are not separate accessibility nodes. Expose
                // bounded steps through the same gesture/undo path as touch.
                customActions = accessibleActions
                this[TimelineDiagnostics.Snapshot] = snapshotValues()
                this[TimelineDiagnostics.LiveSnapshot] = { snapshotValues() }
            }
            .fillMaxWidth()
            .height(contentHeight)
            .onSizeChanged { size ->
                widthPx = size.width.toFloat()
                callbacks.onViewportSize(size.width.toFloat(), size.width / 2f)
            }
            .pointerInput(pxPerUnit, handleSlopPx, touchSlop, longPressMillis) {
                timelineGesture(
                    getState = { liveState?.value ?: currentState },
                    hitTester = hitTester,
                    navigationOnly = compact,
                    automationDensity = density.density,
                    automationHiddenRows = hiddenRows,
                    onAutomationSelect = { selectedPointId = it },
                    onAutomationDraft = { automationDraft = it },
                    widthPx = { widthPx },
                    pxPerUnit = pxPerUnit,
                    handleSlopPx = handleSlopPx,
                    touchSlop = touchSlop,
                    longPressMillis = longPressMillis,
                    haptics = { haptics.performHapticFeedback(it) },
                    callbacks = { currentCallbacks },
                )
            },
    ) {
        val current = liveState?.value ?: currentState
        val currentProject = current.project ?: return@Canvas
        val viewport = current.viewport() ?: return@Canvas
        android.os.Trace.beginSection("Timeline.draw")
        try { drawTimeline(
            project = currentProject,
            content = currentContent,
            viewport = viewport,
            scrollXPx = current.scrollXPx,
            selection = current.selection,
            gesture = current.gesture,
            assets = assets,
            pxPerUnit = pxPerUnit,
            textMeasurer = textMeasurer,
            density = density.density,
            hiddenRows = hiddenRows,
            automationDraft = automationDraft,
            selectedPointId = selectedPointId,
        ) } finally { android.os.Trace.endSection() }
    }
    // Fixed lane badges make the multi-track hierarchy readable while clips scroll below them.
    Column(Modifier.width(30.dp).background(com.videoeditor.core.designsystem.color.VETimelineColor.Background)) {
        Spacer(Modifier.height(baseHeight * (TimelineLayout.RULER_HEIGHT / TimelineLayout.TOTAL_HEIGHT)))
        listOf(Triple(TimelineLayout.ROW_VIDEO, Icons.Outlined.Videocam, "Video track"),
            Triple(TimelineLayout.ROW_TEXT, Icons.Outlined.TextFields, "Text track"),
            Triple(TimelineLayout.ROW_AUDIO, Icons.Outlined.MusicNote, "Audio track")).filterNot { it.first in hiddenRows }.forEach { (row, icon, label) ->
            Box(Modifier.width(34.dp).height(baseHeight * (geometry.rowHeight(row) / TimelineLayout.TOTAL_HEIGHT)).padding(3.dp)
    , contentAlignment = Alignment.Center) {
                com.videoeditor.feature.editor.EditorIcon(icon, label, tint = EditorColor.DarkSecondaryText, modifier = Modifier.size(if (compact) 12.dp else 16.dp))
            }
        }
    }
    if(onEditTransition!=null) com.videoeditor.feature.editor.TransitionJoinIndicators(state,liveState,pxPerUnit,onEditTransition)
    if(onAddMedia!=null) TrailingAddMediaIndicator(state,liveState,pxPerUnit,onAddMedia)
    }
}

/** Prominent Add Media (+) button trailing at the end of the primary video track. */
@Composable
internal fun TrailingAddMediaIndicator(
    state: EditorUiState,
    live: State<EditorUiState>?,
    pxPerUnit: Float,
    onAddMedia: () -> Unit,
) {
    val density = LocalDensity.current
    val touchPx = with(density) { 48.dp.toPx() }
    val clips = state.project?.timeline?.videoClips.orEmpty()
    val durationMicros = state.project?.timeline?.videoTrackDurationMicros ?: 0L

    val current = live?.value ?: state
    val viewport = current.viewport() ?: return

    val endX = if (clips.isEmpty()) {
        current.viewportWidthPx / 2f
    } else {
        viewport.screenXAtTime(current.scrollXPx, durationMicros)
    }

    val isVisible = clips.isEmpty() || (endX >= -touchPx && endX <= current.viewportWidthPx + touchPx)
    if (!isVisible) return

    val buttonOffsetX = if (clips.isEmpty()) {
        (endX - touchPx / 2f).roundToInt()
    } else {
        (endX + with(density) { 8.dp.toPx() }).roundToInt()
    }
    val buttonOffsetY = ((TimelineLayout.RULER_HEIGHT + TimelineLayout.VIDEO_HEIGHT / 2) * pxPerUnit - touchPx / 2f).roundToInt()

    Box(
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset(buttonOffsetX, buttonOffsetY) }
            .size(48.dp)
            .testTag("timeline-add-media-container"),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onAddMedia,
            modifier = Modifier
                .size(36.dp)
                .background(EditorColor.PrimaryBlue, RoundedCornerShape(8.dp))
                .border(1.dp, EditorColor.DarkBorder, RoundedCornerShape(8.dp))
                .testTag("timeline-add-media")
                .semantics { contentDescription = "Add media to timeline" },
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Zoom bucket so strip density changes only across meaningful zoom steps. */
private fun zoomBucket(pixelsPerMicro: Float): Int =
    if (pixelsPerMicro <= 0f) 0 else floor(log2(pixelsPerMicro * 1_000_000f)).toInt()

/** Every gesture the timeline can start, wired to the ViewModel by the screen. */
class TimelineCallbacks(
    val onViewportSize: (widthPx: Float, playheadXpx: Float) -> Unit,
    val onTap: (TimelineHit) -> Unit,
    val onScrubBegin: () -> Boolean,
    val onScrubBy: (dxPx: Float) -> Unit,
    val onScrollEnd: () -> Unit,
    val onFling: (velocityXPx: Float) -> Unit = { _ -> onScrollEnd() },
    val onZoomBegin: (focalPx: Float) -> Boolean,
    val onZoomBy: (scale: Float, centroidPx: Float) -> Unit,
    val onZoomEnd: () -> Unit,
    val onTrimBegin: (clipId: String, edge: TrimMath.Edge, isAudio: Boolean) -> Boolean,
    val onTrimBy: (totalDxPx: Float, isAudio: Boolean) -> Unit,
    val onTrimEnd: (commit: Boolean, isAudio: Boolean) -> Unit,
    val onReorderBegin: (clipId: String) -> Boolean,
    val onReorderTo: (dragCenterPx: Float) -> Unit,
    val onReorderEnd: (commit: Boolean) -> Unit,
    val onFreeMoveBegin: (track: TimelineGesture.MovingFree.Track, itemId: String) -> Boolean,
    val onFreeMoveBy: (totalDxPx: Float) -> Unit,
    val onFreeMoveEnd: (commit: Boolean) -> Unit,
    val onAudioEnvelope: (clipId: String, envelope: VolumeEnvelope) -> Unit = { _, _ -> },
    val onTextTrimBegin: (String, TrimMath.Edge) -> Boolean = { _, _ -> false },
    val onTextTrimBy: (Float) -> Unit = {},
    val onTextTrimEnd: (Boolean) -> Unit = {},
)

/** Secondary rows share navigation with the main strip, including fling cancellation and pinch ownership. */
@Composable
internal fun Modifier.timelineLaneInput(callbacks: TimelineCallbacks?, state: () -> EditorUiState,
    onTap: (Float) -> Unit): Modifier {
    if (callbacks == null) return this.then(Modifier.pointerInput(Unit) {
        detectTapGestures { onTap(it.x) }
    })
    val latestCallbacks by rememberUpdatedState(callbacks)
    val latestState by rememberUpdatedState(state)
    val latestTap by rememberUpdatedState(onTap)
    val config = LocalViewConfiguration.current
    return this.pointerInput(Unit) {
        timelineGesture(getState = { latestState() }, hitTester = TimelineHitTester(), navigationOnly = true,
            automationDensity = 1f, automationHiddenRows = emptySet(), onAutomationSelect = {}, onAutomationDraft = {},
            widthPx = { size.width.toFloat() }, pxPerUnit = 1f, handleSlopPx = 0f,
            touchSlop = config.touchSlop, longPressMillis = config.longPressTimeoutMillis, haptics = {},
            callbacks = { latestCallbacks }, onNavigationTap = { latestTap(it) })
    }
}

/** The gesture mode resolved for the current pointer stream. */
private enum class GestureMode {
    Undecided, Tap, Cancelled, Scrub, Trim, TextTrim, Reorder, FreeMove, Zoom,
}

/**
 * The timeline gesture machine. One pointer stream resolves to exactly one
 * mode: unambiguous targets (trim handle, ruler) start immediately; a clip or
 * empty area races a long-press against first movement — long-press begins a
 * reorder/free-move, earlier movement begins a scrub, and an early lift is a
 * tap (selection). A second pointer anywhere switches to pinch-zoom.
 */
private suspend fun PointerInputScope.timelineGesture(
    getState: () -> EditorUiState,
    hitTester: TimelineHitTester,
    navigationOnly: Boolean = false,
    automationDensity: Float,
    automationHiddenRows: Set<Int>,
    onAutomationSelect: (String?) -> Unit,
    onAutomationDraft: (VolumeEnvelope?) -> Unit,
    widthPx: () -> Float,
    pxPerUnit: Float,
    handleSlopPx: Float,
    touchSlop: Float,
    longPressMillis: Long,
    haptics: (HapticFeedbackType) -> Unit,
    callbacks: () -> TimelineCallbacks,
    onNavigationTap: ((Float) -> Unit)? = null,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downX = down.position.x
        val downTime = down.uptimeMillis

        val state = getState()
        val viewport = state.viewport()
        val project = state.project
        if (viewport == null || project == null || widthPx() <= 0f) {
            return@awaitEachGesture // Nothing loaded yet.
        }
        val cb = callbacks()
        val automation = automationGeometry(state, pxPerUnit, automationDensity, automationHiddenRows)
        if (!navigationOnly && automation != null && editAutomationPoint(down, automation, state, automationDensity,
                longPressMillis, onAutomationSelect, onAutomationDraft, cb.onAudioEnvelope)) return@awaitEachGesture
        val hit = if (navigationOnly) com.videoeditor.editing.timeline.TimelineHit.Ruler else hitTester.hitTest(
            project = project,
            viewport = viewport,
            scrollXPx = state.scrollXPx,
            screenXPx = downX,
            y = down.position.y / pxPerUnit,
            selection = state.selection,
            handleHitSlopPx = handleSlopPx,
            geometry = com.videoeditor.editing.timeline.TimelineGeometry.from(project.timeline),
        )

        var mode = GestureMode.Undecided
        var trimIsAudio = false
        var lastX = downX

        // Begin unambiguous gestures immediately.
        when (hit) {
            is TimelineHit.VideoClip -> {
                val edge = hit.handle
                if (edge != null && cb.onTrimBegin(hit.clipId, edge, false)) {
                    mode = GestureMode.Trim
                }
            }

            is TimelineHit.AudioClip -> {
                val edge = hit.handle
                if (edge != null && cb.onTrimBegin(hit.audioClipId, edge, true)) {
                    mode = GestureMode.Trim
                    trimIsAudio = true
                }
            }

            is TimelineHit.TextClip -> hit.handle?.let { edge ->
                if (cb.onTextTrimBegin(hit.overlayId, edge)) mode = GestureMode.TextTrim
            }
            TimelineHit.Ruler -> if (cb.onScrubBegin()) mode = GestureMode.Scrub
            else -> Unit
        }

        // Phase 1: for undecided targets, race long-press vs first move vs up.
        if (mode == GestureMode.Undecided) {
            val resolved = resolveUndecided(
                down = down,
                downX = downX,
                downTime = downTime,
                hit = hit,
                touchSlop = touchSlop,
                longPressMillis = longPressMillis,
                haptics = haptics,
                cb = cb,
            )
            mode = resolved.mode
            lastX = resolved.lastX
            if (mode == GestureMode.Zoom) {
                cb.onZoomBegin(resolved.zoomFocalX)
                runZoomLoop(cb)
                return@awaitEachGesture
            }
        }

        // Phase 2: drag loop for the resolved mode; it commits on lift.
        if (mode == GestureMode.Cancelled) return@awaitEachGesture
        if (mode != GestureMode.Tap && mode != GestureMode.Undecided) {
            runDragLoop(mode, trimIsAudio, downX, lastX, cb, getState, haptics,
                onNavigationTap, touchSlop, down.position.y)
        } else {
            cb.onTap(hit)
        }
    }
}

/** Result of the long-press/move/up race for an initially-undecided target. */
private data class ResolvedGesture(
    val mode: GestureMode,
    val lastX: Float,
    /** Pinch centroid when a second finger arrived during the race. */
    val zoomFocalX: Float = 0f,
)

/** Outcome of the long-press/move/up race: mode, latest x, and audio-trim flag. */
private suspend fun AwaitPointerEventScope.resolveUndecided(
    down: PointerInputChange,
    downX: Float,
    downTime: Long,
    hit: TimelineHit,
    touchSlop: Float,
    longPressMillis: Long,
    haptics: (HapticFeedbackType) -> Unit,
    cb: TimelineCallbacks,
): ResolvedGesture {
    var lastX = downX
    while (true) {
        // Race an absolute long-press deadline against the next pointer event.
        // The deadline is measured from the down event, not restarted per
        // event: restarting it would let sub-slop finger jitter defer the
        // long-press indefinitely, so a held press would never reorder.
        val remaining = longPressMillis - (currentEvent.changes.maxOf { it.uptimeMillis } - downTime)
        val event: PointerEvent? = if (remaining <= 0L) {
            null
        } else {
            withTimeoutOrNull(remaining) { awaitPointerEvent() }
        }

        if (event == null) {
            // Long-press fired (finger held still). Begin move/reorder.
            val began = when (hit) {
                is TimelineHit.VideoClip -> cb.onReorderBegin(hit.clipId)
                    .takeIf { it }?.let { GestureMode.Reorder }

                is TimelineHit.AudioClip ->
                    cb.onFreeMoveBegin(TimelineGesture.MovingFree.Track.Audio, hit.audioClipId)
                        .takeIf { it }?.let { GestureMode.FreeMove }

                is TimelineHit.TextClip ->
                    cb.onFreeMoveBegin(TimelineGesture.MovingFree.Track.Text, hit.overlayId)
                        .takeIf { it }?.let { GestureMode.FreeMove }

                else -> null
            }
            if (began != null) haptics(HapticFeedbackType.LongPress)
            return ResolvedGesture(began ?: GestureMode.Tap, lastX)
        }

        val changes = event.changes
        if (changes.size >= 2) {
            // A second finger: hand off to the zoom loop (started by the caller).
            val focal = centroidX(changes)
            changes.forEach { it.consume() }
            return ResolvedGesture(GestureMode.Zoom, lastX, focal)
        }
        val change = changes.first()
        if (!change.pressed) {
            // Lifted before moving or long-pressing → tap.
            return ResolvedGesture(if (change.isConsumed) GestureMode.Cancelled else GestureMode.Tap, change.position.x)
        }
        val dx = change.position.x - downX
        val dy = change.position.y - down.position.y
        if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
            return ResolvedGesture(GestureMode.Cancelled, change.position.x)
        }
        if (abs(dx) > touchSlop) {
            // Moved first → scrub the timeline (if the ViewModel accepts it).
            lastX = change.position.x
            val mode = if (cb.onScrubBegin()) GestureMode.Scrub else GestureMode.Tap
            return ResolvedGesture(mode, lastX)
        }
        // Still holding within slop; keep waiting for long-press or move.
    }
}

/** Drives the pointer loop for a scrub/trim/reorder/free-move, or escalates to zoom. */
private suspend fun AwaitPointerEventScope.runDragLoop(
    initialMode: GestureMode,
    trimIsAudio: Boolean,
    downX: Float,
    startLastX: Float,
    cb: TimelineCallbacks,
    getState: () -> EditorUiState,
    haptics: (HapticFeedbackType) -> Unit,
    onNavigationTap: ((Float) -> Unit)? = null,
    touchSlop: Float = 0f,
    downY: Float = 0f,
) {
    val mode = initialMode
    val isAudio = trimIsAudio
    var lastX = startLastX
    var snapLatch = false
    var ended = false
    var moved = false
    val velocityTracker = VelocityTracker()
    currentEvent.changes.firstOrNull()?.let { velocityTracker.addPosition(it.uptimeMillis, it.position) }
    try {
        while (true) {
            val event = awaitPointerEvent()
            val changes = event.changes
            if (changes.none { it.pressed }) {
                // Compose synthesizes consumed up changes for ACTION_CANCEL.
                if (mode == GestureMode.Scrub && onNavigationTap != null && !moved && changes.none { it.isConsumed }) {
                    cb.onScrollEnd()
                    onNavigationTap(downX)
                } else if (mode == GestureMode.Scrub && changes.none { it.isConsumed }) {
                    changes.firstOrNull()?.let { velocityTracker.addPosition(it.uptimeMillis, it.position) }
                    val velocity = velocityTracker.calculateVelocity()
                    if (abs(velocity.x) > 200f) {
                        cb.onFling(velocity.x)
                    } else {
                        cb.onScrollEnd()
                    }
                } else {
                    endMode(mode, cb, isAudio, commit = changes.none { it.isConsumed })
                }
                ended = true
                return
            }

            if (changes.size >= 2) {
                // A second finger mid-drag: cancel the drag, begin zoom instead.
                endMode(mode, cb, isAudio, false)
                ended = true
                cb.onZoomBegin(centroidX(changes))
                runZoomLoop(cb)
                return
            }

            val change = changes.first()
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            val x = change.position.x
            if (onNavigationTap != null && !moved && abs(change.position.y - downY) > touchSlop &&
                abs(change.position.y - downY) > abs(x - downX)) {
                endMode(mode, cb, isAudio, false)
                ended = true
                return
            }
            moved = moved || abs(x - downX) > touchSlop
            when (mode) {
                GestureMode.Scrub -> {
                    cb.onScrubBy(x - lastX)
                    lastX = x
                }

                GestureMode.Trim -> cb.onTrimBy(x - downX, isAudio)
                GestureMode.TextTrim -> cb.onTextTrimBy(x - downX)

                GestureMode.Reorder -> {
                    cb.onReorderTo(x)
                    snapLatch = edgeSnapHaptic(getState().gesture, snapLatch, haptics)
                }

                GestureMode.FreeMove -> {
                    cb.onFreeMoveBy(x - downX)
                    snapLatch = edgeSnapHaptic(getState().gesture, snapLatch, haptics)
                }

                else -> Unit
            }
            change.consume()
        }

    } finally {
        // Disposal or pointer-input cancellation must also discard proposals.
        if (!ended) endMode(mode, cb, isAudio, false)
    }
}

/** Drives the two-finger pinch loop until the pointers lift. */
private suspend fun AwaitPointerEventScope.runZoomLoop(
    cb: TimelineCallbacks,
) {
    // The second pointer's down event is already available when zoom begins.
    // Starting on the next move discards its scale and makes inverse pinches drift.
    val initialPointers = currentEvent.changes.filter { it.pressed }
    var lastDistance = pinchDistance(initialPointers)
    var lastCentroid = centroidX(initialPointers)
    try {
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size < 2) break
            val distance = pinchDistance(pressed)
            val centroid = centroidX(pressed)
            if (lastDistance > 0f && distance > 0f) {
                val scale = distance / lastDistance
                if (scale != 1f || centroid != lastCentroid) {
                    cb.onZoomBy(scale, centroid)
                }
            }
            lastDistance = distance
            lastCentroid = centroid
            pressed.forEach { it.consume() }
        }
    } finally {
        cb.onZoomEnd()
    }
}

private fun endMode(mode: GestureMode, cb: TimelineCallbacks, isAudio: Boolean, commit: Boolean) {
    when (mode) {
        GestureMode.Scrub -> cb.onScrollEnd()
        GestureMode.Trim -> cb.onTrimEnd(commit, isAudio)
        GestureMode.TextTrim -> cb.onTextTrimEnd(commit)
        GestureMode.Reorder -> cb.onReorderEnd(commit)
        GestureMode.FreeMove -> cb.onFreeMoveEnd(commit)
        GestureMode.Zoom -> cb.onZoomEnd()
        else -> Unit
    }
}

/**
 * Returns the updated latch and fires one haptic on each *transition* into a
 * snapped state, so dragging along a magnet gives a single tick rather than a
 * continuous buzz.
 */
private fun edgeSnapHaptic(
    gesture: TimelineGesture,
    latch: Boolean,
    haptics: (HapticFeedbackType) -> Unit,
): Boolean {
    val snapped = when (gesture) {
        is TimelineGesture.Reordering -> gesture.isSnapped
        is TimelineGesture.MovingFree -> gesture.isSnapped
        else -> false
    }
    if (snapped && !latch) haptics(HapticFeedbackType.TextHandleMove)
    return snapped
}

private fun centroidX(changes: List<PointerInputChange>): Float =
    changes.map { it.position.x }.average().toFloat()

private fun pinchDistance(changes: List<PointerInputChange>): Float {
    if (changes.size < 2) return 0f
    val a = changes[0].position
    val b = changes[1].position
    return hypot(a.x - b.x, a.y - b.y)
}
