package com.videoeditor.core.media.preview

import org.junit.Assert.*
import org.junit.Test

class PreviewPeriodTimeTest {
    @Test fun trimmed_fast_first_period_acknowledges_its_actual_reset() {
        val offset=1_000_000_000_001L
        val renderer=1_000_000_933_333L
        val trim=PreviewPeriodTime.adjustedTrimStartUs(500_000,1.5f)
        assertEquals(333_333L,trim)
        assertTrue(SeekResetPosition.matches(600_000,renderer-offset-trim,0,1_333_333))
        assertFalse(SeekResetPosition.matches(600_000,renderer-offset-500_000,0,1_333_333))
    }
    @Test fun first_period_origin_uses_period_speed_domain_in_both_directions() {
        assertEquals(1_000_000L,PreviewPeriodTime.adjustedTrimStartUs(500_000,.5f))
        assertEquals(500_000L,PreviewPeriodTime.adjustedTrimStartUs(500_000,1f))
        assertEquals(250_000L,PreviewPeriodTime.adjustedTrimStartUs(500_000,2f))
        assertEquals(0L,PreviewPeriodTime.adjustedTrimStartUs(0,1.5f))
    }
}
