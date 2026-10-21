package com.videoeditor.editing.timeline

import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.TextOverlay
import com.videoeditor.core.model.Timeline
import kotlin.math.abs

/**
 * The vertical geometry of the timeline: which tracks exist, in what order,
 * and how tall each is. Values are unit-agnostic "rows" — the Compose layer
 * multiplies by a dp constant, hit-testing divides back — so drawing and
 * touch resolution provably agree.
 *
 * Track order follows the reference design: ruler, video, text, audio. Empty
 * tracks still reserve their row (the audio track is where the user adds
 * music; hiding it would make the add-audio action undiscoverable), but the
 * layout reports which rows actually hold content.
 */
object TimelineLayout {
    /** Row indexes, top to bottom. */
    const val ROW_RULER = 0
    const val ROW_VIDEO = 1
    const val ROW_TEXT = 2
    const val ROW_AUDIO = 3

    /** Relative row heights (ruler : video : text : audio). */
    const val RULER_HEIGHT = 0.55f
    const val VIDEO_HEIGHT = 1.75f
    const val TEXT_HEIGHT = 0.95f
    const val AUDIO_HEIGHT = 0.95f

    const val TOTAL_HEIGHT =
        RULER_HEIGHT + VIDEO_HEIGHT + TEXT_HEIGHT + AUDIO_HEIGHT

    /** Trim handles are this wide on screen; hit area is expanded separately. */
    const val TRIM_HANDLE_WIDTH_UNITS = 0.35f

    /** Which row a vertical offset falls in, or null when outside all rows. */
    fun rowAtY(y: Float): Int? {
        if (y < 0f || y >= TOTAL_HEIGHT) return null
        var cursor = 0f
        for ((row, height) in listOf(
            ROW_RULER to RULER_HEIGHT,
            ROW_VIDEO to VIDEO_HEIGHT,
            ROW_TEXT to TEXT_HEIGHT,
            ROW_AUDIO to AUDIO_HEIGHT,
        )) {
            if (y < cursor + height) return row
            cursor += height
        }
        return null
    }

    /** Top offset of [row] in layout units. */
    fun rowTop(row: Int): Float = when (row) {
        ROW_RULER -> 0f
        ROW_VIDEO -> RULER_HEIGHT
        ROW_TEXT -> RULER_HEIGHT + VIDEO_HEIGHT
        ROW_AUDIO -> RULER_HEIGHT + VIDEO_HEIGHT + TEXT_HEIGHT
        else -> error("unknown row $row")
    }

    fun rowHeight(row: Int): Float = when (row) {
        ROW_RULER -> RULER_HEIGHT
        ROW_VIDEO -> VIDEO_HEIGHT
        ROW_TEXT -> TEXT_HEIGHT
        ROW_AUDIO -> AUDIO_HEIGHT
        else -> error("unknown row $row")
    }
}


/** Stable interval partition: overlapping items get separate selectable lanes.
 * Touching endpoints can share a lane; timing and document order are unchanged. */
object TrackLanes {
    fun assign(intervals: List<Triple<String, Long, Long>>): Map<String, Int> {
        val ends = mutableListOf<Long>()
        val lanes = mutableMapOf<String, Int>()
        for ((id, start, end) in intervals.sortedWith(compareBy({ it.second }, { it.first }))) {
            val available = ends.indexOfFirst { it <= start }
            val lane = if (available < 0) ends.size.also { ends.add(end) }
                else available.also { ends[it] = end }
            lanes[id] = lane
        }
        return lanes
    }
    fun text(timeline: Timeline) = assign(timeline.textOverlays.map {
        Triple(it.id, it.startMicros, it.startMicros + it.durationMicros)
    })
    fun audio(timeline: Timeline) = assign(timeline.audioClips.map {
        Triple(it.id, it.timelineStartMicros, it.timelineStartMicros + it.timelineDurationMicros)
    })
    fun count(lanes: Map<String, Int>) = (lanes.values.maxOrNull()?.plus(1) ?: 1)
    fun at(y: Float, row: Int, lanes: Map<String, Int>): Int =
        (((y - TimelineLayout.rowTop(row)) / TimelineLayout.rowHeight(row)) * count(lanes))
            .toInt().coerceIn(0, count(lanes) - 1)
}

/** Expand overlapping tracks instead of squeezing their selectable lanes. */
data class TimelineGeometry(val textLanes: Int = 1, val audioLanes: Int = 1) {
    fun rowHeight(row: Int): Float = TimelineLayout.rowHeight(row) * when (row) {
        TimelineLayout.ROW_TEXT -> textLanes.coerceAtLeast(1)
        TimelineLayout.ROW_AUDIO -> audioLanes.coerceAtLeast(1)
        else -> 1
    }
    fun rowTop(row: Int): Float = (0 until row).sumOf { rowHeight(it).toDouble() }.toFloat()
    val totalHeight: Float get() = rowTop(4)
    fun rowAtY(y: Float): Int? = (0..3).firstOrNull { y >= rowTop(it) && y < rowTop(it) + rowHeight(it) }
    fun laneAt(y: Float, row: Int, lanes: Map<String, Int>): Int =
        (((y - rowTop(row)) / rowHeight(row)) * TrackLanes.count(lanes)).toInt()
            .coerceIn(0, TrackLanes.count(lanes) - 1)
    companion object {
        fun from(timeline: Timeline) = TimelineGeometry(TrackLanes.count(TrackLanes.text(timeline)), TrackLanes.count(TrackLanes.audio(timeline)))
    }
}

