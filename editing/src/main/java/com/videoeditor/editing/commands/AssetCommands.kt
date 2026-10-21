package com.videoeditor.editing.commands

import com.videoeditor.core.model.AudioClip
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.MediaRef
import com.videoeditor.core.model.KeyframeTrack
import com.videoeditor.core.model.Project
import com.videoeditor.core.model.TextOverlay
import com.videoeditor.core.model.VisualEffect
import com.videoeditor.core.model.VisualKeyframe
import com.videoeditor.editing.history.EditCommand

/**
 * Reversible commands for clip properties and the audio/text tracks. Like
 * [TimelineCommands], each is a value object against the immutable [Project];
 * none of these re-pack the video track because they do not change any clip's
 * timeline duration or order — except add/delete, which touch only the
 * free-positioned audio and text tracks where gaps are legal.
 */
object AssetCommands {
    data class SetClipFade(val clipId: String, val fadeInMicros: Long, val fadeOutMicros: Long) : EditCommand {
        override val description = "Fade through black"
        private var previous = 0L to 0L
        override fun apply(project: Project) = project.withVideoClip(clipId) {
            previous = it.fadeInMicros to it.fadeOutMicros
            it.copy(fadeInMicros = fadeInMicros.coerceIn(0L, it.timelineDurationMicros / 2),
                fadeOutMicros = fadeOutMicros.coerceIn(0L, it.timelineDurationMicros / 2))
        }
        override fun undo(project: Project) = project.withVideoClip(clipId) { it.copy(fadeInMicros = previous.first, fadeOutMicros = previous.second) }
    }

    data class UpdateAudioClip(val audioClipId: String, val updated: AudioClip) : EditCommand {
        override val description = "Edit audio"
        private var previous: AudioClip? = null
        override fun apply(project: Project) = project.withAudioClip(audioClipId) {
            previous = it; updated.copy(id = audioClipId)
        }
        override fun undo(project: Project) = project.withAudioClip(audioClipId) { previous ?: it }
    }
    data class SplitAudioClip(val audioClipId: String, val atMicros: Long,
        val newId: String = com.videoeditor.core.common.util.Ids.newId()) : EditCommand {
        override val description = "Split audio"
        private val leftBoundaryId = com.videoeditor.core.common.util.Ids.newId()
        private val rightBoundaryId = com.videoeditor.core.common.util.Ids.newId()
        private var previous: AudioClip? = null
        override fun apply(project: Project): Project {
            val clip = project.timeline.audioClips.firstOrNull { it.id == audioClipId } ?: return project
            if (atMicros <= clip.timelineStartMicros || atMicros >= clip.timelineStartMicros + clip.timelineDurationMicros) return project
            previous = clip
            val sourceAt = clip.sourceStartMicros + ((atMicros - clip.timelineStartMicros) * clip.playbackSpeed).toLong()
            // Partition the volume envelope at the same source position the audio
            // is cut at. Both halves receive a boundary point carrying the gain
            // the unsplit envelope had there, so splitting mid-ramp does not step
            // the level. An empty envelope stays empty, keeping the neutral path
            // untouched. Ducking configuration stays on the left half only: a
            // split of background music should not create a second independently
            // analysed ducking target from one authored setting.
            val (leftEnvelope, rightEnvelope) = clip.volumeEnvelope.splitAt(
                sourceAt,
                leftBoundaryId = leftBoundaryId,
                rightBoundaryId = rightBoundaryId,
            )
            return project.withAudioClips(project.timeline.audioClips.flatMap {
                if (it.id != audioClipId) listOf(it) else listOf(
                    it.copy(sourceEndMicros = sourceAt, fadeOutMicros = 0L, volumeEnvelope = leftEnvelope),
                    it.copy(id = newId, sourceStartMicros = sourceAt, timelineStartMicros = atMicros,
                        fadeInMicros = 0L, volumeEnvelope = rightEnvelope,
                        ducking = com.videoeditor.core.model.DuckingSettings()))
            })
        }
        override fun undo(project: Project): Project {
            val original = previous ?: return project
            return project.withAudioClips(project.timeline.audioClips.filterNot { it.id == newId }.map { if (it.id == audioClipId) original else it })
        }
    }

