package com.videoeditor.editing.commands

import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Transition
import com.videoeditor.core.model.resolveTransitions
import com.videoeditor.editing.history.EditCommand

/** Uses the existing history; validation happens before project state changes. */
object TransitionCommands {
    class Set(private val transition: Transition) : EditCommand {
        override val description = transition.type.displayName
        private var previous: List<Transition>? = null
        override fun apply(project: Project): Project {
            val joins = project.timeline.transitions.filterNot {
                it.id == transition.id || it.outgoingClipId == transition.outgoingClipId && it.incomingClipId == transition.incomingClipId
            } + transition
            val result = project.copy(timeline = project.timeline.copy(transitions = joins))
            result.resolveTransitions()
            previous = project.timeline.transitions
            return result
        }
        override fun undo(project: Project): Project = previous?.let {
            project.copy(timeline = project.timeline.copy(transitions = it))
        } ?: project
    }
    class Remove(private val id: String) : EditCommand {
        override val description = "Remove transition"
        private var previous: List<Transition>? = null
        override fun apply(project: Project): Project {
            previous = project.timeline.transitions
            return project.copy(timeline = project.timeline.copy(transitions = project.timeline.transitions.filterNot { it.id == id }))
        }
        override fun undo(project: Project): Project = previous?.let {
            project.copy(timeline = project.timeline.copy(transitions = it))
        } ?: project
    }
}
