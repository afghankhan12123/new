package com.videoeditor.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Follow the production toolbar and timeline menu; never invoke editor callbacks. */
internal fun editorTestAction(ui: ComposeTestRule, label: String) {
    fun clickVisible(text: String): Boolean {
        val nodes = ui.onAllNodes((hasText(text) or hasContentDescription(text)) and
            hasClickAction() and !hasSetTextAction())
        for (index in nodes.fetchSemanticsNodes().indices.reversed()) {
            val node = nodes[index]
            runCatching { node.performScrollTo() }
            if (runCatching { node.assertIsDisplayed() }.isSuccess) {
                node.assertIsEnabled().performClick()
                return true
            }
        }
        return false
    }
    if (clickVisible(label)) return
    val menuActions = setOf("Expand timeline", "Compact timeline", "Fit timeline",
        "Previous clip boundary", "Next clip boundary", "Zoom out timeline", "Zoom in timeline", "PiP")
    if (label in menuActions) {
        if (!clickVisible("Timeline options")) {
            ui.onNodeWithTag("editor-tool-Edit").performScrollTo().performClick()
            check(clickVisible("Timeline options")) { "Timeline options unavailable" }
        }
    } else if (label in setOf("Split", "Transition", "Trim", "Duration", "Crop", "Fade",
            "Rotate", "Volume", "Duplicate", "Delete", "Mute", "Unmute", "Deselect")) {
        ui.onNodeWithTag("editor-tool-Edit").performScrollTo().performClick()
    }
    check(clickVisible(label)) { "No reachable action: $label" }
}
