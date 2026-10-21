package com.videoeditor.editing.commands

import com.videoeditor.core.model.*
import com.videoeditor.core.model.render.ProjectRenderPlan
import com.videoeditor.editing.history.EditCommand
import java.math.BigDecimal
import java.math.RoundingMode

/** Speed keeps source trims and clip-local visual events attached to the same source instant.
 * Free tracks (music, voiceover, captions, stickers, PiP) retain authored project positions.
 */
object SpeedChangePlanner {
    data class Plan(val before: Project, val after: Project, val shortened: List<Pair<Transition, Transition>>) {
        val needsTransitionAdjustment get() = shortened.isNotEmpty()
    }

    fun plan(project: Project, clipId: String, speed: Float): Plan {
        val original = requireNotNull(project.timeline.videoClips.find { it.id == clipId }) { "Select a video clip" }
        require(!original.isPhoto && speed.isFinite() && speed in .5f..2f) { "Choose a video speed from 0.5× to 2×" }
        fun retime(time: Long): Long = BigDecimal.valueOf(time).multiply(BigDecimal(original.playbackSpeed.toString()))
            .divide(BigDecimal(speed.toString()), 0, RoundingMode.HALF_UP).longValueExact()
        fun VisualKeyframe.retime() = copy(timeMicros = retime(timeMicros))
        val changed = original.copy(playbackSpeed = speed,
            fadeInMicros = retime(original.fadeInMicros), fadeOutMicros = retime(original.fadeOutMicros),
            effects = original.effects.map { it.copy(startTimeMicros = retime(it.startTimeMicros),
                durationMicros = it.durationMicros?.let(::retime), keyframes = it.keyframes.map { key -> key.retime() }) },
            keyframeTracks = original.keyframeTracks.mapValues { (_, track) -> track.copy(keyframes = track.keyframes.map { it.retime() }) })
        var cursor = 0L
        val clips = project.timeline.videoClips.map { if (it.id == clipId) changed else it }.map { clip ->
            clip.copy(timelineStartMicros = cursor).also { cursor = Math.addExact(cursor, it.timelineDurationMicros) }
        }
        val base = project.copy(timeline = project.timeline.copy(videoClips = clips))
        val affected = project.timeline.transitions.filter { it.outgoingClipId == clipId || it.incomingClipId == clipId }.map { it.id }.toSet()
        fun candidate(fraction: Long) = base.copy(timeline = base.timeline.copy(transitions = base.timeline.transitions.map {
            if (it.id !in affected) it else it.copy(durationMicros = maxOf(minOf(100_000L, it.durationMicros),
                BigDecimal.valueOf(it.durationMicros).multiply(BigDecimal.valueOf(fraction))
                    .divide(BigDecimal.valueOf(1_000_000L), 0, RoundingMode.FLOOR).longValueExact()))
        }))
        fun valid(value: Project) = try { ProjectRenderPlan.compile(value); true } catch (_: IllegalArgumentException) { false }
        if (valid(base)) return Plan(project, base, emptyList())
        require(affected.isNotEmpty() && valid(candidate(0))) {
            "This speed leaves too little transition footage (normally 0.10 s). Choose a slower speed or adjust the source trims."
        }
        // Joint monotonic search accounts for both joins sharing a short middle clip.
        // Unaffected transitions are never shortened, removed, or silently pruned by history.
        var low = 0L; var high = 999_999L
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (valid(candidate(middle))) low = middle else high = middle - 1
        }
        val after = candidate(low)
        ProjectRenderPlan.compile(after)
        return Plan(project, after, project.timeline.transitions.zip(after.timeline.transitions).filter { it.first != it.second })
    }

    class Apply(private val proposal: Plan) : EditCommand {
        override val description = "Change speed"
        override fun apply(project: Project): Project {
            require(project.timeline == proposal.before.timeline) { "The timeline changed. Review the speed again." }
            val result = project.copy(timeline = proposal.after.timeline)
            ProjectRenderPlan.compile(result)
            return result
        }
        override fun undo(project: Project) = project.copy(timeline = proposal.before.timeline)
    }
}
