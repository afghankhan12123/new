package com.videoeditor.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stage 8 audio-effect model types.
 *
 * These are pure, serializable value types with no Compose or Media3 dependency.
 * Every field is defaulted so a Stage 6/7 document deserializes to a fully
 * neutral effect state: no audio effect activates itself on an existing project.
 *
 * # Time domains
 *
 * Two time domains appear in audio editing and mixing them is a correctness bug:
 *
 * - **Source domain** — microseconds within the source media file, the same
 *   domain as [AudioClip.sourceStartMicros] and [AudioClip.sourceEndMicros].
 * - **Timeline domain** — project microseconds, the domain of
 *   [AudioClip.timelineStartMicros].
 *
 * Volume automation points are stored in the **source domain**. This is what
 * makes the automation non-destructive: moving a clip changes only its timeline
 * position, trimming narrows the audible window without moving any point, and
 * duplicating copies the envelope verbatim. All three leave the curve attached
 * to the same audio content.
 *
 * Ducking gain curves are computed in the **timeline domain**, because ducking
 * is defined by where tracks overlap on the project timeline, not by positions
 * inside one source file.
 *
 * # Gain units
 *
 * Automation and EQ gains are stored in **decibels** and interpolated in
 * decibels. Interpolating in dB gives a perceptually even ramp; interpolating
 * linear amplitude between the same two points audibly bulges in the middle.
 * Conversion to a linear multiplier is always `10f.pow(db / 20f)` and happens at
 * exactly one place, [gainDbToLinear], so the two interpretations cannot be
 * mixed accidentally.
 *
 * Fade curves are the exception: they are normalized shapes on 0..1 producing a
 * **linear amplitude** factor, matching the pre-existing linear fades they
 * extend. They are never expressed in dB.
 */

/**
 * How gain travels from one [VolumePoint] to the next.
 *
 * The type is attached to the *earlier* point of a segment, the convention used
 * by professional envelope editors: a point describes the curve that leaves it.
 */
@Serializable
enum class VolumeInterpolation {
    /** Straight ramp in dB between the two points. The default. */
    Linear,

    /** Holds the earlier point's gain, then steps at the next point. This is the
     *  only mode that introduces a deliberate gain discontinuity. */
    Hold,

    /** Raised-cosine (smoothstep) ramp in dB. C1-continuous at both ends, so
     *  chained segments have no slope break and no click. */
    Smooth,
}

/**
 * One editable gain point on a clip's volume envelope.
 *
 * @property id Stable identity so UI selection survives drags and reordering.
 *   Supplied by the command layer; the model never invents identifiers.
 * @property sourcePositionMicros Position in the source file's own timeline,
 *   in microseconds. Not relative to the clip's trim start.
 * @property gainDb Gain in decibels, within [VolumeEnvelope.GAIN_RANGE_DB].
 * @property interpolation Curve used from this point toward the next one.
 */
@Serializable
data class VolumePoint(
    val id: String,
    val sourcePositionMicros: Long,
    val gainDb: Float,
    val interpolation: VolumeInterpolation = VolumeInterpolation.Linear,
)

/**
 * An ordered set of [VolumePoint]s forming one clip's gain automation curve.
 *
 * Points are sorted eagerly in ascending source-position order (then by id for
 * ties) as soon as the envelope is constructed. The automation curve is edited
 * interactively, so it is bounded by user input — far too small for eager
 * sorting to cost anything — and a canonical order is what makes the DSP
 * evaluator and the on-canvas curve agree without each re-sorting the list.
 *
 * The empty envelope is the neutral state: it evaluates to 0 dB everywhere and
 * the DSP layer bypasses it entirely, leaving the accepted Stage 7 audio path
 * untouched.
 *
 * # Evaluation rules
 *
 * For a source position `p`, in ascending point order `p0 < p1 < ... < pn`:
 *
 * - No points: 0 dB.
 * - `p <= p0`: `p0.gainDb`. The curve holds its first value as a lead-in, so a
 *   clip never starts at unity and jumps when the first point begins.
 * - `p >= pn`: `pn.gainDb`. The curve holds its last value as a tail.
 * - `pi <= p < pi+1`: interpolate from `pi` to `pi+1` using `pi.interpolation`.
 *
 * Points at an identical position are resolved deterministically by ascending
 * [VolumePoint.id]; the later-ordered point's value wins at that position. The
 * command layer prevents creating duplicates in normal editing.
 */
