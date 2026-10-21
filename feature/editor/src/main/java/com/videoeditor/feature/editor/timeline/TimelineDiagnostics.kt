package com.videoeditor.feature.editor.timeline

import androidx.compose.ui.semantics.SemanticsPropertyKey

/** Non-accessibility test data taken from the same state used by the timeline Canvas. */
object TimelineDiagnostics {
    val Snapshot = SemanticsPropertyKey<List<String>>("TimelinePlaybackSnapshot")
    /** Main-thread reader for observations that must not wait for playback to idle. */
    val LiveSnapshot = SemanticsPropertyKey<() -> List<String>>("TimelineLivePlaybackSnapshot")
}
