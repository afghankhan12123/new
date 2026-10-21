package com.videoeditor.feature.editor.timeline

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.videoeditor.core.designsystem.color.VETimelineColor
import com.videoeditor.core.media.waveform.WaveformPeaks
import com.videoeditor.core.model.Project
import com.videoeditor.editing.timeline.RulerScale
import com.videoeditor.editing.timeline.TimelineContent
import com.videoeditor.editing.timeline.TimelineGesture
import com.videoeditor.editing.timeline.TrackLanes
import com.videoeditor.editing.timeline.TimelineLayout
import com.videoeditor.editing.timeline.TimelineSelection
import com.videoeditor.editing.timeline.TimelineSimulation
import com.videoeditor.editing.timeline.TimelineViewport
import com.videoeditor.editing.timeline.TrimMath
import com.videoeditor.feature.editor.ClipStrip
import com.videoeditor.feature.editor.TimelineAssets
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The timeline's custom rendering. Everything is drawn into one
 * [DrawScope] — the ruler, the three tracks, the drag/trim previews, and the
 * fixed playhead — instead of nested Compose layout components, because the
 * timeline re-renders on every scroll frame and gesture update and canvas
 * drawing keeps that work to a single layout pass with no per-clip
 * recomposition.
 *
 * The clip layout used here is produced by [visiblePlacements], which applies
 * the same gesture-time simulations ([TimelineSimulation]) the committed
 * commands use, so a drag or trim preview is pixel-identical to its result.
 */
