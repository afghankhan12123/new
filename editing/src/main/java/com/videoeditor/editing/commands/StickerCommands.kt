package com.videoeditor.editing.commands

import com.videoeditor.core.common.util.Ids
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.StickerClip
import com.videoeditor.editing.history.EditCommand

/**
 * Reversible commands for the sticker layer. Stickers are free-positioned like
 * the PiP and text tracks: nothing re-packs, gaps are meaningful, and each
 * command is one history entry so a completed gesture undoes as a single step.
 */
object StickerCommands {

    /** Adds a sticker to the project timeline. */
    data class AddSticker(val sticker: StickerClip) : EditCommand {
        override val description = "Add sticker"

        override fun apply(project: Project): Project {
            require(sticker.timelineStartMicros >= 0 && sticker.timelineDurationMicros > 0)
            require(sticker.scale in StickerClip.MIN_SCALE..StickerClip.MAX_SCALE && sticker.opacity in 0f..1f)
            if (project.timeline.stickers.any { it.id == sticker.id }) return project
            return project.withStickers(project.timeline.stickers + sticker)
        }

        override fun undo(project: Project): Project =
            project.withStickers(project.timeline.stickers.filterNot { it.id == sticker.id })
    }

    /**
     * Replaces a sticker with [value] (same id), remembering the previous
     * sticker for undo. Used for every property edit — transform, timing,
     * opacity and z-order — so each completed change is one reversible step.
     */
    data class UpdateSticker(val stickerId: String, val value: StickerClip) : EditCommand {
        override val description = "Edit sticker"
        private var previous: StickerClip? = null

        override fun apply(project: Project): Project {
            require(value.id == stickerId && value.timelineStartMicros >= 0 && value.timelineDurationMicros > 0)
            require(value.scale in StickerClip.MIN_SCALE..StickerClip.MAX_SCALE && value.opacity in 0f..1f)
            require(value.assetKey.isNotBlank()) { "Choose an available sticker asset" }
            return project.withSticker(stickerId) {
                previous = it
                value
            }
        }

        override fun undo(project: Project): Project =
            project.withSticker(stickerId) { previous ?: it }
    }

    /** Removes a sticker. Reversible: the whole sticker is remembered. */
    data class DeleteSticker(val stickerId: String) : EditCommand {
        override val description = "Delete sticker"

        private var removed: StickerClip? = null
        private var removedIndex: Int = -1

        override fun apply(project: Project): Project {
            val index = project.timeline.stickers.indexOfFirst { it.id == stickerId }
            if (index < 0) return project
            removed = project.timeline.stickers[index]
            removedIndex = index
            return project.withStickers(project.timeline.stickers.filterIndexed { i, _ -> i != index })
        }

        override fun undo(project: Project): Project {
            val sticker = removed ?: return project
            val stickers = project.timeline.stickers.toMutableList()
            stickers.add(removedIndex.coerceIn(0, stickers.size), sticker)
            return project.withStickers(stickers)
        }
    }

    /**
     * Duplicates a sticker, stacking the copy directly above the original.
     * The copy's id is fixed at construction so a redo restores the identical
     * instance rather than minting a second one, and a redone duplication never
     * orphans an asset under a forgotten id.
     */
    data class DuplicateSticker(
        val stickerId: String,
        val newId: String = Ids.newId(),
    ) : EditCommand {
        override val description = "Duplicate sticker"

        override fun apply(project: Project): Project {
            val original = project.timeline.stickers.firstOrNull { it.id == stickerId } ?: return project
            if (project.timeline.stickers.any { it.id == newId }) return project
            val topZ = project.timeline.stickers.maxOfOrNull { it.zIndex } ?: original.zIndex
            val copy = original.copy(id = newId, zIndex = topZ + 1)
            return project.withStickers(project.timeline.stickers + copy)
        }

        override fun undo(project: Project): Project =
            project.withStickers(project.timeline.stickers.filterNot { it.id == newId })
    }

    /** Moves a sticker along the timeline; the new start is clamped to 0. */
    data class MoveSticker(val stickerId: String, val newTimelineStartMicros: Long) : EditCommand {
        override val description = "Move sticker"

        private var prevStart: Long = 0L

        override fun apply(project: Project): Project = project.withSticker(stickerId) {
            prevStart = it.timelineStartMicros
            it.copy(timelineStartMicros = newTimelineStartMicros.coerceAtLeast(0L))
        }

        override fun undo(project: Project): Project = project.withSticker(stickerId) {
            it.copy(timelineStartMicros = prevStart)
        }
    }

    private fun Project.withSticker(id: String, transform: (StickerClip) -> StickerClip): Project =
        copy(timeline = timeline.copy(
            stickers = timeline.stickers.map { if (it.id == id) transform(it) else it },
        ))

    private fun Project.withStickers(stickers: List<StickerClip>): Project =
        copy(timeline = timeline.copy(stickers = stickers))
}
