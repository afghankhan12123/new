package com.videoeditor.core.media.audio

import com.videoeditor.core.model.AudioLevelWindow
import com.videoeditor.core.model.DuckingSettings
import com.videoeditor.core.model.VolumeEnvelope
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Deterministic voice-activity envelope detector for automatic ducking.
 *
 * This is pure arithmetic over measured window levels, with no Android, Media3
 * or decode dependency, so every rule the ducking contract promises is
 * host-testable: silence handling, threshold gating, attack and release timing,
 * reduction depth, and multi-trigger combination.
 *
 * # Algorithm
 *
 * 1. **Combine triggers by maximum.** When several foreground clips are active
 *    the detector takes the loudest window level at each position rather than
 *    summing them. Summing would request double attenuation exactly where two
 *    voices overlap, which is where the speech is already clearest. Taking the
 *    maximum applies the requested reduction once regardless of how many voices
 *    are speaking.
 * 2. **Gate by threshold.** A combined level below [DuckingSettings.thresholdDb]
 *    counts as no speech and requests no reduction. This is what stops a silent
 *    or muted voiceover from pulling the music down: a muted trigger contributes
 *    silence, silence falls below the gate, and no ducking is requested.
 * 3. **Smooth by attack and release.** A one-pole filter tracks the requested
 *    reduction. It rises with the attack constant, so the music drops quickly
 *    when speech starts, and falls with the release constant, so it returns
 *    gradually and audibly smoothly when speech stops. The constants are
 *    converted from milliseconds to per-window coefficients using the actual
 *    window length, so the smoothing behaves the same at any window size.
 *
 * Because the smoothing runs across the whole curve in one pass, the gain never
 * jumps at a detection boundary: a window that crosses the threshold changes the
 * *target*, and the filter walks toward it at the attack rate.
 *
 * # Gain depth
 *
 * Full duck is `strengthDb` of reduction, applied as a linear multiplier through
 * the same dB-to-linear conversion every other Stage 8 gain stage uses. A
 * strength of 0 dB therefore means ducking is enabled but inaudible, which is
 * the documented neutral end of the control rather than a special case.
 */
object DuckingEnvelopeDetector {

    /**
     * Builds the gain curve for one background clip.
     *
     * @param triggerLevels measured levels per trigger clip, all expressed on the
     *   same timeline window grid. An empty list, or a list whose entries are all
     *   silent, produces a flat unity curve.
     * @param windowMicros window length shared by [triggerLevels] and the output.
     * @param timelineStartMicros project time of the first window.
     */
    fun buildCurve(
        triggerLevels: List<List<AudioLevelWindow>>,
        windowMicros: Long,
        timelineStartMicros: Long,
        settings: DuckingSettings,
    ): DuckingCurve {
        require(windowMicros > 0L) { "Ducking window must be positive" }
        val coerced = settings.coerced()
        val windowCount = triggerLevels.maxOfOrNull { it.size } ?: 0
        if (windowCount == 0) {
            return DuckingCurve(timelineStartMicros, windowMicros, FloatArray(0))
        }

        // 1. Combine by maximum across triggers, window by window.
        val combinedDbfs = FloatArray(windowCount) { AudioLevelWindow.SILENT_LEVEL_DBFS }
        for (levels in triggerLevels) {
            for (index in levels.indices) {
                combinedDbfs[index] = max(combinedDbfs[index], levels[index].levelDbfs)
            }
        }

        // 2 and 3. Gate, then smooth into a gain curve.
        val fullDuckLinear = VolumeEnvelope.gainDbToLinear(-coerced.strengthDb)
        val attackCoefficient = smoothingCoefficient(coerced.attackMillis, windowMicros)
        val releaseCoefficient = smoothingCoefficient(coerced.releaseMillis, windowMicros)
        val gains = FloatArray(windowCount)

        var smoothed = 0f
        for (index in 0 until windowCount) {
            val speaks = combinedDbfs[index] >= coerced.thresholdDb
            val target = if (speaks) 1f else 0f
            // Rise fast, fall slow. Using separate coefficients for the two
            // directions is what makes the music drop promptly on speech onset
            // and return without an audible snap afterwards.
            val coefficient = if (target > smoothed) attackCoefficient else releaseCoefficient
            smoothed += coefficient * (target - smoothed)
            // Map the smoothed activity onto the gain range: full activity gives
            // the requested reduction, no activity gives unity.
            gains[index] = 1f + (fullDuckLinear - 1f) * smoothed
        }

        return DuckingCurve(timelineStartMicros, windowMicros, gains)
    }

