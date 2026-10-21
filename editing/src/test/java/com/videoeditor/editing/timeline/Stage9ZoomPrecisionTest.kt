package com.videoeditor.editing.timeline

import org.junit.Assert.*
import org.junit.Test

class Stage9ZoomPrecisionTest {
    @Test fun two_finger_translation_still_moves_at_the_scale_limit() {
        val viewport = TimelineViewport(60_000_000, .001f, 1080f, 540f)
        val time = 30_000_000L
        val scroll = viewport.scrollXForTime(time)
        val moved = viewport.scrollXForZoomPan(viewport.pixelsPerMicro, time, 640f)
        assertEquals(scroll - 100f, moved, .01f)
    }
    @Test fun repeated_inverse_zoom_uses_one_focal_time_without_cumulative_drift() {
        val viewport = TimelineViewport(3_600_000_000, .0002f, 1080f, 540f)
        val time = 1_234_567_890L
        val focal = 700f
        val original = viewport.scrollXForZoomPan(viewport.pixelsPerMicro, time, focal)
        repeat(1_000) {
            val enlarged = viewport.scrollXForZoomPan(viewport.pixelsPerMicro * 2f, time, focal)
            assertTrue(enlarged > original)
            assertEquals(original, viewport.scrollXForZoomPan(viewport.pixelsPerMicro, time, focal), 0f)
        }
    }
}