@Serializable
data class VolumeEnvelope(val points: List<VolumePoint> = emptyList()) {

    /**
     * Points in deterministic ascending order: by source position, then by id.
     * Kept as the primary field so the DSP evaluator and the UI curve read the
     * same ordering.
     */
    private val ordered: List<VolumePoint> =
        if (points.size < 2) points
        else points.sortedWith(compareBy({ it.sourcePositionMicros }, { it.id }))

    val isEmpty: Boolean get() = points.isEmpty()

    /** This envelope with points in canonical order. Idempotent. */
    fun normalized(): VolumeEnvelope =
        if (ordered === points || points.size < 2) this else VolumeEnvelope(ordered)

    /** Gain in dB at a source position, per the evaluation rules above. */
    fun gainDbAt(sourcePositionMicros: Long): Float {
        val pts = ordered
        if (pts.isEmpty()) return 0f
        val first = pts.first()
        if (sourcePositionMicros <= first.sourcePositionMicros) return first.gainDb
        val last = pts.last()
        if (sourcePositionMicros >= last.sourcePositionMicros) return last.gainDb

        var low = 0
        var high = pts.lastIndex
        while (high - low > 1) {
            val mid = (low + high) ushr 1
            if (pts[mid].sourcePositionMicros <= sourcePositionMicros) low = mid else high = mid
        }
        val a = pts[low]
        val b = pts[high]
        val span = b.sourcePositionMicros - a.sourcePositionMicros
        if (span <= 0L) return b.gainDb
        val u = (sourcePositionMicros - a.sourcePositionMicros).toFloat() / span.toFloat()
        return when (a.interpolation) {
            VolumeInterpolation.Linear -> a.gainDb + (b.gainDb - a.gainDb) * u
            VolumeInterpolation.Hold -> a.gainDb
            VolumeInterpolation.Smooth ->
                a.gainDb + (b.gainDb - a.gainDb) * smoothstep(u.coerceIn(0f, 1f))
        }
    }

    /** Linear gain multiplier at a source position. */
    fun gainAt(sourcePositionMicros: Long): Float = gainDbToLinear(gainDbAt(sourcePositionMicros))

    /** This envelope with a point replaced by position, or unchanged if absent. */
    fun withPointAt(sourcePositionMicros: Long, gainDb: Float): VolumeEnvelope =
        VolumeEnvelope(ordered.map {
            if (it.sourcePositionMicros == sourcePositionMicros) it.copy(gainDb = gainDb.coerceGain()) else it
        })

    /** This envelope with a point replaced by id, or unchanged if absent. */
    fun withPoint(updated: VolumePoint): VolumeEnvelope =
        VolumeEnvelope(ordered.map { if (it.id == updated.id) updated else it })

    /** The point with the given id, or null. */
    fun pointById(pointId: String): VolumePoint? = ordered.firstOrNull { it.id == pointId }

    fun withoutPoint(pointId: String): VolumeEnvelope =
        VolumeEnvelope(ordered.filterNot { it.id == pointId })

