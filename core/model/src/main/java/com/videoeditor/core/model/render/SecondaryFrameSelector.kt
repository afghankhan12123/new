package com.videoeditor.core.model.render

/** Select within the visible source interval; a neighboring transparent pad is never its picture. */
object SecondaryFrameSelector {
    const val DEFAULT_TOLERANCE_US = 34_000L

    @JvmStatic
    @JvmOverloads
    fun select(
        timesUs: LongArray,
        targetUs: Long,
        startUs: Long,
        endUs: Long,
        intervalComplete: Boolean,
        toleranceUs: Long = DEFAULT_TOLERANCE_US,
    ): Int {
        var selected = -1
        var distance = Long.MAX_VALUE
        var bracketed = intervalComplete
        for (index in timesUs.indices) {
            val time = timesUs[index]
            if (time >= endUs) { bracketed = true; break }
            if (time < startUs) continue
            val delta = kotlin.math.abs(time - targetUs)
            if (delta < distance) { selected = index; distance = delta }
            if (time >= targetUs - 1L) { bracketed = true; break }
        }
        if (!bracketed && selected != -1 && (targetUs - timesUs[selected]) <= toleranceUs) {
            bracketed = true
        }
        // A last picture may cover the remainder of its *confirmed* source interval.
        // Missing inputs are never replaced by padding or an indefinitely cached picture.
        return if (bracketed) selected else -1
    }
}
