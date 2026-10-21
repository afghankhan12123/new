package com.videoeditor.editing.commands

import com.videoeditor.core.common.util.Ids
import com.videoeditor.core.model.BlendMode
import com.videoeditor.core.model.ChromaKeySettings
import com.videoeditor.core.model.KeyframeTrack
import com.videoeditor.core.model.LayerMask
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.OverlayClip
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.VisualEffect
import com.videoeditor.core.model.VisualKeyframe
import com.videoeditor.core.model.VisualTransform
import com.videoeditor.editing.history.EditCommand
import com.videoeditor.core.model.render.rebaseVisualTime

/**
 * Reversible commands for the free-positioned overlay (picture-in-picture)
 * layers. Like the audio and text tracks, overlays are not re-packed: their
 * timeline positions are absolute and gaps are meaningful.
 */
object OverlayCommands {
    /** One reversible document edit for an overlay tool or completed gesture. */
    data class UpdateOverlay(val overlayId: String, val value: OverlayClip) : EditCommand {
        override val description = "Edit overlay"
        private var previous: OverlayClip? = null
        override fun apply(project: Project): Project {
            require(value.id == overlayId && value.timelineStartMicros >= 0 && value.timelineDurationMicros > 0)
            require(value.opacity in 0f..1f && value.volume in 0f..2f && value.transform.scale in 0.05f..8f)
            return project.withOverlay(overlayId) {
                require(value.mediaRefId == it.mediaRefId)
                previous = it
                value
            }
        }
        override fun undo(project: Project): Project = project.withOverlay(overlayId) { previous ?: it }
    }


    /**
     * Adds an overlay layer, registering its [mediaRef] in the project as
     * needed — a layer whose media ref is missing would fail composition
     * building, so the two must be added together.
     */
    data class AddOverlay(val mediaRef: MediaRef, val overlay: OverlayClip) : EditCommand {
        override val description = "Add overlay"

        private var referenceExisted = false

        override fun apply(project: Project): Project {
            if (project.timeline.overlayClips.any { it.id == overlay.id }) return project
            referenceExisted = project.mediaRefs.any { it.id == mediaRef.id }
            val refs = if (project.mediaRefs.any { it.id == mediaRef.id }) {
                project.mediaRefs
            } else {
                project.mediaRefs + mediaRef
            }
            return project
                .copy(mediaRefs = refs)
                .withOverlays(project.timeline.overlayClips + overlay)
        }

        override fun undo(project: Project): Project {
            val without = project.withOverlays(
                project.timeline.overlayClips.filterNot { it.id == overlay.id },
            )
            val stillUsed = without.timeline.overlayClips.any { it.mediaRefId == mediaRef.id } ||
                without.timeline.videoClips.any { it.mediaRefId == mediaRef.id } ||
                without.timeline.audioClips.any { it.mediaRefId == mediaRef.id }
            return if (referenceExisted || stillUsed) without else without.copy(
                mediaRefs = without.mediaRefs.filterNot { it.id == mediaRef.id },
            )
        }
    }

    /** Removes an overlay layer. Reversible: the whole layer is remembered. */
    data class DeleteOverlay(val overlayId: String) : EditCommand {
        override val description = "Delete overlay"

        private var removed: OverlayClip? = null
        private var removedIndex: Int = -1

        override fun apply(project: Project): Project {
            val index = project.timeline.overlayClips.indexOfFirst { it.id == overlayId }
            if (index < 0) return project
            removed = project.timeline.overlayClips[index]
            removedIndex = index
            return project.withOverlays(project.timeline.overlayClips.filterIndexed { i, _ -> i != index })
        }

        override fun undo(project: Project): Project {
            val overlay = removed ?: return project
            val overlays = project.timeline.overlayClips.toMutableList()
            overlays.add(removedIndex.coerceIn(0, overlays.size), overlay)
            return project.withOverlays(overlays)
        }
    }

    /** Duplicates an overlay, stacking the copy directly above the original. */
    data class DuplicateOverlay(val overlayId: String, val newId: String = Ids.newId()) : EditCommand {
        override val description = "Duplicate overlay"


