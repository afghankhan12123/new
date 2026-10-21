package com.videoeditor.editing.commands

import com.videoeditor.core.model.*
import com.videoeditor.editing.history.EditHistory
import org.junit.Assert.*
import org.junit.Test

class TransitionCommandsTest {
    private fun project(): Project = Project("p", "p", 0, 0,
        mediaRefs = listOf("a", "b", "c").map { MediaRef(it, "file:///$it", MediaType.Video, it, durationMicros = 10_000_000) },
        timeline = Timeline(videoClips = listOf("a", "b", "c").mapIndexed { i,id -> Clip(id,id,2_000_000,5_000_000,i*3_000_000L) }))
    private val join = Transition("join", "a", "b", 1_000_000)
    @Test fun set_replace_remove_undo_redo_use_existing_history() {
        val h = EditHistory(); val p = project()
        val a = h.apply(p, TransitionCommands.Set(join)); assertEquals(listOf(join),a.timeline.transitions)
        val b = h.apply(a, TransitionCommands.Set(join.copy(durationMicros=500_000)))
        assertEquals(a,h.undo(b)); assertEquals(b,h.redo(a))
        val c = h.apply(b,TransitionCommands.Remove("join"));assertTrue(c.timeline.transitions.isEmpty())
        assertEquals(b,h.undo(c));assertEquals(c,h.redo(b))
    }
    @Test fun invalid_set_does_not_enter_history() {
        val h=EditHistory();val p=project()
        assertThrows(IllegalArgumentException::class.java) { h.apply(p,TransitionCommands.Set(join.copy(incomingClipId="c"))) }
        assertFalse(h.canUndo)
    }
    @Test fun structural_changes_remove_invalid_join_and_one_undo_restores_exact_state() {
        val changes = listOf(
            TimelineCommands.DeleteClip("a"), TimelineCommands.MoveClip("b",2),
            TimelineCommands.DuplicateClip("a"), TimelineCommands.SplitClip("a",1_000_000),
            TimelineCommands.TrimClip("b",0,3_000_000,2_000_000,5_000_000))
        changes.forEach { command ->
            val h=EditHistory();val p=h.apply(project(),TransitionCommands.Set(join))
            val edited=h.apply(p,command);assertTrue(command.description,edited.timeline.transitions.isEmpty())
            val undone=h.undo(edited);assertEquals(command.description,p,undone)
            assertTrue(h.redo(undone).timeline.transitions.isEmpty())
        }
    }
    @Test fun unrelated_edit_preserves_join_and_does_not_duplicate_it() {
        val h=EditHistory();val p=h.apply(project(),TransitionCommands.Set(join))
        val edited=h.apply(p,TimelineCommands.DuplicateClip("c"));assertEquals(listOf(join),edited.timeline.transitions)
        assertEquals(p,h.undo(edited))
    }
    @Test fun invalid_speed_is_rejected_without_removing_the_join_or_mutating_history() {
        val h = EditHistory(); val p = h.apply(project(), TransitionCommands.Set(join))
        assertThrows(IllegalArgumentException::class.java) { h.apply(p, TimelineCommands.SetSpeed("a", 100f)) }
        assertEquals(listOf(join), p.timeline.transitions)
        assertEquals(project(), h.undo(p))
    }

}
