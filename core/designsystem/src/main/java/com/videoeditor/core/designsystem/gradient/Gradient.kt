package com.videoeditor.core.designsystem.gradient

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.videoeditor.core.designsystem.color.VEColor

/**
 * The signature Cyan -> Blue -> Purple gradient. Reserved for primary actions:
 * New Project, Create, Export Video, and major completion actions. Not applied
 * to every control.
 */
object VEGradient {

    val primary: Brush
        get() = Brush.linearGradient(
            colors = listOf(
                VEColor.GradientStart,
                VEColor.GradientMiddle,
                VEColor.GradientEnd,
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )

    /** Horizontal variant for wide buttons. */
    val primaryHorizontal: Brush
        get() = Brush.horizontalGradient(
            colors = listOf(
                VEColor.GradientStart,
                VEColor.GradientMiddle,
                VEColor.GradientEnd,
            ),
        )

    /** Solid blue-purple used for the circular Create button. */
    val createButton: Brush
        get() = Brush.linearGradient(
            colors = listOf(VEColor.GradientMiddle, VEColor.GradientEnd),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )

    fun solid(color: Color): Brush = Brush.linearGradient(listOf(color, color))
}
