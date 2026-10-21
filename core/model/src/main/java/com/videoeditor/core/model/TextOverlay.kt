package com.videoeditor.core.model

import kotlinx.serialization.Serializable

enum class TextAlignment {
    Start,
    Center,
    End,
}

enum class TextAnimation {
    None,
    FadeIn,
    FadeOut,
    SlideIn,
    SlideOut,
    ScaleIn,
    ScaleOut,
}

/**
 * A text element rendered on top of the video. Position and size are
 * normalized to the canvas so they survive resolution changes.
 */
@Serializable
data class TextOverlay(
    val id: String,
    val text: String,
    val fontId: String = "default",
    val fontSizeSp: Float = 32f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val opacity: Float = 1f,
    val alignment: TextAlignment = TextAlignment.Center,
    val hasShadow: Boolean = false,
    val shadowColorArgb: Int = 0xFF000000.toInt(),
    val hasOutline: Boolean = false,
    val outlineColorArgb: Int = 0xFF000000.toInt(),
    val outlineWidthPx: Float = 2f,
    val hasBackground: Boolean = false,
    val backgroundColorArgb: Int = 0x80000000.toInt(),
    /** Normalized center position on the canvas, 0..1. */
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
    val rotationDegrees: Float = 0f,
    val scale: Float = 1f,
    val enterAnimation: TextAnimation = TextAnimation.None,
    val exitAnimation: TextAnimation = TextAnimation.None,
    /** Timeline placement in microseconds. */
    val startMicros: Long = 0L,
    val durationMicros: Long = 3_000_000L,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Null retains legacy unwrapped captions; new captions wrap to a fraction of canvas width. */
    val maxWidthFraction: Float? = null,
)
