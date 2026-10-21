package com.videoeditor.editing.timeline

/**
 * Chooses the time ruler's division interval for the current zoom and formats
 * tick labels. Intervals come from a fixed ladder of "nice" steps (1/2/5 ×
 * powers of ten, in seconds) so the ruler never shows arbitrary fractions, and
 * the chosen step is the smallest one whose spacing is at least
 * [minSpacingPx] — labels stay readable at every zoom level.
 */
object RulerScale {

    /** Nice ruler steps in microseconds, ascending: 10ms … 10min. */
    private val STEPS_MICROS = longArrayOf(
        10_000L,        // 10 ms
        20_000L,
        50_000L,
        100_000L,       // 100 ms
        200_000L,
        500_000L,
        1_000_000L,     // 1 s
        2_000_000L,
        5_000_000L,
        10_000_000L,    // 10 s
        15_000_000L,
        30_000_000L,
        60_000_000L,    // 1 min
        120_000_000L,
        300_000_000L,   // 5 min
        600_000_000L,   // 10 min
    )

    /**
     * The ruler interval in microseconds for the given zoom: the smallest nice
     * step at least [minSpacingPx] wide on screen. Falls back to the largest
     * step when even it does not fit (astronomical zoom-out), which cannot
     * happen within the app's zoom limits.
     */
    fun intervalMicros(pixelsPerMicro: Float, minSpacingPx: Float): Long {
        require(pixelsPerMicro > 0f) { "pixelsPerMicro must be positive" }
        require(minSpacingPx > 0f) { "minSpacingPx must be positive" }
        return STEPS_MICROS.firstOrNull { it * pixelsPerMicro >= minSpacingPx }
            ?: STEPS_MICROS.last()
    }

    /** Whether [timeMicros] falls on a labeled major tick for [intervalMicros]. */
    fun isMajorTick(timeMicros: Long, intervalMicros: Long): Boolean =
        intervalMicros > 0L && timeMicros % intervalMicros == 0L

    /**
     * Formats a ruler label: `m:ss` from one minute up, whole seconds below,
     * and fractional seconds for sub-second intervals — matching the precision
     * the ruler is actually showing.
     */
    fun formatTickLabel(timeMicros: Long, intervalMicros: Long): String {
        val totalSeconds = timeMicros / 1_000_000L
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (intervalMicros >= 1_000_000L) {
            "%d:%02d".format(minutes, seconds)
        } else {
            val fractional = timeMicros % 1_000_000L
            val decimals = when {
                intervalMicros >= 100_000L -> 1
                intervalMicros >= 10_000L -> 2
                else -> 3
            }
            "%d:%02d.%0${decimals}d".format(
                minutes,
                seconds,
                fractional / divisorFor(decimals),
            )
        }
    }

    /**
     * Formats a duration/position for the transport display as `mm:ss`, the
     * convention the editor already uses.
     */
    fun formatTimecode(timeMicros: Long): String {
        val clamped = if (timeMicros < 0L) 0L else timeMicros
        val totalSeconds = clamped / 1_000_000L
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun divisorFor(decimals: Int): Long = when (decimals) {
        1 -> 100_000L
        2 -> 10_000L
        else -> 1_000L
    }
}