internal fun DrawScope.drawTimeline(
    project: Project,
    content: TimelineContent,
    viewport: TimelineViewport,
    scrollXPx: Float,
    selection: TimelineSelection,
    gesture: TimelineGesture,
    assets: TimelineAssets,
    pxPerUnit: Float,
    textMeasurer: TextMeasurer,
    density: Float,
    hiddenRows: Set<Int> = emptySet(),
    automationDraft: com.videoeditor.core.model.VolumeEnvelope? = null,
    selectedPointId: String? = null,
) {
    val width = size.width
    val height = size.height

    // Panel background.
    drawRect(VETimelineColor.Background, size = Size(width, height))

    val geometry = com.videoeditor.editing.timeline.TimelineGeometry.from(project.timeline)
    fun rowTop(row: Int) = (0 until row).filterNot { it in hiddenRows }.sumOf { geometry.rowHeight(it).toDouble() }.toFloat()
    fun rowHeight(row: Int) = if (row in hiddenRows) 0f else geometry.rowHeight(row)
    val rulerTop = rowTop(TimelineLayout.ROW_RULER) * pxPerUnit
    val rulerHeight = rowHeight(TimelineLayout.ROW_RULER) * pxPerUnit
    val videoTop = rowTop(TimelineLayout.ROW_VIDEO) * pxPerUnit
    val videoHeight = rowHeight(TimelineLayout.ROW_VIDEO) * pxPerUnit
    val textTop = rowTop(TimelineLayout.ROW_TEXT) * pxPerUnit
    val textHeight = rowHeight(TimelineLayout.ROW_TEXT) * pxPerUnit
    val audioTop = rowTop(TimelineLayout.ROW_AUDIO) * pxPerUnit
    val audioHeight = rowHeight(TimelineLayout.ROW_AUDIO) * pxPerUnit

    // Track lanes.
    drawRect(
        VETimelineColor.RulerBackground,
        topLeft = Offset(0f, rulerTop),
        size = Size(width, rulerHeight),
    )
    for ((top, h) in listOf(
        videoTop to videoHeight,
        textTop to textHeight,
        audioTop to audioHeight,
    ).filter { it.second > 0f }) {
        drawRect(
            VETimelineColor.TrackBackground,
            topLeft = Offset(0f, top),
            size = Size(width, h),
        )
        drawLine(
            VETimelineColor.Divider.copy(alpha = .06f),
            Offset(0f, top),
            Offset(width, top),
            strokeWidth = 1f * density,
        )
    }

    // -- ruler --
    traceDrawing("Timeline.ruler") { drawRuler(viewport, scrollXPx, rulerTop, rulerHeight, width, textMeasurer, density) }

    // -- video track --
    val videoPlacements = videoPlacements(project, content, gesture)
    val clipInset = 4f * density
    val clipTop = videoTop + clipInset
    val clipHeight = videoHeight - 2 * clipInset
    val radius = CornerRadius(8f * density, 8f * density)
    val labelStyle = TextStyle(fontSize = 12.sp, color = Color.White)

    for (placement in videoPlacements) {
        val left = viewport.screenXAtTime(scrollXPx, placement.startMicros)
        val right = viewport.screenXAtTime(scrollXPx, placement.endMicros)
        if (right < 0f || left > width) continue
        val rect = Rect(
            max(0f, left),
            clipTop,
            min(width, right),
            clipTop + clipHeight,
        )
        if (rect.width <= 0f) continue

        val isSelected = selection is TimelineSelection.Video &&
            selection.clipId == placement.clipId
        val isDragged = placement.isActive && gesture is TimelineGesture.Reordering

        // Thumbnails (or placeholder while decoding). A strip decoded for a
        // different source range than the clip's current one is stale (a trim
        // committed after decoding); draw the placeholder rather than frames
        // that no longer match the edit.
        val strip = assets.strips[placement.clipId]
        val modelClip = project.timeline.videoClips.firstOrNull { it.id == placement.clipId }
        val staleStrip = strip != null && modelClip != null &&
            (strip.sourceStartMicros != modelClip.sourceStartMicros ||
                strip.sourceEndMicros != modelClip.sourceEndMicros)
        traceDrawing("Timeline.thumbnails") { drawClipBody(
            rect = rect,
            strip = if (staleStrip) null else strip,
            placement = placement,
            viewport = viewport,
            scrollXPx = scrollXPx,
            radius = radius,
            dragged = isDragged,
        ) }

        // Label, if the clip is wide enough to read one.
        if (rect.width > 36f * density && clipHeight > 100f * density) {
            val mediaName = project.timeline.videoClips
                .firstOrNull { it.id == placement.clipId }
                ?.let { clip -> project.mediaRef(clip.mediaRefId)?.displayName }
                ?: ""
            val text = clipLabelText(content, placement.clipId, mediaName)
            val measured = textMeasurer.measure(
                text,
                labelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = androidx.compose.ui.unit.Constraints(
                    maxWidth = (rect.width - 10f * density).toInt().coerceAtLeast(1),
                ),
            )
            drawRect(Color.Black.copy(alpha = 0.45f),
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, measured.size.height + 8f * density))
            drawText(
                measured,
                topLeft = Offset(rect.left + 5f * density, rect.top + 4f * density),
            )
        }

        // Selection border and trim handles for the selected video clip.
        if (isSelected) {
            drawRoundRect(
                color = Color(0xFF8DDCFF),
                topLeft = Offset(rect.left, rect.top),
                size = rect.size,
                cornerRadius = radius,
                style = Stroke(width = 2f * density),
            )
            if (modelClip?.isPhoto != true) drawTrimHandles(rect, gesture, density)
        }
        if (isDragged) {
            // Drop-slot shadow: where the clip will land.
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f),
                topLeft = Offset(rect.left, rect.top),
                size = rect.size,
                cornerRadius = radius,
                style = Stroke(width = 1.5f * density),
            )
        }
    }

    // -- text track --
    val textLanes = TrackLanes.text(project.timeline)
    val textLaneHeight = textHeight / TrackLanes.count(textLanes)
    val textPlacements = if (TimelineLayout.ROW_TEXT in hiddenRows) emptyList() else content.textPlacements(gesture)
    for ((id, startMicros, endMicros, label, isActive) in textPlacements) {
        val left = viewport.screenXAtTime(scrollXPx, startMicros)
        val right = viewport.screenXAtTime(scrollXPx, endMicros)
        if (right < 0f || left > width) continue
        val rect = Rect(
            max(0f, left),
            textTop + (textLanes[id] ?: 0) * textLaneHeight + clipInset,
            min(width, right),
            textTop + ((textLanes[id] ?: 0) + 1) * textLaneHeight - clipInset,
        )
        if (rect.width <= 0f) continue
        drawRoundRect(
            brush = com.videoeditor.feature.editor.EditorAssetStyle.TextTrack,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = radius,
        )
        drawRoundRect(
            color = if (selection is TimelineSelection.Text && selection.overlayId == id) VETimelineColor.SelectionBorder else VETimelineColor.TextClip,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = radius,
            style = Stroke(
                width = if (selection is TimelineSelection.Text && selection.overlayId == id) {
                    2f * density
                } else {
                    1f * density
                },
            ),
        )
        if (rect.width > 24f * density) {
            val measured = textMeasurer.measure(
                label,
                // White on the purple clip reads clearly; the purple label color
                // was near-invisible against the purple-tinted background.
                TextStyle(fontSize = 12.sp, color = Color.White),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = androidx.compose.ui.unit.Constraints(
                    maxWidth = (rect.width - 34f * density).toInt().coerceAtLeast(1),
                ),
            )
            if (rect.width > 64f * density) drawText(textMeasurer.measure("T", TextStyle(fontSize = 15.sp, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)),
                topLeft = Offset(rect.left + 8f * density, rect.center.y - 10f * density))
            drawText(
                measured,
                topLeft = Offset(
                    rect.left + (if (rect.width > 64f * density) 28f else 8f) * density,
                    rect.center.y - measured.size.height / 2f,
                ),
            )
        }
        if (selection is TimelineSelection.Text && selection.overlayId == id) drawTrimHandles(rect, gesture, density)
        if (isActive) {
            drawSnapLine(rect.center.x, textTop, textTop + textHeight, density)
        }
    }

    // -- audio track --
    val audioLanes = TrackLanes.audio(project.timeline)
    val audioLaneHeight = audioHeight / TrackLanes.count(audioLanes)
    val audioPlacements = if (TimelineLayout.ROW_AUDIO in hiddenRows) emptyList() else content.audioPlacements(gesture)
    for ((id, startMicros, endMicros, _, isActive) in audioPlacements) {
        val left = viewport.screenXAtTime(scrollXPx, startMicros)
        val right = viewport.screenXAtTime(scrollXPx, endMicros)
        if (right < 0f || left > width) continue
        val rect = Rect(
            max(0f, left),
            audioTop + (audioLanes[id] ?: 0) * audioLaneHeight + clipInset,
            min(width, right),
            audioTop + ((audioLanes[id] ?: 0) + 1) * audioLaneHeight - clipInset,
        )
        if (rect.width <= 0f) continue
        drawRoundRect(
            color = VETimelineColor.AudioClipBackground,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = radius,
        )
        traceDrawing("Timeline.waveform") { drawWaveform(
            rect = rect,
            peaks = assets.waveforms[id],
            contentLeft = left,
            contentWidth = right - left,
            audio = project.timeline.audioClips.firstOrNull { it.id == id },
            density = density,
        ) }
        // The name comes from the project library; waveform samples retain their time positions.
        if (rect.width > 100f * density && rect.height > 22f * density) {
            val title = project.timeline.audioClips.firstOrNull { it.id == id }
                ?.let { project.mediaRef(it.mediaRefId)?.displayName } ?: "Audio"
            val labelInset = if (selection is TimelineSelection.Audio && selection.audioClipId == id) 18f else 8f
            val label = textMeasurer.measure(title, labelStyle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = (76f * density).toInt()))
            drawRoundRect(VETimelineColor.AudioClipBackground, rect.topLeft,
                Size(label.size.width + (labelInset + 8f) * density, rect.height), radius)
            drawText(label, topLeft = Offset(rect.left + labelInset * density, rect.center.y - label.size.height / 2f))
        }
        val isSelection = selection is TimelineSelection.Audio && selection.audioClipId == id
        drawRoundRect(
            color = if (isSelection) {
                VETimelineColor.SelectionBorder
            } else {
                VETimelineColor.Waveform.copy(alpha = 0.5f)
            },
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = radius,
            style = Stroke(width = if (isSelection) 2f * density else 1f * density),
        )
        if (isSelection) {
            val audio = project.timeline.audioClips.firstOrNull { it.id == id }
            if (audio != null && audio.sourceDurationMicros > 0) {
                val envelope = automationDraft ?: audio.volumeEnvelope
                val geometry = AutomationGeometry(audio, Rect(left, rect.top, right, rect.bottom))
                clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                    val path = Path()
                    // Sample the evaluator so Hold and Smooth match the audible curve.
                    val columns = rect.width.toInt().coerceIn(2, 2048)
                    for (column in 0..columns) {
                        val x = rect.left + rect.width * column / columns
                        val source = audio.sourceStartMicros + ((x - left) / (right - left) * audio.sourceDurationMicros).toLong()
                        val pos = geometry.position(com.videoeditor.core.model.VolumePoint("", source, envelope.gainDbAt(source)))
                        if (column == 0) path.moveTo(x, pos.y) else path.lineTo(x, pos.y)
                    }
                    drawPath(path, Color(0xFF55DFF2), style = Stroke(2 * density))
                    envelope.points.filter { it.sourcePositionMicros in audio.sourceStartMicros..audio.sourceEndMicros }.forEach { point ->
                        val pos = geometry.position(point)
                        drawCircle(if (point.id == selectedPointId) Color.White else Color(0xFF55DFF2), 5 * density, pos)
                        if (point.id == selectedPointId) {
                            val label = textMeasurer.measure("${"%.2f".format((point.sourcePositionMicros - audio.sourceStartMicros) / audio.playbackSpeed / 1_000_000f)} s · ${"%.1f".format(point.gainDb)} dB", TextStyle(fontSize = 10.sp, color = Color.White))
                            drawText(label, topLeft = Offset((pos.x + 8 * density).coerceIn(rect.left, max(rect.left, rect.right - label.size.width)), rect.top))
                        }
                    }
                }
            }
            drawTrimHandles(rect, gesture, density)
        }
        if (isActive) {
            drawSnapLine(rect.center.x, audioTop, audioTop + audioHeight, density)
        }
    }

    // -- fixed playhead: drawn over everything, never scrolls --
    val playheadX = viewport.playheadXpx
    drawLine(
        VETimelineColor.Playhead,
        Offset(playheadX, 0f),
        Offset(playheadX, height),
        strokeWidth = 1.25f * density,
    )
    // Playhead head marker on the ruler.
    val headCenter = Offset(playheadX, 7f * density)
    drawCircle(VETimelineColor.SelectionBorder, 7f * density, headCenter)
    drawCircle(Color.White, 5f * density, headCenter)
}