    /**
     * Splits this envelope at a source position into the envelopes of the two
     * resulting clips.
     *
     * An empty envelope splits into two empty envelopes: the neutral state must
     * stay neutral, and inserting boundary points into a clip that has no
     * automation would activate the DSP stage on both halves for no audible
     * reason.
     *
     * Otherwise the authored gain at the split boundary is preserved exactly:
     * each half receives a boundary point carrying [gainDbAt] evaluated at
     * [sourcePositionMicros]. Without that point the left half would hold its
     * last authored value across the remainder of a ramp, and the right half
     * would lead in from its own first point, so a split in the middle of a ramp
     * would audibly step. With it, evaluating either half at the boundary yields
     * the same dB value the unsplit envelope had there.
     *
     * Both boundary points inherit the interpolation of the segment crossing the
     * boundary, so a Hold segment split mid-hold keeps holding in the right half
     * instead of silently becoming a ramp.
     *
     * A point authored exactly at the boundary is replaced by the boundary point
     * of identical value, so no gain is lost; only that point's id changes.
     *
     * @param leftBoundaryId identity for the left half's new boundary point.
     * @param rightBoundaryId identity for the right half's new boundary point.
     */
    fun splitAt(
        sourcePositionMicros: Long,
        leftBoundaryId: String,
        rightBoundaryId: String,
    ): Pair<VolumeEnvelope, VolumeEnvelope> {
        if (points.isEmpty()) return this to this
        val boundaryGain = gainDbAt(sourcePositionMicros)
        val crossing = ordered.lastOrNull { it.sourcePositionMicros <= sourcePositionMicros }
            ?: ordered.first()
        val left = ordered.filter { it.sourcePositionMicros < sourcePositionMicros } +
            VolumePoint(leftBoundaryId, sourcePositionMicros, boundaryGain, crossing.interpolation)
        val right = listOf(
            VolumePoint(rightBoundaryId, sourcePositionMicros, boundaryGain, crossing.interpolation),
        ) + ordered.filter { it.sourcePositionMicros > sourcePositionMicros }
        return VolumeEnvelope(left) to VolumeEnvelope(right)
    }

    /**
     * This envelope with fresh point identities from [newId]. Used when a clip
     * is duplicated so the copy's points cannot collide with the original's.
     */
    fun remapPointIds(newId: () -> String): VolumeEnvelope =
        if (points.isEmpty()) this else VolumeEnvelope(ordered.map { it.copy(id = newId()) })

    companion object {
        /** Inaudible floor. Exactly -60 dB, never -Infinity, so it serializes. */
        const val MIN_GAIN_DB = -60f

        /** Matches the pre-existing maximum base volume of 4x (+12.04 dB). */
        const val MAX_GAIN_DB = 12f

        val GAIN_RANGE_DB: ClosedFloatingPointRange<Float> = MIN_GAIN_DB..MAX_GAIN_DB

        /**
         * The single dB-to-linear conversion used by every Stage 8 gain stage.
         * Clamped so a malformed document cannot produce NaN or Infinity.
         */
        fun gainDbToLinear(gainDb: Float): Float {
            val db = if (gainDb.isNaN()) 0f else gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            return 10f.pow(db / 20f)
        }

        fun Float.coerceGain(): Float = coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

        /** Raised cosine on 0..1. Exact at both endpoints, zero slope at both. */
        fun smoothstep(u: Float): Float {
            val x = u.coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }
    }
}

/**
 * Fade curve shapes. Each maps a normalized progress `u` on 0..1 to a **linear
 * amplitude** factor on 0..1.
 *
 * Every curve is exact at its endpoints — `f(0) == 0f` and `f(1) == 1f` — and
 * monotonically non-decreasing. Exact endpoints are what prevent the click that
 * an approximating curve introduces at the fade boundary.
 *
 * For a fade-in, `u` is elapsed progress through the fade. For a fade-out, `u`
 * is the *remaining* fraction of the fade, so gain travels 1 -> 0 along the same
 * shape. That makes "Ease In" on a fade-out hold the level high and then drop
 * quickly, which is the natural mirror of the fade-in and needs no second set of
 * curve definitions.
 */
@Serializable
enum class FadeCurve(val label: String) {
    /** Straight ramp. The pre-existing Stage 7 behaviour and the default. */
    Linear("Linear"),

    /** Slow start, fast finish: `u^2`. */
    EaseIn("Ease In"),

    /** Fast start, slow finish: `1 - (1 - u)^2`. */
    EaseOut("Ease Out"),

    /** Symmetric S-curve: raised cosine `u^2 (3 - 2u)`. */
    Smooth("Smooth"),

    /**
     * Perceptually even exponential-style ramp with an exact zero endpoint:
     *
     * ```
     * f(u) = (EXPONENTIAL_K^(u) - 1) / (EXPONENTIAL_K - 1)
     * ```
     *
     * A true exponential fade cannot start at amplitude zero, since that is
     * -Infinity dB. This normalized form reaches exactly 0 at `u = 0` and exactly
     * 1 at `u = 1` while keeping the exponential character in between, so it is
     * safe as a fade endpoint by construction rather than by clamping.
     */
    Exponential("Exponential");

