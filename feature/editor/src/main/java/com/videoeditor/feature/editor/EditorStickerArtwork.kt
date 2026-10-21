package com.videoeditor.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.cos
import kotlin.math.sin

/** Original vector artwork for the explicitly unavailable sticker design gallery. */
@Composable
internal fun EditorStickerArtwork(name: String, modifier: Modifier) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val ink = EditorColor.DarkPrimaryText
            val line = Stroke(1.6f, cap = StrokeCap.Round)
            fun path(first: Offset, second: Offset, third: Offset, fourth: Offset? = null, fifth: Offset? = null, close: Boolean = false) = Path().apply {
                val points = listOfNotNull(first, second, third, fourth, fifth)
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                if (close) close()
            }
            when (name) {
                "Star", "Sparkle", "Diamond" -> {
                    val count = if (name == "Star") 5 else 4
                    val p = Path()
                    repeat(count * 2) { i ->
                        val angle = -Math.PI / 2 + i * Math.PI / count
                        val radius = if (i % 2 == 0) 10f else if (name == "Diamond") 6f else 4f
                        val x = 12 + cos(angle).toFloat() * radius
                        val y = 12 + sin(angle).toFloat() * radius
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }; p.close(); drawPath(p, ink)
                }
                "Heart" -> drawPath(Path().apply {
                    moveTo(12f, 21f); cubicTo(8f, 17f, 2f, 13f, 2f, 7.5f)
                    cubicTo(2f, 1f, 10f, 1f, 12f, 6f); cubicTo(14f, 1f, 22f, 1f, 22f, 7.5f)
                    cubicTo(22f, 13f, 16f, 17f, 12f, 21f); close()
                }, ink)
                "Sun" -> {
                    drawCircle(ink, 4f, Offset(12f, 12f))
                    repeat(8) { i -> val angle = i * Math.PI / 4
                        val v = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                        drawLine(ink, Offset(12f, 12f) + v * 7f, Offset(12f, 12f) + v * 10f, 1.6f, StrokeCap.Round)
                    }
                }
                "Flag" -> {
                    drawLine(ink, Offset(5f, 3f), Offset(5f, 22f), 1.6f, StrokeCap.Round)
                    drawPath(path(Offset(5f, 4f), Offset(20f, 4f), Offset(16f, 9f), Offset(20f, 14f), Offset(5f, 14f), close = true), ink)
                }
                "Plane" -> drawPath(path(Offset(2f, 10f), Offset(22f, 2f), Offset(14f, 22f), Offset(10f, 14f), close = true), ink, style = line)
                "North east" -> {
                    drawLine(ink, Offset(4f, 20f), Offset(20f, 4f), 2f, StrokeCap.Round)
                    drawPath(path(Offset(10f, 4f), Offset(20f, 4f), Offset(20f, 14f)), ink, style = line)
                }
                "Arrow" -> {
                    drawLine(ink, Offset(3f, 12f), Offset(21f, 12f), 2f, StrokeCap.Round)
                    drawPath(path(Offset(15f, 6f), Offset(21f, 12f), Offset(15f, 18f)), ink, style = line)
                }
                "Music" -> {
                    drawCircle(ink, 3.5f, Offset(6f, 18f)); drawCircle(ink, 3.5f, Offset(18f, 16f))
                    drawPath(path(Offset(9f, 18f), Offset(9f, 5f), Offset(21f, 3f), Offset(21f, 16f)), ink, style = line)
                    drawLine(ink, Offset(9f, 9f), Offset(21f, 7f), 2f)
                }
                "Mountain" -> drawPath(path(Offset(2f, 20f), Offset(9f, 5f), Offset(13f, 13f), Offset(17f, 8f), Offset(23f, 20f), close = true), ink, style = line)
                else -> {
                    drawCircle(ink, 9f, Offset(12f, 12f), style = line)
                    drawCircle(ink, 1f, Offset(8f, 9f)); drawCircle(ink, 1f, Offset(16f, 9f))
                    drawArc(ink, 20f, 140f, false, Offset(6f, 8f), Size(12f, 10f), style = line)
                }
            }
        }
    }
}
