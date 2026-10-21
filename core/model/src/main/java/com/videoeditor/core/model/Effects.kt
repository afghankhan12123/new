package com.videoeditor.core.model

import kotlinx.serialization.Serializable

/** Normalized crop rectangle in the range 0..1 relative to the source frame. */
@Serializable
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun isValid(): Boolean =
        left < right && top < bottom &&
            left >= 0f && top >= 0f && right <= 1f && bottom <= 1f
}

/**
 * Geometric transformation applied to a clip. Offsets are normalized to the
 * canvas (-1..1) so they are resolution independent.
 */
@Serializable
data class VisualTransform(
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val crop: CropRect? = null,
)

/** A named filter plus its strength. [filterId] null means "no filter". */
@Serializable
data class FilterSettings(
    val filterId: String? = null,
    val intensity: Float = 1f,
)

/**
 * Per-clip color adjustments. All values are normalized around 0 (no change);
 * the exact numeric range is defined by the rendering pipeline so that preview
 * and export share one interpretation.
 */
@Serializable
data class ColorAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val sharpen: Float = 0f,
    val vignette: Float = 0f,
    val hue: Float = 0f,
) {
    fun isIdentity(): Boolean =
        brightness == 0f && contrast == 0f && saturation == 0f &&
            exposure == 0f && highlights == 0f && shadows == 0f &&
            temperature == 0f && tint == 0f && sharpen == 0f && vignette == 0f && hue == 0f
}