/**
 * Clip placements for the video track, reflecting any in-flight gesture
 * through the same simulation the committed commands use. Without a gesture
 * the model placements are used directly.
 */
private fun videoPlacements(
    project: Project,
    content: TimelineContent,
    gesture: TimelineGesture,
): List<TimelineSimulation.PlacedClip> = when (gesture) {
    is TimelineGesture.Reordering ->
        TimelineSimulation.reorderPreview(project.timeline, gesture.clipId, gesture.proposedIndex)

    is TimelineGesture.Trimming -> {
        val range = gesture.proposedRangeMicros
        if (range != null) {
            TimelineSimulation.trimPreview(
                project.timeline,
                gesture.clipId,
                TrimMath.SourceRange(range.first, range.second),
            )
        } else {
            plainPlacements(content)
        }
    }

    else -> plainPlacements(content)
}

private fun plainPlacements(
    content: TimelineContent,
): List<TimelineSimulation.PlacedClip> = content.videoItems.map {
    TimelineSimulation.PlacedClip(
        clipId = it.clipId,
        startMicros = it.startMicros,
        endMicros = it.endMicros,
        isActive = false,
    )
}

/** Placements for the free tracks with a moving item's proposed start applied. */
private data class FreePlacement(
    val id: String,
    val startMicros: Long,
    val endMicros: Long,
    val label: String,
    val isActive: Boolean,
)

