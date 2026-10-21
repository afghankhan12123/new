package com.videoeditor.core.model

import kotlinx.serialization.Serializable

enum class MediaType {
    Video,
    Photo,
    Audio,
}

/**
 * A reference to a piece of source media. The editor never mutates the original
 * file; it only records enough information to re-open it later.
 *
 * [uri] is a persistable content URI (Photo Picker / SAF). Callers are expected
 * to hold a persistable permission for it; if the URI becomes inaccessible the
 * project surfaces a "missing media" state rather than failing silently.
 */
@Serializable
data class MediaRef(
    val id: String,
    val uri: String,
    val type: MediaType,
    val displayName: String,
    val mimeType: String? = null,
    val durationMicros: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int = 0,
    /** Original source location retained across explicit relinks and saved-project reopening. */
    val originalUri: String? = null,
    /** Null for legacy documents; extracted sources explicitly report audio presence. */
    val hasAudio: Boolean? = null,
    /** EXIF orientation for photos; null identifies documents awaiting metadata resolution. */
    val photoOrientation: Int? = null,
    /** Extracted audio layout; null for legacy/unresolved media. */
    val audioChannelCount: Int? = null,
)
