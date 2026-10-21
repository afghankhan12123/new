package com.videoeditor.feature.editor

import com.videoeditor.feature.editor.timeline.TimelineCallbacks
import com.videoeditor.feature.editor.timeline.timelineLaneInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videoeditor.core.media.stickers.StickerCatalog
import com.videoeditor.editing.timeline.selectedStickerId
import com.videoeditor.core.model.StickerClip
import kotlin.math.min

/** Sticker lane: each sticker is a real timeline item sharing the editor viewport and fixed playhead. */
@Composable
internal fun StickerTimelineLane(viewModel: EditorViewModel, compact: Boolean = false, navigation: TimelineCallbacks? = null, onEdit: () -> Unit = {}) {
    val state by viewModel.screenState.collectAsStateWithLifecycle(initialValue = viewModel.uiState.value)
    val liveState = viewModel.uiState.collectAsStateWithLifecycle()
    val stickers = state.project?.timeline?.stickers.orEmpty().sortedByDescending { it.zIndex }
    if (stickers.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 112.dp).verticalScroll(rememberScrollState())
        .background(EditorColor.DarkBackground).testTag("sticker-timeline")) {
        stickers.forEach { sticker ->
            Canvas(Modifier.fillMaxWidth().height(if (compact) 48.dp else 52.dp).testTag("sticker-lane-${sticker.id}")
                .semantics { contentDescription = "Sticker ${StickerCatalog.asset(sticker.assetKey)?.label ?: sticker.assetKey}" }
                .timelineLaneInput(navigation, { viewModel.uiState.value }) { x ->
                    val live = viewModel.uiState.value
                    val time = live.viewport()?.timeAtScreenX(live.scrollXPx, x)
                    if (time != null && time in sticker.timelineStartMicros until sticker.timelineEndMicros) {
                        viewModel.selectSticker(sticker.id); onEdit()
                    } else viewModel.clearSelection()
                }) {
                val current = liveState.value
                val viewport = current.viewport() ?: return@Canvas
                val x = viewport.screenXAtTime(current.scrollXPx, sticker.timelineStartMicros)
                val end = viewport.screenXAtTime(current.scrollXPx, sticker.timelineEndMicros)
                val selected = state.selection.selectedStickerId == sticker.id
                // Cyan family matches the interactive-selection color contract.
                drawRoundRect(if (selected) Color(0xFF38D6DF) else Color(0xFF2BA8B5),
                    Offset(x, 3.dp.toPx()), Size((end - x).coerceAtLeast(1f), size.height - 6.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                if (selected) drawRoundRect(EditorColor.AccentCyan,
                    Offset(x, 3.dp.toPx()), Size((end - x).coerceAtLeast(1f), size.height - 6.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    style = Stroke(1.5.dp.toPx()))
                if (end > 0 && x < size.width) clipRect(left = x.coerceAtLeast(0f),
                    right = end.coerceAtMost(size.width).coerceAtLeast(x.coerceAtLeast(0f))) {
                    val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE; textSize = 12.sp.toPx()
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    }
                    val labelX = x.coerceAtLeast(0f) + 8.dp.toPx()
                    val label = android.text.TextUtils.ellipsize(
                        StickerCatalog.asset(sticker.assetKey)?.label ?: "Sticker", paint,
                        (minOf(end, size.width) - labelX - 8.dp.toPx()).coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END)
                    drawContext.canvas.nativeCanvas.drawText(label.toString(), labelX,
                        size.height / 2 - (paint.ascent() + paint.descent()) / 2, paint)
                }
                drawLine(Color.White, Offset(current.playheadXpx, 0f), Offset(current.playheadXpx, size.height), 1.25.dp.toPx())
            }
        }
    }
}

/** Selected-sticker tool sheet: precise transform values, timing, layering. */
@Composable
internal fun StickerToolsSheet(viewModel: EditorViewModel, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val project = state.project ?: return
    val sticker = project.timeline.stickers.firstOrNull { it.id == state.selection.selectedStickerId }
    EditorToolPanel("Sticker", onDismiss, sticker?.let { { viewModel.resetStickerTransform(it.id) } },
        onDismiss, cancelLabel = "Close", applyLabel = "Done") {
        if (sticker == null) {
            Text("Select a sticker on the timeline to edit it.", color = EditorColor.DarkSecondaryText,
                style = MaterialTheme.typography.bodySmall)
            return@EditorToolPanel
        }
        fun update(value: StickerClip) = viewModel.updateSticker(value)
        @Composable fun control(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
            EditorValueSlider(label, value.coerceIn(range.start, range.endInclusive), range,
                if (label == "Rotation") "${value.toInt()}°" else "${(value * 100).toInt()}%", change,
                Modifier.testTag("sticker-$label"))
        }
        control("Size", sticker.scale, StickerClip.MIN_SCALE..StickerClip.MAX_SCALE) { update(sticker.copy(scale = it)) }
        control("Opacity", sticker.opacity, 0f..1f) { update(sticker.copy(opacity = it)) }
        control("Rotation", sticker.rotationDegrees, -180f..180f) { update(sticker.copy(rotationDegrees = it)) }
        val total = project.timeline.renderedDurationMicros
        val maxStart = (total - sticker.timelineDurationMicros).coerceAtLeast(0L)
        if (maxStart > 0L) {
            Text("Timeline start", color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall)
            PremiumSlider(sticker.timelineStartMicros / 1_000_000f,
                { update(sticker.copy(timelineStartMicros = (it * 1_000_000).toLong())) },
                valueRange = 0f..(maxStart / 1_000_000f))
        }
        val maxDuration = ((total - sticker.timelineStartMicros) / 1_000_000f)
        if (maxDuration > 0.1f) {
            Text("Duration", color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall)
            PremiumSlider(sticker.timelineDurationMicros / 1_000_000f,
                { update(sticker.copy(timelineDurationMicros = (it * 1_000_000).toLong()
                    .coerceAtLeast(StickerClip.MIN_DURATION_MICROS))) },
                valueRange = 0.1f..maxDuration)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { viewModel.reorderSticker(sticker.id, true) }) { Text("Bring Forward") }
            TextButton(onClick = { viewModel.reorderSticker(sticker.id, false) }) { Text("Send Backward") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { viewModel.duplicateSticker(sticker.id) }) { Text("Duplicate") }
            TextButton(onClick = { viewModel.deleteSticker(sticker.id) }) { Text("Delete", color = EditorColor.Error) }
        }
        Text("Drag on the preview to move · pinch to resize and rotate", color = EditorColor.DarkMutedText,
            style = MaterialTheme.typography.labelSmall)
    }
}
