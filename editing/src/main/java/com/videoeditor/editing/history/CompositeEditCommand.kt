package com.videoeditor.editing.history

import com.videoeditor.core.model.Project

/** One user action containing ordered edits, reversed in reverse order. */
class CompositeEditCommand(
    override val description: String,
    commands: List<EditCommand>,
) : EditCommand {
    private val commands = commands.toList()
    private var appliedCommands: List<EditCommand> = emptyList()

    override fun apply(project: Project): Project {
        val applied = mutableListOf<EditCommand>()
        var result = project
        for (command in commands) {
            val next = command.apply(result)
            // A no-op must never undo something it did not add/change.
            if (next != result) applied.add(command)
            result = next
        }
        appliedCommands = applied
        return result
    }

    override fun undo(project: Project): Project =
        appliedCommands.asReversed().fold(project) { state, command -> command.undo(state) }
}