        override fun apply(project: Project): Project {
            val original = project.timeline.overlayClips.firstOrNull { it.id == overlayId }
                ?: return project
            val topZ = project.timeline.overlayClips.maxOfOrNull { it.zIndex } ?: original.zIndex
            val copy = original.copy(id = newId, zIndex = topZ + 1)
            return project.withOverlays(project.timeline.overlayClips + copy)
        }

        override fun undo(project: Project): Project {
            val id = newId
            return project.withOverlays(project.timeline.overlayClips.filterNot { it.id == id })
        }
    }

    /** Moves an overlay along the timeline; the new start is clamped to 0. */
    data class MoveOverlay(val overlayId: String, val newTimelineStartMicros: Long) : EditCommand {
        override val description = "Move overlay"

        private var prevStart: Long = 0L

        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            prevStart = it.timelineStartMicros
            it.copy(timelineStartMicros = newTimelineStartMicros.coerceAtLeast(0L))
        }

        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(timelineStartMicros = prevStart)
        }
    }

    /** Trims a video overlay's source window; photo overlays keep their duration. */
    data class TrimOverlay(
        val overlayId: String,
        val newSourceStartMicros: Long,
        val newSourceEndMicros: Long,
    ) : EditCommand {
        override val description = "Trim overlay"

        private var previous: OverlayClip? = null

        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            if (it.isPhoto) it else {
                require(newSourceStartMicros >= 0L && newSourceEndMicros > newSourceStartMicros)
                project.mediaRef(it.mediaRefId)?.durationMicros?.let { end -> require(newSourceEndMicros <= end) }
                previous = it
                it.rebaseVisualTime(((newSourceStartMicros - it.sourceStartMicros).toDouble() / it.playbackSpeed).toLong()).copy(
                    sourceStartMicros = newSourceStartMicros, sourceEndMicros = newSourceEndMicros,
                    timelineDurationMicros = ((newSourceEndMicros - newSourceStartMicros).toDouble() / it.playbackSpeed).toLong(),
                )
            }
        }

        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            previous ?: it
        }
    }

    /** Resizes how long a photo overlay stays visible. */
    data class SetOverlayDuration(val overlayId: String, val durationMicros: Long) : EditCommand {
        override val description = "Overlay duration"

        private var previous: Long? = null

        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            previous = it.timelineDurationMicros
            it.copy(timelineDurationMicros = durationMicros.coerceIn(100_000L, 600_000_000L))
        }

        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(timelineDurationMicros = previous ?: it.timelineDurationMicros)
        }
    }

    /** Splits a video overlay at a project time into two layers. */
    data class SplitOverlay(
        val overlayId: String,
        val atProjectTimeMicros: Long,
        val newId: String = Ids.newId(),
    ) : EditCommand {
        override val description = "Split overlay"

        private var previous: OverlayClip? = null

        override fun apply(project: Project): Project {
            val overlay = project.timeline.overlayClips.firstOrNull { it.id == overlayId }
                ?: return project
            if (overlay.isPhoto) return project
            if (atProjectTimeMicros <= overlay.timelineStartMicros ||
                atProjectTimeMicros >= overlay.timelineEndMicros
            ) {
                return project
            }
            val sourceAt = overlay.sourceTimeAt(atProjectTimeMicros)
            if (sourceAt <= overlay.sourceStartMicros || sourceAt >= overlay.sourceEndMicros) {
                return project
            }
            previous = overlay
            val left = overlay.copy(
                sourceEndMicros = sourceAt,
                timelineDurationMicros = atProjectTimeMicros - overlay.timelineStartMicros,
            )
            val right = overlay.rebaseVisualTime(atProjectTimeMicros - overlay.timelineStartMicros).copy(
                id = newId,
                sourceStartMicros = sourceAt,
                timelineStartMicros = atProjectTimeMicros,
                timelineDurationMicros = overlay.timelineEndMicros - atProjectTimeMicros,
            )
            return project.withOverlays(project.timeline.overlayClips.flatMap {
                if (it.id != overlayId) listOf(it) else listOf(left, right)
            })
        }

        override fun undo(project: Project): Project {
            val original = previous ?: return project
            return project.withOverlays(
                project.timeline.overlayClips
                    .filterNot { it.id == newId }
                    .map { if (it.id == overlayId) original else it },
            )
        }
    }

    /** Updates an overlay's canvas placement transform. */
    data class SetOverlayTransform(val overlayId: String, val value: VisualTransform) : EditCommand {
        override val description = "Transform overlay"

        private var previous = VisualTransform()

        override fun apply(project: Project): Project {
            require(value.crop?.isValid() != false && value.scale in 0.05f..8f &&
                value.offsetX in -1.5f..1.5f && value.offsetY in -1.5f..1.5f)
            return project.withOverlay(overlayId) {
                previous = it.transform; it.copy(transform = value)
            }
        }

        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(transform = previous)
        }
    }

    /** Sets an overlay's opacity (0 = invisible .. 1 = fully opaque). */
    data class SetOverlayOpacity(val overlayId: String, val opacity: Float) : EditCommand {
        override val description = "Overlay opacity"

        private var previous: Float = 1f

        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            previous = it.opacity
            it.copy(opacity = opacity.coerceIn(0f, 1f))
        }

        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(opacity = previous)
        }
    }

    /** Sets an overlay's stacking order; higher renders on top. */
    data class SetOverlayZIndex(val overlayId: String, val zIndex: Int) : EditCommand {
        override val description = "Reorder overlay"

        private var previous: Int = 0

        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            previous = it.zIndex
            it.copy(zIndex = zIndex.coerceIn(0, 1000))
        }

        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(zIndex = previous)
        }
    }

    /** Sets a video overlay's playback speed, resizing its timeline duration. */
    data class SetOverlaySpeed(val overlayId: String, val speed: Float) : EditCommand {
        override val description = "Overlay speed"

        private var prevSpeed: Float = 1f
        private var prevDuration: Long = 0L

        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            if (it.isPhoto || speed <= 0f) {
                prevSpeed = it.playbackSpeed; prevDuration = it.timelineDurationMicros; it
            } else {
                prevSpeed = it.playbackSpeed; prevDuration = it.timelineDurationMicros
                it.copy(
                    playbackSpeed = speed,
                    timelineDurationMicros = (it.sourceDurationMicros / speed).toLong(),
                )
            }
        }

        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(playbackSpeed = prevSpeed, timelineDurationMicros = prevDuration)
        }
    }

    /** Sets a video overlay's audio volume. */
    data class SetOverlayVolume(val overlayId: String, val volume: Float) : EditCommand {
        override val description = "Overlay volume"

        private var previous: Float = 1f

        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            previous = it.volume
            it.copy(volume = volume.coerceIn(0f, 4f))
        }

        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(volume = previous)
        }
    }

    /** Mutes or unmutes a video overlay's audio. */
    data class SetOverlayMuted(val overlayId: String, val muted: Boolean) : EditCommand {
        override val description = if (muted) "Mute overlay" else "Unmute overlay"

        private var previous: Boolean = false

        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            previous = it.muted
            it.copy(muted = muted)
        }

        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(muted = previous)
        }
    }

    data class SetOverlayBlendMode(val overlayId: String, val blendMode: BlendMode) : EditCommand {
        override val description = "Blend mode: ${blendMode.displayName}"
        private var previous: BlendMode? = null
        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            previous = it.blendMode
            it.copy(blendMode = blendMode)
        }
        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(blendMode = previous ?: it.blendMode)
        }
    }

    data class SetOverlayMask(val overlayId: String, val mask: LayerMask?) : EditCommand {
        override val description = if (mask != null) "Set mask: ${mask.type.displayName}" else "Remove mask"
        private var previous: LayerMask? = null
        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            previous = it.mask
            it.copy(mask = mask)
        }
        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(mask = previous)
        }
    }

    data class SetOverlayChromaKey(val overlayId: String, val chromaKey: ChromaKeySettings?) : EditCommand {
        override val description = if (chromaKey?.enabled == true) "Chroma key" else "Disable chroma key"
        private var previous: ChromaKeySettings? = null
        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            previous = it.chromaKey
            it.copy(chromaKey = chromaKey)
        }
        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(chromaKey = previous)
        }
    }

    data class SetOverlayEffects(val overlayId: String, val effects: List<VisualEffect>) : EditCommand {
        override val description = "Set overlay effects"
        private var previous: List<VisualEffect>? = null
        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            previous = it.effects
            it.copy(effects = effects)
        }
        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(effects = previous ?: it.effects)
        }
    }

    data class AddOverlayEffect(val overlayId: String, val effect: VisualEffect) : EditCommand {
        override val description = "Add ${effect.type.displayName}"
        private var previous: List<VisualEffect>? = null
        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            previous = it.effects
            it.copy(effects = it.effects + effect)
        }
        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(effects = previous ?: it.effects)
        }
    }

    data class RemoveOverlayEffect(val overlayId: String, val effectId: String) : EditCommand {
        override val description = "Remove effect"
        private var previous: List<VisualEffect>? = null
        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            previous = it.effects
            it.copy(effects = it.effects.filterNot { e -> e.id == effectId })
        }
        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(effects = previous ?: it.effects)
        }
    }

    data class UpdateOverlayEffect(val overlayId: String, val effect: VisualEffect) : EditCommand {
        override val description = "Update ${effect.type.displayName}"
        private var previous: List<VisualEffect>? = null
        override fun apply(project: Project): Project = project.withOverlay(overlayId) {
            previous = it.effects
            it.copy(effects = it.effects.map { e -> if (e.id == effect.id) effect else e })
        }
        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(effects = previous ?: it.effects)
        }
    }

    data class SetOverlayKeyframe(val overlayId: String, val propertyName: String, val keyframe: VisualKeyframe) : EditCommand {
        override val description = "Set keyframe"
        private var previousTracks: Map<String, KeyframeTrack>? = null
        override fun apply(project: Project): Project = project.withOverlay(overlayId) { overlay ->
            previousTracks = overlay.keyframeTracks
            val currentTrack = overlay.keyframeTracks[propertyName] ?: KeyframeTrack(propertyName)
            val updatedKeyframes = (currentTrack.keyframes.filterNot { it.id == keyframe.id || it.timeMicros == keyframe.timeMicros } + keyframe)
                .sortedBy { it.timeMicros }
            val newTracks = overlay.keyframeTracks + (propertyName to currentTrack.copy(keyframes = updatedKeyframes))
            overlay.copy(keyframeTracks = newTracks)
        }
        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(keyframeTracks = previousTracks ?: it.keyframeTracks)
        }
    }

    data class RemoveOverlayKeyframe(val overlayId: String, val propertyName: String, val keyframeId: String) : EditCommand {
        override val description = "Remove keyframe"
        private var previousTracks: Map<String, KeyframeTrack>? = null
        override fun apply(project: Project): Project = project.withOverlay(overlayId) { overlay ->
            previousTracks = overlay.keyframeTracks
            val currentTrack = overlay.keyframeTracks[propertyName] ?: return@withOverlay overlay
            val updatedKeyframes = currentTrack.keyframes.filterNot { it.id == keyframeId }
            val newTracks = if (updatedKeyframes.isEmpty()) {
                overlay.keyframeTracks - propertyName
            } else {
                overlay.keyframeTracks + (propertyName to currentTrack.copy(keyframes = updatedKeyframes))
            }
            overlay.copy(keyframeTracks = newTracks)
        }
        override fun undo(project: Project): Project = project.withOverlay(overlayId) {
            it.copy(keyframeTracks = previousTracks ?: it.keyframeTracks)
        }
    }

    // -- helpers --

    private fun Project.withOverlay(id: String, transform: (OverlayClip) -> OverlayClip): Project =
        copy(
            timeline = timeline.copy(
                overlayClips = timeline.overlayClips.map { if (it.id == id) transform(it) else it },
            ),
        )

    private fun Project.withOverlays(overlays: List<OverlayClip>): Project =
        copy(timeline = timeline.copy(overlayClips = overlays))
}
