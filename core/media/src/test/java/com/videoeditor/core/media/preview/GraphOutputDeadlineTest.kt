package com.videoeditor.core.media.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphOutputDeadlineTest {
    @Test fun only_owned_target_output_completes_startup() {
        val owner = Any()
        val pending = GraphOutputDeadline(owner, 3_933_000, 0, 100)
        assertEquals(GraphOutputDeadline.Result.Waiting, pending.observe(owner, 300_000, 4_099))
        // 3_850_000 is 83ms away (> 34ms tolerance) so it waits
        assertEquals(GraphOutputDeadline.Result.Waiting, pending.observe(owner, 3_850_000, 4_099))
        // 3_900_000 is 33ms away (<= 34ms tolerance) so it is accepted as delivered
        assertEquals(GraphOutputDeadline.Result.Delivered, pending.observe(owner, 3_900_000, 4_099))
        assertEquals(GraphOutputDeadline.Result.Delivered, pending.observe(owner, 3_933_333, 4_100))
        assertEquals(GraphOutputDeadline.Result.Retired, pending.observe(Any(), 3_933_333, 4_100))
        assertEquals(GraphOutputDeadline.Result.Retired, pending.observe(null, 3_933_333, 4_100))
    }

    @Test fun missing_output_allows_one_retry_then_fails() {
        val owner = Any()
        assertEquals(GraphOutputDeadline.Result.Retry,
            GraphOutputDeadline(owner, 0, 0, 100).observe(owner, Long.MIN_VALUE, 4_100))
        assertEquals(GraphOutputDeadline.Result.Failed,
            GraphOutputDeadline(owner, 0, 1, 100).observe(owner, Long.MIN_VALUE, 4_100))
    }
}
