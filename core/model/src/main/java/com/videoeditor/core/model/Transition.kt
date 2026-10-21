package com.videoeditor.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.Serializable

/** A centered visual overlap; ordinary clip positions and primary audio cuts do not ripple. */
@Serializable
data class Transition(
    val id: String,
    val outgoingClipId: String,
    val incomingClipId: String,
    val durationMicros: Long,
    val type: TransitionType = TransitionType.CrossDissolve,
)

@Serializable
enum class TransitionType(val displayName: String) {
    CrossDissolve("Cross Dissolve"),
    FadeThroughBlack("Fade Black"),
    FadeThroughWhite("Fade White"),
    SlideLeft("Slide Left"),
    SlideRight("Slide Right"),
    WipeLeft("Wipe Left"),
    WipeRight("Wipe Right"),
    WipeUp("Wipe Up"),
    WipeDown("Wipe Down"),
    Push("Push"),
    Split("Split"),
    ZoomDissolve("Zoom Dissolve"),
    SmoothZoom("Smooth Zoom"),
}

/** Shared integer evaluator for validation, preview and export. End is exclusive. */
data class ResolvedTransition(
    val transition: Transition,
    val outgoing: Clip,
    val incoming: Clip,
    val joinMicros: Long,
    val startMicros: Long,
    val endMicros: Long,
) {
    fun progressAt(projectMicros: Long): Float =
        ((projectMicros - startMicros).toDouble() / transition.durationMicros).coerceIn(0.0, 1.0).toFloat()

    fun alphaAt(projectMicros: Long): Float = progressAt(projectMicros)

    fun outgoingSourceAt(projectMicros: Long): Long = Math.addExact(outgoing.sourceEndMicros,
        sourceDelta(projectMicros - joinMicros, outgoing.playbackSpeed))

    fun incomingSourceAt(projectMicros: Long): Long = Math.addExact(incoming.sourceStartMicros,
        sourceDelta(projectMicros - joinMicros, incoming.playbackSpeed))

    companion object {
        /** Decimal Float spelling, floor toward negative infinity, including incoming handles. */
        fun sourceDelta(projectDeltaMicros: Long, speed: Float): Long {
            require(speed.isFinite() && speed > 0f) { "Playback speed must be finite and positive" }
            return BigDecimal.valueOf(projectDeltaMicros).multiply(BigDecimal(speed.toString()))
                .setScale(0, RoundingMode.FLOOR).longValueExact()
        }
    }
}

/** Reject unsupported edits before a renderer can request an unavailable source frame. */
fun Project.resolveTransitions(): List<ResolvedTransition> {
    require(timeline.transitions.map { it.id }.distinct().size == timeline.transitions.size) { "Duplicate transition identity" }
    val resolved = timeline.transitions.map { transition ->
        require(transition.id.isNotBlank() && transition.durationMicros > 0) { "Transition duration must be positive" }
        val index = timeline.videoClips.indexOfFirst { it.id == transition.outgoingClipId }
        require(index >= 0 && index + 1 < timeline.videoClips.size) { "Transition needs two adjacent clips" }
        val a = timeline.videoClips[index]
        val b = timeline.videoClips[index + 1]
        require(b.id == transition.incomingClipId && a.id != b.id && a.timelineEndMicros == b.timelineStartMicros) { "Transition clips must share a contiguous join" }
        require(!a.isPhoto && !b.isPhoto) { "Cross Dissolve requires two videos" }
        require(a.playbackSpeed.isFinite() && b.playbackSpeed.isFinite() && a.playbackSpeed > 0f && b.playbackSpeed > 0f) { "Invalid transition speed" }
        val pre = transition.durationMicros / 2
        val post = transition.durationMicros - pre
        require(pre <= a.timelineDurationMicros && post <= b.timelineDurationMicros) { "Transition exceeds adjacent clip duration" }
        val join = a.timelineEndMicros
        val value = ResolvedTransition(transition, a, b, join, Math.subtractExact(join, pre), Math.addExact(join, post))
        require(mediaRef(a.mediaRefId)?.type == MediaType.Video && mediaRef(b.mediaRefId)?.type == MediaType.Video) { "Cross Dissolve requires video sources" }
        val aDuration = mediaRef(a.mediaRefId)?.durationMicros
        val bDuration = mediaRef(b.mediaRefId)?.durationMicros
        require(aDuration != null && bDuration != null) { "Source duration must be verified before applying a transition" }
        require(a.sourceStartMicros >= 0 && b.sourceStartMicros >= 0 && a.sourceEndMicros <= aDuration && b.sourceEndMicros <= bDuration) { "Invalid source trim" }
        require(value.outgoingSourceAt(value.startMicros) >= a.sourceStartMicros && value.outgoingSourceAt(value.endMicros) <= aDuration &&
            value.incomingSourceAt(value.startMicros) >= 0 && value.incomingSourceAt(value.endMicros) <= b.sourceEndMicros) { "Not enough source handles for this duration" }
        value
    }.sortedBy { it.startMicros }
    require(resolved.zipWithNext().all { (a,b) -> a.endMicros <= b.startMicros && a.joinMicros != b.joinMicros }) { "Transition windows must not overlap" }
    return resolved
}