/** Text-track placements honoring an in-flight free move. */
private fun TimelineContent.textPlacements(
    gesture: TimelineGesture,
): List<FreePlacement> = textItems.map { item ->
    val moving = gesture as? TimelineGesture.MovingFree
    val isActive =
        moving != null && moving.track == TimelineGesture.MovingFree.Track.Text &&
            moving.itemId == item.overlayId
    val start = if (isActive) moving!!.proposedStartMicros else item.startMicros
    FreePlacement(item.overlayId, start, start + item.durationMicros, item.label, isActive)
}

/** Audio-track placements honoring an in-flight free move. */
private fun TimelineContent.audioPlacements(
    gesture: TimelineGesture,
): List<FreePlacement> = audioItems.map { item ->
    val moving = gesture as? TimelineGesture.MovingFree
    val isActive =
        moving != null && moving.track == TimelineGesture.MovingFree.Track.Audio &&
            moving.itemId == item.audioClipId
    val start = if (isActive) moving!!.proposedStartMicros else item.startMicros
    FreePlacement(item.audioClipId, start, start + item.durationMicros, "", isActive)
}

/** Label text for a video clip: media name, or "Photo" for stills. */
private fun clipLabelText(content: TimelineContent, clipId: String, mediaName: String): String {
    val item = content.videoItems.firstOrNull { it.clipId == clipId }
    return when {
        item?.isPhoto == true -> "Photo"
        mediaName.substringBeforeLast('.').isNotBlank() && mediaName.substringBeforeLast('.').all { it.isDigit() } -> "Video ${content.videoItems.indexOfFirst { it.clipId == clipId } + 1}"
        mediaName.isNotBlank() -> mediaName.substringBeforeLast('.').take(12)
        else -> "Clip"
    }
}

