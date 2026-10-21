package com.videoeditor.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videoeditor.core.media.composition.measureCaptionDimensions
import com.videoeditor.core.media.stickers.StickerCatalog
import com.videoeditor.core.model.*
import com.videoeditor.editing.timeline.TimelineSelection
import kotlin.math.*

/** One canvas plane and gesture owner for every overlay type. Positive rotation/Y follow Android. */
internal data class LayerFrame(val selection: TimelineSelection, val center: Offset,
    val width: Float, val height: Float, val rotation: Float) {
    val bounds get() = Rect(center.x - width / 2, center.y - height / 2, center.x + width / 2, center.y + height / 2)
    fun point(point: Offset) = rotateCanvasPoint(point, center, rotation)
    fun contains(point: Offset, slop: Float) = bounds.inflate(slop).contains(rotateCanvasPoint(point, center, -rotation))
}

internal fun rotateCanvasPoint(point: Offset, center: Offset, degrees: Float): Offset {
    val a = degrees * PI / 180
    val delta = point - center
    return center + Offset((delta.x * cos(a) - delta.y * sin(a)).toFloat(),
        (delta.x * sin(a) + delta.y * cos(a)).toFloat())
}

internal fun fittedCanvasPlane(width: Float, height: Float, canvas: CanvasSettings): Rect {
    val w = min(width, height * canvas.width.toFloat() / canvas.height)
    val h = w * canvas.height / canvas.width
    return Rect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
}

/** Bottom-to-top hit order is identical to the render plan: PiP, stickers, captions. */
private fun EditorUiState.layerFrames(plane: Rect): List<LayerFrame> {
    val project = project ?: return emptyList()
    val time = playheadMicros
    return buildList {
        project.timeline.overlayClips.sortedWith(compareBy<OverlayClip> { it.zIndex }.thenBy { it.id }).forEach { layer ->
            if (time !in layer.timelineStartMicros until layer.timelineEndMicros) return@forEach
            val media = project.mediaRef(layer.mediaRefId)
            val rotated = (media?.rotationDegrees ?: 0) % 180 != 0
            val sourceWidth = ((if (rotated) media?.height else media?.width) ?: project.canvas.width).toFloat()
            val sourceHeight = ((if (rotated) media?.width else media?.height) ?: project.canvas.height).toFloat()
            val crop = layer.transform.crop
            val aspect = sourceWidth * (crop?.width ?: 1f) / (sourceHeight * (crop?.height ?: 1f)).coerceAtLeast(1f)
            val width = (if (layer.scaleMode != ScaleMode.Fit) plane.width else min(plane.width, plane.height * aspect)) * layer.transform.scale
            val height = if (layer.scaleMode != ScaleMode.Fit) plane.height * layer.transform.scale else width / aspect
            add(LayerFrame(TimelineSelection.Overlay(layer.id), plane.center + Offset(
                layer.transform.offsetX * plane.width / 2, layer.transform.offsetY * plane.height / 2), width, height,
                layer.transform.rotationDegrees.toFloat()))
        }
        project.timeline.stickers.sortedWith(compareBy<StickerClip> { it.zIndex }.thenBy { it.id }).forEach { sticker ->
            if (time !in sticker.timelineStartMicros until sticker.timelineEndMicros) return@forEach
            val aspect = StickerCatalog.asset(sticker.assetKey)?.aspectRatio ?: 1f
            val edge = min(plane.width, plane.height) * StickerClip.BASE_CANVAS_FRACTION * sticker.scale
            add(LayerFrame(TimelineSelection.Sticker(sticker.id), Offset(plane.left + sticker.positionX * plane.width,
                plane.top + sticker.positionY * plane.height), if (aspect >= 1f) edge else edge * aspect,
                if (aspect >= 1f) edge / aspect else edge, sticker.rotationDegrees))
        }
        project.timeline.textOverlays.forEach { text ->
            if (time !in text.startMicros until text.startMicros + text.durationMicros) return@forEach
            val size = measureCaptionDimensions(text, min(project.canvas.width, project.canvas.height) / 1080f, project.canvas.width)
            val ratio = plane.width / project.canvas.width
            add(LayerFrame(TimelineSelection.Text(text.id), Offset(plane.left + text.positionX * plane.width,
                plane.top + text.positionY * plane.height), size.width * text.scale * ratio,
                size.height * text.scale * ratio, text.rotationDegrees))
        }
    }
}

