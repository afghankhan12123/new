package com.videoeditor.feature.editor.timeline

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.core.model.*
import com.videoeditor.editing.timeline.*
import com.videoeditor.feature.editor.EditorUiState
import com.videoeditor.feature.editor.TimelineAssets
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class TimelineAutomationUiTest {
    @get:Rule val compose = createComposeRule()
    private val original = VolumeEnvelope(listOf(VolumePoint("point", 1_000_000, -12f)))
    private val clip = AudioClip("audio", "ref", 0, 8_000_000, volumeEnvelope = original)
    private val state = mutableStateOf(EditorUiState(project = Project("p", "Automation", 0, 0,
        mediaRefs = listOf(MediaRef("ref", "file:///test.m4a", MediaType.Audio, "tone")),
        timeline = Timeline(audioClips = listOf(clip))), selection = TimelineSelection.Audio("audio"), isLoading = false))
    private val commits = CopyOnWriteArrayList<VolumeEnvelope>()
    private val calls = CopyOnWriteArrayList<String>()

    private fun mount() {
        compose.setContent {
            TimelineView(state.value, TimelineAssets(), { _, _ -> }, TimelineCallbacks(
                onViewportSize = { width, playhead -> state.value = state.value.copy(viewportWidthPx = width,
                    playheadXpx = playhead, zoomPixelsPerMicro = width / 10_000_000f) },
                onTap = { calls += "tap" }, onScrubBegin = { calls += "scrub"; true },
                onScrubBy = {}, onScrollEnd = {}, onZoomBegin = { calls += "zoom"; true },
                onZoomBy = { _, _ -> }, onZoomEnd = {}, onTrimBegin = { _, _, _ -> calls += "trim"; true },
                onTrimBy = { _, _ -> }, onTrimEnd = { _, _ -> }, onReorderBegin = { true },
                onReorderTo = {}, onReorderEnd = {}, onFreeMoveBegin = { _, _ -> true },
                onFreeMoveBy = {}, onFreeMoveEnd = {}, onAudioEnvelope = { _, envelope ->
                    commits += envelope
                    state.value = state.value.copy(project = state.value.project!!.let { project ->
                        project.copy(timeline = project.timeline.copy(audioClips = listOf(clip.copy(volumeEnvelope = envelope))))
                    })
                },
            ))
        }
        compose.waitForIdle()
    }

    private fun geometry(): AutomationGeometry {
        val node = compose.onNodeWithTag("timeline").fetchSemanticsNode()
        val density = compose.density.density
        val pxPerUnit = node.size.height / TimelineGeometry.from(state.value.project!!.timeline).totalHeight
        return automationGeometry(state.value, pxPerUnit, density, emptySet())!!
    }

    @Test fun drag_moves_time_and_gain_and_commits_once_without_scrubbing() {
        mount()
        val g = geometry(); val start = g.position(original.points.single())
        val end = Offset(start.x + state.value.zoomPixelsPerMicro * 1_000_000, start.y + g.bounds.height * .15f)
        compose.onNodeWithTag("timeline").performTouchInput {
            down(start); repeat(12) { moveTo(start + (end - start) * ((it + 1) / 12f)); advanceEventTime(20) }; up()
        }
        compose.waitForIdle()
        assertEquals(1, commits.size)
        val moved = commits.single().points.single()
        assertTrue(kotlin.math.abs(2_000_000L - moved.sourcePositionMicros) <= 15_000L)
        assertTrue(moved.gainDb < -18f)
        assertFalse(calls.contains("scrub")); assertFalse(calls.contains("trim"))
    }

    @Test fun cancelled_drag_discards_draft_and_creates_no_history_edit() {
        mount(); val start = geometry().position(original.points.single())
        compose.onNodeWithTag("timeline").performTouchInput {
            down(start); moveTo(start + Offset(80f, 12f)); advanceEventTime(50); cancel()
        }
        compose.waitForIdle()
        assertTrue(commits.isEmpty()); assertEquals(original, state.value.project!!.timeline.audioClips.single().volumeEnvelope)
    }

    @Test fun long_press_deletes_point_once() {
        mount(); val start = geometry().position(original.points.single())
        compose.onNodeWithTag("timeline").performTouchInput { down(start); advanceEventTime(700); up() }
        compose.waitForIdle()
        assertEquals(1, commits.size); assertTrue(commits.single().isEmpty)
    }

    @Test fun empty_waveform_scroll_and_trim_edges_do_not_edit_automation() {
        mount(); val g = geometry()
        compose.onNodeWithTag("timeline").performTouchInput {
            swipe(Offset(g.bounds.left + 10f, g.bounds.center.y), Offset(g.bounds.left + 100f, g.bounds.center.y), 200)
        }
        compose.waitForIdle()
        assertTrue(calls.contains("trim")); assertTrue(commits.isEmpty())
        compose.onNodeWithTag("timeline").performTouchInput {
            swipe(Offset(g.bounds.left + state.value.zoomPixelsPerMicro * 3_000_000, g.bounds.center.y),
                Offset(g.bounds.left + state.value.zoomPixelsPerMicro * 4_000_000, g.bounds.center.y), 200)
        }
        compose.waitForIdle(); assertTrue(calls.contains("scrub")); assertTrue(commits.isEmpty())
    }

    @Test fun add_at_playhead_and_change_interpolation_use_current_model() {
        mount()
        compose.runOnIdle { state.value = state.value.copy(playheadMicros = 2_000_000) }
        action("Add volume point at playhead")
        assertEquals(2, commits.last().points.size)
        assertTrue(commits.last().points.any { it.sourcePositionMicros == 2_000_000L })
        action("Volume interpolation Hold")
        assertEquals(VolumeInterpolation.Hold, commits.last().points.last().interpolation)
        action("Delete selected volume point")
        assertEquals(original, commits.last())
    }

    @Test fun zoomed_fractional_speed_trimmed_clip_maps_to_source_time() {
        mount()
        compose.runOnIdle {
            val adjusted = clip.copy(sourceStartMicros = 500_000, playbackSpeed = .5f,
                volumeEnvelope = VolumeEnvelope(listOf(VolumePoint("point", 1_000_000, -12f))))
            state.value = state.value.copy(project = state.value.project!!.let { it.copy(timeline = it.timeline.copy(audioClips = listOf(adjusted))) },
                zoomPixelsPerMicro = state.value.zoomPixelsPerMicro * 1.5f)
        }
        val g = geometry(); val start = g.position(g.clip.volumeEnvelope.points.single())
        compose.onNodeWithTag("timeline").performTouchInput {
            swipe(start, start + Offset(state.value.zoomPixelsPerMicro * 1_000_000, 0f), 200)
        }
        compose.waitForIdle(); assertEquals(1, commits.size)
        assertTrue(kotlin.math.abs(1_500_000L - commits.single().points.single().sourcePositionMicros) <= 15_000L)
    }

    private fun action(label: String) {
        val actions = compose.onNodeWithTag("timeline").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.first { it.label == label }.action()) }
        compose.waitForIdle()
    }
}
