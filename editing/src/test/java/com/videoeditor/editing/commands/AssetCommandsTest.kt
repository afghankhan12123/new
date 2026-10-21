package com.videoeditor.editing.commands

import com.google.common.truth.Truth.assertThat
import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.MediaType
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.TextOverlay
import com.videoeditor.core.model.Timeline
import org.junit.Test

/**
 * Volume/audio/text commands: every one must be exactly reversible through
 * undo, since they run on the same EditHistory as the structural commands.
 */
class AssetCommandsTest {

    private val refs = listOf(
        MediaRef("rv", "file:///v.mp4", MediaType.Video, "v.mp4"),
        MediaRef("ra", "file:///a.m4a", MediaType.Audio, "a.m4a"),
    )

    private fun project(): Project = Project(
        id = "p",
        name = "p",
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        mediaRefs = refs,
        timeline = Timeline(
            videoClips = listOf(
                Clip("c1", "rv", 0L, 4_000_000L, timelineStartMicros = 0L),
            ),
            audioClips = listOf(
                AudioClip("a1", "ra", 0L, 10_000_000L, timelineStartMicros = 0L),
            ),
            textOverlays = listOf(
                TextOverlay("t1", "Title", startMicros = 0L, durationMicros = 3_000_000L),
            ),
        ),
    )

    @Test
    fun `extraction groups repeated muted extraction and preserves existing detached audio`() {
        val history = com.videoeditor.editing.history.EditHistory()
        val before = project()
        fun extraction(id: String) = com.videoeditor.editing.history.CompositeEditCommand(
            "Extract audio", listOf(
                AssetCommands.AddAudioClip(refs[0], AudioClip(id, "rv", 1_000_000L, 3_000_000L,
                    playbackSpeed = 2f)),
                AssetCommands.SetClipMuted("c1", true),
            ),
        )
        val first = history.apply(before, extraction("detached1"))
        val second = history.apply(first, extraction("detached2"))
        assertThat(history.undo(second)).isEqualTo(first)
        assertThat(history.undo(first)).isEqualTo(before)
        assertThat(history.redo(before)).isEqualTo(first)
        assertThat(history.redo(first)).isEqualTo(second)
        val deleted = history.apply(second, TimelineCommands.DeleteClip("c1"))
        assertThat(deleted.timeline.audioClips).isEqualTo(second.timeline.audioClips)
        assertThat(history.undo(deleted)).isEqualTo(second)
    }

    @Test
    fun `composite ignores duplicate add when reversing mute`() {
        val before = project()
        val command = com.videoeditor.editing.history.CompositeEditCommand("Extract audio", listOf(
            AssetCommands.AddAudioClip(refs[1], before.timeline.audioClips.first()),
            AssetCommands.SetClipMuted("c1", true),
        ))
        assertThat(command.undo(command.apply(before))).isEqualTo(before)
    }

    @Test
    fun `audio split honors speed and undo restores envelopes`() {
        val original = project().copy(timeline = project().timeline.copy(audioClips = listOf(
            AudioClip("a1", "ra", 1_000_000L, 9_000_000L, 2_000_000L, playbackSpeed = 2f, fadeInMicros = 500_000L, fadeOutMicros = 500_000L))))
        val command = AssetCommands.SplitAudioClip("a1", 4_000_000L, "right")
        val result = command.apply(original)
        assertThat(result.timeline.audioClips.map { it.sourceEndMicros }).containsExactly(5_000_000L, 9_000_000L).inOrder()
        assertThat(result.timeline.audioClips[1].sourceStartMicros).isEqualTo(5_000_000L)
        assertThat(result.timeline.audioClips[1].timelineStartMicros).isEqualTo(4_000_000L)
        assertThat(command.undo(result)).isEqualTo(original)
    }

    @Test
    fun `photo duration repacks subsequent clips and undoes exactly`() {
        val original = project().copy(timeline = project().timeline.copy(videoClips = listOf(
            Clip("photo", "rv", photoDurationMicros = 3_000_000L), Clip("after", "rv", 0L, 4_000_000L, 3_000_000L))))
        val command = TimelineCommands.SetPhotoDuration("photo", 5_000_000L)
        val result = command.apply(original)
        assertThat(result.timeline.videoClips[1].timelineStartMicros).isEqualTo(5_000_000L)
        assertThat(command.undo(result)).isEqualTo(original)
    }