    /**
     * Per-window coefficient for a one-pole smoother with the given time
     * constant. Derived from the exponential decay that reaches ~63% after one
     * time constant, so the attack and release behave as real time constants
     * rather than as arbitrary per-window steps.
     */
    internal fun smoothingCoefficient(timeConstantMillis: Int, windowMicros: Long): Float {
        if (timeConstantMillis <= 0) return 1f
        val windowMillis = windowMicros / 1_000.0
        return (1.0 - exp(-windowMillis / timeConstantMillis.toDouble())).toFloat().coerceIn(0f, 1f)
    }

    /**
     * RMS level in dBFS for a window of interleaved PCM16 samples.
     *
     * Returns [AudioLevelWindow.SILENT_LEVEL_DBFS] for an empty window so that
     * "no samples decoded" and "decoded silence" are distinguishable at the
     * curve level even though both fall below any sane threshold.
     */
    fun windowLevelDbfs(samples: ShortArray, sampleCount: Int): Float {
        if (sampleCount <= 0) return AudioLevelWindow.SILENT_LEVEL_DBFS
        var sumOfSquares = 0.0
        for (index in 0 until sampleCount) {
            val normalized = samples[index] / 32768.0
            sumOfSquares += normalized * normalized
        }
        val rms = sqrt(sumOfSquares / sampleCount)
        if (rms <= 0.0) return AudioLevelWindow.SILENT_LEVEL_DBFS
        val dbfs = 20.0 * kotlin.math.log10(rms)
        // Floor at the documented silence marker so a very quiet but real signal
        // is still reported as a finite, comparable level.
        return max(dbfs.toFloat(), AudioLevelWindow.SILENT_LEVEL_DBFS)
    }

    /**
     * Peak amplitude in dBFS over a window of interleaved PCM16 samples. Used to
     * cross-check the RMS measure against an independent statistic.
     */
    fun windowPeakDbfs(samples: ShortArray, sampleCount: Int): Float {
        if (sampleCount <= 0) return AudioLevelWindow.SILENT_LEVEL_DBFS
        var peak = 0
        for (index in 0 until sampleCount) {
            val magnitude = kotlin.math.abs(samples[index].toInt())
            if (magnitude > peak) peak = magnitude
        }
        if (peak == 0) return AudioLevelWindow.SILENT_LEVEL_DBFS
        return max((20.0 * kotlin.math.log10(peak / 32768.0)).toFloat(), AudioLevelWindow.SILENT_LEVEL_DBFS)
    }

    /** Number of PCM16 samples in one analysis window at [sampleRateHz]. */
    fun samplesPerWindow(sampleRateHz: Int, windowMicros: Long): Int =
        ((sampleRateHz.toLong() * windowMicros) / 1_000_000L).toInt().coerceAtLeast(1)

    /**
     * Window length in microseconds, derived from the documented constant so
     * callers cannot accidentally disagree with the detector's own assumption.
     */
    fun windowMicros(): Long = DuckingSettings.ANALYSIS_WINDOW_MILLIS * 1_000L

