package com.videoeditor.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.videoeditor.core.model.CropRect
import kotlin.math.min
import kotlin.math.roundToInt

/** Real cached source frame; normalized handles feed the existing non-destructive crop model. */
@Composable
internal fun SourceCropEditor(bitmap: Bitmap, crop: CropRect, onChange: (CropRect) -> Unit) {
    val latest by rememberUpdatedState(crop)
    val change by rememberUpdatedState(onChange)
    val frame = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(Modifier.fillMaxWidth().height(144.dp).testTag("source-crop-handles")
        .semantics { contentDescription = "Source crop thumbnail. Drag a corner to crop. Exact edges are available below." }
        .pointerInput(bitmap) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val width = min(size.width.toFloat(), size.height * bitmap.width.toFloat() / bitmap.height)
                val height = width * bitmap.height / bitmap.width
                val origin = Offset((size.width - width) / 2, (size.height - height) / 2)
                val initial = latest
                val corners = listOf(Offset(initial.left, initial.top), Offset(initial.right, initial.top),
                    Offset(initial.right, initial.bottom), Offset(initial.left, initial.bottom))
                val index = corners.indices.minBy { (origin + Offset(corners[it].x * width, corners[it].y * height) - down.position).getDistance() }
                if ((origin + Offset(corners[index].x * width, corners[index].y * height) - down.position).getDistance() > 24.dp.toPx()) return@awaitEachGesture
                down.consume()
                var completed = false
                try {
                    do {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        val x = ((pointer.position.x - origin.x) / width).coerceIn(0f, 1f)
                        val y = ((pointer.position.y - origin.y) / height).coerceIn(0f, 1f)
                        change(when (index) {
                            0 -> initial.copy(left = x.coerceAtMost(initial.right - .01f), top = y.coerceAtMost(initial.bottom - .01f))
                            1 -> initial.copy(right = x.coerceAtLeast(initial.left + .01f), top = y.coerceAtMost(initial.bottom - .01f))
                            2 -> initial.copy(right = x.coerceAtLeast(initial.left + .01f), bottom = y.coerceAtLeast(initial.top + .01f))
                            else -> initial.copy(left = x.coerceAtMost(initial.right - .01f), bottom = y.coerceAtLeast(initial.top + .01f))
                        })
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    completed = true
                } finally { if (!completed) change(initial) }
            }
        }) {
        val width = min(size.width, size.height * bitmap.width.toFloat() / bitmap.height)
        val height = width * bitmap.height / bitmap.width
        val left = (size.width - width) / 2; val top = (size.height - height) / 2
        drawRect(Color.Black)
        drawImage(frame, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(width.roundToInt(), height.roundToInt()))
        val l = left + crop.left * width; val r = left + crop.right * width
        val t = top + crop.top * height; val b = top + crop.bottom * height
        val shade = Color.Black.copy(alpha = .55f)
        drawRect(shade, Offset(left, top), Size(width, t - top))
        drawRect(shade, Offset(left, b), Size(width, top + height - b))
        drawRect(shade, Offset(left, t), Size(l - left, b - t))
        drawRect(shade, Offset(r, t), Size(left + width - r, b - t))
        drawRect(EditorColor.AccentCyan, Offset(l, t), Size(r - l, b - t), style = Stroke(1.dp.toPx()))
        for (i in 1..2) {
            drawLine(Color.White.copy(alpha = .4f), Offset(l + (r - l) * i / 3, t), Offset(l + (r - l) * i / 3, b))
            drawLine(Color.White.copy(alpha = .4f), Offset(l, t + (b - t) * i / 3), Offset(r, t + (b - t) * i / 3))
        }
        listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b)).forEach { drawCircle(Color.White, 4.dp.toPx(), it) }
    }
}
