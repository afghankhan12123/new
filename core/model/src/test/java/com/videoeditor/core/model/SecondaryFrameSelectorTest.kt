package com.videoeditor.core.model

import com.videoeditor.core.model.render.SecondaryFrameSelector.select
import org.junit.Assert.assertEquals
import org.junit.Test

class SecondaryFrameSelectorTest {
    @Test fun padding_before_visible_interval_cannot_become_transition_video() {
        assertEquals(-1, select(longArrayOf(966_667), 1_000_000, 1_000_000, 2_000_000, false))
    }
    @Test fun future_padding_cannot_replace_last_visible_picture() {
        assertEquals(0, select(longArrayOf(1_966_667, 2_000_000), 1_999_000, 1_000_000, 2_000_000, false))
    }
    @Test fun unconfirmed_single_old_picture_waits_for_input() {
        assertEquals(-1, select(longArrayOf(1_900_000), 1_950_000, 1_000_000, 2_000_000, false))
    }
    @Test fun decoded_interval_completion_releases_last_picture_for_its_interval() {
        assertEquals(0, select(longArrayOf(1_900_000), 1_950_000, 1_000_000, 2_000_000, true))
    }
    @Test fun terminal_eos_does_not_make_padding_a_valid_picture() {
        assertEquals(-1, select(longArrayOf(900_000, 2_000_000), 1_950_000, 1_000_000, 2_000_000, true))
    }
    @Test fun nearest_valid_bracket_preserves_earlier_frame_on_tie() {
        assertEquals(0, select(longArrayOf(1_000_000, 1_100_000), 1_050_000, 1_000_000, 2_000_000, false))
    }
    @Test fun next_join_cannot_reuse_last_join_watermark_or_frames() {
        assertEquals(-1, select(longArrayOf(1_966_667, 2_900_000), 3_000_000, 3_000_000, 4_000_000, false))
    }
}
