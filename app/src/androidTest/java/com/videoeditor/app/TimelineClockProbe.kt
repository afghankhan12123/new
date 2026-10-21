package com.videoeditor.app

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.videoeditor.feature.editor.timeline.TimelineDiagnostics
import org.junit.Assert.assertEquals

/** Immutable clock telemetry read on its Compose owner thread. */
internal fun readTimelineClock(ui: ComposeTestRule): List<String> {
    return timelineClockReader(ui).invoke()
}

/** Acquire while idle, then observe the live node without an Espresso idle wait
 * for every sample during continuous playback. Reacquire after reopening UI. */
internal fun timelineClockReader(ui: ComposeTestRule): () -> List<String> {
    // Compose 1.7.6 fetchSemanticsNode unconditionally printToString's live
    // descendants on the instrumentation thread. That races layout/semantics
    // during playback. Collection fetch avoids that diagnostic traversal.
    val nodes = ui.onAllNodesWithTag("timeline").fetchSemanticsNodes()
    assertEquals("Expected exactly one editor timeline", 1, nodes.size)
    val node = nodes.single()
    val read = ui.runOnUiThread { node.config[TimelineDiagnostics.LiveSnapshot] }
    return { ui.runOnUiThread { read().toList() } }
}
