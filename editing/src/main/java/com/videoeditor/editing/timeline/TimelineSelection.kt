package com.videoeditor.editing.timeline

/**
 * What is currently selected on the timeline. Exactly one element across all
 * tracks may be selected at a time, so the contextual toolbar and the
 * trim-handle visibility are driven by a single, unambiguous value rather than
 * three nullable ids that could disagree.
 *
 * The id accessors are extension properties (below) rather than members: a
 * member named `audioClipId` would clash with the `Audio` case's own property.
 */
sealed interface TimelineSelection {
    data object None : TimelineSelection
    data class Video(val clipId: String) : TimelineSelection
    data class Audio(val audioClipId: String) : TimelineSelection
    data class Text(val overlayId: String) : TimelineSelection
    data class Overlay(val overlayId: String) : TimelineSelection
    data class Transition(val transitionId: String) : TimelineSelection
    data class Effect(val clipId: String, val effectId: String) : TimelineSelection
    data class Sticker(val stickerId: String) : TimelineSelection
}

/** The selected video clip's id, or null when something else is selected. */
val TimelineSelection.videoClipId: String?
    get() = (this as? TimelineSelection.Video)?.clipId

/** The selected audio clip's id, or null when something else is selected. */
val TimelineSelection.selectedAudioClipId: String?
    get() = (this as? TimelineSelection.Audio)?.audioClipId

/** The selected text overlay's id, or null when something else is selected. */
val TimelineSelection.selectedTextOverlayId: String?
    get() = (this as? TimelineSelection.Text)?.overlayId

/** The selected sticker's id, or null when something else is selected. */
val TimelineSelection.selectedStickerId: String?
    get() = (this as? TimelineSelection.Sticker)?.stickerId

val TimelineSelection.isEmpty: Boolean get() = this is TimelineSelection.None