    /** Linear amplitude factor for normalized progress [u] on 0..1. */
    fun amplitudeAt(u: Float): Float {
        val x = if (u.isNaN()) 0f else u.coerceIn(0f, 1f)
        return when (this) {
            Linear -> x
            EaseIn -> x * x
            EaseOut -> 1f - (1f - x) * (1f - x)
            Smooth -> VolumeEnvelope.smoothstep(x)
            Exponential -> {
                val denominator = EXPONENTIAL_K - 1f
                (EXPONENTIAL_K.pow(x) - 1f) / denominator
            }
        }.coerceIn(0f, 1f)
    }

    companion object {
        /** Curvature of [Exponential]. 3 gives roughly a 9.5 dB initial slope
         *  ratio against linear, audible as exponential without being abrupt. */
        const val EXPONENTIAL_K = 3f
    }
}

/**
 * Three-band equalizer state for one clip.
 *
 * Bands are RBJ Audio EQ Cookbook filters evaluated at the clip's actual sample
 * rate: a low shelf for bass, a peaking filter for mid, a high shelf for treble.
 * Shelf/peak topology, centre frequencies, Q and gain range are fixed here so
 * preview, export and every test measure the same response.
 *
 * The identity state — disabled, or enabled with all bands at 0 dB — bypasses
 * the DSP stage completely rather than running samples through unity filters, so
 * enabling EQ is the only thing that can change an existing clip's samples.
 * A zero-gain band's coefficients form an exact algebraic pass-through, and
 * [BiquadCoefficients.isUnity] recognizes that, so the bypass is real.
 */
@Serializable
data class EqualizerSettings(
    val enabled: Boolean = false,
    val bassGainDb: Float = 0f,
    val midGainDb: Float = 0f,
    val trebleGainDb: Float = 0f,
) {
    /** True when this state must reach the DSP path at all. */
    val isActive: Boolean
        get() = enabled && (bassGainDb != 0f || midGainDb != 0f || trebleGainDb != 0f)

    fun coerced(): EqualizerSettings = copy(
        bassGainDb = bassGainDb.coerceBandGain(),
        midGainDb = midGainDb.coerceBandGain(),
        trebleGainDb = trebleGainDb.coerceBandGain(),
    )

    companion object {
        const val MIN_BAND_GAIN_DB = -12f
        const val MAX_BAND_GAIN_DB = 12f

        /** Low-shelf centre. Below the voice fundamental, above subsonic rumble. */
        const val BASS_CENTRE_HZ = 100f

        /** Peaking centre, in the presence region of speech and most instruments. */
        const val MID_CENTRE_HZ = 1_000f

        /** High-shelf centre, above the voice body but below the Nyquist limit of
         *  a 44.1 kHz source, where the shelf would alias. */
        const val TREBLE_CENTRE_HZ = 8_000f

        /** Shelf slope. 0.707 is Butterworth: maximally flat, no resonant peak. */
        const val SHELF_SLOPE = 0.707f

        /** Peaking bandwidth. Q = 1 is roughly one octave, wide enough to sound
         *  musical and narrow enough that bass and mid do not overlap. */
        const val MID_Q = 1.0f

        fun Float.coerceBandGain(): Float = coerceIn(MIN_BAND_GAIN_DB, MAX_BAND_GAIN_DB)
    }
}

/** Named, fixed EQ band settings. Values are plain dB triples so every preset is
 *  reproducible from this source alone, with no external or trademarked asset. */
@Serializable
enum class EqualizerPreset(val label: String, val bassDb: Float, val midDb: Float, val trebleDb: Float) {
    Flat("Flat", 0f, 0f, 0f),
    Voice("Voice", -3f, 2f, 4f),
    BassBoost("Bass Boost", 6f, 0f, -1f),
    Bright("Bright", -1f, 1f, 6f),
    Warm("Warm", 4f, 1f, -3f);

    fun settings(enabled: Boolean = true) = EqualizerSettings(
        enabled = enabled,
        bassGainDb = bassDb,
        midGainDb = midDb,
        trebleGainDb = trebleDb,
    )
}

