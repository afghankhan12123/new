package com.videoeditor.editing.timeline

import com.google.common.truth.Truth.assertThat
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.Timeline
import com.videoeditor.editing.commands.TimelineCommands
import org.junit.Test

/**
 * Gesture-time previews must agree with what the committed command produces —
 * the user sees the drop result before releasing, and releasing must not move
 * anything.
 */
class TimelineSimulationTest {

    private fun timeline() = Timeline(
        videoClips = listOf(
            Clip("a", "r", 0L, 4_000_000L, timelineStartMicros = 0L),
            Clip("b", "r", 0L, 6_000_000L, timelineStartMicros = 4_000_000L),
            Clip("c", "r", 0L, 2_000_000L, timelineStartMicros = 10_000_000L),
        ),
    )

    @Test
    fun `reorder preview matches the committed MoveClip result`() {
        // Drag "a" to slot 1 (between b and c).
        val preview = TimelineSimulation.reorderPreview(timeline(), "a", 1)
        val committed = TimelineCommands.MoveClip("a", 1).apply(
            com.videoeditor.core.model.Project(
                id = "p", name = "p", createdAtMillis = 0L, updatedAtMillis = 0L,
                timeline = timeline(),
            ),
        ).timeline.videoClips

        val previewById = preview.associateBy { it.clipId }
        assertThat(preview).hasSize(3)
        for (clip in committed) {
            val placed = previewById[clip.id]!!
            assertThat(placed.startMicros).isEqualTo(clip.timelineStartMicros)
            assertThat(placed.endMicros).isEqualTo(clip.timelineEndMicros)
        }
        // The dragged clip draws last (on top).
        assertThat(preview.last().clipId).isEqualTo("a")
        assertThat(preview.last().isActive).isTrue()
    }

    @Test
    fun `reorder preview keeps total duration unchanged`() {
        val preview = TimelineSimulation.reorderPreview(timeline(), "b", 2)
        val span = preview.maxOf { it.endMicros } - preview.minOf { it.startMicros }
        assertThat(span).isEqualTo(12_000_000L)
        assertThat(preview.minOf { it.startMicros }).isEqualTo(0L)
    }

    @Test
    fun `reorder preview for unknown clip falls back to the plain layout`() {
        val preview = TimelineSimulation.reorderPreview(timeline(), "nope", 0)
        assertThat(preview).hasSize(3)
        assertThat(preview.none { it.isActive }).isTrue()
    }

    @Test
    fun `trim preview repacks following clips like the committed command`() {
        // Trim "b" from 6s of source down to 3s.
        val proposed = TrimMath.SourceRange(0L, 3_000_000L)
        val preview = TimelineSimulation.trimPreview(timeline(), "b", proposed)

        val byId = preview.associateBy { it.clipId }
        // a unchanged at 0..4s; b now 4..7s; c re-packed to 7..9s.
        assertThat(byId["a"]!!.startMicros).isEqualTo(0L)
        assertThat(byId["b"]!!.durationMicros).isEqualTo(3_000_000L)
        assertThat(byId["b"]!!.isActive).isTrue()
        assertThat(byId["c"]!!.startMicros).isEqualTo(7_000_000L)
    }

    @Test
    fun `trim preview honors playback speed`() {
        val sped = Timeline(
            videoClips = listOf(
                Clip("a", "r", 0L, 8_000_000L, timelineStartMicros = 0L, playbackSpeed = 2f),
            ),
        )
        // Source range 0..8s at 2x = 4s timeline; trim to 0..4s source = 2s.
        val preview = TimelineSimulation.trimPreview(
            sped, "a", TrimMath.SourceRange(0L, 4_000_000L),
        )
        assertThat(preview.single().durationMicros).isEqualTo(2_000_000L)
    }
}
