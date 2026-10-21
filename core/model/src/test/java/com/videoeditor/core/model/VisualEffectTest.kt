package com.videoeditor.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class VisualEffectTest {

    @Test
    fun `all visual effect types have valid display names and categories`() {
        VisualEffectType.values().forEach { type ->
            assertThat(type.displayName).isNotEmpty()
            assertThat(type.category).isNotEmpty()
            val effect = VisualEffect.createDefault(type)
            assertThat(effect.id).isNotEmpty()
            assertThat(effect.type).isEqualTo(type)
            assertThat(effect.enabled).isTrue()
            assertThat(effect.intensity).isGreaterThan(0f)
        }
    }

    @Test
    fun `keyframe evaluation with linear interpolation works correctly`() {
        val track = KeyframeTrack(
            propertyName = "intensity",
            keyframes = listOf(
                VisualKeyframe("k1", 1_000_000L, 0.0f, KeyframeInterpolation.Linear),
                VisualKeyframe("k2", 2_000_000L, 1.0f, KeyframeInterpolation.Linear),
            )
        )

        // Before first keyframe
        assertThat(track.evaluate(500_000L, 0.5f)).isEqualTo(0.0f)
        // At first keyframe
        assertThat(track.evaluate(1_000_000L, 0.5f)).isEqualTo(0.0f)
        // Midpoint
        assertThat(track.evaluate(1_500_000L, 0.5f)).isWithin(1e-4f).of(0.5f)
        // At second keyframe
        assertThat(track.evaluate(2_000_000L, 0.5f)).isEqualTo(1.0f)
        // Past second keyframe
        assertThat(track.evaluate(3_000_000L, 0.5f)).isEqualTo(1.0f)
    }

    @Test
    fun `keyframe evaluation with hold interpolation retains previous value`() {
        val track = KeyframeTrack(
            propertyName = "opacity",
            keyframes = listOf(
                VisualKeyframe("k1", 1_000_000L, 0.2f, KeyframeInterpolation.Hold),
                VisualKeyframe("k2", 2_000_000L, 0.8f, KeyframeInterpolation.Hold),
            )
        )

        assertThat(track.evaluate(1_000_000L, 0.5f)).isEqualTo(0.2f)
        assertThat(track.evaluate(1_999_999L, 0.5f)).isEqualTo(0.2f)
        assertThat(track.evaluate(2_000_000L, 0.5f)).isEqualTo(0.8f)
    }

    @Test
    fun `keyframe evaluation with smooth easing produces smooth transition`() {
        val track = KeyframeTrack(
            propertyName = "scale",
            keyframes = listOf(
                VisualKeyframe("k1", 0L, 0.0f, KeyframeInterpolation.Smooth),
                VisualKeyframe("k2", 1_000_000L, 1.0f, KeyframeInterpolation.Smooth),
            )
        )

        // Midpoint for smoothstep is 0.5
        assertThat(track.evaluate(500_000L, 0.5f)).isWithin(1e-4f).of(0.5f)
        // At 25% progress, smoothstep is 3*(0.25)^2 - 2*(0.25)^3 = 0.1875 - 0.03125 = 0.15625 < 0.25 (eases in)
        val at25 = track.evaluate(250_000L, 0.5f)
        assertThat(at25).isLessThan(0.25f)
        // At 75% progress, smoothstep eases out
        val at75 = track.evaluate(750_000L, 0.5f)
        assertThat(at75).isGreaterThan(0.75f)
    }

    @Test
    fun `chroma key color channels are unpacked correctly`() {
        val greenKey = ChromaKeySettings(keyColor = 0xFF00FF00.toInt())
        assertThat(greenKey.keyRed).isEqualTo(0f)
        assertThat(greenKey.keyGreen).isEqualTo(1.0f)
        assertThat(greenKey.keyBlue).isEqualTo(0f)

        val blueKey = ChromaKeySettings(keyColor = 0xFF0000FF.toInt())
        assertThat(blueKey.keyRed).isEqualTo(0f)
        assertThat(blueKey.keyGreen).isEqualTo(0f)
        assertThat(blueKey.keyBlue).isEqualTo(1.0f)
    }

    @Test
    fun `layer mask evaluation with keyframes animates properties`() {
        val mask = LayerMask(
            centerX = 0f,
            centerY = 0f,
            keyframeTracks = mapOf(
                "centerX" to KeyframeTrack(
                    "centerX",
                    listOf(
                        VisualKeyframe("k1", 0L, -0.5f),
                        VisualKeyframe("k2", 1_000_000L, 0.5f),
                    )
                )
            )
        )

        val atMid = mask.evaluatedMaskAt(500_000L)
        assertThat(atMid.centerX).isWithin(1e-4f).of(0.0f)
    }

    @Test
    fun `project with Stage 9 features serializes and deserializes losslessly`() {
        val effect = VisualEffect.createDefault(VisualEffectType.GaussianBlur)
        val mask = LayerMask(type = MaskType.Circle, feather = 0.1f)
        val chroma = ChromaKeySettings(enabled = true, tolerance = 0.4f)

        val project = Project(
            id = "test-project-stage9",
            name = "Stage 9 Project",
            createdAtMillis = 1000L,
            updatedAtMillis = 2000L,
            timeline = Timeline(
                videoClips = listOf(
                    Clip(
                        id = "clip-1",
                        mediaRefId = "ref-1",
                        effects = listOf(effect),
                    )
                ),
                overlayClips = listOf(
                    OverlayClip(
                        id = "overlay-1",
                        mediaRefId = "ref-2",
                        blendMode = BlendMode.Screen,
                        mask = mask,
                        chromaKey = chroma,
                    )
                ),
                transitions = listOf(
                    Transition(
                        id = "trans-1",
                        outgoingClipId = "clip-1",
                        incomingClipId = "clip-2",
                        durationMicros = 1_000_000L,
                        type = TransitionType.FadeThroughBlack,
                    )
                )
            )
        )

        val json = Json.encodeToString(project)
        val decoded = Json.decodeFromString<Project>(json)

        assertThat(decoded.id).isEqualTo(project.id)
        val decodedClip = decoded.timeline.videoClips.single()
        assertThat(decodedClip.effects.single().type).isEqualTo(VisualEffectType.GaussianBlur)

        val decodedOverlay = decoded.timeline.overlayClips.single()
        assertThat(decodedOverlay.blendMode).isEqualTo(BlendMode.Screen)
        assertThat(decodedOverlay.mask?.type).isEqualTo(MaskType.Circle)
        assertThat(decodedOverlay.chromaKey?.enabled).isTrue()

        val decodedTransition = decoded.timeline.transitions.single()
        assertThat(decodedTransition.type).isEqualTo(TransitionType.FadeThroughBlack)
    }

    @Test
    fun blendModes_all13Variants_haveDistinctNamesAndSerialize() {
        val modes = BlendMode.values()
        assertThat(modes).hasLength(13)
        val names = modes.map { it.displayName }.distinct()
        assertThat(names).hasSize(13)

        // Verify JSON serialization round-trip for all 13
        for (mode in modes) {
            val json = Json.encodeToString(mode)
            val decoded = Json.decodeFromString<BlendMode>(json)
            assertThat(decoded).isEqualTo(mode)
        }
    }

    @Test
    fun transitions_all13Variants_haveDistinctNamesAndSerialize() {
        val types = TransitionType.values()
        assertThat(types).hasLength(13)
        val names = types.map { it.displayName }.distinct()
        assertThat(names).hasSize(13)

        for (type in types) {
            val json = Json.encodeToString(type)
            val decoded = Json.decodeFromString<TransitionType>(json)
            assertThat(decoded).isEqualTo(type)
        }
    }

    @Test
    fun overlayClip_evaluatesOpacityWithKeyframes_linearHoldSmooth() {
        val keyframes = listOf(
            VisualKeyframe("k1", 0L, 0.2f, KeyframeInterpolation.Linear),
            VisualKeyframe("k2", 1_000_000L, 0.8f, KeyframeInterpolation.Hold),
            VisualKeyframe("k3", 2_000_000L, 1.0f, KeyframeInterpolation.Smooth),
            VisualKeyframe("k4", 3_000_000L, 0.0f, KeyframeInterpolation.Linear),
        )
        val overlay = OverlayClip(
            id = "ov1",
            mediaRefId = "ref1",
            timelineStartMicros = 100_000L,
            timelineDurationMicros = 5_000_000L,
            opacity = 1.0f,
            keyframeTracks = mapOf("opacity" to KeyframeTrack("opacity", keyframes))
        )

        // At timeline start (relative 0us) -> 0.2f
        assertThat(overlay.evaluateOpacityAt(100_000L)).isEqualTo(0.2f)

        // Halfway between 0 and 1s (relative 500,000us) Linear -> 0.5f
        val midLinear = overlay.evaluateOpacityAt(600_000L)
        assertThat(midLinear).isWithin(0.01f).of(0.5f)

        // At relative 1,500,000us, k2 is Hold with value 0.8f -> must remain 0.8f
        assertThat(overlay.evaluateOpacityAt(1_600_000L)).isEqualTo(0.8f)

        // At relative 2,000,000us, value reaches 1.0f
        assertThat(overlay.evaluateOpacityAt(2_100_000L)).isEqualTo(1.0f)
    }

    @Test
    fun layerMask_polygonShape_withVariousSidesAndTransforms() {
        for (sides in 3..8) {
            val mask = LayerMask(
                type = MaskType.Polygon,
                polygonSides = sides,
                centerX = 0.1f,
                centerY = -0.2f,
                scaleX = 0.8f,
                scaleY = 0.6f,
                rotationDegrees = 45f,
                feather = 0.08f,
                invert = true,
            )
            val json = Json.encodeToString(mask)
            val decoded = Json.decodeFromString<LayerMask>(json)
            assertThat(decoded.type).isEqualTo(MaskType.Polygon)
            assertThat(decoded.polygonSides).isEqualTo(sides)
            assertThat(decoded.centerX).isEqualTo(0.1f)
            assertThat(decoded.centerY).isEqualTo(-0.2f)
            assertThat(decoded.scaleX).isEqualTo(0.8f)
            assertThat(decoded.scaleY).isEqualTo(0.6f)
            assertThat(decoded.rotationDegrees).isEqualTo(45f)
            assertThat(decoded.feather).isEqualTo(0.08f)
            assertThat(decoded.invert).isTrue()
        }
    }

    @Test
    fun visualEffect_timelineIntervals_activeCheck() {
        val effect = VisualEffect(
            id = "eff1",
            type = VisualEffectType.GaussianBlur,
            startTimeMicros = 1_000_000L,
            durationMicros = 2_000_000L,
            intensity = 0.9f
        )
        // Before start -> inactive
        assertThat(effect.isActiveAt(500_000L)).isFalse()
        // At start -> active
        assertThat(effect.isActiveAt(1_000_000L)).isTrue()
        // Inside interval -> active
        assertThat(effect.isActiveAt(2_000_000L)).isTrue()
        // At end bound -> inactive
        assertThat(effect.isActiveAt(3_000_000L)).isFalse()
        // After end bound -> inactive
        assertThat(effect.isActiveAt(3_500_000L)).isFalse()
    }
}
