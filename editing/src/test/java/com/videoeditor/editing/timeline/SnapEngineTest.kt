package com.videoeditor.editing.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Magnetic snapping. The threshold is a screen distance converted to time at
 * the current zoom, so the same pixel proximity snaps whether zoomed in or
 * out — verified here by snapping at two different zoom levels.
 */
class SnapEngineTest {

    private val candidates = listOf(
        0L to SnapEngine.SnapTarget.TimelineStart,
        15_000_000L to SnapEngine.SnapTarget.TimelineEnd,
        7_000_000L to SnapEngine.SnapTarget.Playhead,
        5_000_000L to SnapEngine.SnapTarget.ClipBoundary("b", 5_000_000L, isStart = true),
    )

    @Test
    fun `snaps to nearest candidate within threshold`() {
        // 100px/s; a 10px threshold is 100ms. 6.95s is 50ms from the playhead.
        val ppm = 100f / 1_000_000f
        val result = SnapEngine.snap(6_950_000L, ppm, 10f, candidates)
        assertThat(result.isSnapped).isTrue()
        assertThat(result.timeMicros).isEqualTo(7_000_000L)
        assertThat(result.target).isEqualTo(SnapEngine.SnapTarget.Playhead)
    }

    @Test
    fun `does not snap beyond the threshold`() {
        val ppm = 100f / 1_000_000f
        // 6.5s is 500ms from the playhead — far beyond a 10px (100ms) threshold.
        val result = SnapEngine.snap(6_500_000L, ppm, 10f, candidates)
        assertThat(result.isSnapped).isFalse()
        assertThat(result.timeMicros).isEqualTo(6_500_000L)
    }

    @Test
    fun `threshold is zoom-consistent in pixels not time`() {
        // Zoomed in 4x, the same 10px threshold is only 25ms of time. A 30ms
        // offset snaps at the shallow zoom but not at the deep one.
        val shallow = 100f / 1_000_000f
        val deep = 400f / 1_000_000f
        val offset = 6_970_000L // 30ms from the playhead

        assertThat(SnapEngine.snap(offset, shallow, 10f, candidates).isSnapped).isTrue()
        assertThat(SnapEngine.snap(offset, deep, 10f, candidates).isSnapped).isFalse()
    }

    @Test
    fun `nearest candidate wins on a tie of two`() {
        val ppm = 100f / 1_000_000f
        // 5.02s is 20ms from the clip boundary and 1.98s from the playhead.
        val result = SnapEngine.snap(5_020_000L, ppm, 30f, candidates)
        assertThat(result.timeMicros).isEqualTo(5_000_000L)
        assertThat(result.target)
            .isEqualTo(SnapEngine.SnapTarget.ClipBoundary("b", 5_000_000L, isStart = true))
    }

    @Test
    fun `empty candidates never snap`() {
        val result = SnapEngine.snap(7_000_000L, 100f / 1_000_000f, 10f, emptyList())
        assertThat(result.isSnapped).isFalse()
        assertThat(result.timeMicros).isEqualTo(7_000_000L)
    }

    @Test
    fun `candidates exclude the dragged clip and include boundaries`() {
        val cands = SnapEngine.candidatesForMove(
            clipBoundaries = listOf(
                Triple("a", 0L, 4_000_000L),
                Triple("dragged", 4_000_000L, 8_000_000L),
                Triple("c", 8_000_000L, 12_000_000L),
            ),
            draggedClipId = "dragged",
            playheadMicros = 6_000_000L,
            durationMicros = 12_000_000L,
        )
        val times = cands.map { it.first }
        // Timeline start/end and playhead always present.
        assertThat(times).containsAtLeast(0L, 12_000_000L, 6_000_000L)
        // Boundaries of other clips, not the dragged one.
        assertThat(times).containsAtLeast(4_000_000L, 8_000_000L)
        assertThat(cands.none { it.second is SnapEngine.SnapTarget.ClipBoundary &&
            (it.second as SnapEngine.SnapTarget.ClipBoundary).clipId == "dragged" }).isTrue()
    }

    @Test
    fun `moved clip snaps by whichever edge is closer`() {
        val ppm = 100f / 1_000_000f
        val clipDuration = 4_000_000L
        // Dragging so the clip's *end* (proposedStart+4s = 5.0s) lands on the
        // 5s boundary → proposed start snaps to 1s.
        val result = SnapEngine.snapMovedClip(
            proposedStartMicros = 950_000L, // end at 4.95s, 50ms from 5s
            clipDurationMicros = clipDuration,
            pixelsPerMicro = ppm,
            thresholdPx = 10f,
            candidates = candidates,
        )
        assertThat(result.isSnapped).isTrue()
        assertThat(result.timeMicros).isEqualTo(1_000_000L) // end now exactly 5s
    }

    @Test
    fun `moved clip does not snap when both edges are far`() {
        val ppm = 100f / 1_000_000f
        val result = SnapEngine.snapMovedClip(
            proposedStartMicros = 10_000_000L, // start 10s, end 14s; both far
            clipDurationMicros = 4_000_000L,
            pixelsPerMicro = ppm,
            thresholdPx = 10f,
            candidates = candidates,
        )
        assertThat(result.isSnapped).isFalse()
        assertThat(result.timeMicros).isEqualTo(10_000_000L)
    }
}
