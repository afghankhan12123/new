package com.videoeditor.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.videoeditor.core.common.util.Ids
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.VolumeEnvelope
import com.videoeditor.core.model.VolumeInterpolation
import com.videoeditor.core.model.VolumePoint
import kotlin.math.abs

/**
 * Editable gain-envelope editor for one audio clip's [VolumeEnvelope].
 *
 * The curve is drawn and edited in **source microseconds**, the domain the
 * envelope is authored in. Horizontal position maps to source time within the
 * clip's audible window; vertical position maps to gain in dB. The waveform the
 * source decodes to is drawn beneath the envelope so a point lands on the audio
 * content it controls.
 *
 * A drag moves the nearest point; a tap on empty curve space adds a point at the
 * tap; Apply commits one undo entry through the command layer. Reset clears the
 * whole envelope. Gain labels stay in dB so the control matches the model's
 * documented units.
 */
@Composable
internal fun VolumeAutomationPanel(
    audioClip: AudioClip,
    peaks: com.videoeditor.core.media.waveform.WaveformPeaks?,
    playheadMicros: Long,
    onPlayhead: (Long) -> Unit,
    onEnvelope: (VolumeEnvelope) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val envelope = audioClip.volumeEnvelope.normalized()
    val sourceStart = audioClip.sourceStartMicros
    val sourceEnd = audioClip.sourceEndMicros
    var selectedPointId by remember(audioClip.id) { mutableStateOf<String?>(null) }
    var draggedPointId by remember(audioClip.id) { mutableStateOf<String?>(null) }
    var draftCurve by remember(audioClip.id) { mutableStateOf(envelope) }
    var beforeDrag by remember { mutableStateOf(envelope) }
    val currentCurve by rememberUpdatedState(draftCurve)

    // Re-sync the draft when the underlying envelope changes from outside
    // (undo/redo, split, reset), so the editor never shows a stale curve.
    androidx.compose.runtime.LaunchedEffect(envelope) {
        if (draggedPointId == null) draftCurve = envelope
        if (selectedPointId != null && envelope.pointById(selectedPointId!!) == null) {
            selectedPointId = null
        }
    }

    val selectedPoint = selectedPointId?.let { draftCurve.pointById(it) }

    EditorToolPanel(
        "Volume Automation",
        onDismiss,
        onReset = {
            selectedPointId = null
            draftCurve = VolumeEnvelope()
        },
        onApply = {
            onEnvelope(draftCurve)
            onDismiss()
        },
        applyEnabled = true,
        tagPrefix = "volume-automation",
    ) {
        EditorChoice("Add point at playhead", false, {
            val source = (sourceStart + (playheadMicros - audioClip.timelineStartMicros) * audioClip.playbackSpeed.toDouble())
                .toLong().coerceIn(sourceStart, sourceEnd)
            val existing = draftCurve.points.firstOrNull { it.sourcePositionMicros == source }
            val point = existing ?: VolumePoint(Ids.newId(), source, draftCurve.gainDbAt(source))
            if (existing == null) draftCurve = VolumeEnvelope(draftCurve.points + point).normalized()
            selectedPointId = point.id
        })
        Text(
            "Drag a point to move it. Tap the curve to add one. Long-press a point to delete it.",
            color = EditorColor.DarkSecondaryText,
            style = MaterialTheme.typography.labelSmall,
        )
        Box(
            Modifier.fillMaxWidth().height(160.dp).padding(vertical = 8.dp),
        ) {
            VolumeCurveCanvas(
                envelope = draftCurve,
                sourceStartMicros = sourceStart,
                sourceEndMicros = sourceEnd,
                peaks = peaks,
                selectedPointId = selectedPointId,
                modifier = Modifier.fillMaxSize(),
            )
            // A scrim the canvas draws over handles the edit gestures. It is a
            // separate layer so the curve stays sharp under the pointer.
            Box(
                Modifier.fillMaxSize().testTag("automation-curve")
                    .pointerInput(audioClip.id, sourceStart, sourceEnd) {
                        detectTapGestures(
                            onTap = { offset ->
                                val tappedId = nearestPointId(currentCurve, offset, size, sourceStart, sourceEnd)
                                if (tappedId != null) {
                                    selectedPointId = tappedId
                                } else {
                                    val (positionMicros, gainDb) = offsetToModel(offset, size, sourceStart, sourceEnd)
                                    val newPoint = VolumePoint(Ids.newId(), positionMicros, gainDb)
                                    draftCurve = VolumeEnvelope(currentCurve.points + newPoint).normalized()
                                    selectedPointId = newPoint.id
                                }
                            },
                            onLongPress = { offset ->
                                nearestPointId(currentCurve, offset, size, sourceStart, sourceEnd)?.let { id ->
                                    draftCurve = currentCurve.withoutPoint(id)
                                    if (selectedPointId == id) selectedPointId = null
                                }
                            },
                        )
                    }
                    .pointerInput(audioClip.id, sourceStart, sourceEnd) {
                        detectDragGestures(
                            onDragStart = { offset: Offset ->
                                beforeDrag = currentCurve
                                draggedPointId = nearestPointId(currentCurve, offset, size, sourceStart, sourceEnd)
                                if (draggedPointId != null) selectedPointId = draggedPointId
                            },
                            onDrag = { change: androidx.compose.ui.input.pointer.PointerInputChange, _: Offset ->
                                val id = draggedPointId ?: return@detectDragGestures
                                change.consume()
                                val position = change.position
                                val (positionMicros, gainDb) = offsetToModel(position, size, sourceStart, sourceEnd)
                                val updated = currentCurve.points.map {
                                    if (it.id != id) it else it.copy(sourcePositionMicros = positionMicros, gainDb = gainDb)
                                }
                                draftCurve = VolumeEnvelope(updated).normalized()
                            },
                            onDragEnd = { draggedPointId = null },
                            onDragCancel = { draftCurve = beforeDrag; draggedPointId = null },
                        )
                    },
            )
        }

        if (selectedPoint != null) {
            VolumePointEditor(
                point = selectedPoint,
                sourceStartMicros = sourceStart,
                sourceEndMicros = sourceEnd,
                onChange = { updated ->
                    draftCurve = draftCurve.withPoint(updated).normalized()
                },
                onDelete = {
                    draftCurve = draftCurve.withoutPoint(selectedPoint.id)
                    selectedPointId = null
                },
            )
        } else {
            Text(
                "Tap a point to edit its exact time, gain and curve.",
                color = EditorColor.DarkMutedText,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Draws the gain envelope over the source waveform.
 *
 * The waveform is the *source* content; the envelope is the *output gain*. They
 * are drawn in different colors and at different scales so the two are never
 * confused. The envelope line runs the full width because the model holds the
 * first gain as a lead-in and the last as a tail.
 */
@Composable
private fun VolumeCurveCanvas(
    envelope: VolumeEnvelope,
    sourceStartMicros: Long,
    sourceEndMicros: Long,
    peaks: com.videoeditor.core.media.waveform.WaveformPeaks?,
    selectedPointId: String?,
    modifier: Modifier = Modifier,
) {
    val lineColor = EditorColor.AccentCyan
    val fillColor = EditorColor.AccentCyan.copy(alpha = 0.12f)
    val selectedColor = Color.White
    val gridColor = EditorColor.DarkBorder
    val peakColor = EditorColor.DarkSecondaryText.copy(alpha = 0.4f)
    val sourceSpan = (sourceEndMicros - sourceStartMicros).coerceAtLeast(1L)

    Canvas(modifier) {
        // Unity guide at the vertical centre (0 dB).
        val centreY = size.height * 0.5f
        val unityY = gainToY(0f, size.height)
        drawLine(gridColor, Offset(0f, unityY), Offset(size.width, unityY), strokeWidth = 1f)
        // Upper and lower gain guides at the documented dB range.
        for (fraction in listOf(0.25f, 0.75f)) {
            drawLine(gridColor.copy(alpha = 0.5f), Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), strokeWidth = 1f)
        }

        // Source waveform, drawn as vertical bars across the width. It is drawn
        // once per column and not re-derived per point move, which is what keeps
        // the editor from re-decoding or re-measuring on every slider event.
        peaks?.let { p ->
            val columnCount = p.size
            for (column in 0 until columnCount) {
                val x = column.toFloat() / columnCount.toFloat() * size.width
                val amplitude = p.peaks[column].coerceIn(0f, 1f)
                val barHeight = amplitude * size.height * 0.4f
                drawLine(
                    peakColor,
                    Offset(x, centreY - barHeight),
                    Offset(x, centreY + barHeight),
                    strokeWidth = (size.width / columnCount).coerceAtLeast(1f),
                )
            }
        }

        if (envelope.points.isNotEmpty()) {
            val points = envelope.points
            val path = Path()
            val fillPath = Path()
            // Draw the same evaluator as DSP, including Hold and Smooth segments.
            val columns = size.width.toInt().coerceIn(2, 2048)
            fillPath.moveTo(0f, size.height)
            for (column in 0..columns) {
                val x = size.width * column / columns
                val source = sourceStartMicros + (sourceSpan.toDouble() * column / columns).toLong()
                val y = gainToY(envelope.gainDbAt(source), size.height)
                if (column == 0) path.moveTo(x, y) else path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            fillPath.lineTo(size.width, size.height)
            fillPath.close()
            drawPath(fillPath, fillColor)
            drawPath(path, lineColor, style = Stroke(width = 3f))

            for (point in points) {
                val x = sourceToX(point.sourcePositionMicros, sourceStartMicros, sourceSpan, size.width)
                val y = gainToY(point.gainDb, size.height)
                val isSelected = point.id == selectedPointId
                drawCircle(if (isSelected) selectedColor else lineColor, radius = if (isSelected) 7f else 5f, center = Offset(x, y))
                drawCircle(lineColor, radius = if (isSelected) 10f else 8f, center = Offset(x, y), style = Stroke(width = 1.5f))
            }
        }
    }
}

/** The selected point's exact time, gain and curve, with delete. */
@Composable
private fun VolumePointEditor(
    point: VolumePoint,
    sourceStartMicros: Long,
    sourceEndMicros: Long,
    onChange: (VolumePoint) -> Unit,
    onDelete: () -> Unit,
) {
    val sourceSpan = (sourceEndMicros - sourceStartMicros).coerceAtLeast(1L)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Point", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            EditorChoice("Delete", false, onDelete)
        }
        EditorValueSlider(
            "Time",
            ((point.sourcePositionMicros - sourceStartMicros) / sourceSpan.toFloat()).coerceIn(0f, 1f),
            0f..1f,
            "${"%.2f".format(point.sourcePositionMicros / 1_000_000f)} s",
            { fraction ->
                val newSource = (sourceStartMicros + fraction * sourceSpan).toLong()
                onChange(point.copy(sourcePositionMicros = newSource))
            },
        )
        EditorValueSlider(
            "Gain",
            point.gainDb,
            VolumeEnvelope.MIN_GAIN_DB..VolumeEnvelope.MAX_GAIN_DB,
            "${"%.1f".format(point.gainDb)} dB",
            { db -> onChange(point.copy(gainDb = db)) },
        )
        Text("Curve to next point", color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            VolumeInterpolation.entries.forEach { interpolation ->
                EditorChoice(
                    interpolation.name,
                    point.interpolation == interpolation,
                    { onChange(point.copy(interpolation = interpolation)) },
                    Modifier.weight(1f),
                )
            }
        }
    }
}

// -- coordinate mapping --

private fun sourceToX(sourcePositionMicros: Long, sourceStartMicros: Long, sourceSpanMicros: Long, widthPx: Float): Float =
    ((sourcePositionMicros - sourceStartMicros).toFloat() / sourceSpanMicros.toFloat()).coerceIn(0f, 1f) * widthPx

private fun gainToY(gainDb: Float, heightPx: Float): Float {
    val normalized = (gainDb - VolumeEnvelope.MIN_GAIN_DB) / (VolumeEnvelope.MAX_GAIN_DB - VolumeEnvelope.MIN_GAIN_DB)
    return (1f - normalized.coerceIn(0f, 1f)) * heightPx
}

private fun offsetToModel(offset: Offset, size: IntSize, sourceStartMicros: Long, sourceEndMicros: Long): Pair<Long, Float> {
    val width = size.width.toFloat().coerceAtLeast(1f)
    val height = size.height.toFloat().coerceAtLeast(1f)
    val sourceSpan = (sourceEndMicros - sourceStartMicros).coerceAtLeast(1L)
    val fractionX = (offset.x / width).coerceIn(0f, 1f)
    val sourcePosition = sourceStartMicros + (fractionX * sourceSpan).toLong()
    val fractionY = (offset.y / height).coerceIn(0f, 1f)
    val gainDb = VolumeEnvelope.MIN_GAIN_DB + (1f - fractionY) * (VolumeEnvelope.MAX_GAIN_DB - VolumeEnvelope.MIN_GAIN_DB)
    return sourcePosition to gainDb
}

private fun nearestPointId(
    envelope: VolumeEnvelope,
    offset: Offset,
    size: IntSize,
    sourceStartMicros: Long,
    sourceEndMicros: Long,
): String? {
    val sourceSpan = (sourceEndMicros - sourceStartMicros).coerceAtLeast(1L)
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    var nearest: VolumePoint? = null
    var nearestDistance = Float.MAX_VALUE
    for (point in envelope.points) {
        val x = sourceToX(point.sourcePositionMicros, sourceStartMicros, sourceSpan, width)
        val y = gainToY(point.gainDb, height)
        val distance = abs(offset.x - x) + abs(offset.y - y)
        if (distance < nearestDistance) {
            nearestDistance = distance
            nearest = point
        }
    }
    // Only consider a point near enough to be grabbed.
    return if (nearestDistance <= GRAB_RADIUS_PX) nearest?.id else null
}

private const val GRAB_RADIUS_PX = 96f