    /**
     * A bounded, reusable accumulator that reduces decoded PCM to window levels
     * without allocating per sample.
     *
     * Two window lengths are involved and conflating them is a correctness bug:
     *
     * - [sourceWindowMicros] bounds how many **source** samples make up one
     *   window. This is what the sample counter uses.
     * - [gridWindowMicros] is the **timeline** spacing of the emitted labels.
     *
     * For a trigger playing at speed `s`, one timeline grid window covers
     * `s * gridWindowMicros` of source time, so the caller passes
     * `sourceWindowMicros = gridWindowMicros * s`. That makes each emitted window
     * correspond to exactly one timeline grid slot at any speed, instead of
     * drifting for slowed or sped-up triggers. At the common speed of 1 the two
     * lengths are equal and the mapping is exact.
     *
     * Feed it PCM16 interleaved frames in ascending source order; it emits one
     * [AudioLevelWindow] per completed window. The trailing partial window is
     * emitted by [finish] so the curve covers the whole trigger span.
     *
     * Channels are summed into the window's energy, which measures the trigger's
     * total level. A stereo voiceover therefore ducks on its combined loudness
     * rather than on one channel, and mono and stereo triggers gate alike.
     */
    class WindowAccumulator(
        private val gridStartMicros: Long,
        private val sampleRateHz: Int,
        channelCount: Int,
        private val sourceWindowMicros: Long,
        private val gridWindowMicros: Long = sourceWindowMicros,
    ) {
        private val channels = channelCount.coerceAtLeast(1)
        private val windowSamples = samplesPerWindow(sampleRateHz, sourceWindowMicros) * channels
        private val levels = ArrayList<AudioLevelWindow>()
        private var sumOfSquares = 0.0
        private var samplesInWindow = 0
        private var windowIndex = 0

        val windows: List<AudioLevelWindow> get() = levels

        /** Consumes interleaved PCM16 samples and emits completed windows. */
        fun add(samples: ShortArray, sampleCount: Int) {
            var consumed = 0
            while (consumed < sampleCount) {
                val normalized = samples[consumed] / 32768.0
                sumOfSquares += normalized * normalized
                samplesInWindow++
                consumed++
                if (samplesInWindow >= windowSamples) emitWindow()
            }
        }

        /** Emits the trailing partial window, if it holds any samples. */
        fun finish(): List<AudioLevelWindow> {
            if (samplesInWindow > 0) emitWindow()
            return levels
        }

        private fun emitWindow() {
            val rms = sqrt(sumOfSquares / samplesInWindow)
            val dbfs = if (rms <= 0.0) {
                AudioLevelWindow.SILENT_LEVEL_DBFS
            } else {
                max(
                    (20.0 * kotlin.math.log10(rms)).toFloat(),
                    AudioLevelWindow.SILENT_LEVEL_DBFS,
                )
            }
            levels += AudioLevelWindow(gridStartMicros + windowIndex * gridWindowMicros, dbfs)
            sumOfSquares = 0.0
            samplesInWindow = 0
            windowIndex++
        }
    }

    /**
     * Re-expresses a level list measured over one trigger's own span onto the
     * shared window grid, so triggers of different lengths and start positions
     * can be combined window by window.
     *
     * Windows before a trigger's first measurement are treated as silence, which
     * is the documented policy: a clip that has not started cannot request
     * ducking.
     */
    fun alignToGrid(
        levels: List<AudioLevelWindow>,
        gridStartMicros: Long,
        gridWindowCount: Int,
        windowMicros: Long,
    ): List<AudioLevelWindow> {
        if (gridWindowCount <= 0) return emptyList()
        val byStart = HashMap<Long, Float>(levels.size)
        for (window in levels) byStart[window.timelineStartMicros] = window.levelDbfs
        return List(gridWindowCount) { index ->
            val windowStart = gridStartMicros + index * windowMicros
            AudioLevelWindow(
                windowStart,
                byStart[windowStart] ?: AudioLevelWindow.SILENT_LEVEL_DBFS,
            )
        }
    }

    /**
     * Bounds the analysis so a pathological trigger span cannot allocate an
     * unbounded curve. Beyond this many windows the analysis is clamped and the
     * excess is covered by the curve's final value, which the gain processor
     * already holds past the end of its array.
     */
    const val MAX_WINDOWS = 60_000

    /** Window count for a span, bounded by [MAX_WINDOWS]. */
    fun windowCountFor(spanMicros: Long, windowMicros: Long): Int =
        ((spanMicros + windowMicros - 1) / windowMicros).toInt().coerceIn(0, MAX_WINDOWS)

    /** Linear gain for a requested reduction in dB, via the shared conversion. */
    fun linearForReductionDb(reductionDb: Float): Float =
        VolumeEnvelope.gainDbToLinear(-reductionDb.coerceIn(0f, DuckingSettings.MAX_STRENGTH_DB))

    /**
     * Raised-cosine weight used when a window straddles a sample boundary, so a
     * trigger whose span does not align to the grid cannot produce a one-window
     * gain step at its edges.
     */
    fun boundaryWeight(offsetMicros: Long, windowMicros: Long): Float {
        if (windowMicros <= 0L) return 1f
        val u = (offsetMicros.toFloat() / windowMicros.toFloat()).coerceIn(0f, 1f)
        return VolumeEnvelope.smoothstep(u)
    }

    /** Convenience: 10^x for the few places that need a raw power. */
    internal fun pow10(exponent: Float): Float = 10f.pow(exponent)
}