/**
 * Stereo placement for one clip.
 *
 * `pan` runs -1 (full left) through 0 (centre) to +1 (full right).
 *
 * The centre position is exact unity on both channels, so a clip left at centre
 * is bit-identical to the same clip with no balance stage at all. That is the
 * property that keeps existing projects unchanged: balance is only applied when
 * `pan != 0f`, and the DSP stage reports itself inactive otherwise.
 *
 * For stereo material this is a **balance** control, attenuating the opposite
 * channel on a constant-power curve; the channels keep their own identity and
 * are never swapped or summed. For mono material it is a true **pan**, routing
 * the single channel across a stereo output. Both laws are defined in the DSP
 * layer, which is the only place that knows the input channel count.
 */
@Serializable
data class AudioBalance(val pan: Float = 0f) {
    val isIdentity: Boolean get() = pan == 0f

    fun coerced(): AudioBalance = AudioBalance(
        if (pan.isNaN()) 0f else pan.coerceIn(MIN_PAN, MAX_PAN),
    )

    companion object {
        const val MIN_PAN = -1f
        const val MAX_PAN = 1f
        val CENTER = AudioBalance(0f)
        val LEFT = AudioBalance(-1f)
        val RIGHT = AudioBalance(1f)
    }
}

/**
 * Automatic ducking configuration attached to a background clip.
 *
 * Ducking is deterministic and entirely local: an envelope detector measures the
 * trigger clips' levels in fixed windows and derives a gain curve. There is no
 * speech recognition, no voice identification and no network access.
 *
 * # Contract
 *
 * - **Trigger selection** is explicit. [triggerClipIds] names the foreground
 *   clips whose levels drive the detector; nothing is inferred from track order.
 * - **Muted triggers never duck.** A muted or silent voiceover must not pull the
 *   music down, so muted clips are excluded from analysis rather than measured
 *   as silence.
 * - **Multiple triggers combine by maximum activity**, not by summing. When two
 *   voices overlap, the deeper of the two requested attenuations applies once.
 *   Summing would double-attenuate exactly where the speech is clearest.
 * - **Attenuation is applied once**, at the target clip, by a single DSP stage.
 *
 * A missing or undecodable trigger is reported as an analysis failure state by
 * the UI. The documented policy is then to apply **no** attenuation, never an
 * arbitrary reduction.
 */
@Serializable
data class DuckingSettings(
    val enabled: Boolean = false,
    val triggerClipIds: List<String> = emptyList(),
    /** Requested reduction at full duck, expressed as a positive depth in dB. */
    val strengthDb: Float = DEFAULT_STRENGTH_DB,
    /** Detector gate in dBFS. Windows below this level count as no speech. */
    val thresholdDb: Float = DEFAULT_THRESHOLD_DB,
    val attackMillis: Int = DEFAULT_ATTACK_MILLIS,
    val releaseMillis: Int = DEFAULT_RELEASE_MILLIS,
) {
    val isActive: Boolean get() = enabled && triggerClipIds.isNotEmpty()

    fun coerced(): DuckingSettings = copy(
        strengthDb = strengthDb.coerceIn(MIN_STRENGTH_DB, MAX_STRENGTH_DB),
        thresholdDb = thresholdDb.coerceIn(MIN_THRESHOLD_DB, MAX_THRESHOLD_DB),
        attackMillis = attackMillis.coerceIn(MIN_TIME_CONSTANT_MILLIS, MAX_TIME_CONSTANT_MILLIS),
        releaseMillis = releaseMillis.coerceIn(MIN_TIME_CONSTANT_MILLIS, MAX_TIME_CONSTANT_MILLIS),
        triggerClipIds = triggerClipIds.distinct(),
    )

    /**
     * Parameters that change the computed gain curve. Any change invalidates a
     * cached analysis, because the cache is keyed on exactly this set plus the
     * trigger clips' source identity and trim range.
     */
    fun analysisKey(): String = listOf(
        thresholdDb, strengthDb, attackMillis, releaseMillis,
        triggerClipIds.sorted().joinToString(","),
    ).joinToString("|")

    companion object {
        const val DEFAULT_STRENGTH_DB = 14f
        const val MIN_STRENGTH_DB = 0f
        const val MAX_STRENGTH_DB = 30f

        const val DEFAULT_THRESHOLD_DB = -38f
        const val MIN_THRESHOLD_DB = -70f
        const val MAX_THRESHOLD_DB = -10f

        const val DEFAULT_ATTACK_MILLIS = 40
        const val DEFAULT_RELEASE_MILLIS = 380
        const val MIN_TIME_CONSTANT_MILLIS = 5
        const val MAX_TIME_CONSTANT_MILLIS = 2_000

        /** Detector window length. 20 ms resolves syllable onsets while staying
         *  long enough to average over at least one cycle of an 80 Hz voice
         *  fundamental. */
        const val ANALYSIS_WINDOW_MILLIS = 20
    }
}

