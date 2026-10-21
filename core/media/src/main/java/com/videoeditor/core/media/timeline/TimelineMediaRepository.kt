package com.videoeditor.core.media.timeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.videoeditor.core.common.result.Result
import com.videoeditor.core.common.result.getOrNull
import com.videoeditor.core.media.waveform.WaveformExtractor
import com.videoeditor.core.media.waveform.WaveformPeaks
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * Supplies the timeline's heavy media assets — thumbnail strips for video
 * clips and waveform peaks for audio clips — from real source media, with
 * caching and cancellation built in:
 *
 *  - Frames are decoded at downscaled size ([MediaMetadataRetriever] with a
 *    scaling transform is not available pre-API 27 for all formats, so frames
 *    are scaled immediately and the full-resolution bitmap is dropped).
 *  - Strip sizes are bucketed to powers of two, so a zoom gesture recomputes a
 *    strip only when it crosses a bucket, not on every scale change; the
 *    renderer samples the cached strip at whatever density the current zoom
 *    needs.
 *  - Results live in bounded LRU caches keyed by (uri, source range, bucket),
 *    so scrolling back and forth never re-decodes.
 *  - All work runs on [ioDispatcher] and checks for coroutine cancellation
 *    between frames, so an obsolete strip (zoom changed mid-generation) stops
 *    instead of finishing pointlessly.
 */
