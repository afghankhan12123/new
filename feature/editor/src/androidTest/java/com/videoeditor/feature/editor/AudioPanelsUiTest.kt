package com.videoeditor.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose controls on API36; model/DSP and full screen/lifecycle gates are separate. */
@RunWith(AndroidJUnit4::class)
class AudioPanelsUiTest {
    @get:Rule val compose = createComposeRule()
    private val clip = AudioClip("audio", "music", 500_000, 6_500_000, playbackSpeed = .5f)

    @Test fun balance_switching_selected_clip_reads_its_settings() {
        val selected = mutableStateOf(clip)
        compose.setContent { MaterialTheme { AudioBalancePanel(selected.value, {}, {}, {}) } }
        compose.runOnIdle { selected.value = clip.copy(id = "other", balance = AudioBalance(.75f)) }
        compose.onNodeWithText("Right 75%").assertExists()
    }

    @Test fun balance_shortcut_is_draft_until_apply_and_cancel_discards() {
        var committed: AudioBalance? = null; var dismissed = 0
        compose.setContent { MaterialTheme { AudioBalancePanel(clip,
            { committed = it }, {}, { dismissed++ }) } }
        compose.onNodeWithText("Right", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertNull(committed) }
        compose.onNodeWithTag("balance-apply").performClick()
        compose.runOnIdle { assertEquals(1f, committed!!.pan, 0f); assertEquals(1, dismissed) }
    }

    @Test fun balance_cancel_does_not_commit_draft() {
        var committed = false; var dismissed = false
        compose.setContent { MaterialTheme { AudioBalancePanel(clip, { committed = true }, {}, { dismissed = true }) } }
        compose.onNodeWithText("Left", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("balance-cancel").performClick()
        compose.runOnIdle { assertFalse(committed); assertTrue(dismissed) }
    }

    @Test fun eq_preset_commits_three_bands_and_reset_callback_is_explicit() {
        var committed: EqualizerSettings? = null; var resets = 0
        compose.setContent { MaterialTheme { EqualizerPanel(clip, { committed = it }, { resets++ }, {}) } }
        val preset = EqualizerPreset.entries.last()
        compose.onNodeWithText(preset.label).performClick()
        compose.runOnIdle { assertNull(committed) }
        compose.onNodeWithTag("equalizer-apply").performClick()
        compose.runOnIdle {
            assertTrue(committed!!.enabled)
            assertEquals(preset.bassDb, committed!!.bassGainDb, 0f)
            assertEquals(preset.midDb, committed!!.midGainDb, 0f)
            assertEquals(preset.trebleDb, committed!!.trebleGainDb, 0f)
        }
        compose.onNodeWithText("Reset").performClick()
        compose.runOnIdle { assertEquals(1, resets) }
    }

    @Test fun ducking_trigger_and_advanced_settings_apply_together() {
        var committed: DuckingSettings? = null
        val voice = AudioClip("voice", "voice-ref", 0, 6_000_000)
        val project = Project("p", "Ducking", 0, 0, mediaRefs = listOf(
            MediaRef("music", "file:///music", MediaType.Audio, "Music"),
            MediaRef("voice-ref", "file:///voice", MediaType.Audio, "Voiceover")),
            timeline = Timeline(audioClips = listOf(clip, voice)))
        compose.setContent { MaterialTheme { DuckingPanel(clip, project, false, { committed = it }, {}, {}) } }
        compose.onNodeWithContentDescription("Enable automatic ducking").performClick()
        compose.onNodeWithContentDescription("Duck on Voiceover").performScrollTo().performClick()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Threshold").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ducking-apply").performClick()
        compose.runOnIdle { assertTrue(committed!!.enabled); assertEquals(listOf("voice"), committed!!.triggerClipIds) }
    }

