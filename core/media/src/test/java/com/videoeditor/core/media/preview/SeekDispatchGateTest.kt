package com.videoeditor.core.media.preview

import org.junit.Assert.*
import org.junit.Test

class SeekDispatchGateTest {
    @Test fun unresolved_drag_decode_gets_work_budget_but_final_lift_never_waits_for_budget() {
        val gate = SeekDispatchGate()
        assertTrue(gate.canSupersede(0, false))
        gate.dispatched(100)
        assertFalse(gate.canSupersede(116, false))
        assertFalse(gate.canSupersede(179, false))
        assertTrue(gate.canSupersede(180, false))
        assertTrue(gate.canSupersede(116, true))
    }
    @Test fun replacement_generation_does_not_inherit_old_dispatch_budget() {
        val gate = SeekDispatchGate()
        gate.dispatched(100)
        gate.reset()
        assertTrue(gate.canSupersede(101, false))
    }
    @Test fun fractional_speed_reset_rounding_is_normalized_only_inside_correct_period() {
        assertTrue(SeekResetPosition.matches(0, -1, 0, 4_000_000))
        assertFalse(SeekResetPosition.matches(0, -2_000, 0, 4_000_000))
        assertTrue(SeekResetPosition.matches(3_000_000, 2_999_999, 2_000_000, 4_000_000))
        assertFalse(SeekResetPosition.matches(3_000_000, 3_000_000, 0, 3_000_000))
        assertFalse(SeekResetPosition.matches(3_000_000, 3_002_000, 2_000_000, 4_000_000))
        assertFalse(SeekResetPosition.matches(3_000_000, -1, 2_000_000, 4_000_000))
    }

}
