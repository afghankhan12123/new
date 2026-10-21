package com.videoeditor.feature.editor

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Dimensions shared by the shell and tool hosts. Values describe usable window space, not video pixels. */
internal object EditorMetrics {
    val TouchTarget = 48.dp
    val Icon = 20.dp
    val Header = 56.dp
    val Dock = 76.dp
    val Transport = 48.dp
    val PanelPadding = 16.dp
    val Corner = 10.dp
    val TimelineCompact = 88.dp
    const val MotionMillis = 160

    /** Allocate after IME/insets. Text entry keeps a usable canvas and always-visible Done action. */
    fun previewHeight(available: Dp, panelOpen: Boolean, keyboard: Boolean, expanded: Boolean): Dp {
        val remaining = (available - Header - Transport).coerceAtLeast(0.dp)
        return when {
            keyboard -> (remaining * .53f).coerceIn(100.dp, 210.dp).coerceAtMost(remaining * .65f)
            expanded -> (remaining * .25f).coerceIn(112.dp, 190.dp)
            panelOpen -> (remaining * .40f).coerceIn(176.dp, 300.dp)
            else -> (remaining - Dock - 220.dp).coerceIn(150.dp, 480.dp)
        }.coerceAtMost(remaining)
    }
}
