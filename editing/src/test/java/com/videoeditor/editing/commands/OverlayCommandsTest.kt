package com.videoeditor.editing.commands

import com.google.common.truth.Truth.assertThat
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.core.model.OverlayClip
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Timeline
import com.videoeditor.core.model.VisualTransform
import com.videoeditor.editing.history.EditHistory
import org.junit.Test

class OverlayCommandsTest {
    @Test fun `tool edits persist every layer property and undo as one gesture`() {
        val original = videoOverlay()
        val base = baseProject().let { it.copy(timeline = it.timeline.copy(overlayClips = listOf(original))) }
        val edited = original.copy(timelineStartMicros = 2_000_000, sourceStartMicros = 500_000,
            sourceEndMicros = 2_500_000, timelineDurationMicros = 2_000_000, opacity = .4f,
            volume = .7f, muted = true, zIndex = 3,
            transform = VisualTransform(scale = .5f, offsetX = .2f, offsetY = -.3f, rotationDegrees = 45))
        val history = EditHistory()
        val next = history.apply(base, OverlayCommands.UpdateOverlay(original.id, edited))
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val reopened = json.decodeFromString(Project.serializer(), json.encodeToString(Project.serializer(), next))
        assertThat(reopened.timeline.overlayClips).containsExactly(edited)
        assertThat(history.undo(next)).isEqualTo(base)
        assertThat(history.redo(base)).isEqualTo(next)
    }


    private fun baseProject(): Project {
        val main = MediaRef("m1", "content://main/video", MediaType.Video, "Main.mp4")
        val overlay = MediaRef("m2", "content://overlay/video", MediaType.Video, "Overlay.mp4")
        return Project(
            id = "p", name = "P", createdAtMillis = 0, updatedAtMillis = 0,
            mediaRefs = listOf(main, overlay),
            timeline = Timeline(
                videoClips = listOf(
                    Clip(id = "c1", mediaRefId = "m1", sourceStartMicros = 0, sourceEndMicros = 8_000_000),
                ),
            ),
        )
    }

    private fun videoOverlay(
        id: String = "ov1",
        start: Long = 1_000_000,
        duration: Long = 3_000_000,
        sourceStart: Long = 0L,
        sourceEnd: Long = 3_000_000,
        zIndex: Int = 0,
    ) = OverlayClip(
        id = id, mediaRefId = "m2",
        timelineStartMicros = start, timelineDurationMicros = duration,
        sourceStartMicros = sourceStart, sourceEndMicros = sourceEnd, zIndex = zIndex,
    )

    @Test fun `add overlay from library keeps the preexisting media reference on undo`() {
        val history = EditHistory()
        val project = baseProject()
        val overlay = videoOverlay()
        val after = history.apply(project, OverlayCommands.AddOverlay(project.mediaRef("m2")!!, overlay))
        assertThat(after.timeline.overlayClips).containsExactly(overlay)
        val undone = history.undo(after)
        assertThat(undone.timeline.overlayClips).isEmpty()
        assertThat(undone).isEqualTo(project)
    }

