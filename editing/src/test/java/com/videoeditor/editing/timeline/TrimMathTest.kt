package com.videoeditor.editing.timeline

import com.google.common.truth.Truth.assertThat
import com.videoeditor.core.model.Clip
import org.junit.Test

/**
 * Trim boundary enforcement: trims must stay inside the source media, keep a
 * minimum span (never zero or negative durations), scale deltas by playback
 * speed, and never move the untouched edge.
 */
class TrimMathTest {

    private fun clip(
        sourceStart: Long = 1_000_000L,
        sourceEnd: Long = 5_000_000L,
        speed: Float = 1f,
    ) = Clip(
        id = "c",
        mediaRefId = "ref",
        sourceStartMicros = sourceStart,
        sourceEndMicros = sourceEnd,
        playbackSpeed = speed,
    )

    private val sourceDuration = 10_000_000L // 10s source file

    @Test
    fun `start trim moves only the start`() {
        val result = TrimMath.proposeTrim(
            clip(sourceStart = 1_000_000L, sourceEnd = 5_000_000L),
            TrimMath.Edge.Start,
            deltaTimelineMicros = 500_000L,
            sourceDurationMicros = sourceDuration,
        )
        assertThat(result.startMicros).isEqualTo(1_500_000L)
        assertThat(result.endMicros).isEqualTo(5_000_000L)
        assertThat(TrimMath.isValid(result, sourceDuration)).isTrue()
    }

    @Test
    fun `end trim moves only the end`() {
        val result = TrimMath.proposeTrim(
            clip(),
            TrimMath.Edge.End,
            deltaTimelineMicros = -1_000_000L,
            sourceDurationMicros = sourceDuration,
        )
        assertThat(result.startMicros).isEqualTo(1_000_000L)
        assertThat(result.endMicros).isEqualTo(4_000_000L)
    }

    @Test
    fun `start trim cannot go before the source beginning`() {
        val result = TrimMath.proposeTrim(
            clip(sourceStart = 1_000_000L),
            TrimMath.Edge.Start,
            deltaTimelineMicros = -5_000_000L,
            sourceDurationMicros = sourceDuration,
        )
        assertThat(result.startMicros).isEqualTo(0L)
    }

    @Test
    fun `end trim cannot exceed the source end`() {
        val result = TrimMath.proposeTrim(
            clip(sourceEnd = 5_000_000L),
            TrimMath.Edge.End,
            deltaTimelineMicros = 50_000_000L,
            sourceDurationMicros = sourceDuration,
        )
        assertThat(result.endMicros).isEqualTo(sourceDuration)
    }

    @Test
    fun `trims never collapse below the minimum span`() {
        // Drag the start handle past the end: clamps so span == minimum.
        val collapsedStart = TrimMath.proposeTrim(
            clip(sourceStart = 1_000_000L, sourceEnd = 5_000_000L),
            TrimMath.Edge.Start,
            deltaTimelineMicros = 20_000_000L,
            sourceDurationMicros = sourceDuration,
        )
        assertThat(collapsedStart.startMicros)
            .isEqualTo(5_000_000L - TrimMath.MIN_SOURCE_SPAN_MICROS)
        assertThat(collapsedStart.durationMicros)
            .isEqualTo(TrimMath.MIN_SOURCE_SPAN_MICROS)

        // Drag the end handle before the start.
        val collapsedEnd = TrimMath.proposeTrim(
            clip(),
            TrimMath.Edge.End,
            deltaTimelineMicros = -20_000_000L,
            sourceDurationMicros = sourceDuration,
        )
        assertThat(collapsedEnd.endMicros)
            .isEqualTo(1_000_000L + TrimMath.MIN_SOURCE_SPAN_MICROS)
        assertThat(collapsedEnd.durationMicros)
            .isEqualTo(TrimMath.MIN_SOURCE_SPAN_MICROS)
    }

    @Test
    fun `durations are never negative even for pathological deltas`() {
        for (delta in listOf(Long.MIN_VALUE, -999_999_999_999L, 999_999_999_999L, Long.MAX_VALUE)) {
            for (edge in TrimMath.Edge.entries) {
                val result = TrimMath.proposeTrim(clip(), edge, delta, sourceDuration)
                assertThat(result.durationMicros)
                    .isAtLeast(TrimMath.MIN_SOURCE_SPAN_MICROS)
                assertThat(TrimMath.isValid(result, sourceDuration)).isTrue()
            }
        }
    }

    @Test
    fun `delta scales with playback speed`() {
        // At 2x, dragging 1s of timeline consumes 2s of source.
        val result = TrimMath.proposeTrim(
            clip(sourceStart = 2_000_000L, sourceEnd = 8_000_000L, speed = 2f),
            TrimMath.Edge.End,
            deltaTimelineMicros = 1_000_000L,
            sourceDurationMicros = sourceDuration,
        )
        assertThat(result.endMicros).isEqualTo(10_000_000L)

        // At 0.5x, dragging 1s of timeline consumes only 0.5s of source.
        val slow = TrimMath.proposeTrim(
            clip(sourceStart = 2_000_000L, sourceEnd = 8_000_000L, speed = 0.5f),
            TrimMath.Edge.End,
            deltaTimelineMicros = 1_000_000L,
            sourceDurationMicros = sourceDuration,
        )
        assertThat(slow.endMicros).isEqualTo(8_500_000L)
    }

    @Test
    fun `start trim near source end leaves a valid minimum window`() {
        // Clip already ends at the source end; dragging the start handle
        // forward is limited to keep the minimum span.
        val result = TrimMath.proposeTrim(
            clip(sourceStart = 9_500_000L, sourceEnd = 10_000_000L),
            TrimMath.Edge.Start,
            deltaTimelineMicros = 5_000_000L,
            sourceDurationMicros = sourceDuration,
        )
        assertThat(result.startMicros)
            .isEqualTo(sourceDuration - TrimMath.MIN_SOURCE_SPAN_MICROS)
        assertThat(TrimMath.isValid(result, sourceDuration)).isTrue()
    }

    @Test
    fun `isValid rejects out-of-range and short ranges`() {
        assertThat(TrimMath.isValid(TrimMath.SourceRange(-1L, 5_000_000L), sourceDuration)).isFalse()
        assertThat(TrimMath.isValid(TrimMath.SourceRange(0L, 10_000_001L), sourceDuration)).isFalse()
        assertThat(TrimMath.isValid(TrimMath.SourceRange(0L, 99_999L), sourceDuration)).isFalse()
        assertThat(TrimMath.isValid(TrimMath.SourceRange(0L, 100_000L), sourceDuration)).isTrue()
    }

    @Test
    fun `timeline duration after trim equals source span over speed`() {
        val trimmed = TrimMath.proposeTrim(
            clip(sourceStart = 0L, sourceEnd = 6_000_000L, speed = 2f),
            TrimMath.Edge.Start,
            deltaTimelineMicros = 500_000L, // consumes 1s of source at 2x
            sourceDurationMicros = 8_000_000L,
        )
        assertThat(trimmed.startMicros).isEqualTo(1_000_000L)
        val newClip = clip(sourceStart = trimmed.startMicros, sourceEnd = trimmed.endMicros, speed = 2f)
        // (6s-1s)/2 = 2.5s of timeline.
        assertThat(newClip.timelineDurationMicros).isEqualTo(2_500_000L)
    }
}
