package com.videoeditor.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.DuckingSettings
import com.videoeditor.core.model.Project

/**
 * Automatic ducking for a background music clip.
 *
 * Ducking is driven by *which other clips speak*, not by any analysis of the
 * music itself. The user picks the foreground clips (a voiceover, an extracted
 * dialogue track) whose levels should pull the music down, and sets how far.
 * Threshold, attack and release sit in an Advanced section so the default
 * interface is just the enable toggle, the trigger picker and the strength.
 *
 * The enable toggle controls whether the clip's ducking is active at all; with
 * it off, the DSP stage is never inserted and the music plays unchanged.
 */
@Composable
internal fun DuckingPanel(
    audioClip: AudioClip,
    project: Project,
    analysisFailed: Boolean,
    onDucking: (DuckingSettings) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = audioClip.ducking.coerced()
    var enabled by remember(audioClip.id, audioClip.ducking) { mutableStateOf(settings.enabled) }
    var triggerIds by remember(audioClip.id, audioClip.ducking) { mutableStateOf(settings.triggerClipIds.toSet()) }
    var strengthDb by remember(audioClip.id, audioClip.ducking) { mutableStateOf(settings.strengthDb) }
    var thresholdDb by remember(audioClip.id, audioClip.ducking) { mutableStateOf(settings.thresholdDb) }
    var attackMillis by remember(audioClip.id, audioClip.ducking) { mutableStateOf(settings.attackMillis.toFloat()) }
    var releaseMillis by remember(audioClip.id, audioClip.ducking) { mutableStateOf(settings.releaseMillis.toFloat()) }
    var showAdvanced by remember(audioClip.id, audioClip.ducking) { mutableStateOf(false) }

    // Only *other* clips can be triggers: a clip cannot duck on itself.
    val candidates = project.timeline.audioClips.filter { it.id != audioClip.id }

    EditorToolPanel(
        "Auto Ducking",
        onDismiss,
        onReset = {
            enabled = false
            triggerIds = emptySet()
            strengthDb = DuckingSettings.DEFAULT_STRENGTH_DB
            thresholdDb = DuckingSettings.DEFAULT_THRESHOLD_DB
            attackMillis = DuckingSettings.DEFAULT_ATTACK_MILLIS.toFloat()
            releaseMillis = DuckingSettings.DEFAULT_RELEASE_MILLIS.toFloat()
        },
        onApply = {
            onDucking(
                DuckingSettings(
                    enabled = enabled,
                    triggerClipIds = triggerIds.toList(),
                    strengthDb = strengthDb,
                    thresholdDb = thresholdDb,
                    attackMillis = attackMillis.toInt(),
                    releaseMillis = releaseMillis.toInt(),
                ),
            )
            onDismiss()
        },
        tagPrefix = "ducking",
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable ducking", color = EditorColor.DarkPrimaryText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                enabled,
                { enabled = it },
                modifier = Modifier.semantics { contentDescription = "Enable automatic ducking" },
            )
        }

        if (enabled) {
            if (analysisFailed) {
                Text(
                    "Could not analyze the trigger audio. No ducking is applied.",
                    color = EditorColor.Error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Text("Lower the music when these clips play:", color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall)
            if (candidates.isEmpty()) {
                Text(
                    "Add a voiceover or another audio clip to use as a trigger.",
                    color = EditorColor.DarkMutedText,
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                Column {
                    candidates.forEach { trigger ->
                        val ref = project.mediaRef(trigger.mediaRefId)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                ref?.displayName ?: "Audio",
                                color = EditorColor.DarkPrimaryText,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            androidx.compose.material3.Switch(
                                trigger.id in triggerIds,
                                { checked ->
                                    triggerIds = if (checked) triggerIds + trigger.id else triggerIds - trigger.id
                                },
                                modifier = Modifier.semantics { contentDescription = "Duck on ${ref?.displayName ?: "audio"}" },
                            )
                        }
                    }
                }
            }

            EditorValueSlider(
                "Strength",
                strengthDb,
                DuckingSettings.MIN_STRENGTH_DB..DuckingSettings.MAX_STRENGTH_DB,
                "${"%.0f".format(strengthDb)} dB",
                { strengthDb = it },
            )

            EditorChoice(
                if (showAdvanced) "Hide advanced" else "Advanced",
                showAdvanced,
                { showAdvanced = !showAdvanced },
            )
            if (showAdvanced) {
                EditorValueSlider(
                    "Threshold",
                    thresholdDb,
                    DuckingSettings.MIN_THRESHOLD_DB..DuckingSettings.MAX_THRESHOLD_DB,
                    "${"%.0f".format(thresholdDb)} dBFS",
                    { thresholdDb = it },
                )
                EditorValueSlider(
                    "Attack",
                    attackMillis,
                    DuckingSettings.MIN_TIME_CONSTANT_MILLIS.toFloat()..DuckingSettings.MAX_TIME_CONSTANT_MILLIS.toFloat(),
                    "${"%.0f".format(attackMillis)} ms",
                    { attackMillis = it },
                )
                EditorValueSlider(
                    "Release",
                    releaseMillis,
                    DuckingSettings.MIN_TIME_CONSTANT_MILLIS.toFloat()..DuckingSettings.MAX_TIME_CONSTANT_MILLIS.toFloat(),
                    "${"%.0f".format(releaseMillis)} ms",
                    { releaseMillis = it },
                )
            }
        }
    }
}
