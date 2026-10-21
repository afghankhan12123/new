package com.videoeditor.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoeditor.core.model.CanvasSettings
import com.videoeditor.core.model.ScaleMode
import com.videoeditor.core.model.VisualTransform
import kotlin.math.*

/** Mirrors crop -> rotation -> presentation -> canvas placement, the engine's transform order. */
@Composable
internal fun TransformSelectionHandles(
    modifier: Modifier,
    canvas: CanvasSettings,
    sourceAspect: Float,
    transform: VisualTransform,
    onChange: (VisualTransform) -> Unit,
) {
    val latest by rememberUpdatedState(transform)
    val change by rememberUpdatedState(onChange)
    Canvas(modifier.testTag("transform-handles")
        .semantics { contentDescription = "Transform selection. Drag to move; pinch to scale and rotate. Precise values are available in the tool panel." }
        .pointerInput(canvas.width, canvas.height) {
            var remainder = 0f
            detectTransformGestures { _, pan, zoom, rotation ->
                val degrees = latest.rotationDegrees + rotation + remainder
                val roundedDegrees = degrees.roundToInt()
                remainder = degrees - roundedDegrees
                val width = min(size.width.toFloat(), size.height * canvas.width.toFloat() / canvas.height)
                val height = width * canvas.height / canvas.width
                if (width > 0 && height > 0) change(latest.copy(
                    offsetX = (latest.offsetX + pan.x * 2 / width).coerceIn(-1f, 1f),
                    offsetY = (latest.offsetY + pan.y * 2 / height).coerceIn(-1f, 1f),
                    scale = (latest.scale * zoom).coerceIn(.1f, 4f),
                    rotationDegrees = roundedDegrees.coerceIn(-360, 360),
                ))
            }
        }) {
        val canvasWidth = min(size.width, size.height * canvas.width.toFloat() / canvas.height)
        val canvasHeight = canvasWidth * canvas.height / canvas.width
        val crop = transform.crop
        val sourceWidth = sourceAspect * (crop?.width ?: 1f)
        val sourceHeight = crop?.height ?: 1f
        val angle = Math.toRadians(transform.rotationDegrees.toDouble()).toFloat()
        val c = cos(angle); val s = sin(angle)
        val rotatedWidth = abs(sourceWidth * c) + abs(sourceHeight * s)
        val rotatedHeight = abs(sourceWidth * s) + abs(sourceHeight * c)
        val fit = if (canvas.scaleMode == ScaleMode.Fit)
            min(canvasWidth / rotatedWidth, canvasHeight / rotatedHeight)
        else max(canvasWidth / rotatedWidth, canvasHeight / rotatedHeight)
        val center = Offset(size.width / 2 + transform.offsetX * canvasWidth / 2,
            size.height / 2 + transform.offsetY * canvasHeight / 2)
        val corners = listOf(Offset(-1f, -1f), Offset(1f, -1f), Offset(1f, 1f), Offset(-1f, 1f)).map {
            val x = it.x * sourceWidth / 2; val y = it.y * sourceHeight / 2
            center + Offset(x * c - y * s, x * s + y * c) * (fit * transform.scale)
        }
        val path = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            corners.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        drawPath(path, EditorColor.AccentCyan, style = Stroke(1.dp.toPx()))
        for (fraction in listOf(1f / 3, 2f / 3)) {
            val top = corners[0] + (corners[1] - corners[0]) * fraction
            val bottom = corners[3] + (corners[2] - corners[3]) * fraction
            drawLine(Color.White.copy(alpha = .22f), top, bottom, 1.dp.toPx())
            val left = corners[0] + (corners[3] - corners[0]) * fraction
            val right = corners[1] + (corners[2] - corners[1]) * fraction
            drawLine(Color.White.copy(alpha = .22f), left, right, 1.dp.toPx())
        }
        corners.forEach {
            drawCircle(EditorColor.AccentCyan, 4.dp.toPx(), it)
            drawCircle(Color.White, 2.7.dp.toPx(), it)
        }
        val badgeY = (corners.minOf { it.y } - 14.dp.toPx()).coerceIn(14.dp.toPx(), size.height - 14.dp.toPx())
        drawRoundRect(EditorColor.DarkPrimarySurface, Offset(center.x - 20.dp.toPx(), badgeY - 11.dp.toPx()),
            Size(40.dp.toPx(), 22.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        drawRoundRect(EditorColor.AccentCyan.copy(alpha = .6f), Offset(center.x - 20.dp.toPx(), badgeY - 11.dp.toPx()),
            Size(40.dp.toPx(), 22.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
        drawContext.canvas.nativeCanvas.drawText("${transform.rotationDegrees}°", center.x, badgeY + 4.dp.toPx(),
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE; textSize = 12.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
            })
    }
}
