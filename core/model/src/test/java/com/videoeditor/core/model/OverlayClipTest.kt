package com.videoeditor.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class OverlayClipTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `legacy documents without overlays keep their edits and gain an empty layer list`() {
        val project = json.decodeFromString<Project>(
            """{"id":"legacy","name":"Original","createdAtMillis":0,"updatedAtMillis":0,""" +
                """"mediaRefs":[{"id":"video","uri":"content://original/video","type":"Video","displayName":"Original.mp4"}],""" +
                """"timeline":{"videoClips":[{"id":"clip","mediaRefId":"video","sourceStartMicros":2000000,"sourceEndMicros":8000000,"playbackSpeed":2.0}],""" +
                """"audioClips":[],"textOverlays":[]}}""",
        )
        assertThat(project.timeline.overlayClips).isEmpty()
        assertThat(project.timeline.renderedDurationMicros).isEqualTo(3_000_000)
        assertThat(project.timeline.totalDurationMicros).isEqualTo(3_000_000)
    }

    @Test fun `overlay round-trips through the project document`() {
        val overlay = OverlayClip(
            id = "ov1",
            mediaRefId = "video2",
            timelineStartMicros = 1_000_000,
            timelineDurationMicros = 2_500_000,
            sourceStartMicros = 500_000,
            sourceEndMicros = 3_000_000,
            playbackSpeed = 1.5f,
            volume = 0.5f,
            opacity = 0.75f,
            zIndex = 2,
            transform = VisualTransform(
                rotationDegrees = 90,
                flipHorizontal = true,
                scale = 0.4f,
                offsetX = 0.5f,
                offsetY = -0.25f,
                crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f),
            ),
        )
        val timeline = Timeline(
            videoClips = listOf(
                Clip(id = "main", mediaRefId = "video1", sourceStartMicros = 0, sourceEndMicros = 10_000_000),
            ),
            overlayClips = listOf(overlay),
        )
        val project = Project("p", "P", 0, 0, timeline = timeline)
        val reopened = json.decodeFromString<Project>(json.encodeToString(project))
        assertThat(reopened.timeline.overlayClips.single()).isEqualTo(overlay)
        assertThat(reopened.timeline.videoClips.single().id).isEqualTo("main")
    }

    @Test fun `overlay source-time mapping honors trim and speed like main clips`() {
        val overlay = OverlayClip(
            id = "ov1",
            mediaRefId = "v",
            timelineStartMicros = 2_000_000,
            timelineDurationMicros = 1_000_000,
            sourceStartMicros = 4_000_000,
            sourceEndMicros = 6_000_000,
            playbackSpeed = 2f,
        )
        assertThat(overlay.sourceTimeAt(2_000_000)).isEqualTo(4_000_000)
        assertThat(overlay.sourceTimeAt(2_500_000)).isEqualTo(5_000_000)
        assertThat(overlay.timelineEndMicros).isEqualTo(3_000_000)
    }

    @Test fun `total duration includes overlay ends but rendered duration stays visual-track based`() {
        val timeline = Timeline(
            videoClips = listOf(
                Clip(id = "main", mediaRefId = "video1", sourceStartMicros = 0, sourceEndMicros = 4_000_000),
            ),
            overlayClips = listOf(
                OverlayClip(
                    id = "ov1", mediaRefId = "video2",
                    timelineStartMicros = 1_000_000, timelineDurationMicros = 2_000_000,
                    sourceStartMicros = 0, sourceEndMicros = 2_000_000,
                ),
            ),
        )
        assertThat(timeline.renderedDurationMicros).isEqualTo(4_000_000)
        assertThat(timeline.totalDurationMicros).isEqualTo(4_000_000)
    }

    @Test fun `z index orders stacking independent of list order`() {
        val bottom = OverlayClip("a", "m", zIndex = 5, timelineDurationMicros = 1)
        val top = OverlayClip("b", "m", zIndex = 9, timelineDurationMicros = 1)
        val timeline = Timeline(overlayClips = listOf(top, bottom))
        assertThat(timeline.overlayClips.sortedBy { it.zIndex }.map { it.id })
            .containsExactly("a", "b").inOrder()
    }
}