/**
 * One window's measured level from the ducking detector.
 *
 * @property timelineStartMicros Window start in project time.
 * @property levelDbfs Measured RMS in dBFS, or [SILENT_LEVEL_DBFS] when the
 *   window held no samples at all.
 */
@Serializable
data class AudioLevelWindow(
    val timelineStartMicros: Long,
    val levelDbfs: Float,
) {
    companion object {
        /** Reported for a window with no samples. Distinguished from measured
         *  silence so an undecodable trigger is not mistaken for a quiet one. */
        const val SILENT_LEVEL_DBFS = -120f
    }
}

/**
 * Per-band biquad coefficients for one sample rate, derived from the RBJ Audio
 * EQ Cookbook. Held as a value type so the DSP layer, host tests and independent
 * reference analysis all compute coefficients from one definition.
 *
 * Coefficients are normalized by `a0`, giving the Direct Form II Transposed
 * recurrence
 *
 * ```
 * y[n] = b0 x[n] + b1 x[n-1] + b2 x[n-2] - a1 y[n-1] - a2 y[n-2]
 * ```
 *
 * with `a0 == 1f`.
 */
data class BiquadCoefficients(
    val b0: Float,
    val b1: Float,
    val b2: Float,
    val a1: Float,
    val a2: Float,
) {
    /**
     * True when the filter is an exact algebraic no-op, so the DSP layer can
     * bypass it without running samples through a unity filter.
     *
     * H(z) == 1 identically exactly when the numerator equals the denominator:
     * `b0 == 1 && b1 == a1 && b2 == a2`. That holds for the trivial identity
     * (all-zero a1/a2) and also for a zero-gain shelf or peak, which the RBJ
     * formulas produce as a pass-through (a == 1 makes every b equal its a
     * counterpart). Checking the pass-through equality rather than literal zeros
     * is what lets a zero-gain band be recognized as a no-op and skipped.
     */
    val isUnity: Boolean
        get() = b0 == 1f && b1 == a1 && b2 == a2

    /**
     * True when both poles lie strictly inside the unit circle, the condition
     * for a bounded output. Computed from the polynomial roots rather than from
     * the coefficient magnitudes, so it holds for every topology used here.
     */
    val isStable: Boolean
        get() {
            // z^2 + a1 z + a2 = 0. |root| < 1 for both roots iff |a2| < 1 and
            // |a1| < 1 + a2 (the Jury stability criterion for a second-order
            // polynomial with a0 == 1).
            return abs(a2) < 1f && abs(a1) < 1f + a2
        }

    /**
     * Steady-state magnitude response at [frequencyHz] for [sampleRateHz], in dB.
     * Evaluates H(e^jw) directly, which is the definition the verification
     * measurements are checked against.
     */
    fun magnitudeDbAt(frequencyHz: Float, sampleRateHz: Int): Float {
        val w = 2.0 * Math.PI * frequencyHz.toDouble() / sampleRateHz.toDouble()
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)
        val numReal = b0.toDouble() + b1.toDouble() * cosW + b2.toDouble() * cos2W
        val numImag = -(b1.toDouble() * sinW + b2.toDouble() * sin2W)
        val denReal = 1.0 + a1.toDouble() * cosW + a2.toDouble() * cos2W
        val denImag = -(a1.toDouble() * sinW + a2.toDouble() * sin2W)
        val numMag = sqrt(numReal * numReal + numImag * numImag)
        val denMag = sqrt(denReal * denReal + denImag * denImag)
        if (denMag == 0.0) return -120f
        val magnitude = numMag / denMag
        if (magnitude <= 0.0) return -120f
        return (20.0 * log10(magnitude)).toFloat()
    }

    companion object {
        /**
         * Low shelf. Boosts everything below [centreHz] by [gainDb], flat above.
         */
        fun lowShelf(centreHz: Float, gainDb: Float, sampleRateHz: Int, slope: Float = EqualizerSettings.SHELF_SLOPE): BiquadCoefficients {
            val a = amplitudeFor(gainDb)
            val w0 = 2.0 * Math.PI * centreHz.toDouble() / sampleRateHz.toDouble()
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / 2.0 * sqrtSlope(slope, a)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            return normalize(
                b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha),
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0),
                b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha),
                a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha,
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0),
                a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha,
            )
        }

        /**
         * High shelf. Boosts everything above [centreHz] by [gainDb], flat below.
         */
        fun highShelf(centreHz: Float, gainDb: Float, sampleRateHz: Int, slope: Float = EqualizerSettings.SHELF_SLOPE): BiquadCoefficients {
            val a = amplitudeFor(gainDb)
            val w0 = 2.0 * Math.PI * centreHz.toDouble() / sampleRateHz.toDouble()
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / 2.0 * sqrtSlope(slope, a)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            return normalize(
                b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha),
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0),
                b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha),
                a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha,
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0),
                a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha,
            )
        }

        /**
         * Peaking EQ. Boosts or cuts a band centred on [centreHz] with
         * bandwidth set by [q], leaving the extremes untouched.
         */
        fun peaking(centreHz: Float, gainDb: Float, sampleRateHz: Int, q: Float = EqualizerSettings.MID_Q): BiquadCoefficients {
            val a = amplitudeFor(gainDb)
            val w0 = 2.0 * Math.PI * centreHz.toDouble() / sampleRateHz.toDouble()
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q.toDouble())
            return normalize(
                b0 = 1.0 + alpha * a,
                b1 = -2.0 * cosW0,
                b2 = 1.0 - alpha * a,
                a0 = 1.0 + alpha / a,
                a1 = -2.0 * cosW0,
                a2 = 1.0 - alpha / a,
            )
        }

        /** Linear amplitude for a gain in dB. 0 dB maps to exactly 1.0. */
        private fun amplitudeFor(gainDb: Float): Double =
            10.0.pow(gainDb.toDouble() / 40.0)

        private fun sqrtSlope(slope: Float, a: Double): Double {
            val s = slope.toDouble().coerceAtLeast(1e-6)
            return sqrt((a + 1.0 / a) * (1.0 / s - 1.0) + 2.0)
        }

        private fun normalize(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double): BiquadCoefficients {
            require(a0 != 0.0 && !a0.isNaN() && !a0.isInfinite()) { "Degenerate filter denominator" }
            return BiquadCoefficients(
                b0 = (b0 / a0).toFloat(),
                b1 = (b1 / a0).toFloat(),
                b2 = (b2 / a0).toFloat(),
                a1 = (a1 / a0).toFloat(),
                a2 = (a2 / a0).toFloat(),
            )
        }
    }
}

