package com.videoeditor.feature.editor

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
import androidx.compose.ui.unit.dp
import com.videoeditor.core.model.AudioBalance
import com.videoeditor.core.model.AudioClip

/**
 * Stereo placement for one audio clip.
 *
 * The control is a single centered slider from Left to Right. For stereo
 * material it is a balance control, attenuating the opposite channel. For mono
 * material it is a true pan, routing the single channel across a stereo output —
 * the output pipeline preserves a stereo image because the balance processor
 * emits two channels and the audio graph adopts that layout.
 *
 * Centre is the identity state and changes nothing, which keeps a clip left at
 * centre on the accepted mono output path. Left / Center / Right are one-tap
 * shortcuts onto the slider.
 */
@Composable
internal fun AudioBalancePanel(
    audioClip: AudioClip,
    onBalance: (AudioBalance) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pan by remember(audioClip.id, audioClip.balance) { mutableStateOf(audioClip.balance.pan) }

    EditorToolPanel(
        "Balance",
        onDismiss,
        onReset = {
            pan = 0f
        },
        onApply = {
            onBalance(AudioBalance(pan))
            onDismiss()
        },
        tagPrefix = "balance",
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Text("L", color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.labelMedium)
            Text(
                when {
                    pan <= -0.95f -> "Left"
                    pan >= 0.95f -> "Right"
                    pan == 0f -> "Center"
                    pan < 0f -> "Left ${(kotlin.math.abs(pan) * 100).toInt()}%"
                    else -> "Right ${(pan * 100).toInt()}%"
                },
                color = EditorColor.DarkPrimaryText,
                style = MaterialTheme.typography.labelMedium,
            )
            Text("R", color = EditorColor.DarkSecondaryText, style = MaterialTheme.typography.labelMedium)
        }
        EditorValueSlider(
            "Pan",
            pan,
            AudioBalance.MIN_PAN..AudioBalance.MAX_PAN,
            when {
                pan == 0f -> "C"
                pan < 0f -> "L ${(kotlin.math.abs(pan) * 100).toInt()}%"
                else -> "R ${(pan * 100).toInt()}%"
            },
            { pan = it },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            EditorChoice("Left", pan <= -0.95f, { pan = -1f }, Modifier.weight(1f))
            EditorChoice("Center", pan == 0f, { pan = 0f }, Modifier.weight(1f))
            EditorChoice("Right", pan >= 0.95f, { pan = 1f }, Modifier.weight(1f))
        }
    }
}
