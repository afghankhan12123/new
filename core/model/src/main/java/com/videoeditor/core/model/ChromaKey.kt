package com.videoeditor.core.model

import kotlinx.serialization.Serializable

/**
 * Chroma key configuration for background color removal on visual layers.
 * All operations run deterministically in GPU fragment shaders.
 */
@Serializable
data class ChromaKeySettings(
    val enabled: Boolean = false,
    /** Key color in ARGB format (e.g. 0xFF00FF00 for green screen). */
    val keyColor: Int = 0xFF00FF00.toInt(),
    /** Tolerance threshold in normalized chroma distance (0.0 .. 1.0). */
    val tolerance: Float = 0.35f,
    /** Edge transition smoothness (0.0 .. 1.0). */
    val softness: Float = 0.15f,
    /** Suppression of key color reflection / fringe on foreground subject (0.0 .. 1.0). */
    val spillSuppression: Float = 0.60f,
    /** Overall layer opacity scale (0.0 .. 1.0). */
    val opacity: Float = 1.0f,
) {
    val keyRed: Float get() = ((keyColor shr 16) and 0xFF) / 255.0f
    val keyGreen: Float get() = ((keyColor shr 8) and 0xFF) / 255.0f
    val keyBlue: Float get() = (keyColor and 0xFF) / 255.0f

    companion object {
        const val PRESET_GREEN = 0xFF00FF00.toInt()
        const val PRESET_BLUE = 0xFF0000FF.toInt()
    }
}