private fun DrawScope.drawRuler(
    viewport: TimelineViewport,
    scrollXPx: Float,
    rulerTop: Float,
    rulerHeight: Float,
    width: Float,
    textMeasurer: TextMeasurer,
    density: Float,
) {
    val interval = RulerScale.intervalMicros(viewport.pixelsPerMicro, 64f * density * fontScale.coerceAtLeast(1f))
    val spacingPx = interval * viewport.pixelsPerMicro

    // First tick at or left of the visible window, aligned to the interval.
    val visibleStartTime = viewport.timeAtScreenX(scrollXPx, 0f)
    val firstTick = floorDiv(visibleStartTime, interval) * interval
    val lastTime = viewport.timeAtScreenX(scrollXPx, width)

    val labelStyle = TextStyle(fontSize = 10.sp, color = VETimelineColor.Label, fontFeatureSettings = "tnum")
    var tickTime = firstTick
    while (tickTime <= lastTime + interval) {
        val x = viewport.screenXAtTime(scrollXPx, tickTime)
        if (x in -2f..width + 2f && tickTime >= 0) {
            val isMajor = RulerScale.isMajorTick(tickTime, interval)
            val tickHeight = if (isMajor) rulerHeight * 0.22f else rulerHeight * 0.12f
            drawLine(
                if (isMajor) VETimelineColor.TickMajor else VETimelineColor.TickMinor,
                Offset(x, rulerTop + rulerHeight - tickHeight),
                Offset(x, rulerTop + rulerHeight),
                strokeWidth = if (isMajor) 1.5f * density else 1f * density,
            )
            if (isMajor && spacingPx > 48f * density) {
                val label = RulerScale.formatTickLabel(tickTime, interval)
                val measured = textMeasurer.measure(label, labelStyle, maxLines = 1)
                val labelX = (x - measured.size.width / 2f).coerceAtLeast(0f)
                if (labelX + measured.size.width <= width) {
                    drawText(
                        measured,
                        topLeft = Offset(labelX, rulerTop + 2f * density),
                    )
                }
            }
        }
        tickTime += interval
    }
}

private fun floorDiv(a: Long, b: Long): Long = Math.floorDiv(a, b)

/**
 * Draws one video clip's body: its decoded thumbnail strip, or a placeholder
 * while decoding. Frames are mapped evenly across the clip's drawn width; a
 * strip whose source range no longer matches the clip (stale after a trim
 * commit) is passed as null by the caller so the placeholder shows instead.
 */
