package com.videoeditor.core.model.render

import com.videoeditor.core.model.*

/** Decoder/effect timestamps include the sequence item's project offset.
 * Preview retimes its MediaPeriod before effects; export retimes after source effects.
 * Composition-level passes always receive final project microseconds.
 */
data class ItemTimeMapping(val itemStartMicros: Long, val sourceSpeed: Float = 1f) {
    init { require(sourceSpeed.isFinite() && sourceSpeed > 0f) }
    fun projectTime(presentationMicros: Long): Long = Math.addExact(itemStartMicros,
        ((presentationMicros - itemStartMicros).toDouble() / sourceSpeed).toLong())
    fun clipLocalTime(presentationMicros: Long, clipStartMicros: Long): Long =
        Math.subtractExact(projectTime(presentationMicros), clipStartMicros)
}

/** Rebase the existing clip-local contract when a source head is removed or split.
 * Keeping preceding keys (now negative) preserves Hold/Linear/Smooth interpolation exactly.
 */
fun Clip.rebaseVisualTime(removedProjectMicros: Long): Clip {
    if (removedProjectMicros == 0L) return this
    fun VisualKeyframe.shift() = copy(timeMicros = Math.subtractExact(timeMicros, removedProjectMicros))
    return copy(
        effects = effects.map { effect -> effect.copy(
            startTimeMicros = Math.subtractExact(effect.startTimeMicros, removedProjectMicros),
            keyframes = effect.keyframes.map { it.shift() },
        ) },
        keyframeTracks = keyframeTracks.mapValues { (_, track) -> track.copy(keyframes = track.keyframes.map { it.shift() }) },
    )
}

/** Overlay effects, opacity and mask animation share the same clip-local head-trim/split rebase. */
fun OverlayClip.rebaseVisualTime(removedProjectMicros: Long): OverlayClip {
    if (removedProjectMicros == 0L) return this
    fun VisualKeyframe.shift() = copy(timeMicros = Math.subtractExact(timeMicros, removedProjectMicros))
    fun KeyframeTrack.shift() = copy(keyframes = keyframes.map { it.shift() })
    return copy(
        effects = effects.map { it.copy(startTimeMicros = Math.subtractExact(it.startTimeMicros, removedProjectMicros),
            keyframes = it.keyframes.map { key -> key.shift() }) },
        keyframeTracks = keyframeTracks.mapValues { (_, track) -> track.shift() },
        mask = mask?.copy(keyframeTracks = mask.keyframeTracks.mapValues { (_, track) -> track.shift() }),
    )
}