    @Test fun ducking_missing_trigger_shows_recovery_and_analysis_failure_is_visible() {
        compose.setContent { MaterialTheme { DuckingPanel(clip.copy(ducking = DuckingSettings(enabled = true)),
            Project("p", "No trigger", 0, 0, timeline = Timeline(audioClips = listOf(clip))),
            true, {}, {}, {}) } }
        compose.onNodeWithText("Add a voiceover or another audio clip to use as a trigger.").assertExists()
        compose.onNodeWithText("Could not analyze the trigger audio. No ducking is applied.").assertExists()
    }

    @Test fun fade_both_curves_and_durations_commit_as_one_settings_change() {
        var result: AudioClip? = null
        compose.setContent { MaterialTheme { FadeCurvePanel(clip,
            { fadeIn, fadeOut, curveIn, curveOut -> result = clip.copy(fadeInMicros = fadeIn, fadeOutMicros = fadeOut,
                fadeInCurve = curveIn, fadeOutCurve = curveOut) }, {}, {}) } }
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(2f) }
        compose.onNodeWithText(FadeCurve.Smooth.label).performClick()
        compose.onNodeWithText("Fade Out").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(3f) }
        compose.onNodeWithText(FadeCurve.Exponential.label).performClick()
        compose.runOnIdle { assertNull(result) }
        compose.onNodeWithTag("fade-apply").performClick()
        compose.runOnIdle {
            assertEquals(2_000_000L, result!!.fadeInMicros); assertEquals(3_000_000L, result!!.fadeOutMicros)
            assertEquals(FadeCurve.Smooth, result!!.fadeInCurve); assertEquals(FadeCurve.Exponential, result!!.fadeOutCurve)
        }
    }

    @Test fun automation_add_at_playhead_maps_fractional_speed_and_avoids_duplicate_time() {
        var result: VolumeEnvelope? = null
        compose.setContent { MaterialTheme { VolumeAutomationPanel(clip, null, 2_000_000, {}, { result = it }, {}, {}) } }
        compose.onNodeWithText("Add point at playhead").performClick()
        compose.onNodeWithText("Add point at playhead").performClick()
        compose.onNodeWithTag("volume-automation-apply").performClick()
        compose.runOnIdle { assertEquals(1, result!!.points.size); assertEquals(1_500_000L, result!!.points.single().sourcePositionMicros) }
    }

    @Test fun automation_pointer_cancellation_restores_draft() {
        val envelope = VolumeEnvelope(listOf(VolumePoint("point", 3_500_000, -12f)))
        var result: VolumeEnvelope? = null
        compose.setContent { MaterialTheme { VolumeAutomationPanel(clip.copy(volumeEnvelope = envelope), null, 0, {}, { result = it }, {}, {}) } }
        val curve = compose.onNodeWithTag("automation-curve")
        val size = curve.fetchSemanticsNode().size
        val start = Offset(size.width / 2f, size.height / 3f)
        curve.performTouchInput { down(start); moveTo(start + Offset(100f, 20f)); advanceEventTime(50); cancel() }
        compose.onNodeWithTag("volume-automation-apply").performClick()
        compose.runOnIdle { assertEquals(envelope, result) }
    }

    @Test fun automation_drag_survives_recomposition() {
        val point = VolumePoint("point", 3_500_000, -12f)
        val envelope = VolumeEnvelope(listOf(point))
        var result: VolumeEnvelope? = null
        compose.setContent { MaterialTheme { VolumeAutomationPanel(clip.copy(volumeEnvelope = envelope), null, 0, {}, { result = it }, {}, {}) } }
        val curve = compose.onNodeWithTag("automation-curve")
        val size = curve.fetchSemanticsNode().size
        val start = Offset(size.width / 2f, size.height / 3f)
        curve.performTouchInput { down(start); moveTo(start + Offset(60f, 15f)); advanceEventTime(50) }
        compose.waitForIdle()
        curve.performTouchInput { moveTo(start + Offset(120f, 25f)); advanceEventTime(50); up() }
        compose.onNodeWithTag("volume-automation-apply").performClick()
        compose.runOnIdle { assertTrue(result!!.points.single().sourcePositionMicros > point.sourcePositionMicros + 500_000) }
    }
}