private fun DrawScope.drawClipBody(
    rect: Rect,
    strip: ClipStrip?,
    placement: TimelineSimulation.PlacedClip,
    viewport: TimelineViewport,
    scrollXPx: Float,
    radius: CornerRadius,
    dragged: Boolean,
) {
    val clipPath = Path().apply {
        addRoundRect(RoundRect(rect, radius))
    }
    clipPath(clipPath) {
        drawRect(VETimelineColor.ClipPlaceholder, topLeft = rect.topLeft, size = rect.size)

        val frames = strip?.images
        if (!frames.isNullOrEmpty()) {
            // Each thumbnail covers an equal slice of the source range. Draw
            // only the slices intersecting the rect.
            val frameCount = frames.size
            val fullSourceSpan = strip.sourceEndMicros - strip.sourceStartMicros
            val sampleStartFraction = if (fullSourceSpan > 0) (strip.sampledSourceStartMicros - strip.sourceStartMicros).toDouble() / fullSourceSpan else 0.0
            val sampleSpanFraction = if (fullSourceSpan > 0) (strip.sampledSourceEndMicros - strip.sampledSourceStartMicros).toDouble() / fullSourceSpan else 1.0
            val sampleTimelineStart = placement.startMicros + (placement.durationMicros * sampleStartFraction).toLong()
            val contentLeft = viewport.screenXAtTime(scrollXPx, sampleTimelineStart)
            val contentWidth = (placement.durationMicros * sampleSpanFraction * viewport.pixelsPerMicro).toFloat()

            var drew = false
            for (i in 0 until frameCount) {
                val frameLeft = contentLeft + contentWidth * i / frameCount
                val frameRight = contentLeft + contentWidth * (i + 1) / frameCount
                if (frameRight < rect.left || frameLeft > rect.right) continue
                val bitmap = frames[i] ?: continue
                drew = true
                // Center-crop each independently decoded frame to its tile.
                // Preserve image proportions even when bucketized density makes
                // a tile narrower than the source frame.
                val tileAspect = max(1f, frameRight - frameLeft) / rect.height
                val imageAspect = bitmap.width.toFloat() / bitmap.height
                val cropWidth = if (imageAspect > tileAspect)
                    (bitmap.height * tileAspect).roundToInt().coerceIn(1, bitmap.width)
                    else bitmap.width
                val cropHeight = if (imageAspect < tileAspect)
                    (bitmap.width / tileAspect).roundToInt().coerceIn(1, bitmap.height)
                    else bitmap.height
                drawImage(
                    bitmap,
                    srcOffset = androidx.compose.ui.unit.IntOffset(
                        (bitmap.width - cropWidth) / 2, (bitmap.height - cropHeight) / 2),
                    srcSize = androidx.compose.ui.unit.IntSize(cropWidth, cropHeight),
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        ceil(frameLeft).roundToInt(),
                        rect.top.roundToInt(),
                    ),
                    dstSize = Size(
                        max(1f, frameRight - frameLeft),
                        rect.height,
                    ).let {
                        androidx.compose.ui.unit.IntSize(
                            max(1, ceil(it.width).roundToInt()),
                            max(1, it.height.roundToInt()),
                        )
                    },
                )
                // A quiet frame edge keeps adjacent authentic samples legible.
                if (i > 0) drawLine(Color.Black.copy(alpha = .38f),
                    Offset(frameLeft, rect.top), Offset(frameLeft, rect.bottom), density)
            }
            if (!drew) {
                // Strip decoded but frames missing; keep the placeholder.
            }
        }

        if (dragged) {
            drawRect(Color.Black.copy(alpha = 0.35f), topLeft = rect.topLeft, size = rect.size)
        }
    }
}

/**
 * Draws one audio clip's waveform from real decoded peaks, aggregated into
 * screen columns. Peaks are evenly spaced over the clip's source range, and
 * timeline position maps linearly to source position, so each drawn column
 * aggregates the cached buckets beneath it: zooming never re-decodes, it only
 * re-aggregates.
 */
