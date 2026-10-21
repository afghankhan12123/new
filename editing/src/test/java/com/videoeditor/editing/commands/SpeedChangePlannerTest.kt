package com.videoeditor.editing.commands

import com.videoeditor.core.model.*
import com.videoeditor.core.model.render.ProjectRenderPlan
import com.videoeditor.editing.history.EditHistory
import org.junit.Assert.*
import org.junit.Test

class SpeedChangePlannerTest {
    private fun project(handleUs: Long = 2_000_000): Project {
        val ref = MediaRef("source", "content://fixture", MediaType.Video, "fixture", durationMicros = 20_000_000)
        val key = VisualKeyframe("key", 1_000_000, .4f)
        return Project("p", "p", 0, 0, mediaRefs = listOf(ref), timeline = Timeline(
            videoClips = listOf(Clip("a", ref.id, sourceStartMicros = 1_000_000, sourceEndMicros = 5_000_000,
                fadeOutMicros = 500_000, effects = listOf(VisualEffect("fx", VisualEffectType.Vignette,
                    startTimeMicros = 200_000, durationMicros = 2_000_000, keyframes = listOf(key))),
                keyframeTracks = mapOf("scale" to KeyframeTrack("scale", listOf(key)))),
                Clip("b", ref.id, sourceStartMicros = handleUs, sourceEndMicros = 10_000_000, timelineStartMicros = 4_000_000)),
            transitions = listOf(Transition("join", "a", "b", 1_000_000)),
            audioClips = listOf(AudioClip("music", ref.id, timelineStartMicros = 100_000, sourceEndMicros = 8_000_000)),
            textOverlays = listOf(TextOverlay("caption", "caption", startMicros = 500_000))))
    }
    @Test fun valid_speed_preserves_join_ids_sources_free_tracks_and_retimes_local_events() {
        val base = project(); val plan = SpeedChangePlanner.plan(base, "a", 2f)
        assertFalse(plan.needsTransitionAdjustment)
        val clip = plan.after.timeline.videoClips.first()
        assertEquals(base.timeline.transitions, plan.after.timeline.transitions)
        assertEquals(base.timeline.audioClips, plan.after.timeline.audioClips)
        assertEquals(base.timeline.textOverlays, plan.after.timeline.textOverlays)
        assertEquals(5_000_000L, clip.sourceEndMicros)
        assertEquals(500_000L, clip.keyframeTracks.getValue("scale").keyframes.single().timeMicros)
        assertEquals(100_000L, clip.effects.single().startTimeMicros)
        assertEquals(1_000_000L, clip.effects.single().durationMicros)
        assertEquals(250_000L, clip.fadeOutMicros)
        assertEquals(2_000_000L, plan.after.timeline.videoClips[1].timelineStartMicros)
        ProjectRenderPlan.compile(plan.after)
    }
    @Test fun insufficient_handles_propose_shorter_duration_without_deleting_join_or_trimming_source() {
        val base = project(handleUs = 600_000)
        val plan = SpeedChangePlanner.plan(base, "b", 2f)
        assertTrue(plan.needsTransitionAdjustment)
        val join = plan.after.timeline.transitions.single()
        assertEquals("join", join.id)
        assertTrue(join.durationMicros in 599_990L..600_001L)
        assertEquals(base.timeline.videoClips[1].sourceStartMicros, plan.after.timeline.videoClips[1].sourceStartMicros)
        ProjectRenderPlan.compile(plan.after)
    }
    @Test fun direct_command_cannot_silently_prune_transition_on_speed_conflict() {
        val base = project(handleUs = 600_000)
        val history = EditHistory()
        assertThrows(IllegalArgumentException::class.java) { history.apply(base, TimelineCommands.SetSpeed("b", 2f)) }
        assertFalse(history.canUndo)
    }
    @Test fun speed_adjustment_undo_redo_is_an_exact_timeline_transaction() {
        val base = project(handleUs = 600_000)
        val plan = SpeedChangePlanner.plan(base, "b", 2f)
        val history = EditHistory()
        val changed = history.apply(base, SpeedChangePlanner.Apply(plan))
        assertEquals(base, history.undo(changed))
        assertEquals(changed, history.redo(base))
    }
    @Test fun stale_review_is_rejected_before_history_mutates() {
        val base = project(); val plan = SpeedChangePlanner.plan(base, "a", 1.5f)
        val modified = base.copy(timeline = base.timeline.copy(textOverlays = emptyList()))
        assertThrows(IllegalArgumentException::class.java) { SpeedChangePlanner.Apply(plan).apply(modified) }
    }
    @Test fun speeding_short_middle_clip_recalculates_both_neighbor_windows_together() {
        val base = project()
        val a = base.timeline.videoClips[0]
        val b = base.timeline.videoClips[1].copy(sourceStartMicros = 2_000_000, sourceEndMicros = 3_000_000)
        val c = Clip("c", a.mediaRefId, sourceStartMicros = 2_000_000, sourceEndMicros = 6_000_000, timelineStartMicros = 5_000_000)
        val original = base.copy(timeline = base.timeline.copy(videoClips = listOf(a, b, c), transitions = listOf(
            Transition("ab", "a", "b", 900_000), Transition("bc", "b", "c", 900_000))))
        ProjectRenderPlan.compile(original)
        val plan = SpeedChangePlanner.plan(original, "b", 2f)
        assertEquals(2, plan.shortened.size)
        assertEquals(listOf("ab", "bc"), plan.after.timeline.transitions.map { it.id })
        val windows = plan.after.resolveTransitions()
        assertTrue(windows[0].endMicros <= windows[1].startMicros)
        ProjectRenderPlan.compile(plan.after)
    }
    @Test fun failed_redo_preserves_history_ownership() {
        val base = project()
        var reject = false
        val command = object : com.videoeditor.editing.history.EditCommand {
            override val description = "fixture"
            override fun apply(project: Project): Project {
                require(!reject)
                return project.copy(name = "changed")
            }
            override fun undo(project: Project) = project.copy(name = base.name)
        }
        val history = EditHistory()
        history.undo(history.apply(base, command))
        reject = true
        assertThrows(IllegalArgumentException::class.java) { history.redo(base) }
        assertTrue(history.canRedo)
        assertFalse(history.canUndo)
        reject = false
        assertEquals("changed", history.redo(base).name)
    }

}
