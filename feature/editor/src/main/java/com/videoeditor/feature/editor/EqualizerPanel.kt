package com.videoeditor.feature.editor

import androidx.compose.foundation.layout.Arrangement
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
import com.videoeditor.core.model.EqualizerPreset
import com.videoeditor.core.model.EqualizerSettings

/**
 * Three-band equalizer for one audio clip.
 *
 * Presets are prominent because they cover the common cases with one tap; the
 * manual Bass / Mid / Treble sliders sit in the same panel so a preset can be a
 * starting point rather than a separate screen. Every band shows its value in
 * dB, matching the model's documented units.
 *
 * Bypass is the disabled state: a disabled EQ never reaches the DSP path, so
 * the clip decodes and mixes exactly as it did before the tool was opened.
 */
@Composable
internal fun EqualizerPanel(
    audioClip: AudioClip,
    onEqualizer: (EqualizerSettings) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember(audioClip.id, audioClip.equalizer) { mutableStateOf(audioClip.equalizer.enabled) }
    var bassDb by remember(audioClip.id, audioClip.equalizer) { mutableStateOf(audioClip.equalizer.bassGainDb) }
    var midDb by remember(audioClip.id, audioClip.equalizer) { mutableStateOf(audioClip.equalizer.midGainDb) }
    var trebleDb by remember(audioClip.id, audioClip.equalizer) { mutableStateOf(audioClip.equalizer.trebleGainDb) }

    EditorToolPanel(
        "Equalizer",
        onDismiss,
        onReset = {
            enabled = false
            bassDb = 0f
            midDb = 0f
            trebleDb = 0f
        },
        onApply = {
            onEqualizer(
                EqualizerSettings(
                    enabled = enabled,
                    bassGainDb = bassDb,
                    midGainDb = midDb,
                    trebleGainDb = trebleDb,
                ),
            )
            onDismiss()
        },
        tagPrefix = "equalizer",
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable EQ", color = EditorColor.DarkPrimaryText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                enabled,
                { enabled = it },
                modifier = Modifier.semantics { contentDescription = "Enable equalizer" },
            )
        }

        Text("Preset", color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EqualizerPreset.entries.forEach { preset ->
                EditorChoice(
                    preset.label,
                    enabled && bassDb == preset.bassDb && midDb == preset.midDb && trebleDb == preset.trebleDb,
                    {
                        enabled = true
                        bassDb = preset.bassDb
                        midDb = preset.midDb
                        trebleDb = preset.trebleDb
                    },
                    Modifier.weight(1f),
                )
            }
        }

        EditorValueSlider(
            "Bass",
            bassDb,
            EqualizerSettings.MIN_BAND_GAIN_DB..EqualizerSettings.MAX_BAND_GAIN_DB,
            "${"%.1f".format(bassDb)} dB",
            { bassDb = it },
        )
        EditorValueSlider(
            "Mid",
            midDb,
            EqualizerSettings.MIN_BAND_GAIN_DB..EqualizerSettings.MAX_BAND_GAIN_DB,
            "${"%.1f".format(midDb)} dB",
            { midDb = it },
        )
        EditorValueSlider(
            "Treble",
            trebleDb,
            EqualizerSettings.MIN_BAND_GAIN_DB..EqualizerSettings.MAX_BAND_GAIN_DB,
            "${"%.1f".format(trebleDb)} dB",
            { trebleDb = it },
        )
    }
}
