package com.videoeditor.core.media.preview

/** Match the pinned Media3 constant-speed period mapper, including its Float rounding. */
object PreviewPeriodTime {
    @JvmStatic fun adjustedTrimStartUs(sourceStartUs: Long, speed: Float): Long =
        (sourceStartUs / speed).toLong()
}
