package com.videoeditor.editing.commands

import com.videoeditor.core.model.*
import com.videoeditor.core.model.render.ProjectRenderPlan
import com.videoeditor.core.model.render.rebaseVisualTime
import com.videoeditor.editing.history.EditCommand
import java.math.BigDecimal
import java.math.RoundingMode

/** A proposal contains the complete validated result. No intermediate trim reaches history/preview. */
object TransitionHandlePlanner {
    data class Plan(val before: Project, val after: Project, val transition: Transition,
        val outgoingTrimMicros: Long, val incomingTrimMicros: Long) {
        val timelineReductionMicros get() = before.timeline.renderedDurationMicros - after.timeline.renderedDurationMicros
    }

    fun plan(project: Project, transition: Transition): Plan {
        require(transition.durationMicros >= 100_000L) { "Choose a duration of at least 0.10 s" }
        val clips = project.timeline.videoClips
        val index = clips.indexOfFirst { it.id == transition.outgoingClipId }
        require(index >= 0 && index + 1 < clips.size && clips[index + 1].id == transition.incomingClipId) { "Select adjacent video clips" }
        val a = clips[index]; val b = clips[index + 1]
        require(!a.isPhoto && !b.isPhoto) { "Transitions require two videos" }
        val mediaA = requireNotNull(project.mediaRef(a.mediaRefId)?.durationMicros) { "Verify outgoing source duration" }
        requireNotNull(project.mediaRef(b.mediaRefId)?.durationMicros) { "Verify incoming source duration" }
        fun sourceCeil(us: Long, speed: Float) = BigDecimal.valueOf(us).multiply(BigDecimal(speed.toString()))
            .setScale(0, RoundingMode.CEILING).longValueExact()
        val pre = transition.durationMicros / 2
        val post = transition.durationMicros - pre
        val aTrim = (sourceCeil(post, a.playbackSpeed) - (mediaA - a.sourceEndMicros)).coerceAtLeast(0L)
        val bTrim = (sourceCeil(pre, b.playbackSpeed) - b.sourceStartMicros).coerceAtLeast(0L)
        val adjustedA = a.copy(sourceEndMicros = Math.subtractExact(a.sourceEndMicros, aTrim))
        val adjustedB = b.rebaseVisualTime((bTrim.toDouble() / b.playbackSpeed).toLong()).copy(sourceStartMicros = Math.addExact(b.sourceStartMicros, bTrim))
        require(adjustedA.timelineDurationMicros >= maxOf(200_000L, pre) &&
            adjustedB.timelineDurationMicros >= maxOf(200_000L, post)) { "Not enough footage for this duration; shorten the transition" }
        var cursor = 0L
        val adjusted = clips.map { clip ->
            val value = when (clip.id) { a.id -> adjustedA; b.id -> adjustedB; else -> clip }
            value.copy(timelineStartMicros = cursor).also { cursor = Math.addExact(cursor, it.timelineDurationMicros) }
        }
        val joins = project.timeline.transitions.filterNot { it.id == transition.id ||
            it.outgoingClipId == a.id && it.incomingClipId == b.id } + transition
        // Overlay, music, voiceover and their automation remain at authored project positions.
        val result = project.copy(timeline = project.timeline.copy(videoClips = adjusted, transitions = joins))
        ProjectRenderPlan.compile(result) // includes neighbors, trims, handles, bounds and input budget
        return Plan(project, result, transition, aTrim, bTrim)
    }

    class Apply(private val proposal: Plan) : EditCommand {
        override val description = "Apply ${proposal.transition.type.displayName} with source handles"
        override fun apply(project: Project): Project {
            require(project.timeline == proposal.before.timeline && project.mediaRefs == proposal.before.mediaRefs) {
                "The timeline changed. Review the transition proposal again."
            }
            return project.copy(timeline = proposal.after.timeline)
        }
        override fun undo(project: Project) = project.copy(timeline = proposal.before.timeline)
    }
}
