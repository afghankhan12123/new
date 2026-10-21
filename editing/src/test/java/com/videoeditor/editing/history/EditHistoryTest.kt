package com.videoeditor.editing.history

import com.google.common.truth.Truth.assertThat
import com.videoeditor.core.model.Project
import org.junit.Test

class EditHistoryTest {

    private fun project(name: String = "p") = Project(
        id = "id",
        name = name,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    private class RenameCommand(val newName: String) : EditCommand {
        override val description = "Rename"
        private var prev: String = ""
        override fun apply(project: Project): Project {
            prev = project.name
            return project.copy(name = newName)
        }
        override fun undo(project: Project): Project = project.copy(name = prev)
    }

    @Test
    fun `apply then undo restores original`() {
        val history = EditHistory()
        var p = project("first")
        p = history.apply(p, RenameCommand("second"))
        assertThat(p.name).isEqualTo("second")
        p = history.undo(p)
        assertThat(p.name).isEqualTo("first")
    }

    @Test
    fun `redo re-applies undone command`() {
        val history = EditHistory()
        var p = project("first")
        p = history.apply(p, RenameCommand("second"))
        p = history.undo(p)
        p = history.redo(p)
        assertThat(p.name).isEqualTo("second")
    }

    @Test
    fun `new edit clears redo stack`() {
        val history = EditHistory()
        var p = project("first")
        p = history.apply(p, RenameCommand("second"))
        p = history.undo(p)
        assertThat(history.canRedo).isTrue()
        p = history.apply(p, RenameCommand("third"))
        assertThat(history.canRedo).isFalse()
    }

    @Test
    fun `undo on empty history returns current`() {
        val history = EditHistory()
        val p = project("first")
        val result = history.undo(p)
        assertThat(result).isSameInstanceAs(p)
    }

    @Test
    fun `history respects max depth`() {
        val history = EditHistory(maxDepth = 3)
        var p = project()
        repeat(10) { i -> p = history.apply(p, RenameCommand("n$i")) }
        var undoCount = 0
        while (history.canUndo) {
            p = history.undo(p)
            undoCount++
        }
        assertThat(undoCount).isEqualTo(3)
    }
}
