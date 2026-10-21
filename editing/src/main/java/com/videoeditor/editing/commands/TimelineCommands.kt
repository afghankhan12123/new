package com.videoeditor.editing.commands

import com.videoeditor.core.common.util.Ids
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.Timeline
import com.videoeditor.core.model.render.rebaseVisualTime
import com.videoeditor.editing.history.EditCommand

/**
 * Concrete, reversible timeline operations. Each command is a value object that
 * knows how to apply and undo itself against the immutable [Project] document.
 *
 * After any structural change to the video track, clips are re-packed so their
 * timeline positions remain contiguous (no gaps), which keeps downstream timing
 * deterministic.
 */
object TimelineCommands {

    /** Trims a clip by adjusting its source in/out points. */
    data class TrimClip(
        val clipId: String,
        val newSourceStartMicros: Long,
        val newSourceEndMicros: Long,
        val prevSourceStartMicros: Long,
        val prevSourceEndMicros: Long,
    ) : EditCommand {
        override val description = "Trim clip"
        private var previous: Clip? = null

        override fun apply(project: Project): Project = project.withClip(clipId) { clip ->
            require(!clip.isPhoto && newSourceStartMicros >= 0L && newSourceEndMicros > newSourceStartMicros)
            project.mediaRef(clip.mediaRefId)?.durationMicros?.let { require(newSourceEndMicros <= it) }
            previous = clip
            clip.rebaseVisualTime(((newSourceStartMicros - clip.sourceStartMicros).toDouble() / clip.playbackSpeed).toLong()).copy(
                sourceStartMicros = newSourceStartMicros,
                sourceEndMicros = newSourceEndMicros,
            )
        }.repacked()

        override fun undo(project: Project): Project = project.withClip(clipId) { previous ?: it }.repacked()
    }

    /** Splits a clip at a project time into two clips. */
    data class SplitClip(
        val clipId: String,
        val atProjectTimeMicros: Long,
        val newId: String = Ids.newId(),
    ) : EditCommand {
        override val description = "Split clip"
        private var previous: Clip? = null

        override fun apply(project: Project): Project {
            val clip = project.timeline.videoClips.firstOrNull { it.id == clipId }
                ?: return project
            val offsetInClip = atProjectTimeMicros - clip.timelineStartMicros
            if (offsetInClip <= 0L || offsetInClip >= clip.timelineDurationMicros) return project
            val sourceSplitMicros = clip.sourceTimeAt(atProjectTimeMicros)
            if (!clip.isPhoto && (sourceSplitMicros <= clip.sourceStartMicros || sourceSplitMicros >= clip.sourceEndMicros)) return project
            previous = clip
            val left = if (clip.isPhoto) clip.copy(photoDurationMicros = offsetInClip)
                else clip.copy(sourceEndMicros = sourceSplitMicros)
            val right = clip.rebaseVisualTime(offsetInClip).let {
                if (clip.isPhoto) it.copy(id = newId, photoDurationMicros = clip.timelineDurationMicros - offsetInClip)
                else it.copy(id = newId, sourceStartMicros = sourceSplitMicros)
            }
            val index = project.timeline.videoClips.indexOfFirst { it.id == clipId }
            val newClips = project.timeline.videoClips.toMutableList().apply {
                removeAt(index)
                addAll(index, listOf(left, right))
            }
            return project.withTimeline(project.timeline.copy(videoClips = newClips)).repacked()
        }

        override fun undo(project: Project): Project {
            val original = previous ?: return project
            return project.withTimeline(project.timeline.copy(videoClips = project.timeline.videoClips
                .filterNot { it.id == newId }.map { if (it.id == clipId) original else it })).repacked()
        }
    }

    /** Deletes a clip from the video track. */
    data class DeleteClip(val clipId: String) : EditCommand {
        override val description = "Delete clip"

        private var removedClip: Clip? = null
        private var removedIndex: Int = -1

        override fun apply(project: Project): Project {
            val index = project.timeline.videoClips.indexOfFirst { it.id == clipId }
            if (index < 0) return project
            removedClip = project.timeline.videoClips[index]
            removedIndex = index
            val newClips = project.timeline.videoClips.toMutableList().apply { removeAt(index) }
            return project.withTimeline(project.timeline.copy(videoClips = newClips)).repacked()
        }

        override fun undo(project: Project): Project {
            val clip = removedClip ?: return project
            val newClips = project.timeline.videoClips.toMutableList().apply {
                add(removedIndex.coerceIn(0, size), clip)
            }
            return project.withTimeline(project.timeline.copy(videoClips = newClips)).repacked()
        }
    }

    /** Moves a clip to a new index in the video track. */
    data class MoveClip(val clipId: String, val toIndex: Int) : EditCommand {
        override val description = "Move clip"

        private var fromIndex: Int = -1

