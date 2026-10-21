package com.videoeditor.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.videoeditor.core.common.result.Result
import com.videoeditor.core.common.result.map
import com.videoeditor.core.common.result.runCatchingResult
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.core.model.Timing
import com.videoeditor.core.common.util.Ids
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts duration, dimensions, rotation, and MIME type from a media URI
 * without loading full-resolution frames into memory.
 */
@Singleton
class MediaMetadataExtractor @Inject constructor() {

    fun extract(context: Context, uri: Uri): Result<MediaRef, MediaError> = runCatchingResult {
        val declaredMime = context.contentResolver.getType(uri) ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase())
        if (declaredMime?.startsWith("image/") == true) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val stream = context.contentResolver.openInputStream(uri) ?: error("Image cannot be opened")
            stream.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image cannot be decoded" }
            val orientation = readPhotoOrientation(context, uri)
            val swap = orientation in 5..8
            return@runCatchingResult MediaRef(
                id = Ids.newId(), uri = uri.toString(), type = MediaType.Photo,
                displayName = displayNameFromUri(context, uri), mimeType = declaredMime,
                width = if (swap) bounds.outHeight else bounds.outWidth,
                height = if (swap) bounds.outWidth else bounds.outHeight, photoOrientation = orientation,
            )
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            // METADATA_KEY_DURATION is milliseconds; the model is microseconds.
            val durationMicros = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { it * Timing.MICROS_PER_MILLISECOND }
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            val type = when {
                mimeType?.startsWith("video") == true -> MediaType.Video
                mimeType?.startsWith("image") == true -> MediaType.Photo
                mimeType?.startsWith("audio") == true -> MediaType.Audio
                else -> MediaType.Video
            }

            MediaRef(
                id = Ids.newId(),
                uri = uri.toString(),
                type = type,
                displayName = displayNameFromUri(context, uri),
                mimeType = mimeType,
                durationMicros = durationMicros,
                width = width,
                height = height,
                rotationDegrees = rotation,
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                audioChannelCount = readAudioChannels(context, uri),
            )
        } finally {
            retriever.release()
        }
    }.mapFailureToMediaError()

    private fun readAudioChannels(context: Context, uri: Uri): Int? = runCatching {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString("mime")?.startsWith("audio/") == true }
                ?.let { if (it.containsKey("channel-count")) it.getInteger("channel-count") else null }
        } finally { extractor.release() }
    }.getOrNull()

    private fun displayNameFromUri(context: Context, uri: Uri): String {
        val providerName = if (uri.scheme == "content") runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull() else null
        return providerName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "media"
    }

    /** Resolve legacy photo orientation and audio metadata needed by speed edits. Call on IO. */
    fun resolveSpeedAudio(context: Context, project: com.videoeditor.core.model.Project): com.videoeditor.core.model.Project {
        val used = project.timeline.videoClips.filter { !it.isPhoto && !it.muted && it.playbackSpeed != 1f }.map { it.mediaRefId }.toSet()
        val audioRefs = project.timeline.audioClips.map { it.mediaRefId }.toSet() +
            project.timeline.videoClips.filter { !it.muted && !it.isPhoto }.map { it.mediaRefId } +
            project.timeline.overlayClips.filter { !it.muted && !it.isPhoto }.map { it.mediaRefId }
        fun needsLayout(ref: MediaRef) = ref.id in audioRefs && ref.hasAudio != false && ref.audioChannelCount == null
        if (project.mediaRefs.none { needsLayout(it) || (it.id in used && it.hasAudio == null) || (it.type == MediaType.Photo && it.photoOrientation == null) }) return project
        return project.copy(mediaRefs = project.mediaRefs.map { ref ->
            if (!(needsLayout(ref) || (ref.id in used && ref.hasAudio == null) || (ref.type == MediaType.Photo && ref.photoOrientation == null))) ref else when (val metadata = extract(context, Uri.parse(ref.uri))) {
                is Result.Success -> if (ref.type == MediaType.Photo) ref.copy(width = metadata.value.width, height = metadata.value.height, photoOrientation = metadata.value.photoOrientation) else ref.copy(hasAudio = metadata.value.hasAudio, audioChannelCount = metadata.value.audioChannelCount)
                is Result.Failure -> ref
            }
        })
    }

    private fun Result<MediaRef, Throwable>.mapFailureToMediaError(): Result<MediaRef, MediaError> =
        when (this) {
            is Result.Success -> this
            is Result.Failure -> Result.Failure(
                MediaError.MetadataExtractionFailed(error.message ?: "Unknown error")
            )
        }
}

/** Decode orientation separately from bitmap bounds, including mirrored camera images. */
internal fun readPhotoOrientation(context: Context, uri: Uri): Int = runCatching {
    context.contentResolver.openInputStream(uri)?.use {
        android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
    } ?: 1
}.getOrDefault(1).takeIf { it in 1..8 } ?: 1