    @Test
    fun `pixel transforms survive undo without modifying source media`() {
        val original = project()
        val transform = com.videoeditor.core.model.VisualTransform(rotationDegrees = 90,
            flipHorizontal = true, scale = 1.2f, offsetX = 0.2f,
            crop = com.videoeditor.core.model.CropRect(0.1f, 0.2f, 0.8f, 0.9f))
        val command = AssetCommands.SetVisualTransform("c1", transform)
        val result = command.apply(original)
        assertThat(result.timeline.videoClips[0].transform).isEqualTo(transform)
        assertThat(result.mediaRefs).isEqualTo(original.mediaRefs)
        assertThat(command.undo(result)).isEqualTo(original)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid crop is rejected before mutation`() {
        AssetCommands.SetVisualTransform("c1", com.videoeditor.core.model.VisualTransform(
            crop = com.videoeditor.core.model.CropRect(0.8f, 0f, 0.2f, 1f))).apply(project())
    }

    @Test
    fun `set clip volume applies and undoes`() {
        val command = AssetCommands.SetClipVolume("c1", 0.3f)
        val applied = command.apply(project())
        assertThat(applied.timeline.videoClips[0].volume).isEqualTo(0.3f)
        val undone = command.undo(applied)
        assertThat(undone.timeline.videoClips[0].volume).isEqualTo(1f)
    }

    @Test
    fun `volume is clamped to a sane range`() {
        val applied = AssetCommands.SetClipVolume("c1", 99f).apply(project())
        assertThat(applied.timeline.videoClips[0].volume).isEqualTo(4f)
        val negative = AssetCommands.SetClipVolume("c1", -5f).apply(project())
        assertThat(negative.timeline.videoClips[0].volume).isEqualTo(0f)
    }

    @Test
    fun `mute applies and undoes`() {
        val command = AssetCommands.SetClipMuted("c1", true)
        val applied = command.apply(project())
        assertThat(applied.timeline.videoClips[0].muted).isTrue()
        assertThat(command.undo(applied).timeline.videoClips[0].muted).isFalse()
    }

    @Test
    fun `add audio clip is reversible and registers a new media ref`() {
        val newRef = MediaRef("r-new", "file:///new.m4a", MediaType.Audio, "new.m4a")
        val clip = AudioClip("a2", "r-new", 0L, 5_000_000L, timelineStartMicros = 2_000_000L)
        val command = AssetCommands.AddAudioClip(newRef, clip)
        val applied = command.apply(project())
        assertThat(applied.timeline.audioClips).hasSize(2)
        assertThat(applied.timeline.audioClips.last().id).isEqualTo("a2")
        // Without the ref registered, CompositionBuilder would silently skip
        // the clip — apply must add both together.
        assertThat(applied.mediaRef("r-new")).isEqualTo(newRef)
        val undone = command.undo(applied)
        assertThat(undone.timeline.audioClips).hasSize(1)
        assertThat(undone.mediaRef("r-new")).isNull()
    }

    @Test
    fun `add audio with existing media ref does not duplicate it and undo keeps it`() {
        val clip = AudioClip("a2", "ra", 0L, 5_000_000L)
        val command = AssetCommands.AddAudioClip(refs[1], clip)
        val applied = command.apply(project())
        assertThat(applied.mediaRefs.filter { it.id == "ra" }).hasSize(1)
        // "ra" is still used by a1 after undo — the ref must survive.
        val undone = command.undo(applied)
        assertThat(undone.mediaRef("ra")).isNotNull()
    }

    @Test
    fun `add duplicate audio id is ignored`() {
        val duplicate = AudioClip("a1", "ra", 0L, 5_000_000L)
        val applied = AssetCommands.AddAudioClip(refs[1], duplicate).apply(project())
        assertThat(applied.timeline.audioClips).hasSize(1)
    }

    @Test
    fun `delete audio clip restores it at its index`() {
        val base = project()
        val withTwo = AssetCommands.AddAudioClip(
            refs[1],
            AudioClip("a2", "ra", 0L, 5_000_000L),
        ).apply(base)

        val command = AssetCommands.DeleteAudioClip("a1")
        val applied = command.apply(withTwo)
        assertThat(applied.timeline.audioClips.map { it.id }).containsExactly("a2")
        val undone = command.undo(applied)
        assertThat(undone.timeline.audioClips.map { it.id }).containsExactly("a1", "a2").inOrder()
    }

    @Test
    fun `audio volume and trim are reversible`() {
        val volume = AssetCommands.SetAudioVolume("a1", 0.5f)
        val withVolume = volume.apply(project())
        assertThat(withVolume.timeline.audioClips[0].volume).isEqualTo(0.5f)
        assertThat(volume.undo(withVolume).timeline.audioClips[0].volume).isEqualTo(1f)

        val trim = AssetCommands.TrimAudioClip("a1", 1_000_000L, 6_000_000L)
        val trimmed = trim.apply(project())
        assertThat(trimmed.timeline.audioClips[0].sourceStartMicros).isEqualTo(1_000_000L)
        assertThat(trimmed.timeline.audioClips[0].sourceEndMicros).isEqualTo(6_000_000L)
        val restored = trim.undo(trimmed)
        assertThat(restored.timeline.audioClips[0].sourceStartMicros).isEqualTo(0L)
        assertThat(restored.timeline.audioClips[0].sourceEndMicros).isEqualTo(10_000_000L)
    }

    @Test
    fun `text add delete and edit are reversible`() {
        val overlay = TextOverlay("t2", "Second", startMicros = 1_000_000L)
        val add = AssetCommands.AddTextOverlay(overlay)
        val added = add.apply(project())
        assertThat(added.timeline.textOverlays).hasSize(2)
        assertThat(add.undo(added).timeline.textOverlays).hasSize(1)

        val edit = AssetCommands.SetText("t1", "Renamed")
        val edited = edit.apply(project())
        assertThat(edited.timeline.textOverlays[0].text).isEqualTo("Renamed")
        assertThat(edit.undo(edited).timeline.textOverlays[0].text).isEqualTo("Title")

        val delete = AssetCommands.DeleteTextOverlay("t1")
        val deleted = delete.apply(project())
        assertThat(deleted.timeline.textOverlays).isEmpty()
        assertThat(delete.undo(deleted).timeline.textOverlays.map { it.id }).containsExactly("t1")
    }

    @Test
    fun `move audio clip is reversible and clamps to zero`() {
        val command = AssetCommands.MoveAudioClip("a1", 5_000_000L)
        val applied = command.apply(project())
        assertThat(applied.timeline.audioClips[0].timelineStartMicros).isEqualTo(5_000_000L)
        assertThat(command.undo(applied).timeline.audioClips[0].timelineStartMicros).isEqualTo(0L)

        val negative = AssetCommands.MoveAudioClip("a1", -3_000_000L).apply(project())
        assertThat(negative.timeline.audioClips[0].timelineStartMicros).isEqualTo(0L)
    }

    @Test
    fun `move text overlay is reversible and clamps to zero`() {
        val command = AssetCommands.MoveTextOverlay("t1", 4_000_000L)
        val applied = command.apply(project())
        assertThat(applied.timeline.textOverlays[0].startMicros).isEqualTo(4_000_000L)
        assertThat(command.undo(applied).timeline.textOverlays[0].startMicros).isEqualTo(0L)

        val negative = AssetCommands.MoveTextOverlay("t1", -1L).apply(project())
        assertThat(negative.timeline.textOverlays[0].startMicros).isEqualTo(0L)
    }

    @Test
    fun `asset commands never repack the video track`() {
        // Volume changes must not shift any clip's timeline position.
        val before = project().timeline.videoClips
        val after = AssetCommands.SetClipVolume("c1", 0.2f)
            .apply(project()).timeline.videoClips
        assertThat(after.map { it.timelineStartMicros })
            .isEqualTo(before.map { it.timelineStartMicros })
        assertThat(after.map { it.sourceEndMicros })
            .isEqualTo(before.map { it.sourceEndMicros })
    }
    @Test
    fun `relink preserves edits and undo restores original reference`() {
        val original = project()
        val replacement = refs.first().copy(id = "new", uri = "content://replacement/video")
        val command = AssetCommands.RelinkMedia("rv", replacement)
        val relinked = command.apply(original)
        assertThat(relinked.timeline).isEqualTo(original.timeline)
        assertThat(relinked.mediaRef("rv")?.uri).isEqualTo(replacement.uri)
        assertThat(relinked.mediaRef("rv")?.originalUri).isEqualTo(original.mediaRef("rv")?.uri)
        assertThat(command.undo(relinked)).isEqualTo(original)
    }

    @Test
    fun `clip visual effects add update reorder and remove support full undo redo`() {
        val history = com.videoeditor.editing.history.EditHistory()
        val p0 = project()

        val effect1 = com.videoeditor.core.model.VisualEffect.createDefault(com.videoeditor.core.model.VisualEffectType.Exposure)
        val effect2 = com.videoeditor.core.model.VisualEffect.createDefault(com.videoeditor.core.model.VisualEffectType.Vignette)

        val p1 = history.apply(p0, AssetCommands.AddClipEffect("c1", effect1))
        assertThat(p1.timeline.videoClips.first().effects).containsExactly(effect1)

        val p2 = history.apply(p1, AssetCommands.AddClipEffect("c1", effect2))
        assertThat(p2.timeline.videoClips.first().effects).containsExactly(effect1, effect2).inOrder()

        val updatedEffect1 = effect1.copy(intensity = 0.75f)
        val p3 = history.apply(p2, AssetCommands.UpdateClipEffect("c1", updatedEffect1))
        assertThat(p3.timeline.videoClips.first().effects.first().intensity).isEqualTo(0.75f)

        val p4 = history.apply(p3, AssetCommands.ReorderClipEffects("c1", fromIndex = 0, toIndex = 1))
        assertThat(p4.timeline.videoClips.first().effects.map { it.id }).containsExactly(effect2.id, effect1.id).inOrder()

        val p5 = history.apply(p4, AssetCommands.RemoveClipEffect("c1", effect2.id))
        assertThat(p5.timeline.videoClips.first().effects.map { it.id }).containsExactly(effect1.id)

        // Undo back to p0
        val u4 = history.undo(p5)
        assertThat(u4.timeline.videoClips.first().effects.map { it.id }).containsExactly(effect2.id, effect1.id)
        val u3 = history.undo(u4)
        assertThat(u3.timeline.videoClips.first().effects.first().intensity).isEqualTo(0.75f)
        val u2 = history.undo(u3)
        assertThat(u2.timeline.videoClips.first().effects.first().intensity).isEqualTo(1.0f)
        val u1 = history.undo(u2)
        assertThat(u1.timeline.videoClips.first().effects).containsExactly(effect1)
        val u0 = history.undo(u1)
        assertThat(u0.timeline.videoClips.first().effects).isEmpty()
    }

    @Test
    fun `clip keyframe add and remove support full undo redo`() {
        val history = com.videoeditor.editing.history.EditHistory()
        val p0 = project()

        val kf1 = com.videoeditor.core.model.VisualKeyframe("kf1", 0L, 0.2f)
        val kf2 = com.videoeditor.core.model.VisualKeyframe("kf2", 1_000_000L, 0.8f)

        val p1 = history.apply(p0, AssetCommands.SetClipKeyframe("c1", "exposure", kf1))
        assertThat(p1.timeline.videoClips.first().keyframeTracks["exposure"]?.keyframes).containsExactly(kf1)

        val p2 = history.apply(p1, AssetCommands.SetClipKeyframe("c1", "exposure", kf2))
        assertThat(p2.timeline.videoClips.first().keyframeTracks["exposure"]?.keyframes).containsExactly(kf1, kf2).inOrder()

        val p3 = history.apply(p2, AssetCommands.RemoveClipKeyframe("c1", "exposure", "kf1"))
        assertThat(p3.timeline.videoClips.first().keyframeTracks["exposure"]?.keyframes).containsExactly(kf2)

        val u2 = history.undo(p3)
        assertThat(u2.timeline.videoClips.first().keyframeTracks["exposure"]?.keyframes).containsExactly(kf1, kf2)
        val u1 = history.undo(u2)
        assertThat(u1.timeline.videoClips.first().keyframeTracks["exposure"]?.keyframes).containsExactly(kf1)
        val u0 = history.undo(u1)
        assertThat(u0.timeline.videoClips.first().keyframeTracks["exposure"]).isNull()
    }
}