    data class SetColorGrade(val clipId: String, val filter: com.videoeditor.core.model.FilterSettings,
        val adjustments: com.videoeditor.core.model.ColorAdjustments) : EditCommand {
        override val description = "Change color grade"
        private var previousFilter = com.videoeditor.core.model.FilterSettings()
        private var previousAdjustments = com.videoeditor.core.model.ColorAdjustments()
        override fun apply(project: Project) = project.withVideoClip(clipId) {
            previousFilter = it.filter; previousAdjustments = it.colorAdjustments
            it.copy(filter = filter.copy(intensity = filter.intensity.coerceIn(0f, 1f)), colorAdjustments = adjustments)
        }
        override fun undo(project: Project) = project.withVideoClip(clipId) {
            it.copy(filter = previousFilter, colorAdjustments = previousAdjustments)
        }
    }

    /** Stores only the affected value, preserving unrelated edits during undo. */
    data class SetVisualTransform(val clipId: String, val value: com.videoeditor.core.model.VisualTransform) : EditCommand {
        override val description = "Transform clip"
        private var previous = com.videoeditor.core.model.VisualTransform()
        override fun apply(project: Project): Project {
            require(value.crop?.isValid() != false && value.scale in 0.1f..4f &&
                value.offsetX in -1f..1f && value.offsetY in -1f..1f)
            return project.withVideoClip(clipId) { previous = it.transform; it.copy(transform = value) }
        }
        override fun undo(project: Project) = project.withVideoClip(clipId) { it.copy(transform = previous) }
    }
    data class SetCanvas(val value: com.videoeditor.core.model.CanvasSettings) : EditCommand {
        override val description = "Change canvas"
        private var previous = com.videoeditor.core.model.CanvasSettings()
        override fun apply(project: Project): Project { previous = project.canvas; return project.copy(canvas = value) }
        override fun undo(project: Project) = project.copy(canvas = previous)
    }


    /** Explicit replacement; all timeline edits and the previous reference survive undo. */
    data class RelinkMedia(val mediaRefId: String, val replacement: MediaRef) : EditCommand {
        override val description = "Relink file"
        private var previous: MediaRef? = null
        override fun apply(project: Project): Project {
            val original = project.mediaRef(mediaRefId) ?: return project
            previous = original
            return project.copy(mediaRefs = project.mediaRefs.map {
                if (it.id == mediaRefId) replacement.copy(id = mediaRefId, originalUri = original.originalUri ?: original.uri) else it
            })
        }
        override fun undo(project: Project): Project = project.copy(mediaRefs = project.mediaRefs.map {
            if (it.id == mediaRefId) previous ?: it else it
        })
    }


    /** Sets a video clip's volume (0 = silent .. 1 = source volume, may exceed). */
    data class SetClipVolume(val clipId: String, val volume: Float) : EditCommand {
        override val description = "Change volume"

        private var prevVolume: Float = 1f

        override fun apply(project: Project): Project = project.withVideoClip(clipId) { clip ->
            prevVolume = clip.volume
            clip.copy(volume = volume.coerceIn(0f, MAX_VOLUME))
        }

        override fun undo(project: Project): Project = project.withVideoClip(clipId) { clip ->
            clip.copy(volume = prevVolume)
        }
    }

    /** Mutes or unmutes a video clip's audio. */
    data class SetClipMuted(val clipId: String, val muted: Boolean) : EditCommand {
        override val description = if (muted) "Mute clip" else "Unmute clip"

        private var prevMuted: Boolean = false

        override fun apply(project: Project): Project = project.withVideoClip(clipId) { clip ->
            prevMuted = clip.muted
            clip.copy(muted = muted)
        }

        override fun undo(project: Project): Project = project.withVideoClip(clipId) { clip ->
            clip.copy(muted = prevMuted)
        }
    }

