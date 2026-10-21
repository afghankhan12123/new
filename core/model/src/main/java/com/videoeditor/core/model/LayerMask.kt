package com.videoeditor.core.model

import kotlinx.serialization.Serializable

/**
 * Supported geometric and analytical mask shapes for video and photo layers.
 */
@Serializable
enum class MaskType(val displayName: String) {
    Rectangle("Rectangle"),
    RoundedRectangle("Rounded Rectangle"),
    Circle("Circle / Ellipse"),
    LinearGradient("Linear Gradient"),
    Polygon("Regular Polygon"),
}

/**
 * Defines a mask on a visual layer that modulates its composited alpha channel.
 * Operates in normalized layer space (-1.0 .. 1.0) with resolution-independent softness.
 */
@Serializable
data class LayerMask(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: MaskType = MaskType.Rectangle,
    val enabled: Boolean = true,
    val centerX: Float = 0.0f,
    val centerY: Float = 0.0f,
    val scaleX: Float = 1.0f,
    val scaleY: Float = 1.0f,
    val rotationDegrees: Float = 0.0f,
    val cornerRadius: Float = 0.2f,
    val feather: Float = 0.05f,
    val invert: Boolean = false,
    val opacity: Float = 1.0f,
    val polygonSides: Int = 5,
    val keyframeTracks: Map<String, KeyframeTrack> = emptyMap(),
) {
    fun evaluateProperty(name: String, timeMicros: Long, fallback: Float): Float {
        val track = keyframeTracks[name] ?: return fallback
        return track.evaluate(timeMicros, fallback)
    }

    fun evaluatedMaskAt(timeMicros: Long): LayerMask {
        if (keyframeTracks.isEmpty()) return this
        return copy(
            centerX = evaluateProperty("centerX", timeMicros, centerX),
            centerY = evaluateProperty("centerY", timeMicros, centerY),
            scaleX = evaluateProperty("scaleX", timeMicros, scaleX),
            scaleY = evaluateProperty("scaleY", timeMicros, scaleY),
            rotationDegrees = evaluateProperty("rotationDegrees", timeMicros, rotationDegrees),
            opacity = evaluateProperty("opacity", timeMicros, opacity),
            feather = evaluateProperty("feather", timeMicros, feather),
        )
    }
}
