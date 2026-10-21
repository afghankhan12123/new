package com.videoeditor.core.model

import kotlinx.serialization.Serializable

/**
 * The complete, versioned, non-destructive description of an editing project.
 *
 * A project is persisted as a serialized document (plus a Room row for fast
 * listing). The [version] field enables future schema migrations; the document
 * is the source of truth for the edit, and the original media files are never
 * modified.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val version: Int = CURRENT_VERSION,
    val canvas: CanvasSettings = CanvasSettings(),
    val frameRate: FrameRate = FrameRate.FPS_30,
    val mediaRefs: List<MediaRef> = emptyList(),
    val timeline: Timeline = Timeline(),
    val exportPreferences: ExportPreferences = ExportPreferences(),
    val isFavorite: Boolean = false,
    val inTrash: Boolean = false,
    val lastExportFailed: Boolean = false,
) {
    fun mediaRef(id: String): MediaRef? = mediaRefs.firstOrNull { it.id == id }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * User's preferred export configuration, remembered per project.
 *
 * These values are the durable per-project settings the export screen restores
 * on reopen. [frameRate] is validated against device capabilities at export
 * time; an older or future value the current build cannot encode falls back to
 * [FrameRate.FPS_30] (the accepted Stage 6 default) rather than failing.
 */
@Serializable
data class ExportPreferences(
    val resolution: ExportResolution = ExportResolution.RES_1080P,
    val frameRate: FrameRate = FrameRate.FPS_30,
    val quality: ExportQuality = ExportQuality.Standard,
)

enum class ExportResolution(val label: String, val width: Int, val height: Int) {
    RES_720P("720p", 1280, 720),
    RES_1080P("1080p", 1920, 1080),
}

enum class ExportQuality(val label: String) {
    /** Serialized name retained for document compatibility; shown as the
     * middle "Balanced" preset since Stage 7. */
    Smaller("Smaller File"),
    Standard("Balanced"),
    High("Higher Quality"),
}