    /**
     * Adds an audio clip to the audio track at a free timeline position,
     * registering its [mediaRef] in the project as needed — a timeline clip
     * whose media ref is missing would be silently skipped when building the
     * composition, so the two must be added together.
     */
    data class AddAudioClip(val mediaRef: MediaRef, val audioClip: AudioClip) : EditCommand {
        override val description = "Add audio"

        private var referenceExisted = false

        override fun apply(project: Project): Project {
            if (project.timeline.audioClips.any { it.id == audioClip.id }) return project
            referenceExisted = project.mediaRefs.any { it.id == mediaRef.id }
            val refs = if (project.mediaRefs.any { it.id == mediaRef.id }) {
                project.mediaRefs
            } else {
                project.mediaRefs + mediaRef
            }
            return project
                .copy(mediaRefs = refs)
                .withAudioClips(project.timeline.audioClips + audioClip)
        }

        override fun undo(project: Project): Project {
            val withoutClip = project.withAudioClips(
                project.timeline.audioClips.filterNot { it.id == audioClip.id },
            )
            // Drop the media ref too, unless another timeline element still
            // references it (the same file can back several clips).
            val stillUsed = withoutClip.timeline.overlayClips.any { it.mediaRefId == mediaRef.id } ||
                withoutClip.timeline.audioClips.any { it.mediaRefId == mediaRef.id } ||
                withoutClip.timeline.videoClips.any { it.mediaRefId == mediaRef.id }
            return if (referenceExisted || stillUsed) withoutClip else withoutClip.copy(
                mediaRefs = withoutClip.mediaRefs.filterNot { it.id == mediaRef.id },
            )
        }
    }

    /** Removes an audio clip. Reversible: the whole clip is remembered. */
    data class DeleteAudioClip(val audioClipId: String) : EditCommand {
        override val description = "Delete audio"

        private var removed: AudioClip? = null
        private var removedIndex: Int = -1

        override fun apply(project: Project): Project {
            val index = project.timeline.audioClips.indexOfFirst { it.id == audioClipId }
            if (index < 0) return project
            removed = project.timeline.audioClips[index]
            removedIndex = index
            return project.withAudioClips(
                project.timeline.audioClips.filterIndexed { i, _ -> i != index },
            )
        }

        override fun undo(project: Project): Project {
            val clip = removed ?: return project
            val clips = project.timeline.audioClips.toMutableList()
            clips.add(removedIndex.coerceIn(0, clips.size), clip)
            return project.withAudioClips(clips)
        }
    }

    /** Sets an audio clip's volume. */
    data class SetAudioVolume(val audioClipId: String, val volume: Float) : EditCommand {
        override val description = "Change audio volume"

        private var prevVolume: Float = 1f

        override fun apply(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            prevVolume = clip.volume
            clip.copy(volume = volume.coerceIn(0f, MAX_VOLUME))
        }

        override fun undo(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            clip.copy(volume = prevVolume)
        }
    }

    /**
     * Moves an audio clip along the free-positioned audio track. The new start
     * is clamped to 0; unlike video clips, audio positions are not re-packed
     * because gaps are meaningful on the audio track.
     */
    data class MoveAudioClip(val audioClipId: String, val newTimelineStartMicros: Long) :
        EditCommand {
        override val description = "Move audio"

        private var prevStart: Long = 0L

        override fun apply(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            prevStart = clip.timelineStartMicros
            clip.copy(timelineStartMicros = newTimelineStartMicros.coerceAtLeast(0L))
        }

        override fun undo(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            clip.copy(timelineStartMicros = prevStart)
        }
    }

    /** Moves a text overlay along its free-positioned track. */
    data class MoveTextOverlay(val overlayId: String, val newStartMicros: Long) : EditCommand {
        override val description = "Move text"

        private var prevStart: Long = 0L

        override fun apply(project: Project): Project =
            project.withTextOverlay(overlayId) { overlay ->
                prevStart = overlay.startMicros
                overlay.copy(startMicros = newStartMicros.coerceAtLeast(0L))
            }

        override fun undo(project: Project): Project =
            project.withTextOverlay(overlayId) { it.copy(startMicros = prevStart) }
    }

