package com.videoeditor.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.videoeditor.core.common.result.Result
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates downscaled thumbnails from media URIs. Uses [MediaMetadataRetriever]
 * with a target size so full-resolution frames are never held in memory.
 */
@Singleton
class ThumbnailGenerator @Inject constructor() {

    fun generateVideoThumbnail(
        context: Context,
        uri: Uri,
        timeMicros: Long = 0L,
        targetWidth: Int = 320,
    ): Result<Bitmap, MediaError> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bitmap = retriever.getFrameAtTime(
                timeMicros,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return Result.Failure(MediaError.ThumbnailFailed("No frame available"))
            Result.Success(scaleDown(bitmap, targetWidth))
        } catch (t: Throwable) {
            Result.Failure(MediaError.ThumbnailFailed(t.message ?: "Thumbnail failed"))
        } finally {
            retriever.release()
        }
    }

    fun generateImageThumbnail(
        context: Context,
        uri: Uri,
        targetWidth: Int = 320,
    ): Result<Bitmap, MediaError> {
        return try {
            val bitmap = decodeImageThumbnail(context, uri, targetWidth)
                ?: return Result.Failure(MediaError.ThumbnailFailed("No image available"))
            Result.Success(scaleDown(bitmap, targetWidth))
        } catch (t: Throwable) {
            Result.Failure(MediaError.ThumbnailFailed(t.message ?: "Thumbnail failed"))
        }
    }

    private fun scaleDown(source: Bitmap, targetWidth: Int): Bitmap {
        if (source.width <= targetWidth) return source
        val ratio = targetWidth.toFloat() / source.width
        val targetHeight = (source.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        if (scaled !== source) source.recycle()
        return scaled
    }
}