/**
 * The three bands of [EqualizerSettings] resolved for one sample rate.
 *
 * Built once per configuration change, never per buffer, and reused for every
 * channel of every frame in that configuration.
 */
data class EqualizerBands(
    val bass: BiquadCoefficients,
    val mid: BiquadCoefficients,
    val treble: BiquadCoefficients,
) {
    /** True when no band alters samples, so the DSP stage can bypass. */
    val isUnity: Boolean get() = bass.isUnity && mid.isUnity && treble.isUnity

    val allStable: Boolean get() = bass.isStable && mid.isStable && treble.isStable

    companion object {
        fun from(settings: EqualizerSettings, sampleRateHz: Int): EqualizerBands = EqualizerBands(
            bass = BiquadCoefficients.lowShelf(EqualizerSettings.BASS_CENTRE_HZ, settings.bassGainDb, sampleRateHz),
            mid = BiquadCoefficients.peaking(EqualizerSettings.MID_CENTRE_HZ, settings.midGainDb, sampleRateHz),
            treble = BiquadCoefficients.highShelf(EqualizerSettings.TREBLE_CENTRE_HZ, settings.trebleGainDb, sampleRateHz),
        )

        /** Neutral bands, used to verify the bypass path is a numerical no-op. */
        val IDENTITY = EqualizerBands(
            BiquadCoefficients(1f, 0f, 0f, 0f, 0f),
            BiquadCoefficients(1f, 0f, 0f, 0f, 0f),
            BiquadCoefficients(1f, 0f, 0f, 0f, 0f),
        )
    }
}

