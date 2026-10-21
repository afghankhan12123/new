package com.videoeditor.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri

/** Bounded image decoding; ImageDecoder applies the image's EXIF orientation. */
internal fun decodeImageThumbnail(context: Context, uri: Uri, targetEdge: Int): Bitmap? {
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val ratio = minOf(1f, targetEdge.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val options = BitmapFactory.Options().apply {
        while (maxOf(bounds.outWidth, bounds.outHeight) / (inSampleSize * 2) >= targetEdge) inSampleSize *= 2
    }
    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val orientation = readPhotoOrientation(context, uri)
    val matrix = android.graphics.Matrix()
    if (orientation in listOf(2, 4, 5, 7)) matrix.setScale(-1f, 1f)
    val rotation = when (orientation) { 3, 4 -> 180f; 5, 8 -> 270f; 6, 7 -> 90f; else -> 0f }
    matrix.postRotate(rotation)
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
}
