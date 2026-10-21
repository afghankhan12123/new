package com.videoeditor.editing.commands

import com.videoeditor.core.model.Project
import com.videoeditor.core.model.StickerClip
import com.videoeditor.core.model.Timeline
import com.videoeditor.editing.history.EditHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerCommandsTest {

    private fun project(stickers: List<StickerClip> = emptyList()) =
        Project(id = "p", name = "t", createdAtMillis = 0, updatedAtMillis = 0,
            timeline = Timeline(stickers = stickers))

    private fun sticker(id: String = "s1", zIndex: Int = 0) = StickerClip(id = id, assetKey = "star", zIndex = zIndex)

    @Test
    fun addAndUndoRestoresEmptyTrack() {
        val history = EditHistory()
        val empty = project()
        val added = history.apply(empty, StickerCommands.AddSticker(sticker()))
        assertEquals(1, added.timeline.stickers.size)
        assertEquals(empty, history.undo(added))
        assertEquals(added, history.redo(empty))
    }

    @Test
    fun duplicateAddsCopyAboveOriginalAndUndoRemovesIt() {
        val history = EditHistory()
        val withOne = history.apply(project(), StickerCommands.AddSticker(sticker()))
        val withTwo = history.apply(withOne, StickerCommands.DuplicateSticker("s1"))
        assertEquals(2, withTwo.timeline.stickers.size)
        val copy = withTwo.timeline.stickers.first { it.id != "s1" }
        assertEquals("star", copy.assetKey)
        assertEquals(1, copy.zIndex)
        assertEquals(withOne, history.undo(withTwo))
    }

    @Test
    fun deleteRestoresSameInstanceAtSameIndex() {
        val history = EditHistory()
        var p = project(listOf(sticker("a"), sticker("b"), sticker("c")))
        p = history.apply(p, StickerCommands.DeleteSticker("b"))
        assertEquals(listOf("a", "c"), p.timeline.stickers.map { it.id })
        val restored = history.undo(p)
        assertEquals(listOf("a", "b", "c"), restored.timeline.stickers.map { it.id })
    }

    @Test
    fun moveClampsToZeroAndUndoes() {
        val history = EditHistory()
        val start = project(listOf(sticker().copy(timelineStartMicros = 500_000L)))
        val moved = history.apply(start, StickerCommands.MoveSticker("s1", -200_000L))
        assertEquals(0L, moved.timeline.stickers[0].timelineStartMicros)
        assertEquals(500_000L, history.undo(moved).timeline.stickers[0].timelineStartMicros)
    }

    @Test
    fun replacementPreservesIdentityAndUndoes() {
        val p = project(listOf(sticker()))
        val history = EditHistory()
        val replaced = history.apply(p, StickerCommands.UpdateSticker("s1", sticker().copy(assetKey = "heart")))
        assertEquals("heart", replaced.timeline.stickers.single().assetKey)
        assertEquals("s1", replaced.timeline.stickers.single().id)
        assertEquals(p, history.undo(replaced))
        assertEquals(replaced, history.redo(p))
    }

    @Test
    fun updateRejectsOutOfRangeScale() {
        val p = project(listOf(sticker()))
        assertThrows(IllegalArgumentException::class.java) {
            StickerCommands.UpdateSticker("s1", sticker().copy(scale = 99f)).apply(p)
        }
    }

    @Test
    fun newEditAfterUndoInvalidatesRedoBranch() {
        val history = EditHistory()
        var p = history.apply(project(), StickerCommands.AddSticker(sticker()))
        p = history.undo(p)
        p = history.apply(p, StickerCommands.AddSticker(sticker("other")))
        assertFalse(history.canRedo)
        assertEquals(listOf("other"), p.timeline.stickers.map { it.id })
    }

    @Test
    fun duplicateNeverReusesIdsAcrossRedo() {
        val history = EditHistory()
        var p = history.apply(project(), StickerCommands.AddSticker(sticker()))
        p = history.apply(p, StickerCommands.DuplicateSticker("s1"))
        val firstCopyId = p.timeline.stickers.first { it.id != "s1" }.id
        p = history.undo(p)
        p = history.redo(p)
        assertEquals(firstCopyId, p.timeline.stickers.first { it.id != "s1" }.id)
        assertEquals(2, p.timeline.stickers.map { it.id }.distinct().size)
    }
}
