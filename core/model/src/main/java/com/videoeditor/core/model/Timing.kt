package com.videoeditor.core.model

/**
 * All timeline timing is expressed in integer microseconds to avoid cumulative
 * floating-point drift. Conversions to/from seconds and frame positions are
 * centralized here so the rest of the app never does ad-hoc time math.
 */
object Timing {
    const val MICROS_PER_SECOND: Long = 1_000_000L
    const val MICROS_PER_MILLISECOND: Long = 1_000L

    fun secondsToMicros(seconds: Double): Long = (seconds * MICROS_PER_SECOND).toLong()

    fun microsToSeconds(micros: Long): Double = micros.toDouble() / MICROS_PER_SECOND

    fun millisToMicros(millis: Long): Long = millis * MICROS_PER_MILLISECOND

    fun microsToMillis(micros: Long): Long = micros / MICROS_PER_MILLISECOND

    /**
     * Converts a time in microseconds to an output frame index for the given
     * frame rate. Uses rounding so that a time exactly on a frame boundary maps
     * to that frame rather than drifting to the previous one.
     */
    fun microsToFrame(micros: Long, frameRate: FrameRate): Long =
        (micros.toDouble() * frameRate.fps / MICROS_PER_SECOND).toLong()

    /** Converts an output frame index back to microseconds for the given frame rate. */
    fun frameToMicros(frame: Long, frameRate: FrameRate): Long =
        frame * MICROS_PER_SECOND / frameRate.fps
}