/**
 * Stereo gain pair for a balance setting, resolved for a known input channel
 * count.
 *
 * Two laws, chosen by the input layout the DSP stage actually receives:
 *
 * - **Stereo input** uses a balance law. Centre is unity on both channels. Off
 *   centre, the opposite channel is attenuated on a constant-power curve and the
 *   requested channel stays at unity, so total energy is roughly preserved and
 *   neither channel is ever amplified past its source level.
 * - **Mono input** uses a pan law, splitting one channel across a stereo output
 *   on the same constant-power curve. Centre gives unity to both channels, which
 *   is a dual-mono image and the expected result for centred mono material.
 *
 * Constant power means `left^2 + right^2 == 2` for the normalized mono law
 * (unity at centre, `sqrt(2)` at the hard extremes), keeping perceived loudness
 * steady as the image moves. The stereo balance law keeps the louder side at
 * exactly unity for the same reason.
 */
data class BalanceGains(val left: Float, val right: Float) {

    companion object {
        val IDENTITY = BalanceGains(1f, 1f)

        /** Normalization that makes the mono pan law unity at centre. */
        private val SQRT_2 = sqrt(2.0)

        /**
         * Mono-to-stereo pan. [pan] is -1..1.
         *
         * Constant-power law normalized to unity at centre:
         *
         * ```
         * theta = (pan + 1) * pi / 4
         * left  = sqrt(2) * cos(theta)
         * right = sqrt(2) * sin(theta)
         * ```
         *
         * The `sqrt(2)` factor matters. Without it centre would be 0.707 per
         * channel, but a centred clip bypasses this stage entirely and plays at
         * unity, so the first fraction of a slider move would drop the level 3 dB
         * — an audible step. Normalized to unity at centre, the engaged law meets
         * the bypassed state continuously, and total power stays constant across
         * the whole range because `left^2 + right^2 == 2` throughout.
         *
         * The consequence at hard left or hard right is a single channel at
         * sqrt(2), about +3 dB. That is the intended constant-power result, not
         * an accidental boost, and it is saturated to the PCM range at the point
         * of conversion like every other Stage 8 gain stage.
         */
        fun forMonoPan(pan: Float): BalanceGains {
            val p = if (pan.isNaN()) 0f else pan.coerceIn(AudioBalance.MIN_PAN, AudioBalance.MAX_PAN)
            // theta spans 0..pi/2 as pan spans -1..1.
            val theta = (p + 1f) * (Math.PI / 4.0)
            val normalization = SQRT_2
            return BalanceGains(
                (cos(theta) * normalization).toFloat(),
                (sin(theta) * normalization).toFloat(),
            )
        }

        /** Stereo balance: the attenuated side falls on a constant-power curve
         *  while the requested side stays at unity. */
        fun forStereoBalance(pan: Float): BalanceGains {
            val p = if (pan.isNaN()) 0f else pan.coerceIn(AudioBalance.MIN_PAN, AudioBalance.MAX_PAN)
            // At p = 0 both are unity. At p = +1 the left channel is fully
            // attenuated; at p = -1 the right channel is. cos(theta) with theta
            // spanning 0..pi/2 over |p| gives a constant-power attenuation of the
            // opposite side and exactly 1.0 at centre.
            val theta = kotlin.math.abs(p) * (Math.PI / 2.0)
            val attenuated = cos(theta).toFloat()
            return if (p >= 0f) BalanceGains(attenuated, 1f) else BalanceGains(1f, attenuated)
        }
    }
}
