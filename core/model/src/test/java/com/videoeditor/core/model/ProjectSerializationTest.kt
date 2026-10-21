package com.videoeditor.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class ProjectSerializationTest {
    @Test fun `legacy captions default to normal type and new styles survive reopening`() {
        val caption = Json.decodeFromString<TextOverlay>("""{"id":"legacy","text":"Caption"}""")
        assertThat(caption.bold).isFalse()
        assertThat(caption.italic).isFalse()
        val styled = caption.copy(bold = true, italic = true, fontId = "serif")
        assertThat(Json.decodeFromString<TextOverlay>(Json.encodeToString(styled))).isEqualTo(styled)
    }

    @Test fun `legacy documents without relink provenance preserve their source and edits`() {
        val project = Json.decodeFromString<Project>("""{"id":"legacy","name":"Original","createdAtMillis":0,"updatedAtMillis":0,"mediaRefs":[{"id":"video","uri":"content://original/video","type":"Video","displayName":"Original.mp4"}],"timeline":{"videoClips":[{"id":"clip","mediaRefId":"video","sourceStartMicros":2000000,"sourceEndMicros":8000000,"playbackSpeed":2.0}],"audioClips":[],"textOverlays":[]}}""")
        assertThat(project.mediaRefs.single().uri).isEqualTo("content://original/video")
        assertThat(project.mediaRefs.single().originalUri).isNull()
        assertThat(project.timeline.videoClips.single().sourceStartMicros).isEqualTo(2_000_000)
        assertThat(project.timeline.renderedDurationMicros).isEqualTo(3_000_000)
    }

    @Test fun `relink provenance survives serialization and reopening`() {
        val original = MediaRef("video", "content://replacement/video", MediaType.Video, "Replacement.mp4", originalUri = "content://original/video")
        val reopened = Json.decodeFromString<MediaRef>(Json.encodeToString(original))
        assertThat(reopened).isEqualTo(original)
    }

    @Test fun `stage 9 effects masks chroma key and blend modes survive round-trip serialization`() {
        val effect = VisualEffect.createDefault(VisualEffectType.Vignette).copy(
            intensity = 0.8f,
            parameters = mapOf("radius" to 0.75f, "feather" to 0.4f),
        )
        val mask = LayerMask(
            type = MaskType.RoundedRectangle,
            cornerRadius = 0.35f,
            feather = 0.12f,
            invert = true,
            keyframeTracks = mapOf(
                "scaleX" to KeyframeTrack(
                    propertyName = "scaleX",
                    keyframes = listOf(
                        VisualKeyframe("kf1", 0L, 0.5f, KeyframeInterpolation.Linear),
                        VisualKeyframe("kf2", 1_000_000L, 1.2f, KeyframeInterpolation.Smooth),
                    ),
                ),
            ),
        )
        val chromaKey = ChromaKeySettings(
            enabled = true,
            keyColor = ChromaKeySettings.PRESET_GREEN,
            tolerance = 0.42f,
            softness = 0.22f,
            spillSuppression = 0.75f,
        )
        val overlay = OverlayClip(
            id = "ov1",
            mediaRefId = "video1",
            timelineStartMicros = 500_000L,
            timelineDurationMicros = 3_000_000L,
            effects = listOf(effect),
            mask = mask,
            chromaKey = chromaKey,
            blendMode = BlendMode.Screen,
        )
        val clip = Clip(
            id = "c1",
            mediaRefId = "video1",
            sourceStartMicros = 0L,
            sourceEndMicros = 5_000_000L,
            effects = listOf(
                VisualEffect.createDefault(VisualEffectType.Exposure).copy(intensity = 0.5f),
                VisualEffect.createDefault(VisualEffectType.PresetCinematic),
            ),
        )
        val project = Project(
            id = "p1",
            name = "Stage 9 Test Project",
            createdAtMillis = 1000L,
            updatedAtMillis = 2000L,
            timeline = Timeline(
                videoClips = listOf(clip),
                overlayClips = listOf(overlay),
            ),
        )

        val json = Json.encodeToString(project)
        val restored = Json.decodeFromString<Project>(json)

        assertThat(restored.timeline.videoClips.first().effects).hasSize(2)
        assertThat(restored.timeline.videoClips.first().effects[0].type).isEqualTo(VisualEffectType.Exposure)
        assertThat(restored.timeline.videoClips.first().effects[0].intensity).isEqualTo(0.5f)
        assertThat(restored.timeline.videoClips.first().effects[1].type).isEqualTo(VisualEffectType.PresetCinematic)

        val restoredOverlay = restored.timeline.overlayClips.first()
        assertThat(restoredOverlay.blendMode).isEqualTo(BlendMode.Screen)
        assertThat(restoredOverlay.effects).hasSize(1)
        assertThat(restoredOverlay.effects.first().type).isEqualTo(VisualEffectType.Vignette)
        assertThat(restoredOverlay.effects.first().parameters["radius"]).isEqualTo(0.75f)

        assertThat(restoredOverlay.mask).isNotNull()
        assertThat(restoredOverlay.mask?.type).isEqualTo(MaskType.RoundedRectangle)
        assertThat(restoredOverlay.mask?.cornerRadius).isEqualTo(0.35f)
        assertThat(restoredOverlay.mask?.invert).isTrue()
        assertThat(restoredOverlay.mask?.keyframeTracks?.get("scaleX")?.keyframes).hasSize(2)
        assertThat(restoredOverlay.mask?.evaluateProperty("scaleX", 500_000L, 0f)).isGreaterThan(0.5f)

        assertThat(restoredOverlay.chromaKey).isNotNull()
        assertThat(restoredOverlay.chromaKey?.enabled).isTrue()
        assertThat(restoredOverlay.chromaKey?.tolerance).isEqualTo(0.42f)
        assertThat(restoredOverlay.chromaKey?.spillSuppression).isEqualTo(0.75f)
    }
}

