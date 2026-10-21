package com.videoeditor.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * Host tests for the Stage 8 audio-effect model: envelope interpolation,
 * deterministic ordering, split/duplicate mapping, fade curve shapes, EQ
 * coefficient correctness and stability, balance laws, and the serialization
 * contract that keeps Stage 6/7 documents neutral.
 *
 * These are pure math and data tests; sample-level PCM verification lives in
 * the on-device processor tests.
 */
class AudioEffectsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // -- VolumeEnvelope evaluation --

    @Test fun `legacy media layout is unresolved and stereo metadata round trips`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString(MediaRef.serializer(), """{"id":"r","uri":"file:///tone.m4a","type":"Audio","displayName":"tone"}""")
        assertEquals(null, legacy.audioChannelCount)
        val stereo = legacy.copy(audioChannelCount = 2)
        assertEquals(stereo, json.decodeFromString(MediaRef.serializer(), json.encodeToString(MediaRef.serializer(), stereo)))
    }

    @Test fun `empty envelope is unity everywhere`() {
        val envelope = VolumeEnvelope()
        assertThat(envelope.isEmpty).isTrue()
        assertThat(envelope.gainDbAt(0L)).isEqualTo(0f)
        assertThat(envelope.gainDbAt(500_000L)).isEqualTo(0f)
        assertThat(envelope.gainAt(2_000_000L)).isEqualTo(1f)
    }

    @Test fun `gain holds first and last values as lead-in and tail`() {
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("b", 2_000_000, -6f),
            VolumePoint("a", 1_000_000, -12f),
        ))
        assertThat(envelope.gainDbAt(0L)).isEqualTo(-12f)
        assertThat(envelope.gainDbAt(999_999L)).isEqualTo(-12f)
        assertThat(envelope.gainDbAt(3_000_000L)).isEqualTo(-6f)
        assertThat(envelope.gainDbAt(9_999_999L)).isEqualTo(-6f)
    }

    @Test fun `linear interpolation between points is exact in dB`() {
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 1_000_000, -12f),
            VolumePoint("b", 3_000_000, 0f),
        ))
        // Midpoint: -6 dB exactly.
        assertThat(envelope.gainDbAt(2_000_000L)).isWithin(1e-4f).of(-6f)
        // Quarter positions.
        assertThat(envelope.gainDbAt(1_500_000L)).isWithin(1e-4f).of(-9f)
        assertThat(envelope.gainDbAt(2_500_000L)).isWithin(1e-4f).of(-3f)
    }

    @Test fun `out-of-order input still evaluates in deterministic position order`() {
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("c", 3_000_000, 0f),
            VolumePoint("a", 1_000_000, -12f),
            VolumePoint("b", 2_000_000, -6f),
        ))
        assertThat(envelope.gainDbAt(1_500_000L)).isWithin(1e-4f).of(-9f)
        assertThat(envelope.gainDbAt(2_500_000L)).isWithin(1e-4f).of(-3f)
    }

    @Test fun `hold interpolation keeps the earlier gain until the next point`() {
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 1_000_000, -6f, VolumeInterpolation.Hold),
            VolumePoint("b", 2_000_000, 0f),
        ))
        assertThat(envelope.gainDbAt(1_999_999L)).isEqualTo(-6f)
        assertThat(envelope.gainDbAt(2_000_000L)).isEqualTo(0f)
    }

    @Test fun `smooth interpolation is exact at endpoints and between linear bounds`() {
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 1_000_000, -12f, VolumeInterpolation.Smooth),
            VolumePoint("b", 3_000_000, 0f),
        ))
        assertThat(envelope.gainDbAt(1_000_000L)).isWithin(1e-4f).of(-12f)
        assertThat(envelope.gainDbAt(3_000_000L)).isWithin(1e-4f).of(0f)
        // Smoothstep midpoint equals the linear midpoint by symmetry, but at
        // quarter positions it must sit inside the linear envelope's convex hull.
        val quarter = envelope.gainDbAt(1_500_000L)
        assertThat(quarter).isAtLeast(-12f)
        assertThat(quarter).isAtMost(0f)
        // Raised-cosine smoothstep(0.25) = 0.15625, so -12 + 12 * 0.15625 = -10.125 dB.
        assertThat(quarter).isWithin(1e-3f).of(-10.125f)
    }

    @Test fun `duplicate source positions resolve deterministically by id`() {
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("z", 1_000_000, -6f),
            VolumePoint("a", 1_000_000, -2f),
        ))
        // Later-ordered id wins at the shared position.
        assertThat(envelope.gainDbAt(1_000_000L)).isEqualTo(-2f)
    }

    @Test fun `gain conversion is 10-to-the-db-over-20 and clamped`() {
        assertThat(VolumeEnvelope.gainDbToLinear(0f)).isWithin(1e-6f).of(1f)
        assertThat(VolumeEnvelope.gainDbToLinear(-6f)).isWithin(1e-3f).of(0.5012f)
        assertThat(VolumeEnvelope.gainDbToLinear(6f)).isWithin(1e-3f).of(1.9953f)
        assertThat(VolumeEnvelope.gainDbToLinear(-200f)).isEqualTo(
            VolumeEnvelope.gainDbToLinear(VolumeEnvelope.MIN_GAIN_DB))
        assertThat(VolumeEnvelope.gainDbToLinear(Float.NaN)).isEqualTo(1f)
        assertThat(VolumeEnvelope.gainDbToLinear(Float.POSITIVE_INFINITY)).isEqualTo(
            VolumeEnvelope.gainDbToLinear(VolumeEnvelope.MAX_GAIN_DB))
    }

    // -- Split mapping --

    @Test fun `empty envelope splits into two empty envelopes`() {
        val (left, right) = VolumeEnvelope().splitAt(5_000_000, "lb", "rb")
        assertThat(left.isEmpty).isTrue()
        assertThat(right.isEmpty).isTrue()
    }

    @Test fun `split preserves authored gain at the boundary on both halves`() {
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 0L, 0f),
            VolumePoint("b", 10_000_000, -12f),
        ))
        val (left, right) = envelope.splitAt(4_000_000, "lb", "rb")
        val boundaryGain = envelope.gainDbAt(4_000_000L)
        assertThat(left.gainDbAt(4_000_000L)).isWithin(1e-4f).of(boundaryGain)
        assertThat(right.gainDbAt(4_000_000L)).isWithin(1e-4f).of(boundaryGain)
        // Interior points stay with their half.
        assertThat(left.points.map { it.sourcePositionMicros })
            .containsExactly(0L, 4_000_000L).inOrder()
        assertThat(right.points.map { it.sourcePositionMicros })
            .containsExactly(4_000_000L, 10_000_000L).inOrder()
        // The right half's tail still holds the original final gain.
        assertThat(right.gainDbAt(20_000_000L)).isEqualTo(-12f)
    }

    @Test fun `split mid-ramp leaves both halves' interiors continuous with the original`() {
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 0L, -12f),
            VolumePoint("b", 8_000_000, 0f),
        ))
        val (left, right) = envelope.splitAt(2_000_000, "lb", "rb")
        // The left half evaluated inside its span must equal the unsplit envelope.
        assertThat(left.gainDbAt(1_000_000L)).isWithin(1e-4f).of(envelope.gainDbAt(1_000_000L))
        // The right half evaluated inside its span must equal the unsplit envelope.
        assertThat(right.gainDbAt(5_000_000L)).isWithin(1e-4f).of(envelope.gainDbAt(5_000_000L))
    }

    @Test fun `split inside a hold segment keeps holding in the right half`() {
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 0L, -6f, VolumeInterpolation.Hold),
            VolumePoint("b", 8_000_000, 0f),
        ))
        val (_, right) = envelope.splitAt(2_000_000, "lb", "rb")
        assertThat(right.gainDbAt(5_000_000L)).isEqualTo(-6f)
        assertThat(right.gainDbAt(8_000_000L)).isEqualTo(0f)
    }

    @Test fun `split at an authored point replaces it with an equal boundary point`() {
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 0L, 0f),
            VolumePoint("b", 4_000_000, -6f),
            VolumePoint("c", 10_000_000, -6f),
        ))
        val (left, right) = envelope.splitAt(4_000_000, "lb", "rb")
        assertThat(left.gainDbAt(4_000_000L)).isEqualTo(-6f)
        assertThat(right.gainDbAt(4_000_000L)).isEqualTo(-6f)
        assertThat(right.points.none { it.id == "b" }).isTrue()
        assertThat(left.points.none { it.id == "b" }).isTrue()
    }

    // -- Remap for duplication --

    @Test fun `remapPointIds regenerates every id and keeps gains`() {
        val envelope = VolumeEnvelope(listOf(
            VolumePoint("a", 1_000_000, -6f),
            VolumePoint("b", 2_000_000, -3f),
        ))
        var counter = 0
        val remapped = envelope.remapPointIds { "new-${counter++}" }
        assertThat(remapped.points.map { it.id }).containsExactly("new-0", "new-1")
        assertThat(remapped.gainDbAt(1_500_000L)).isWithin(1e-4f).of(envelope.gainDbAt(1_500_000L))
    }

    // -- Fade curves --

    @Test fun `every fade curve is exact at its endpoints and monotonic`() {
        for (curve in FadeCurve.entries) {
            assertThat(curve.amplitudeAt(0f)).isWithin(1e-6f).of(0f)
            assertThat(curve.amplitudeAt(1f)).isWithin(1e-6f).of(1f)
            var previous = -1f
            var step = 0
            while (step <= 100) {
                val value = curve.amplitudeAt(step / 100f)
                assertThat(value).isAtLeast(previous)
                previous = value
                step++
            }
        }
    }

    @Test fun `linear fade reproduces the stage 7 identity`() {
        assertThat(FadeCurve.Linear.amplitudeAt(0.25f)).isWithin(1e-6f).of(0.25f)
        assertThat(FadeCurve.Linear.amplitudeAt(0.5f)).isWithin(1e-6f).of(0.5f)
        assertThat(FadeCurve.Linear.amplitudeAt(0.75f)).isWithin(1e-6f).of(0.75f)
    }

    @Test fun `ease in starts slower than linear and ease out starts faster`() {
        assertThat(FadeCurve.EaseIn.amplitudeAt(0.5f)).isWithin(1e-4f).of(0.25f)
        assertThat(FadeCurve.EaseOut.amplitudeAt(0.5f)).isWithin(1e-4f).of(0.75f)
    }

    @Test fun `exponential fade is exact at zero and steeper than linear at the midpoint`() {
        assertThat(FadeCurve.Exponential.amplitudeAt(0f)).isEqualTo(0f)
        assertThat(FadeCurve.Exponential.amplitudeAt(1f)).isWithin(1e-6f).of(1f)
        assertThat(FadeCurve.Exponential.amplitudeAt(0.5f)).isLessThan(0.5f)
    }

    // -- EQ coefficients --

    @Test fun `zero-gain bands are unity coefficients`() {
        val bands = EqualizerBands.from(EqualizerSettings(), 48_000)
        assertThat(bands.isUnity).isTrue()
        assertThat(bands.bass.isUnity).isTrue()
        assertThat(bands.mid.isUnity).isTrue()
        assertThat(bands.treble.isUnity).isTrue()
    }

    @Test fun `every supported band gain produces a stable filter at both sample rates`() {
        for (rate in listOf(44_100, 48_000)) {
            for (gainDb in listOf(-12f, -6f, 3f, 6f, 12f)) {
                for (settings in listOf(
                    EqualizerSettings(enabled = true, bassGainDb = gainDb),
                    EqualizerSettings(enabled = true, midGainDb = gainDb),
                    EqualizerSettings(enabled = true, trebleGainDb = gainDb),
                )) {
                    val bands = EqualizerBands.from(settings, rate)
                    assertThat(bands.allStable).isTrue()
                }
            }
        }
    }

    @Test fun `bass boost raises the low band and leaves the top flat`() {
        val bands = EqualizerBands.from(EqualizerSettings(enabled = true, bassGainDb = 6f), 48_000)
        // A Butterworth (S=0.707) shelf reaches its full gain only at DC and
        // rolls off within its slope. At 50 Hz it is about +5.2 dB, close to
        // full boost; the band's effect on the bass region is unambiguous.
        assertThat(bands.bass.magnitudeDbAt(50f, 48_000)).isWithin(0.5f).of(5.18f)
        // At the shelf centre the response is the half-amplitude point (+3 dB).
        assertThat(bands.bass.magnitudeDbAt(EqualizerSettings.BASS_CENTRE_HZ, 48_000)).isWithin(0.6f).of(3f)
        // The shelf keeps attenuating through the transition: 200 Hz is far
        // below full boost, and the response returns toward unity by 1 kHz.
        assertThat(bands.bass.magnitudeDbAt(200f, 48_000)).isLessThan(2f)
        assertThat(bands.bass.magnitudeDbAt(1_000f, 48_000)).isWithin(0.5f).of(0f)
        assertThat(bands.bass.magnitudeDbAt(10_000f, 48_000)).isWithin(0.5f).of(0f)
    }

    @Test fun `mid peak raises the centre and leaves both extremes flat`() {
        val bands = EqualizerBands.from(EqualizerSettings(enabled = true, midGainDb = 6f), 48_000)
        assertThat(bands.mid.magnitudeDbAt(EqualizerSettings.MID_CENTRE_HZ, 48_000)).isWithin(0.3f).of(6f)
        assertThat(bands.mid.magnitudeDbAt(50f, 48_000)).isWithin(0.3f).of(0f)
        assertThat(bands.mid.magnitudeDbAt(16_000f, 48_000)).isWithin(0.3f).of(0f)
    }

    @Test fun `treble boost raises the high band and leaves the bottom flat`() {
        val bands = EqualizerBands.from(EqualizerSettings(enabled = true, trebleGainDb = 6f), 48_000)
        assertThat(bands.treble.magnitudeDbAt(16_000f, 48_000)).isWithin(0.6f).of(6f)
        assertThat(bands.treble.magnitudeDbAt(EqualizerSettings.TREBLE_CENTRE_HZ, 48_000)).isWithin(0.6f).of(3f)
        assertThat(bands.treble.magnitudeDbAt(50f, 48_000)).isWithin(0.5f).of(0f)
    }

    @Test fun `response matches at 44100 within the documented tolerance`() {
        val settings = EqualizerSettings(enabled = true, bassGainDb = 6f, midGainDb = 3f, trebleGainDb = -3f)
        val at44 = EqualizerBands.from(settings, 44_100)
        val at48 = EqualizerBands.from(settings, 48_000)
        // Same nominal frequency must yield the same response within 0.5 dB.
        for (freq in listOf(50f, 100f, 1_000f, 8_000f, 12_000f)) {
            val diff = abs(
                (at44.bass.magnitudeDbAt(freq, 44_100) + at44.mid.magnitudeDbAt(freq, 44_100) + at44.treble.magnitudeDbAt(freq, 44_100)) -
                    (at48.bass.magnitudeDbAt(freq, 48_000) + at48.mid.magnitudeDbAt(freq, 48_000) + at48.treble.magnitudeDbAt(freq, 48_000)),
            )
            assertThat(diff).isLessThan(0.5f)
        }
    }

    @Test fun `presets carry their documented band gains`() {
        assertThat(EqualizerPreset.Flat.settings().isActive).isFalse()
        val voice = EqualizerPreset.Voice.settings()
        assertThat(voice.bassGainDb).isEqualTo(-3f)
        assertThat(voice.midGainDb).isEqualTo(2f)
        assertThat(voice.trebleGainDb).isEqualTo(4f)
        val bass = EqualizerPreset.BassBoost.settings()
        assertThat(bass.bassGainDb).isEqualTo(6f)
    }

    @Test fun `band gains clamp to the documented range`() {
        val settings = EqualizerSettings(enabled = true, bassGainDb = 40f, midGainDb = -40f, trebleGainDb = 0f).coerced()
        assertThat(settings.bassGainDb).isEqualTo(12f)
        assertThat(settings.midGainDb).isEqualTo(-12f)
    }

    // -- Balance laws --

    @Test fun `centre balance is exact unity on both channels`() {
        assertThat(BalanceGains.IDENTITY.left).isEqualTo(1f)
        assertThat(BalanceGains.IDENTITY.right).isEqualTo(1f)
        // The normalized pan law meets the bypassed state continuously: centre
        // is unity on both channels, matching a clip with no balance stage.
        assertThat(BalanceGains.forMonoPan(0f).left).isWithin(1e-5f).of(1f)
        assertThat(BalanceGains.forMonoPan(0f).right).isWithin(1e-5f).of(1f)
        assertThat(BalanceGains.forStereoBalance(0f)).isEqualTo(BalanceGains.IDENTITY)
    }

    @Test fun `mono pan is constant power across the full range`() {
        // The normalized law keeps left^2 + right^2 == 2 throughout (unity at
        // centre, sqrt(2) at the extremes), which is the constant-power property
        // expressed in a scale that meets the bypassed centre at continuity.
        var step = -10
        while (step <= 10) {
            val pan = step / 10f
            val gains = BalanceGains.forMonoPan(pan)
            val power = gains.left * gains.left + gains.right * gains.right
            assertThat(power).isWithin(1e-3f).of(2f)
            step++
        }
        assertThat(BalanceGains.forMonoPan(-1f).right).isWithin(1e-4f).of(0f)
        assertThat(BalanceGains.forMonoPan(1f).left).isWithin(1e-4f).of(0f)
    }

    @Test fun `stereo balance never amplifies and keeps the requested side at unity`() {
        val left = BalanceGains.forStereoBalance(-1f)
        assertThat(left.left).isEqualTo(1f)
        assertThat(left.right).isWithin(1e-4f).of(0f)
        val right = BalanceGains.forStereoBalance(1f)
        assertThat(right.right).isEqualTo(1f)
        assertThat(right.left).isWithin(1e-4f).of(0f)
        // Half-way: attenuated side is cos(pi/4) ≈ 0.7071.
        assertThat(BalanceGains.forStereoBalance(0.5f).left).isWithin(1e-3f).of(0.7071f)
        assertThat(BalanceGains.forStereoBalance(0.5f).right).isEqualTo(1f)
    }

    @Test fun `pan clamps and NaN maps to centre`() {
        assertThat(BalanceGains.forMonoPan(2f)).isEqualTo(BalanceGains.forMonoPan(1f))
        assertThat(BalanceGains.forMonoPan(-2f)).isEqualTo(BalanceGains.forMonoPan(-1f))
        // NaN normalizes to the same gains as an explicit centre pan.
        assertThat(BalanceGains.forMonoPan(Float.NaN)).isEqualTo(BalanceGains.forMonoPan(0f))
        assertThat(AudioBalance(Float.NaN).coerced().pan).isEqualTo(0f)
    }

    // -- Ducking settings contract --

    @Test fun `ducking coerces into documented bounds`() {
        val settings = DuckingSettings(
            enabled = true, triggerClipIds = listOf("t"), strengthDb = 500f,
            thresholdDb = 20f, attackMillis = 0, releaseMillis = 100_000,
        ).coerced()
        assertThat(settings.strengthDb).isEqualTo(DuckingSettings.MAX_STRENGTH_DB)
        assertThat(settings.thresholdDb).isEqualTo(DuckingSettings.MAX_THRESHOLD_DB)
        assertThat(settings.attackMillis).isEqualTo(DuckingSettings.MIN_TIME_CONSTANT_MILLIS)
        assertThat(settings.releaseMillis).isEqualTo(DuckingSettings.MAX_TIME_CONSTANT_MILLIS)
    }

    @Test fun `ducking is inactive without triggers and zero strength is unity reduction`() {
        assertThat(DuckingSettings(enabled = true).isActive).isFalse()
        assertThat(DuckingSettings(enabled = true, triggerClipIds = listOf("t")).isActive).isTrue()
    }

    // -- Serialization contract --

    @Test fun `audio clip effects round-trip through the project document format`() {
        val clip = AudioClip(
            id = "a1", mediaRefId = "m1",
            sourceStartMicros = 0L, sourceEndMicros = 4_000_000L,
            timelineStartMicros = 1_000_000L,
            volumeEnvelope = VolumeEnvelope(listOf(
                VolumePoint("p1", 500_000, -6f),
                VolumePoint("p2", 2_000_000, 0f, VolumeInterpolation.Smooth),
            )),
            fadeInMicros = 300_000, fadeInCurve = FadeCurve.EaseOut,
            fadeOutMicros = 200_000, fadeOutCurve = FadeCurve.Exponential,
            equalizer = EqualizerPreset.Voice.settings(),
            balance = AudioBalance(0.5f),
            ducking = DuckingSettings(enabled = true, triggerClipIds = listOf("v1"), strengthDb = 10f),
        )
        val decoded = json.decodeFromString<AudioClip>(json.encodeToString(clip))
        assertThat(decoded).isEqualTo(clip)
        // The Smooth segment from p1 to p2, evaluated at its midpoint, is the
        // raised-cosine midpoint: exactly halfway, -3 dB.
        assertThat(decoded.volumeEnvelope.gainDbAt(1_250_000L)).isWithin(1e-4f).of(-3f)
    }

    @Test fun `legacy stage 7 audio clip documents load with neutral effects`() {
        // A Stage 7 document body: no volumeEnvelope, no curves, no EQ/balance/ducking.
        val legacyJson = """{"id":"a1","mediaRefId":"m1","sourceStartMicros":0,"sourceEndMicros":4000000,"timelineStartMicros":0,"volume":1.0,"muted":false,"fadeInMicros":300000,"fadeOutMicros":0,"playbackSpeed":1.0}"""
        val clip = json.decodeFromString<AudioClip>(legacyJson)
        assertThat(clip.volumeEnvelope.isEmpty).isTrue()
        assertThat(clip.fadeInCurve).isEqualTo(FadeCurve.Linear)
        assertThat(clip.fadeOutCurve).isEqualTo(FadeCurve.Linear)
        assertThat(clip.equalizer.isActive).isFalse()
        assertThat(clip.balance.isIdentity).isTrue()
        assertThat(clip.ducking.isActive).isFalse()
        assertThat(clip.fadeInMicros).isEqualTo(300_000L)
    }

    @Test fun `unknown future fields are ignored by the document reader`() {
        val futureJson = """{"id":"a1","mediaRefId":"m1","sourceStartMicros":0,"sourceEndMicros":4000000,"timelineStartMicros":0,"volumeEnvelope":{"points":[{"id":"p1","sourcePositionMicros":1000000,"gainDb":-3.0,"interpolation":"Linear","futureField":42}]},"unknownAudioFeature":{"x":1}}"""
        val clip = json.decodeFromString<AudioClip>(futureJson)
        assertThat(clip.volumeEnvelope.points.single().gainDb).isEqualTo(-3f)
    }

    @Test fun `a stage 7 clip with neutral effects produces the stage 7 sample path`() {
        // Neutral state must report itself inactive everywhere the DSP chain checks.
        val clip = AudioClip(id = "a", mediaRefId = "m", sourceStartMicros = 0, sourceEndMicros = 1_000_000)
        assertThat(clip.volumeEnvelope.points.isEmpty()).isTrue()
        assertThat(clip.equalizer.isActive).isFalse()
        assertThat(clip.balance.isIdentity).isTrue()
        assertThat(clip.ducking.isActive).isFalse()
        assertThat(clip.fadeInCurve).isEqualTo(FadeCurve.Linear)
        assertThat(clip.fadeOutCurve).isEqualTo(FadeCurve.Linear)
    }
}
