package com.videoeditor.core.model

import kotlinx.serialization.Serializable

enum class FrameRate(val fps: Int) {
    FPS_24(24),
    FPS_30(30),
    FPS_60(60),
}

/**
 * Preset aspect ratios offered to the user. [widthRatio]/[heightRatio] describe
 * the canvas orientation; the actual pixel size is derived from the export
 * resolution.
 */
enum class AspectRatioPreset(
    val label: String,
    val hint: String,
    val widthRatio: Int,
    val heightRatio: Int,
) {
    Original("Original", "Source aspect ratio", 0, 0),
    Portrait9x16("9:16", "TikTok, Reels, Shorts", 9, 16),
    Landscape16x9("16:9", "YouTube", 16, 9),
    Square1x1("1:1", "Square posts", 1, 1),
    Portrait4x5("4:5", "Portrait posts", 4, 5),
    Portrait3x4("3:4", "Portrait posts", 3, 4),
}

/** How source content is mapped into the canvas. */
enum class ScaleMode {
    Fit,
    Fill,
    Crop,
}

/**
 * Canvas (output frame) configuration. When [aspectRatio] is [AspectRatioPreset.Original]
 * the effective size is taken from the first video clip at render time.
 */
@Serializable
data class CanvasSettings(
    val aspectRatio: AspectRatioPreset = AspectRatioPreset.Original,
    val scaleMode: ScaleMode = ScaleMode.Fit,
    val backgroundColorArgb: Int = 0xFF000000.toInt(),
    val width: Int = 1920,
    val height: Int = 1080,
)