@Singleton
class TimelineMediaRepository @Inject constructor(
    private val waveformExtractor: WaveformExtractor,
    @com.videoeditor.core.common.di.IoDispatcher
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val bitmapCache = object : LruCache<String, Any>(BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Any): Int = when (value) {
            is Bitmap -> value.byteCount
            is StripHolder -> max(1, value.byteCount)
            else -> 1
        }
    }

    private val waveformCache = object : LruCache<String, WaveformPeaks>(WAVEFORM_CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: WaveformPeaks): Int = max(1, value.size)
    }

    /**
     * The frames of one clip's thumbnail strip, evenly spaced across its
     * source range. Missing entries are null (a frame that failed to decode);
     * the renderer draws a placeholder for those.
     */
    private val stripDecodeCount = java.util.concurrent.atomic.AtomicLong()
    private val waveformDecodeCount = java.util.concurrent.atomic.AtomicLong()

    private fun countDecode(name: String, count: java.util.concurrent.atomic.AtomicLong) {
        val value = count.incrementAndGet()
        if (android.os.Build.VERSION.SDK_INT >= 29) android.os.Trace.setCounter(name, value)
    }

    suspend fun thumbnailStrip(
        context: Context,
        uri: Uri,
        sourceStartMicros: Long,
        sourceEndMicros: Long,
        requestedCount: Int,
        thumbHeightPx: Int,
    ): List<Bitmap?> = withContext(ioDispatcher) {
        val count = bucketize(requestedCount)
        val key = stripKey(uri, sourceStartMicros, sourceEndMicros, count, thumbHeightPx)

        synchronized(bitmapCache) {
            @Suppress("UNCHECKED_CAST")
            (bitmapCache.get(key) as? StripHolder)?.let { return@withContext it.frames }
        }

        countDecode("Timeline.thumbnailDecodeCount", stripDecodeCount)
        val frames = decodeStrip(
            context, uri, sourceStartMicros, sourceEndMicros, count, thumbHeightPx,
        )
        synchronized(bitmapCache) {
            bitmapCache.put(key, StripHolder(frames))
        }
        frames
    }

    /**
     * A single full-clip thumbnail (used while a strip is still decoding and
     * for photo clips). Returns null when no frame is available.
     */
    suspend fun singleThumbnail(
        context: Context,
        uri: Uri,
        sourceTimeMicros: Long,
        thumbHeightPx: Int,
    ): Bitmap? = withContext(ioDispatcher) {
        val key = "single|${uri}|$sourceTimeMicros|$thumbHeightPx"
        synchronized(bitmapCache) {
            (bitmapCache.get(key) as? Bitmap)?.let { return@withContext it }
        }
        val retriever = MediaMetadataRetriever()
        val videoBitmap = try {
            retriever.setDataSource(context, uri)
            scaledFrame(retriever, sourceTimeMicros, thumbHeightPx)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
        // Some providers return no video frame for photos without throwing.
        val bitmap = videoBitmap ?: runCatching {
            com.videoeditor.core.media.decodeImageThumbnail(context, uri, thumbHeightPx * 2)
        }.getOrNull()?.let { scaleToHeight(it, thumbHeightPx) }
        bitmap?.let {
            synchronized(bitmapCache) { bitmapCache.put(key, it) }
        }
        bitmap
    }

    /**
     * Waveform peaks for one audio clip's source range. Fixed bucket count —
     * zoom never invalidates waveforms because the renderer aggregates these
     * peaks into drawn columns.
     */
    suspend fun waveform(
        context: Context,
        uri: Uri,
        sourceStartMicros: Long,
        sourceEndMicros: Long,
    ): WaveformPeaks {
        val key = "wave|${uri}|$sourceStartMicros|$sourceEndMicros"
        synchronized(waveformCache) {
            waveformCache.get(key)?.let { return it }
        }
        val peaks = withContext(ioDispatcher) {
            countDecode("Timeline.waveformDecodeCount", waveformDecodeCount)
            waveformExtractor.extract(
                context, uri, sourceStartMicros, sourceEndMicros, WAVEFORM_BUCKETS,
            ).getOrNull() ?: WaveformPeaks.EMPTY
        }
        synchronized(waveformCache) {
            // Cache empties too, so a source without audio is not re-decoded on
            // every pass over the timeline.
            waveformCache.put(key, peaks)
        }
        return peaks
    }

    /** Cache accounting excludes active UI references; measured separately in stress tests. */
    fun cachedBitmapBytes(): Int = synchronized(bitmapCache) { bitmapCache.size() }

    fun clearCaches() {
        synchronized(bitmapCache) { bitmapCache.evictAll() }
        synchronized(waveformCache) { waveformCache.evictAll() }
    }

    // -- internals --

    private suspend fun decodeStrip(
        context: Context,
        uri: Uri,
        sourceStartMicros: Long,
        sourceEndMicros: Long,
        count: Int,
        thumbHeightPx: Int,
    ): List<Bitmap?> = withContext(ioDispatcher) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val span = sourceEndMicros - sourceStartMicros
            (0 until count).map { index ->
                // Sample at bucket centers so the first and last frames are
                // inside the clip's source range, not on its exact edges.
                val t = sourceStartMicros + span * (2 * index + 1) / (2 * count)
                // Stop promptly when the caller's scope is cancelled (e.g. the
                // zoom changed and this strip is obsolete).
                currentCoroutineContext().ensureActive()
                runCatching {
                    scaledFrame(retriever, t, thumbHeightPx)
                }.getOrNull()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaledFrame(retriever: MediaMetadataRetriever, timeUs: Long, heightPx: Int): Bitmap? {
        val height = heightPx.coerceAtLeast(1)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: height
            val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: height
            val width = (height.toDouble() * sourceWidth / sourceHeight.coerceAtLeast(1)).toInt().coerceIn(1, 2048)
            return retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
                ?.let { scaleToHeight(it, height) }
        }
        return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleToHeight(it, height) }
    }

    /** Scales [source] to [heightPx], recycling the original when it changes. */
    private fun scaleToHeight(source: Bitmap, heightPx: Int): Bitmap {
        if (source.height <= heightPx) return source
        val ratio = heightPx.toFloat() / source.height
        val width = max(1, ceil(source.width * ratio).toInt())
        val scaled = Bitmap.createScaledBitmap(source, width, heightPx, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    /** Rounds a strip count up to a power of two, clamped to a sane range. */
    private fun bucketize(count: Int): Int {
        if (count <= MIN_STRIP_COUNT) return MIN_STRIP_COUNT
        if (count >= MAX_STRIP_COUNT) return MAX_STRIP_COUNT
        return 1 shl ceil(log2(count.toFloat())).toInt()
    }

    private fun stripKey(
        uri: Uri,
        start: Long,
        end: Long,
        count: Int,
        heightPx: Int,
    ): String = "strip|$uri|$start|$end|$count|$heightPx"

    /**
     * One cache entry per strip; the holder keeps a strip's frames together so
     * they are evicted as a unit and their footprint counts against the byte
     * budget.
     */
    private class StripHolder(val frames: List<Bitmap?>) {
        val byteCount: Int get() = frames.sumOf { it?.byteCount ?: 0 }
    }

    private companion object {
        /** ~12 MB of decoded thumbnails; generous for phone timelines. */
        const val BITMAP_CACHE_BYTES = 12 * 1024 * 1024

        /** Up to 16 audio clips' worth of peaks (each ~2000 floats ≈ 8 KB). */
        const val WAVEFORM_CACHE_ENTRIES = 4096

        const val MIN_STRIP_COUNT = 2
        const val MAX_STRIP_COUNT = 32

        /**
         * Peaks per audio clip. Fine enough that a deeply zoomed waveform
         * still shows real signal detail: at 250 ms visible span and 4s of
         * audio, one drawn column of ~4 px covers ~8 buckets.
         */
        const val WAVEFORM_BUCKETS = 2000
    }
}
