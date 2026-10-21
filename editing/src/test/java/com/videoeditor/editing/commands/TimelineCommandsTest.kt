package com.videoeditor.editing.commands

import com.google.common.truth.Truth.assertThat
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Timeline
import com.videoeditor.editing.history.EditHistory
import org.junit.Test

class TimelineCommandsTest {

    private fun clip(
        id: String,
        sourceStart: Long = 0L,
        sourceEnd: Long = 4_000_000L,
        speed: Float = 1f,
    ) = Clip(
        id = id,
        mediaRefId = "ref-$id",
        sourceStartMicros = sourceStart,
        sourceEndMicros = sourceEnd,
    )

    private fun project(vararg clips: Clip): Project {
        var cursor = 0L
        val packed = clips.map { c ->
            c.copy(timelineStartMicros = cursor).also { cursor += it.timelineDurationMicros }
        }
        return Project(
            id = "p",
            name = "p",
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
            mediaRefs = packed.map { c -> com.videoeditor.core.model.MediaRef(c.mediaRefId, "content://fixture/${c.id}",
                com.videoeditor.core.model.MediaType.Video, c.id, durationMicros = c.sourceEndMicros) },
            timeline = Timeline(videoClips = packed.toList()),
        )
    }

    @Test
    fun `split divides clip into two contiguous clips`() {
        val p = project(clip("a", sourceEnd = 10_000_000L))
        val split = TimelineCommands.SplitClip("a", atProjectTimeMicros = 4_000_000L)
        val result = split.apply(p)

        assertThat(result.timeline.videoClips).hasSize(2)
        val left = result.timeline.videoClips[0]
        val right = result.timeline.videoClips[1]
        assertThat(left.sourceEndMicros).isEqualTo(4_000_000L)
        assertThat(right.sourceStartMicros).isEqualTo(4_000_000L)
        assertThat(right.timelineStartMicros).isEqualTo(left.timelineDurationMicros)
        // Total duration preserved.
        assertThat(result.timeline.videoTrackDurationMicros).isEqualTo(10_000_000L)
    }

    @Test
    fun `split undo merges back into one clip`() {
        val p = project(clip("a", sourceEnd = 10_000_000L))
        val split = TimelineCommands.SplitClip("a", atProjectTimeMicros = 4_000_000L)
        val applied = split.apply(p)
        val undone = split.undo(applied)

        assertThat(undone.timeline.videoClips).hasSize(1)
        assertThat(undone.timeline.videoClips[0].sourceEndMicros).isEqualTo(10_000_000L)
    }

    @Test
    fun `split outside clip bounds is a no-op`() {
        val p = project(clip("a", sourceEnd = 4_000_000L))
        val split = TimelineCommands.SplitClip("a", atProjectTimeMicros = 99_000_000L)
        val result = split.apply(p)
        assertThat(result.timeline.videoClips).hasSize(1)
    }

    @Test
    fun `delete removes clip and repacks`() {
        val p = project(clip("a"), clip("b"), clip("c"))
        val del = TimelineCommands.DeleteClip("b")
        val result = del.apply(p)

        assertThat(result.timeline.videoClips.map { it.id }).containsExactly("a", "c").inOrder()
        // Contiguous: c starts where a ends.
        val a = result.timeline.videoClips[0]
        val c = result.timeline.videoClips[1]
        assertThat(c.timelineStartMicros).isEqualTo(a.timelineEndMicros)
    }

    @Test
    fun `delete undo restores clip at original index`() {
        val p = project(clip("a"), clip("b"), clip("c"))
        val del = TimelineCommands.DeleteClip("b")
        val applied = del.apply(p)
        val undone = del.undo(applied)
        assertThat(undone.timeline.videoClips.map { it.id }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `move reorders clips`() {
        val p = project(clip("a"), clip("b"), clip("c"))
        val move = TimelineCommands.MoveClip("c", toIndex = 0)
        val result = move.apply(p)
        assertThat(result.timeline.videoClips.map { it.id }).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun `duplicate inserts copy after original`() {
        val p = project(clip("a"), clip("b"))
        val dup = TimelineCommands.DuplicateClip("a")
        val result = dup.apply(p)
        assertThat(result.timeline.videoClips).hasSize(3)
        assertThat(result.timeline.videoClips[1].mediaRefId).isEqualTo("ref-a")
        assertThat(result.timeline.videoClips[1].id).isNotEqualTo("a")
    }

    @Test
    fun `speed change halves timeline duration at 2x`() {
        val p = project(clip("a", sourceEnd = 4_000_000L))
        val speed = TimelineCommands.SetSpeed("a", 2f)
        val result = speed.apply(p)
        assertThat(result.timeline.videoClips[0].timelineDurationMicros).isEqualTo(2_000_000L)
    }

    @Test
    fun `speed undo restores duration`() {
        val p = project(clip("a", sourceEnd = 4_000_000L))
        val speed = TimelineCommands.SetSpeed("a", 2f)
        val applied = speed.apply(p)
        val undone = speed.undo(applied)
        assertThat(undone.timeline.videoClips[0].timelineDurationMicros).isEqualTo(4_000_000L)
    }

    @Test
    fun `full undo redo cycle through history`() {
        val history = EditHistory()
        var p = project(clip("a"), clip("b"))
        val originalIds = p.timeline.videoClips.map { it.id }

        p = history.apply(p, TimelineCommands.DeleteClip("b"))
        assertThat(p.timeline.videoClips).hasSize(1)

        p = history.undo(p)
        assertThat(p.timeline.videoClips.map { it.id }).isEqualTo(originalIds)
    }

    @Test
    fun `append clips adds to end and undoes correctly`() {
        val history = EditHistory()
        var p = project(clip("a", sourceEnd = 2_000_000L))
        val newClip = clip("b", sourceEnd = 3_000_000L)
        val ref = com.videoeditor.core.model.MediaRef(
            id = "ref-b",
            uri = "content://media/b",
            type = com.videoeditor.core.model.MediaType.Video,
            displayName = "b.mp4",
            durationMicros = 3_000_000L,
        )

        p = history.apply(p, TimelineCommands.AppendClips(listOf(ref), listOf(newClip)))
        assertThat(p.timeline.videoClips).hasSize(2)
        assertThat(p.timeline.videoClips[1].id).isEqualTo("b")
        assertThat(p.timeline.videoClips[1].timelineStartMicros).isEqualTo(2_000_000L)
        assertThat(p.timeline.videoTrackDurationMicros).isEqualTo(5_000_000L)
        assertThat(p.mediaRefs).contains(ref)

        p = history.undo(p)
        assertThat(p.timeline.videoClips).hasSize(1)
        assertThat(p.timeline.videoClips[0].id).isEqualTo("a")
        assertThat(p.timeline.videoTrackDurationMicros).isEqualTo(2_000_000L)

        p = history.redo(p)
        assertThat(p.timeline.videoClips).hasSize(2)
        assertThat(p.timeline.videoClips[1].id).isEqualTo("b")
    }
}

