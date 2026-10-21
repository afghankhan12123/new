package com.videoeditor.editing.history

import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Transition
import com.videoeditor.core.model.resolveTransitions

/**
 * A bounded undo/redo stack of [EditCommand]s applied to a [Project].
 *
 * The history holds the command log only; the current project state lives with
 * the caller. This keeps the history cheap and the project the single source of
 * truth.
 */
class EditHistory(private val maxDepth: Int = 100) {

    private data class Entry(val command: EditCommand, val previousTransitions: List<Transition>)
    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Applies [command] to [current], pushes it onto the undo stack, and clears
     * the redo stack (a new edit invalidates the redo branch). Returns the new
     * project.
     */
    fun apply(current: Project, command: EditCommand): Project {
        val next = retainValidTransitions(command.apply(current))
        if (next == current) return current
        undoStack.addLast(Entry(command, current.timeline.transitions))
        while (undoStack.size > maxDepth) undoStack.removeFirst()
        redoStack.clear()
        return next
    }

    /** Pops the most recent command and reverses it. Returns the new project. */
    fun undo(current: Project): Project {
        val command = undoStack.lastOrNull() ?: return current
        val restored = command.command.undo(current).let { it.copy(timeline = it.timeline.copy(transitions = command.previousTransitions)) }
        undoStack.removeLast()
        redoStack.addLast(command)
        return restored
    }

    /** Re-applies the most recently undone command. Returns the new project. */
    fun redo(current: Project): Project {
        val command = redoStack.lastOrNull() ?: return current
        val next = retainValidTransitions(command.command.apply(current))
        redoStack.removeLast()
        undoStack.addLast(command)
        return next
    }

    /** Structural edits remove invalid joins atomically; the same Undo restores them.
     * Duplication never copies a join. Nonadjacent/split/deleted/insufficient-handle
     * joins are removed, with unaffected stable clip identities retained.
     */
    private fun retainValidTransitions(project: Project): Project {
        if (project.timeline.transitions.isEmpty()) return project
        val valid = mutableListOf<Transition>()
        for (transition in project.timeline.transitions) {
            val candidate = project.copy(timeline = project.timeline.copy(transitions = valid + transition))
            try {
                candidate.resolveTransitions()
                valid += transition
            } catch (_: IllegalArgumentException) {
                // Invalid edit-model joins are intentionally removed in this history action.
            } catch (_: ArithmeticException) {
                // Overflow is invalid timing, never a source window for rendering.
            }
        }
        return project.copy(timeline = project.timeline.copy(transitions = valid))
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
