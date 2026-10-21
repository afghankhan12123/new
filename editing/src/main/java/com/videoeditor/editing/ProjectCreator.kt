package com.videoeditor.editing

import com.videoeditor.core.common.util.Ids
import com.videoeditor.core.model.CanvasSettings
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.FrameRate
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Timeline
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a new [Project] from a confirmed media selection. A project is only
 * created once the user has actually chosen media — opening the picker alone
 * never creates an empty placeholder project.
 */
@Singleton
class ProjectCreator @Inject constructor() {

    fun create(
        name: String,
        mediaRefs: List<MediaRef>,
        frameRate: FrameRate = FrameRate.FPS_30,
        canvas: CanvasSettings? = null,
    ): Project {
        val now = System.currentTimeMillis()
        val videoAndPhotoClips = buildInitialClips(mediaRefs)

        return Project(
            id = Ids.newId(),
            name = name.ifBlank { defaultName(now) },
            createdAtMillis = now,
            updatedAtMillis = now,
            canvas = canvas ?: originalCanvas(mediaRefs),
            frameRate = frameRate,
            mediaRefs = mediaRefs,
            timeline = Timeline(videoClips = videoAndPhotoClips),
        )
    }

    private fun originalCanvas(mediaRefs: List<MediaRef>): CanvasSettings {
        val first = mediaRefs.firstOrNull { it.type != MediaType.Audio } ?: return CanvasSettings()
        val width = first.width?.takeIf { it > 0 } ?: return CanvasSettings()
        val height = first.height?.takeIf { it > 0 } ?: return CanvasSettings()
        val rotated = ((first.rotationDegrees % 360) + 360) % 360 in listOf(90, 270)
        return CanvasSettings(width = if (rotated) height else width, height = if (rotated) width else height)
    }

    /**
     * Lays video and photo clips sequentially on the main track. Audio-only
     * selections are not placed on the video track (they belong on the audio
     * track, added via the audio editor).
     */
    private fun buildInitialClips(mediaRefs: List<MediaRef>): List<Clip> {
        var cursor = 0L
        return mediaRefs
            .filter { it.type == MediaType.Video || it.type == MediaType.Photo }
            .map { ref ->
                val sourceDuration = ref.durationMicros ?: DEFAULT_PHOTO_DURATION_MICROS
                val isPhoto = ref.type == MediaType.Photo
                Clip(
                    id = Ids.newId(),
                    mediaRefId = ref.id,
                    sourceStartMicros = 0L,
                    sourceEndMicros = sourceDuration,
                    timelineStartMicros = cursor,
                    photoDurationMicros = if (isPhoto) DEFAULT_PHOTO_DURATION_MICROS else null,
                ).also { cursor += it.timelineDurationMicros }
            }
    }

    /**
     * A friendly default name for a new project. Existing projects are never
     * renamed automatically — this only applies when the user creates one
     * without choosing a name.
     */
    private fun defaultName(now: Long): String {
        val date = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(now))
        return "Untitled Project · $date"
    }

    companion object {
        private const val DEFAULT_PHOTO_DURATION_MICROS = 3_000_000L
    }
}