@Composable
internal fun LayerCanvasHandles(modifier: Modifier, viewModel: EditorViewModel) {
    val live = viewModel.uiState.collectAsStateWithLifecycle()
    Canvas(modifier.testTag("layer-canvas-handles").semantics {
        contentDescription = "Overlay canvas. Tap a layer to edit. Drag to move, drag a corner to resize, or pinch and rotate."
    }.pointerInput(viewModel) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val state = live.value
            if (state.preview.playRequested || down.isConsumed) return@awaitEachGesture
            val project = state.project ?: return@awaitEachGesture
            val plane = fittedCanvasPlane(size.width.toFloat(), size.height.toFloat(), project.canvas)
            if (plane.width <= 0f || plane.height <= 0f) return@awaitEachGesture
            val frames = state.layerFrames(plane)
            val selected = frames.firstOrNull { it.selection == state.selection }
            val handleRadius = 24.dp.toPx()
            val hit = selected?.takeIf {
                it.contains(down.position, handleRadius) ||
                    (it.point(Offset(it.center.x, it.bounds.top - 28.dp.toPx())) - down.position).getDistance() <= handleRadius
            } ?: frames.asReversed().firstOrNull { it.contains(down.position, 8.dp.toPx()) }
            if (hit == null) {
                if (waitForUpOrCancellation() != null) viewModel.requestCanvasEditing(TimelineSelection.None)
                return@awaitEachGesture
            }
            val resize = (selected?.selection == hit.selection || selected == null) && listOf(hit.bounds.topLeft, hit.bounds.topRight,
                hit.bounds.bottomLeft, hit.bounds.bottomRight).any { (hit.point(it) - down.position).getDistance() <= handleRadius }
            val rotating = (selected?.selection == hit.selection || selected == null) &&
                (hit.point(Offset(hit.center.x, hit.bounds.top - 28.dp.toPx())) - down.position).getDistance() <= handleRadius
            viewModel.select(hit.selection)
            when (val selection = hit.selection) {
                is TimelineSelection.Text -> viewModel.beginTextTransform(selection.overlayId)
                is TimelineSelection.Sticker -> viewModel.beginStickerTransform()
                is TimelineSelection.Overlay -> viewModel.beginOverlayTransform()
                else -> return@awaitEachGesture
            }
            var frame: LayerFrame = hit
            var last = down.position
            var moved = false
            var completed = false
            down.consume()
            try {
                do {
                    val event = awaitPointerEvent()
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                    if (event.changes.any { it.pressed }) {
                        val zoom: Float
                        val rotation: Float
                        val center: Offset
                        if (resize && event.changes.count { it.pressed } == 1) {
                            zoom = (pointer.position - frame.center).getDistance() / (last - frame.center).getDistance().coerceAtLeast(1f)
                            rotation = 0f; center = frame.center
                        } else if (rotating && event.changes.count { it.pressed } == 1) {
                            val a = atan2(pointer.position.y - frame.center.y, pointer.position.x - frame.center.x)
                            val b = atan2(last.y - frame.center.y, last.x - frame.center.x)
                            rotation = (((a - b) * 180f / PI.toFloat() + 540f) % 360f) - 180f
                            zoom = 1f; center = frame.center
                        } else {
                            zoom = event.calculateZoom().takeIf { it.isFinite() && it > 0f } ?: 1f
                            rotation = event.calculateRotation()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val anchor = if (centroid.isSpecified) centroid else frame.center
                            center = anchor + (rotateCanvasPoint(frame.center, anchor, rotation) - anchor) * zoom + event.calculatePan()
                        }
                        fun snap(value: Float, target: Float) = if (abs(value - target) <= 7.dp.toPx()) target else value
                        val snapped = Offset(snap(center.x, plane.center.x), snap(center.y, plane.center.y))
                        val x = ((snapped.x - plane.left) / plane.width).coerceIn(0f, 1f)
                        val y = ((snapped.y - plane.top) / plane.height).coerceIn(0f, 1f)
                        val degrees = ((frame.rotation + rotation + 540f) % 360f) - 180f
                        val scaleFactor = zoom.coerceIn(.25f, 4f)
                        val currentProject = viewModel.uiState.value.project ?: project
                        when (val selection = hit.selection) {
                            is TimelineSelection.Text -> currentProject.timeline.textOverlays.firstOrNull { it.id == selection.overlayId }?.let {
                                viewModel.previewTextTransform(it.copy(positionX = x, positionY = y,
                                    scale = (it.scale * scaleFactor).coerceIn(.1f, 4f), rotationDegrees = degrees))
                            }
                            is TimelineSelection.Sticker -> viewModel.previewStickerTransform {
                                it.copy(positionX = x, positionY = y, scale = (it.scale * scaleFactor).coerceIn(StickerClip.MIN_SCALE, StickerClip.MAX_SCALE), rotationDegrees = degrees)
                            }
                            is TimelineSelection.Overlay -> currentProject.timeline.overlayClips.firstOrNull { it.id == selection.overlayId }?.let {
                                viewModel.previewOverlayTransform(it.transform.copy(offsetX = x * 2 - 1, offsetY = y * 2 - 1,
                                    scale = (it.transform.scale * scaleFactor).coerceIn(.05f, 8f), rotationDegrees = degrees.roundToInt()))
                            }
                            else -> Unit
                        }
                        moved = moved || (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                            abs(zoom - 1f) > .01f || abs(rotation) > 1f
                        frame = (viewModel.uiState.value.layerFrames(plane).firstOrNull { it.selection == hit.selection } ?: frame)
                            // PiP persistence uses integer degrees. Retain sub-degree motion
                            // for this gesture so slow rotation does not round every sample to zero.
                            .copy(center = Offset(center.x.coerceIn(plane.left, plane.right), center.y.coerceIn(plane.top, plane.bottom)))
                            // Retain unsnapped finger intent. Reusing the snapped center here
                            // discards each small pan delta and traps slow drags at the magnet.
                            .let { if (hit.selection is TimelineSelection.Overlay) it.copy(rotation = degrees) else it }
                        last = pointer.position
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                completed = true
            } finally {
                when (hit.selection) {
                    is TimelineSelection.Text -> viewModel.endTextTransform(completed && moved)
                    is TimelineSelection.Sticker -> viewModel.endStickerTransform(completed && moved)
                    is TimelineSelection.Overlay -> viewModel.endOverlayTransform(completed && moved)
                    else -> Unit
                }
            }
            if (completed && !moved) viewModel.requestCanvasEditing(hit.selection)
        }
    }) {
        val state = live.value
        val project = state.project ?: return@Canvas
        if (state.preview.playRequested) return@Canvas
        val plane = fittedCanvasPlane(size.width, size.height, project.canvas)
        val frame = state.layerFrames(plane).firstOrNull { it.selection == state.selection } ?: return@Canvas
        if (abs(frame.center.x - plane.center.x) < 1f) drawLine(EditorColor.AccentCyan.copy(alpha = .6f),
            Offset(plane.center.x, plane.top), Offset(plane.center.x, plane.bottom), 1.dp.toPx())
        if (abs(frame.center.y - plane.center.y) < 1f) drawLine(EditorColor.AccentCyan.copy(alpha = .6f),
            Offset(plane.left, plane.center.y), Offset(plane.right, plane.center.y), 1.dp.toPx())
        val outside = listOf(frame.bounds.topLeft, frame.bounds.topRight, frame.bounds.bottomLeft, frame.bounds.bottomRight)
            .map(frame::point).any { !plane.contains(it) }
        val border = if (outside) Color(0xFFFFD166) else EditorColor.AccentCyan
        rotate(frame.rotation, frame.center) {
            drawRect(border, frame.bounds.topLeft, frame.bounds.size, style = Stroke(1.5.dp.toPx()))
            listOf(frame.bounds.topLeft, frame.bounds.topRight, frame.bounds.bottomLeft, frame.bounds.bottomRight).forEach {
                drawCircle(EditorColor.DarkPrimarySurface, 6.dp.toPx(), it)
                drawCircle(Color.White, 4.dp.toPx(), it)
            }
            val handle = Offset(frame.center.x, frame.bounds.top - 28.dp.toPx())
            drawLine(EditorColor.AccentCyan, Offset(frame.center.x, frame.bounds.top), handle, 1.dp.toPx())
            drawCircle(EditorColor.AccentCyan, 6.dp.toPx(), handle)
            drawCircle(Color.White, 3.dp.toPx(), handle)
        }
    }
}
