package com.videoeditor.core.model.render

import com.videoeditor.core.model.*

/** Immutable, non-persisted render IR. All intervals are half-open project microseconds. */
data class ProjectRenderPlan private constructor(
    val project: Project,
    val durationMicros: Long,
    val transitions: List<ResolvedTransition>,
    /** Bottom to top. Two reusable lanes serve every non-overlapping transition. */
    val layers: List<OverlayClip>,
) {
    fun transitionAt(timeUs: Long): ResolvedTransition? =
        transitions.firstOrNull { timeUs >= it.startMicros && timeUs < it.endMicros }

    fun transitionLayer(join: ResolvedTransition, incoming: Boolean): OverlayClip {
        val clip = if (incoming) join.incoming else join.outgoing
        return OverlayClip(
            id = if (incoming) INCOMING_LANE else OUTGOING_LANE,
            mediaRefId = clip.mediaRefId,
            timelineStartMicros = join.startMicros,
            timelineDurationMicros = join.transition.durationMicros,
            sourceStartMicros = if (incoming) join.incomingSourceAt(join.startMicros) else join.outgoingSourceAt(join.startMicros),
            sourceEndMicros = if (incoming) join.incomingSourceAt(join.endMicros) else join.outgoingSourceAt(join.endMicros),
            playbackSpeed = clip.playbackSpeed,
            muted = true,
        )
    }

    /** Decoder topology excludes parameters evaluated by the session's render passes. */
    fun hasSameTopology(other: ProjectRenderPlan): Boolean {
        fun Project.topology(): Project = copy(
            name = "", updatedAtMillis = 0L, exportPreferences = ExportPreferences(),
            canvas = canvas.copy(scaleMode = ScaleMode.Fit, backgroundColorArgb = 0xFF000000.toInt()),
            timeline = timeline.copy(
                videoClips = timeline.videoClips.map { it.copy(filter = FilterSettings(), colorAdjustments = ColorAdjustments(), effects = emptyList(), keyframeTracks = emptyMap(),
                    transform = it.transform.copy(scale = 1f, offsetX = 0f, offsetY = 0f)) },
                textOverlays = emptyList(), stickers = emptyList(),
                overlayClips = timeline.overlayClips.sortedBy { it.id }.map { it.copy(
                    opacity = 1f, zIndex = 0, scaleMode = ScaleMode.Fit,
                    transform = it.transform.copy(scale = 1f, offsetX = 0f, offsetY = 0f, rotationDegrees = 0),
                    effects = emptyList(), mask = null, chromaKey = null,
                    blendMode = BlendMode.Normal, keyframeTracks = emptyMap(),
                ) },
            ),
        )
        // IDs and source windows remain part of equality; no persistence contract changes.
        return project.topology() == other.project.topology() &&
            layers.map { it.id }.toSet() == other.layers.map { it.id }.toSet()
    }

    companion object {
        const val OUTGOING_LANE = "render:transition:outgoing"
        const val INCOMING_LANE = "render:transition:incoming"
        fun isTransitionLane(id: String) = id == OUTGOING_LANE || id == INCOMING_LANE

        fun compile(project: Project): ProjectRenderPlan {
            val duration = project.timeline.renderedDurationMicros
            require(project.canvas.width > 0 && project.canvas.height > 0) { "Invalid canvas dimensions" }
            require(duration >= 0L) { "Invalid project duration" }
            var end = 0L
            project.timeline.videoClips.forEach { clip ->
                require(clip.playbackSpeed.isFinite() && clip.playbackSpeed > 0f) { "Invalid speed: ${clip.id}" }
                require(clip.timelineDurationMicros > 0L && clip.timelineStartMicros == end) { "Non-contiguous primary clip: ${clip.id}" }
                val media = requireNotNull(project.mediaRef(clip.mediaRefId)) { "Missing media: ${clip.mediaRefId}" }
                require(clip.isPhoto || (clip.sourceStartMicros >= 0L && clip.sourceEndMicros > clip.sourceStartMicros &&
                    (media.durationMicros == null || clip.sourceEndMicros <= media.durationMicros!!))) { "Invalid source range: ${clip.id}" }
                end = Math.addExact(clip.timelineStartMicros, clip.timelineDurationMicros)
            }
            val joins = project.resolveTransitions()
            val provisional = ProjectRenderPlan(project, duration, joins, emptyList())
            val transitionLanes = joins.firstOrNull()?.let { join ->
                listOf(false, true).map { incoming -> provisional.transitionLayer(join, incoming) }
            }.orEmpty()
            val overlays = project.timeline.overlayClips.filter { it.timelineEndMicros > 0 && it.timelineStartMicros < duration }
                .map { layer ->
                    val visibleDuration = minOf(layer.timelineDurationMicros, duration - layer.timelineStartMicros,
                        if (layer.isPhoto) Long.MAX_VALUE else if (layer.playbackSpeed > 0f)
                            (layer.sourceDurationMicros.toDouble() / layer.playbackSpeed).toLong() else 0L)
                    layer.copy(timelineDurationMicros = visibleDuration, sourceEndMicros = if (layer.isPhoto) layer.sourceEndMicros else
                        minOf(layer.sourceEndMicros, Math.addExact(layer.sourceStartMicros,
                            ResolvedTransition.sourceDelta(visibleDuration, layer.playbackSpeed))))
                }.sortedWith(compareBy<OverlayClip> { it.zIndex }.thenBy { it.id })
            overlays.forEach { layer ->
                require(layer.timelineStartMicros >= 0 && layer.timelineDurationMicros > 0) { "Invalid overlay interval: ${layer.id}" }
                require(layer.playbackSpeed.isFinite() && layer.playbackSpeed > 0f) { "Invalid overlay speed: ${layer.id}" }
                val media = requireNotNull(project.mediaRef(layer.mediaRefId)) { "Missing overlay media: ${layer.mediaRefId}" }
                if (!layer.isPhoto) require(layer.sourceStartMicros >= 0 && layer.sourceEndMicros > layer.sourceStartMicros &&
                    (media.durationMicros == null || layer.sourceEndMicros <= media.durationMicros!!)) { "Invalid overlay source range: ${layer.id}" }
                require(layer.transform.scale.isFinite() && layer.transform.offsetX.isFinite() && layer.transform.offsetY.isFinite()) { "Invalid overlay geometry: ${layer.id}" }
            }
            require(transitionLanes.size + overlays.size + 1 <= 8) { "At most ${7 - transitionLanes.size} picture-in-picture layers are available with this render plan" }
            return provisional.copy(layers = transitionLanes + overlays)
        }
    }
}