/** What a touch landed on, resolved against the model at the current viewport. */
sealed interface TimelineHit {
    data object Empty : TimelineHit

    /** On the ruler: a scrub gesture starts here. */
    data object Ruler : TimelineHit

    /** On a video clip, optionally near one of its trim handles. */
    data class VideoClip(
        val clipId: String,
        val handle: TrimMath.Edge?,
    ) : TimelineHit

    data class TextClip(val overlayId: String, val handle: TrimMath.Edge? = null) : TimelineHit

    /** On an audio clip, optionally near one of its trim handles. */
    data class AudioClip(
        val audioClipId: String,
        val handle: TrimMath.Edge? = null,
    ) : TimelineHit
}

/**
 * Resolves screen touches to model elements. Uses the same [TimelineViewport]
 * the renderer uses, so a clip drawn at a position is hit-testable at exactly
 * that position.
 */
class TimelineHitTester {

    /**
     * @param scrollXPx current horizontal scroll of the timeline content
     * @param screenXPx touch x in the timeline's coordinate space
     * @param y touch y in layout units (0..TOTAL_HEIGHT)
     * @param selection what is currently selected — only the selected element
     *   shows trim handles, so only it can be hit on one
     * @param handleHitSlopPx extra slop around handles so they remain grabbable
     */
    fun hitTest(
        project: Project,
        viewport: TimelineViewport,
        scrollXPx: Float,
        screenXPx: Float,
        y: Float,
        selection: TimelineSelection,
        handleHitSlopPx: Float,
        geometry: TimelineGeometry = TimelineGeometry(),
    ): TimelineHit {
        val row = geometry.rowAtY(y) ?: return TimelineHit.Empty
        val timeMicros = viewport.timeAtScreenX(scrollXPx, screenXPx)

        return when (row) {
            TimelineLayout.ROW_RULER -> TimelineHit.Ruler

            TimelineLayout.ROW_VIDEO -> {
                // The selected clip's trim handles take priority: they are drawn
                // at fixed screen positions and their slop intentionally extends
                // just past the clip's own time span (a handle at t=0 must be
                // grabbable even though pixels left of it hold no clip).
                val selectedId = selection.videoClipId
                if (selectedId != null) {
                    val selected =
                        project.timeline.videoClips.firstOrNull { it.id == selectedId }
                    if (selected != null && !selected.isPhoto) {
                        edgeHandleAt(
                            viewport, scrollXPx, screenXPx, handleHitSlopPx,
                            selected.timelineStartMicros, selected.timelineEndMicros,
                        )?.let { return TimelineHit.VideoClip(selected.id, it) }
                    }
                }

                val clip = project.timeline.videoClips.firstOrNull {
                    timeMicros >= it.timelineStartMicros && timeMicros < it.timelineEndMicros
                } ?: return TimelineHit.Empty
                TimelineHit.VideoClip(clip.id, handle = null)
            }

            TimelineLayout.ROW_TEXT -> {
                val lanes = TrackLanes.text(project.timeline)
                val lane = geometry.laneAt(y, row, lanes)
                val selected = project.timeline.textOverlays.firstOrNull { it.id == selection.selectedTextOverlayId }
                if (selected != null && lanes[selected.id] == lane) {
                    edgeHandleAt(viewport, scrollXPx, screenXPx, handleHitSlopPx,
                        selected.startMicros, selected.startMicros + selected.durationMicros)?.let {
                        return TimelineHit.TextClip(selected.id, it)
                    }
                }
                val overlay = project.timeline.textOverlays.firstOrNull {
                    lanes[it.id] == lane &&
                    timeMicros >= it.startMicros && timeMicros < it.startMicros + it.durationMicros
                } ?: return TimelineHit.Empty
                TimelineHit.TextClip(overlay.id)
            }

            TimelineLayout.ROW_AUDIO -> {
                val lanes = TrackLanes.audio(project.timeline)
                val lane = geometry.laneAt(y, row, lanes)
                val selectedId = selection.selectedAudioClipId
                if (selectedId != null) {
                    val selected = project.timeline.audioClips.firstOrNull { it.id == selectedId }
                    if (selected != null && lanes[selected.id] == lane) {
                        val start = selected.timelineStartMicros
                        val end = start + selected.timelineDurationMicros
                        edgeHandleAt(
                            viewport, scrollXPx, screenXPx, handleHitSlopPx, start, end,
                        )?.let { return TimelineHit.AudioClip(selected.id, it) }
                    }
                }

                val audio = project.timeline.audioClips.firstOrNull {
                    lanes[it.id] == lane &&
                    timeMicros >= it.timelineStartMicros &&
                        timeMicros < it.timelineStartMicros + it.timelineDurationMicros
                } ?: return TimelineHit.Empty
                TimelineHit.AudioClip(audio.id)
            }

            else -> TimelineHit.Empty
        }
    }