private fun DrawScope.drawWaveform(
    rect: Rect,
    peaks: WaveformPeaks?,
    contentLeft: Float,
    contentWidth: Float,
    audio: com.videoeditor.core.model.AudioClip?,
    density: Float,
) {
    if (peaks == null || peaks.size == 0) {
        // Undecoded: a subtle baseline so the clip still reads as audio.
        drawLine(
            VETimelineColor.Waveform.copy(alpha = 0.4f),
            Offset(rect.left, rect.center.y),
            Offset(rect.right, rect.center.y),
            strokeWidth = 1f * density,
        )
        return
    }
    val color = if (audio?.muted == true) VETimelineColor.WaveformMuted else VETimelineColor.Waveform
    // Fine columns aggregate genuine cached peaks at the visible timeline scale.
    val columnWidthPx = max(2f, 3f * density)
    val peakScale = peaks.peakInRange(0, peaks.size).coerceAtLeast(.000001f)
    val center = rect.center.y
    val maxAmp = rect.height * 0.30f
    val clipWidthPx = contentWidth.coerceAtLeast(1f)

    var x = rect.left
    while (x < rect.right) {
        val leftFraction = ((x - contentLeft) / clipWidthPx).coerceIn(0f, 1f)
        val rightFraction =
            ((x + columnWidthPx - contentLeft) / clipWidthPx).coerceIn(0f, 1f)
        val bucketLeft = (leftFraction * peaks.size).toInt()
        val bucketRight = (rightFraction * peaks.size).toInt() + 1
        val peak = peaks.peakInRange(bucketLeft, bucketRight)
        val sourceProgress = (leftFraction * (audio?.timelineDurationMicros ?: 0L)).toLong()
        val duration = audio?.timelineDurationMicros ?: 0L
        val fadeIn = if ((audio?.fadeInMicros ?: 0L) > 0L)
            (sourceProgress.toFloat() / audio!!.fadeInMicros).coerceIn(0f, 1f) else 1f
        val fadeOut = if ((audio?.fadeOutMicros ?: 0L) > 0L)
            ((duration - sourceProgress).toFloat() / audio!!.fadeOutMicros).coerceIn(0f, 1f) else 1f
        val gain = if (audio?.muted == true) 0f else (audio?.volume ?: 1f) * min(fadeIn, fadeOut)
        val amp = max(0.5f * density, (peak / peakScale).coerceIn(0f, 1f) * gain.coerceAtMost(1f) * maxAmp)
        drawLine(
            color,
            Offset(x, center - amp),
            Offset(x, center + amp),
            strokeWidth = 1.15f * density,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        x += columnWidthPx
    }
}

private fun DrawScope.drawTrimHandles(rect: Rect, gesture: TimelineGesture, density: Float) {
    val handleWidth = min(12f * density, rect.width / 3f)
    val outline = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(8f * density))) }
    clipPath(outline) {
    val trimming = gesture as? TimelineGesture.Trimming
    val activeEdge = if (trimming != null) trimming.edge else null

    // Left handle.
    val leftColor = if (activeEdge == TrimMath.Edge.Start) {
        VETimelineColor.Snap
    } else {
        VETimelineColor.Handle
    }
    drawRoundRect(
        color = leftColor,
        topLeft = Offset(rect.left, rect.top),
        size = Size(handleWidth, rect.height),
        cornerRadius = CornerRadius(1f * density),
    )
    // Right handle.
    val rightColor = if (activeEdge == TrimMath.Edge.End) {
        VETimelineColor.Snap
    } else {
        VETimelineColor.Handle
    }
    drawRoundRect(
        color = rightColor,
        topLeft = Offset(rect.right - handleWidth, rect.top),
        size = Size(handleWidth, rect.height),
        cornerRadius = CornerRadius(1f * density),
    )
    // A contrasting grip makes the draggable edge distinguishable from the
    // selection border, including on bright thumbnails. Hit slop stays wider.
    for (centerX in listOf(rect.left + handleWidth / 2, rect.right - handleWidth / 2)) {
        for (offset in listOf(-1.5f, 1.5f)) {
            drawLine(VETimelineColor.SelectionBorder,
                Offset(centerX + offset * density, rect.center.y - 4f * density),
                Offset(centerX + offset * density, rect.center.y + 4f * density),
                strokeWidth = 1.25f * density)
        }
    }
    } // clip grips to the same outer corners as the selected strip
}

private fun DrawScope.drawSnapLine(x: Float, top: Float, bottom: Float, density: Float) {
    drawLine(
        VETimelineColor.Snap,
        Offset(x, top),
        Offset(x, bottom),
        strokeWidth = 1.5f * density,
    )
}

/** Nested CPU scopes; GPU execution and uploads are measured in platform traces. */
private inline fun traceDrawing(name: String, block: () -> Unit) {
    android.os.Trace.beginSection(name)
    try { block() } finally { android.os.Trace.endSection() }
}
