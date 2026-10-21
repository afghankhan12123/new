package com.videoeditor.editing.history

import com.videoeditor.core.model.Project

/**
 * A reversible edit operation. Commands operate on the immutable [Project]
 * document and produce a new one, which is what makes undo/redo and crash-safe
 * persistence straightforward.
 */
interface EditCommand {
    /** A short, human-readable description for logging and future UI. */
    val description: String

    /** Applies the command, returning the resulting project. */
    fun apply(project: Project): Project

    /** Reverses the command, returning the previous project. */
    fun undo(project: Project): Project
}
