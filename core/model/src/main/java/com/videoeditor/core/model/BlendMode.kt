package com.videoeditor.core.model

import kotlinx.serialization.Serializable

/**
 * Standard creative compositing blend modes implemented in GPU shaders.
 */
@Serializable
enum class BlendMode(val displayName: String) {
    Normal("Normal"),
    Multiply("Multiply"),
    Screen("Screen"),
    Overlay("Overlay"),
    Darken("Darken"),
    Lighten("Lighten"),
    Add("Add"),
    ColorDodge("Color Dodge"),
    ColorBurn("Color Burn"),
    HardLight("Hard Light"),
    SoftLight("Soft Light"),
    Difference("Difference"),
    Exclusion("Exclusion"),
}