    /**
     * Returns the trim edge whose on-screen position is within [slopPx] of
     * [screenXPx], or null. Shared by the video and audio rows.
     */
    private fun edgeHandleAt(
        viewport: TimelineViewport,
        scrollXPx: Float,
        screenXPx: Float,
        slopPx: Float,
        startMicros: Long,
        endMicros: Long,
    ): TrimMath.Edge? {
        val startX = viewport.screenXAtTime(scrollXPx, startMicros)
        val endX = viewport.screenXAtTime(scrollXPx, endMicros)
        return when {
            abs(screenXPx - startX) <= slopPx -> TrimMath.Edge.Start
            abs(screenXPx - endX) <= slopPx -> TrimMath.Edge.End
            else -> null
        }
    }

    /**
     * The index a dragged clip would occupy if dropped with its center at
     * [dropCenterMicros]. Computed against the *remaining* clips (the dragged
     * one removed), matching how [com.videoeditor.editing.commands.TimelineCommands.MoveClip]
     * inserts at an index of the reduced list.
     */
    fun reorderIndexAt(timeline: Timeline, draggedClipId: String, dropCenterMicros: Long): Int {
        val others = timeline.videoClips.filterNot { it.id == draggedClipId }
        var index = 0
        for (clip in others) {
            val center = clip.timelineStartMicros + clip.timelineDurationMicros / 2
            if (dropCenterMicros > center) index++ else break
        }
        return index
    }
}

/**
 * The items the timeline draws, derived from the authoritative project.
 * Recomputed only when the project changes — never during scroll or zoom,
 * which are pure viewport transforms.
 */
data class TimelineContent(
    val durationMicros: Long,
    val videoItems: List<VideoTrackItem>,
    val textItems: List<TextTrackItem>,
    val audioItems: List<AudioTrackItem>,
) {
    data class VideoTrackItem(
        val clipId: String,
        val mediaRefId: String,
        val startMicros: Long,
        val endMicros: Long,
        val isPhoto: Boolean,
    ) {
        val durationMicros: Long get() = endMicros - startMicros
    }

    data class TextTrackItem(
        val overlayId: String,
        val label: String,
        val startMicros: Long,
        val endMicros: Long,
    ) {
        val durationMicros: Long get() = endMicros - startMicros
    }

    data class AudioTrackItem(
        val audioClipId: String,
        val mediaRefId: String,
        val startMicros: Long,
        val endMicros: Long,
        /** Source in/out for waveform extraction. */
        val sourceStartMicros: Long,
        val sourceEndMicros: Long,
        val muted: Boolean,
    ) {
        val durationMicros: Long get() = endMicros - startMicros
    }

    companion object {
        /** Empty content for the not-yet-loaded state. */
        val EMPTY = TimelineContent(
            durationMicros = 0L,
            videoItems = emptyList(),
            textItems = emptyList(),
            audioItems = emptyList(),
        )

        /** Labels longer than this are truncated with an ellipsis on the clip. */
        private const val MAX_LABEL_CHARS = 24

        fun from(project: Project): TimelineContent {
            val timeline = project.timeline
            return TimelineContent(
                durationMicros = timeline.totalDurationMicros,
                videoItems = timeline.videoClips.map { clip ->
                    VideoTrackItem(
                        clipId = clip.id,
                        mediaRefId = clip.mediaRefId,
                        startMicros = clip.timelineStartMicros,
                        endMicros = clip.timelineEndMicros,
                        isPhoto = clip.isPhoto,
                    )
                },
                textItems = timeline.textOverlays.map { overlay: TextOverlay ->
                    TextTrackItem(
                        overlayId = overlay.id,
                        label = truncate(overlay.text),
                        startMicros = overlay.startMicros,
                        endMicros = overlay.startMicros + overlay.durationMicros,
                    )
                },
                audioItems = timeline.audioClips.map { audio: AudioClip ->
                    AudioTrackItem(
                        audioClipId = audio.id,
                        mediaRefId = audio.mediaRefId,
                        startMicros = audio.timelineStartMicros,
                        endMicros = audio.timelineStartMicros + audio.timelineDurationMicros,
                        sourceStartMicros = audio.sourceStartMicros,
                        sourceEndMicros = audio.sourceEndMicros,
                        muted = audio.muted,
                    )
                },
            )
        }

        private fun truncate(text: String): String {
            val oneLine = text.replace('\n', ' ').trim()
            return if (oneLine.length <= MAX_LABEL_CHARS) oneLine
            else oneLine.take(MAX_LABEL_CHARS - 1) + "…"
        }
    }
}
