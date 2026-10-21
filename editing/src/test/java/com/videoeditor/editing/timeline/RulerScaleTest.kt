package com.videoeditor.editing.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Ruler division selection and labels: the ruler must stay readable at every
 * zoom level, choosing nice intervals (10ms … 10min) and formatting labels at
 * the matching precision.
 */
class RulerScaleTest {

    @Test
    fun `interval is the smallest nice step at least minSpacing wide`() {
        // 100px per second, 80px minimum spacing → 1s ticks (100px apart);
        // 0.5s would only be 50px apart.
        assertThat(RulerScale.intervalMicros(100f / 1_000_000f, 80f)).isEqualTo(1_000_000L)
        // 200px per second, 150px minimum → still 1s.
        assertThat(RulerScale.intervalMicros(200f / 1_000_000f, 150f)).isEqualTo(1_000_000L)
        // 1000px per second, 150px minimum → 200ms (200px apart); 100ms would
        // be only 100px apart.
        assertThat(RulerScale.intervalMicros(1000f / 1_000_000f, 150f)).isEqualTo(200_000L)
    }

    @Test
    fun `interval grows when zoomed out`() {
        // 10px per second: 1s ticks are 10px apart — far too dense for an
        // 80px minimum. The smallest fit is 10s (100px).
        assertThat(RulerScale.intervalMicros(10f / 1_000_000f, 80f)).isEqualTo(10_000_000L)
    }

    @Test
    fun `interval falls back to the largest step at extreme zoom-out`() {
        // At 1e-8 px/µs even the 10-minute step is only 6px wide; the ladder
        // tops out at it rather than returning nothing.
        assertThat(RulerScale.intervalMicros(0.00000001f, 80f)).isEqualTo(600_000_000L)
    }

    @Test
    fun `intervals are always nice 1-2-5 style steps`() {
        val nice = setOf(
            10_000L, 20_000L, 50_000L, 100_000L, 200_000L, 500_000L,
            1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L, 15_000_000L,
            30_000_000L, 60_000_000L, 120_000_000L, 300_000_000L, 600_000_000L,
        )
        // Sweep the app's full zoom range.
        var ppm = 1000f / 250_000f // max zoom
        while (ppm > 1000f / 45_000_000f) { // below min zoom
            assertThat(nice).contains(RulerScale.intervalMicros(ppm, 72f))
            ppm /= 1.7f
        }
    }

    @Test
    fun `major ticks are exact multiples of the interval`() {
        assertThat(RulerScale.isMajorTick(3_000_000L, 1_000_000L)).isTrue()
        assertThat(RulerScale.isMajorTick(3_500_000L, 1_000_000L)).isFalse()
        assertThat(RulerScale.isMajorTick(0L, 1_000_000L)).isTrue()
    }

    @Test
    fun `labels match the interval precision`() {
        assertThat(RulerScale.formatTickLabel(0L, 1_000_000L)).isEqualTo("0:00")
        assertThat(RulerScale.formatTickLabel(65_000_000L, 1_000_000L)).isEqualTo("1:05")
        assertThat(RulerScale.formatTickLabel(1_500_000L, 500_000L)).isEqualTo("0:01.5")
        assertThat(RulerScale.formatTickLabel(250_000L, 100_000L)).isEqualTo("0:00.2")
        assertThat(RulerScale.formatTickLabel(12_340_000L, 10_000L)).isEqualTo("0:12.34")
    }

    @Test
    fun `timecode formats transport display`() {
        assertThat(RulerScale.formatTimecode(0L)).isEqualTo("00:00")
        assertThat(RulerScale.formatTimecode(9_999_999L)).isEqualTo("00:09")
        assertThat(RulerScale.formatTimecode(75_000_000L)).isEqualTo("01:15")
        assertThat(RulerScale.formatTimecode(-5L)).isEqualTo("00:00")
    }
}