    /** Trims an audio clip's source range, keeping its timeline start. */
    data class TrimAudioClip(
        val audioClipId: String,
        val newSourceStartMicros: Long,
        val newSourceEndMicros: Long,
    ) : EditCommand {
        override val description = "Trim audio"

        private var prevStart: Long = 0L
        private var prevEnd: Long = 0L

        override fun apply(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            prevStart = clip.sourceStartMicros
            prevEnd = clip.sourceEndMicros
            clip.copy(
                sourceStartMicros = newSourceStartMicros,
                sourceEndMicros = newSourceEndMicros,
            )
        }

        override fun undo(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            clip.copy(sourceStartMicros = prevStart, sourceEndMicros = prevEnd)
        }
    }

    /** Adds a text overlay at a free timeline position. */
    data class AddTextOverlay(val overlay: TextOverlay) : EditCommand {
        override val description = "Add text"

        override fun apply(project: Project): Project {
            if (project.timeline.textOverlays.any { it.id == overlay.id }) return project
            return project.withTextOverlays(project.timeline.textOverlays + overlay)
        }

        override fun undo(project: Project): Project =
            project.withTextOverlays(
                project.timeline.textOverlays.filterNot { it.id == overlay.id },
            )
    }

    /** Removes a text overlay. */
    data class DeleteTextOverlay(val overlayId: String) : EditCommand {
        override val description = "Delete text"

        private var removed: TextOverlay? = null
        private var removedIndex: Int = -1

        override fun apply(project: Project): Project {
            val index = project.timeline.textOverlays.indexOfFirst { it.id == overlayId }
            if (index < 0) return project
            removed = project.timeline.textOverlays[index]
            removedIndex = index
            return project.withTextOverlays(
                project.timeline.textOverlays.filterIndexed { i, _ -> i != index },
            )
        }

        override fun undo(project: Project): Project {
            val overlay = removed ?: return project
            val overlays = project.timeline.textOverlays.toMutableList()
            overlays.add(removedIndex.coerceIn(0, overlays.size), overlay)
            return project.withTextOverlays(overlays)
        }
    }

    /** Changes a text overlay's content and keeps every other property. */
    data class SetText(val overlayId: String, val text: String) : EditCommand {
        override val description = "Edit text"

        private var prevText: String = ""

        override fun apply(project: Project): Project =
            project.withTextOverlay(overlayId) { overlay ->
                prevText = overlay.text
                overlay.copy(text = text)
            }

        override fun undo(project: Project): Project =
            project.withTextOverlay(overlayId) { it.copy(text = prevText) }
    }

    /**
     * Replaces a text overlay with [updated] (same id), remembering the previous
     * overlay for undo. Used for style changes (size, color, alignment, opacity,
     * background, position) so each is one reversible edit.
     */
    data class UpdateTextOverlay(val overlayId: String, val updated: TextOverlay) : EditCommand {
        override val description = "Edit text style"

        private var previous: TextOverlay? = null

        override fun apply(project: Project): Project =
            project.withTextOverlay(overlayId) { overlay ->
                previous = overlay
                updated
            }

        override fun undo(project: Project): Project {
            val prev = previous ?: return project
            return project.withTextOverlay(overlayId) { prev }
        }
    }

    // -- Stage 8 audio effects --

    /**
     * Adds one automation point to a clip's volume envelope. A point at an
     * identical source position replaces the existing one, so re-dropping a
     * point where one already stands is an update, not a duplicate.
     */
    data class AddVolumePoint(
        val audioClipId: String,
        val point: com.videoeditor.core.model.VolumePoint,
    ) : EditCommand {
        override val description = "Add volume point"
        private var previous: com.videoeditor.core.model.VolumeEnvelope? = null
        override fun apply(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            previous = clip.volumeEnvelope
            val clamped = point.copy(
                gainDb = point.gainDb.coerceIn(
                    com.videoeditor.core.model.VolumeEnvelope.MIN_GAIN_DB,
                    com.videoeditor.core.model.VolumeEnvelope.MAX_GAIN_DB,
                ),
                sourcePositionMicros = point.sourcePositionMicros
                    .coerceIn(clip.sourceStartMicros, clip.sourceEndMicros),
            )
            clip.copy(volumeEnvelope = com.videoeditor.core.model.VolumeEnvelope(
                clip.volumeEnvelope.points.filterNot {
                    it.sourcePositionMicros == clamped.sourcePositionMicros
                } + clamped,
            ).normalized())
        }
        override fun undo(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            clip.copy(volumeEnvelope = previous ?: clip.volumeEnvelope)
        }
    }

