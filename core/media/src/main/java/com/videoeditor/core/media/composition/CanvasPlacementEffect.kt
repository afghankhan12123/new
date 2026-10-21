package com.videoeditor.core.media.composition

import android.graphics.Matrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.videoeditor.core.model.Clip
import com.videoeditor.core.model.render.ItemTimeMapping

/** Resolution-independent canvas placement. Animation keys remain local to the authored clip. */
@UnstableApi
internal class CanvasPlacementEffect(
    private val scale: Float, private val x: Float, private val y: Float,
    private val timeMapping: ItemTimeMapping = ItemTimeMapping(0),
    private val effectOriginMicros: Long = timeMapping.itemStartMicros,
    private val liveClip: (() -> Clip)? = null,
) : MatrixTransformation {
    private val matrix = Matrix()
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val clip = liveClip?.invoke()
        val transform = clip?.transform
        val local = timeMapping.clipLocalTime(presentationTimeUs, effectOriginMicros)
        fun value(name: String, fallback: Float) = clip?.keyframeTracks?.get(name)?.evaluate(local, fallback) ?: fallback
        val rotation = value("rotationDegrees", transform?.rotationDegrees?.toFloat() ?: 0f) - (transform?.rotationDegrees ?: 0)
        matrix.setRotate(-rotation)
        val effectiveScale = value("scale", transform?.scale ?: scale).coerceIn(.01f, 8f)
        matrix.postScale(effectiveScale, effectiveScale)
        matrix.postTranslate(value("positionX", transform?.offsetX ?: x), -value("positionY", transform?.offsetY ?: y))
        return matrix
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int) = liveClip == null && scale == 1f && x == 0f && y == 0f
}
