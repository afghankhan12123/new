package com.videoeditor.core.media.preview

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** One completed, content-addressed small photo. No graph or decoder resources are cached. */
@androidx.media3.common.util.UnstableApi
internal class PreviewBitmapCache(private val ownedDirectory: File) {
    private data class Entry(val uri: Uri, val digest: ByteArray, val bitmap: Bitmap)
    private val lock = Any()
    private var epoch = 0L
    private var entry: Entry? = null

    fun clear() = synchronized(lock) {
        epoch++
        // Only this cache owns the retained copy. Each graph receives a different bitmap.
        entry?.bitmap?.recycle()
        entry = null
    }

    fun load(loader: BitmapLoader, uri: Uri): ListenableFuture<Bitmap> {
        val bytes = snapshot(uri) ?: return loader.loadBitmap(uri)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val requestEpoch: Long
        synchronized(lock) {
            entry?.takeIf { it.uri == uri && it.digest.contentEquals(digest) && !it.bitmap.isRecycled }
                ?.let { return Futures.immediateFuture(it.bitmap.copy(Bitmap.Config.ARGB_8888, false)) }
            requestEpoch = epoch
        }
        // Decode the very bytes that were hashed, avoiding a second file read
        // that could race replacement. Publish the private copy before the
        // returned future allows a graph to consume/recycle the original.
        return Futures.transform(loader.decodeBitmap(bytes), { decoded ->
            val bitmap = checkNotNull(decoded)
            if (!bitmap.isRecycled && bitmap.config == Bitmap.Config.ARGB_8888 &&
                bitmap.allocationByteCount <= MAX_BITMAP_BYTES &&
                !(android.os.Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap())) {
                val retained = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) }
                    catch (_: OutOfMemoryError) { null } // Optional retention must not fail an otherwise usable decode.
                if (retained != null) synchronized(lock) {
                    if (epoch == requestEpoch) {
                        entry?.bitmap?.recycle()
                        entry = Entry(uri, digest, retained)
                    } else retained.recycle()
                }
            }
            bitmap
        }, MoreExecutors.directExecutor())
    }

    private fun snapshot(uri: Uri): ByteArray? {
        if (uri.scheme != "file") return null
        return try {
            val file = File(uri.path ?: return null).canonicalFile
            if (!file.toPath().startsWith(ownedDirectory.canonicalFile.toPath()) || file.length() > MAX_SOURCE_BYTES) return null
            file.inputStream().use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_SOURCE_BYTES) return null
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            }
        } catch (_: java.io.IOException) { null }
          catch (_: SecurityException) { null }
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 1_048_576
        const val MAX_BITMAP_BYTES = 16_777_216
    }
}
