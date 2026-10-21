package com.videoeditor.editing.timeline

import com.google.common.truth.Truth.assertThat
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.Timeline
import org.junit.Test

/**
 * Clip-boundary navigation: the transport's prev/next boundary buttons must
 * land on real clip edges, advance from a position already on one, and stop
 * at the timeline ends.
 */
class TimelineNavTest {

    // 4s + 3s + 5s → boundaries 0, 4s, 7s, 12s.
    private val timeline = Timeline(
        videoClips = listOf(
            Clip("a", "r", 0L, 4_000_000L, timelineStartMicros = 0L),
            Clip("b", "r", 0L, 3_000_000L, timelineStartMicros = 4_000_000L),
            Clip("c", "r", 0L, 5_000_000L, timelineStartMicros = 7_000_000L),
        ),
    )

    @Test
    fun `boundaries are sorted deduplicated clip edges plus ends`() {
        assertThat(TimelineNav.boundaries(timeline))
            .containsExactly(0L, 4_000_000L, 7_000_000L, 12_000_000L)
            .inOrder()
    }

    @Test
    fun `next boundary advances from mid-clip`() {
        assertThat(TimelineNav.adjacentBoundary(timeline, 1_000_000L, forward = true))
            .isEqualTo(4_000_000L)
        assertThat(TimelineNav.adjacentBoundary(timeline, 5_000_000L, forward = true))
            .isEqualTo(7_000_000L)
    }

    @Test
    fun `next boundary skips the one already under the playhead`() {
        // Sitting exactly on 4s, "next" must go to 7s, not re-trigger 4s.
        assertThat(TimelineNav.adjacentBoundary(timeline, 4_000_000L, forward = true))
            .isEqualTo(7_000_000L)
        // Within the epsilon of 4s also counts as "on" it.
        assertThat(TimelineNav.adjacentBoundary(timeline, 4_005_000L, forward = true))
            .isEqualTo(7_000_000L)
    }

    @Test
    fun `previous boundary walks backwards and stops at zero`() {
        assertThat(TimelineNav.adjacentBoundary(timeline, 9_000_000L, forward = false))
            .isEqualTo(7_000_000L)
        assertThat(TimelineNav.adjacentBoundary(timeline, 7_000_000L, forward = false))
            .isEqualTo(4_000_000L)
        assertThat(TimelineNav.adjacentBoundary(timeline, 0L, forward = false)).isNull()
    }

    @Test
    fun `next boundary at the timeline end is null`() {
        assertThat(TimelineNav.adjacentBoundary(timeline, 12_000_000L, forward = true)).isNull()
    }

    @Test
    fun `empty timeline has only the zero boundary`() {
        val empty = Timeline()
        assertThat(TimelineNav.boundaries(empty)).containsExactly(0L)
        assertThat(TimelineNav.adjacentBoundary(empty, 0L, forward = true)).isNull()
        assertThat(TimelineNav.adjacentBoundary(empty, 0L, forward = false)).isNull()
    }
}
