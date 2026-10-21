package com.videoeditor.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/** Compact reference styling with a full 48dp touch area and accessible progress actions. */
@Composable
internal fun PremiumSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: SliderColors = SliderDefaults.colors(thumbColor = EditorColor.DarkPrimaryText,
        activeTrackColor = EditorColor.AccentCyan, inactiveTrackColor = EditorColor.DarkElevatedSurface),
) {
    val change = rememberUpdatedState(onValueChange)
    val finished = rememberUpdatedState(onValueChangeFinished)
    val range = rememberUpdatedState(valueRange)
    Canvas(modifier.fillMaxWidth().height(48.dp)
        .semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange)
            if (enabled) setProgress { next -> onValueChange(next.coerceIn(valueRange)); onValueChangeFinished?.invoke(); true } else disabled()
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                val margin = 12.dp.toPx()
                fun changeAt(x: Float) {
                    val fraction = ((x - margin) / (size.width - 2 * margin).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    change.value(range.value.start + fraction * (range.value.endInclusive - range.value.start))
                }
                changeAt(down.position.x); down.consume()
                do {
                    val event = awaitPointerEvent()
                    event.changes.firstOrNull { it.id == down.id }?.let { changeAt(it.position.x) }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                finished.value?.invoke()
            }
        }) {
        val margin = 12.dp.toPx()
        val start = Offset(margin, size.height / 2)
        val end = Offset(size.width - margin, size.height / 2)
        val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        val thumb = Offset(start.x + (end.x - start.x) * fraction, start.y)
        drawLine(colors.inactiveTrackColor, start, end, 4.dp.toPx(), StrokeCap.Round)
        drawLine(androidx.compose.ui.graphics.Brush.horizontalGradient(if (enabled) listOf(colors.activeTrackColor, EditorColor.PrimaryBlue) else listOf(EditorColor.DarkMutedText, EditorColor.DarkMutedText), startX = start.x, endX = end.x), start, thumb, 4.dp.toPx(), StrokeCap.Round)
        drawCircle(if (enabled) colors.thumbColor else EditorColor.DarkMutedText, 7.dp.toPx(), thumb)
    }
}
