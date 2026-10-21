package com.videoeditor.core.media.composition

import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.VideoCompositorSettings
import com.videoeditor.core.model.*
import com.videoeditor.core.model.render.ProjectRenderPlan

/** Input identity, clock ownership, project-time visibility and geometry shared by both adapters. */
@UnstableApi
class OverlayCompositorSettings(
    private val layers: List<OverlayClip?>,
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    private val transitions: List<ResolvedTransition> = emptyList(),
    private val renderState: RenderPlanState? = null,
) : VideoCompositorSettings {
    init {
        require(canvasWidth > 0 && canvasHeight > 0 && layers.isNotEmpty())
    }
    val timingPrimaryInputId get() = layers.indexOfLast { it == null }.coerceAtLeast(0)
    val inputCount get() = layers.size
    private fun joins() = renderState?.plan?.transitions ?: transitions
    private fun activeTransition(timeUs: Long) = joins().firstOrNull { timeUs >= it.startMicros && timeUs < it.endMicros }
    fun getLayer(inputId: Int): OverlayClip? = layers.getOrNull(inputId)
    fun stackOrder(inputId: Int): Int {
        val id = layers.getOrNull(inputId)?.id ?: return -1
        return renderState?.plan?.layers?.indexOfFirst { it.id == id } ?: (layers.size - inputId)
    }
    private fun layerAt(inputId: Int, timeUs: Long): OverlayClip? {
        val original = layers.getOrNull(inputId) ?: return null
        val plan = renderState?.plan ?: return original
        return if (ProjectRenderPlan.isTransitionLane(original.id)) {
            plan.transitionAt(timeUs)?.let { plan.transitionLayer(it, original.id == ProjectRenderPlan.INCOMING_LANE) }
        } else plan.layers.firstOrNull { it.id == original.id }
    }
    private fun transitionFor(inputId: Int, timeUs: Long): ResolvedTransition? {
        val id = layers.getOrNull(inputId)?.id ?: return null
        if (ProjectRenderPlan.isTransitionLane(id)) return activeTransition(timeUs)
        return transitions.firstOrNull { id == transitionLayerId(it.transition.id, false) || id == transitionLayerId(it.transition.id, true) }
    }
    fun isIncoming(inputId: Int, timeUs: Long): Boolean {
        val id = layers.getOrNull(inputId)?.id
        return id == ProjectRenderPlan.INCOMING_LANE || transitionFor(inputId, timeUs)?.let { id == transitionLayerId(it.transition.id, true) } == true
    }
    /** Zero means ordinary content; transition ordinals are offset by one for the shader. */
    fun isTransitionInput(inputId: Int) = layers.getOrNull(inputId)?.id?.let { ProjectRenderPlan.isTransitionLane(it) || it.startsWith("transition:") } == true
    fun transitionKind(inputId: Int, timeUs: Long) = transitionFor(inputId, timeUs)?.transition?.type?.ordinal?.plus(1) ?: 0
    fun transitionProgress(inputId: Int, timeUs: Long) = transitionFor(inputId, timeUs)?.progressAt(timeUs) ?: 0f
    fun getBlendMode(inputId: Int): BlendMode = layers.getOrNull(inputId)?.blendMode ?: BlendMode.Normal
    fun blendModeAt(inputId: Int, timeUs: Long) = layerAt(inputId, timeUs)?.blendMode ?: BlendMode.Normal
    /** Hidden optional streams never hold the primary video hostage at startup, EOS or item boundaries. */
    fun isInputRequired(inputId: Int, timeUs: Long): Boolean = inputId == timingPrimaryInputId ||
        layerAt(inputId, timeUs)?.let { timeUs >= it.timelineStartMicros && timeUs < it.timelineEndMicros &&
            getOverlaySettings(inputId, timeUs).alphaScale > 0f } == true

    fun inputStartUs(inputId: Int, timeUs: Long): Long = layerAt(inputId, timeUs)?.timelineStartMicros ?: 0L
    fun inputEndUs(inputId: Int, timeUs: Long): Long = layerAt(inputId, timeUs)?.timelineEndMicros ?: Long.MAX_VALUE

    override fun getOutputSize(inputSizes: List<Size>) = Size(canvasWidth, canvasHeight)
    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        if (inputId == timingPrimaryInputId) return OverlaySettings.Builder()
            .setAlphaScale(if (activeTransition(presentationTimeUs) == null) 1f else 0f).build()
        val layer = layerAt(inputId, presentationTimeUs) ?: return OverlaySettings.Builder().setAlphaScale(0f).build()
        val local = (presentationTimeUs - layer.timelineStartMicros).coerceAtLeast(0L)
        val transform = layer.transform
        fun animated(name: String, fallback: Float) = layer.keyframeTracks[name]?.evaluate(local, fallback) ?: fallback
        var alpha = layer.evaluateOpacityAt(presentationTimeUs)
        var scale = animated("scale", transform.scale).coerceIn(.01f, 8f)
        var x = animated("positionX", transform.offsetX)
        var y = animated("positionY", transform.offsetY)
        val transition = transitionFor(inputId, presentationTimeUs)
        if (transition != null) {
            val incoming = isIncoming(inputId, presentationTimeUs)
            val p = transition.progressAt(presentationTimeUs)
            when (transition.transition.type) {
                TransitionType.CrossDissolve -> alpha = if (incoming) p else 1f
                TransitionType.FadeThroughBlack -> alpha = if (incoming) ((p - .5f) * 2f).coerceIn(0f, 1f) else 1f
                TransitionType.FadeThroughWhite -> alpha = if (incoming && p < .5f) 0f else 1f
                TransitionType.SlideLeft -> x = if (incoming) (1f - p) * 2f else 0f
                TransitionType.SlideRight -> x = if (incoming) -(1f - p) * 2f else 0f
                TransitionType.Push -> x = if (incoming) (1f - p) * 2f else -p * 2f
                TransitionType.WipeLeft, TransitionType.WipeRight, TransitionType.WipeUp,
                TransitionType.WipeDown, TransitionType.Split -> alpha = 1f
                TransitionType.ZoomDissolve -> { alpha = if (incoming) p else 1f; scale *= if (incoming) .8f + .2f * p else 1f + .25f * p }
                TransitionType.SmoothZoom -> {
                    val smooth = p * p * (3f - 2f * p)
                    alpha = if (incoming) smooth else 1f
                    scale *= if (incoming) 2f - smooth else 1f + smooth
                }
            }
        }
        if (presentationTimeUs < layer.timelineStartMicros || presentationTimeUs >= layer.timelineEndMicros) alpha = 0f
        return OverlaySettings.Builder().setAlphaScale(alpha.coerceIn(0f, 1f))
            .setScale(scale, scale)
            .setRotationDegrees(-animated("rotation", transform.rotationDegrees.toFloat()))
            .setBackgroundFrameAnchor(x.coerceIn(-1f, 1f), -y.coerceIn(-1f, 1f))
            .setOverlayFrameAnchor(0f, 0f).build()
    }
}

internal fun transitionLayerId(id: String, incoming: Boolean) = "transition:$id:${if (incoming) "incoming" else "outgoing"}"