    @Test fun `add overlay keeps media ref when main track still uses it`() {
        val history = EditHistory()
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(
                videoClips = p.timeline.videoClips + Clip(id = "c2", mediaRefId = "m2", sourceStartMicros = 0, sourceEndMicros = 1_000_000),
            ))
        }
        val overlay = videoOverlay()
        val after = history.apply(project, OverlayCommands.AddOverlay(project.mediaRef("m2")!!, overlay))
        val undone = history.undo(after)
        assertThat(undone.mediaRefs.map { it.id }).containsExactly("m1", "m2")
    }

    @Test fun `delete and undo restore layer at its original list position`() {
        val history = EditHistory()
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(
                overlayClips = listOf(videoOverlay("a", zIndex = 0), videoOverlay("b", zIndex = 1)),
            ))
        }
        val after = history.apply(project, OverlayCommands.DeleteOverlay("a"))
        assertThat(after.timeline.overlayClips.map { it.id }).containsExactly("b")
        val undone = history.undo(after)
        assertThat(undone.timeline.overlayClips.map { it.id }).containsExactly("a", "b").inOrder()
    }

    @Test fun `duplicate stacks the copy above every existing layer`() {
        val history = EditHistory()
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(
                overlayClips = listOf(videoOverlay("a", zIndex = 0), videoOverlay("b", zIndex = 3)),
            ))
        }
        val after = history.apply(project, OverlayCommands.DuplicateOverlay("a"))
        val copy = after.timeline.overlayClips.single { it.id != "a" && it.id != "b" }
        assertThat(copy.zIndex).isEqualTo(4)
        assertThat(copy.mediaRefId).isEqualTo("m2")
        val undone = history.undo(after)
        assertThat(undone.timeline.overlayClips.map { it.id }).containsExactly("a", "b")
    }

    @Test fun `move clamps to zero and restores on undo`() {
        val history = EditHistory()
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(overlayClips = listOf(videoOverlay())))
        }
        val after = history.apply(project, OverlayCommands.MoveOverlay("ov1", -5_000_000))
        assertThat(after.timeline.overlayClips.single().timelineStartMicros).isEqualTo(0)
        val undone = history.undo(after)
        assertThat(undone.timeline.overlayClips.single().timelineStartMicros).isEqualTo(1_000_000)
    }

    @Test fun `trim resizes source window and timeline duration, undo restores`() {
        val history = EditHistory()
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(overlayClips = listOf(videoOverlay())))
        }
        val after = history.apply(project, OverlayCommands.TrimOverlay("ov1", 500_000, 2_000_000))
        val overlay = after.timeline.overlayClips.single()
        assertThat(overlay.sourceStartMicros).isEqualTo(500_000)
        assertThat(overlay.sourceEndMicros).isEqualTo(2_000_000)
        assertThat(overlay.timelineDurationMicros).isEqualTo(1_500_000)
        val undone = history.undo(after)
        assertThat(undone.timeline.overlayClips.single().sourceDurationMicros).isEqualTo(3_000_000)
    }

    @Test fun `trim is ignored for photo overlays`() {
        val history = EditHistory()
        val photo = videoOverlay().copy(isPhoto = true)
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(overlayClips = listOf(photo)))
        }
        val after = history.apply(project, OverlayCommands.TrimOverlay("ov1", 500_000, 2_000_000))
        assertThat(after.timeline.overlayClips.single()).isEqualTo(photo)
    }

    @Test fun `split divides source and timeline at the same point and merges on undo`() {
        val history = EditHistory()
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(overlayClips = listOf(videoOverlay())))
        }
        // Split at 2s project = 1s into a 3s overlay starting at 1s; source window 0..3s.
        val after = history.apply(project, OverlayCommands.SplitOverlay("ov1", 2_000_000, newId = "ov1b"))
        assertThat(after.timeline.overlayClips.map { it.id }).containsExactly("ov1", "ov1b").inOrder()
        val left = after.timeline.overlayClips[0]
        val right = after.timeline.overlayClips[1]
        assertThat(left.sourceEndMicros).isEqualTo(1_000_000)
        assertThat(left.timelineDurationMicros).isEqualTo(1_000_000)
        assertThat(right.sourceStartMicros).isEqualTo(1_000_000)
        assertThat(right.timelineStartMicros).isEqualTo(2_000_000)
        assertThat(right.timelineDurationMicros).isEqualTo(2_000_000)
        val undone = history.undo(after)
        assertThat(undone.timeline.overlayClips.single()).isEqualTo(videoOverlay())
    }

    @Test fun `split outside the overlay is a no-op`() {
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(overlayClips = listOf(videoOverlay())))
        }
        val after = OverlayCommands.SplitOverlay("ov1", 10_000_000).apply(project)
        assertThat(after).isEqualTo(project)
    }

    @Test fun `transform updates placement and undo restores it`() {
        val history = EditHistory()
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(overlayClips = listOf(videoOverlay())))
        }
        val target = VisualTransform(scale = 0.5f, offsetX = 0.4f, offsetY = -0.3f, rotationDegrees = 45)
        val after = history.apply(project, OverlayCommands.SetOverlayTransform("ov1", target))
        assertThat(after.timeline.overlayClips.single().transform).isEqualTo(target)
        val undone = history.undo(after)
        assertThat(undone.timeline.overlayClips.single().transform).isEqualTo(VisualTransform())
    }

    @Test fun `opacity and z index clamp and restore`() {
        val history = EditHistory()
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(overlayClips = listOf(videoOverlay())))
        }
        var after = history.apply(project, OverlayCommands.SetOverlayOpacity("ov1", 7f))
        assertThat(after.timeline.overlayClips.single().opacity).isEqualTo(1f)
        after = history.apply(after, OverlayCommands.SetOverlayZIndex("ov1", 12))
        assertThat(after.timeline.overlayClips.single().zIndex).isEqualTo(12)
        after = history.undo(after)
        assertThat(after.timeline.overlayClips.single().zIndex).isEqualTo(0)
        after = history.undo(after)
        assertThat(after.timeline.overlayClips.single().opacity).isEqualTo(1f)
    }

    @Test fun `speed rescales timeline duration for video overlays only`() {
        val history = EditHistory()
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(overlayClips = listOf(videoOverlay())))
        }
        val after = history.apply(project, OverlayCommands.SetOverlaySpeed("ov1", 2f))
        val overlay = after.timeline.overlayClips.single()
        assertThat(overlay.playbackSpeed).isEqualTo(2f)
        assertThat(overlay.timelineDurationMicros).isEqualTo(1_500_000)
        val undone = history.undo(after)
        assertThat(undone.timeline.overlayClips.single().timelineDurationMicros).isEqualTo(3_000_000)
    }

    @Test fun `overlay blend mode mask and chroma key support full undo redo`() {
        val history = EditHistory()
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(overlayClips = listOf(videoOverlay())))
        }

        // Blend Mode
        val p1 = history.apply(project, OverlayCommands.SetOverlayBlendMode("ov1", com.videoeditor.core.model.BlendMode.Screen))
        assertThat(p1.timeline.overlayClips.single().blendMode).isEqualTo(com.videoeditor.core.model.BlendMode.Screen)
        val u0 = history.undo(p1)
        assertThat(u0.timeline.overlayClips.single().blendMode).isEqualTo(com.videoeditor.core.model.BlendMode.Normal)

        // Mask
        val mask = com.videoeditor.core.model.LayerMask(
            type = com.videoeditor.core.model.MaskType.Circle,
            feather = 0.1f,
        )
        val p2 = history.apply(p1, OverlayCommands.SetOverlayMask("ov1", mask))
        assertThat(p2.timeline.overlayClips.single().mask).isEqualTo(mask)
        val u1 = history.undo(p2)
        assertThat(u1.timeline.overlayClips.single().mask).isNull()

        // Chroma Key
        val chromaKey = com.videoeditor.core.model.ChromaKeySettings(
            enabled = true,
            tolerance = 0.4f,
        )
        val p3 = history.apply(p2, OverlayCommands.SetOverlayChromaKey("ov1", chromaKey))
        assertThat(p3.timeline.overlayClips.single().chromaKey).isEqualTo(chromaKey)
        val u2 = history.undo(p3)
        assertThat(u2.timeline.overlayClips.single().chromaKey).isNull()
    }

    @Test fun `overlay effects and keyframes support full undo redo`() {
        val history = EditHistory()
        val project = baseProject().let { p ->
            p.copy(timeline = p.timeline.copy(overlayClips = listOf(videoOverlay())))
        }

        val effect = com.videoeditor.core.model.VisualEffect.createDefault(com.videoeditor.core.model.VisualEffectType.GaussianBlur)
        val p1 = history.apply(project, OverlayCommands.AddOverlayEffect("ov1", effect))
        assertThat(p1.timeline.overlayClips.single().effects).containsExactly(effect)

        val updatedEffect = effect.copy(intensity = 0.5f)
        val p2 = history.apply(p1, OverlayCommands.UpdateOverlayEffect("ov1", updatedEffect))
        assertThat(p2.timeline.overlayClips.single().effects.single().intensity).isEqualTo(0.5f)

        val kf = com.videoeditor.core.model.VisualKeyframe("kf1", 500_000L, 0.7f)
        val p3 = history.apply(p2, OverlayCommands.SetOverlayKeyframe("ov1", "intensity", kf))
        assertThat(p3.timeline.overlayClips.single().keyframeTracks["intensity"]?.keyframes).containsExactly(kf)

        val u2 = history.undo(p3)
        assertThat(u2.timeline.overlayClips.single().keyframeTracks["intensity"]).isNull()
        val u1 = history.undo(u2)
        assertThat(u1.timeline.overlayClips.single().effects.single().intensity).isEqualTo(0.8f)
        val u0 = history.undo(u1)
        assertThat(u0.timeline.overlayClips.single().effects).isEmpty()
    }
}