    /**
     * Moves and/or re-gains one automation point. Re-clamps into the clip's
     * audible source window so a drag can never leave the envelope outside it.
     */
    data class MoveVolumePoint(
        val audioClipId: String,
        val pointId: String,
        val newSourcePositionMicros: Long,
        val newGainDb: Float,
    ) : EditCommand {
        override val description = "Edit volume point"
        private var previous: com.videoeditor.core.model.VolumeEnvelope? = null
        override fun apply(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            previous = clip.volumeEnvelope
            clip.copy(volumeEnvelope = com.videoeditor.core.model.VolumeEnvelope(
                clip.volumeEnvelope.points.map {
                    if (it.id != pointId) it else it.copy(
                        sourcePositionMicros = newSourcePositionMicros
                            .coerceIn(clip.sourceStartMicros, clip.sourceEndMicros),
                        gainDb = newGainDb.coerceIn(
                            com.videoeditor.core.model.VolumeEnvelope.MIN_GAIN_DB,
                            com.videoeditor.core.model.VolumeEnvelope.MAX_GAIN_DB,
                        ),
                    )
                },
            ).normalized())
        }
        override fun undo(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            clip.copy(volumeEnvelope = previous ?: clip.volumeEnvelope)
        }
    }

    /** Removes one automation point. */
    data class RemoveVolumePoint(
        val audioClipId: String,
        val pointId: String,
    ) : EditCommand {
        override val description = "Remove volume point"
        private var previous: com.videoeditor.core.model.VolumeEnvelope? = null
        override fun apply(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            previous = clip.volumeEnvelope
            clip.copy(volumeEnvelope = clip.volumeEnvelope.withoutPoint(pointId))
        }
        override fun undo(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            clip.copy(volumeEnvelope = previous ?: clip.volumeEnvelope)
        }
    }

    /** Changes which curve one point uses toward the next. */
    data class SetVolumePointCurve(
        val audioClipId: String,
        val pointId: String,
        val curve: com.videoeditor.core.model.VolumeInterpolation,
    ) : EditCommand {
        override val description = "Change volume curve"
        private var previous: com.videoeditor.core.model.VolumeEnvelope? = null
        override fun apply(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            previous = clip.volumeEnvelope
            clip.copy(volumeEnvelope = com.videoeditor.core.model.VolumeEnvelope(
                clip.volumeEnvelope.points.map {
                    if (it.id != pointId) it else it.copy(interpolation = curve)
                },
            ).normalized())
        }
        override fun undo(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            clip.copy(volumeEnvelope = previous ?: clip.volumeEnvelope)
        }
    }

    /** Clears every automation point, restoring the neutral envelope. */
    data class ResetVolumeEnvelope(val audioClipId: String) : EditCommand {
        override val description = "Reset volume automation"
        private var previous: com.videoeditor.core.model.VolumeEnvelope? = null
        override fun apply(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            previous = clip.volumeEnvelope
            clip.copy(volumeEnvelope = com.videoeditor.core.model.VolumeEnvelope())
        }
        override fun undo(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            clip.copy(volumeEnvelope = previous ?: clip.volumeEnvelope)
        }
    }

    /** Sets the clip's ducking configuration (values are clamped on apply). */
    data class SetDucking(
        val audioClipId: String,
        val settings: com.videoeditor.core.model.DuckingSettings,
    ) : EditCommand {
        override val description = "Edit ducking"
        private var previous: com.videoeditor.core.model.DuckingSettings? = null
        override fun apply(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            previous = clip.ducking
            clip.copy(ducking = settings.coerced())
        }
        override fun undo(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            clip.copy(ducking = previous ?: clip.ducking)
        }
    }

