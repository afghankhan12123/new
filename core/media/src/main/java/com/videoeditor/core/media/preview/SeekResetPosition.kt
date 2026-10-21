package com.videoeditor.core.media.preview

/** Resolve a child reset only inside the requested source period. The pinned speed mapper
 * round-trips through Float, so sub-millisecond rounding is not an obsolete seek.
 */
object SeekResetPosition {
    @JvmStatic
    fun matches(requestedUs: Long, actualUs: Long, periodStartUs: Long, periodEndUs: Long): Boolean =
        requestedUs >= 0L && actualUs >= -1_000L && requestedUs >= periodStartUs && requestedUs < periodEndUs &&
            kotlin.math.abs(requestedUs - actualUs) <= 1_000L
}
