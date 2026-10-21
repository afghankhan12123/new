package com.videoeditor.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.FadeCurve

/**
 * Fade editor for one audio clip, covering both the existing linear fades and
 * the new curve shapes.
 *
 * Fade In and Fade Out are edited as a pair under segmented tabs, because they
 * share the panel and the user typically adjusts both for one clip. Each fade
 * has a duration in seconds and a curve shape, drawn as a small live preview of
 * the exact shape the DSP applies.
 *
 * Durations are clamped to the clip's timeline duration so a fade can never
 * exceed the audio it fades. Both fades commit together as one undo entry.
 */
@Composable
internal fun FadeCurvePanel(
    audioClip: AudioClip,
    onFade: (fadeInMicros: Long, fadeOutMicros: Long, fadeInCurve: FadeCurve, fadeOutCurve: FadeCurve) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val durationSeconds = (audioClip.timelineDurationMicros / 1_000_000f).coerceAtLeast(0.01f)
    var fadeInSeconds by remember(audioClip.id, audioClip) { mutableStateOf(audioClip.fadeInMicros / 1_000_000f) }
    var fadeOutSeconds by remember(audioClip.id, audioClip) { mutableStateOf(audioClip.fadeOutMicros / 1_000_000f) }
    var fadeInCurve by remember(audioClip.id, audioClip) { mutableStateOf(audioClip.fadeInCurve) }
    var fadeOutCurve by remember(audioClip.id, audioClip) { mutableStateOf(audioClip.fadeOutCurve) }
    var section by remember(audioClip.id, audioClip) { mutableStateOf("Fade In") }

    EditorToolPanel(
        "Fade",
        onDismiss,
        onReset = {
            fadeInSeconds = 0f
            fadeOutSeconds = 0f
            fadeInCurve = FadeCurve.Linear
            fadeOutCurve = FadeCurve.Linear
        },
        onApply = {
            onFade(
                (fadeInSeconds.coerceIn(0f, durationSeconds) * 1_000_000f).toLong(),
                (fadeOutSeconds.coerceIn(0f, durationSeconds) * 1_000_000f).toLong(),
                fadeInCurve,
                fadeOutCurve,
            )
            onDismiss()
        },
        tagPrefix = "fade",
    ) {
        EditorSegments(listOf("Fade In", "Fade Out"), section, { section = it })
        when (section) {
            "Fade In" -> {
                EditorValueSlider(
                    "Duration",
                    fadeInSeconds.coerceIn(0f, durationSeconds),
                    0f..durationSeconds,
                    "${"%.1f".format(fadeInSeconds)} s",
                    { fadeInSeconds = it },
                )
                FadeCurveSelector(fadeInCurve) { fadeInCurve = it }
                FadeCurvePreview(fadeInCurve, fadeOut = false)
            }
            else -> {
                EditorValueSlider(
                    "Duration",
                    fadeOutSeconds.coerceIn(0f, durationSeconds),
                    0f..durationSeconds,
                    "${"%.1f".format(fadeOutSeconds)} s",
                    { fadeOutSeconds = it },
                )
                FadeCurveSelector(fadeOutCurve) { fadeOutCurve = it }
                FadeCurvePreview(fadeOutCurve, fadeOut = true)
            }
        }
    }
}

/** One row of curve-shape choices. */
@Composable
private fun FadeCurveSelector(selected: FadeCurve, onSelect: (FadeCurve) -> Unit) {
    Column {
        Text("Curve", color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            FadeCurve.entries.take(3).forEach { curve ->
                EditorChoice(curve.label, selected == curve, { onSelect(curve) }, Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            FadeCurve.entries.drop(3).forEach { curve ->
                EditorChoice(curve.label, selected == curve, { onSelect(curve) }, Modifier.weight(1f))
            }
            // Keep the second row the same width as the first.
            androidx.compose.foundation.layout.Box(Modifier.weight(1f))
        }
    }
}

/**
 * A small live drawing of the selected curve shape. For a fade-out the shape is
 * mirrored vertically, since a fade-out runs the same curve from 1 down to 0.
 */
@Composable
private fun FadeCurvePreview(curve: FadeCurve, fadeOut: Boolean) {
    val lineColor = EditorColor.AccentCyan
    val gridColor = EditorColor.DarkBorder
    Canvas(Modifier.fillMaxWidth().height(64.dp).padding(top = 4.dp)) {
        val steps = 48
        // Baseline and top guides.
        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        drawLine(gridColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
        val path = Path()
        for (step in 0..steps) {
            val u = step.toFloat() / steps
            // A fade-out runs the same shape in reverse.
            val amplitude = curve.amplitudeAt(if (fadeOut) 1f - u else u)
            val x = u * size.width
            val y = (1f - amplitude) * size.height
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3f))
    }
}