    /** Sets the clip's equalizer state (band gains are clamped on apply). */
    data class SetEqualizer(
        val audioClipId: String,
        val settings: com.videoeditor.core.model.EqualizerSettings,
    ) : EditCommand {
        override val description = "Edit equalizer"
        private var previous: com.videoeditor.core.model.EqualizerSettings? = null
        override fun apply(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            previous = clip.equalizer
            clip.copy(equalizer = settings.coerced())
        }
        override fun undo(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            clip.copy(equalizer = previous ?: clip.equalizer)
        }
    }

    /** Sets the clip's stereo placement (pan is clamped to -1..1 on apply). */
    data class SetBalance(
        val audioClipId: String,
        val balance: com.videoeditor.core.model.AudioBalance,
    ) : EditCommand {
        override val description = "Edit balance"
        private var previous: com.videoeditor.core.model.AudioBalance? = null
        override fun apply(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            previous = clip.balance
            clip.copy(balance = balance.coerced())
        }
        override fun undo(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            clip.copy(balance = previous ?: clip.balance)
        }
    }

    /** Sets fade durations and curve shapes together, so a fade panel commit
     *  is one undo entry rather than two. */
    data class SetAudioFade(
        val audioClipId: String,
        val fadeInMicros: Long,
        val fadeOutMicros: Long,
        val fadeInCurve: com.videoeditor.core.model.FadeCurve,
        val fadeOutCurve: com.videoeditor.core.model.FadeCurve,
    ) : EditCommand {
        override val description = "Edit audio fade"
        private var previous: AudioClip? = null
        override fun apply(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            previous = clip
            clip.copy(
                fadeInMicros = fadeInMicros.coerceIn(0L, clip.timelineDurationMicros),
                fadeOutMicros = fadeOutMicros.coerceIn(0L, clip.timelineDurationMicros),
                fadeInCurve = fadeInCurve,
                fadeOutCurve = fadeOutCurve,
            )
        }
        override fun undo(project: Project): Project = project.withAudioClip(audioClipId) { clip ->
            previous?.let { clip.copy(
                fadeInMicros = it.fadeInMicros, fadeOutMicros = it.fadeOutMicros,
                fadeInCurve = it.fadeInCurve, fadeOutCurve = it.fadeOutCurve,
            ) } ?: clip
        }
    }

    data class SetClipEffects(val clipId: String, val effects: List<VisualEffect>) : EditCommand {
        override val description = "Set visual effects"
        private var previous: List<VisualEffect>? = null
        override fun apply(project: Project): Project = project.withVideoClip(clipId) {
            previous = it.effects
            it.copy(effects = effects)
        }
        override fun undo(project: Project): Project = project.withVideoClip(clipId) {
            it.copy(effects = previous ?: it.effects)
        }
    }

    data class AddClipEffect(val clipId: String, val effect: VisualEffect) : EditCommand {
        override val description = "Add ${effect.type.displayName}"
        private var previous: List<VisualEffect>? = null
        override fun apply(project: Project): Project = project.withVideoClip(clipId) {
            previous = it.effects
            it.copy(effects = it.effects + effect)
        }
        override fun undo(project: Project): Project = project.withVideoClip(clipId) {
            it.copy(effects = previous ?: it.effects)
        }
    }

    data class RemoveClipEffect(val clipId: String, val effectId: String) : EditCommand {
        override val description = "Remove effect"
        private var previous: List<VisualEffect>? = null
        override fun apply(project: Project): Project = project.withVideoClip(clipId) {
            previous = it.effects
            it.copy(effects = it.effects.filterNot { e -> e.id == effectId })
        }
        override fun undo(project: Project): Project = project.withVideoClip(clipId) {
            it.copy(effects = previous ?: it.effects)
        }
    }

    data class UpdateClipEffect(val clipId: String, val effect: VisualEffect) : EditCommand {
        override val description = "Update ${effect.type.displayName}"
        private var previous: List<VisualEffect>? = null
        override fun apply(project: Project): Project = project.withVideoClip(clipId) {
            previous = it.effects
            it.copy(effects = it.effects.map { e -> if (e.id == effect.id) effect else e })
        }
        override fun undo(project: Project): Project = project.withVideoClip(clipId) {
            it.copy(effects = previous ?: it.effects)
        }
    }