        override fun apply(project: Project): Project {
            val clips = project.timeline.videoClips.toMutableList()
            val from = clips.indexOfFirst { it.id == clipId }
            if (from < 0) return project
            fromIndex = from
            val clip = clips.removeAt(from)
            clips.add(toIndex.coerceIn(0, clips.size), clip)
            return project.withTimeline(project.timeline.copy(videoClips = clips)).repacked()
        }

        override fun undo(project: Project): Project {
            if (fromIndex < 0) return project
            val clips = project.timeline.videoClips.toMutableList()
            val current = clips.indexOfFirst { it.id == clipId }
            if (current < 0) return project
            val clip = clips.removeAt(current)
            clips.add(fromIndex.coerceIn(0, clips.size), clip)
            return project.withTimeline(project.timeline.copy(videoClips = clips)).repacked()
        }
    }

    /** Duplicates a clip, inserting the copy immediately after it. */
    data class DuplicateClip(val clipId: String) : EditCommand {
        override val description = "Duplicate clip"

        private val newId: String = Ids.newId()

        override fun apply(project: Project): Project {
            val index = project.timeline.videoClips.indexOfFirst { it.id == clipId }
            if (index < 0) return project
            val copy = project.timeline.videoClips[index].copy(id = newId)
            val newClips = project.timeline.videoClips.toMutableList().apply {
                add(index + 1, copy)
            }
            return project.withTimeline(project.timeline.copy(videoClips = newClips)).repacked()
        }

        override fun undo(project: Project): Project {
            val id = newId
            val newClips = project.timeline.videoClips.filterNot { it.id == id }
            return project.withTimeline(project.timeline.copy(videoClips = newClips)).repacked()
        }
    }

    data class SetPhotoDuration(val clipId: String, val durationMicros: Long) : EditCommand {
        override val description = "Photo duration"
        private var previous: Long? = null
        override fun apply(project: Project) = project.withClip(clipId) {
            if (!it.isPhoto) it else {
                previous = it.photoDurationMicros
                it.copy(photoDurationMicros = durationMicros.coerceIn(100_000L, 60_000_000L))
            }
        }.repacked()
        override fun undo(project: Project) = project.withClip(clipId) { it.copy(photoDurationMicros = previous ?: it.photoDurationMicros) }.repacked()
    }

    /** Sets the playback speed of a clip. */
    data class SetSpeed(val clipId: String, val speed: Float) : EditCommand {
        override val description = "Change speed"

        private var applied: SpeedChangePlanner.Apply? = null

        override fun apply(project: Project): Project {
            val proposal = SpeedChangePlanner.plan(project, clipId, speed)
            require(!proposal.needsTransitionAdjustment) { "This speed needs shorter transitions. Review the speed panel to apply them together." }
            return SpeedChangePlanner.Apply(proposal).also { applied = it }.apply(project)
        }

        override fun undo(project: Project): Project = applied?.undo(project) ?: project
    }

    /** Appends video or photo clips to the end of the primary video track. */
    data class AppendClips(
        val mediaRefs: List<MediaRef>,
        val clips: List<Clip>,
    ) : EditCommand {
        override val description = "Add media"
        private var previousRefs: List<MediaRef>? = null

        override fun apply(project: Project): Project {
            previousRefs = project.mediaRefs
            val existingRefs = project.mediaRefs.associateBy { it.id }.toMutableMap()
            for (ref in mediaRefs) {
                existingRefs[ref.id] = ref
            }
            val newClips = project.timeline.videoClips + clips
            return project.copy(
                mediaRefs = existingRefs.values.toList(),
                timeline = project.timeline.copy(videoClips = newClips),
            ).repacked()
        }

        override fun undo(project: Project): Project {
            val addedClipIds = clips.map { it.id }.toSet()
            val newClips = project.timeline.videoClips.filterNot { it.id in addedClipIds }
            return project.copy(
                mediaRefs = previousRefs ?: project.mediaRefs,
                timeline = project.timeline.copy(videoClips = newClips),
            ).repacked()
        }
    }

    // -- helpers --

    private fun Project.withClip(clipId: String, transform: (Clip) -> Clip): Project {
        val newClips = timeline.videoClips.map {
            if (it.id == clipId) transform(it) else it
        }
        return withTimeline(timeline.copy(videoClips = newClips))
    }

    private fun Project.withTimeline(timeline: Timeline): Project =
        copy(timeline = timeline)

    /** Re-packs video clips so their timeline positions are contiguous. */
    private fun Project.repacked(): Project {
        var cursor = 0L
        val packed = timeline.videoClips.map { clip ->
            clip.copy(timelineStartMicros = cursor).also { cursor += it.timelineDurationMicros }
        }
        return copy(timeline = timeline.copy(videoClips = packed))
    }
}
