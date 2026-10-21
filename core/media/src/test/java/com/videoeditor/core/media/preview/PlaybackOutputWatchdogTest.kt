package com.videoeditor.core.media.preview

import org.junit.Assert.*
import org.junit.Test

class PlaybackOutputWatchdogTest {
    @Test fun first_frame_does_not_disable_later_stall_detection() {
        val owner = Any(); val watcher = PlaybackOutputWatchdog()
        assertFalse(watcher.stalled(owner, 10, 0))
        assertFalse(watcher.stalled(owner, 20, 3_999))
        assertFalse(watcher.stalled(owner, 20, 7_998))
        assertTrue(watcher.stalled(owner, 20, 7_999))
    }
    @Test fun pause_and_replaced_owner_restart_the_deadline() {
        val watcher = PlaybackOutputWatchdog(); val owner = Any()
        watcher.stalled(owner, 0, 0)
        assertFalse(watcher.stalled(Any(), 0, 9_000))
        watcher.reset()
        assertFalse(watcher.stalled(owner, 0, 99_000))
    }
}