    data class ReorderClipEffects(val clipId: String, val fromIndex: Int, val toIndex: Int) : EditCommand {
        override val description = "Reorder effects"
        private var previous: List<VisualEffect>? = null
        override fun apply(project: Project): Project = project.withVideoClip(clipId) { clip ->
            previous = clip.effects
            if (fromIndex !in clip.effects.indices || toIndex !in clip.effects.indices) clip else {
                val list = clip.effects.toMutableList()
                val moved = list.removeAt(fromIndex)
                list.add(toIndex.coerceIn(0, list.size), moved)
                clip.copy(effects = list)
            }
        }
        override fun undo(project: Project): Project = project.withVideoClip(clipId) {
            it.copy(effects = previous ?: it.effects)
        }
    }

    data class SetClipKeyframe(val clipId: String, val propertyName: String, val keyframe: VisualKeyframe) : EditCommand {
        override val description = "Set keyframe"
        private var previousTracks: Map<String, KeyframeTrack>? = null
        override fun apply(project: Project): Project = project.withVideoClip(clipId) { clip ->
            previousTracks = clip.keyframeTracks
            val currentTrack = clip.keyframeTracks[propertyName] ?: KeyframeTrack(propertyName)
            val updatedKeyframes = (currentTrack.keyframes.filterNot { it.id == keyframe.id || it.timeMicros == keyframe.timeMicros } + keyframe)
                .sortedBy { it.timeMicros }
            val newTracks = clip.keyframeTracks + (propertyName to currentTrack.copy(keyframes = updatedKeyframes))
            clip.copy(keyframeTracks = newTracks)
        }
        override fun undo(project: Project): Project = project.withVideoClip(clipId) {
            it.copy(keyframeTracks = previousTracks ?: it.keyframeTracks)
        }
    }

    data class RemoveClipKeyframe(val clipId: String, val propertyName: String, val keyframeId: String) : EditCommand {
        override val description = "Remove keyframe"
        private var previousTracks: Map<String, KeyframeTrack>? = null
        override fun apply(project: Project): Project = project.withVideoClip(clipId) { clip ->
            previousTracks = clip.keyframeTracks
            val currentTrack = clip.keyframeTracks[propertyName] ?: return@withVideoClip clip
            val updatedKeyframes = currentTrack.keyframes.filterNot { it.id == keyframeId }
            val newTracks = if (updatedKeyframes.isEmpty()) {
                clip.keyframeTracks - propertyName
            } else {
                clip.keyframeTracks + (propertyName to currentTrack.copy(keyframes = updatedKeyframes))
            }
            clip.copy(keyframeTracks = newTracks)
        }
        override fun undo(project: Project): Project = project.withVideoClip(clipId) {
            it.copy(keyframeTracks = previousTracks ?: it.keyframeTracks)
        }
    }

    // -- helpers --

    private const val MAX_VOLUME = 4f

    private fun Project.withVideoClip(clipId: String, transform: (Clip) -> Clip): Project =
        copy(
            timeline = timeline.copy(
                videoClips = timeline.videoClips.map { if (it.id == clipId) transform(it) else it },
            ),
        )

    private fun Project.withAudioClip(id: String, transform: (AudioClip) -> AudioClip): Project =
        copy(
            timeline = timeline.copy(
                audioClips = timeline.audioClips.map { if (it.id == id) transform(it) else it },
            ),
        )

    private fun Project.withAudioClips(clips: List<AudioClip>): Project =
        copy(timeline = timeline.copy(audioClips = clips))

    private fun Project.withTextOverlay(id: String, transform: (TextOverlay) -> TextOverlay): Project =
        copy(
            timeline = timeline.copy(
                textOverlays = timeline.textOverlays.map { if (it.id == id) transform(it) else it },
            ),
        )

    private fun Project.withTextOverlays(overlays: List<TextOverlay>): Project =
        copy(timeline = timeline.copy(textOverlays = overlays))
}
